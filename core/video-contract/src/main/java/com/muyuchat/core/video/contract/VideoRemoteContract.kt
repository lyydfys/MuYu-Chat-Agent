package com.muyuchat.core.video.contract

/**
 * Explicit remote ComfyUI binding (proposal §11, V3-01).
 *
 * The endpoint is chosen by the user for one submit; local failure never switches to it. The API
 * key stays in memory for the request and is never written into the workflow, snapshot, Room row,
 * logs or a share artifact. [endpointId] is a non-secret local identifier safe to persist.
 */
data class VideoRemoteEndpointBinding(
    val endpointId: String,
    val baseUrl: String,
    val apiKey: String? = null,
    /** Only enable when the fixed server revision is known to support per-prompt interrupt. */
    val allowTargetedCancel: Boolean = false,
)

data class VideoRemoteCapabilities(
    val serverVersion: String?,
    val supportsTargetedCancel: Boolean,
    val supportsImageUpload: Boolean = false,
)

sealed class VideoRemoteProbeResult {
    data class Ready(val capabilities: VideoRemoteCapabilities) : VideoRemoteProbeResult()
    data object AuthFailed : VideoRemoteProbeResult()
    data class Failed(val code: VideoErrorCode, val detail: String) : VideoRemoteProbeResult()
}

/** Probe capability of an explicitly configured endpoint (no task submission). */
interface VideoRemoteProbePort {
    suspend fun probe(endpoint: VideoRemoteEndpointBinding): VideoRemoteProbeResult
}
