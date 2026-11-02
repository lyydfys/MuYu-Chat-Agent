package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentArtifactCommit
import com.muyuchat.core.agent.contract.AgentArtifactCommitPhase
import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventTypes

/**
 * Room implementation of the artifact commit ledger. Every method is one transaction; the filesystem
 * phase transitions are persisted here so a crash can be reconciled by hash without re-running a
 * tool (architecture 10.3).
 */
class RoomAgentArtifactLedger(
    private val daos: AgentJournalDaos,
    private val transactions: AgentTransactionRunner
) : AgentArtifactLedger {
    override suspend fun begin(
        commit: AgentArtifactCommit,
        artifact: AgentStoredArtifact
    ): Boolean = transactions.inTransaction {
        if (daos.artifactCommit.find(commit.commitId) != null) return@inTransaction false
        val invocation = daos.invocation.find(commit.invocationId)
            ?: throw AgentStorageException(
                AgentDataErrorCodes.STORAGE_ARTIFACT_COMMIT,
                "artifact commit references a missing invocation"
            )
        if (invocation.runId.isEmpty()) {
            throw AgentStorageException(
                AgentDataErrorCodes.STORAGE_ARTIFACT_COMMIT,
                "artifact commit invocation has no run"
            )
        }
        if (daos.task.find(artifact.artifact.taskId) == null) {
            throw AgentStorageException(
                AgentDataErrorCodes.STORAGE_ARTIFACT_COMMIT,
                "artifact commit references a missing task"
            )
        }
        daos.artifact.insert(AgentEntityMappers.toEntity(artifact))
        daos.artifactCommit.insert(
            AgentEntityMappers.toEntity(commit, artifact.artifact.createdAtMillis)
        )
        true
    }

    override suspend fun advance(
        commitId: String,
        phase: AgentArtifactCommitPhase,
        updatedAtMillis: Long,
        expectedFrom: Set<AgentArtifactCommitPhase>
    ): Boolean = daos.artifactCommit.advancePhase(
        commitId = commitId,
        phase = phase.encode(),
        updatedAt = updatedAtMillis,
        expectedPhases = expectedFrom.map { it.encode() }
    ) == 1

    override suspend fun findCommit(commitId: String): AgentArtifactCommit? =
        daos.artifactCommit.find(commitId)?.let(AgentEntityMappers::toContract)

    override suspend fun findArtifact(artifactId: String): AgentStoredArtifact? =
        daos.artifact.find(artifactId)?.let(AgentEntityMappers::toStoredArtifact)

    override suspend fun nextVersion(taskId: String, logicalPath: String): Long =
        daos.artifact.maxVersion(taskId, logicalPath) + 1

    override suspend fun latestCommitted(taskId: String, logicalPath: String): AgentStoredArtifact? {
        val entity = daos.artifact.findLatest(taskId, logicalPath) ?: return null
        val stored = AgentEntityMappers.toStoredArtifact(entity)
        return if (stored.artifact.commitState ==
            com.muyuchat.core.agent.contract.AgentArtifactCommitState.COMMITTED
        ) {
            stored
        } else {
            null
        }
    }

    override suspend fun listLatestCommitted(
        taskId: String,
        limit: Int
    ): List<AgentStoredArtifact> {
        require(limit in 1..AgentDataLimits.MAX_LIST_SCAN_ENTRIES) { "limit must be in 1..10000" }
        return daos.artifact.listLatestCommitted(taskId, limit)
            .map(AgentEntityMappers::toStoredArtifact)
    }

    override suspend fun markFailed(commitId: String, updatedAtMillis: Long) {
        transactions.inTransaction {
            val commit = daos.artifactCommit.find(commitId) ?: return@inTransaction
            daos.artifactCommit.advancePhase(
                commitId = commitId,
                phase = AgentArtifactCommitPhase.FAILED.encode(),
                updatedAt = updatedAtMillis,
                expectedPhases = listOf(
                    AgentArtifactCommitPhase.PREPARING.encode(),
                    AgentArtifactCommitPhase.STAGED.encode(),
                    AgentArtifactCommitPhase.RENAMED.encode(),
                    AgentArtifactCommitPhase.UNKNOWN.encode()
                )
            )
            daos.artifact.markFailed(commit.artifactId)
        }
    }

    override suspend fun unsettledCommits(taskId: String, limit: Int): List<AgentArtifactCommit> {
        require(limit in 1..AgentDataLimits.MAX_ARTIFACT_PAGE_LIMIT) { "limit must be in 1..100" }
        return daos.artifactCommit.listUnsettledForTask(taskId, limit)
            .map(AgentEntityMappers::toContract)
    }

    override suspend fun commitArtifact(
        commitId: String,
        storedArtifact: AgentStoredArtifact,
        committedAtMillis: Long,
        invocationResultRef: String,
        event: AgentArtifactCommitEvent?
    ): AgentArtifactCommitDecision = transactions.inTransaction {
        val commitEntity = daos.artifactCommit.find(commitId)
            ?: return@inTransaction AgentArtifactCommitDecision.Conflict("commit journal row is missing")
        val phase = AgentEnumCodec.decodeArtifactCommitPhase(commitEntity.phase)
        if (phase != AgentArtifactCommitPhase.RENAMED && phase != AgentArtifactCommitPhase.STAGED) {
            return@inTransaction AgentArtifactCommitDecision.Conflict(
                "artifact commit is in phase $phase"
            )
        }
        val artifactEntity = daos.artifact.find(commitEntity.artifactId)
            ?: return@inTransaction AgentArtifactCommitDecision.Conflict("artifact row is missing")
        if (artifactEntity.taskId != storedArtifact.artifact.taskId ||
            artifactEntity.relativePath != storedArtifact.artifact.relativePath ||
            commitEntity.artifactId != storedArtifact.artifact.artifactId
        ) {
            return@inTransaction AgentArtifactCommitDecision.Conflict(
                "artifact commit journal does not match the recorded artifact"
            )
        }
        val latest = daos.artifact.findLatest(
            storedArtifact.artifact.taskId,
            storedArtifact.artifact.relativePath
        )
        val preconditionError = when {
            commitEntity.expectedOldHash == null && latest != null ->
                "logical path already has a committed artifact"
            commitEntity.expectedOldHash != null && latest == null ->
                "logical path has no committed artifact to overwrite"
            commitEntity.expectedOldHash != null && latest!!.sha256 != commitEntity.expectedOldHash ->
                "logical path content changed since the command was frozen"
            else -> null
        }
        if (preconditionError != null) {
            daos.artifactCommit.advancePhase(
                commitId = commitId,
                phase = AgentArtifactCommitPhase.FAILED.encode(),
                updatedAt = committedAtMillis,
                expectedPhases = listOf(AgentArtifactCommitPhase.RENAMED.encode(), AgentArtifactCommitPhase.STAGED.encode())
            )
            daos.artifact.markFailed(commitEntity.artifactId)
            return@inTransaction AgentArtifactCommitDecision.PreconditionChanged(preconditionError)
        }
        val version = daos.artifact.maxVersionExcluding(
            taskId = storedArtifact.artifact.taskId,
            relativePath = storedArtifact.artifact.relativePath,
            artifactId = commitEntity.artifactId
        ) + 1
        daos.artifact.clearLatest(
            taskId = storedArtifact.artifact.taskId,
            relativePath = storedArtifact.artifact.relativePath,
            keepArtifactId = commitEntity.artifactId
        )
        val updated = daos.artifact.markCommitResult(
            artifactId = commitEntity.artifactId,
            commitState = com.muyuchat.core.agent.contract.AgentArtifactCommitState.COMMITTED.encode(),
            sizeBytes = commitEntity.newSizeBytes,
            sha256 = commitEntity.newHash,
            storageRef = storedArtifact.storageRef,
            mediaType = storedArtifact.artifact.mediaType,
            isLatest = 1,
            latestKey = storedArtifact.artifact.relativePath,
            commitId = commitId,
            version = version
        )
        check(updated == 1) { "artifact commit lost its artifact row" }
        val advanced = daos.artifactCommit.advancePhase(
            commitId = commitId,
            phase = AgentArtifactCommitPhase.COMMITTED.encode(),
            updatedAt = committedAtMillis,
            expectedPhases = listOf(AgentArtifactCommitPhase.RENAMED.encode(), AgentArtifactCommitPhase.STAGED.encode())
        )
        check(advanced == 1) { "artifact commit phase CAS failed" }
        val invocationUpdated = daos.invocation.markSucceeded(
            invocationId = commitEntity.invocationId,
            updatedAt = committedAtMillis
        )
        check(invocationUpdated == 1) {
            "artifact commit requires the invocation to be RUNNING"
        }
        if (event != null) {
            appendCommitEvent(commitEntity.invocationId, event)
        }
        AgentArtifactCommitDecision.Committed(
            storedArtifact.copy(
                version = version,
                isLatest = true,
                commitId = commitId
            )
        )
    }

    private suspend fun appendCommitEvent(invocationId: String, event: AgentArtifactCommitEvent) {
        val invocation = daos.invocation.find(invocationId)
            ?: throw AgentStorageException(AgentDataErrorCodes.STORAGE_CORRUPTION, "invocation disappeared")
        val run = daos.run.find(invocation.runId)
            ?: throw AgentStorageException(AgentDataErrorCodes.STORAGE_CORRUPTION, "run disappeared")
        val sequence = run.lastSequence + 1
        daos.event.insertAll(
            listOf(
                AgentEntityMappers.toEntity(
                    AgentEvent(
                        eventId = event.eventId,
                        runId = run.runId,
                        sequence = sequence,
                        type = event.type,
                        createdAtMillis = event.createdAtMillis,
                        commandId = event.commandId,
                        summary = event.summary
                    )
                )
            )
        )
        val rows = daos.run.advanceSequenceCas(
            runId = run.runId,
            lastSequence = sequence,
            revision = run.revision + 1,
            updatedAt = event.createdAtMillis,
            expectedRevision = run.revision
        )
        check(rows == 1) { "run sequence CAS failed while committing artifact" }
    }

    companion object {
        const val ARTIFACT_COMMITTED_EVENT: String = AgentEventTypes.ARTIFACT_COMMITTED
    }
}
