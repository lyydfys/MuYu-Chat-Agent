package com.muyuchat.mca.agent.composition

import com.muyuchat.core.agent.contract.AgentJournal
import com.muyuchat.core.agent.contract.AgentModelAttempt
import com.muyuchat.core.agent.contract.AgentRunSnapshot
import com.muyuchat.core.agent.contract.AgentWorkspaceFilePort
import com.muyuchat.core.agent.contract.AgentWorkspaceQueries
import com.muyuchat.core.agent.contract.AgentArtifactCommit
import com.muyuchat.core.agent.contract.ToolInvocation
import com.muyuchat.core.agent.runtime.journal.AgentArtifactReader
import com.muyuchat.core.agent.runtime.journal.AgentCommandLedger
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationLedger
import com.muyuchat.core.agent.runtime.run.AgentRunSpec
import com.muyuchat.mca.McaRoomDatabase
import com.muyuchat.mca.agent.data.AgentArtifactCrashInjector
import com.muyuchat.mca.agent.data.AgentClock
import com.muyuchat.mca.agent.data.AgentDataLimits
import com.muyuchat.mca.agent.data.AgentEntityMappers
import com.muyuchat.mca.agent.data.AgentJournalDaos
import com.muyuchat.mca.agent.data.AgentPayloadStore
import com.muyuchat.mca.agent.data.RoomAgentToolInvocationLedger
import com.muyuchat.mca.agent.data.AgentTaskRepository
import com.muyuchat.mca.agent.data.AgentWorkspaceStore
import com.muyuchat.mca.agent.data.RoomAgentArtifactLedger
import com.muyuchat.mca.agent.data.RoomAgentCommandLedger
import com.muyuchat.mca.agent.data.RoomAgentJournal
import com.muyuchat.mca.agent.data.RoomAgentRuntimeReaders
import com.muyuchat.mca.agent.data.RoomAgentTransactionRunner
import com.muyuchat.mca.agent.data.RoomAgentWorkspaceQueries
import com.muyuchat.mca.agent.data.export.AgentTaskDisplayTitleReader
import com.muyuchat.mca.agent.data.export.AgentTaskPackageExporter
import com.muyuchat.mca.agent.data.export.AgentTaskPackageImporter
import com.muyuchat.mca.agent.data.export.RoomAgentPackageStore
import com.muyuchat.mca.agent.data.egress.RoomAgentEgressGrantContextReader
import com.muyuchat.mca.agent.data.egress.RoomAgentEgressGrantStore
import com.muyuchat.mca.agent.security.AgentEgressGrantStore
import com.muyuchat.mca.agent.data.quota.AgentRunCancelRequester
import com.muyuchat.mca.agent.data.quota.AgentWorkspaceQuota
import com.muyuchat.mca.agent.data.quota.RoomAgentPackageMaintenance
import com.muyuchat.mca.agent.data.CoreAgentParkTokenStore
import com.muyuchat.mca.agent.data.RoomAgentParkTokenStore
import com.muyuchat.mca.agent.memory.AgentMemorySelectionService
import com.muyuchat.mca.agent.memory.AgentMemoryStore
import com.muyuchat.mca.agent.reliability.FileAgentDispatchOutboxStore
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxStore
import com.muyuchat.core.agent.runtime.run.AgentApprovedDispatchHandoff
import com.muyuchat.core.agent.runtime.run.InMemoryAgentApprovedDispatchHandoff
import com.muyuchat.mca.agent.memory.data.RoomAgentMemoryPersistence
import com.muyuchat.mca.agent.platform.AgentRecoveryRecordSource
import com.muyuchat.mca.agent.security.AgentApprovalStorePort
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Application-side composition of the Agent data owner.
 *
 * It is the only place that knows both `McaRoomDatabase` and the Agent data classes. Business rules
 * stay in the runtime owner; the page receives only [queries]/commands; the tool layer receives only
 * [workspaceFilePort]. No DAO escapes this assembly.
 */
class AgentDataAssembly(
    private val database: McaRoomDatabase,
    filesDir: File,
    private val clock: AgentClock = AgentClock.system(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /**
     * The one Room transaction runner over the shared `mca.db`. Exposed to the composition root so
     * approval resolution can commit the approval CAS, invocation state and run journal together
     * (N03) instead of opening three independent transactions.
     */
    internal val transactions = RoomAgentTransactionRunner(database)
    private val agentRoot = File(filesDir, AGENT_ROOT_DIRECTORY)
    private val packageStagingRoot = File(agentRoot, PACKAGE_STAGING_DIRECTORY)
    private val grantDao = database.agentGrantDao()

    private val daos = AgentJournalDaos(
        task = database.agentTaskDao(),
        workspace = database.agentWorkspaceDao(),
        run = database.agentRunDao(),
        step = database.agentStepDao(),
        attempt = database.agentModelAttemptDao(),
        invocation = database.agentInvocationDao(),
        approval = database.agentApprovalDao(),
        event = database.agentEventDao(),
        artifact = database.agentArtifactDao(),
        artifactCommit = database.agentArtifactCommitDao(),
        question = database.agentQuestionDao(),
        command = database.agentCommandDao()
    )

    val journal: AgentJournal = RoomAgentJournal(daos, transactions, clock)

    val queries: AgentWorkspaceQueries = RoomAgentWorkspaceQueries(
        taskDao = daos.task,
        runDao = daos.run,
        eventDao = daos.event,
        stepDao = daos.step,
        approvalDao = daos.approval,
        questionDao = daos.question,
        artifactDao = daos.artifact
    )

    val commandLedger: AgentCommandLedger = RoomAgentCommandLedger(
        dao = database.agentCommandResultDao(),
        transactions = transactions,
        clock = clock
    )

    /** Room-backed one-shot approvals used by K07's broker. */
    val approvalStore: AgentApprovalStorePort by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomAgentApprovalStore(
            approvalDao = daos.approval,
            invocationDao = daos.invocation,
            transactions = transactions,
            clock = clock
        )
    }

    private val payloadStore: AgentPayloadStore = AgentPayloadStore(
        agentRoot = agentRoot,
        clock = clock,
        idGenerator = idGenerator
    )

    /**
     * Durable tool-invocation ledger for the P0 approval closure: PROPOSED before freeze, frozen
     * material recorded before the approval, the single RUNNING gate and the terminal result.
     */
    val toolInvocationLedger: AgentToolInvocationLedger = RoomAgentToolInvocationLedger(
        taskDao = daos.task,
        runDao = daos.run,
        stepDao = daos.step,
        invocationDao = daos.invocation,
        payloadStore = payloadStore,
        transactions = transactions,
        clock = clock
    )

    /** N03 production wiring: in-memory one-shot authorization hand-off + durable Room outbox. */
    val dispatchHandoff: AgentApprovedDispatchHandoff = InMemoryAgentApprovedDispatchHandoff()
    private val legacyDispatchOutbox = FileAgentDispatchOutboxStore(
        File(agentRoot, "reliability"),
        clock = { clock.nowMillis() }
    )
    private val roomDispatchOutbox = com.muyuchat.mca.agent.data.RoomAgentDispatchOutboxStore(
        dao = database.agentDispatchOutboxDao(),
        transactions = transactions,
        clock = { clock.nowMillis() }
    )
    val dispatchOutbox: AgentDispatchOutboxStore = roomDispatchOutbox

    /** Copies the pre-Room file outbox once per startup; idempotent by idempotency key. */
    val legacyOutboxImporter = com.muyuchat.mca.agent.reliability.LegacyAgentDispatchOutboxImporter(
        legacy = legacyDispatchOutbox,
        room = roomDispatchOutbox
    )

    /**
     * Durable workflow frames: Room rows with the legacy file document as a one-time import source.
     * The workflow service still derives a task-root key and a legacy run-id key per execution.
     */
    fun workflowCheckpointStoreFactory(
        legacyRoot: File
    ): com.muyuchat.mca.agent.workflow.WorkflowCheckpointStoreFactory =
        com.muyuchat.mca.agent.workflow.RoomWorkflowCheckpointStoreFactory(
            dao = database.agentWorkflowCheckpointDao(),
            transactions = transactions,
            legacyRoot = legacyRoot,
            clock = { clock.nowMillis() }
        )

    /**
     * S4 durable park/wake tokens. One Room row per parked run, written after the run's
     * WAITING_APPROVAL/WAITING_USER transition and consumed by the owner's wake entry point.
     */
    val parkTokens: com.muyuchat.core.agent.runtime.run.AgentParkTokenStore =
        CoreAgentParkTokenStore(
            RoomAgentParkTokenStore(database.agentParkTokenDao())
        )

    /** One typed reader bundle for the runtime owner; the interfaces stay separate. */
    val runtimeReaders: RoomAgentRuntimeReaders = RoomAgentRuntimeReaders(
        taskDao = daos.task,
        questionDao = daos.question,
        approvalDao = daos.approval,
        artifactDao = daos.artifact
    )

    /** Run/task-scoped egress grants over the existing agent_grants table (no schema change). */
    val egressGrantStore: AgentEgressGrantStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomAgentEgressGrantStore(
            grantDao = grantDao,
            transactions = transactions,
            contextReader = RoomAgentEgressGrantContextReader(daos.task, daos.run),
            clock = clock
        )
    }

    val artifactReader: AgentArtifactReader get() = runtimeReaders

    /**
     * The one Room-backed memory persistence, store and selection service for this process.
     *
     * They share the same `mca.db` handle and the same DAOs as the Agent journal; no component
     * creates a second database or a second memory writer. The store is the only memory content
     * writer; [memorySelectionService] is the only place a task's read scope is frozen/resolved.
     */
    val memoryPersistence: RoomAgentMemoryPersistence = RoomAgentMemoryPersistence(
        database = database,
        dao = database.agentMemoryDao()
    )

    val memoryStore: AgentMemoryStore = AgentMemoryStore(
        persistence = memoryPersistence,
        clock = { clock.nowMillis() },
        idFactory = idGenerator
    )

    val memorySelectionService: AgentMemorySelectionService = AgentMemorySelectionService(
        persistence = memoryPersistence,
        clock = { clock.nowMillis() }
    )

    val taskRepository: AgentTaskRepository = AgentTaskRepository(
        daos = daos,
        grantDao = grantDao,
        transactions = transactions,
        clock = clock,
        idGenerator = idGenerator,
        memorySelectionService = memorySelectionService
    )

    /** Frozen run context (binding/policy/budget) for K03's AgentRunSpecProvider. */
    suspend fun readRunSpec(taskId: String): AgentRunSpec? = taskRepository.readRunSpec(taskId)

    val workspaceStore: AgentWorkspaceStore = AgentWorkspaceStore(
        agentRoot = agentRoot,
        ledger = RoomAgentArtifactLedger(daos, transactions),
        clock = clock,
        idGenerator = idGenerator,
        crashInjector = AgentArtifactCrashInjector.NONE,
        ioDispatcher = ioDispatcher
    )

    val workspaceFilePort: AgentWorkspaceFilePort get() = workspaceStore

    /** Startup recovery reads only; the reconciler commits through [journal]. */
    val recoveryRecords: AgentRecoveryRecordSource = object : AgentRecoveryRecordSource {
        override suspend fun findInvocation(invocationId: String): ToolInvocation? =
            daos.invocation.find(invocationId)?.let(AgentEntityMappers::toContract)

        override suspend fun findArtifactCommit(artifactId: String): AgentArtifactCommit? =
            daos.artifactCommit.listByArtifact(artifactId, 1)
                .firstOrNull()
                ?.let(AgentEntityMappers::toContract)

        override suspend fun findLatestAttempt(runId: String): AgentModelAttempt? =
            daos.attempt.listByRun(runId, AgentDataLimits.MAX_CHILD_PAGE_LIMIT)
                .lastOrNull()
                ?.let(AgentEntityMappers::toContract)
    }

    /** Exposed only for the startup reconciler that must read an artifact's task/workspace scope. */
    suspend fun findCommittedArtifact(
        workspaceId: String,
        taskId: String,
        artifactId: String
    ) = workspaceStore.findCommittedArtifact(workspaceId, taskId, artifactId)

    /** K14 export: one shared artifact store/ledger, never a second instance. */
    val packageExporter: AgentTaskPackageExporter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AgentTaskPackageExporter(
            taskReader = runtimeReaders,
            artifactReader = runtimeReaders,
            artifactStore = workspaceStore.artifactStore,
            stagingRoot = packageStagingRoot,
            displayTitleReader = AgentTaskDisplayTitleReader { taskId ->
                daos.task.find(taskId)?.displayTitle
            },
            clock = clock,
            ioDispatcher = ioDispatcher
        )
    }

    /** K14 import: validates into private staging; persistence is a separate explicit step. */
    val packageImporter: AgentTaskPackageImporter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AgentTaskPackageImporter(
            stagingRoot = packageStagingRoot,
            quota = AgentWorkspaceQuota(agentRoot),
            idGenerator = idGenerator,
            ioDispatcher = ioDispatcher
        )
    }

    /** K14 durable import: archived workspace/task/artifact rows in one Room transaction. */
    val packageStore: RoomAgentPackageStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomAgentPackageStore(
            daos = daos,
            transactions = transactions,
            agentRoot = agentRoot,
            importStagingRoot = packageStagingRoot,
            clock = clock,
            idGenerator = idGenerator,
            ioDispatcher = ioDispatcher
        )
    }

    /** Deletion needs the exact scoped runtime cancel callback, so the runtime owner supplies it. */
    fun createPackageMaintenance(
        cancelRequester: AgentRunCancelRequester
    ): RoomAgentPackageMaintenance = RoomAgentPackageMaintenance(
        daos = daos,
        transactions = transactions,
        agentRoot = agentRoot,
        cancelRequester = cancelRequester,
        grantDao = grantDao,
        clock = clock,
        ioDispatcher = ioDispatcher
    )

    companion object {
        /** `files/agent` is excluded from cloud backup and device transfer (both backup XMLs). */
        const val AGENT_ROOT_DIRECTORY: String = "agent"

        /** Task-package archives are assembled here before any caller stream is touched. */
        const val PACKAGE_STAGING_DIRECTORY: String = "package-staging"
    }
}
