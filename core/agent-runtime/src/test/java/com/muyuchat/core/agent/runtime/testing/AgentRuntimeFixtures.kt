package com.muyuchat.core.agent.runtime.testing

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentModelPort
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentModelRequest
import com.muyuchat.core.agent.contract.AgentModelResult
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentStopDispatch
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.runtime.journal.InMemoryAgentJournal
import com.muyuchat.core.agent.runtime.journal.AgentFrozenInvocation
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationRecord
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationUpdate
import com.muyuchat.core.agent.runtime.journal.AgentToolScope
import com.muyuchat.core.agent.runtime.journal.AgentToolProposal
import com.muyuchat.core.agent.runtime.run.AgentArtifactVerifier
import com.muyuchat.core.agent.runtime.run.AgentFrozenCommandDecision
import com.muyuchat.core.agent.runtime.run.AgentFrozenCommandHook
import com.muyuchat.core.agent.runtime.run.AgentFrozenCommandRequest
import com.muyuchat.core.agent.runtime.run.AgentLoopResult
import com.muyuchat.core.agent.runtime.run.AgentLoopResume
import com.muyuchat.core.agent.runtime.run.AgentLoopTranscript
import com.muyuchat.core.agent.runtime.run.AgentRunExecutor
import com.muyuchat.core.agent.runtime.run.AgentRunSpec
import com.muyuchat.core.agent.runtime.run.AgentRunSpecProvider
import com.muyuchat.core.agent.runtime.run.AgentToolAuthorizationBasis
import com.muyuchat.core.agent.runtime.run.AgentToolAuthorizationPort
import com.muyuchat.core.agent.runtime.run.AgentToolAuthorizationRequest
import com.muyuchat.core.agent.runtime.run.AgentToolAuthorizationResult
import com.muyuchat.core.agent.runtime.run.AgentToolDenialResult
import com.muyuchat.core.agent.runtime.run.AuthorizedToolCommand
import com.muyuchat.core.agent.runtime.validation.AgentV1ToolSpecs
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation

/** Shared fakes for deterministic K03 memory-level tests. No Android, Room, engine or network. */
object AgentRuntimeFixtures {

    const val TASK_ID = "task-1"
    const val RUN_ID = "run-1"

    fun binding(contextWindowTokens: Int? = 8_192): AgentModelBinding = AgentModelBinding(
        providerId = "local",
        modelId = "mnn-qwen3-4b",
        runtime = "MNN_CPU",
        assetFingerprint = "sha256:fixture",
        protocolMode = AgentProtocolMode.PROMPTED_V1,
        modelDisplayName = "Qwen3 4B fixture",
        contextWindowTokens = contextWindowTokens
    )

    fun task(
        taskId: String = TASK_ID,
        goal: String = "整理 inputs/brief.txt 并生成 artifacts/report.md",
        lastRunId: String? = null,
        revision: Long = 0L,
        state: AgentDisplayStatus = AgentDisplayStatus.IDLE
    ): AgentTask = AgentTask(
        taskId = taskId,
        workspaceId = "workspace-1",
        goal = goal,
        lifecycle = AgentTaskLifecycle.ACTIVE,
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        displayStatus = state,
        lastRunId = lastRunId,
        revision = revision
    )

    fun run(
        runId: String = RUN_ID,
        taskId: String = TASK_ID,
        runOrdinal: Long = 1L,
        state: AgentRunState = AgentRunState.RUNNING,
        epoch: Long = 1L,
        budget: AgentBudget = AgentBudget(),
        spentBudget: AgentSpentBudget = AgentSpentBudget(),
        revision: Long = 1L,
        lastSequence: Long = 0L
    ): AgentRun = AgentRun(
        runId = runId,
        taskId = taskId,
        runOrdinal = runOrdinal,
        state = state,
        reason = AgentPauseReason.NONE,
        epoch = epoch,
        modelBinding = binding(),
        policy = AgentRunPolicy(),
        budget = budget,
        spentBudget = spentBudget,
        lastSequence = lastSequence,
        revision = revision,
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L
    )

    /** Commits a task plus one run in [state] at revision 1. */
    suspend fun journalWithRun(
        journal: InMemoryAgentJournal,
        runState: AgentRunState = AgentRunState.RUNNING,
        contextWindowTokens: Int? = 8_192,
        budget: AgentBudget = AgentBudget()
    ): Pair<AgentTask, AgentRun> {
        val task = task()
        journal.commit(
            CreateTaskTransition(
                commandId = "setup-task-command",
                task = task
            )
        )
        val run = run(
            state = runState,
            budget = budget
        ).copy(
            modelBinding = binding(contextWindowTokens),
            revision = 1L
        )
        journal.commit(
            CreateRunTransition(
                commandId = "setup-run-command",
                run = run,
                taskRevision = task.revision,
                taskDisplay = AgentDisplayStatus.RUNNING
            )
        )
        return task to run
    }

    fun toolDescriptors(): List<AgentToolDescriptor> =
        AgentV1ToolSpecs.descriptors(SupportedToolSchemaValidator())

    fun frozenCommand(request: AgentFrozenCommandRequest): FrozenToolCommand = FrozenToolCommand(
        invocationId = request.invocationId,
        runId = request.run.runId,
        stepId = request.stepId,
        callOrdinal = request.callOrdinal,
        toolKey = request.descriptor.toolKey,
        toolVersion = request.descriptor.version,
        schemaHash = request.descriptor.schemaHash,
        normalizedArgumentsJson = request.normalizedArgumentsJson,
        argumentsHash = request.argumentsHash,
        resourceFingerprint = "fixture-resource",
        runEpoch = request.run.epoch,
        policyVersion = request.run.policy.policyVersion,
        expiresAtMillis = 9_000_000L,
        providerCallId = request.providerCallId
    )

    fun successResult(
        summary: String = "read ok",
        dataJson: String? = """{"content":"brief"}""",
        artifactIds: List<String> = emptyList()
    ): AgentToolResult = AgentToolResult(
        status = AgentToolResultStatus.SUCCEEDED,
        summary = summary,
        dataJson = dataJson,
        artifactIds = artifactIds,
        truncated = false
    )

    fun modelFailure(code: String = AgentErrorCodes.MODEL_PROTOCOL_ERROR): AgentModelResult.Failure =
        AgentModelResult.Failure(
            AgentError(
                code = code,
                category = AgentErrorCategory.MODEL,
                retryability = AgentRetryability.AFTER_USER_ACTION,
                safeMessage = "fixture failure",
                correlationId = "fixture"
            )
        )

    fun completed(
        run: AgentRun,
        text: String = "done",
        artifacts: List<String> = emptyList()
    ): AgentLoopResult.Completed = AgentLoopResult.Completed(
        run = run,
        transcript = AgentLoopTranscript(emptyList(), 0, 0),
        finalText = text,
        artifactIds = artifacts
    )

    fun paused(run: AgentRun, reason: AgentPauseReason): AgentLoopResult.Paused =
        AgentLoopResult.Paused(
            run = run,
            transcript = AgentLoopTranscript(emptyList(), 0, 0),
            reason = reason,
            error = null
        )

    fun waitingUser(
        run: AgentRun,
        question: String = "哪类读者？",
        options: List<String> = listOf("开发者", "管理者")
    ): AgentLoopResult.WaitingUser = AgentLoopResult.WaitingUser(
        run = run,
        transcript = AgentLoopTranscript(emptyList(), 1, 0),
        questionText = question,
        options = options
    )

    fun pendingApproval(
        approvalId: String = "approval-fault-1",
        invocationId: String = "invocation-fault-1",
        runEpoch: Long = 1L,
        expiresAtMillis: Long = 5_000_000L,
        createdAtMillis: Long = 1_000_000L
    ): AgentApproval = AgentApproval(
        approvalId = approvalId,
        invocationId = invocationId,
        principal = "fault-fixture-user",
        argumentsHash = "a".repeat(64),
        toolSchemaHash = "b".repeat(64),
        resourceFingerprint = "fault-fixture-resource",
        policyVersion = 1L,
        runEpoch = runEpoch,
        expiresAtMillis = expiresAtMillis,
        status = AgentApprovalStatus.PENDING,
        createdAtMillis = createdAtMillis
    )

    fun frozenCommandFor(
        invocationId: String = "invocation-fault-1",
        runId: String = RUN_ID,
        runEpoch: Long = 1L,
        toolKey: String = AgentV1ToolSpecs.WORKSPACE_READ_TEXT
    ): FrozenToolCommand {
        val descriptor = AgentV1ToolSpecs.descriptor(toolKey, SupportedToolSchemaValidator())
        return FrozenToolCommand(
            invocationId = invocationId,
            runId = runId,
            stepId = "step-fault-1",
            callOrdinal = 0,
            toolKey = toolKey,
            toolVersion = descriptor.version,
            schemaHash = descriptor.schemaHash,
            normalizedArgumentsJson = """{"limit":4096,"offset":0,"path":"inputs/brief.txt"}""",
            argumentsHash = "d".repeat(64),
            resourceFingerprint = "fault-fixture-resource",
            runEpoch = runEpoch,
            policyVersion = 1L,
            expiresAtMillis = 9_000_000L
        )
    }

    fun waitingApproval(
        run: AgentRun,
        approval: AgentApproval
    ): AgentLoopResult.WaitingApproval = AgentLoopResult.WaitingApproval(
        run = run,
        transcript = AgentLoopTranscript(emptyList(), 1, 0),
        invocationId = approval.invocationId,
        callHash = "c".repeat(64),
        approval = approval
    )

    /** Approval derived from the exact frozen command so the owner binding check can pass. */
    fun pendingApprovalFor(
        command: FrozenToolCommand,
        approvalId: String = "approval-fault-1",
        createdAtMillis: Long = 1_000_000L,
        expiresAtMillis: Long = 5_000_000L
    ): AgentApproval = AgentApproval(
        approvalId = approvalId,
        invocationId = command.invocationId,
        principal = "fault-fixture-user",
        argumentsHash = command.argumentsHash,
        toolSchemaHash = command.schemaHash,
        resourceFingerprint = command.resourceFingerprint,
        policyVersion = command.policyVersion,
        runEpoch = command.runEpoch,
        expiresAtMillis = expiresAtMillis,
        status = AgentApprovalStatus.PENDING,
        createdAtMillis = createdAtMillis
    )

    fun scopeFor(task: AgentTask, path: String = "inputs/brief.txt"): AgentToolScope =
        AgentToolScope(
            taskRoot = "${task.workspaceId}/tasks/${task.taskId}",
            logicalPaths = listOf(path),
            expectedOldHash = null,
            networkDestinations = emptyList()
        )
}

/** Scripted model port; one result per infer call. */
class QueueModelPort(
    private val results: MutableList<AgentModelResult> = mutableListOf()
) : AgentModelPort {
    val requests = mutableListOf<AgentModelRequest>()
    var inferCount: Int = 0
    var stopCount: Int = 0
    var gate: CompletableDeferred<Unit>? = null

    fun enqueue(result: AgentModelResult) {
        results += result
    }

    override suspend fun infer(
        request: AgentModelRequest,
        progress: AgentModelProgressSink
    ): AgentModelResult {
        inferCount += 1
        requests += request
        gate?.await()
        return results.removeFirstOrNull()
            ?: throw IllegalStateException("No scripted model result left")
    }

    override suspend fun requestStop(attempt: com.muyuchat.core.agent.contract.AgentModelAttemptRef): AgentStopDispatch {
        stopCount += 1
        return AgentStopDispatch(true)
    }
}

/** Model port that never returns, used to prove cancellation propagation. */
class SuspendingModelPort : AgentModelPort {
    var started: CompletableDeferred<Unit> = CompletableDeferred()
    var cancellationCount: Int = 0

    override suspend fun infer(
        request: AgentModelRequest,
        progress: AgentModelProgressSink
    ): AgentModelResult {
        started.complete(Unit)
        try {
            awaitCancellation()
        } catch (cancelled: CancellationException) {
            cancellationCount += 1
            throw cancelled
        }
    }

    override suspend fun requestStop(attempt: com.muyuchat.core.agent.contract.AgentModelAttemptRef): AgentStopDispatch =
        AgentStopDispatch(true)
}

class RecordingToolExecutor(
    override val descriptor: AgentToolDescriptor,
    private val result: AgentToolResult = AgentRuntimeFixtures.successResult()
) : AgentToolExecutor {
    val commands = mutableListOf<FrozenToolCommand>()
    val contexts = mutableListOf<AgentToolContext>()
    var executeCount: Int = 0

    override suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult {
        executeCount += 1
        commands += command
        contexts += context
        return result
    }
}

class FakeToolCatalog(private val executors: Map<String, AgentToolExecutor>) : AgentToolCatalog {
    override fun find(toolKey: String): AgentToolExecutor? = executors[toolKey]

    override fun snapshot(): List<AgentToolDescriptor> = executors.values.map { it.descriptor }
}

/**
 * Scripted K07 hook. When a [ledger] is supplied it mirrors the production hide order: the frozen
 * command/descriptor/scope is recorded (CAS PROPOSED -> VALIDATED) before the decision is returned.
 */
class RecordingFrozenCommandHook(
    private val ledger: com.muyuchat.core.agent.runtime.journal.AgentToolInvocationLedger? = null,
    private val clockMillis: Long = 1_000L,
    private val factory: suspend (AgentFrozenCommandRequest) -> AgentFrozenCommandDecision =
        { AgentFrozenCommandDecision.ReadyToExecute(AgentRuntimeFixtures.frozenCommand(it)) }
) : AgentFrozenCommandHook {
    val requests = mutableListOf<AgentFrozenCommandRequest>()

    override suspend fun freeze(request: AgentFrozenCommandRequest): AgentFrozenCommandDecision {
        requests += request
        val decision = factory(request)
        if (ledger == null) return decision
        val command = when (decision) {
            is AgentFrozenCommandDecision.ReadyToExecute -> decision.command
            is AgentFrozenCommandDecision.WaitingApproval -> decision.command
            is AgentFrozenCommandDecision.Denied,
            is AgentFrozenCommandDecision.Failed -> null
        } ?: return decision
        val recorded = try {
            ledger.recordFrozen(
                AgentFrozenInvocation(
                    command = command,
                    descriptor = request.descriptor,
                    scope = AgentRuntimeFixtures.scopeFor(request.task),
                    recordedAtMillis = clockMillis
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            AgentToolInvocationRecord.Rejected(
                com.muyuchat.core.agent.contract.AgentErrorCodes.INTERNAL_UNEXPECTED,
                failure.javaClass.simpleName
            )
        }
        return if (recorded is AgentToolInvocationRecord.Recorded) {
            decision
        } else {
            val rejected = recorded as AgentToolInvocationRecord.Rejected
            AgentFrozenCommandDecision.Failed(rejected.errorCode, rejected.safeMessage)
        }
    }
}

class FakeArtifactVerifier(
    var registered: Set<String> = emptySet()
) : AgentArtifactVerifier {
    override suspend fun verify(taskId: String, artifactIds: List<String>): List<String> =
        artifactIds.filterNot { it in registered }
}

/** Scripted executor for owner-level tests; supports immediate results and a blocking mode. */
open class ScriptedRunExecutor(
    private val results: MutableList<AgentLoopResult> = mutableListOf()
) : AgentRunExecutor {
    val calls = mutableListOf<AgentRun>()
    val resumes = mutableListOf<AgentLoopResume?>()
    var executeCount: Int = 0
    var cancellationCount: Int = 0
    var suspendForever: Boolean = false
    var gate: CompletableDeferred<Unit>? = null

    fun enqueue(result: AgentLoopResult) {
        results += result
    }

    open override suspend fun execute(
        run: AgentRun,
        task: AgentTask,
        resume: AgentLoopResume?,
        progress: AgentModelProgressSink
    ): AgentLoopResult {
        executeCount += 1
        calls += run
        resumes += resume
        if (suspendForever) {
            try {
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                cancellationCount += 1
                throw cancelled
            }
        }
        gate?.await()
        return results.removeFirstOrNull()
            ?: AgentRuntimeFixtures.completed(run)
    }
}

class FixedRunSpecProvider(
    private val spec: AgentRunSpec = AgentRunSpec(
        modelBinding = AgentRuntimeFixtures.binding(),
        policy = AgentRunPolicy(),
        budget = AgentBudget()
    )
) : AgentRunSpecProvider {
    override suspend fun specFor(task: AgentTask): AgentRunSpec = spec
}

/** Minimal immutable catalog entry for custom-schema tests. */
fun customDescriptor(toolKey: String, schemaJson: String, schemaHash: String): AgentToolDescriptor =
    AgentToolDescriptor(
        toolKey = toolKey,
        version = "1",
        displayName = toolKey,
        description = "fixture",
        schemaJson = schemaJson,
        schemaHash = schemaHash,
        sideEffect = AgentToolSideEffect.READ_ONLY,
        dataClass = AgentDataClass.PRIVATE
    )

/** Shared owner assembly for K03 command/owner tests. */
class AgentOwnerHarness(
    scope: kotlinx.coroutines.CoroutineScope,
    val executor: ScriptedRunExecutor = ScriptedRunExecutor(),
    maxPendingCommands: Int = com.muyuchat.core.agent.contract.AgentContract.MAX_PENDING_COMMANDS,
    stopSettlementSource: com.muyuchat.core.agent.runtime.run.AgentRunStopSettlementSource? = null,
    stopJoinTimeoutMillis: Long =
        com.muyuchat.core.agent.runtime.run.DEFAULT_STOP_JOIN_TIMEOUT_MILLIS,
    /** N03 durable boundary; [AgentRunTransaction.Direct] keeps legacy tests unchanged. */
    transaction: com.muyuchat.core.agent.runtime.run.AgentRunTransaction =
        com.muyuchat.core.agent.runtime.run.AgentRunTransaction.Direct,
    /** N03 production wiring substitutes; null keeps the legacy inline approval launch. */
    dispatchHandoff: com.muyuchat.core.agent.runtime.run.AgentApprovedDispatchHandoff? = null,
    dispatchSink: com.muyuchat.core.agent.runtime.run.AgentApprovedDispatchSink? = null,
    /** S4 durable park/wake tokens; a fresh in-memory store keeps legacy tests isolated. */
    val parkTokens: com.muyuchat.core.agent.runtime.run.AgentParkTokenStore =
        com.muyuchat.core.agent.runtime.run.InMemoryAgentParkTokenStore()
) {
    val clock = com.muyuchat.core.agent.runtime.run.ManualAgentMonotonicClock()
    val ids = com.muyuchat.core.agent.runtime.run.SequentialAgentIdGenerator("owner")
    val registry = com.muyuchat.core.agent.runtime.run.AgentActiveAttemptRegistry()
    val journal = InMemoryAgentJournal()
    val toolAuthorization: AgentToolAuthorizationPort = JournalToolAuthorization(journal)
    val owner = com.muyuchat.core.agent.runtime.run.AgentRunOwner(
        journal = journal,
        ledger = journal,
        taskReader = journal,
        questionReader = journal,
        approvalReader = journal,
        toolLedger = journal,
        toolAuthorization = toolAuthorization,
        specProvider = FixedRunSpecProvider(),
        executor = executor,
        processor = com.muyuchat.core.agent.runtime.run.AgentCommandProcessor(
            scope,
            maxPendingNormal = maxPendingCommands
        ),
        clock = clock,
        ids = ids,
        scope = scope,
        attemptRegistry = registry,
        stopSettlementSource = stopSettlementSource,
        transaction = transaction,
        dispatchHandoff = dispatchHandoff,
        dispatchSink = dispatchSink,
        parkTokens = parkTokens,
        stopJoinTimeoutMillis = stopJoinTimeoutMillis
    )

    /**
     * Seeds a durable PROPOSED -> VALIDATED -> WAITING_APPROVAL invocation for a frozen command so
     * owner-level approval tests exercise the real durable binding precondition.
     */
    suspend fun seedFrozenInvocation(command: FrozenToolCommand, stepIndex: Int = 1) {
        val snapshot = journal.readRun(command.runId) ?: error("run ${command.runId} not found")
        val descriptor = AgentV1ToolSpecs.descriptor(
            command.toolKey,
            SupportedToolSchemaValidator()
        )
        val proposal = AgentToolProposal(
            invocationId = command.invocationId,
            runId = command.runId,
            taskId = snapshot.task.taskId,
            stepId = command.stepId,
            stepIndex = stepIndex,
            callOrdinal = command.callOrdinal,
            toolKey = descriptor.toolKey,
            toolVersion = descriptor.version,
            schemaHash = descriptor.schemaHash,
            argumentsHash = "0".repeat(64),
            proposedAtMillis = 1_000L
        )
        check(journal.recordProposed(proposal) is AgentToolInvocationRecord.Recorded) {
            "proposal was rejected"
        }
        check(
            journal.recordFrozen(
                AgentFrozenInvocation(
                    command = command,
                    descriptor = descriptor,
                    scope = AgentRuntimeFixtures.scopeFor(snapshot.task),
                    recordedAtMillis = 1_000L
                )
            ) is AgentToolInvocationRecord.Recorded
        ) { "frozen command was rejected" }
        check(
            journal.markWaitingApproval(command.invocationId, 1_000L) is
                AgentToolInvocationUpdate.Updated
        ) { "waiting-approval transition was rejected" }
    }

    suspend fun createTask(
        goal: String = "整理 inputs/brief.txt",
        budget: AgentBudget = AgentBudget()
    ): com.muyuchat.core.agent.contract.AgentTask {
        val result = owner.createTask(
            com.muyuchat.core.agent.contract.CreateAgentTask(
                commandId = ids.nextId("cmd"),
                goal = goal,
                displayTitle = "fixture task",
                modelBinding = AgentRuntimeFixtures.binding(),
                policy = AgentRunPolicy(),
                budget = budget
            )
        )
        check(result is com.muyuchat.core.agent.contract.AgentCommandResult.Accepted) {
            "createTask rejected: $result"
        }
        return journal.allTasks().last()
    }

    suspend fun start(
        task: com.muyuchat.core.agent.contract.AgentTask,
        expectedTaskRevision: Long = task.revision,
        commandId: String = ids.nextId("cmd")
    ): com.muyuchat.core.agent.contract.AgentCommandResult = owner.startRun(
        com.muyuchat.core.agent.contract.StartAgentRun(
            commandId = commandId,
            taskId = task.taskId,
            expectedTaskRevision = expectedTaskRevision
        )
    )

    suspend fun run(runId: String): com.muyuchat.core.agent.contract.AgentRun? =
        journal.readRun(runId)?.run
}

/**
 * Owner-level fake of the one-shot authorization seam over the in-memory ledger. It proves the
 * owner only resumes with an [AuthorizedToolCommand] reconstructed from durable frozen material;
 * the real PENDING -> CONSUMED CAS is covered by the app-level broker/store tests.
 */
class JournalToolAuthorization(
    private val ledger: InMemoryAgentJournal
) : AgentToolAuthorizationPort {
    var authorizeCount: Int = 0
        private set
    var denyCount: Int = 0
        private set

    override suspend fun authorize(
        request: AgentToolAuthorizationRequest
    ): AgentToolAuthorizationResult {
        authorizeCount += 1
        val invocation = ledger.readInvocation(request.invocationId)
            ?: return AgentToolAuthorizationResult.NotExecuted(
                com.muyuchat.core.agent.contract.AgentErrorCodes.INTERNAL_INVARIANT,
                "The frozen invocation is not recorded."
            )
        if (invocation.runId != request.run.runId ||
            invocation.status != ToolInvocationStatus.WAITING_APPROVAL
        ) {
            return AgentToolAuthorizationResult.NotExecuted(
                com.muyuchat.core.agent.contract.AgentErrorCodes.COMMAND_INVALID_STATE,
                "The invocation is not waiting for approval."
            )
        }
        val frozen = ledger.readFrozenInvocation(request.invocationId)
            ?: return AgentToolAuthorizationResult.NotExecuted(
                com.muyuchat.core.agent.contract.AgentErrorCodes.STORAGE_CORRUPTION,
                "The frozen command material is missing."
            )
        return AgentToolAuthorizationResult.Authorized(
            AuthorizedToolCommand(
                command = frozen.command,
                approvalId = request.approvalId,
                basis = AgentToolAuthorizationBasis.APPROVED_ONCE
            )
        )
    }

    override suspend fun deny(request: AgentToolAuthorizationRequest): AgentToolDenialResult {
        denyCount += 1
        return AgentToolDenialResult.Denied("The user denied this tool invocation.")
    }
}
