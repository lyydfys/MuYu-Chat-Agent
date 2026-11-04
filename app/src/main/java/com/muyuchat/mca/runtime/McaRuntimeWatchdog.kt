package com.muyuchat.mca.runtime

import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The 15 s health loop behind "Web 掉了自动拉起".
 *
 * Everything is injected: the probe (gateway reachability), the runtime phase accessor, the restart
 * entry point, the logger and the clock. That keeps the loop testable and — more important — keeps it
 * from owning a second runtime: the only action it can take is calling the *existing* restart entry
 * its caller supplies.
 *
 * Lifecycle: [start] is idempotent per scope, [stop] cancels the job. The loop never restarts while a
 * generation is starting/stopping, because a restart during `STARTING` would race the owner's own
 * control mutex.
 */
internal class McaRuntimeWatchdog(
    private val probe: () -> Boolean,
    private val restart: suspend () -> Unit,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val probeIntervalMs: Long = WatchdogPolicy.PROBE_INTERVAL_MS,
    private val stateAccessor: () -> WatchdogState = { WatchdogState() },
    private val onState: (WatchdogState) -> Unit = {}
) {
    private var job: Job? = null
    private var state: WatchdogState? = null

    /** Current state for the self-check rows; null until the first probe ran in this process. */
    fun currentState(nowMillis: Long = now()): WatchdogState =
        state ?: WatchdogPolicy.initialState(nowMillis)

    fun isRunning(): Boolean = job?.isActive == true

    /** Starts the loop once; a second call is a no-op (no duplicate probes, no double restarts). */
    fun start(scope: CoroutineScope) {
        if (isRunning()) return
        state = state ?: stateAccessor()
        job = scope.launch {
            while (isActive) {
                val current = state ?: WatchdogPolicy.initialState(now())
                val decision = WatchdogPolicy.evaluate(ok = probe(), state = current, nowMillis = now())
                state = decision.state
                onState(decision.state)
                decision.logLine?.let(log)
                if (decision.action == WatchdogAction.RESTART) {
                    // The restart entry owns its own serialisation; a throw here must not kill the
                    // loop, and the next failed streak will decide again.
                    runCatching { restart() }
                        .onFailure { error -> log("watchdog: restart failed: ${error.message ?: error.javaClass.simpleName}") }
                }
                delay(probeIntervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /**
         * True only when a restart is meaningful: a READY generation whose HTTP surface is gone, or a
         * FAILED/STOPPED host. `STARTING`/`STOPPING` are transitional and must not be interrupted.
         */
        fun shouldProbe(snapshot: DshRuntimeSnapshot?): Boolean = when (snapshot?.phase) {
            null -> false
            DshRuntimePhase.READY, DshRuntimePhase.FAILED, DshRuntimePhase.STOPPED -> true
            else -> false
        }
    }
}

/**
 * Maps real runtime facts into the console's self-check rows.
 *
 * The caller supplies the facts (runtime snapshot, Web port, gateway reachability, PTY readiness,
 * configuration directory readability) so this class contains no Android dependency and the console
 * never has to guess.
 */
// The fact collector is McaRuntimeSelfCheckSource in McaRuntimeSelfCheck.kt; the row model lives in
// core:dsh-contract (`com.muyuchat.core.dsh.contract.runtime`), so the console feature can render rows
// without depending on this module.
