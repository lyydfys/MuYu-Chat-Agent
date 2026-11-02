package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.AgentControlMessage
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import kotlinx.coroutines.CancellationException

/**
 * One model tool call: the prompt loop orchestrates proposal (via [AgentToolInvocationPipeline]),
 * transcript/event writes and nested tool execution. The pipeline never writes the transcript or
 * executes a tool; it owns the proposal/freeze/decision ordering.
 */
internal sealed interface AgentToolHandling {
        data class Continue(
            val spent: AgentSpentBudget,
            val run: AgentRun
        ) : AgentToolHandling

        data class Pause(
            val spent: AgentSpentBudget,
            val run: AgentRun,
            val reason: AgentPauseReason,
            val error: AgentError
        ) : AgentToolHandling

        data class WaitApproval(
            val invocationId: String,
            val approval: AgentApproval,
            val run: AgentRun
        ) : AgentToolHandling

        /**
         * A tool outcome whose side effects cannot be proven. The loop must stop and park the run for
         * review; handing an UNKNOWN observation back to the model would allow an unproven side effect
         * to be retried as a brand-new invocation.
         */
        data class NeedsReview(
            val spent: AgentSpentBudget,
            val run: AgentRun,
            val error: AgentError
        ) : AgentToolHandling
    }

/**
 * Pre-execution outcome of one call (N06).
 *
 * [Ready] means the command is frozen, charged and journaled `tool.proposed`, and may be dispatched;
 * it is the only state an executor may run from. [Handled] already wrote its terminal ledger row and
 * typed `tool.result` event and carries the paired observation the caller must append to the
 * transcript, so a serial caller and a batched caller record identical facts in the same order.
 */
internal sealed interface AgentToolPreparation {
    data class Ready(
        val spent: AgentSpentBudget,
        val run: AgentRun,
        val authorization: AuthorizedToolCommand,
        val invocationId: String,
        val callHash: String,
        val toolKey: String
    ) : AgentToolPreparation

    data class Handled(
        val spent: AgentSpentBudget,
        val run: AgentRun,
        val invocationId: String,
        val toolKey: String,
        val observation: AgentJsonValue
    ) : AgentToolPreparation

    data class Paused(
        val spent: AgentSpentBudget,
        val run: AgentRun,
        val reason: AgentPauseReason,
        val error: AgentError
    ) : AgentToolPreparation

    data class Waiting(
        val run: AgentRun,
        val invocationId: String,
        val callHash: String,
        val approval: AgentApproval
    ) : AgentToolPreparation
}

internal suspend fun AgentPromptedLoopCoordinator.handleToolCall(
        message: AgentControlMessage.ToolCall,
        rawEnvelope: String,
        callHash: String,
        run: AgentRun,
        task: AgentTask,
        stepIndex: Int,
        catalog: AgentToolCatalog,
        spent: AgentSpentBudget,
        entries: MutableList<AgentTranscriptEntry>,
        progress: AgentModelProgressSink,
        providerCallId: String? = null
    ): AgentToolHandling {
        return when (
            val preparation = prepareToolCall(
                message = message,
                callHash = callHash,
                run = run,
                task = task,
                stepIndex = stepIndex,
                catalog = catalog,
                spent = spent,
                providerCallId = providerCallId
            )
        ) {
            is AgentToolPreparation.Handled -> {
                entries += AgentTranscriptEntry.ToolResult(
                    preparation.invocationId,
                    callHash,
                    preparation.toolKey,
                    preparation.observation
                )
                AgentToolHandling.Continue(preparation.spent, preparation.run)
            }
            is AgentToolPreparation.Paused -> AgentToolHandling.Pause(
                spent = preparation.spent,
                run = preparation.run,
                reason = preparation.reason,
                error = preparation.error
            )
            is AgentToolPreparation.Waiting -> AgentToolHandling.WaitApproval(
                invocationId = preparation.invocationId,
                approval = preparation.approval,
                run = preparation.run
            )
            is AgentToolPreparation.Ready -> {
                val outcome = AgentToolExecutionPipeline(
                    toolLedger = toolLedger,
                    clock = clock,
                    codec = codec,
                    catalog = catalog
                ).execute(
                    authorization = preparation.authorization,
                    run = preparation.run,
                    task = task,
                    stepIndex = stepIndex,
                    emit = { eventRun, type, summary, invocationId ->
                        appendEvent(eventRun, type, summary, invocationId)
                    },
                    readRun = { runId -> journal.readRun(runId)?.run }
                )
                entries += AgentTranscriptEntry.ToolResult(
                    preparation.invocationId,
                    callHash,
                    preparation.toolKey,
                    toObservation(outcome.result)
                )
                if (outcome.result.status == AgentToolResultStatus.UNKNOWN) {
                    AgentToolHandling.NeedsReview(
                        spent = preparation.spent,
                        run = outcome.run,
                        error = unknownToolResultError(run, outcome.result)
                    )
                } else {
                    AgentToolHandling.Continue(preparation.spent, outcome.run)
                }
            }
        }
    }

internal fun unknownToolResultError(run: AgentRun, result: AgentToolResult): AgentError = AgentError(
    code = AgentErrorCodes.TOOL_RESULT_UNKNOWN,
    category = AgentErrorCategory.TOOL,
    retryability = AgentRetryability.AFTER_USER_ACTION,
    safeMessage = result.summary.take(400).ifBlank { "The tool result is unknown." },
    correlationId = run.runId,
    possibleSideEffects = result.possibleSideEffects
)

/**
 * Proposal/freeze/decision half of one call, shared by the serial single-call path and the bounded
 * read-only batch (N06). It performs every ledger/event write the proposal needs and never invokes an
 * executor and never appends a transcript entry, so a batch can freeze all calls (serial, ordinal)
 * before dispatching them and then record the pairs in input order.
 */
internal suspend fun AgentPromptedLoopCoordinator.prepareToolCall(
        message: AgentControlMessage.ToolCall,
        callHash: String,
        run: AgentRun,
        task: AgentTask,
        stepIndex: Int,
        catalog: AgentToolCatalog,
        spent: AgentSpentBudget,
        providerCallId: String? = null
    ): AgentToolPreparation {
        val pipeline = AgentToolInvocationPipeline(
            catalog = catalog,
            schemaValidator = schemaValidator,
            toolLedger = toolLedger,
            commandHook = commandHook,
            budgetPolicy = budgetPolicy,
            ids = ids,
            clock = clock
        )
        val prepared = pipeline.prepare(
            AgentInvocationProposalRequest(
                run = run,
                task = task,
                toolName = message.name,
                argumentsJson = message.argumentsJson,
                callHash = callHash,
                stepIndex = stepIndex,
                spent = spent,
                providerCallId = providerCallId
            )
        ) { runNow, proposal, descriptor, normalizedArgumentsJson ->
            appendEvent(
                runNow,
                AgentEventTypes.TOOL_PROPOSED,
                AgentToolLifecycle.proposedSummary(
                    codec,
                    descriptor.toolKey,
                    descriptor.sideEffect,
                    normalizedArgumentsJson
                ),
                proposal.invocationId
            )
        }
        return when (prepared) {
            is AgentToolInvocationPipeline.Preparation.Rejected -> {
                val status = prepared.status
                if (prepared.runAfterProposal != null) {
                    markInvocationTerminal(
                        prepared.invocationId,
                        if (status == AgentToolResultStatus.DENIED) {
                            ToolInvocationStatus.DENIED
                        } else {
                            ToolInvocationStatus.NOT_EXECUTED
                        },
                        prepared.possibleSideEffects
                    )
                }
                val observation = toolObservation(
                    status,
                    prepared.safeMessage,
                    errorCode = prepared.errorCode,
                    possibleSideEffects = prepared.possibleSideEffects
                )
                val runNow = prepared.runAfterProposal
                if (runNow != null) {
                    val withEvent = appendEvent(
                        runNow,
                        AgentEventTypes.TOOL_RESULT,
                        AgentToolLifecycle.notExecutedSummary(
                            codec,
                            prepared.toolKey,
                            status,
                            prepared.errorCode
                        ),
                        prepared.invocationId
                    )
                    return AgentToolPreparation.Handled(
                        spent = spent,
                        run = withEvent,
                        invocationId = prepared.invocationId,
                        toolKey = prepared.toolKey,
                        observation = observation
                    )
                }
                return AgentToolPreparation.Handled(
                    spent = spent,
                    run = run,
                    invocationId = prepared.invocationId,
                    toolKey = prepared.toolKey,
                    observation = observation
                )
            }
            is AgentToolInvocationPipeline.Preparation.BudgetExceeded -> AgentToolPreparation.Paused(
                spent = spent,
                run = run,
                reason = prepared.decision.pauseReason,
                error = prepared.decision.error
            )
            is AgentToolInvocationPipeline.Preparation.Unbound -> {
                markInvocationTerminal(
                    prepared.invocationId,
                    ToolInvocationStatus.NOT_EXECUTED,
                    false
                )
                val observation = toolObservation(
                    AgentToolResultStatus.NOT_EXECUTED,
                    "The approval is not bound to the frozen invocation.",
                    errorCode = AgentErrorCodes.INTERNAL_INVARIANT
                )
                val runNow = appendEvent(
                    prepared.run,
                    AgentEventTypes.TOOL_RESULT,
                    AgentToolLifecycle.notExecutedSummary(
                        codec,
                        prepared.command.toolKey,
                        AgentToolResultStatus.NOT_EXECUTED,
                        AgentErrorCodes.INTERNAL_INVARIANT
                    ),
                    prepared.invocationId
                )
                AgentToolPreparation.Handled(
                    spent = spent,
                    run = runNow,
                    invocationId = prepared.invocationId,
                    toolKey = prepared.command.toolKey,
                    observation = observation
                )
            }
            is AgentToolInvocationPipeline.Preparation.Waiting -> {
                val runNow = appendEvent(
                    prepared.run,
                    AgentEventTypes.TOOL_APPROVAL_REQUESTED,
                    AgentToolLifecycle.approvalRequestedSummary(
                        codec,
                        prepared.command,
                        prepared.descriptor,
                        prepared.command.normalizedArgumentsJson
                    ),
                    prepared.invocationId
                )
                AgentToolPreparation.Waiting(
                    run = runNow,
                    invocationId = prepared.invocationId,
                    callHash = callHash,
                    approval = prepared.approval
                )
            }
            is AgentToolInvocationPipeline.Preparation.Ready -> {
                val charged = budgetPolicy.chargeToolInvocation(spent)
                AgentToolPreparation.Ready(
                    spent = charged,
                    run = prepared.run,
                    authorization = AuthorizedToolCommand(
                        command = prepared.command,
                        approvalId = null,
                        basis = AgentToolAuthorizationBasis.WORKSPACE_READ_GRANT
                    ),
                    invocationId = prepared.invocationId,
                    callHash = callHash,
                    toolKey = prepared.command.toolKey
                )
            }
        }
    }

private suspend fun AgentPromptedLoopCoordinator.markInvocationTerminal(
        invocationId: String,
        status: ToolInvocationStatus,
        possibleSideEffects: Boolean
    ) {
        try {
            toolLedger.markTerminal(
                invocationId = invocationId,
                status = status,
                possibleSideEffects = possibleSideEffects,
                resultRef = null,
                atMillis = clock.nowMillis()
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // The durable row is closed best-effort here; the typed observation already fails closed.
        }
    }
