package com.muyuchat.feature.dsh.workspace.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muyuchat.feature.dsh.workspace.DshConsoleUiState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muyuchat.core.dsh.contract.console.DshConsolePort
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsolePort
import com.muyuchat.feature.dsh.workspace.DshConsoleViewModel

/**
 * Route wrapper of the DSH-only console.
 *
 * It owns exactly one [DshConsoleViewModel] for the injected projection port and, optionally, the
 * D23 maintenance section driven by [maintenancePort]. The console overlays the official page
 * inside the same tab: the underlying WebView stays composed so returning from the console does
 * not recreate the renderer, lose scroll state or resend DSH work. Notices are shown in this
 * surface's own snackbar, not in the MCA chat shell.
 *
 * When [maintenancePort] is null the maintenance section renders an honest disabled note; the
 * console itself still works from its own port.
 */
@Composable
fun DshConsoleRoute(
    port: DshConsolePort,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    maintenancePort: DshMaintenanceConsolePort? = null
) {
    val viewModel: DshConsoleViewModel = viewModel(
        key = "dsh-console",
        factory = DshConsoleViewModel.factory(port)
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Re-read on every console entry so a host that changed while the page was hidden is not
    // shown from the first snapshot forever.
    LaunchedEffect(viewModel) { viewModel.start(); viewModel.refresh() }
    // The screen-capture consent dialog is another Activity: this page stops while it is open and
    // resumes when the user answers. Re-project on resume so the consent row and action state show
    // the owner's new fact instead of a stale "waiting" projection.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(notice?.id) {
        val current = notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(current.text, duration = SnackbarDuration.Short)
        viewModel.consumeNotice(current.id)
    }

    Box(modifier = modifier.fillMaxSize()) {
        DshConsoleScreen(
            state = state,
            onIntent = viewModel::execute,
            onBack = onBack,
            modifier = Modifier.fillMaxSize(),
            maintenanceContent = {
                DshMaintenanceConsoleHost(
                    port = maintenancePort,
                    refreshKey = (state as? DshConsoleUiState.Ready)?.snapshot?.revision
                )
                // Additive device-privilege block of the same console column (Root + ADB status).
                // It adds no destination and does not reorder the existing sections.
                DshDeviceAccessSection()
                DshPictureInPictureSection()
                // Additive container block (proot + user-supplied rootfs): read-only verdict plus
                // the one fixed probe. It adds no destination and no layout change.
                DshContainerSection()
            }
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        )
    }
}
