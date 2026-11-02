package com.muyuchat.core.video.contract

import kotlinx.coroutines.flow.Flow

/**
 * User-facing acquisition of the local Wan2.2 model components (V1-01 follow-up).
 *
 * The video page only observes this typed port and sends intents. Downloading, resuming,
 * checksum verification and file placement belong to the process-level implementation in the
 * app composition root, so leaving the page never cancels a download and re-entering reuses the
 * same state. Nothing here creates a second video execution owner.
 */
enum class VideoModelAssetRole {
    DIT,
    TEXT_ENCODER,
    VAE,
    LORA,
    TAE,
}

enum class VideoModelAssetPhase {
    /** No check has run in this process yet. */
    UNKNOWN,

    /** Reading local lengths and SHA-256 of already-present files. */
    CHECKING,

    /** At least one component is absent or not the pinned bytes. */
    MISSING,

    /** Bytes are being downloaded or resumed; partial files survive app restarts. */
    DOWNLOADING,

    /** All bytes are present and are being hashed against the manifest pins. */
    VERIFYING,

    READY,

    FAILED,

    /** User stopped the transfer; completed components and `.part` files remain reusable. */
    CANCELLED,
}

data class VideoModelAssetComponentState(
    val role: VideoModelAssetRole,
    val label: String,
    val relativePath: String,
    val expectedBytes: Long,
    val present: Boolean,
    /** true only when the pinned SHA-256 was actually recomputed and matched. */
    val verified: Boolean,
    val detail: String? = null,
)

data class VideoModelAssetState(
    val phase: VideoModelAssetPhase = VideoModelAssetPhase.UNKNOWN,
    val components: List<VideoModelAssetComponentState> = emptyList(),
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val message: String? = null,
    val errorCode: String? = null,
) {
    val ready: Boolean get() = phase == VideoModelAssetPhase.READY

    val active: Boolean
        get() = phase == VideoModelAssetPhase.CHECKING ||
            phase == VideoModelAssetPhase.DOWNLOADING ||
            phase == VideoModelAssetPhase.VERIFYING

    val canDownload: Boolean
        get() = phase == VideoModelAssetPhase.MISSING ||
            phase == VideoModelAssetPhase.FAILED ||
            phase == VideoModelAssetPhase.CANCELLED
}

interface VideoModelAssetPort {
    fun observe(): Flow<VideoModelAssetState>

    /** Re-check local components. Never starts a network transfer. */
    fun refresh()

    /** Start or resume the transfer in the process scope; repeated calls are coalesced. */
    fun download()

    /** Cooperative stop. Completed components stay reusable; `.part` files are kept. */
    fun cancel()
}
