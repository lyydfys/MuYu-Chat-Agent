package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentModelTurn
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolSideEffect

/**
 * N06: at most this many read-only executor calls may be in flight for one batch.
 *
 * The batch itself is bounded by [AgentPromptedLoopCoordinator.MAX_BATCH_CALLS]; only the executor
 * invocations overlap (never journal/transcript writes), so a fourth call waits for a free slot.
 */
internal const val MAX_PARALLEL_READ_ONLY_CALLS: Int = 3

/**
 * Returns a safe reason why a native batch must not execute, or null when it is a bounded
 * read-only batch that may run.
 *
 * A single non-read-only call, a missing provider call id or an oversized batch refuses the whole
 * batch before dispatch: the run never executes a partial set of calls and never silently drops one.
 * READ_ONLY also excludes `NETWORK` and `EXTERNAL_SIDE_EFFECT`, so no call in a parallel batch may
 * perform external egress.
 */
internal fun batchProblem(turn: AgentModelTurn, catalog: AgentToolCatalog): String? {
    if (turn.calls.size > AgentPromptedLoopCoordinator.MAX_BATCH_CALLS) {
        return "The batch exceeds ${AgentPromptedLoopCoordinator.MAX_BATCH_CALLS} calls."
    }
    if (turn.calls.any { it.callId.isNullOrBlank() }) {
        return "Every batch call needs a provider call id."
    }
    val nonReadOnly = turn.calls.firstOrNull { call ->
        val executor = catalog.find(call.name) ?: return@firstOrNull false
        executor.descriptor.sideEffect != AgentToolSideEffect.READ_ONLY
    }
    if (nonReadOnly != null) return "Only read-only calls may be batched."
    return null
}
