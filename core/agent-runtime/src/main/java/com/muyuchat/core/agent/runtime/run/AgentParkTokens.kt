package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentContract
import java.util.concurrent.ConcurrentHashMap

/** The durable park that produced a wake token. */
enum class AgentParkKind { APPROVAL, USER, FRAME }

/**
 * One durable park/wake token (S4).
 *
 * It is written after the run's WAITING_APPROVAL/WAITING_USER transition commits and carries the
 * exact resume material plus the identity a later wake must match:
 * `(runId, epoch, checkpointRevision, wakeId)`. The transcript is the strict JSON produced by
 * [AgentParkTranscriptCodec]; it is never a second run state, only the loop's resume input.
 *
 * [wakeId] is derived deterministically from `(runId, revision, kind)`, so a replayed park writes
 * the same token instead of accumulating one row per attempt.
 */
data class AgentParkToken(
    val runId: String,
    val epoch: Long,
    val checkpointRevision: Long,
    val wakeId: String,
    val kind: AgentParkKind,
    val approvalId: String?,
    val invocationId: String?,
    val questionId: String?,
    val transcriptJson: String,
    val createdAtMillis: Long
) {
    init {
        AgentContract.requireId(runId, "runId")
        AgentContract.requireId(wakeId, "wakeId")
        require(epoch > 0L) { "epoch must be positive" }
        require(checkpointRevision > 0L) { "checkpointRevision must be positive" }
        require(createdAtMillis > 0L) { "createdAtMillis must be positive" }
        require(transcriptJson.isNotBlank()) { "transcriptJson must not be blank" }
        when (kind) {
            AgentParkKind.APPROVAL -> require(
                approvalId != null && invocationId != null && questionId == null
            ) { "an approval park binds exactly one approval and invocation" }

            AgentParkKind.USER -> require(
                questionId != null && approvalId == null && invocationId == null
            ) { "a user park binds exactly one question" }

            AgentParkKind.FRAME -> Unit
        }
    }

    fun transcript(): AgentLoopTranscript = AgentParkTranscriptCodec.decode(transcriptJson)
}

/** Outcome of one wake attempt against the durable token and the run row. */
sealed interface AgentParkWakeResult {
    /** The run is still parked; the wake changed nothing and may be repeated safely. */
    data class StillParked(val runId: String, val kind: AgentParkKind) : AgentParkWakeResult

    /**
     * A user answer was already durable when the process stopped; the wake re-queued the exact
     * resume through the single admission path. An approval is never resumed this way.
     */
    data class Resumed(val runId: String, val wakeId: String) : AgentParkWakeResult

    /** The token was already consumed or its wake id no longer matches. */
    data object AlreadyConsumed : AgentParkWakeResult

    /**
     * The token no longer belongs to the durable run; it was deleted. No resume material is used
     * and no executor is called.
     */
    data class Stale(
        val runId: String,
        val errorCode: String,
        val safeMessage: String
    ) : AgentParkWakeResult
}

/**
 * Durable token store. The runtime writes one token per parked run; only the owner decides when a
 * token is cleared or consumed.
 */
interface AgentParkTokenStore {
    suspend fun record(token: AgentParkToken)

    suspend fun load(runId: String): AgentParkToken?

    suspend fun delete(runId: String)

    suspend fun list(): List<AgentParkToken>

    /**
     * One-shot CAS: removes and returns the token only when [wakeId] matches the durable row.
     * Null for an unknown run, an already-consumed token or a stale wake id; the row is untouched.
     */
    suspend fun consume(runId: String, wakeId: String): AgentParkToken?
}

/** Owner with no durable store keeps the historical in-memory park contract. */
object NoAgentParkTokenStore : AgentParkTokenStore {
    override suspend fun record(token: AgentParkToken) = Unit
    override suspend fun load(runId: String): AgentParkToken? = null
    override suspend fun delete(runId: String) = Unit
    override suspend fun list(): List<AgentParkToken> = emptyList()
    override suspend fun consume(runId: String, wakeId: String): AgentParkToken? = null
}

/** Deterministic in-memory store used by owner tests and harnesses without a database. */
class InMemoryAgentParkTokenStore : AgentParkTokenStore {
    private val tokens = ConcurrentHashMap<String, AgentParkToken>()

    override suspend fun record(token: AgentParkToken) {
        tokens[token.runId] = token
    }

    override suspend fun load(runId: String): AgentParkToken? = tokens[runId]

    override suspend fun delete(runId: String) {
        tokens.remove(runId)
    }

    override suspend fun list(): List<AgentParkToken> = tokens.values.toList()

    override suspend fun consume(runId: String, wakeId: String): AgentParkToken? {
        val current = tokens[runId] ?: return null
        if (current.wakeId != wakeId) return null
        return if (tokens.remove(runId, current)) current else null
    }
}
