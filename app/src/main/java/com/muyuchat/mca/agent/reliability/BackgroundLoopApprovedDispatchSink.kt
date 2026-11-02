package com.muyuchat.mca.agent.reliability

import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxEntry
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxStore
import com.muyuchat.core.agent.runtime.run.AgentApprovedDispatch
import com.muyuchat.core.agent.runtime.run.AgentApprovedDispatchHandoff
import com.muyuchat.core.agent.runtime.run.AgentApprovedDispatchOffer
import com.muyuchat.core.agent.runtime.run.AgentApprovedDispatchSink
import kotlinx.coroutines.CancellationException

/**
 * Production approval→outbox bridge (N03).
 *
 * The owner offers the exact in-memory authorized resume; this sink publishes it to the
 * single-consumer hand-off and wakes the background loop. The loop's dispatcher then claims the
 * outbox row and calls `AgentRunOwner.dispatchApproved`, which consumes that same hand-off exactly
 * once. No approval is consumed here and no execution engine is created.
 *
 * If the loop is not running (composition mis-wired or shutting down), the sink marks the matching
 * outbox row [AgentDispatchOutboxStatus.BYPASSED] and returns
 * [AgentApprovedDispatchOffer.NotAccepted], so the owner falls back to its legacy inline launch.
 * `BYPASSED` is terminal: a later pass can never dispatch the same approval a second time. If the
 * process dies between that mark and the inline launch, the run stays RUNNING/APPROVED and startup
 * recovery fences it — the outbox never reports success.
 */
class BackgroundLoopApprovedDispatchSink(
    private val handoff: AgentApprovedDispatchHandoff,
    /**
     * Late-bound because the loop is constructed after the owner (the loop's executor needs the
     * owner, and the owner needs this sink). Returning null means no consumer is available.
     */
    private val wakeTargetProvider: () -> AgentOutboxWakeTarget?,
    private val outbox: AgentDispatchOutboxStore,
    private val clock: () -> Long = System::currentTimeMillis
) : AgentApprovedDispatchSink {

    constructor(
        handoff: AgentApprovedDispatchHandoff,
        wakeTarget: AgentOutboxWakeTarget,
        outbox: AgentDispatchOutboxStore,
        clock: () -> Long = System::currentTimeMillis
    ) : this(handoff, { wakeTarget }, outbox, clock)

    override suspend fun offer(dispatch: AgentApprovedDispatch): AgentApprovedDispatchOffer {
        val wakeTarget = wakeTargetProvider() ?: return bypass(dispatch)
        if (!wakeTarget.isRunning()) return bypass(dispatch)
        handoff.publish(dispatch)
        return when (wakeTarget.wake()) {
            AgentOutboxWakeResult.Accepted,
            AgentOutboxWakeResult.Coalesced -> AgentApprovedDispatchOffer.HandedOff

            AgentOutboxWakeResult.NotRunning -> {
                handoff.discard(dispatch.invocationId)
                bypass(dispatch)
            }
        }
    }

    private suspend fun bypass(dispatch: AgentApprovedDispatch): AgentApprovedDispatchOffer {
        try {
            val key = AgentDispatchOutboxEntry.keyFor(dispatch.invocationId)
            val entry = outbox.read(key)
            if (entry != null && !entry.status.isTerminal) {
                outbox.settle(
                    idempotencyKey = key,
                    expectedRevision = entry.revision,
                    status = AgentDispatchOutboxStatus.BYPASSED,
                    invocationStatus = null,
                    errorCode = IN_PROCESS_FALLBACK,
                    nowMillis = clock()
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The owner fallback still launches; the startup reconciler aligns the row from the
            // invocation ledger if the file write failed.
        }
        return AgentApprovedDispatchOffer.NotAccepted(
            IN_PROCESS_FALLBACK,
            "The outbox loop is not running; the owner dispatches this approval inline."
        )
    }

    private companion object {
        const val IN_PROCESS_FALLBACK: String = "in_process_dispatch_fallback"
    }
}
