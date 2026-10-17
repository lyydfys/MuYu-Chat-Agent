package com.muyuchat.mca

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/** Captures a canonical, non-symlink regular file into an exact reusable package descriptor. */
internal fun captureImageExecutionAssetDescriptor(
    bundleRoot: File,
    source: File,
    installedPin: ImageProfileAsset? = null
): TextualInversionExecutionAssetDescriptor {
    val root = bundleRoot.canonicalFile
    val requested = source.absoluteFile
    require(!Files.isSymbolicLink(requested.toPath())) {
        "Prompt execution asset must not be a symbolic link: ${requested.path}"
    }
    val file = requested.canonicalFile
    require(file.path.startsWith(root.path + File.separator)) {
        "Prompt execution asset escapes its bundle root: ${file.path}"
    }
    fun attributes(): BasicFileAttributes = Files.readAttributes(
        file.toPath(),
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS
    )
    val before = attributes()
    require(before.isRegularFile && !before.isSymbolicLink && before.size() > 0L) {
        "Prompt execution asset is missing, empty, or not a regular file: ${file.path}"
    }
    val label = file.relativeTo(root).invariantSeparatorsPath
    val sha256 = installedPin?.let { pin ->
        require(pin.relativePath.replace('\\', '/').trim() == label &&
            pin.sizeBytes == before.size() &&
            TextualInversionContract.SHA256_PATTERN.matches(pin.fingerprint.lowercase())
        ) { "Prompt execution asset $label differs from its installed profile pin." }
        pin.fingerprint.lowercase()
    } ?: file.sha256ForProfile()
    val after = attributes()
    require(after.isRegularFile && !after.isSymbolicLink &&
        before.fileKey()?.toString() == after.fileKey()?.toString() &&
        before.size() == after.size() &&
        before.lastModifiedTime() == after.lastModifiedTime()
    ) { "Prompt execution asset changed while it was being captured: ${file.path}" }
    installedPin?.let { pin ->
        require(pin.sizeBytes == after.size()) {
            "Prompt execution asset $label differs from its installed profile pin."
        }
    }
    return TextualInversionExecutionAssetDescriptor(
        label = label,
        path = file.path,
        sizeBytes = after.size(),
        sha256 = sha256,
        fileKey = after.fileKey()?.toString(),
        lastModifiedMillis = after.lastModifiedTime().toMillis()
    )
}

/**
 * Verifies every concrete text-encoder file that authorizes direct Simplified Chinese input.
 *
 * This deliberately does not reuse a downloaded profile pin: direct multilingual admission is a
 * semantic claim about the exact bytes native will consume, so the SHA-256 is recomputed for the
 * requested file every time this helper is called. Callers choose the request boundary; this
 * helper performs no caching or filesystem mutation.
 */
internal fun verifyNativeMultilingualTextEncoderEvidenceAsset(
    bundleRoot: File,
    profile: ImageExecutionProfile
) {
    // An unsigned, expired, or malformed multilingual declaration is deliberately retained for
    // diagnostics, but it must fall back to the English-dominant product path. Only the verified
    // closure authorizes direct Chinese input and therefore requires this strict byte check.
    if (!profile.hasVerifiedNativeSimplifiedChineseTextEncoder()) return

    val evidenceAssets = requireNotNull(profile.textEncoderLanguage?.evidence).consumedAssets()
    try {
        val root = bundleRoot.canonicalFile
        require(root.isDirectory) { "Text-encoder evidence bundle root is missing." }
        evidenceAssets.forEach { evidenceAsset ->
            val expectedPath = evidenceAsset.relativePath.replace('\\', '/').trim()
            require(expectedPath.isNotBlank()) { "Text-encoder evidence asset path is blank." }

            // Check every requested component before canonicalization so an in-tree symlink cannot
            // be accepted merely because its target resolves below the bundle root.
            var requestedPath = root.toPath()
            expectedPath.split('/').forEach { segment ->
                requestedPath = requestedPath.resolve(segment)
                require(!Files.isSymbolicLink(requestedPath)) {
                    "Text-encoder evidence asset path must not traverse a symbolic link: $requestedPath"
                }
            }
            val requested = requestedPath.toFile().absoluteFile
            val file = requested.canonicalFile
            require(file.path.startsWith(root.path + File.separator)) {
                "Text-encoder evidence asset escapes its bundle root: ${file.path}"
            }
            val actualPath = file.relativeTo(root).invariantSeparatorsPath
            require(actualPath == expectedPath) {
                "Text-encoder evidence asset path differs from its profile evidence pin."
            }

            fun attributes(): BasicFileAttributes = Files.readAttributes(
                file.toPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS
            )

            val before = attributes()
            require(before.isRegularFile && !before.isSymbolicLink && before.size() > 0L) {
                "Text-encoder evidence asset is missing, empty, or not a regular file: ${file.path}"
            }
            require(before.size() == evidenceAsset.sizeBytes) {
                "Text-encoder evidence asset size differs from its profile evidence pin."
            }
            val actualSha256 = file.sha256ForProfile()
            val after = attributes()
            require(after.isRegularFile && !after.isSymbolicLink &&
                before.fileKey()?.toString() == after.fileKey()?.toString() &&
                before.size() == after.size() &&
                before.lastModifiedTime() == after.lastModifiedTime()
            ) {
                "Text-encoder evidence asset changed while its SHA-256 was verified: ${file.path}"
            }
            require(after.size() == evidenceAsset.sizeBytes &&
                actualSha256.equals(evidenceAsset.fingerprint, ignoreCase = true)
            ) {
                "Text-encoder evidence asset bytes differ from its profile evidence pin."
            }
        }
    } catch (error: IOException) {
        throw IllegalArgumentException(
            "Text-encoder evidence asset is missing or cannot be read.",
            error
        )
    }
}

/** Converts raw filesystem failures into the product error returned by UI and Local API flows. */
internal fun requireNativeMultilingualTextEncoderEvidenceAsset(
    bundleRoot: File,
    profile: ImageExecutionProfile
) {
    try {
        verifyNativeMultilingualTextEncoderEvidenceAsset(bundleRoot, profile)
    } catch (error: Exception) {
        throw LocalImageProductContractException(
            code = "invalid_image_prompt_language_evidence",
            message = "原生中文文本编码器证据文件不完整或校验失败，尚未启动图片生成。"
        )
    }
}
