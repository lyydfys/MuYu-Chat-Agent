package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentArtifactCommit
import com.muyuchat.core.agent.contract.AgentArtifactCommitPhase
import com.muyuchat.core.agent.contract.AgentArtifactCommitState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory artifact commit ledger for the JVM crash/recovery tests. */
class FakeArtifactLedger(
    private val clock: AgentClock = AgentClock { System.nanoTime() / 1_000_000 }
) : AgentArtifactLedger {
    val commits = linkedMapOf<String, AgentArtifactCommit>()
    val artifacts = linkedMapOf<String, AgentStoredArtifact>()
    private val latest = linkedMapOf<Pair<String, String>, AgentStoredArtifact>()
    private val mutex = Mutex()

    /** Test hook executed inside commitArtifact after the precondition re-read (race fixture). */
    var mutateLatestBeforeCommit: (() -> Unit)? = null

    override suspend fun begin(commit: AgentArtifactCommit, artifact: AgentStoredArtifact): Boolean =
        mutex.withLock {
            if (commits.containsKey(commit.commitId)) return@withLock false
            commits[commit.commitId] = commit
            artifacts[artifact.artifact.artifactId] = artifact
            true
        }

    override suspend fun advance(
        commitId: String,
        phase: AgentArtifactCommitPhase,
        updatedAtMillis: Long,
        expectedFrom: Set<AgentArtifactCommitPhase>
    ): Boolean = mutex.withLock {
        val current = commits[commitId] ?: return@withLock false
        if (current.phase !in expectedFrom) return@withLock false
        commits[commitId] = current.copy(phase = phase, updatedAtMillis = updatedAtMillis)
        true
    }

    override suspend fun findCommit(commitId: String): AgentArtifactCommit? =
        mutex.withLock { commits[commitId] }

    override suspend fun findArtifact(artifactId: String): AgentStoredArtifact? =
        mutex.withLock { artifacts[artifactId] }

    override suspend fun nextVersion(taskId: String, logicalPath: String): Long =
        mutex.withLock {
            (artifacts.values
                .filter { it.artifact.taskId == taskId && it.artifact.relativePath == logicalPath }
                .maxOfOrNull { it.version } ?: 0L) + 1
        }

    override suspend fun latestCommitted(taskId: String, logicalPath: String): AgentStoredArtifact? =
        mutex.withLock {
            latest[taskId to logicalPath]?.takeIf {
                it.artifact.commitState == AgentArtifactCommitState.COMMITTED
            }
        }

    override suspend fun listLatestCommitted(
        taskId: String,
        limit: Int
    ): List<AgentStoredArtifact> = mutex.withLock {
        artifacts.values
            .filter { it.artifact.taskId == taskId && it.isLatest }
            .sortedBy { it.artifact.relativePath }
            .take(limit)
    }

    override suspend fun markFailed(commitId: String, updatedAtMillis: Long) {
        mutex.withLock {
            val commit = commits[commitId] ?: return@withLock
            commits[commitId] = commit.copy(
                phase = AgentArtifactCommitPhase.FAILED,
                updatedAtMillis = updatedAtMillis
            )
            artifacts[commit.artifactId]?.let { stored ->
                artifacts[commit.artifactId] = stored.copy(
                    artifact = stored.artifact.copy(commitState = AgentArtifactCommitState.FAILED),
                    isLatest = false
                )
                latest.remove(stored.artifact.taskId to stored.artifact.relativePath)
            }
        }
    }

    override suspend fun unsettledCommits(taskId: String, limit: Int): List<AgentArtifactCommit> =
        mutex.withLock {
            commits.values
                .filter { commit ->
                    val artifact = artifacts[commit.artifactId]
                    artifact?.artifact?.taskId == taskId && commit.phase in setOf(
                        AgentArtifactCommitPhase.PREPARING,
                        AgentArtifactCommitPhase.STAGED,
                        AgentArtifactCommitPhase.RENAMED
                    )
                }
                .sortedBy { it.updatedAtMillis }
                .take(limit)
        }

    override suspend fun commitArtifact(
        commitId: String,
        storedArtifact: AgentStoredArtifact,
        committedAtMillis: Long,
        invocationResultRef: String,
        event: AgentArtifactCommitEvent?
    ): AgentArtifactCommitDecision = mutex.withLock {
        mutateLatestBeforeCommit?.invoke()
        val commit = commits[commitId]
            ?: return@withLock AgentArtifactCommitDecision.Conflict("missing commit")
        if (commit.phase !in setOf(AgentArtifactCommitPhase.RENAMED, AgentArtifactCommitPhase.STAGED)) {
            return@withLock AgentArtifactCommitDecision.Conflict("phase ${commit.phase}")
        }
        val key = storedArtifact.artifact.taskId to storedArtifact.artifact.relativePath
        val current = latest[key]
        val preconditionError = when {
            commit.expectedOldHash == null && current != null ->
                "path already exists"
            commit.expectedOldHash != null && current == null ->
                "path does not exist"
            commit.expectedOldHash != null && current!!.artifact.sha256 != commit.expectedOldHash ->
                "content changed"
            else -> null
        }
        if (preconditionError != null) {
            commits[commitId] = commit.copy(
                phase = AgentArtifactCommitPhase.FAILED,
                updatedAtMillis = committedAtMillis
            )
            artifacts[commit.artifactId]?.let { stored ->
                artifacts[commit.artifactId] = stored.copy(
                    artifact = stored.artifact.copy(commitState = AgentArtifactCommitState.FAILED),
                    isLatest = false
                )
            }
            return@withLock AgentArtifactCommitDecision.PreconditionChanged(preconditionError)
        }
        val version = (artifacts.values
            .filter {
                it.artifact.taskId == key.first &&
                    it.artifact.relativePath == key.second &&
                    it.artifact.artifactId != commit.artifactId
            }
            .maxOfOrNull { it.version } ?: 0L) + 1
        val committed = storedArtifact.copy(
            artifact = storedArtifact.artifact.copy(
                commitState = AgentArtifactCommitState.COMMITTED,
                sizeBytes = commit.newSizeBytes,
                sha256 = commit.newHash
            ),
            version = version,
            isLatest = true,
            commitId = commitId
        )
        artifacts.values
            .filter {
                it.artifact.taskId == key.first &&
                    it.artifact.relativePath == key.second &&
                    it.artifact.artifactId != commit.artifactId &&
                    it.isLatest
            }
            .forEach { previous ->
                artifacts[previous.artifact.artifactId] = previous.copy(isLatest = false)
            }
        artifacts[commit.artifactId] = committed
        latest[key] = committed
        commits[commitId] = commit.copy(
            phase = AgentArtifactCommitPhase.COMMITTED,
            updatedAtMillis = committedAtMillis
        )
        AgentArtifactCommitDecision.Committed(committed)
    }

    fun latest(taskId: String, logicalPath: String): AgentStoredArtifact? =
        latest[taskId to logicalPath]

    /** Race fixture: replace the latest committed mapping just before the DB commit re-check. */
    fun mutateLatestForTest(
        taskId: String,
        logicalPath: String,
        transform: (AgentStoredArtifact) -> AgentStoredArtifact
    ) {
        val key = taskId to logicalPath
        latest[key]?.let { latest[key] = transform(it) }
    }

    fun clockNow(): Long = clock.nowMillis()
}
