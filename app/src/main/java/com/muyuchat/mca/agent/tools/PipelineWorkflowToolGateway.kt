package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationLedger
import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import com.muyuchat.core.agent.runtime.run.AgentBudgetPolicy
import com.muyuchat.core.agent.runtime.run.AgentFrozenCommandHook
import com.muyuchat.core.agent.runtime.run.AgentIdGenerator
import com.muyuchat.core.agent.runtime.run.AgentInvocationProposalRequest
import com.muyuchat.core.agent.runtime.run.AgentLoopResume
import com.muyuchat.core.agent.runtime.run.AgentMonotonicClock
import com.muyuchat.core.agent.runtime.run.AgentResumeToolDecision
import com.muyuchat.core.agent.runtime.run.AgentToolAuthorizationBasis
import com.muyuchat.core.agent.runtime.run.AgentToolExecutionPipeline
import com.muyuchat.core.agent.runtime.run.AgentToolInvocationPipeline
import com.muyuchat.core.agent.runtime.run.AuthorizedToolCommand
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import com.muyuchat.mca.agent.workflow.WorkflowExecutionErrorCodes
import com.muyuchat.mca.agent.workflow.WorkflowToolCall
import com.muyuchat.mca.agent.workflow.WorkflowToolGateway
import com.muyuchat.mca.agent.workflow.WorkflowToolOutcome
import com.muyuchat.mca.agent.workflow.WorkflowToolReceipt
import com.muyuchat.mca.agent.workflow.WorkflowToolReceiptLoad
import com.muyuchat.mca.agent.workflow.WorkflowToolReceiptStore
import kotlinx.coroutines.CancellationException

/**
 * Mutable facts of one run-owned workflow execution.
 *
 * [run] carries the spent budget after each tool call, because the owner applies the loop result's
 * run only at the end of the invocation. [transcript] is the pairing evidence the owner stores for
 * an approval resume; it never becomes model context for a graph run.
 */
class WorkflowToolRunState(
    run: AgentRun,
    transcript: List<AgentTranscriptEntry> = emptyList()
) {
    var run: AgentRun = run
        private set

    private val entries: MutableList<AgentTranscriptEntry> = transcript.toMutableList()

    fun updateRun(next: AgentRun) {
        run = next
    }

    fun append(entry: AgentTranscriptEntry) {
        entries += entry
    }

    /** A resumed node reuses one invocation id; do not append the same pairing twice. */
    fun appendIfAbsent(entry: AgentTranscriptEntry) {
        val invocationId = (entry as? AgentTranscriptEntry.ToolResult)?.invocationId
        if (invocationId != null &&
            entries.any { it is AgentTranscriptEntry.ToolResult && it.invocationId == invocationId }
        ) {
            return
        }
        entries += entry
    }

    fun transcriptSnapshot(): List<AgentTranscriptEntry> = entries.toList()
}

/**
 * Workflow tool node over the same invocation/execution pipeline as the model loop (N03/N18).
 *
 * No frozen command is built here: schema validation, PROPOSED/VALIDATED ledger rows, the typed
 * resource scope, approval binding, budget check, RUNNING CAS and terminal settlement all come from
 * [AgentToolInvocationPipeline] + [AgentToolExecutionPipeline]. The workflow owns only the stable
 * node step identity, so a resumed run re-proposes the same logical invocation instead of creating a
 * second side effect. A call that needs approval suspends the graph; the owner persists a real
 * WAITING_APPROVAL run and the approved command comes back through [AgentLoopResume].
 */
class PipelineWorkflowToolGateway(
    private val run: AgentRun,
    private val task: AgentTask,
    private val state: WorkflowToolRunState,
    private val catalog: AgentToolCatalog,
    private val toolLedger: AgentToolInvocationLedger,
    private val commandHook: AgentFrozenCommandHook,
    private val budgetPolicy: AgentBudgetPolicy,
    private val ids: AgentIdGenerator,
    private val clock: AgentMonotonicClock,
    private val receipts: WorkflowToolReceiptStore,
    private val events: com.muyuchat.core.agent.runtime.run.AgentRunEventSink? = null,
    private val resume: AgentLoopResume?,
    private val codec: StrictJsonCodec = StrictAgentJsonCodec()
) : WorkflowToolGateway {

    private val invocationPipeline = AgentToolInvocationPipeline(
        catalog = catalog,
        schemaValidator = com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator(),
        toolLedger = toolLedger,
        commandHook = commandHook,
        budgetPolicy = budgetPolicy,
        ids = ids,
        clock = clock
    )

    override suspend fun invoke(call: WorkflowToolCall): WorkflowToolOutcome {
        val stepId = stepIdFor(call.runId, call.nodeId)
        val callHash = callHashFor(call)
        val stableCallHash = stableCallHashFor(call)
        // The ledger and the graph checkpoint are separate stores. A receipt written after the
        // ledger terminal result is the only evidence that lets a resumed node reuse a side effect
        // instead of dispatching it again; it is task-scoped so a successor run of the same task
        // reuses the predecessor result. A corrupt receipt fails closed.
        when (
            val receipt = receipts.loadNode(
                taskId = task.taskId,
                frameKey = call.frameKey,
                nodeId = call.nodeId,
                legacyRunId = call.runId,
                legacyStepId = stepId
            )
        ) {
            is WorkflowToolReceiptLoad.Corrupt -> return failed(
                WorkflowExecutionErrorCodes.TOOL_RECEIPT_CORRUPT,
                receipt.safeMessage
            )
            is WorkflowToolReceiptLoad.Loaded -> return reuse(receipt.receipt, call, callHash, stableCallHash)
            WorkflowToolReceiptLoad.Absent -> Unit
        }
        when (val decision = resume?.toolDecision) {
            is AgentResumeToolDecision.Authorized -> {
                if (decision.authorization.command.stepId != stepId) {
                    return failed(
                        WorkflowExecutionErrorCodes.TOOL_APPROVAL_MISMATCH,
                        "The approved invocation does not belong to this workflow node."
                    )
                }
                if (decision.authorization.command.toolKey != call.toolKey) {
                    return failed(
                        WorkflowExecutionErrorCodes.TOOL_APPROVAL_MISMATCH,
                        "The approved invocation names a different tool."
                    )
                }
                return execute(decision.authorization, call, callHash, stepId)
            }
            is AgentResumeToolDecision.NotExecuted -> {
                if (decision.toolKey != call.toolKey) {
                    return failed(
                        WorkflowExecutionErrorCodes.TOOL_APPROVAL_MISMATCH,
                        "The rejected invocation names a different tool."
                    )
                }
                return failed(
                    decision.errorCode ?: WorkflowExecutionErrorCodes.TOOL_NOT_EXECUTED,
                    decision.safeMessage
                )
            }
            null -> Unit
        }
        val prepared = invocationPipeline.prepare(
            AgentInvocationProposalRequest(
                run = state.run,
                task = task,
                toolName = call.toolKey,
                argumentsJson = call.argumentsJson,
                callHash = callHash,
                stepIndex = 0,
                spent = state.run.spentBudget,
                stepId = stepId
            )
        ) { runNow, _, descriptorNow, normalizedArguments ->
            val emitted = events?.toolProposed(runNow, descriptorNow, normalizedArguments) ?: runNow
            state.updateRun(emitted)
            emitted
        }
        return when (prepared) {
            is AgentToolInvocationPipeline.Preparation.Ready -> execute(
                AuthorizedToolCommand(
                    command = prepared.command,
                    approvalId = null,
                    basis = AgentToolAuthorizationBasis.WORKSPACE_READ_GRANT
                ),
                call,
                callHash,
                stepId
            )
            is AgentToolInvocationPipeline.Preparation.Waiting -> {
                val emitted = events?.approvalRequested(
                    run = prepared.run,
                    command = prepared.command,
                    descriptor = prepared.descriptor,
                    normalizedArgumentsJson = prepared.command.normalizedArgumentsJson
                ) ?: prepared.run
                state.updateRun(emitted)
                WorkflowToolOutcome.WaitingApproval(
                    invocationId = prepared.invocationId,
                    callHash = callHash,
                    approval = prepared.approval
                )
            }
            is AgentToolInvocationPipeline.Preparation.Rejected -> failed(
                prepared.errorCode,
                prepared.safeMessage
            )
            is AgentToolInvocationPipeline.Preparation.BudgetExceeded -> failed(
                WorkflowExecutionErrorCodes.BUDGET_EXHAUSTED,
                prepared.decision.error.safeMessage
            )
            is AgentToolInvocationPipeline.Preparation.Unbound -> failed(
                WorkflowExecutionErrorCodes.TOOL_NOT_EXECUTED,
                "The approval could not be bound to the frozen invocation."
            )
        }
    }

    private suspend fun execute(
        authorization: AuthorizedToolCommand,
        call: WorkflowToolCall,
        callHash: String,
        stepId: String
    ): WorkflowToolOutcome {
        val stableCallHash = stableCallHashFor(call)
        if (authorization.command.stepId != stepId || authorization.command.runId != run.runId) {
            return failed(
                WorkflowExecutionErrorCodes.TOOL_APPROVAL_MISMATCH,
                "The authorized command does not belong to this workflow frame."
            )
        }
        val budget = budgetPolicy.checkNextToolInvocation(state.run.spentBudget, state.run.budget)
        if (budget is com.muyuchat.core.agent.runtime.run.AgentBudgetDecision.Exceeded) {
            return failed(WorkflowExecutionErrorCodes.BUDGET_EXHAUSTED, budget.error.safeMessage)
        }
        val spent = budgetPolicy.chargeToolInvocation(state.run.spentBudget)
        state.updateRun(state.run.copy(spentBudget = spent))
        val outcome = try {
            AgentToolExecutionPipeline(
                toolLedger = toolLedger,
                clock = clock,
                codec = codec,
                catalog = catalog
            ).execute(
                authorization = authorization,
                run = state.run,
                task = task,
                stepIndex = 0,
                emit = { runNow, type, summary, invocationId ->
                    val emitted = events?.append(runNow, type, summary, invocationId) ?: runNow
                    state.updateRun(emitted)
                    emitted
                },
                readRun = { null }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return failed(
                WorkflowExecutionErrorCodes.TOOL_UNKNOWN,
                "The tool execution ended without a proven terminal result."
            )
        }
        state.appendIfAbsent(
            AgentTranscriptEntry.ToolResult(
                invocationId = authorization.command.invocationId,
                callHash = callHash,
                toolKey = authorization.command.toolKey,
                result = observation(outcome.result)
            )
        )
        // Receipts are evidence, not the tool outcome: a bounded/IO failure must never turn a
        // successful execution into a node failure. A missing receipt only makes a later resume
        // fail closed instead of reusing the result.
        try {
            receipts.saveTask(
                taskId = task.taskId,
                frameKey = call.frameKey,
                nodeId = call.nodeId,
                receipt = WorkflowToolReceipt(
                    invocationId = authorization.command.invocationId,
                    toolKey = authorization.command.toolKey,
                    callHash = stableCallHash,
                    status = outcome.result.status,
                    summary = outcome.result.summary.take(MAX_MESSAGE_CHARS),
                    dataJson = outcome.result.dataJson?.takeIf {
                        it.toByteArray(Charsets.UTF_8).size <= MAX_RECEIPT_DATA_BYTES
                    },
                    artifactIds = outcome.result.artifactIds.take(MAX_RECEIPT_ARTIFACTS),
                    errorCode = outcome.result.errorCode,
                    possibleSideEffects = outcome.result.possibleSideEffects
                )
            )
        } catch (_: Throwable) {
            // Bounded receipt storage is best effort; the ledger row remains authoritative.
        }
        return when (outcome.result.status) {
            AgentToolResultStatus.SUCCEEDED -> succeeded(outcome.result.dataJson, outcome.result.summary, outcome.result.artifactIds)
            AgentToolResultStatus.UNKNOWN -> failed(
                WorkflowExecutionErrorCodes.TOOL_UNKNOWN,
                outcome.result.summary
            )
            AgentToolResultStatus.DENIED -> failed(
                WorkflowExecutionErrorCodes.TOOL_DENIED,
                outcome.result.summary
            )
            AgentToolResultStatus.NOT_EXECUTED -> failed(
                WorkflowExecutionErrorCodes.TOOL_NOT_EXECUTED,
                outcome.result.summary
            )
            AgentToolResultStatus.CANCELLED -> failed(
                WorkflowExecutionErrorCodes.TOOL_CANCELLED,
                outcome.result.summary
            )
            AgentToolResultStatus.FAILED -> failed(
                outcome.result.errorCode ?: WorkflowExecutionErrorCodes.TOOL_FAILED,
                outcome.result.summary
            )
        }
    }

    /**
     * Reuses the exact terminal result of this node. The receipt is only written after the ledger
     * settled, so this path never re-dispatches a side effect. A receipt for another tool under the
     * same stable node key is a configuration/integrity error and fails closed.
     */
    private suspend fun reuse(
        receipt: WorkflowToolReceipt,
        call: WorkflowToolCall,
        callHash: String,
        stableCallHash: String
    ): WorkflowToolOutcome {
        if (receipt.toolKey != call.toolKey) {
            return failed(
                WorkflowExecutionErrorCodes.TOOL_RECEIPT_MISMATCH,
                "The stored tool receipt belongs to another tool."
            )
        }
        if (receipt.callHash != stableCallHash) {
            return failed(
                WorkflowExecutionErrorCodes.TOOL_RECEIPT_MISMATCH,
                "The stored tool receipt belongs to a different call."
            )
        }
        state.appendIfAbsent(
            AgentTranscriptEntry.ToolResult(
                invocationId = receipt.invocationId,
                callHash = callHash,
                toolKey = receipt.toolKey,
                result = observation(receipt)
            )
        )
        val reusedResult = com.muyuchat.core.agent.contract.AgentToolResult(
            status = receipt.status,
            summary = receipt.summary,
            dataJson = receipt.dataJson,
            artifactIds = receipt.artifactIds,
            errorCode = receipt.errorCode,
            possibleSideEffects = receipt.possibleSideEffects
        )
        val emitted = events?.toolResult(
            run = state.run,
            toolKey = receipt.toolKey,
            invocationId = receipt.invocationId,
            result = reusedResult
        ) ?: state.run
        state.updateRun(emitted)
        return when (receipt.status) {
            AgentToolResultStatus.SUCCEEDED ->
                succeeded(receipt.dataJson, receipt.summary, receipt.artifactIds)
            AgentToolResultStatus.UNKNOWN -> failed(
                WorkflowExecutionErrorCodes.TOOL_UNKNOWN,
                receipt.summary
            )
            AgentToolResultStatus.DENIED -> failed(
                WorkflowExecutionErrorCodes.TOOL_DENIED,
                receipt.summary
            )
            AgentToolResultStatus.NOT_EXECUTED -> failed(
                WorkflowExecutionErrorCodes.TOOL_NOT_EXECUTED,
                receipt.summary
            )
            AgentToolResultStatus.CANCELLED -> failed(
                WorkflowExecutionErrorCodes.TOOL_CANCELLED,
                receipt.summary
            )
            AgentToolResultStatus.FAILED -> failed(
                receipt.errorCode ?: WorkflowExecutionErrorCodes.TOOL_FAILED,
                receipt.summary
            )
        }
    }

    private fun observation(result: com.muyuchat.core.agent.contract.AgentToolResult): AgentJsonValue =
        AgentJsonValues.obj(
            "status" to AgentJsonValues.str(result.status.name),
            "summary" to AgentJsonValues.str(result.summary),
            "errorCode" to AgentJsonValues.str(result.errorCode ?: ""),
            "possibleSideEffects" to AgentJsonValues.bool(result.possibleSideEffects)
        )

    private fun observation(receipt: WorkflowToolReceipt): AgentJsonValue =
        AgentJsonValues.obj(
            "status" to AgentJsonValues.str(receipt.status.name),
            "summary" to AgentJsonValues.str(receipt.summary),
            "errorCode" to AgentJsonValues.str(receipt.errorCode ?: ""),
            "possibleSideEffects" to AgentJsonValues.bool(receipt.possibleSideEffects)
        )

    private fun succeeded(
        dataJson: String?,
        summary: String,
        artifactIds: List<String>
    ): WorkflowToolOutcome {
        if (dataJson.isNullOrBlank()) {
            return WorkflowToolOutcome.Succeeded(
                AgentJsonValues.obj(
                    "summary" to AgentJsonValues.str(summary),
                    "artifactIds" to AgentJsonValues.arr(artifactIds.map(AgentJsonValues::str))
                )
            )
        }
        val parsed = codec.parse(dataJson) as? AgentJsonParseResult.Parsed
            ?: return failed(
                WorkflowExecutionErrorCodes.TOOL_RESULT_INVALID,
                "The tool returned data that is not strict JSON."
            )
        return WorkflowToolOutcome.Succeeded(parsed.value)
    }

    private fun failed(code: String, message: String): WorkflowToolOutcome.Failed =
        WorkflowToolOutcome.Failed(code, message.take(MAX_MESSAGE_CHARS))

    private fun stepIdFor(runId: String, nodeId: String): String =
        "wfnode-" + AgentHashes.sha256HexUtf8("$runId\u0000$nodeId").take(40)

    private fun callHashFor(call: WorkflowToolCall): String =
        "wf-" + AgentHashes.sha256HexUtf8(
            "${call.runId}\u0000${call.nodeId}\u0000${call.toolKey}\u0000${call.argumentsJson}"
        ).take(48)

    /**
     * Run-independent identity of the logical call. The run id is deliberately absent: a successor
     * run of the same task must recognize the predecessor's receipt, while changed arguments must
     * not reuse a stale result. The frame key keeps foreach/subworkflow frames apart.
     */
    private fun stableCallHashFor(call: WorkflowToolCall): String =
        "wfc-" + AgentHashes.sha256HexUtf8(
            "${call.frameKey}\u0000${call.nodeId}\u0000${call.toolKey}\u0000${call.argumentsJson}"
        ).take(48)

    private companion object {
        const val MAX_MESSAGE_CHARS = 400
        const val MAX_RECEIPT_DATA_BYTES = 64 * 1024
        const val MAX_RECEIPT_ARTIFACTS = 20
    }
}

/** Factory seam so the graph service can build one gateway per run without owning pipeline types. */
interface WorkflowToolGatewayFactory {
    suspend fun create(
        run: AgentRun,
        task: AgentTask,
        state: WorkflowToolRunState,
        resume: AgentLoopResume?
    ): WorkflowToolGateway
}
