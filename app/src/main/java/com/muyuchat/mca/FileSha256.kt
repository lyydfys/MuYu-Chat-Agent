package com.muyuchat.mca

import java.io.File
import java.security.MessageDigest

/**
 * The single SHA-256-of-file-contents helper for the image pipeline.
 *
 * This existed as three byte-for-byte-equivalent `private fun File.sha256Contents()` copies -- in
 * `LocalImageProvider.kt`, `QnnImageRuntimeStager.kt` and `QnnImageVerificationStamp.kt` -- which is
 * a correctness hazard, not just duplication: the digest is compared against manifest and
 * verification-stamp values, so the three copies have to keep agreeing about what "the hash of this
 * file" means. They already did (the only differences were a buffer-size constant and a redundant
 * `read > 0` guard; `MessageDigest.update(b, 0, 0)` is a no-op), so they converge here.
 *
 * The streaming loop is kept deliberately literal: the file can be a multi-hundred-megabyte model
 * artefact, so it must never be read into a single array.
 */
internal fun File.sha256Contents(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
