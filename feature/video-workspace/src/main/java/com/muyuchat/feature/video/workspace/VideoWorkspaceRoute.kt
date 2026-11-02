package com.muyuchat.feature.video.workspace

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.muyuchat.core.video.contract.VideoImportPort
import com.muyuchat.core.video.contract.VideoModelAssetPort
import com.muyuchat.core.video.contract.VideoRemoteProbePort
import com.muyuchat.core.video.contract.VideoWorkflowPort
import com.muyuchat.feature.video.workspace.ui.VideoWorkspaceScreen

/**
 * Public entry point of the native video workspace tab (proposal
 * mca-native-video-workflow-v1 §9).
 *
 * The app composition root supplies the process owner's [VideoWorkflowPort] and the ComfyUI
 * [VideoImportPort]; the page itself only sends intents and observes read-only projections.
 * `port == null` / `importPort == null` are explicit unwired states: editing works, execution
 * and import are honestly refused, and nothing fabricates progress, placeholders or a fake
 * runtime.
 *
 * Leaving this page never cancels a run; re-entering must reuse the same owner. The V1-05 slice
 * adds the published-video library (playback/share/save-as/delete) and the ComfyUI API import
 * preview on top of the V0-03 editor shell.
 */
@Composable
fun VideoWorkspaceRoute(
    port: VideoWorkflowPort?,
    importPort: VideoImportPort?,
    remoteProbePort: VideoRemoteProbePort?,
    modelAssetPort: VideoModelAssetPort?,
    enginePreference: VideoExperimentalEnginePreference? = null,
    /** Installed Turbo LoRA for the external engine; null when the composition root has none. */
    turboLoraPathProvider: (() -> String?)? = null,
    /** Durable draft store; null keeps saved-state-only persistence. */
    draftStore: VideoDraftStore? = null,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: VideoWorkspaceViewModel = viewModel(
        factory = videoWorkspaceViewModelFactory(
            port,
            importPort,
            remoteProbePort,
            modelAssetPort,
            enginePreference,
            turboLoraPathProvider,
            draftStore,
        ),
    )
    VideoWorkspaceScreen(
        viewModel = viewModel,
        onExit = onExit,
        modifier = modifier,
    )
}

private fun videoWorkspaceViewModelFactory(
    port: VideoWorkflowPort?,
    importPort: VideoImportPort?,
    remoteProbePort: VideoRemoteProbePort?,
    modelAssetPort: VideoModelAssetPort?,
    enginePreference: VideoExperimentalEnginePreference?,
    turboLoraPathProvider: (() -> String?)?,
    draftStore: VideoDraftStore?,
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        VideoWorkspaceViewModel(
            savedState = createSavedStateHandle(),
            port = port,
            importPort = importPort,
            remotePort = remoteProbePort,
            modelAssetPort = modelAssetPort,
            enginePreference = enginePreference,
            turboLoraPathProvider = turboLoraPathProvider,
            draftStore = draftStore,
        )
    }
}
