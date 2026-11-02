package com.muyuchat.mca.agent.data

import androidx.room.RoomDatabase
import androidx.room.withTransaction

/**
 * Transaction seam so the Room journal can commit every transition atomically while unit tests can
 * substitute a deterministic runner without Android/Room.
 */
interface AgentTransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

/** The production runner. Every journal/ledger write goes through exactly one Room transaction. */
class RoomAgentTransactionRunner(
    private val database: RoomDatabase
) : AgentTransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.withTransaction { block() }
}
