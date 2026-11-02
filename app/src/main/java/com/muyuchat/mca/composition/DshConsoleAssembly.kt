package com.muyuchat.mca.composition

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.muyuchat.core.dsh.contract.DshRuntimeClient
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.auth.DshAuthAttemptView
import com.muyuchat.core.dsh.contract.console.DshConsoleAccountProjection
import com.muyuchat.core.dsh.contract.console.DshConsoleCapabilityProjection
import com.muyuchat.core.dsh.contract.console.DshConsoleDiagnosticsProjection
import com.muyuchat.core.dsh.contract.console.DshConsoleIntent
import com.muyuchat.core.dsh.contract.console.DshConsoleIntentResult
import com.muyuchat.core.dsh.contract.console.DshConsoleMaintenanceProjection
import com.muyuchat.core.dsh.contract.console.DshConsoleOfficialSurface
import com.muyuchat.core.dsh.contract.console.DshConsolePort
import com.muyuchat.core.dsh.contract.console.DshConsoleRejection
import com.muyuchat.core.dsh.contract.console.DshConsoleRuntimeProjection
import com.muyuchat.core.dsh.contract.console.DshConsoleShellSandboxProjection
import com.muyuchat.core.dsh.contract.console.DshConsoleSnapshot
import com.muyuchat.core.dsh.contract.console.DshShellSandboxStatus
import com.muyuchat.core.dsh.contract.console.DshExternalBrowserPort
import com.muyuchat.core.dsh.contract.console.DshPopupHandoffResult
import com.muyuchat.core.dsh.contract.console.DshPopupRequest
import com.muyuchat.core.dsh.contract.console.DshPopupUnavailable
import com.muyuchat.core.dsh.contract.console.DshScreenCaptureConsentPhase
import java.util.concurrent.atomic.AtomicLong

/**
 * App-side implementation of the D20 console port.
 *
 * It only projects facts already owned by the runtime client, the capability bridge and the
 * maintenance owner, and forwards typed intents to those owners. It never writes runtime, model,
 * session, plugin or UI state itself. Every refusal is a real owner refusal; nothing here invents
 * a success.
 *
 * Honest limits (reported, not hidden): the official-surface links are not wired yet, so the
 * projection advertises no surface and the intent is rejected; the account row is projected only
 * from the injected attempt source (without one it reads as "source unavailable"); diagnostics carry
 * only the current runtime failure code; first-frame state is owned by the host and is not observed
 * here.
 *
 * Official account surface: `OpenOfficialSurface(ACCOUNT)` cannot be routed from this adapter
 * without guessing an official URL. The console port has no reference to the WebView/workspace
 * route owner (the only component that can issue a same-origin load), and the official client
 * exposes no host-facing, typed account route: its account UI is reached from inside the official
 * page. Navigating would therefore require either a guessed path or injected JavaScript, both
 * forbidden. The intent stays a typed `SURFACE_UNAVAILABLE` and the console shows the action as
 * unavailable.
 */
internal class DshConsolePortAdapter(
    private val runtime: DshRuntimeClient,
    private val capabilities: () -> List<DshConsoleCapabilityProjection>,
    private val maintenance: DshMaintenanceAssembly,
    private val screenCapture: DshScreenCaptureConsentController,
    private val firstFrame: () -> Boolean = { false },
    private val modelBound: () -> Boolean = { false },
    /**
     * Optional account-lane attempt source (`DshAccountLoginOwner::attemptView`). It is a bounded
     * read-only snapshot; no credential, URL or token ever crosses this seam. When null the console
     * projects the typed `SOURCE_UNAVAILABLE` account row instead of inventing "not started".
     */
    private val accountAttempt: (() -> DshAuthAttemptView?)? = null,
    /** Durable seeder fact; null means "not probed", never "enforced". */
    private val sandboxStatus: () -> DshShellSandboxStatus? = { null },
    private val clock: () -> Long = System::currentTimeMillis
) : DshConsolePort {

    private val revision = AtomicLong(0L)
    private val accountProjection = accountAttempt?.let {
        DshConsoleAccountProjectionAdapter(attemptView = it, clock = clock)
    }

    override suspend fun snapshot(): DshConsoleSnapshot {
        val current = runtime.snapshot.value
        return DshConsoleSnapshot(
            revision = revision.incrementAndGet(),
            capturedAtMillis = clock(),
            runtime = DshConsoleRuntimeProjection(
                phase = current.phase,
                generation = current.epoch,
                origin = current.origin?.takeIf(::isLoopbackOrigin),
                stopRequested = current.phase == DshRuntimePhase.STOPPED,
                firstFrameRendered = firstFrame(),
                failureCode = current.failure?.code?.name,
                modelBound = modelBound()
            ),
            capabilities = capabilities(),
            // The consent projection is owned by the app consent controller; the console only
            // reads it. The system dialog itself is launched by the visible host Activity.
            screenCapture = screenCapture.projectedState(),
            // Bounded account-attempt observation only; the projection has no success state.
            account = accountProjection?.project() ?: DshConsoleAccountProjection(),
            maintenance = DshConsoleMaintenanceProjection(maintenance.admissionSnapshot()),
            diagnostics = DshConsoleDiagnosticsProjection(
                lastErrorCode = current.failure?.code?.name
            ),
            shellSandbox = sandboxStatus().toConsoleProjection(),
            officialSurfaces = emptySet()
        )
    }

    override suspend fun execute(intent: DshConsoleIntent): DshConsoleIntentResult = when (intent) {
        DshConsoleIntent.Refresh -> DshConsoleIntentResult.Applied()

        DshConsoleIntent.RequestScreenCapture -> {
            val consent = screenCapture.projectedState()
            when {
                // The dialog can only arm a projection for a live generation; the console gate
                // already hides the action, but the port is the authority for a direct call.
                runtime.snapshot.value.phase != DshRuntimePhase.READY ->
                    DshConsoleIntentResult.Rejected(
                        reason = DshConsoleRejection.HOST_NOT_READY,
                        message = "DSH 运行时未就绪，无法申请屏幕捕获"
                    )

                consent.phase == DshScreenCaptureConsentPhase.GRANTED ->
                    DshConsoleIntentResult.Rejected(
                        reason = DshConsoleRejection.SCREEN_CAPTURE_ALREADY_GRANTED,
                        message = "屏幕捕获已授权，可直接创建虚拟屏"
                    )

                consent.isPending ->
                    DshConsoleIntentResult.Rejected(
                        reason = DshConsoleRejection.SCREEN_CAPTURE_PENDING,
                        message = "屏幕捕获授权请求正在进行中"
                    )

                screenCapture.request() ->
                    // Pending, not Applied: the owner has only recorded the request. The grant
                    // exists after the Activity result is accepted, never at this point.
                    DshConsoleIntentResult.Pending("已请求屏幕捕获授权，请在系统对话框中允许")

                else -> DshConsoleIntentResult.Rejected(
                    reason = DshConsoleRejection.SCREEN_CAPTURE_PENDING,
                    message = "屏幕捕获授权请求未能开始，请稍后重试"
                )
            }
        }

        DshConsoleIntent.StopRuntime -> {
            runtime.stop()
            val snapshot = runtime.snapshot.value
            if (snapshot.phase == DshRuntimePhase.STOPPED) {
                DshConsoleIntentResult.Applied("DSH 已确认停止")
            } else {
                DshConsoleIntentResult.Failed(
                    code = "STOP_UNKNOWN",
                    retryable = true,
                    message = "停止结果未确认，服务保持可重试"
                )
            }
        }

        DshConsoleIntent.RestartRuntime -> {
            val snapshot = runtime.restart()
            if (snapshot.phase == DshRuntimePhase.READY) {
                DshConsoleIntentResult.Applied("DSH 已重新启动")
            } else {
                DshConsoleIntentResult.Failed(
                    code = snapshot.failure?.code?.name ?: "RESTART_FAILED",
                    retryable = true
                )
            }
        }

        DshConsoleIntent.AcquireMaintenanceBarrier ->
            if (maintenance.acquireConsoleBarrier()) {
                DshConsoleIntentResult.Applied("维护写屏障已持有")
            } else {
                DshConsoleIntentResult.Rejected(
                    reason = DshConsoleRejection.MAINTENANCE_ACTIVE,
                    message = "仍有写入或读取租约，无法获取维护屏障"
                )
            }

        DshConsoleIntent.ReleaseMaintenanceBarrier ->
            if (maintenance.releaseConsoleBarrier()) {
                DshConsoleIntentResult.Applied("维护写屏障已释放")
            } else {
                DshConsoleIntentResult.Rejected(
                    reason = DshConsoleRejection.MAINTENANCE_NOT_HELD,
                    message = "当前控制台没有持有维护屏障"
                )
            }

        is DshConsoleIntent.OpenOfficialSurface -> DshConsoleIntentResult.Rejected(
            reason = DshConsoleRejection.SURFACE_UNAVAILABLE,
            message = when (intent.surface) {
                // No typed same-origin route or WebView navigation seam exists for the official
                // account UI; naming a path would be a guess and JS injection is not allowed.
                DshConsoleOfficialSurface.ACCOUNT ->
                    "官方账号页面没有可用的控制台路由，请在 DSH 官方页面内打开"

                else -> "官方页面跳转尚未接入，请在 DSH 页面内使用官方导航"
            }
        )

        DshConsoleIntent.ExportDiagnostics -> DshConsoleIntentResult.Rejected(
            reason = DshConsoleRejection.NOT_AVAILABLE,
            message = "诊断导出尚未接入"
        )
    }

    private fun isLoopbackOrigin(value: String): Boolean =
        LOOPBACK.matches(value)

    private fun DshShellSandboxStatus?.toConsoleProjection(): DshConsoleShellSandboxProjection =
        if (this == null) {
            DshConsoleShellSandboxProjection()
        } else {
            DshConsoleShellSandboxProjection(
                known = true,
                enforced = enforced,
                landlockAbi = landlockAbi,
                partial = partial,
                advisoryReason = advisoryReason
            )
        }

    private companion object {
        val LOOPBACK = Regex("""http://127\.0\.0\.1:\d{1,5}""")
    }
}

/** Keeps the console's stop fact aligned with the composition teardown rule (D01). */

/**
 * D04 trusted external browser handoff.
 *
 * The system browser is a separate surface from the DSH WebView, so the official account
 * authorize page never shares the DSH cookie jar. When no browser handler exists the result is a
 * typed [DshPopupUnavailable.NO_BROWSER_HANDLER]; nothing falls back to the DSH WebView.
 */
internal class AndroidDshExternalBrowserPort(
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis
) : DshExternalBrowserPort {

    override suspend fun openPopup(request: DshPopupRequest): DshPopupHandoffResult {
        if (request.isExpiredAt(clock())) return DshPopupHandoffResult.Expired
        val uri = runCatching { Uri.parse(request.url) }.getOrNull()
            ?: return DshPopupHandoffResult.Unavailable(DshPopupUnavailable.NO_BROWSER_HANDLER)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }
            .fold(
                onSuccess = { DshPopupHandoffResult.HandedOff },
                onFailure = {
                    DshPopupHandoffResult.Unavailable(DshPopupUnavailable.NO_BROWSER_HANDLER)
                }
            )
    }
}
