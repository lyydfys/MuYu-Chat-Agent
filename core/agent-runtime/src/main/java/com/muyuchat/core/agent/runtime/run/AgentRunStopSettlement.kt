package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentModelAttemptRef

/**
 * Typed result of settling the real resource fence for one stopped run.
 *
 * Only [Released] allows the owner to write the single CANCELLED terminal and release the active
 * slot. [StoppingUnknown] and [Failed] keep the run in STOPPING so a resource cannot be reused
 * before the native/network owner is proven quiet.
 */
sealed interface AgentRunStopSettlement {
    /** The exact execution owner proved quiescence (or no native work was ever dispatched). */
    data class Released(val evidence: String? = null) : AgentRunStopSettlement

    /** Stop was requested but quiescence is not proven yet; a late proof may still arrive. */
    data class StoppingUnknown(val detail: String) : AgentRunStopSettlement

    /** The settlement machinery itself failed; the run must remain STOPPING. */
    data class Failed(val detail: String) : AgentRunStopSettlement
}

/**
 * Identity-bound stop-settlement receipt. [runEpoch] and [attempt] exist so a late proof from a
 * retired execution cannot settle a replacement run or a different run.
 */
data class AgentStopSettlementReceipt(
    val runId: String,
    val runEpoch: Long,
    val settlement: AgentRunStopSettlement,
    val attempt: AgentModelAttemptRef? = null
) {
    init {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(runEpoch > 0L) { "runEpoch must be positive" }
    }
}

/**
 * App-scope source of the real stop settlement for exactly one run.
 *
 * The source owns the native/network lease fence (for the local model that is
 * `AgentModelLeaseRetention` + the engine quiescence proof). It must be bounded: it may not wait
 * forever for a model or network call that never returns. Returning
 * [AgentRunStopSettlement.StoppingUnknown] is the correct answer when quiescence is not proven; the
 * owner keeps STOPPING and accepts a later [AgentStopSettlementReceipt] through
 * [AgentRunOwner.reportStopSettlement].
 *
 * A run with no retained lease or no live attempt (for example a run stopped while waiting for
 * approval) is [AgentRunStopSettlement.Released]: there is no resource fence to hold. Only a real
 * lease whose proof is missing is [AgentRunStopSettlement.StoppingUnknown].
 *
 * Local model wiring: read `AgentModelLeaseRetention` for every entry whose attempt belongs to
 * [runId]. RELEASED (or no entry) is [AgentRunStopSettlement.Released]; STOPPING/FENCE_HELD is
 * [AgentRunStopSettlement.StoppingUnknown]. When a later engine proof releases the fence, the
 * app-scope settlement task reports the new receipt through [AgentRunOwner.reportStopSettlement].
 *
 * There is deliberately no default implementation: composing an owner without a source keeps the
 * legacy "joined executor means settled" compatibility contract only, and is never evidence of a
 * native stop.
 */
fun interface AgentRunStopSettlementSource {
    suspend fun settlementFor(runId: String, runEpoch: Long): AgentStopSettlementReceipt
}
