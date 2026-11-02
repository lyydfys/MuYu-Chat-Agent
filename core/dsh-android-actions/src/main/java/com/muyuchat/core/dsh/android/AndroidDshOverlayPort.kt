package com.muyuchat.core.dsh.android

import android.content.Context
import android.provider.Settings
import com.muyuchat.core.dsh.contract.capability.DshCapabilityState

/**
 * The app-owned overlay window, as the capability bridge needs to see it.
 *
 * Implemented by whatever owns the floating window (the app composition or the overlay feature).
 * `setVisible` must be idempotent and must return only after the window request was handed to the
 * platform; the port then reads the state back instead of assuming the change happened.
 */
interface DshOverlayWindowController {
    fun isRunning(): Boolean

    fun isEngineOnline(): Boolean

    fun isVisible(): Boolean

    suspend fun setVisible(visible: Boolean)
}

/**
 * `GET /overlay` adapter.
 *
 * The permission gate is the system `SYSTEM_ALERT_WINDOW` check; `visible` is only claimed when
 * that grant exists and the controller reports the window attached. A controller failure is
 * reported as a status message, not as a fake success.
 */
class AndroidDshOverlayPort(
    context: Context,
    private val controller: DshOverlayWindowController
) : DshOverlayPort {

    private val appContext: Context = context.applicationContext

    override suspend fun snapshot(): DshOverlaySnapshot = snapshotOf(message = null)

    override suspend fun show(): DshOverlaySnapshot = requestVisibility(visible = true)

    override suspend fun hide(): DshOverlaySnapshot = requestVisibility(visible = false)

    private suspend fun requestVisibility(visible: Boolean): DshOverlaySnapshot {
        if (!Settings.canDrawOverlays(appContext)) {
            return snapshotOf(message = "未授予悬浮窗权限")
        }
        return try {
            controller.setVisible(visible)
            snapshotOf(message = null)
        } catch (_: RuntimeException) {
            snapshotOf(message = "悬浮窗控制器异常")
        }
    }

    private fun snapshotOf(message: String?): DshOverlaySnapshot {
        val granted = Settings.canDrawOverlays(appContext)
        return DshOverlaySnapshot(
            running = controller.isRunning(),
            engineUp = controller.isEngineOnline(),
            granted = granted,
            visible = granted && controller.isVisible(),
            message = message
        )
    }

    /** Availability of the overlay action, without touching the window. */
    override fun state(): DshCapabilityState = when {
        !AndroidDshPermissionChecks.declares(appContext, ManifestPermission.SYSTEM_ALERT_WINDOW) ->
            DshCapabilityState.NOT_DECLARED
        Settings.canDrawOverlays(appContext) -> DshCapabilityState.GRANTED
        else -> DshCapabilityState.DENIED
    }

    private object ManifestPermission {
        const val SYSTEM_ALERT_WINDOW = "android.permission.SYSTEM_ALERT_WINDOW"
    }
}
