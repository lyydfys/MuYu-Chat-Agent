package com.muyuchat.mca.agent.data

/**
 * App-side durable park/wake contract over `agent_park_tokens`.
 *
 * This is deliberately a local app data-layer contract, not the core runtime contract: the runtime
 * `AgentParkToken` type lives in `:core:agent-runtime`, while the row belongs to the single
 * `mca.db` owner here. The Lead wires them with a thin adapter, so neither layer imports the other.
 *
 * The methods speak [AgentParkTokenEntity] because the store is the persistence boundary: mapping
 * `kind` to/from the core enum belongs to the adapter that also owns the core type.
 */
interface AgentParkTokenPersistence {
    /** Inserts or replaces the single token row for `token.runId`. */
    suspend fun record(token: AgentParkTokenEntity)

    /** Returns the durable token for [runId], or null when the run is not parked. */
    suspend fun load(runId: String): AgentParkTokenEntity?

    /** Removes the token unconditionally; true when a row was deleted. */
    suspend fun delete(runId: String): Boolean

    /** All parked tokens, oldest first, for startup recovery and inspection. */
    suspend fun list(): List<AgentParkTokenEntity>

    /**
     * One-shot wake CAS: removes and returns the token only when [wakeId] matches the durable row.
     * Returns null for an unknown run, an already-consumed token, or a stale wake id; in every
     * null case the durable row (if any) is left untouched.
     */
    suspend fun consume(runId: String, wakeId: String): AgentParkTokenEntity?
}

/**
 * Room-backed [AgentParkTokenPersistence].
 *
 * The constructor takes only the DAO so the store is testable on the JVM with a fake DAO and has no
 * Android or composition-root dependency. Atomicity of [consume] comes from the DAO's
 * `@Transaction` method; this class adds no state of its own.
 */
class RoomAgentParkTokenStore(
    private val dao: AgentParkTokenDao
) : AgentParkTokenPersistence {
    override suspend fun record(token: AgentParkTokenEntity) {
        dao.upsert(token)
    }

    override suspend fun load(runId: String): AgentParkTokenEntity? = dao.find(runId)

    override suspend fun delete(runId: String): Boolean = dao.delete(runId) > 0

    override suspend fun list(): List<AgentParkTokenEntity> = dao.listAll()

    override suspend fun consume(runId: String, wakeId: String): AgentParkTokenEntity? =
        dao.consume(runId, wakeId)
}
