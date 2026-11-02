package com.muyuchat.mca.agent.reliability

import com.muyuchat.core.agent.runtime.journal.AgentDispatchExecution
import com.muyuchat.core.agent.runtime.journal.AgentDispatchExecutor
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxEntry
import com.muyuchat.core.agent.runtime.run.AgentRunOwner

/**
 * Production `AgentDispatchExecutor` for the dispatch outbox (N03).
 *
 * It delegates to [AgentRunOwner.dispatchApproved], which consumes the exact in-memory authorized
 * resume and starts the existing run loop. The executor never consumes an approval, never rebuilds
 * an `AuthorizedToolCommand` from storage and never creates a second owner/engine:
 * - no matching live authorization -> [AgentDispatchExecution.Refused] with zero side effects;
 * - run/epoch/slot mismatch -> typed [AgentDispatchExecution.Refused];
 * - accepted -> [AgentDispatchExecution.HandedOff]; the durable terminal invocation then settles
 *   the outbox row, or the startup reconciler fences an unknown receipt.
 *
 * The function form of the constructor keeps the adapter unit-testable without a full owner.
 */
class OwnerApprovedDispatchExecutor(
    private val dispatchFn: suspend (AgentDispatchOutboxEntry) -> AgentDispatchExecution
) : AgentDispatchExecutor {

    constructor(owner: AgentRunOwner) : this(owner::dispatchApproved)

    override suspend fun dispatch(entry: AgentDispatchOutboxEntry): AgentDispatchExecution =
        dispatchFn(entry)
}
