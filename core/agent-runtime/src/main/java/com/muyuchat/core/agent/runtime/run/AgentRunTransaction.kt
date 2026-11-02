package com.muyuchat.core.agent.runtime.run

/**
 * One durable unit of work for approval resolution (N03 / DESIGN 4.2).
 *
 * The approval CAS (`PENDING -> CONSUMED`), the invocation state and the run/ledger transition must
 * commit together or not at all. The runtime cannot depend on Room, so the owner calls this port;
 * production injects the app's Room transaction runner (both stores share one database), tests use
 * [Direct] or a recording fake.
 *
 * Tool execution must stay outside this block: no network/native/user interaction may run while a
 * database transaction is open.
 */
interface AgentRunTransaction {
    suspend fun <T> inTransaction(block: suspend () -> T): T

    /** No transaction boundary (compatibility default for harnesses that own no shared store). */
    object Direct : AgentRunTransaction {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }
}
