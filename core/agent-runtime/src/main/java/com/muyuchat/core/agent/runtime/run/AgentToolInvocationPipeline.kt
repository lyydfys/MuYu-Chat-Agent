package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.runtime.AgentTranscriptEntry

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.journal.AgentFrozenInvocation
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationLedger
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationRecord
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationUpdate
import com.muyuchat.core.agent.runtime.journal.AgentToolProposal
import com.muyuchat.core.agent.runtime.AgentArgumentValidation
import com.muyuchat.core.agent.runtime.AgentToolSchemaValidator
import kotlinx.coroutines.CancellationException

data class AgentInvocationProposalRequest(
    val run: AgentRun,
    val task: AgentTask,
    val toolName: String,
    val argumentsJson: String,
    val callHash: String,
    val stepIndex: Int,
    val spent: AgentSpentBudget,
    val providerCallId: String? = null,
    /**
     * Stable caller-owned step identity. Model loops leave it null and receive a generated id; a
     * workflow node passes its node-derived step id so a crash/resume re-proposes the same durable
     * invocation instead of minting a second side effect.
     */
    val stepId: String? = null,
    /**
     * The transcript the model already saw, for the repeated-failure guard.
     *
     * Empty by default so every existing caller keeps its behaviour; the model loop passes its own
     * entries, which is where a repeated call actually comes from. A workflow node that proposes the
     * same node twice is not a model retry and must not be blocked by this.
     */
    val entries: List<AgentTranscriptEntry> = emptyList()
)

/**
 * Single proposal/freeze seam for one tool invocation (N03).
 *
 * Every caller (the prompted loop today, workflow/script later) submits the model-authored name and
 * arguments here. The pipeline owns: catalog lookup, schema validation, budget check, the durable
 * PROPOSED row, the caller's `tool.proposed` event callback, and the freeze/decision. It never
 * executes the tool and never appends transcripts; that remains with the caller, so the run journal
 * keeps exactly one writer.
 */
class AgentToolInvocationPipeline(
    private val catalog: AgentToolCatalog,
    private val schemaValidator: AgentToolSchemaValidator,
    private val toolLedger: AgentToolInvocationLedger,
    private val commandHook: AgentFrozenCommandHook,
    private val budgetPolicy: AgentBudgetPolicy,
    private val ids: AgentIdGenerator,
    private val clock: AgentMonotonicClock,
    /** JSON codec used to canonicalise arguments for the repeated-failure guard. */
    private val codec: com.muyuchat.core.agent.runtime.StrictJsonCodec =
        com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec()
) {
    sealed interface Preparation {
        /**
         * No executable command was produced. [runAfterProposal] is non-null only when the PROPOSED
         * event/row were already committed (deny/fail paths), so the caller knows whether it must
         * journal a paired result event.
         */
        data class Rejected(
            val invocationId: String,
            val status: AgentToolResultStatus,
            val errorCode: String,
            val safeMessage: String,
            val toolKey: String,
            val runAfterProposal: AgentRun? = null,
            val possibleSideEffects: Boolean = false
        ) : Preparation

        data class BudgetExceeded(
            val invocationId: String,
            val decision: AgentBudgetDecision.Exceeded
        ) : Preparation

        /** Freeze produced a waiting approval that could not be bound to the frozen row. */
        data class Unbound(
            val invocationId: String,
            val run: AgentRun,
            val command: FrozenToolCommand
        ) : Preparation

        data class Waiting(
            val invocationId: String,
            val run: AgentRun,
            val command: FrozenToolCommand,
            val descriptor: AgentToolDescriptor,
            val approval: AgentApproval
        ) : Preparation

        data class Ready(
            val invocationId: String,
            val run: AgentRun,
            val command: FrozenToolCommand
        ) : Preparation
    }

    suspend fun prepare(
        request: AgentInvocationProposalRequest,
        onProposed: suspend (AgentRun, AgentToolProposal, AgentToolDescriptor, String) -> AgentRun
    ): Preparation {
        val invocationId = ids.nextId("invocation")
        val executor = catalog.find(request.toolName)
            ?: return Preparation.Rejected(
                invocationId = invocationId,
                status = AgentToolResultStatus.NOT_EXECUTED,
                errorCode = AgentErrorCodes.TOOL_NOT_FOUND,
                safeMessage = "The requested tool is not available in this run.",
                toolKey = request.toolName
            )
        val descriptor = executor.descriptor
        val validation = schemaValidator.validateArguments(descriptor, request.argumentsJson)
        if (validation is AgentArgumentValidation.Invalid) {
            return Preparation.Rejected(
                invocationId = invocationId,
                status = AgentToolResultStatus.NOT_EXECUTED,
                errorCode = validation.errorCode,
                safeMessage = validation.safeMessage,
                toolKey = request.toolName
            )
        }
        validation as AgentArgumentValidation.Valid
        val invocationBudget = budgetPolicy.checkNextToolInvocation(request.spent, request.run.budget)
        if (invocationBudget is AgentBudgetDecision.Exceeded) {
            return Preparation.BudgetExceeded(invocationId, invocationBudget)
        }
        // The durable PROPOSED row exists before freeze/decision, so an approval can never reference
        // an invocation that was not persisted first.
        val proposal = AgentToolProposal(
            invocationId = invocationId,
            runId = request.run.runId,
            taskId = request.task.taskId,
            stepId = request.stepId ?: ids.nextId("step"),
            stepIndex = request.stepIndex,
            callOrdinal = 0,
            toolKey = descriptor.toolKey,
            toolVersion = descriptor.version,
            schemaHash = descriptor.schemaHash,
            argumentsHash = validation.argumentsHash,
            proposedAtMillis = clock.nowMillis()
        )
        val recorded = try {
            toolLedger.recordProposed(proposal)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            AgentToolInvocationRecord.Rejected(
                AgentErrorCodes.INTERNAL_UNEXPECTED,
                "The runtime could not record the tool invocation."
            )
        }
        if (recorded !is AgentToolInvocationRecord.Recorded) {
            val rejected = recorded as AgentToolInvocationRecord.Rejected
            return Preparation.Rejected(
                invocationId = invocationId,
                status = AgentToolResultStatus.NOT_EXECUTED,
                errorCode = rejected.errorCode,
                safeMessage = rejected.safeMessage,
                toolKey = request.toolName
            )
        }
        val invocation = recorded.invocation
        val runAfterProposal = onProposed(
            request.run,
            proposal,
            descriptor,
            validation.normalizedArgumentsJson
        )
        val providedScope = (executor as? AgentTypedScopeSource)?.scopePrecondition()
        val frozenRequest = AgentFrozenCommandRequest(
            run = runAfterProposal,
            task = request.task,
            stepId = invocation.stepId,
            invocationId = invocation.invocationId,
            callOrdinal = invocation.callOrdinal,
            descriptor = descriptor,
            normalizedArgumentsJson = validation.normalizedArgumentsJson,
            argumentsHash = validation.argumentsHash,
            callHash = request.callHash,
            providerCallId = request.providerCallId,
            typedScope = providedScope
        )
        // The transcript must be consulted *before* freezing: freezing reserves an invocation id,
        // writes tool.proposed and charges the budget, so a block afterwards would leave a ledger row
        // for a call that never ran.
        if (shouldBlockRepeatedToolCall(
                codec,
                request.entries,
                descriptor.toolKey,
                frozenRequest.callHash,
                validation.normalizedArgumentsJson
            )
        ) {
            // The rejection names the failure and asks for a different approach; recording it as a
            // transcript entry stays the loop's job, because only the loop owns the mutable entries
            // list it appends observations to.
            return Preparation.Rejected(
                invocationId = invocationId,
                status = AgentToolResultStatus.NOT_EXECUTED,
                errorCode = BLOCKED_ERROR_CODE,
                safeMessage = retryAdvice(
                    descriptor.toolKey,
                    lastFailureReasonFor(request.entries, descriptor.toolKey)
                ),
                toolKey = descriptor.toolKey
            )
        }
        val decision = try {
            commandHook.freeze(frozenRequest)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            AgentFrozenCommandDecision.Failed(
                AgentErrorCodes.INTERNAL_UNEXPECTED,
                "The runtime could not freeze the tool command."
            )
        }
        return when (decision) {
            is AgentFrozenCommandDecision.Denied -> Preparation.Rejected(
                invocationId = invocation.invocationId,
                status = AgentToolResultStatus.DENIED,
                errorCode = decision.errorCode,
                safeMessage = decision.safeMessage,
                toolKey = descriptor.toolKey,
                runAfterProposal = runAfterProposal,
                possibleSideEffects = decision.possibleSideEffects
            )
            is AgentFrozenCommandDecision.Failed -> Preparation.Rejected(
                invocationId = invocation.invocationId,
                status = AgentToolResultStatus.FAILED,
                errorCode = decision.errorCode,
                safeMessage = decision.safeMessage,
                toolKey = descriptor.toolKey,
                runAfterProposal = runAfterProposal,
                possibleSideEffects = decision.possibleSideEffects
            )
            is AgentFrozenCommandDecision.WaitingApproval -> {
                val command = decision.command
                val approval = decision.approval
                val frozenRow = toolLedger.readInvocation(invocation.invocationId)
                val bound = approval != null &&
                    approval.invocationId == invocation.invocationId &&
                    approval.argumentsHash == command.argumentsHash &&
                    approval.toolSchemaHash == command.schemaHash &&
                    approval.resourceFingerprint == command.resourceFingerprint &&
                    approval.runEpoch == command.runEpoch &&
                    approval.policyVersion == command.policyVersion &&
                    frozenRow != null &&
                    frozenRow.status == ToolInvocationStatus.VALIDATED &&
                    matchesFrozenCommand(frozenRow, command)
                val waiting = if (bound) {
                    toolLedger.markWaitingApproval(invocation.invocationId, clock.nowMillis())
                } else {
                    null
                }
                val boundApproval = approval
                if (!bound || boundApproval == null || waiting !is AgentToolInvocationUpdate.Updated) {
                    Preparation.Unbound(invocation.invocationId, runAfterProposal, command)
                } else {
                    Preparation.Waiting(
                        invocationId = invocation.invocationId,
                        run = runAfterProposal,
                        command = command,
                        descriptor = descriptor,
                        approval = boundApproval
                    )
                }
            }
            is AgentFrozenCommandDecision.ReadyToExecute -> Preparation.Ready(
                invocationId = invocation.invocationId,
                run = runAfterProposal,
                command = decision.command
            )
        }
    }

    private fun matchesFrozenCommand(
        invocation: com.muyuchat.core.agent.contract.ToolInvocation,
        command: FrozenToolCommand
    ): Boolean =
        invocation.invocationId == command.invocationId &&
            invocation.runId == command.runId &&
            invocation.stepId == command.stepId &&
            invocation.callOrdinal == command.callOrdinal &&
            invocation.toolKey == command.toolKey &&
            invocation.toolVersion == command.toolVersion &&
            invocation.schemaHash == command.schemaHash
}
