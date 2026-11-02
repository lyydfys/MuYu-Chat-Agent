package com.muyuchat.mca.agent.workflow
import com.muyuchat.core.agent.contract.AgentModelAttemptRef
import com.muyuchat.core.agent.contract.AgentModelMessage
import com.muyuchat.core.agent.contract.AgentModelPort
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentModelRequest
import com.muyuchat.core.agent.contract.AgentModelResult
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentTranscriptRole
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import com.muyuchat.core.agent.runtime.workflow.WorkflowNodeSuspension
import com.muyuchat.mca.agent.memory.AgentMemoryLimits
import com.muyuchat.core.agent.runtime.workflow.WorkflowAgentTaskOutcome
import com.muyuchat.core.agent.runtime.workflow.WorkflowAgentTaskRequest
import com.muyuchat.core.agent.runtime.workflow.WorkflowAgentTaskRunner
import com.muyuchat.core.agent.runtime.workflow.WorkflowNodeExecution
import com.muyuchat.core.agent.runtime.workflow.WorkflowNodeExecutionRequest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Node adapters that hand one frozen node request to an external owner: the shared tool gateway,
 * the Rhino script runtime, the Agent model loop, or an Agent task/frame run.
 *
 * They never write checkpoint state or journal events; the graph runner commits every state change
 * through the single run loop in [WorkflowExecutionService].
 */
internal class WorkflowExternalNodeAdapters(
    private val modelPort: AgentModelPort?,
    agentFrameExecutor: WorkflowAgentFrameExecutor?,
    private val agentTaskRunner: WorkflowAgentTaskRunner,
    private val codec: StrictJsonCodec
) {
    private fun failed(code: String, message: String): WorkflowNodeExecution.Failed =
        WorkflowNodeExecution.Failed(code, message)

    private companion object {
        const val DEFAULT_MODEL_OUTPUT_TOKENS = WorkflowExecutionLimits.DEFAULT_MODEL_OUTPUT_TOKENS
        const val MAX_MODEL_OUTPUT_TOKENS = WorkflowExecutionLimits.MAX_MODEL_OUTPUT_TOKENS
        const val MAX_MODEL_INPUT_BYTES = WorkflowExecutionLimits.MAX_MODEL_INPUT_BYTES
        const val MAX_MODEL_OUTPUT_BYTES = WorkflowExecutionLimits.MAX_MODEL_OUTPUT_BYTES
        const val WORKFLOW_MODEL_SYSTEM_PROMPT = WorkflowExecutionLimits.WORKFLOW_MODEL_SYSTEM_PROMPT
    }

    internal suspend fun toolNode(
        request: WorkflowNodeExecutionRequest,
        context: WorkflowNodeContext
    ): WorkflowNodeExecution {
        val fields = workflowConfigFields(codec, request.node.configJson)
            ?: return failed(
                WorkflowExecutionErrorCodes.CONFIG_INVALID,
                "The tool node config is not one JSON object."
            )
        val toolKey = (fields["toolKey"] as? AgentJsonValue.Str)?.value?.takeIf { it.isNotBlank() }
            ?: return failed(
                WorkflowExecutionErrorCodes.CONFIG_INVALID,
                "The tool node has no toolKey."
            )
        val argumentsJson = toolArguments(fields)
            ?: return failed(
                WorkflowExecutionErrorCodes.TOOL_ARGUMENTS_INVALID,
                "Tool node arguments must be one JSON object."
            )
        return invokeToolNode(request.node.nodeId, toolKey, argumentsJson, context)
    }

    private fun toolArguments(fields: Map<String, AgentJsonValue>): String? =
        when (val arguments = fields["arguments"]) {
            null -> "{}"
            is AgentJsonValue.Obj -> codec.write(AgentJsonValues.canonical(arguments))
            else -> null
        }

    private suspend fun invokeToolNode(
        nodeId: String,
        toolKey: String,
        argumentsJson: String,
        context: WorkflowNodeContext
    ): WorkflowNodeExecution {
        if (!context.budget.consumeToolCall()) {
            return failed(
                WorkflowExecutionErrorCodes.BUDGET_EXHAUSTED,
                "The workflow run has no tool-call budget left."
            )
        }
        val invocation = try {
            context.toolGateway.invoke(
                WorkflowToolCall(
                    runId = context.runId,
                    nodeId = nodeId,
                    toolKey = toolKey,
                    argumentsJson = argumentsJson,
                    scope = context.toolScope,
                    frameKey = context.frameKey
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return failed(
                WorkflowExecutionErrorCodes.TOOL_FAILED,
                "The tool gateway failed unexpectedly."
            )
        }
        return when (invocation) {
            is WorkflowToolOutcome.Succeeded -> WorkflowNodeExecution.Succeeded(invocation.output)
            is WorkflowToolOutcome.Failed -> failed(invocation.errorCode, invocation.safeMessage)
            is WorkflowToolOutcome.WaitingApproval -> WorkflowNodeExecution.Suspended(
                WorkflowNodeSuspension(
                    invocationId = invocation.invocationId,
                    callHash = invocation.callHash,
                    approval = invocation.approval
                )
            )
        }
    }

    /**
     * SCRIPT node (N18): a reference to an installed package/host tool export, executed through the
     * same tool gateway/pipeline as a TOOL node.
     *
     * It never creates a script engine, process, raw path or executor here. The referenced key must
     * be present in the run-scoped catalog (for example a tool package export); if it is absent the
     * node fails closed. Strong isolation and raw inline script sources are NOT provided by this
     * slice and remain explicit gaps.
     */
    internal suspend fun scriptNode(
        request: WorkflowNodeExecutionRequest,
        context: WorkflowNodeContext
    ): WorkflowNodeExecution {
        val fields = workflowConfigFields(codec, request.node.configJson)
            ?: return failed(
                WorkflowExecutionErrorCodes.CONFIG_INVALID,
                "The script node config is not one JSON object."
            )
        val scriptRef = (fields["scriptRef"] as? AgentJsonValue.Str)
            ?.value?.takeIf { it.isNotBlank() }
            ?: return failed(
                WorkflowExecutionErrorCodes.CONFIG_INVALID,
                "The script node has no scriptRef."
            )
        val argumentsJson = toolArguments(fields)
            ?: return failed(
                WorkflowExecutionErrorCodes.TOOL_ARGUMENTS_INVALID,
                "Script node arguments must be one JSON object."
            )
        return invokeToolNode(request.node.nodeId, scriptRef, argumentsJson, context)
    }

    /**
     * One bounded completion through the same [AgentModelPort] the Agent loop uses.
     *
     * The port owns the real engine/facade identity, lease and stop proof. The node supplies the
     * frozen run binding, one explicit prompt plus the active predecessor JSON, an output-token
     * limit and an attempt identity; tools are deliberately empty (tool use belongs to TOOL /
     * AGENT_TASK nodes). The run's model-attempt/token budget and the shared workflow budget are
     * charged before/after the attempt; cancellation calls the port's exact stop.
     */
    internal suspend fun modelNode(
        request: WorkflowNodeExecutionRequest,
        context: WorkflowNodeContext
    ): WorkflowNodeExecution {
        val fields = workflowConfigFields(codec, request.node.configJson)
            ?: return failed(
                WorkflowExecutionErrorCodes.MODEL_CONFIG,
                "The model node config is not one JSON object."
            )
        val prompt = (fields["prompt"] as? AgentJsonValue.Str)
            ?.value?.takeIf { it.isNotBlank() }
            ?: return failed(
                WorkflowExecutionErrorCodes.MODEL_CONFIG,
                "The model node has no prompt."
            )
        val maxOutputTokens = (fields["maxOutputTokens"] as? AgentJsonValue.Num)
            ?.literal?.toIntOrNull() ?: DEFAULT_MODEL_OUTPUT_TOKENS
        if (maxOutputTokens !in 1..MAX_MODEL_OUTPUT_TOKENS) {
            return failed(
                WorkflowExecutionErrorCodes.MODEL_CONFIG,
                "The model node maxOutputTokens is out of range."
            )
        }
        val state = context.state
            ?: return failed(
                WorkflowExecutionErrorCodes.NODE_NOT_SUPPORTED,
                "A model node needs an owner-anchored run budget context."
            )
        val port = modelPort
            ?: return failed(
                WorkflowExecutionErrorCodes.NODE_NOT_SUPPORTED,
                "No model port is wired for workflow execution."
            )
        val binding = context.plan?.modelBinding ?: state.run.modelBinding
        if (binding.protocolMode == AgentProtocolMode.NONE) {
            return failed(
                WorkflowExecutionErrorCodes.MODEL_BINDING_MISSING,
                "The workflow run has no runnable model binding."
            )
        }
        if (state.run.spentBudget.modelAttempts >= state.run.budget.modelAttempts) {
            return failed(
                WorkflowExecutionErrorCodes.MODEL_BUDGET,
                "The run has no model-attempt budget left."
            )
        }
        if (!context.budget.consumeModelCall()) {
            return failed(
                WorkflowExecutionErrorCodes.BUDGET_EXHAUSTED,
                "The workflow run has no model-call budget left."
            )
        }
        val inputJson = if (request.inputs.isEmpty()) {
            ""
        } else {
            codec.write(
                AgentJsonValues.obj(
                    "inputs" to AgentJsonValue.Arr(request.inputs)
                )
            )
        }
        val userContent = if (inputJson.isEmpty()) prompt else "$prompt\n\nInput:\n$inputJson"
        if (userContent.toByteArray(Charsets.UTF_8).size > MAX_MODEL_INPUT_BYTES) {
            return failed(
                WorkflowExecutionErrorCodes.MODEL_LIMIT,
                "The model node prompt/input exceeds its byte limit."
            )
        }
        val attempt = AgentModelAttemptRef(
            attemptId = "wfmodel-" + UUID.randomUUID().toString(),
            runId = state.run.runId,
            stepIndex = 0,
            ordinal = state.run.spentBudget.modelAttempts + 1
        )
        val modelRequest = AgentModelRequest(
            attempt = attempt,
            binding = binding,
            systemPrompt = WORKFLOW_MODEL_SYSTEM_PROMPT,
            messages = listOf(
                AgentModelMessage(
                    messageId = "wfmodel-msg-" + UUID.randomUUID().toString(),
                    role = AgentTranscriptRole.USER,
                    content = userContent
                )
            ),
            maxOutputTokens = maxOutputTokens,
            samplingJson = binding.samplingJson,
            callOrdinal = 0,
            tools = emptyList()
        )
        val result = try {
            port.infer(modelRequest, AgentModelProgressSink { })
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { port.requestStop(attempt) }
            }
            throw cancelled
        } catch (_: Throwable) {
            return failed(
                WorkflowExecutionErrorCodes.MODEL_FAILED,
                "The model port failed unexpectedly."
            )
        }
        val outputTokens = when (result) {
            is AgentModelResult.Success -> result.usage?.outputTokens ?: estimatedTokens(result.visibleText)
            else -> 0
        }
        val spent = state.run.spentBudget.copy(
            modelAttempts = state.run.spentBudget.modelAttempts + 1,
            generatedTokens = state.run.spentBudget.generatedTokens + outputTokens,
            estimated = state.run.spentBudget.estimated &&
                (result !is AgentModelResult.Success || (result.usage?.estimated ?: true))
        )
        state.updateRun(state.run.copy(spentBudget = spent))
        if (spent.modelAttempts > state.run.budget.modelAttempts ||
            spent.generatedTokens > state.run.budget.totalGeneratedTokens
        ) {
            return failed(
                WorkflowExecutionErrorCodes.MODEL_BUDGET,
                "The run model budget was exceeded by this attempt."
            )
        }
        if (!context.budget.chargeModelTokens(outputTokens)) {
            return failed(
                WorkflowExecutionErrorCodes.BUDGET_EXHAUSTED,
                "The workflow token budget is exhausted."
            )
        }
        return when (result) {
            is AgentModelResult.Success -> {
                if (result.visibleText.toByteArray(Charsets.UTF_8).size > MAX_MODEL_OUTPUT_BYTES) {
                    failed(
                        WorkflowExecutionErrorCodes.MODEL_LIMIT,
                        "The model node output exceeds its byte limit."
                    )
                } else {
                    WorkflowNodeExecution.Succeeded(AgentJsonValue.Str(result.visibleText))
                }
            }
            is AgentModelResult.Failure -> failed(
                WorkflowExecutionErrorCodes.MODEL_FAILED,
                "The model attempt failed (${result.error.code})."
            )
            is AgentModelResult.Cancelled -> failed(
                WorkflowExecutionErrorCodes.MODEL_CANCELLED,
                "The model attempt was cancelled."
            )
        }
    }

    private fun estimatedTokens(text: String): Int =
        (text.toByteArray(Charsets.UTF_8).size + 2) / 3 + 1

    internal suspend fun agentTaskNode(
        request: WorkflowNodeExecutionRequest,
        context: WorkflowNodeContext
    ): WorkflowNodeExecution {
        val fields = workflowConfigFields(codec, request.node.configJson)
            ?: return failed(
                WorkflowExecutionErrorCodes.CONFIG_INVALID,
                "The agent-task node config is not one JSON object."
            )
        val goal = (fields["goal"] as? AgentJsonValue.Str)?.value?.takeIf { it.isNotBlank() }
            ?: return failed(
                WorkflowExecutionErrorCodes.CONFIG_INVALID,
                "The agent-task node has no goal template."
            )
        val plan = context.plan
            ?: return failed(
                WorkflowExecutionErrorCodes.NODE_NOT_SUPPORTED,
                "An agent-task node needs the owner command adapter and an explicit model plan."
            )
        val nodeMemory = parseAgentTaskMemorySpaceIds(fields["memorySpaceIds"])
            ?: return failed(
                WorkflowExecutionErrorCodes.CONFIG_INVALID,
                "The agent-task memorySpaceIds must be 1-${AgentMemoryLimits.MAX_SELECTED_SPACES} " +
                    "unique bounded ids."
            )
        val planMemory = canonicalMemorySpaceIds(plan.policy.memorySpaceIds)
        val policy = when {
            nodeMemory.isEmpty() -> plan.policy
            planMemory.isEmpty() -> plan.policy.copy(memorySpaceIds = nodeMemory)
            planMemory == nodeMemory -> plan.policy
            else -> return failed(
                WorkflowExecutionErrorCodes.CONFIG_INVALID,
                "The agent-task node and the run plan declare different memory spaces."
            )
        }
        if (context.ownerFrame) {
            return agentFrameNode.execute(
                run = context.ownerRun,
                task = context.ownerTask,
                state = context.state,
                progress = context.ownerProgress,
                goal = goal,
                policy = policy
            )
        }
        val childBudget = context.budget.childAgentBudget()
            ?: return failed(
                WorkflowExecutionErrorCodes.BUDGET_EXHAUSTED,
                "The workflow run has no model/token/time budget left for a child agent task."
            )
        val outcome = try {
            agentTaskRunner.run(
                WorkflowAgentTaskRequest(
                    workflowRunId = context.runId,
                    nodeId = request.node.nodeId,
                    goal = goal,
                    modelBinding = plan.modelBinding,
                    policy = policy,
                    budget = childBudget,
                    displayTitle = plan.displayTitle,
                    waitTimeoutMillis = context.budget.remainingDurationMillis()
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return failed(
                WorkflowExecutionErrorCodes.AGENT_TASK_FAILED,
                "The child agent task adapter failed unexpectedly."
            )
        }
        return when (outcome) {
            is WorkflowAgentTaskOutcome.Succeeded -> {
                if (context.budget.charge(outcome.spent)) {
                    WorkflowNodeExecution.Succeeded(outcome.output)
                } else {
                    failed(
                        WorkflowExecutionErrorCodes.BUDGET_EXHAUSTED,
                        "The child agent task exceeded the shared workflow budget."
                    )
                }
            }

            is WorkflowAgentTaskOutcome.Failed -> {
                context.budget.charge(outcome.spent)
                failed(outcome.errorCode, outcome.safeMessage)
            }

            is WorkflowAgentTaskOutcome.NotSupported -> failed(
                WorkflowExecutionErrorCodes.NODE_NOT_SUPPORTED,
                outcome.safeMessage
            )
        }
    }

    /**
     * Optional AGENT_TASK memory selection. Absent/null/empty means "inherit the run plan".
     * A present array must contain 1..16 unique, bounded, control-free space ids.
     */
    private fun parseAgentTaskMemorySpaceIds(value: AgentJsonValue?): Set<String>? =
        parseAgentTaskMemorySpaceSelection(value)

    private fun canonicalMemorySpaceIds(ids: Set<String>): Set<String> =
        ids.map(String::trim).filter(String::isNotEmpty).toSet()

    private val agentFrameNode = WorkflowAgentFrameNode(agentFrameExecutor)
}
