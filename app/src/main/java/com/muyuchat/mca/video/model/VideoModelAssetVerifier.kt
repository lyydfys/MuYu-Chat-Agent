package com.muyuchat.mca.video.model

import com.muyuchat.core.video.contract.VideoErrorCode
import java.io.File
import java.security.MessageDigest

/**
 * Verifies a local model bundle against its pinned manifest (proposal §5.1). Pure asset
 * checking: no download, no load, no UI decisions. The verdict distinguishes missing files,
 * truncated/interrupted files (size below pin), foreign files with the same name (size or
 * sha256 mismatch) and healthy components, and separately reports a disk-space precondition so
 * callers can fail before downloading, not after filling the disk.
 *
 * sha256 is computed by streaming the file; a component whose pin has no sha256 (pending
 * re-pin) passes on size alone and is reported as such — it is never silently upgraded to
 * "checksum verified".
 */
object VideoModelAssetVerifier {

    fun verify(
        manifest: VideoModelManifest,
        bundleRoot: File,
        availableSpaceBytes: () -> Long? = { null },
        hashFile: (File) -> String = ::sha256Of,
    ): VideoModelAssetVerification {
        val outcomes = mutableListOf<ComponentVerification>()
        var requiredBytes = 0L
        for (component in manifest.components) {
            val file = File(bundleRoot, component.relativePath)
            requiredBytes += component.sizeBytes
            outcomes += when {
                !file.exists() -> ComponentVerification.Missing(component)

                file.length() != component.sizeBytes -> ComponentVerification.SizeMismatch(
                    component,
                    file.length(),
                    code = if (file.length() < component.sizeBytes) {
                        // Shorter than the pin: interrupted download or truncated copy.
                        VideoErrorCode.INPUT_MISSING
                    } else {
                        VideoErrorCode.MODEL_CHECKSUM_MISMATCH
                    },
                )

                else -> {
                    val pinned = component.sha256
                    if (pinned == null) {
                        // Size-only pin: healthy on length, checksum explicitly unverified.
                        ComponentVerification.SizeOnlyOk(component)
                    } else {
                        val actual = hashFile(file)
                        if (!actual.equals(pinned, ignoreCase = true)) {
                            // Same name, different bytes: a foreign file must not load.
                            ComponentVerification.ChecksumMismatch(component, actual)
                        } else {
                            ComponentVerification.Ok(component)
                        }
                    }
                }
            }
        }

        val available = availableSpaceBytes()
        val insufficient = available?.let { space ->
            if (space <= requiredBytes) requiredBytes - space + 1 else null
        }
        // Readiness is decided by the required roles only (DiT, encoder, VAE, Turbo LoRA). The
        // TAE head backs an optional decode path, so its absence or damage must not block a
        // full-VAE run; its actual outcome is still reported in the component list.
        val requiredVerified = outcomes
            .filter { it.file.required }
            .all { it is ComponentVerification.Ok }

        return if (requiredVerified && insufficient == null) {
            VideoModelAssetVerification.Ready(manifest, outcomes)
        } else {
            VideoModelAssetVerification.Problems(manifest, outcomes, insufficient)
        }
    }

    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
