package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentContract
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Runtime identity generation. Ids are opaque, stable and never derived from display text.
 * Callers pass a short lowercase prefix (task, workspace, run, step, invocation, event, attempt).
 */
fun interface AgentIdGenerator {
    fun nextId(prefix: String): String
}

/** Production generator: random UUIDs with a typed prefix. */
class UuidAgentIdGenerator : AgentIdGenerator {
    override fun nextId(prefix: String): String = AgentContract.requireId(
        requireNotNull(normalizePrefix(prefix)) + "-" + UUID.randomUUID(),
        "generated id"
    )

    private fun normalizePrefix(prefix: String): String? =
        prefix.trim().lowercase().takeIf { it.matches(Regex("^[a-z][a-z0-9]{0,15}$")) }
}

/** Deterministic generator for tests and reproducible fixtures. */
class SequentialAgentIdGenerator(
    private val namespace: String = "test"
) : AgentIdGenerator {
    private val counter = AtomicLong(0L)

    override fun nextId(prefix: String): String {
        val safePrefix = prefix.trim().lowercase()
        require(safePrefix.matches(Regex("^[a-z][a-z0-9]{0,15}$"))) { "invalid id prefix: '$prefix'" }
        return AgentContract.requireId("$namespace-$safePrefix-${counter.incrementAndGet()}", "generated id")
    }
}
