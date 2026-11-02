package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.runtime.run.AgentParkWakeResult
import kotlinx.coroutines.CancellationException

internal sealed interface AgentParkWakeRunResult {
    data class Completed(val outcome: AgentParkWakeResult) : AgentParkWakeRunResult

    data class Failed(val error: Throwable) : AgentParkWakeRunResult
}

/**
 * One durable park wake, without Android or WorkManager types.
 *
 * [wake] is always the process-scoped owner entry; this runner never builds a second runtime.
 * [settle] runs exactly once on success, failure and cancellation so a worker-only process can
 * release its borrowed root without an Activity.
 */
internal class AgentParkWakeRunner(
    private val wake: suspend () -> AgentParkWakeResult,
    private val settle: () -> Unit
) {
    suspend fun run(): AgentParkWakeRunResult = try {
        AgentParkWakeRunResult.Completed(wake())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        AgentParkWakeRunResult.Failed(error)
    } finally {
        settle()
    }
}
