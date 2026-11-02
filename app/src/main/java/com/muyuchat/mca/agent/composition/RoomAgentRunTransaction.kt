package com.muyuchat.mca.agent.composition

import com.muyuchat.core.agent.runtime.run.AgentRunTransaction
import com.muyuchat.mca.agent.data.AgentTransactionRunner

/**
 * Bridges the runtime's platform-free [AgentRunTransaction] port onto the app's one Room runner.
 *
 * Both the approval store and the agent journal are DAOs of the same `McaRoomDatabase`, so wrapping
 * the approval CAS, invocation state and run/ledger transition in this transaction makes them a
 * single unit: a failure in any of them rolls all of them back (N03). The runtime never imports
 * Room, and this adapter holds no business rule.
 */
internal class RoomAgentRunTransaction(
    private val transactions: AgentTransactionRunner
) : AgentRunTransaction {
    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        transactions.inTransaction(block)
}
