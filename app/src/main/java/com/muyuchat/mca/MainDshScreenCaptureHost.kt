package com.muyuchat.mca

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muyuchat.core.dsh.contract.console.DshScreenCaptureConsentPhase
import com.muyuchat.mca.composition.DshScreenCaptureConsentController

/**
 * Shell bridge between the app-owned screen-capture consent request and the visible Activity.
 *
 * The console can only *record* a request: the system `MediaProjection` dialog requires visible
 * user presence and an `ActivityResultLauncher`, both owned by [MainActivity]. This composable
 * renders nothing, observes the typed request, and hands each request id to the Activity exactly
 * once. It holds no projection, no Intent and no dialog state; the consent state machine lives in
 * [DshScreenCaptureConsentController], which survives Activity recreation with the ViewModel.
 *
 * A request that is already [DshScreenCaptureConsentPhase.AWAITING_USER] (for example after a
 * configuration change while the dialog is open) is not launched again; the ActivityResultRegistry
 * delivers the pending result to the recreated launcher.
 */
@Composable
internal fun DshScreenCaptureConsentHost(
    consent: DshScreenCaptureConsentController,
    onLaunchConsent: (Long) -> Unit
) {
    val state by consent.state.collectAsStateWithLifecycle()
    val latestOnLaunch = rememberUpdatedState(onLaunchConsent)
    LaunchedEffect(state.requestId, state.phase) {
        if (state.phase == DshScreenCaptureConsentPhase.REQUESTED) {
            latestOnLaunch.value(state.requestId)
        }
    }
}
