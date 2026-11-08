package com.muyuchat.mca.onboarding

import android.content.Context
import com.muyuchat.core.dsh.contract.container.DshContainerInstallPort
import com.muyuchat.core.dsh.contract.container.DshContainerInstallSnapshot
import com.muyuchat.feature.dsh.workspace.container.AndroidDshContainerAssets
import com.muyuchat.feature.dsh.workspace.container.DshContainerHost
import com.muyuchat.feature.dsh.workspace.container.DshContainerInstallUi
import com.muyuchat.feature.dsh.workspace.container.DshContainerLayout
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Adapts the process-level container host onto the narrow install contract.
 *
 * This is the only place in `:app` that knows `:feature:dsh-workspace`: everything downstream (the
 * first-run host, its tests, the page) sees only [DshContainerInstallPort]. Swapping the implementation
 * later means changing this one constructor call, not the flow.
 *
 * Ownership: the host still owns the run and its progress. This adapter owns nothing but a read-only
 * projection of that value (`stateIn` over the host's flow), so it cannot become a second writer — it
 * has no setter and no other state.
 *
 * The mapping is deliberately loss-less where it matters and lossy where it does not: the console's log
 * lines collapse to the last one (which is the only line a first-run screen can use), while the phase
 * keeps cancellation distinct from failure so the page never reports a cancel as an error.
 */
internal class DshContainerInstallAdapter(
    /** Application context only: an install outlives the Activity that started it. */
    private val context: Context,
    private val host: DshContainerHost = DshContainerHost.of(context.applicationContext)
) : DshContainerInstallPort {

    /** Contains only the projection coroutine; the install itself runs on the host's own scope. */
    private val projectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val state: StateFlow<DshContainerInstallSnapshot> =
        host.installProgress
            .map { it.toSnapshot() }
            .stateIn(
                scope = projectionScope,
                started = SharingStarted.Eagerly,
                initialValue = host.installProgress.value.toSnapshot()
            )

    override fun start() {
        val layout = DshContainerLayout.forApp(
            filesDir = context.filesDir.absolutePath,
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir
        )
        host.startInstall(AndroidDshContainerAssets(context.assets), layout)
    }

    override fun cancel() = host.cancelInstall()
}

/**
 * Reduces the console-facing value to the three facts a first-run page can honestly render.
 *
 * `done` alone cannot distinguish success from failure, so the order of the checks matters: a
 * cancelled run is never a failure, and only a run that finished without a failure text succeeded.
 */
internal fun DshContainerInstallUi.toSnapshot(): DshContainerInstallSnapshot {
    val phase = when {
        cancelled -> DshContainerInstallSnapshot.Phase.CANCELLED
        running -> DshContainerInstallSnapshot.Phase.RUNNING
        failure != null -> DshContainerInstallSnapshot.Phase.FAILED
        done -> DshContainerInstallSnapshot.Phase.SUCCEEDED
        else -> DshContainerInstallSnapshot.Phase.IDLE
    }
    return DshContainerInstallSnapshot(
        phase = phase,
        progressPercent = fraction?.let { (it * 100f).roundToInt().coerceIn(0, 100) },
        detail = failure ?: lines.lastOrNull()?.text
    )
}
