package com.muyuchat.feature.dsh.workspace

import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.console.DshConsoleIntent
import com.muyuchat.core.dsh.contract.console.DshConsoleRejection
import com.muyuchat.core.dsh.contract.console.DshConsoleSnapshot
import com.muyuchat.core.dsh.contract.console.DshScreenCaptureConsentPhase

/** Result of the console's local, read-only permission gate for one typed intent. */
sealed interface DshConsoleIntentGate {
    data object Allowed : DshConsoleIntentGate
    data class Denied(val reason: DshConsoleRejection) : DshConsoleIntentGate
}

/**
 * Pure intent gating for the DSH console (D20).
 *
 * This is not a second authority: it only decides whether the *button* may submit an intent, using
 * the read-only snapshot. The injected [com.muyuchat.core.dsh.contract.console.DshConsolePort]
 * owner still performs its own permission, maintenance and generation checks and may reject. The
 * gate exists so the console never offers an action the snapshot already proves impossible (for
 * example "release maintenance" when no barrier is held, or "restart" while the host is starting).
 */
object DshConsoleIntentPolicy {

    fun gate(intent: DshConsoleIntent, snapshot: DshConsoleSnapshot): DshConsoleIntentGate =
        when (intent) {
            DshConsoleIntent.Refresh -> DshConsoleIntentGate.Allowed

            is DshConsoleIntent.OpenOfficialSurface -> openSurface(intent, snapshot)

            DshConsoleIntent.StopRuntime -> when {
                snapshot.runtime.stopRequested ->
                    denied(DshConsoleRejection.STOP_REQUESTED)

                snapshot.runtime.phase == DshRuntimePhase.READY ||
                    snapshot.runtime.phase == DshRuntimePhase.STARTING ->
                    DshConsoleIntentGate.Allowed

                else -> denied(DshConsoleRejection.HOST_NOT_READY)
            }

            DshConsoleIntent.RestartRuntime -> when (snapshot.runtime.phase) {
                DshRuntimePhase.FAILED,
                DshRuntimePhase.STOPPED -> DshConsoleIntentGate.Allowed

                else -> denied(DshConsoleRejection.HOST_BUSY)
            }

            DshConsoleIntent.RequestScreenCapture -> requestScreenCapture(snapshot)

            DshConsoleIntent.AcquireMaintenanceBarrier -> when {
                snapshot.maintenance.admission.barrierActive ->
                    denied(DshConsoleRejection.MAINTENANCE_ACTIVE)

                snapshot.runtime.phase == DshRuntimePhase.STARTING ||
                    snapshot.runtime.phase == DshRuntimePhase.STOPPING ->
                    denied(DshConsoleRejection.HOST_BUSY)

                else -> DshConsoleIntentGate.Allowed
            }

            DshConsoleIntent.ReleaseMaintenanceBarrier ->
                if (snapshot.maintenance.admission.barrierActive) {
                    DshConsoleIntentGate.Allowed
                } else {
                    denied(DshConsoleRejection.MAINTENANCE_NOT_HELD)
                }

            DshConsoleIntent.ExportDiagnostics ->
                if (snapshot.diagnostics.redactedLines.isEmpty() &&
                    snapshot.diagnostics.lastErrorCode == null
                ) {
                    denied(DshConsoleRejection.NOT_AVAILABLE)
                } else {
                    DshConsoleIntentGate.Allowed
                }
        }

    /** Intents whose button may be enabled for [snapshot]. Read-only derivation, no side effect. */
    fun availableIntents(snapshot: DshConsoleSnapshot): Set<DshConsoleIntent> {
        val candidates = buildList<DshConsoleIntent> {
            add(DshConsoleIntent.Refresh)
            snapshot.officialSurfaces.forEach { add(DshConsoleIntent.OpenOfficialSurface(it)) }
            add(DshConsoleIntent.StopRuntime)
            add(DshConsoleIntent.RestartRuntime)
            add(DshConsoleIntent.RequestScreenCapture)
            add(DshConsoleIntent.AcquireMaintenanceBarrier)
            add(DshConsoleIntent.ReleaseMaintenanceBarrier)
            add(DshConsoleIntent.ExportDiagnostics)
        }
        return candidates.filter { gate(it, snapshot) == DshConsoleIntentGate.Allowed }.toSet()
    }

    /**
     * The consent entry exists only while the virtual-screen tool is actually registered for this
     * generation: a grant cannot be used without the authenticated vscreen bridge. A pending or
     * granted consent removes the action instead of re-requesting it; a cancelled/refused request
     * may be retried. The app consent owner still refuses a duplicate request.
     */
    private fun requestScreenCapture(snapshot: DshConsoleSnapshot): DshConsoleIntentGate {
        val virtualScreen = snapshot.capabilities.firstOrNull {
            it.capability == DshCapability.VIRTUAL_SCREEN
        }
        if (virtualScreen == null || !virtualScreen.toolRegistered) {
            return denied(DshConsoleRejection.SCREEN_CAPTURE_UNAVAILABLE)
        }
        return when (snapshot.screenCapture.phase) {
            DshScreenCaptureConsentPhase.GRANTED ->
                denied(DshConsoleRejection.SCREEN_CAPTURE_ALREADY_GRANTED)

            DshScreenCaptureConsentPhase.REQUESTED,
            DshScreenCaptureConsentPhase.AWAITING_USER ->
                denied(DshConsoleRejection.SCREEN_CAPTURE_PENDING)

            DshScreenCaptureConsentPhase.IDLE,
            DshScreenCaptureConsentPhase.CANCELLED,
            DshScreenCaptureConsentPhase.REJECTED,
            DshScreenCaptureConsentPhase.RELEASED ->
                if (snapshot.runtime.phase == DshRuntimePhase.READY) {
                    DshConsoleIntentGate.Allowed
                } else {
                    denied(DshConsoleRejection.HOST_NOT_READY)
                }
        }
    }

    private fun openSurface(
        intent: DshConsoleIntent.OpenOfficialSurface,
        snapshot: DshConsoleSnapshot
    ): DshConsoleIntentGate {
        if (intent.surface !in snapshot.officialSurfaces) {
            return denied(DshConsoleRejection.SURFACE_UNAVAILABLE)
        }
        return if (snapshot.runtime.phase == DshRuntimePhase.READY) {
            DshConsoleIntentGate.Allowed
        } else {
            denied(DshConsoleRejection.HOST_NOT_READY)
        }
    }

    private fun denied(reason: DshConsoleRejection): DshConsoleIntentGate =
        DshConsoleIntentGate.Denied(reason)
}

/** User-facing explanation of a refusal. Fixed strings; never echoes snapshot content. */
fun DshConsoleRejection.messageFor(): String = when (this) {
    DshConsoleRejection.HOST_NOT_READY -> "DSH 运行时尚未就绪。"
    DshConsoleRejection.HOST_BUSY -> "DSH 运行时正在切换状态，请稍候。"
    DshConsoleRejection.STOP_REQUESTED -> "DSH 已被请求停止，不会自动恢复。"
    DshConsoleRejection.MAINTENANCE_ACTIVE -> "维护屏障已被持有。"
    DshConsoleRejection.MAINTENANCE_NOT_HELD -> "当前没有持有的维护屏障。"
    DshConsoleRejection.SURFACE_UNAVAILABLE -> "该官方页面入口当前不可用。"
    DshConsoleRejection.NOT_AVAILABLE -> "当前没有可用的诊断信息。"
    DshConsoleRejection.SCREEN_CAPTURE_UNAVAILABLE -> "虚拟屏通道尚未注册，无法申请屏幕捕获。"
    DshConsoleRejection.SCREEN_CAPTURE_ALREADY_GRANTED -> "屏幕捕获已授权。"
    DshConsoleRejection.SCREEN_CAPTURE_PENDING -> "屏幕捕获授权请求正在进行中。"
    DshConsoleRejection.USER_PRESENCE_REQUIRED -> "该操作需要在可见页面完成。"
    DshConsoleRejection.UNKNOWN_INTENT -> "不支持的控制台操作。"
}
