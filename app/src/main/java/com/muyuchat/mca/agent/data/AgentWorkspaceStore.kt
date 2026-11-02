package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentFileResult
import com.muyuchat.core.agent.contract.AgentListResult
import com.muyuchat.core.agent.contract.AgentReadResult
import com.muyuchat.core.agent.contract.AgentSearchResult
import com.muyuchat.core.agent.contract.AgentWorkspaceFilePort
import com.muyuchat.core.agent.contract.AgentWriteResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The frozen [AgentWorkspaceFilePort] implementation used by the four built-in tools (K08).
 *
 * Read operations are delegated to the bounded [AgentWorkspaceReadStore]; writes go through the
 * commit-journaled [AgentArtifactStore]. This class is the single port object the composition root
 * injects, while each half stays independently testable.
 *
 * [reconcile]/[reconcileTask] expose crash recovery for startup (K11): they hash the surviving file
 * and settle the `agent_artifact_commits` journal; they never re-run a tool.
 */
class AgentWorkspaceStore(
    agentRoot: File,
    ledger: AgentArtifactLedger,
    clock: AgentClock = AgentClock.system(),
    idGenerator: () -> String = { UUID.randomUUID().toString() },
    crashInjector: AgentArtifactCrashInjector = AgentArtifactCrashInjector.NONE,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AgentWorkspaceFilePort {
    private val readStore = AgentWorkspaceReadStore(agentRoot, ledger, ioDispatcher)

    /**
     * The single commit-journaled artifact store shared with K14 export/recovery. Exposing the same
     * instance keeps one ledger/store configuration in composition (no second physical store).
     */
    val artifactStore: AgentArtifactStore = AgentArtifactStore(
        agentRoot = agentRoot,
        ledger = ledger,
        clock = clock,
        idGenerator = idGenerator,
        crashInjector = crashInjector,
        ioDispatcher = ioDispatcher
    )

    override suspend fun list(
        workspaceId: String,
        taskId: String,
        path: String,
        limit: Int,
        cursor: String?
    ): AgentFileResult<AgentListResult> =
        readStore.list(workspaceId, taskId, path, limit, cursor)

    override suspend fun readText(
        workspaceId: String,
        taskId: String,
        path: String,
        offsetBytes: Long,
        limitBytes: Int
    ): AgentFileResult<AgentReadResult> =
        readStore.readText(workspaceId, taskId, path, offsetBytes, limitBytes)

    override suspend fun searchText(
        workspaceId: String,
        taskId: String,
        query: String,
        path: String,
        maxMatches: Int
    ): AgentFileResult<AgentSearchResult> =
        readStore.searchText(workspaceId, taskId, query, path, maxMatches)

    override suspend fun writeArtifact(
        workspaceId: String,
        taskId: String,
        invocationId: String,
        logicalPath: String,
        content: String,
        expectedOldHash: String?,
        policyVersion: Long
    ): AgentFileResult<AgentWriteResult> =
        artifactStore.writeArtifact(
            workspaceId = workspaceId,
            taskId = taskId,
            invocationId = invocationId,
            logicalPath = logicalPath,
            content = content,
            expectedOldHash = expectedOldHash,
            policyVersion = policyVersion
        )

    fun ensureTaskRoots(workspaceId: String, taskId: String): File =
        readStore.ensureTaskRoots(workspaceId, taskId)

    fun taskRoot(workspaceId: String, taskId: String): File =
        readStore.taskRoot(workspaceId, taskId)

    suspend fun reconcile(
        workspaceId: String,
        taskId: String,
        commitId: String
    ): AgentArtifactRecoveryResult = artifactStore.reconcile(workspaceId, taskId, commitId)

    suspend fun reconcileTask(
        workspaceId: String,
        taskId: String,
        limit: Int = AgentDataLimits.MAX_ARTIFACT_PAGE_LIMIT
    ): List<AgentArtifactRecoveryResult> = artifactStore.reconcileTask(workspaceId, taskId, limit)

    suspend fun findCommittedArtifact(
        workspaceId: String,
        taskId: String,
        artifactId: String
    ): AgentStoredArtifact? = artifactStore.findCommitted(workspaceId, taskId, artifactId)
}
