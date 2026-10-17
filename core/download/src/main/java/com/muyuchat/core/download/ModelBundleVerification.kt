package com.muyuchat.core.download

import com.muyuchat.core.download.ModelBundleInstaller.Companion.WINDOWS_DRIVE_PREFIX
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

    internal fun verifyComponent(
        root: File,
        audit: ModelBundleComponentAudit
    ): ModelBundleComponentVerificationStatus {
        val file = runCatching { safeDescendant(root, audit.relativePath) }.getOrNull()
            ?: return ModelBundleComponentVerificationStatus.MISSING
        if (!file.isFile) return ModelBundleComponentVerificationStatus.MISSING
        if (file.length() <= 0L) return ModelBundleComponentVerificationStatus.MISSING
        if (file.length() != audit.observedSizeBytes) return ModelBundleComponentVerificationStatus.SIZE_MISMATCH
        val actualSha = sha256(file)
        if (!actualSha.equals(audit.observedSha256, ignoreCase = true)) {
            return ModelBundleComponentVerificationStatus.DIGEST_MISMATCH
        }
        val sourceSha = audit.sourceSha256
        return if (sourceSha != null && !actualSha.equals(sourceSha, ignoreCase = true)) {
            ModelBundleComponentVerificationStatus.SOURCE_SHA256_MISMATCH
        } else if (sourceSha != null) {
            ModelBundleComponentVerificationStatus.MATCHED_SOURCE_SHA256
        } else {
            ModelBundleComponentVerificationStatus.MATCHED_OBSERVED_DIGEST
        }
    }

    internal fun normalizeRelativePath(rawPath: String): String {
        require(rawPath.isNotBlank()) { "Bundle relativePath must not be blank." }
        require('\u0000' !in rawPath) { "Bundle relativePath contains a NUL character." }
        val normalized = rawPath.replace('\\', '/')
        require(!normalized.startsWith('/')) { "Bundle relativePath must not be absolute: $rawPath" }
        require(!WINDOWS_DRIVE_PREFIX.containsMatchIn(normalized)) {
            "Bundle relativePath must not use an absolute drive path: $rawPath"
        }
        val segments = normalized.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
            "Bundle relativePath contains an invalid segment: $rawPath"
        }
        return segments.joinToString("/")
    }

    internal fun rejectConflictingPaths(paths: List<String>) {
        val normalized = paths.map { it.lowercase(Locale.ROOT) }
        require(normalized.toSet().size == normalized.size) {
            "Duplicate bundle relativePath values are not allowed."
        }
        val pathSet = normalized.toSet()
        normalized.forEach { path ->
            val segments = path.split('/')
            for (index in 1 until segments.size) {
                val parentPath = segments.take(index).joinToString("/")
                require(parentPath !in pathSet) {
                    "Bundle relativePath conflicts with a file parent: $parentPath and $path"
                }
            }
        }
    }

    internal fun safeDescendant(root: File, relativePath: String): File {
        val canonicalRoot = root.canonicalFile
        val child = File(canonicalRoot, relativePath.replace('/', File.separatorChar)).canonicalFile
        require(child.toPath().startsWith(canonicalRoot.toPath()) && child != canonicalRoot) {
            "Bundle relativePath escapes its root: $relativePath"
        }
        return child
    }

    internal suspend fun cancellableSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
