package com.muyuchat.mca

import com.muyuchat.core.download.ImageEngineIntegrityMetadataStatus
import com.muyuchat.core.download.ModelBundleComponentAudit
import com.muyuchat.core.download.ModelBundleComponentVerification
import com.muyuchat.core.download.ModelBundleComponentVerificationStatus
import com.muyuchat.core.download.ModelBundleAuditVerificationResult
import com.muyuchat.core.download.ModelBundleInstaller
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale
import org.json.JSONObject

internal object LocalImageBundleContract {
    private val qwenImage21AuditVerifier = QwenImage21BundleAuditVerifier()

    val qwenImage21RequiredComponentPaths: List<String> = listOf(
        "dit.mnn",
        "dit.mnn.weight",
        "text_encoder/llm.mnn",
        "text_encoder/llm.mnn.weight",
        "text_encoder/embeddings_int4.bin",
        "text_encoder/tokenizer.txt",
        "text_encoder/te_config.json",
        "text_encoder/te_llm_config.json",
        "text_encoder/llm_config.json",
        "txt_in.mnn",
        "txt_in.mnn.weight",
        "img_in.mnn",
        "img_in.mnn.weight",
        "vae_decoder.mnn"
    )

    val sanaRequiredComponentPaths: List<String> = listOf(
        "config.json",
        "llm/config.json",
        "llm/llm_config.json",
        "llm/llm.mnn",
        "llm/llm.mnn.weight",
        "llm/tokenizer.txt",
        "llm/meta_queries.mnn",
        "connector.mnn",
        "connector.mnn.weight",
        "projector.mnn",
        "projector.mnn.weight",
        "transformer.mnn",
        "transformer.mnn.weight",
        "vae_decoder.mnn",
        "vae_decoder.mnn.weight",
        "vae_encoder.mnn",
        "vae_encoder.mnn.weight"
    )

    private val stableDiffusion15RequiredComponentPaths = listOf(
        "text_encoder.mnn",
        "text_encoder.mnn.weight",
        "unet.mnn",
        "unet.mnn.weight",
        "vae_decoder.mnn",
        "vae_decoder.mnn.weight"
    )

    private const val TOKENIZER_REQUIREMENT =
        "tokenizer.mtok/tokenizer.txt or vocab.json + merges.txt"

    fun inspectMnnBundle(
        bundleRoot: File?,
        primaryFile: File,
        family: LocalImageModelFamily
    ): MnnBundleContractCheck {
        val candidates = buildList {
            primaryFile.parentFile?.takeIf { it.isDirectory }?.let(::add)
            bundleRoot?.takeIf { it.isDirectory }?.let(::add)
        }.distinctBy { root ->
            runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
        }
        if (candidates.isEmpty()) {
            return MnnBundleContractCheck(
                root = null,
                missingComponents = expectedComponents(family)
            )
        }

        val checks = candidates.map { root ->
            MnnBundleContractCheck(
                root = root,
                missingComponents = missingComponents(root, family),
                integrityMessage = integrityMessage(root)
            )
        }
        return checks.firstOrNull { it.missingComponents.isEmpty() && it.integrityMessage == null }
            ?: checks.minWithOrNull(compareBy<MnnBundleContractCheck> { it.missingComponents.size }
                .thenBy { if (it.integrityMessage == null) 0 else 1 })
            ?: MnnBundleContractCheck(root = null, missingComponents = expectedComponents(family))
    }

    fun inspectQwenImage21Bundle(
        bundleRoot: File?,
        primaryFile: File,
        forceFullIntegrityRecheck: Boolean = false
    ): QwenImage21BundleContractCheck {
        val candidates = buildList {
            primaryFile.parentFile?.takeIf { it.isDirectory }?.let(::add)
            bundleRoot?.takeIf { it.isDirectory }?.let(::add)
        }.distinctBy { root ->
            runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
        }
        if (candidates.isEmpty()) {
            return QwenImage21BundleContractCheck(
                root = null,
                missingComponents = qwenImage21RequiredComponentPaths
            )
        }
        val checks = candidates.map { root ->
            val canonicalRoot = runCatching { root.canonicalFile }.getOrNull()
            val missing = qwenImage21RequiredComponentPaths.filter { relativePath ->
                val component = canonicalRoot?.safeQwenDescendant(relativePath)
                component == null || !component.isFile || !component.canRead() || component.length() <= 0L
            }
            QwenImage21BundleContractCheck(
                root = root,
                missingComponents = missing,
                integrityMessage = qwenImage21IntegrityMessage(root, forceFullIntegrityRecheck)
            )
        }
        return checks.firstOrNull { it.missingComponents.isEmpty() && it.integrityMessage == null }
            ?: checks.minWithOrNull(compareBy<QwenImage21BundleContractCheck> { it.missingComponents.size }
                .thenBy { if (it.integrityMessage == null) 0 else 1 })
            ?: QwenImage21BundleContractCheck(
                root = null,
                missingComponents = qwenImage21RequiredComponentPaths
            )
    }

    private fun qwenImage21IntegrityMessage(root: File, forceFullIntegrityRecheck: Boolean): String? {
        val verification = qwenImage21AuditVerifier.verify(
            bundleRoot = root,
            forceFullRecheck = forceFullIntegrityRecheck
        ).verification
        if (!verification.auditFile.isFile || !verification.auditReadable || verification.components.isEmpty()) {
            return "Qwen-Image-2.1 has no readable download integrity record. " +
                "Download the complete recommended package again, then retry."
        }

        integrityMessage(verification)?.let { return it }

        val auditedByPath = verification.components.associateBy { component ->
            component.audit.relativePath.replace('\\', '/')
        }
        val missingAuditPaths = qwenImage21RequiredComponentPaths.filterNot(auditedByPath::containsKey)
        if (missingAuditPaths.isNotEmpty()) {
            return "Qwen-Image-2.1 integrity record is incomplete: " +
                "${missingAuditPaths.joinToString(", ")}. Download the complete recommended package again."
        }

        val publisherUnverified = qwenImage21RequiredComponentPaths.filter { path ->
            val component = auditedByPath.getValue(path)
            component.status != ModelBundleComponentVerificationStatus.MATCHED_SOURCE_SHA256 ||
                component.audit.sourceMetadataStatus != ImageEngineIntegrityMetadataStatus.SOURCE_SHA256
        }
        if (publisherUnverified.isNotEmpty()) {
            return "Qwen-Image-2.1 publisher SHA-256 verification is unavailable for: " +
                "${publisherUnverified.joinToString(", ")}. Download the complete recommended package again."
        }
        return null
    }

    private fun File.safeQwenDescendant(relativePath: String): File? {
        if (relativePath.isBlank() || relativePath.startsWith('/') ||
            Regex("^[A-Za-z]:").containsMatchIn(relativePath) ||
            relativePath.split('/', '\\').any { it.isBlank() || it == "." || it == ".." }
        ) return null
        val canonicalRoot = runCatching { canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching {
            File(canonicalRoot, relativePath.replace('/', File.separatorChar)).canonicalFile
        }.getOrNull() ?: return null
        return candidate.takeIf { it.path.startsWith(canonicalRoot.path + File.separator) }
    }

    private fun integrityMessage(root: File): String? {
        val verification = ModelBundleInstaller().verifyInstalledBundle(root)
        return integrityMessage(verification)
    }

    private fun integrityMessage(verification: ModelBundleAuditVerificationResult): String? {
        if (!verification.auditFile.isFile) return null
        if (!verification.auditReadable) {
            return "Downloaded bundle integrity audit is unreadable; download it again."
        }
        val changed = verification.components.filter { component ->
            component.status !in setOf(
                ModelBundleComponentVerificationStatus.MATCHED_SOURCE_SHA256,
                ModelBundleComponentVerificationStatus.MATCHED_OBSERVED_DIGEST
            )
        }
        if (changed.isNotEmpty()) {
            return "Downloaded bundle integrity audit failed: " +
                changed.joinToString(", ") { "${it.audit.relativePath} (${it.status.name})" } + "."
        }
        val sourceUnknown = verification.components.filter {
            it.audit.sourceMetadataStatus != ImageEngineIntegrityMetadataStatus.SOURCE_SHA256
        }
        return sourceUnknown.takeIf { it.isNotEmpty() }?.let { unknown ->
            "Publisher SHA-256 is unavailable for: " +
                unknown.joinToString(", ") { it.audit.relativePath } +
                ". Local SHA-256 was recorded after download, but source verification is unavailable."
        }
    }

    private fun expectedComponents(family: LocalImageModelFamily): List<String> =
        if (family == LocalImageModelFamily.SANA) {
            sanaRequiredComponentPaths
        } else {
            stableDiffusion15RequiredComponentPaths + TOKENIZER_REQUIREMENT
        }

    private fun missingComponents(root: File, family: LocalImageModelFamily): List<String> {
        if (family == LocalImageModelFamily.SANA) {
            return sanaRequiredComponentPaths.filterNot { path -> root.hasNonEmptyFile(path) }
        }

        return buildList {
            addAll(stableDiffusion15RequiredComponentPaths.filterNot { path -> root.hasNonEmptyFile(path) })
            val hasTokenizer = root.hasNonEmptyFile("tokenizer.mtok") ||
                root.hasNonEmptyFile("tokenizer.txt") ||
                (root.hasNonEmptyFile("vocab.json") && root.hasNonEmptyFile("merges.txt"))
            if (!hasTokenizer) add(TOKENIZER_REQUIREMENT)
        }
    }

    private fun File.hasNonEmptyFile(relativePath: String): Boolean {
        val file = File(this, relativePath.replace('/', File.separatorChar))
        return file.isFile && file.length() > 0L
    }
}

/**
 * Process-local memoization for repeated execution-time Qwen readiness checks.
 *
 * The install audit is a receipt because ModelBundleInstaller writes it only after hashing each
 * staged file against the pinned source SHA, then atomically promotes the bundle. A fast-path
 * receipt is accepted only under MCA's app-specific `image_models` roots, with exact audited file
 * sizes, no symlinks/path escapes, publisher SHA metadata, and component mtimes no newer than the
 * audit. Android app-private/scoped-storage rules isolate those roots from other application
 * UIDs; the installer replaces bundles atomically. Root/ADB or same-UID code that rewrites bytes
 * and restores every filesystem identity attribute is outside that sandbox threat model. Any
 * root outside the enforced MCA directory patterns, stale audit, missing file keys, symlinks, or
 * snapshot uncertainty falls back to a fresh full SHA-256 verification. Explicit callers can
 * force that full verification through `inspectQwenImage21Bundle(..., forceFullIntegrityRecheck = true)`.
 */
internal class QwenImage21BundleAuditVerifier(
    private val cacheEligibleRoot: (File) -> Boolean = ::isMcaPrivateImageModelBundleRoot,
    private val installer: ModelBundleInstaller = ModelBundleInstaller(),
    private val fileKeyProvider: (java.nio.file.Path, BasicFileAttributes) -> String? = { _, attributes ->
        attributes.fileKey()?.toString()?.takeIf(String::isNotBlank)
    },
    private val maxEntries: Int = 4
) {
    private data class CacheEntry(
        val snapshot: String,
        val verification: ModelBundleAuditVerificationResult
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, CacheEntry>(8, 0.75f, true)

    fun verify(
        bundleRoot: File,
        forceFullRecheck: Boolean = false
    ): QwenImage21BundleAuditVerification = synchronized(lock) {
        val root = runCatching { bundleRoot.canonicalFile }.getOrNull()
            ?: return@synchronized fullVerification(bundleRoot)
        val cacheKey = root.path
        val cacheAllowed = !forceFullRecheck && cacheEligibleRoot(root)

        if (cacheAllowed) {
            val cached = entries[cacheKey]
            if (cached != null) {
                val currentSnapshot = captureSnapshot(root)
                if (currentSnapshot != null && currentSnapshot == cached.snapshot) {
                    return@synchronized QwenImage21BundleAuditVerification(
                        verification = cached.verification,
                        usedFastPath = true
                    )
                }
                entries.remove(cacheKey)
            }
        } else {
            entries.remove(cacheKey)
        }

        // A fresh trusted install receipt avoids rereading the 9.4 GiB weight files on the first
        // execution. The metadata snapshot is taken on both sides to reject a concurrent change.
        if (cacheAllowed) {
            val beforeReceipt = captureSnapshot(root)
            val receipt = readFreshInstallReceipt(root)
            val afterReceipt = captureSnapshot(root)
            if (receipt != null && beforeReceipt != null && beforeReceipt == afterReceipt) {
                remember(cacheKey, afterReceipt, receipt)
                return@synchronized QwenImage21BundleAuditVerification(receipt, usedFastPath = true)
            }
        }

        // Snapshot around the full hash so a concurrent install/replacement cannot seed a stale
        // cache entry. If either snapshot is unavailable or differs, the verification result is
        // still returned but never memoized.
        val before = if (cacheAllowed) captureSnapshot(root) else null
        val verification = installer.verifyInstalledBundle(root)
        if (cacheAllowed && before != null && canCacheVerifiedResult(verification)) {
            val after = captureSnapshot(root)
            if (after != null && before == after) {
                remember(cacheKey, after, verification)
            }
        }
        QwenImage21BundleAuditVerification(verification, usedFastPath = false)
    }

    private fun fullVerification(root: File): QwenImage21BundleAuditVerification =
        QwenImage21BundleAuditVerification(
            verification = installer.verifyInstalledBundle(root),
            usedFastPath = false
        )

    private fun canCacheVerifiedResult(
        verification: ModelBundleAuditVerificationResult
    ): Boolean = verification.auditReadable && verification.components.isNotEmpty() &&
        verification.components.all { component ->
            component.status == ModelBundleComponentVerificationStatus.MATCHED_SOURCE_SHA256 &&
                component.audit.sourceMetadataStatus == ImageEngineIntegrityMetadataStatus.SOURCE_SHA256
        }

    private fun remember(
        key: String,
        snapshot: String,
        verification: ModelBundleAuditVerificationResult
    ) {
        entries[key] = CacheEntry(snapshot, verification)
        while (entries.size > maxEntries.coerceAtLeast(1)) {
            val oldest = entries.entries.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            } else {
                break
            }
        }
    }

    private fun readFreshInstallReceipt(root: File): ModelBundleAuditVerificationResult? = runCatching {
        val auditFile = File(root, ModelBundleInstaller.AUDIT_FILE_NAME)
        if (!auditFile.isFile || !auditFile.canRead() || Files.isSymbolicLink(auditFile.toPath())) return null
        val auditAttributes = Files.readAttributes(
            auditFile.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
        if (!auditAttributes.isRegularFile || fileKeyProvider(auditFile.toPath(), auditAttributes) == null) return null
        val manifest = JSONObject(auditFile.readText(Charsets.UTF_8))
        if (manifest.optString("schema") != ModelBundleInstaller.AUDIT_SCHEMA) return null
        val components = manifest.optJSONArray("components") ?: return null
        if (components.length() == 0) return null
        val seenPaths = mutableSetOf<String>()
        val verified = ArrayList<ModelBundleComponentVerification>(components.length())
        for (index in 0 until components.length()) {
            val item = components.optJSONObject(index) ?: return null
            val relativePath = item.optString("path").replace('\\', '/')
            if (!isSafeRelativePath(relativePath) || !seenPaths.add(relativePath.lowercase(Locale.ROOT))) return null
            val file = safeReceiptDescendant(root, relativePath) ?: return null
            val attributes = Files.readAttributes(
                file.toPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS
            )
            val observedSize = item.optLong("observedSizeBytes", -1L)
            val observedSha = item.optString("observedSha256").lowercase(Locale.ROOT)
            val sourceSha = item.optString("sourceSha256").lowercase(Locale.ROOT)
            val sourceMetadataStatus = item.optString("sourceMetadataStatus")
                .let { status -> ImageEngineIntegrityMetadataStatus.entries.firstOrNull { it.name == status } }
                ?: return null
            val sourceSize = item.optLong("sourceSizeBytes", -1L).takeIf { it > 0L }
            val transformed = item.optBoolean("transformed", false)
            if (!attributes.isRegularFile || attributes.isSymbolicLink ||
                fileKeyProvider(file.toPath(), attributes) == null ||
                !file.canRead() || observedSize <= 0L || attributes.size() != observedSize ||
                (sourceSize != null && sourceSize != observedSize) ||
                !SHA256_HEX.matches(observedSha) || !sourceSha.matches(SHA256_HEX) ||
                observedSha != sourceSha ||
                sourceMetadataStatus != ImageEngineIntegrityMetadataStatus.SOURCE_SHA256 || transformed ||
                attributes.lastModifiedTime() > auditAttributes.lastModifiedTime()
            ) return null
            verified += ModelBundleComponentVerification(
                audit = ModelBundleComponentAudit(
                    relativePath = relativePath,
                    observedSizeBytes = observedSize,
                    observedSha256 = observedSha,
                    sourceSizeBytes = sourceSize,
                    sourceSha256 = sourceSha,
                    sourceMetadataStatus = sourceMetadataStatus,
                    transformed = transformed
                ),
                status = ModelBundleComponentVerificationStatus.MATCHED_SOURCE_SHA256
            )
        }
        val verifiedPaths = verified.mapTo(mutableSetOf()) { it.audit.relativePath }
        if (LocalImageBundleContract.qwenImage21RequiredComponentPaths.any { it !in verifiedPaths }) return null
        if (verified.any { it.status != ModelBundleComponentVerificationStatus.MATCHED_SOURCE_SHA256 }) return null
        ModelBundleAuditVerificationResult(
            auditFile = auditFile.canonicalFile,
            auditReadable = true,
            components = verified
        )
    }.getOrNull()

    private fun safeReceiptDescendant(root: File, relativePath: String): File? {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        var current = canonicalRoot.toPath()
        relativePath.split('/').forEach { segment ->
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) return null
        }
        val candidate = runCatching { current.toFile().canonicalFile }.getOrNull() ?: return null
        return candidate.takeIf {
            it.path.startsWith(canonicalRoot.path + File.separator) && it.isFile
        }
    }

    private fun isSafeRelativePath(path: String): Boolean = path.isNotBlank() &&
        !path.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(path) &&
        '\u0000' !in path && path.split('/').none { it.isBlank() || it == "." || it == ".." }

    private fun captureSnapshot(root: File): String? = runCatching {
        if (!root.isDirectory || !root.canRead() || Files.isSymbolicLink(root.toPath())) return null
        val auditFile = File(root, ModelBundleInstaller.AUDIT_FILE_NAME)
        if (!auditFile.isFile || !auditFile.canRead() || Files.isSymbolicLink(auditFile.toPath())) return null
        val auditDigest = sha256(auditFile)
        val rootPath = root.toPath()
        val identities = mutableListOf<String>()
        Files.walk(rootPath).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (Files.isSymbolicLink(path)) return null
                val attributes = Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS
                )
                val fileKey = fileKeyProvider(path, attributes) ?: return null
                if (!attributes.isDirectory && !attributes.isRegularFile) return null
                val relative = rootPath.relativize(path).toString().replace('\\', '/')
                identities += listOf(
                    relative,
                    if (attributes.isDirectory) "directory" else "file",
                    attributes.size().toString(),
                    attributes.lastModifiedTime().toString(),
                    attributes.creationTime().toString(),
                    fileKey
                ).joinToString("|")
            }
        }
        identities.sort()
        val payload = buildString {
            append("audit=").append(auditDigest).append('\n')
            identities.forEach { append(it).append('\n') }
        }
        MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }.getOrNull()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

internal data class QwenImage21BundleAuditVerification(
    val verification: ModelBundleAuditVerificationResult,
    val usedFastPath: Boolean
)

private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

private fun isMcaPrivateImageModelBundleRoot(root: File): Boolean {
    val path = runCatching { root.canonicalPath.replace('\\', '/') }.getOrNull() ?: return false
    val allowedPrefixes = listOf(
        Regex("^/data/user/[^/]+/com\\.muyuchat\\.mca/files/image_models/"),
        Regex("^/data/data/com\\.muyuchat\\.mca/files/image_models/"),
        Regex("^/storage/emulated/[^/]+/Android/data/com\\.muyuchat\\.mca/files/image_models/"),
        Regex("^/storage/self/primary/Android/data/com\\.muyuchat\\.mca/files/image_models/"),
        Regex("^/mnt/user/[^/]+/primary/Android/data/com\\.muyuchat\\.mca/files/image_models/")
    )
    return allowedPrefixes.any { it.containsMatchIn(path) }
}


internal data class QwenImage21BundleContractCheck(
    val root: File?,
    val missingComponents: List<String>,
    val integrityMessage: String? = null
) {
    fun readinessMessage(): String? {
        if (root == null) {
            return "Qwen-Image-2.1 requires its complete MNN image bundle directory."
        }
        integrityMessage?.let { return it }
        return missingComponents.takeIf { it.isNotEmpty() }?.let { missing ->
            "Qwen-Image-2.1 bundle is incomplete: ${missing.joinToString(", ")}. " +
                "Download the complete recommended model package, then retry."
        }
    }
}

internal data class MnnBundleContractCheck(
    val root: File?,
    val missingComponents: List<String>,
    val integrityMessage: String? = null
) {
    fun readinessMessage(family: LocalImageModelFamily): String? {
        if (root == null) {
            return "MNN-Diffusion image engine requires a complete resource directory."
        }
        integrityMessage?.let { return it }
        if (missingComponents.isEmpty()) return null
        return if (family == LocalImageModelFamily.SANA) {
            "MNN Sana bundle is incomplete: ${missingComponents.joinToString(", ")}."
        } else {
            "MNN-Diffusion Stable Diffusion 1.5 bundle is incomplete: " +
                "${missingComponents.joinToString(", ")}."
        }
    }
}

internal data class MnnVerificationRoute(
    val family: LocalImageModelFamily,
    val steps: Int,
    val width: Int,
    val height: Int,
    val requiresUnetPreflight: Boolean
)

internal fun mnnVerificationRoute(
    modelFamily: LocalImageModelFamily,
    manifest: LocalImageBundleManifest?
): MnnVerificationRoute {
    val family = manifest?.family ?: modelFamily
    val isSana = family == LocalImageModelFamily.SANA
    return MnnVerificationRoute(
        family = family,
        steps = if (isSana) manifest?.smokeSteps?.coerceIn(2, 50) ?: 2 else 20,
        width = if (isSana) manifest?.smokeWidth.toSmokeDimension() else 512,
        height = if (isSana) manifest?.smokeHeight.toSmokeDimension() else 512,
        requiresUnetPreflight = !isSana
    )
}

private fun Int?.toSmokeDimension(): Int =
    this?.takeIf { it > 0 }?.coerceIn(256, 1536) ?: 512
