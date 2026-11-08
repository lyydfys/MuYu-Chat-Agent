package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentJournal
import com.muyuchat.core.agent.contract.AgentModelAttemptRef
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentModelRequest
import com.muyuchat.core.agent.contract.AgentModelResult
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.AppendEventsTransition
import com.muyuchat.core.agent.runtime.AgentArgumentValidation
import com.muyuchat.core.agent.runtime.AgentTurnDecodeResult
import com.muyuchat.core.agent.runtime.AgentTurnDecoder
import com.muyuchat.core.agent.runtime.AgentControlMessage
import com.muyuchat.core.agent.runtime.AgentSystemPromptBuilder
import com.muyuchat.core.agent.runtime.AgentToolSchemaValidator
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import com.muyuchat.core.agent.runtime.AgentTranscriptSerializer
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.context.AgentContextAssembler
import com.muyuchat.core.agent.runtime.context.AgentContextAssemblyRequest
import com.muyuchat.core.agent.runtime.context.AgentContextAssemblyResult
import com.muyuchat.core.agent.runtime.journal.AgentJournalRejectedException
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationLedger
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationRecord
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationUpdate
import com.muyuchat.core.agent.runtime.journal.AgentToolProposal
import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import com.muyuchat.core.agent.runtime.protocol.AgentTurnExecution
import com.muyuchat.core.agent.runtime.protocol.toExecutionMessage
import com.muyuchat.core.agent.runtime.protocol.AgentProtocolErrorCodes
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import kotlinx.coroutines.CancellationException

/**
 * Deterministic PROMPTED_V1 step coordinator.
 *
 * One execution consumes one run from RUNNING until it needs the owner (terminal, pause, user
 * question, approval) or the caller cancels it. It never spawns unscoped work and never starts a
 * second model call in parallel: tool calls are serial, at most one per response.
 *
 * It uses the frozen K02 seams through injection and commits only append-only events/attempt facts
 * to the journal here; run state transitions and the single terminal write stay with
 * [AgentRunOwner], so there is exactly one run-state writer.
 */
class AgentPromptedLoopCoordinator(
    private val decoderFactory: (Set<String>) -> AgentTurnDecoder,
    private val serializer: AgentTranscriptSerializer,
    private val systemPromptBuilder: AgentSystemPromptBuilder,
    private val catalogProvider: AgentRunToolCatalogProvider,
    internal val schemaValidator: AgentToolSchemaValidator,
    internal val journal: AgentJournal,
    internal val toolLedger: AgentToolInvocationLedger,
    private val modelPort: com.muyuchat.core.agent.contract.AgentModelPort,
    internal val budgetPolicy: AgentBudgetPolicy,
    private val contextAssembler: AgentContextAssembler,
    internal val commandHook: AgentFrozenCommandHook,
    private val artifactVerifier: AgentArtifactVerifier,
    internal val ids: AgentIdGenerator,
    internal val clock: AgentMonotonicClock,
    private val attemptRegistry: AgentActiveAttemptRegistry = AgentActiveAttemptRegistry(),
    /**
     * Dispatcher for the bounded read-only batch (N06). Null keeps the caller's execution context,
     * so the production assembly is unchanged; the coordinator never creates its own dispatcher.
     */
    internal val parallelDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null,
    /**
     * Interjections typed while the run is in flight (Mika's "read at the next step boundary").
     *
     * Shared with the owner through the same registry instance; tests inject their own so a case can
     * assert the exact drained text without touching process-wide state.
     */
    private val interjectionQueues: AgentInterjectionQueues = AgentInterjectionQueues.global,
    /**
     * Optional operation knowledge for the SYSTEM prompt. [AgentKnowledgePort.NONE] is the default,
     * so a run built without an app-side provider produces byte-identical prompts.
     */
    private val knowledgePort: com.muyuchat.core.agent.contract.AgentKnowledgePort =
        com.muyuchat.core.agent.contract.AgentKnowledgePort.NONE
) : AgentRunExecutor {

    internal val codec: StrictJsonCodec = StrictAgentJsonCodec()

    /** One audit-event writer shared with the workflow graph strategy. */
    internal val eventWriter: AgentRunEventSink = AgentRunEventWriter(journal, clock, ids)

    /** Canonical transcript v2 records for native calls/results (N05d). */
    internal val transcriptV2 =
        com.muyuchat.core.agent.runtime.protocol.AgentTranscriptV2Codec(codec)

    override suspend fun execute(
        run: AgentRun,
        task: AgentTask,
        resume: AgentLoopResume?,
        progress: AgentModelProgressSink
    ): AgentLoopResult {
        try {
            return executeInternal(run, task, resume, progress)
        } finally {
            releaseRunCatalog(catalogProvider, run)
        }
    }

    /**
     * S3a prompt-only frame: run the same loop under the parent run/task with an explicit empty
     * tool catalog.
     *
     * The frame does not resolve, record or release the parent run's catalog: the workflow
     * gateway owns that lifecycle. An empty catalog is enforced here, not inferred from policy
     * fields, so a frame can never propose or execute a tool. A model that still emits a call
     * fails through the normal tool resolution path and produces no side effect.
     */
    suspend fun executePromptOnlyFrame(
        run: AgentRun,
        task: AgentTask,
        resume: AgentLoopResume?,
        progress: AgentModelProgressSink
    ): AgentLoopResult = executeInternal(
        run = run,
        task = task,
        resume = resume,
        progress = progress,
        catalogOverride = PromptOnlyFrameCatalog
    )

    /**
     * S3b same-root frame: tools, skills or memory execute only when the resolved run catalog is
     * strictly [AgentToolSideEffect.READ_ONLY].
     *
     * A write/network/external tool could suspend for approval, and the parent workflow frame has
     * no nested-approval checkpoint yet. The guard therefore runs before the first model request:
     * such a catalog returns a typed failure with zero side effects. A read-only catalog runs the
     * one loop with the exact run-scoped snapshot; like the prompt-only frame it neither records nor
     * releases the parent's catalog identity (the workflow gateway owns that lifecycle).
     */
    suspend fun executeSameRootFrame(
        run: AgentRun,
        task: AgentTask,
        resume: AgentLoopResume?,
        progress: AgentModelProgressSink
    ): AgentLoopResult {
        val catalog = try {
            catalogProvider.catalogFor(run, task)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return catalogUnavailableResult(run, failure)
        }
        val unsafe = catalog.snapshot().firstOrNull {
            it.sideEffect != AgentToolSideEffect.READ_ONLY
        }
        if (unsafe != null) {
            return AgentLoopResult.Failed(
                run = run,
                transcript = AgentLoopTranscript(emptyList(), 0, 0),
                error = AgentError(
                    code = "WF_FRAME_WRITE_TOOL_UNSUPPORTED",
                    category = AgentErrorCategory.POLICY,
                    retryability = AgentRetryability.AFTER_USER_ACTION,
                    safeMessage = "The same-root frame cannot use '${unsafe.toolKey}': write, " +
                        "network and external tools need nested approval checkpointing.",
                    correlationId = run.runId
                )
            )
        }
        return executeInternal(
            run = run,
            task = task,
            resume = resume,
            progress = progress,
            catalogOverride = catalog
        )
    }

    private suspend fun executeInternal(
        run: AgentRun,
        task: AgentTask,
        resume: AgentLoopResume?,
        progress: AgentModelProgressSink,
        catalogOverride: AgentToolCatalog? = null
    ): AgentLoopResult {
        // Resolve the run-scoped catalog exactly once, before any resume/decoder work, so every
        // model turn and tool resolution in this execution sees the same immutable snapshot.
        var catalog = if (catalogOverride != null) {
            catalogOverride
        } else {
            try {
                catalogProvider.catalogFor(run, task)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                return catalogUnavailableResult(run, failure)
            }
        }
        // N04: the catalog identity is durable for the run. A resumed execution may only continue
        // with the same hash. When the current surface drifted, the provider may reconstruct the
        // exact recorded surface; otherwise the run fails closed instead of silently changing which
        // tools/approvals it can reach. The first execution records the hash before any model work.
        // A prompt-only frame is deliberately outside the parent's catalog identity: it does not
        // read, record or restore the parent's run-scoped tool surface.
        var currentRun = run
        if (catalogOverride == null) {
            var catalogHash = AgentToolCatalogHashing.hash(catalog)
            val recordedCatalog = readRecordedCatalogHash(run.runId)
            if (recordedCatalog is CatalogIdentity.Recorded && recordedCatalog.hash != catalogHash) {
                val restored = if (recordedCatalog.tools.isEmpty()) {
                    null
                } else {
                    try {
                        catalogProvider.catalogForRecorded(
                            run = run,
                            task = task,
                            recorded = AgentRecordedCatalog(recordedCatalog.hash, recordedCatalog.tools)
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        null
                    }
                }
                if (restored == null) {
                    return catalogDriftResult(run, recordedCatalog, catalogHash)
                }
                catalog = restored
                catalogHash = AgentToolCatalogHashing.hash(restored)
                if (catalogHash != recordedCatalog.hash) {
                    return catalogDriftResult(run, recordedCatalog, catalogHash)
                }
                currentRun = appendEvent(
                    currentRun,
                    AgentEventTypes.TOOL_CATALOG_RESTORED,
                    catalogEventSummary(catalogHash, catalog)
                )
            }
            if (recordedCatalog is CatalogIdentity.Unreadable) {
                return catalogDriftResult(run, recordedCatalog, catalogHash)
            }
            if (recordedCatalog is CatalogIdentity.Absent) {
                currentRun = appendEvent(
                    currentRun,
                    AgentEventTypes.TOOL_CATALOG_RESOLVED,
                    catalogEventSummary(catalogHash, catalog)
                )
            }
        }
        val decoder = decoderFactory(
            catalog.snapshot().map { it.toolKey }.toSet()
        )
        val entries = ArrayList<AgentTranscriptEntry>()
        var modelRetries: Int = 0
    var stepIndex: Int
        var repairs: Int
        var spent = run.spentBudget
        if (resume == null) {
            stepIndex = 0
            repairs = 0
        } else {
            entries += resume.transcript.entries
            stepIndex = resume.transcript.nextStepIndex
            repairs = resume.transcript.repairsUsed
        }
        resume?.let { resumeState ->
            resumeState.userAnswer?.let { answer ->
                entries += AgentTranscriptEntry.UserAnswer(
                    questionId = requireNotNull(resumeState.questionId) { "user answer needs questionId" },
                    text = answer
                )
            }
            val decision = resumeState.toolDecision
            if (decision != null) {
                spent = budgetPolicy.chargeToolInvocation(spent)
                when (decision) {
                    is AgentResumeToolDecision.Authorized -> {
                        val outcome = AgentToolExecutionPipeline(
                            toolLedger = toolLedger,
                            clock = clock,
                            codec = codec,
                            catalog = catalog
                        ).execute(
                            authorization = decision.authorization,
                            run = currentRun,
                            task = task,
                            stepIndex = stepIndex,
                            emit = { eventRun, type, summary, invocationId ->
                                appendEvent(eventRun, type, summary, invocationId)
                            },
                            readRun = { runId -> journal.readRun(runId)?.run }
                        )
                        currentRun = outcome.run.copy(spentBudget = spent)
                        val providerCallId = decision.authorization.command.providerCallId
                        entries += AgentTranscriptEntry.ToolResult(
                            invocationId = decision.authorization.command.invocationId,
                            callHash = decision.callHash,
                            toolKey = decision.authorization.command.toolKey,
                            result = providerCallId?.let { callId ->
                                nativeResultObservation(callId, toObservation(outcome.result))
                            } ?: toObservation(outcome.result),
                            providerCallId = providerCallId
                        )
                    }
                    is AgentResumeToolDecision.NotExecuted -> {
                        currentRun = appendEvent(
                            currentRun,
                            AgentEventTypes.TOOL_APPROVAL_RESOLVED,
                            AgentToolLifecycle.notExecutedSummary(
                                codec,
                                decision.toolKey,
                                decision.status,
                                decision.errorCode
                            ),
                            decision.invocationId
                        )
                        currentRun = appendEvent(
                            currentRun,
                            AgentEventTypes.TOOL_RESULT,
                            AgentToolLifecycle.notExecutedSummary(
                                codec,
                                decision.toolKey,
                                decision.status,
                                decision.errorCode
                            ),
                            decision.invocationId
                        )
                        entries += AgentTranscriptEntry.ToolResult(
                            invocationId = decision.invocationId,
                            callHash = decision.callHash,
                            toolKey = decision.toolKey,
                            result = toolObservation(
                                status = decision.status,
                                summary = decision.safeMessage,
                                errorCode = decision.errorCode
                            )
                        )
                    }
                }
                stepIndex += 1
            }
        }

        val activeSince = clock.nowMillis()
        try {
            modelLoop@ while (true) {
                budgetPolicy.checkRunActiveTime(activeSince, currentRun.budget)
                    .exceededOrNull()?.let { exceeded ->
                        return pause(currentRun, entries, stepIndex, repairs, spent, exceeded)
                    }
                budgetPolicy.checkNextModelAttempt(spent, currentRun.budget)
                    .exceededOrNull()?.let { exceeded ->
                        return pause(currentRun, entries, stepIndex, repairs, spent, exceeded)
                    }
                budgetPolicy.checkGeneratedTokens(spent, currentRun.budget)
                    .exceededOrNull()?.let { exceeded ->
                        return pause(currentRun, entries, stepIndex, repairs, spent, exceeded)
                    }
                // Step boundary: everything typed while the previous step ran is delivered once, in
                // arrival order, as user text before the model sees this step.
                val drained = interjectionQueues.drainAsEntries(currentRun.runId, clock.nowMillis())
                if (drained.isNotEmpty()) entries.addAll(drained)
                // Operation knowledge is resolved once per step from the run's own goal, so a
                // retry inside the step cannot see a different fragment than the first attempt.
                val operationKnowledge = resolveOperationKnowledge(task.goal)
                val systemPrompt = systemPromptBuilder.build(
                    catalog.snapshot(),
                    currentRun.policy.policyVersion,
                    budgetSummaryJson(currentRun.budget),
                    currentRun.modelBinding.protocolMode,
                    operationKnowledge
                )
                val assembly = contextAssembler.assemble(
                    AgentContextAssemblyRequest(
                        systemPrompt = systemPrompt,
                        userGoal = task.goal,
                        entries = entries,
                        contextWindowTokens = currentRun.modelBinding.contextWindowTokens,
                        reservedOutputTokens = currentRun.budget.textOutputTokens,
                        toolResultToModelBytes = currentRun.budget.toolResultToModelBytes,
                        protocolMode = currentRun.modelBinding.protocolMode
                    )
                )
                when (assembly) {
                    is AgentContextAssemblyResult.Overflow -> return AgentLoopResult.Paused(
                        run = currentRun.copy(spentBudget = spent),
                        transcript = transcript(entries, stepIndex, repairs),
                        reason = AgentPauseReason.CONTEXT_LIMIT,
                        error = assembly.error
                    )
                    is AgentContextAssemblyResult.InvalidHistory -> return AgentLoopResult.Failed(
                        run = currentRun.copy(spentBudget = spent),
                        transcript = transcript(entries, stepIndex, repairs),
                        error = assembly.error
                    )
                    is AgentContextAssemblyResult.Ready -> Unit
                }
                val ready = assembly as AgentContextAssemblyResult.Ready
                val maxOutputTokens = budgetPolicy.maxOutputTokens(spent, currentRun.budget)
                if (maxOutputTokens <= 0) {
                    val exceeded = budgetPolicy.checkGeneratedTokens(
                        spent,
                        currentRun.budget,
                        additionalTokens = 1
                    ) as AgentBudgetDecision.Exceeded
                    return pause(currentRun, entries, stepIndex, repairs, spent, exceeded)
                }
                val attemptOrdinal = spent.modelAttempts + 1
                val attemptId = ids.nextId("attempt")
                val identity = AgentAttemptIdentity(
                    runId = currentRun.runId,
                    epoch = currentRun.epoch,
                    stepIndex = stepIndex,
                    attemptOrdinal = attemptOrdinal,
                    attemptId = attemptId
                )
                attemptRegistry.start(identity)
                currentRun = appendEvent(
                    currentRun,
                    AgentEventTypes.MODEL_ATTEMPT_STARTED,
                    "attemptOrdinal=$attemptOrdinal step=$stepIndex"
                )
                val request = AgentModelRequest(
                    attempt = AgentModelAttemptRef(
                        attemptId = attemptId,
                        runId = currentRun.runId,
                        stepIndex = stepIndex,
                        ordinal = attemptOrdinal
                    ),
                    binding = currentRun.modelBinding,
                    systemPrompt = ready.context.systemPrompt,
                    messages = ready.serialization.messages,
                    maxOutputTokens = maxOutputTokens,
                    samplingJson = currentRun.modelBinding.samplingJson,
                    // The frozen run catalog, not a live extension map: a native adapter declares
                    // exactly the tools this run resolved (N05c).
                    tools = catalog.snapshot()
                )
                val modelStartedAt = clock.nowMillis()
                val modelResult = try {
                    modelPort.infer(request, progress)
                } finally {
                    attemptRegistry.clear(currentRun.runId)
                }
                spent = budgetPolicy.chargeModelAttempt(
                    spent,
                    modelStartedAt,
                    generatedTokens = outputTokensOf(modelResult)
                )
                currentRun = currentRun.copy(spentBudget = spent)
                budgetPolicy.checkModelWallTime(modelStartedAt, currentRun.budget)
                    .exceededOrNull()?.let { exceeded ->
                        return pause(currentRun, entries, stepIndex, repairs, spent, exceeded)
                    }
                budgetPolicy.checkGeneratedTokens(spent, currentRun.budget)
                    .exceededOrNull()?.let { exceeded ->
                        return pause(currentRun, entries, stepIndex, repairs, spent, exceeded)
                    }
                currentRun = appendEvent(
                    currentRun,
                    AgentEventTypes.MODEL_ATTEMPT_SETTLED,
                    "terminal=${terminalLabel(modelResult)}"
                )
                when (modelResult) {
                    is AgentModelResult.Cancelled -> return AgentLoopResult.Cancelled(
                        run = currentRun.copy(spentBudget = spent),
                        transcript = transcript(entries, stepIndex, repairs),
                        error = modelResult.error
                    )
                    is AgentModelResult.Failure -> {
                        val error = modelResult.error
                        // A terminal-unverified provider failure is safe to retry within the frozen
                        // budget: the model produced no durable turn, so no tool/result pairing can
                        // be duplicated. Two bounded retries absorb transient cloud resets.
                        if (error.code == AgentErrorCodes.MODEL_TERMINAL_UNVERIFIED &&
                            error.retryability == AgentRetryability.SAFE_RETRY &&
                            modelRetries < MAX_SAFE_MODEL_RETRIES &&
                            spent.modelAttempts < currentRun.budget.modelAttempts
                        ) {
                            modelRetries += 1
                            continue@modelLoop
                        }
                        return when (error.code) {
                            AgentErrorCodes.RESOURCE_BUSY -> AgentLoopResult.Paused(
                                currentRun.copy(spentBudget = spent),
                                transcript(entries, stepIndex, repairs),
                                AgentPauseReason.RESOURCE_BUSY,
                                error
                            )
                            AgentErrorCodes.MODEL_CONTEXT_LIMIT -> AgentLoopResult.Paused(
                                currentRun.copy(spentBudget = spent),
                                transcript(entries, stepIndex, repairs),
                                AgentPauseReason.CONTEXT_LIMIT,
                                error
                            )
                            AgentErrorCodes.MODEL_PROTOCOL_ERROR,
                            AgentErrorCodes.MODEL_TERMINAL_UNVERIFIED -> AgentLoopResult.Paused(
                                currentRun.copy(spentBudget = spent),
                                transcript(entries, stepIndex, repairs),
                                AgentPauseReason.MODEL_PROTOCOL_ERROR,
                                error
                            )
                            else -> AgentLoopResult.Failed(
                                currentRun.copy(spentBudget = spent),
                                transcript(entries, stepIndex, repairs),
                                error
                            )
                        }
                    }
                    is AgentModelResult.Success -> {
                        // N05b/N05c: every provider round becomes one canonical AgentModelTurn; the
                        // execution strategy derives its control message from that turn. A native
                        // adapter supplies the reconstructed turn; prompted adapters leave it null
                        // and the configured decoder (V1) produces it from visible text. An
                        // unsupported turn shape uses the same bounded protocol-repair path.
                        val decodedTurn: AgentTurnDecodeResult = modelResult.turn
                            ?.let { turn -> AgentTurnDecodeResult.Accepted(turn) }
                            ?: decoder.decode(modelResult.visibleText)
                        // N06: a native turn may carry a small batch of read-only calls. Only the
                        // executor invocations overlap (鈮?); proposal, events and transcript pairing
                        // stay serial in input ordinal order. Anything else (write call, oversized
                        // batch, missing call id, prompted binding) falls through to the existing
                        // bounded protocol-repair path instead of silently dropping calls.
                        val batchTurn = (decodedTurn as? AgentTurnDecodeResult.Accepted)
                            ?.turn
                            ?.takeIf {
                                it.finish == com.muyuchat.core.agent.contract.AgentTurnFinish.TOOL_CALL &&
                                    it.calls.size > 1
                            }
                        if (batchTurn != null &&
                            batchTurn.protocol == com.muyuchat.core.agent.contract.AgentTurnProtocol.NATIVE &&
                            currentRun.modelBinding.protocolMode ==
                            com.muyuchat.core.agent.contract.AgentProtocolMode.NATIVE &&
                            batchProblem(batchTurn, catalog) == null
                        ) {
                            when (
                                val batch = executeToolBatch(
                                    turn = batchTurn,
                                    run = currentRun,
                                    task = task,
                                    stepIndex = stepIndex,
                                    catalog = catalog,
                                    spentStart = spent,
                                    entries = entries
                                )
                            ) {
                                is AgentToolBatchOutcome.Continue -> {
                                    spent = batch.spent
                                    currentRun = batch.run.copy(spentBudget = batch.spent)
                                    stepIndex = batch.nextStepIndex
                                    continue
                                }
                                is AgentToolBatchOutcome.Paused -> return AgentLoopResult.Paused(
                                    run = batch.run.copy(spentBudget = batch.spent),
                                    transcript = transcript(entries, stepIndex, repairs),
                                    reason = batch.reason,
                                    error = batch.error
                                )
                                is AgentToolBatchOutcome.NeedsReview -> return AgentLoopResult.NeedsReview(
                                    run = batch.run.copy(spentBudget = batch.spent),
                                    transcript = transcript(entries, stepIndex, repairs),
                                    error = batch.error
                                )
                                is AgentToolBatchOutcome.Waiting -> return AgentLoopResult.WaitingApproval(
                                    run = batch.run.copy(spentBudget = spent),
                                    transcript = transcript(entries, stepIndex, repairs),
                                    invocationId = batch.invocationId,
                                    callHash = batch.callHash,
                                    approval = batch.approval
                                )
                            }
                        }
                        var executable: AgentTurnExecution.Executable? = null
                        val failure = when (decodedTurn) {
                            is AgentTurnDecodeResult.Rejected -> decodedTurn
                            is AgentTurnDecodeResult.Accepted ->
                                if (decodedTurn.turn.protocol ==
                                    com.muyuchat.core.agent.contract.AgentTurnProtocol.NATIVE &&
                                    currentRun.modelBinding.protocolMode !=
                                    com.muyuchat.core.agent.contract.AgentProtocolMode.NATIVE
                                ) {
                                    AgentTurnDecodeResult.Rejected(
                                        errorCode = AgentProtocolErrorCodes.TURN_NOT_EXECUTABLE,
                                        safeMessage = "A native turn arrived on a prompted binding.",
                                        repairHintJson = ""
                                    )
                                } else {
                                    when (val execution = decodedTurn.turn.toExecutionMessage()) {
                                        is AgentTurnExecution.Unsupported ->
                                            AgentTurnDecodeResult.Rejected(
                                                errorCode = execution.errorCode,
                                                safeMessage = execution.safeMessage,
                                                repairHintJson = ""
                                            )
                                        is AgentTurnExecution.Executable -> {
                                            executable = execution
                                            null
                                        }
                                    }
                                }
                        }
                        if (failure != null) {
                            entries += AgentTranscriptEntry.ProtocolError(
                                errorCode = failure.errorCode,
                                schemaHintJson = failure.repairHintJson
                            )
                            currentRun = appendEvent(
                                currentRun,
                                AgentEventTypes.PROTOCOL_ERROR,
                                failure.errorCode
                            )
                            if (repairs >= currentRun.budget.protocolRepairs) {
                                protocolFallbackCompletion(
                                    currentRun.copy(spentBudget = spent),
                                    transcript(entries, stepIndex, repairs),
                                    modelResult.visibleText
                                )?.let { return it }
                                return AgentLoopResult.Paused(
                                    run = currentRun.copy(spentBudget = spent),
                                    transcript = transcript(entries, stepIndex, repairs),
                                    reason = AgentPauseReason.MODEL_PROTOCOL_ERROR,
                                    error = protocolError(failure.safeMessage)
                                )
                            }
                            repairs += 1
                            spent = budgetPolicy.chargeProtocolRepair(spent)
                            stepIndex += 1
                            continue
                        }
                        when (val message = requireNotNull(executable).message) {
                            is AgentControlMessage.ToolCall -> {
                                // A native provider call has no prompted envelope: the run journals
                                // the canonical transcript v2 call record (callId + tool + arguments),
                                // so the hash/invocation pairing is stable and the native serializer
                                // can rebuild the exact provider item. A legacy V1 bridge envelope is
                                // still readable through the codec's upcast path.
                                val nativeTurn = modelResult.turn
                                val nativeCall = nativeTurn?.calls?.singleOrNull()
                                val rawEnvelope = if (nativeCall != null) {
                                    nativeCallEnvelope(nativeCall)
                                } else {
                                    modelResult.visibleText
                                }
                                entries += AgentTranscriptEntry.AssistantEnvelope(
                                    rawEnvelope = rawEnvelope,
                                    providerCallId = nativeCall?.callId
                                )
                                val callHash = AgentHashes.sha256HexUtf8(rawEnvelope)
                                val resultsStart = entries.size
                                val toolOutcome = handleToolCall(
                                    message = message,
                                    rawEnvelope = rawEnvelope,
                                    callHash = callHash,
                                    run = currentRun,
                                    task = task,
                                    stepIndex = stepIndex,
                                    catalog = catalog,
                                    spent = spent,
                                    entries = entries,
                                    progress = progress,
                                    providerCallId = nativeCall?.callId
                                )
                                // Pair this provider call with its own tool result so a native
                                // serializer can emit `function_call_output` with the same id, and
                                // record the result as a v2 output record. (A call that parks for
                                // approval pairs on the resume path.)
                                nativeCall?.callId?.let { callId ->
                                    pairNativeResults(entries, resultsStart, callId)
                                }
                                when (toolOutcome) {
                                    is AgentToolHandling.Continue -> {
                                        spent = toolOutcome.spent
                                        currentRun = toolOutcome.run.copy(spentBudget = spent)
                                        stepIndex += 1
                                    }
                                    is AgentToolHandling.Pause -> return AgentLoopResult.Paused(
                                        run = toolOutcome.run.copy(spentBudget = toolOutcome.spent),
                                        transcript = transcript(entries, stepIndex, repairs),
                                        reason = toolOutcome.reason,
                                        error = toolOutcome.error
                                    )
                                    is AgentToolHandling.NeedsReview -> return AgentLoopResult.NeedsReview(
                                        run = toolOutcome.run.copy(spentBudget = toolOutcome.spent),
                                        transcript = transcript(entries, stepIndex, repairs),
                                        error = toolOutcome.error
                                    )
                                    is AgentToolHandling.WaitApproval -> return AgentLoopResult.WaitingApproval(
                                        run = toolOutcome.run.copy(spentBudget = spent),
                                        transcript = transcript(entries, stepIndex, repairs),
                                        invocationId = toolOutcome.invocationId,
                                        callHash = callHash,
                                        approval = toolOutcome.approval
                                    )
                                }
                            }
                            is AgentControlMessage.AskUser -> {
                                entries += AgentTranscriptEntry.AssistantEnvelope(modelResult.visibleText)
                                currentRun = appendEvent(
                                    currentRun,
                                    AgentEventTypes.QUESTION_ASKED,
                                    "questionChars=${message.question.length}"
                                )
                                stepIndex += 1
                                return AgentLoopResult.WaitingUser(
                                    run = currentRun.copy(spentBudget = spent),
                                    transcript = transcript(entries, stepIndex, repairs),
                                    questionText = message.question,
                                    options = message.options
                                )
                            }
                            is AgentControlMessage.Final -> {
                                val unknown = try {
                                    artifactVerifier.verify(task.taskId, message.artifactIds)
                                } catch (failure: Throwable) {
                                    if (failure is CancellationException) throw failure
                                    return AgentLoopResult.Failed(
                                        currentRun.copy(spentBudget = spent),
                                        transcript(entries, stepIndex, repairs),
                                        internalError("The artifact verifier failed.")
                                    )
                                }
                                if (unknown.isNotEmpty()) {
                                    entries += AgentTranscriptEntry.ProtocolError(
                                        errorCode = AgentProtocolErrorCodes.FINAL_ARTIFACT_UNKNOWN,
                                        schemaHintJson = ""
                                    )
                                    currentRun = appendEvent(
                                        currentRun,
                                        AgentEventTypes.PROTOCOL_ERROR,
                                        AgentProtocolErrorCodes.FINAL_ARTIFACT_UNKNOWN
                                    )
                                    if (repairs >= currentRun.budget.protocolRepairs) {
                                        return AgentLoopResult.Paused(
                                            currentRun.copy(spentBudget = spent),
                                            transcript(entries, stepIndex, repairs),
                                            AgentPauseReason.MODEL_PROTOCOL_ERROR,
                                            protocolError("The final answer cited unregistered artifacts.")
                                        )
                                    }
                                    repairs += 1
                                    spent = budgetPolicy.chargeProtocolRepair(spent)
                                    stepIndex += 1
                                    continue
                                }
                                entries += AgentTranscriptEntry.AssistantEnvelope(modelResult.visibleText)
                                stepIndex += 1
                                return AgentLoopResult.Completed(
                                    run = currentRun.copy(spentBudget = spent),
                                    transcript = transcript(entries, stepIndex, repairs),
                                    finalText = message.text,
                                    artifactIds = message.artifactIds
                                )
                            }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            attemptRegistry.clear(currentRun.runId)
            throw cancelled
        } catch (lost: AgentJournalRejectedException) {
            val latest = journal.readRun(currentRun.runId)?.run
            val runNow = latest ?: currentRun
            return if (runNow.state == com.muyuchat.core.agent.contract.AgentRunState.STOPPING ||
                runNow.state == com.muyuchat.core.agent.contract.AgentRunState.PAUSING
            ) {
                AgentLoopResult.Cancelled(
                    run = runNow,
                    transcript = transcript(entries, stepIndex, repairs),
                    error = null
                )
            } else {
                AgentLoopResult.Failed(
                    run = runNow,
                    transcript = transcript(entries, stepIndex, repairs),
                    error = internalError("The loop lost its journal revision: ${lost.message}")
                )
            }
        }
    }

    companion object {
        private const val MAX_SAFE_MODEL_RETRIES: Int = 2

        internal const val CORRELATION_ID = "agent-prompted-loop"

        /** N06: bounded native batch; only the executor calls overlap (≤3), settlement is ordinal. */
        internal const val MAX_BATCH_CALLS = 4

        /** Bounded scan for the run's recorded catalog identity (written at its first step). */
        internal const val CATALOG_EVENT_SCAN_LIMIT = 200
    }
}

/** Explicit empty surface for a prompt-only same-root frame: no tool can be proposed or executed. */
private object PromptOnlyFrameCatalog : AgentToolCatalog {
    override fun find(toolKey: String): AgentToolExecutor? = null

    override fun snapshot(): List<AgentToolDescriptor> = emptyList()
}

