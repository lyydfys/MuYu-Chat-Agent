package com.muyuchat.mca.agent.data.quota

import com.muyuchat.mca.agent.data.AgentClock
import com.muyuchat.mca.agent.data.AgentGrantDao
import com.muyuchat.mca.agent.data.AgentJournalDaos
import com.muyuchat.mca.agent.data.AgentTransactionRunner
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * K14 composition facade: deletion policy + retry queue + staging GC over one Room/agent-root wiring.
 * The caller supplies the exact scoped cancel callback from the runtime owner; this facade never
 * starts, stops or queries a model by itself.
 */
class RoomAgentPackageMaintenance(
    daos: AgentJournalDaos,
    transactions: AgentTransactionRunner,
    agentRoot: File,
    cancelRequester: AgentRunCancelRequester,
    grantDao: AgentGrantDao,
    clock: AgentClock = AgentClock.system(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val retryStore = FileAgentFileCleanupRetryStore(agentRoot, ioDispatcher)
    private val fileCleaner = AgentTaskFileCleaner(agentRoot, ioDispatcher)
    private val port = RoomAgentTaskDeletionPort(
        daos = daos,
        transactions = transactions,
        cancelRequester = cancelRequester,
        fileCleaner = fileCleaner,
        grantDao = grantDao,
        clock = clock
    )
    private val deletionPolicy = AgentTaskDeletionPolicy(
        port = port,
        retryStore = retryStore,
        clock = clock
    )
    private val garbageCollector = AgentWorkspaceGarbageCollector(
        agentRoot = agentRoot,
        clock = clock,
        ioDispatcher = ioDispatcher
    )

    suspend fun deleteTask(taskId: String): AgentTaskDeletionResult =
        deletionPolicy.deleteTask(taskId)

    suspend fun retryPendingCleanup(
        limit: Int = 20
    ): List<AgentTaskDeletionRetryResult> = deletionPolicy.retryPendingCleanup(limit)

    suspend fun collectOrphanStaging(): AgentWorkspaceGcResult =
        garbageCollector.collectOrphanStaging()
}
