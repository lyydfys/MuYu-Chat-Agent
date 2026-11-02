package com.muyuchat.mca.dsh.overlay

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.muyuchat.core.dsh.android.DshOverlayWindowController

/**
 * App-side owner of the floating overlay, implementing the capability bridge's
 * [DshOverlayWindowController] port.
 *
 * Thin by design: it observes the permission, forwards commands to [DshOverlayService], translates
 * service callbacks into [DshOverlayEvent]s and keeps the one [DshOverlayState] the pure reducer
 * produces. Create one per app process, pass it to `AndroidDshOverlayPort`, and call [release] on
 * composition teardown. [refreshStatus] is the single poke needed after a generation/phase change.
 */
class DshOverlayController(
    context: Context,
    private val statusProvider: DshOverlayStatusProvider = DshOverlayStatusProvider.Unavailable,
    private val permissionCheck: () -> Boolean = {
        Settings.canDrawOverlays(context.applicationContext)
    },
    private val onStateChanged: (DshOverlayState) -> Unit = {}
) : DshOverlayWindowController {

    private val appContext: Context = context.applicationContext
    private val lock = Any()
    private var state = DshOverlayState()

    private val host = object : DshOverlayWindowHost {
        override fun onWindowAttached() {
            dispatch(DshOverlayEvent.WindowAttached)
            DshOverlayService.instance?.applyStatus(current().status)
        }

        override fun onServiceStopped() {
            dispatch(DshOverlayEvent.ServiceDestroyed)
        }

        override fun onMarkTapped() {
            togglePanel()
        }

        override fun onWindowRejected(failure: DshOverlayFailure) {
            dispatch(DshOverlayEvent.StartFailed(failure))
        }
    }

    init {
        DshOverlayWindowBus.host = host
        dispatch(DshOverlayEvent.PermissionObserved(currentPermission()))
        dispatch(DshOverlayEvent.StatusUpdated(statusProvider.status()))
    }

    override fun isRunning(): Boolean = current().running

    override fun isEngineOnline(): Boolean = current().engineOnline

    override fun isVisible(): Boolean = current().visible

    override suspend fun setVisible(visible: Boolean) {
        if (visible) show() else hide()
    }

    fun state(): DshOverlayState = current()

    /** Requests the window. Returns a typed reason when it was refused or could not start. */
    fun show(): DshOverlayFailure? {
        dispatch(DshOverlayEvent.PermissionObserved(currentPermission()))
        val requested = dispatch(DshOverlayEvent.ShowRequested)
        if (requested.windowAttached) {
            DshOverlayService.instance?.applyStatus(requested.status)
            return null
        }
        if (requested.service != DshOverlayServiceState.STARTING) {
            return requested.failure ?: DshOverlayFailure.of(
                DshOverlayFailureCode.SERVICE_START_FAILED,
                "悬浮窗无法启动"
            )
        }
        return start(DshOverlayService.intent(appContext, DshOverlayService.ACTION_SHOW, requested.status))
    }

    /** Stops the window service; the window is released by the service's own destroy path. */
    fun hide() {
        dispatch(DshOverlayEvent.HideRequested)
        appContext.stopService(Intent(appContext, DshOverlayService::class.java))
    }

    /** Panel toggle requested by the brand-mark tap. */
    fun togglePanel() {
        val next = dispatch(DshOverlayEvent.MarkTapped)
        DshOverlayService.instance?.setPanelExpanded(next.panelOpen)
    }

    /** Re-reads the provider and pushes the text into the panel when it is attached. */
    fun refreshStatus() {
        val status = statusProvider.status()
        dispatch(DshOverlayEvent.StatusUpdated(status))
        DshOverlayService.instance?.applyStatus(status)
    }

    /** Detaches from the process-level bus and stops the window service. */
    fun release() {
        if (DshOverlayWindowBus.host === host) DshOverlayWindowBus.host = null
        appContext.stopService(Intent(appContext, DshOverlayService::class.java))
    }

    private fun start(intent: Intent): DshOverlayFailure? = try {
        appContext.startService(intent)
        null
    } catch (error: RuntimeException) {
        val failure = DshOverlayFailure.of(
            DshOverlayFailureCode.SERVICE_START_FAILED,
            "无法启动悬浮窗服务（需应用在前台且已声明服务）：" + error.message
        )
        dispatch(DshOverlayEvent.StartFailed(failure))
        failure
    }

    private fun currentPermission(): DshOverlayPermissionState =
        if (permissionCheck()) DshOverlayPermissionState.GRANTED else DshOverlayPermissionState.DENIED

    private fun current(): DshOverlayState = synchronized(lock) { state }

    private fun dispatch(event: DshOverlayEvent): DshOverlayState {
        val next: DshOverlayState
        val changed: Boolean
        synchronized(lock) {
            next = DshOverlayStateReducer.reduce(state, event)
            changed = next != state
            state = next
        }
        if (changed) onStateChanged(next)
        return next
    }
}
