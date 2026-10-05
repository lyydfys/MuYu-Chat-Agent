package com.muyuchat.mca

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

data class TextualInversionImportResult(
    val artifact: TextualInversionArtifact,
    val duplicate: Boolean = false
)

// P7/S35: private→internal after the head-type move; the store reads it cross-file.
internal data class ArtifactFileVerification(
    val canonicalPath: String,
    val fileKey: String?,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val sha256: String
) {
    fun sameIdentity(other: ArtifactFileVerification): Boolean =
        canonicalPath == other.canonicalPath &&
            fileKey == other.fileKey &&
            sizeBytes == other.sizeBytes &&
            lastModifiedMillis == other.lastModifiedMillis &&
            sha256 == other.sha256
}

class TextualInversionSelectionLease internal constructor(
    val selection: TextualInversionSelection,
    val rootPath: String,
    private val verify: () -> Unit = {},
    private val release: () -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)

    fun verifyUnchanged() {
        check(!closed.get()) { "Textual inversion selection lease is already closed." }
        verify()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

internal fun requireTextualInversionContentImportUri(
    scheme: String?,
    authority: String?
) {
    require(scheme.equals("content", ignoreCase = true) && !authority.isNullOrBlank()) {
        "Textual inversion import requires a content URI with an authority."
    }
}

class TextualInversionStoreException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)
