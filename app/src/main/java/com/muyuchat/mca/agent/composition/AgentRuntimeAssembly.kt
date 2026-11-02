package com.muyuchat.mca.agent.composition

import com.muyuchat.mca.agent.reliability.AgentApprovalOutboxLedger
import com.muyuchat.mca.agent.reliability.AgentOutboxBackgroundLoop
import com.muyuchat.mca.agent.reliability.BackgroundLoopApprovedDispatchSink
import com.muyuchat.mca.agent.reliability.OwnerApprovedDispatchExecutor
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxDispatcher
import com.muyuchat.core.agent.runtime.journal.AgentDispatchStartupReconciler
import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentCreationReceipt
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentModelPort
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentWorkspaceCommands
import com.muyuchat.core.agent.contract.AgentWorkspaceQueries
import com.muyuchat.core.agent.contract.AnswerAgentQuestion
import com.muyuchat.core.agent.contract.CancelAgentRun
import com.muyuchat.core.agent.contract.ContinueAgentTask
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.core.agent.contract.PauseAgentRun
import com.muyuchat.core.agent.contract.ResolveAgentApproval
import com.muyuchat.core.agent.contract.StartAgentRun
import com.muyuchat.core.agent.runtime.context.AgentContextAssembler
import com.muyuchat.core.agent.runtime.journal.AgentJournalRejectedException
import com.muyuchat.core.agent.runtime.protocol.AgentPromptedSystemPromptBuilder
import com.muyuchat.core.agent.runtime.protocol.AgentNativeTranscriptSerializer
import com.muyuchat.core.agent.runtime.protocol.AgentPromptedTranscriptSerializer
import com.muyuchat.core.agent.runtime.protocol.PromptedV1Decoder
import com.muyuchat.core.agent.runtime.protocol.PromptedV1TurnDecoder
import com.muyuchat.core.agent.runtime.run.AgentActiveAttemptRegistry
import com.muyuchat.core.agent.runtime.run.AgentArtifactVerifier
import com.muyuchat.core.agent.runtime.run.AgentBudgetPolicy
import com.muyuchat.core.agent.runtime.run.AgentCommandProcessor
import com.muyuchat.core.agent.runtime.run.AgentFrozenCommandHook
import com.muyuchat.core.agent.runtime.run.AgentIdGenerator
import com.muyuchat.core.agent.runtime.run.AgentMonotonicClock
import com.muyuchat.core.agent.runtime.run.AgentPromptedLoopCoordinator
import com.muyuchat.core.agent.runtime.run.AgentRunOwner
import com.muyuchat.core.agent.runtime.run.AgentRunExecutor
import com.muyuchat.core.agent.runtime.run.AgentRunEventWriter
import com.muyuchat.core.agent.runtime.run.AgentRunSpecProvider
import com.muyuchat.core.agent.runtime.run.AgentRunStopSettlementSource
import com.muyuchat.core.agent.runtime.run.AgentRunToolCatalogProvider
import com.muyuchat.core.agent.runtime.run.AgentLoopResult
import com.muyuchat.core.agent.runtime.run.AgentLoopTranscript
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.mca.agent.tools.PipelineWorkflowToolGateway
import com.muyuchat.mca.agent.tools.WorkflowToolGatewayFactory
import com.muyuchat.mca.agent.workflow.AgentWorkflowDefinitionStore
import com.muyuchat.mca.agent.workflow.AgentWorkflowGraphRunExecutor
import com.muyuchat.mca.agent.workflow.WorkflowExecutionService
import com.muyuchat.mca.agent.workflow.WorkflowToolGateway
import com.muyuchat.mca.agent.workflow.WorkflowToolOutcome
import com.muyuchat.core.agent.runtime.run.AgentStopSettlementReceipt
import com.muyuchat.core.agent.runtime.run.AgentSystemMonotonicClock
import com.muyuchat.core.agent.runtime.run.UuidAgentIdGenerator
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import com.muyuchat.mca.agent.data.AgentTaskCreationOutcome
import com.muyuchat.mca.agent.data.ObservingAgentJournal
import com.muyuchat.mca.agent.data.export.AgentPackageResult
import com.muyuchat.mca.agent.data.quota.AgentCancelOutcome
import com.muyuchat.mca.agent.data.quota.AgentTaskDeletionResult
import com.muyuchat.mca.agent.data.quota.AgentTaskDeletionRetryResult
import com.muyuchat.mca.agent.data.quota.AgentWorkspaceGcResult
import com.muyuchat.mca.agent.platform.AgentStartupRecovery
import kotlinx.coroutines.CoroutineScope

/**
 * The one production Agent run owner for this app process.
 *
 * Wiring rule: this class owns no model/engine/native handle. The model port is the K04 adapter
 * (same engine + same facade), the tool catalog is an immutable K08 snapshot, and [commandHook] is
 * the K07 freeze/decide seam. It is constructed once per composition scope; the page receives only
 * [queries] and this object as [AgentWorkspaceCommands].
 */
class AgentRuntimeAssembly(
    private val data: AgentDataAssembly,
    private val modelPort: AgentModelPort,
    private val toolCatalogProvider: AgentRunToolCatalogProvider,
    private val commandHook: AgentFrozenCommandHook,
    /** K07 broker: the single one-shot approval consume point used by the owner resume path. */
    permissionBroker: com.muyuchat.mca.agent.security.AgentPermissionBroker,
    private val scope: CoroutineScope,
    runObserver: ((AgentRun) -> Unit)? = null,
    private val clock: AgentMonotonicClock = AgentSystemMonotonicClock,
    private val ids: AgentIdGenerator = UuidAgentIdGenerator(),
    /**
     * App-scope owner of the real native/network resource fence (the local Agent model lease
     * accounting). It must be injected by the production composition so a stopped run is only
     * CANCELLED after the exact execution is proven quiescent.
     */
    private val stopSettlementSource: AgentRunStopSettlementSource? = null,
    /**
     * Dispatches the exact engine stop for a run's live model attempt before the owner cancels the
     * execution job. The owner's own cancel only cancels the coroutine; a local runner that ignores
     * coroutine cancellation would keep its lease and the run could never leave STOPPING.
     */
    private val stopLiveModelAttempts: (suspend (String) -> Boolean)? = null,
    /**
     * N18/N19: the immutable definition store and checkpoint root the graph strategy reads when a
     * run policy pins a workflow version. Both null keeps every normal Agent run on the model loop
     * and makes a workflow-policy run fail closed with a typed reason.
     */
    private val workflowDefinitions: AgentWorkflowDefinitionStore? = null,
    private val workflowCheckpointRoot: java.io.File? = null,
    /**
     * S4 platform wake hint for parks that remain waiting after cold-start recovery. Null keeps
     * harnesses without WorkManager on the in-process sweep only.
     */
    private val parkWakeScheduler: com.muyuchat.mca.agent.workflow.AgentParkWakeScheduler? = null
) : AgentWorkspaceCommands {

    /**
     * Every committed run transition flows through this journal decorator, which is how the app
     * composition drives the foreground-service guard. No polling and no Android type enters runtime.
     */
    private val journal = if (runObserver == null) {
        data.journal
    } else {
        ObservingAgentJournal(data.journal, runObserver)
    }

    val queries: AgentWorkspaceQueries = data.queries

    /** One durable park wake through the same owner; the worker never builds a runtime. */
    suspend fun wakeParked(
        runId: String,
        wakeId: String
    ): com.muyuchat.core.agent.runtime.run.AgentParkWakeResult =
        owner.wakeParked(runId, wakeId)

    private val attemptRegistry = AgentActiveAttemptRegistry()
    private val budgetPolicy = AgentBudgetPolicy(clock)
    private val serializer = AgentPromptedTranscriptSerializer()
    private val schemaValidator = SupportedToolSchemaValidator()
    private val contextAssembler = AgentContextAssembler(
        serializer,
        nativeSerializer = AgentNativeTranscriptSerializer()
    )
    // N03 production: approved invocations are enqueued and dispatched by the background loop;
    // the ledger wrapper settles the outbox from the authoritative terminal invocation fact.
    private val toolLedger = AgentApprovalOutboxLedger(data.toolInvocationLedger, data.dispatchOutbox)
    private val toolAuthorization = BrokerAgentToolAuthorization(toolLedger, permissionBroker)

    /** Late-bound so the loop (which needs the owner) can be constructed after the owner. */
    private var dispatchLoop: AgentOutboxBackgroundLoop? = null

    private val dispatchSink = BackgroundLoopApprovedDispatchSink(
        handoff = data.dispatchHandoff,
        wakeTargetProvider = { dispatchLoop },
        outbox = data.dispatchOutbox,
        clock = { clock.nowMillis() }
    )

    private val modelExecutor = AgentPromptedLoopCoordinator(
        decoderFactory = { keys -> PromptedV1TurnDecoder(PromptedV1Decoder({ keys })) },
        serializer = serializer,
        systemPromptBuilder = com.muyuchat.core.agent.runtime.protocol.AgentProtocolSystemPromptBuilder(),
        catalogProvider = toolCatalogProvider,
        schemaValidator = schemaValidator,
        journal = journal,
        toolLedger = toolLedger,
        modelPort = modelPort,
        budgetPolicy = budgetPolicy,
        contextAssembler = contextAssembler,
        commandHook = commandHook,
        artifactVerifier = AgentArtifactVerifier { taskId, artifactIds ->
            val registered = data.artifactReader.readArtifacts(taskId)
                .mapTo(HashSet()) { it.artifactId }
            artifactIds.filterNot(registered::contains)
        },
        ids = ids,
        clock = clock,
        attemptRegistry = attemptRegistry
    )

    /**
     * Graph strategy for a run whose frozen policy pins a published workflow version. It is built
     * lazily so it can reference this assembly's own one-owner commands for AgentTask child nodes;
     * a workflow run never constructs a second owner, engine or tool catalog.
     */
    private val workflowFrameService: WorkflowExecutionService? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val definitions = workflowDefinitions ?: return@lazy null
        val checkpointRoot = workflowCheckpointRoot ?: return@lazy null
        val receiptStore = com.muyuchat.mca.agent.workflow.WorkflowToolReceiptStore(checkpointRoot)
        WorkflowExecutionService(
            definitions = definitions,
            checkpointRoot = checkpointRoot,
            checkpointStoreFactory = data.workflowCheckpointStoreFactory(checkpointRoot),
            toolGateway = WorkflowToolGateway {
                WorkflowToolOutcome.Failed(
                    "WF_TOOL_PIPELINE_UNAVAILABLE",
                    "This execution path has no tool gateway."
                )
            },
            agentTaskRunner = com.muyuchat.core.agent.runtime.workflow.WorkspaceCommandWorkflowAgentTaskRunner(
                this,
                queries
            ),
            modelPort = modelPort,
            runScopedToolGatewayFactory = object : WorkflowToolGatewayFactory {
                override suspend fun create(
                    run: AgentRun,
                    task: com.muyuchat.core.agent.contract.AgentTask,
                    state: com.muyuchat.mca.agent.tools.WorkflowToolRunState,
                    resume: com.muyuchat.core.agent.runtime.run.AgentLoopResume?
                ): WorkflowToolGateway = PipelineWorkflowToolGateway(
                    run = run,
                    task = task,
                    state = state,
                    catalog = toolCatalogProvider.catalogFor(run, task),
                    toolLedger = toolLedger,
                    commandHook = commandHook,
                    budgetPolicy = budgetPolicy,
                    ids = ids,
                    clock = clock,
                    receipts = receiptStore,
                    events = AgentRunEventWriter(journal, clock, ids),
                    resume = resume
                )
            },
            agentFrameExecutor = com.muyuchat.mca.agent.workflow.WorkflowAgentFrameExecutor {
                    run, task, resume, progress ->
                modelExecutor.executeSameRootFrame(run, task, resume, progress)
            }
        )
    }

    private val workflowExecutor: AgentRunExecutor? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        workflowFrameService?.let(::AgentWorkflowGraphRunExecutor)
    }

    private val executor = AgentRunExecutor { run, task, resume, progress ->
        if (run.policy.workflow == null) {
            modelExecutor.execute(run, task, resume, progress)
        } else {
            val graph = workflowExecutor
                ?: return@AgentRunExecutor AgentLoopResult.Failed(
                    run = run,
                    transcript = AgentLoopTranscript(emptyList(), 0, 0),
                    error = AgentError(
                        code = "WF_RUNTIME_UNAVAILABLE",
                        category = AgentErrorCategory.POLICY,
                        retryability = AgentRetryability.AFTER_USER_ACTION,
                        safeMessage = "Workflow execution is not configured in this process.",
                        correlationId = run.runId
                    )
                )
            graph.execute(run, task, resume, progress)
        }
    }

    private val specProvider = AgentRunSpecProvider { task ->
        data.readRunSpec(task.taskId) ?: throw AgentJournalRejectedException(
            AgentErrorCodes.COMMAND_INVALID_STATE,
            "task ${task.taskId} has no frozen run context; create it through CreateAgentTask."
        )
    }

    private val owner = AgentRunOwner(
        journal = journal,
        ledger = data.commandLedger,
        taskReader = data.runtimeReaders,
        questionReader = data.runtimeReaders,
        approvalReader = data.runtimeReaders,
        toolLedger = toolLedger,
        toolAuthorization = toolAuthorization,
        specProvider = specProvider,
        executor = executor,
        processor = AgentCommandProcessor(scope),
        clock = clock,
        ids = ids,
        scope = scope,
        attemptRegistry = attemptRegistry,
        stopSettlementSource = stopSettlementSource,
        // N03: approval consume + invocation state + run journal commit in one Room transaction.
        transaction = RoomAgentRunTransaction(data.transactions),
        dispatchHandoff = data.dispatchHandoff,
        dispatchSink = dispatchSink,
        parkTokens = data.parkTokens
    )

    private val dispatchDispatcher = AgentDispatchOutboxDispatcher(
        store = data.dispatchOutbox,
        ledger = toolLedger,
        executor = OwnerApprovedDispatchExecutor(owner),
        dispatcherId = "agent-outbox-main",
        clock = { clock.nowMillis() },
        leaseMillis = 10 * 60_000L
    )

    private val outboxLoop = AgentOutboxBackgroundLoop(dispatchDispatcher, scope).also {
        dispatchLoop = it
    }

    /** Task creation is the repository path so the frozen binding/policy/budget are persisted. */
    override suspend fun createTask(command: CreateAgentTask): AgentCommandResult {
        return when (val outcome = data.taskRepository.createTask(command)) {
            is AgentTaskCreationOutcome.Created -> AgentCommandResult.Accepted(
                appliedRevision = outcome.task.revision,
                runId = null,
                idempotentReplay = outcome.idempotentReplay,
                // The durable command -> task anchor. Callers resolve the created task from this
                // receipt only; a fresh and a replayed command carry the same task identity.
                creationReceipt = AgentCreationReceipt(
                    creationCommandId = command.commandId,
                    taskId = outcome.task.taskId,
                    workspaceId = outcome.task.workspaceId,
                    appliedRevision = outcome.task.revision,
                    idempotentReplay = outcome.idempotentReplay
                )
            )
            is AgentTaskCreationOutcome.Rejected -> AgentCommandResult.Rejected(outcome.error)
        }
    }

    override suspend fun startRun(command: StartAgentRun): AgentCommandResult = owner.startRun(command)

    override suspend fun requestPause(command: PauseAgentRun): AgentCommandResult = owner.requestPause(command)

    override suspend fun requestCancel(command: CancelAgentRun): AgentCommandResult {
        // Ask the model port for the exact engine stop first: the owner below only cancels the
        // execution job, which a native runner may ignore until the generation finishes.
        if (stopLiveModelAttempts != null) {
            try {
                stopLiveModelAttempts.invoke(command.runId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A refused dispatch changes nothing: the owner's bounded settlement still decides.
            }
        }
        return owner.requestCancel(command)
    }

    override suspend fun continueTask(command: ContinueAgentTask): AgentCommandResult = owner.continueTask(command)

    override suspend fun answerQuestion(command: AnswerAgentQuestion): AgentCommandResult =
        owner.answerQuestion(command)

    /**
     * The run owner owns approval resolution. On ALLOW_ONCE it reconstructs the frozen candidate
     * from durable invocation material and asks the K07 broker for the single atomic
     * `PENDING -> CONSUMED` authorization; only an authorized command reaches the loop. A repeated
     * click sees a non-pending approval and is rejected, so the executor runs at most once.
     */
    override suspend fun resolveApproval(command: ResolveAgentApproval): AgentCommandResult =
        owner.resolveApproval(command)

    private val startupReconcileGate = StartupReconcileGate()

    /**
     * Explicit startup reconciliation. Never auto-resumes a run; it settles non-terminal records into
     * PAUSED/NEEDS_REVIEW. The caller passes whether a typed artifact reconciler is available.
     *
     * The UI, a worker-only cold start and later API entries share one runtime, so the scan is
     * gated to one attempt per process root. Without the gate a worker wake could run schedules
     * before recovery, or a second UI/worker entry could re-expire just-settled records.
     */
    suspend fun reconcileStartup(
        artifactReconciler: com.muyuchat.mca.agent.platform.AgentArtifactReconciler =
            com.muyuchat.mca.agent.platform.AgentArtifactReconciler.Never
    ) {
        startupReconcileGate.runOnce {
            AgentStartupRecovery(
                journal = journal,
                records = data.recoveryRecords,
                artifactReconciler = artifactReconciler,
                parkTokens = data.parkTokens,
                questionReader = data.runtimeReaders
            ).reconcile()
            data.legacyOutboxImporter.import()
            AgentDispatchStartupReconciler(
                store = data.dispatchOutbox,
                ledger = toolLedger,
                clock = { clock.nowMillis() }
            ).reconcile()
            // Start only after startup reconciliation; new approvals otherwise fall back to the inline
            // path with a typed BYPASSED row instead of being silently dropped.
            outboxLoop.start()
            // S4 cold start: an answered user park is resumed through the single admission path;
            // waiting parks stay armed; a RUNNING token without usable resume material fails closed.
            data.parkTokens.list().forEach { token ->
                try {
                    val outcome = owner.wakeParked(token.runId, token.wakeId)
                    if (outcome is com.muyuchat.core.agent.runtime.run.AgentParkWakeResult.StillParked) {
                        parkWakeScheduler?.schedule(token.runId, token.wakeId)
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // The token stays durable; arm the platform wake so a worker can retry it.
                    parkWakeScheduler?.schedule(token.runId, token.wakeId)
                }
            }
        }
    }

    // ---- K14 task package + delete operations (composition-only transports) ----

    private val packageMaintenance by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        data.createPackageMaintenance(::cancelForDeletion)
    }

    suspend fun exportTaskPackage(
        taskId: String,
        output: java.io.OutputStream
    ): com.muyuchat.mca.agent.data.export.AgentPackageResult<
        com.muyuchat.mca.agent.data.export.AgentTaskExportSummary
        > = data.packageExporter.export(taskId, output)

    suspend fun importTaskPackage(input: java.io.InputStream): AgentPackageResult<ImportedAgentTask> {
        val imported = when (val parsed = data.packageImporter.import(input)) {
            is AgentPackageResult.Success -> parsed.value
            is AgentPackageResult.Failure -> return parsed
        }
        return when (val saved = data.packageStore.persistImported(imported)) {
            is AgentPackageResult.Success -> AgentPackageResult.Success(
                ImportedAgentTask(
                    taskId = saved.value.taskId,
                    displayTitle = imported.displayTitle
                )
            )
            is AgentPackageResult.Failure -> saved
        }
    }

    suspend fun deleteTaskPackage(taskId: String): AgentTaskDeletionResult =
        packageMaintenance.deleteTask(taskId)

    suspend fun retryPackageCleanup(): List<AgentTaskDeletionRetryResult> =
        packageMaintenance.retryPendingCleanup()

    suspend fun collectPackageStaging(): AgentWorkspaceGcResult =
        packageMaintenance.collectOrphanStaging()

    /**
     * Notification stop action: validates the exact run/epoch, sends only the scoped cancel command
     * through the owner, and reports a typed callback result. Never calls a global stop.
     */
    suspend fun requestCancelFromNotification(
        runId: String,
        epoch: Long
    ): com.muyuchat.mca.agent.platform.AgentRunStopCallbackResult {
        val snapshot = journal.readRun(runId)
            ?: return stopRejected("run_not_found")
        if (snapshot.run.epoch != epoch) return stopRejected("stale_epoch")
        if (!snapshot.run.state.holdsActiveSlot) return stopRejected("not_active")
        if (snapshot.run.state == com.muyuchat.core.agent.contract.AgentRunState.STOPPING ||
            snapshot.run.state == com.muyuchat.core.agent.contract.AgentRunState.PAUSING
        ) {
            return com.muyuchat.mca.agent.platform.AgentRunStopCallbackResult(
                com.muyuchat.mca.agent.platform.AgentRunStopCallbackOutcome.ALREADY_STOPPING,
                "already_stopping"
            )
        }
        return when (
            val result = owner.requestCancel(
                CancelAgentRun(
                    commandId = ids.nextId("agent-notification-stop"),
                    runId = runId,
                    expectedRunRevision = snapshot.run.revision
                )
            )
        ) {
            is AgentCommandResult.Accepted -> com.muyuchat.mca.agent.platform.AgentRunStopCallbackResult(
                com.muyuchat.mca.agent.platform.AgentRunStopCallbackOutcome.ACCEPTED,
                "stop_dispatched"
            )
            is AgentCommandResult.Rejected -> stopRejected(result.error.code)
        }
    }

    private fun stopRejected(
        detail: String
    ): com.muyuchat.mca.agent.platform.AgentRunStopCallbackResult =
        com.muyuchat.mca.agent.platform.AgentRunStopCallbackResult(
            com.muyuchat.mca.agent.platform.AgentRunStopCallbackOutcome.REJECTED,
            detail
        )

    /**
     * Foreground-service admission failed for a RUNNING run. Pause with the typed platform reason
     * instead of silently continuing without process protection; the user can retry explicitly.
     * Coalesces safely with a user pause because PAUSING/PAUSED is already settled.
     */
    suspend fun pauseForForegroundUnavailable(runId: String): Boolean {
        val snapshot = journal.readRun(runId) ?: return false
        val run = snapshot.run
        if (run.state.isTerminal || run.state == AgentRunState.PAUSING || run.state == AgentRunState.PAUSED) {
            return true
        }
        return owner.requestPause(
            PauseAgentRun(
                commandId = ids.nextId("agent-fgs-pause"),
                runId = runId,
                expectedRunRevision = run.revision,
                reason = AgentPauseReason.FOREGROUND_SERVICE_UNAVAILABLE
            )
        ) is AgentCommandResult.Accepted
    }

    private suspend fun cancelForDeletion(
        run: com.muyuchat.core.agent.contract.AgentRun
    ): AgentCancelOutcome {
        val snapshot = journal.readRun(run.runId) ?: return AgentCancelOutcome.Missing
        if (snapshot.run.state.isTerminal) return AgentCancelOutcome.Missing
        return when (
            val result = owner.requestCancel(
                CancelAgentRun(
                    commandId = ids.nextId("agent-delete"),
                    runId = run.runId,
                    expectedRunRevision = snapshot.run.revision
                )
            )
        ) {
            is AgentCommandResult.Accepted -> AgentCancelOutcome.Accepted
            is AgentCommandResult.Rejected -> AgentCancelOutcome.Rejected(result.error.code)
        }
    }

    /**
     * Late real resource settlement from the app-scope lease source. The owner validates the run
     * id/epoch and the STOPPING state itself, so a stale or foreign receipt can never settle a
     * replacement run; repeated receipts stay idempotent.
     */
    suspend fun reportStopSettlement(receipt: AgentStopSettlementReceipt): AgentCommandResult =
        owner.reportStopSettlement(receipt)

    /**
     * Epoch of the exact run when it is still STOPPING, used to bind a lease release that happens
     * after the owner's bounded settlement query to that run identity. A run that already settled
     * (or never stopped) returns null, so a late proof can never touch it.
     */
    internal suspend fun stoppingRunEpoch(runId: String): Long? =
        journal.readRun(runId)
            ?.run
            ?.takeIf { run -> run.state == AgentRunState.STOPPING }
            ?.epoch

    fun close() {
        owner.close()
    }

    companion object {
        /** Stable correlation id for a command that has no run yet. */
        const val CREATE_TASK_CORRELATION = "agent-task-create"
    }
}

/** Result of a validated + durably persisted import. The task stays IMPORTED_ARCHIVED. */
data class ImportedAgentTask(
    val taskId: String,
    val displayTitle: String?
)

