package com.muyuchat.core.video.contract

import kotlinx.coroutines.flow.Flow

/**
 * Read-only projection of one published video asset (proposal §8.1/§8.4).
 *
 * [reference] is an opaque app-owned playback handle (today a FileProvider content URI). The
 * feature layer only parses it for playback/sharing; it never receives a raw filesystem path and
 * never writes media state. Media rows are written by the process owner after the file has been
 * verified and atomically published.
 */
data class VideoMediaProjection(
    val mediaId: VideoMediaAssetId,
    val runId: VideoRunId,
    val mimeType: String,
    val reference: String,
    val sizeBytes: Long,
    val width: Long,
    val height: Long,
    val durationUs: Long,
    val fps: Long,
    val frameCount: Long,
    val codec: String,
    val createdAtMs: Long,
)

sealed class VideoDeleteResult {
    /** Tombstone committed and the owned file was unlinked (idempotent for a already-deleted row). */
    data class Deleted(val mediaId: VideoMediaAssetId) : VideoDeleteResult()

    data class Unknown(val mediaId: VideoMediaAssetId) : VideoDeleteResult()

    data class Failed(
        val mediaId: VideoMediaAssetId,
        val code: VideoErrorCode,
        val detail: String,
    ) : VideoDeleteResult()
}

/**
 * Media-library read/delete port. Video files are owned by MCA (not the user's original inputs or
 * exported copies); delete never touches either. Physical deletion is irreversible, so the UI must
 * confirm first and the store must tombstone the row before unlinking the owned file.
 */
interface VideoMediaLibraryPort {
    fun observeMedia(): Flow<List<VideoMediaProjection>>

    suspend fun deleteMedia(mediaId: VideoMediaAssetId): VideoDeleteResult
}
