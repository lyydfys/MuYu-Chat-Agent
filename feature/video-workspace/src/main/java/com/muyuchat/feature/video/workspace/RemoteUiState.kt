package com.muyuchat.feature.video.workspace

import com.muyuchat.core.video.contract.VideoRemoteCapabilities

/**
 * Remote ComfyUI probe/preview state (V3-01). The API key is never stored here: only the result of
 * an explicit probe and non-secret capability facts are exposed to the page.
 */
sealed class RemoteUiState {
    data object Idle : RemoteUiState()
    data object Probing : RemoteUiState()
    data class Ready(val capabilities: VideoRemoteCapabilities) : RemoteUiState()
    data object AuthFailed : RemoteUiState()
    data class Failed(val code: String, val detail: String) : RemoteUiState()
}
