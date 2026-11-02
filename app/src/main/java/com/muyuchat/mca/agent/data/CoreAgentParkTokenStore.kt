package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.runtime.run.AgentParkKind
import com.muyuchat.core.agent.runtime.run.AgentParkToken
import com.muyuchat.core.agent.runtime.run.AgentParkTokenStore

/**
 * Runtime-facing adapter over the Room-owned `agent_park_tokens` rows.
 *
 * The core runtime owns the meaning of a token; this adapter is the only place that maps the row
 * shape and the [AgentParkKind] enum. An unknown stored kind is treated as "no token": the run then
 * follows the normal startup fence instead of resuming from material this build cannot interpret.
 */
class CoreAgentParkTokenStore(
    private val persistence: AgentParkTokenPersistence
) : AgentParkTokenStore {
    override suspend fun record(token: AgentParkToken) {
        persistence.record(token.toEntity())
    }

    override suspend fun load(runId: String): AgentParkToken? =
        persistence.load(runId)?.toCoreOrNull()

    override suspend fun delete(runId: String) {
        persistence.delete(runId)
    }

    override suspend fun list(): List<AgentParkToken> =
        persistence.list().mapNotNull { it.toCoreOrNull() }

    override suspend fun consume(runId: String, wakeId: String): AgentParkToken? =
        persistence.consume(runId, wakeId)?.toCoreOrNull()

    private fun AgentParkToken.toEntity(): AgentParkTokenEntity = AgentParkTokenEntity(
        runId = runId,
        epoch = epoch,
        checkpointRevision = checkpointRevision,
        wakeId = wakeId,
        kind = kind.name,
        approvalId = approvalId,
        invocationId = invocationId,
        questionId = questionId,
        transcriptJson = transcriptJson,
        createdAtMillis = createdAtMillis
    )

    private fun AgentParkTokenEntity.toCoreOrNull(): AgentParkToken? {
        val parsedKind = AgentParkKind.entries.firstOrNull { it.name == kind } ?: return null
        return runCatching {
            AgentParkToken(
                runId = runId,
                epoch = epoch,
                checkpointRevision = checkpointRevision,
                wakeId = wakeId,
                kind = parsedKind,
                approvalId = approvalId,
                invocationId = invocationId,
                questionId = questionId,
                transcriptJson = transcriptJson,
                createdAtMillis = createdAtMillis
            )
        }.getOrNull()
    }
}
