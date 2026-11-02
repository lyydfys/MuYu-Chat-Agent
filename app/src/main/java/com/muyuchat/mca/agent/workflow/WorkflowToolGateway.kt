package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.runtime.AgentArgumentValidation
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentToolSchemaValidator
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import kotlinx.coroutines.CancellationException

/** The existing task/workspace identity a workflow tool node executes under. */
data class WorkflowToolScope(
    val taskId: String,
    val workspaceId: String
) {
    init {
        AgentContract.requireId(taskId, "workflow tool taskId")
        require(workspaceId.isNotBlank()) { "workflow tool workspaceId must not be blank" }
    }
}

/**
 * One executor already resolved from the existing run-scoped tool catalog.
 *
 * [execute] is that executor's own method, captured by the composition root; this value carries no
 * engine, loop or scheduler of its own. The workflow package never builds a tool executor.
 */
data class WorkflowToolTarget(
    val descriptor: AgentToolDescriptor,
    val execute: suspend (FrozenToolCommand, AgentToolContext) -> AgentToolResult
)

/**
 * Narrow lookup over the one run-scoped catalog: exactly one registered tool or `null`.
 *
 * The app composition root adapts the real catalog to this single-method port. Keeping the port here
 * (instead of importing the catalog type) proves the workflow package cannot grow a second executor:
 * it can only ask the existing catalog for a descriptor and execute it.
 */
fun interface WorkflowToolRegistry {
    fun find(toolKey: String): WorkflowToolTarget?
}

/** One tool-node invocation request. Arguments are static JSON from the published node config. */
data class WorkflowToolCall(
    val runId: String,
    val nodeId: String,
    val toolKey: String,
    val argumentsJson: String,
    val scope: WorkflowToolScope?,
    /**
     * Run-independent execution frame (task root or a frame key derived from it). Receipts use it
     * instead of [runId] so a successor run of the same task reuses the predecessor result while a
     * foreach/subworkflow frame keeps its own identity.
     */
    val frameKey: String = runId
) {
    init {
        AgentContract.requireId(runId, "workflow tool runId")
        AgentContract.requireId(nodeId, "workflow tool nodeId")
        AgentContract.requireId(frameKey, "workflow tool frameKey")
        require(toolKey.isNotBlank()) { "workflow tool key must not be blank" }
    }
}

/** Terminal answer of the tool gateway; non-success states never become a node success. */
sealed interface WorkflowToolOutcome {
    data class Succeeded(val output: AgentJsonValue) : WorkflowToolOutcome

    data class Failed(val errorCode: String, val safeMessage: String) : WorkflowToolOutcome

    /** The frozen invocation needs the user's one-shot approval; the graph must park, not fail. */
    data class WaitingApproval(
        val invocationId: String,
        val callHash: String,
        val approval: com.muyuchat.core.agent.contract.AgentApproval
    ) : WorkflowToolOutcome
}

/**
 * Narrow seam between the graph node executor and the registered tool registry.
 *
 * It exposes exactly one operation and no scheduler, loop, model adapter or Android surface, so a
 * workflow execution cannot grow a second tool executor.
 */
fun interface WorkflowToolGateway {
    suspend fun invoke(call: WorkflowToolCall): WorkflowToolOutcome
}

/**
 * Production adapter over [WorkflowToolRegistry].
 *
 * - only a registered descriptor may run; a missing key is `WF_TOOL_NOT_REGISTERED`;
 * - only `READ_ONLY` descriptors are allowed here because this execution slice has no approval
 *   surface (`WF_TOOL_POLICY_DENIED`); a write/network tool is never executed silently;
 * - arguments are validated against the descriptor schema by the existing
 *   [SupportedToolSchemaValidator], producing the same normalized JSON/hash the owner pipeline uses;
 * - the pre-existing executor captured in [WorkflowToolTarget] is invoked with a frozen command.
 */
class RegisteredWorkflowToolGateway(
    private val registry: WorkflowToolRegistry,
    private val schemaValidator: AgentToolSchemaValidator = SupportedToolSchemaValidator(),
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) : WorkflowToolGateway {

    private val codec: StrictJsonCodec = StrictAgentJsonCodec()

    override suspend fun invoke(call: WorkflowToolCall): WorkflowToolOutcome {
        val target = registry.find(call.toolKey)
            ?: return failed(
                WorkflowExecutionErrorCodes.TOOL_NOT_REGISTERED,
                "The tool is not registered in the active catalog."
            )
        val descriptor = target.descriptor
        if (descriptor.toolKey != call.toolKey) {
            return failed(
                WorkflowExecutionErrorCodes.TOOL_NOT_REGISTERED,
                "The registered descriptor does not match the requested tool."
            )
        }
        if (descriptor.sideEffect != AgentToolSideEffect.READ_ONLY) {
            return failed(
                WorkflowExecutionErrorCodes.TOOL_POLICY_DENIED,
                "This workflow slice only executes read-only tools; the tool needs the approval surface."
            )
        }
        val scope = call.scope
            ?: return failed(
                WorkflowExecutionErrorCodes.TOOL_CONTEXT_UNAVAILABLE,
                "A tool node needs the existing task and workspace identity."
            )
        val arguments = when (
            val validation = schemaValidator.validateArguments(descriptor, call.argumentsJson)
        ) {
            is AgentArgumentValidation.Valid -> validation
            is AgentArgumentValidation.Invalid -> return failed(
                WorkflowExecutionErrorCodes.TOOL_ARGUMENTS_INVALID,
                validation.safeMessage
            )
        }
        val command = FrozenToolCommand(
            invocationId = "${call.runId}.${call.nodeId}.0",
            runId = call.runId,
            stepId = call.nodeId,
            callOrdinal = 0,
            toolKey = descriptor.toolKey,
            toolVersion = descriptor.version,
            schemaHash = descriptor.schemaHash,
            normalizedArgumentsJson = arguments.normalizedArgumentsJson,
            argumentsHash = arguments.argumentsHash,
            resourceFingerprint = RESOURCE_FINGERPRINT,
            runEpoch = 1L,
            policyVersion = 1L,
            expiresAtMillis = clockMillis() + descriptor.timeoutMillis
        )
        val result = try {
            target.execute(
                command,
                AgentToolContext(
                    taskId = scope.taskId,
                    workspaceId = scope.workspaceId,
                    runId = call.runId,
                    stepIndex = 0,
                    runEpoch = command.runEpoch,
                    policyVersion = command.policyVersion,
                    workspaceReadGranted = true
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return failed(
                WorkflowExecutionErrorCodes.TOOL_FAILED,
                "The tool executor failed unexpectedly."
            )
        }
        return when (result.status) {
            AgentToolResultStatus.SUCCEEDED -> succeeded(result)
            AgentToolResultStatus.DENIED -> failed(
                WorkflowExecutionErrorCodes.TOOL_DENIED,
                result.summary
            )
            AgentToolResultStatus.NOT_EXECUTED -> failed(
                WorkflowExecutionErrorCodes.TOOL_NOT_EXECUTED,
                result.summary
            )
            AgentToolResultStatus.CANCELLED -> failed(
                WorkflowExecutionErrorCodes.TOOL_CANCELLED,
                result.summary
            )
            AgentToolResultStatus.UNKNOWN -> failed(
                WorkflowExecutionErrorCodes.TOOL_UNKNOWN,
                result.summary
            )
            AgentToolResultStatus.FAILED -> failed(
                result.errorCode ?: WorkflowExecutionErrorCodes.TOOL_FAILED,
                result.summary
            )
        }
    }

    private fun succeeded(result: AgentToolResult): WorkflowToolOutcome {
        val dataJson = result.dataJson
        if (dataJson.isNullOrBlank()) {
            return WorkflowToolOutcome.Succeeded(
                AgentJsonValues.obj(
                    "summary" to AgentJsonValues.str(result.summary),
                    "artifactIds" to AgentJsonValues.arr(
                        result.artifactIds.map(AgentJsonValues::str)
                    )
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

    private companion object {
        const val RESOURCE_FINGERPRINT = "workflow"
        const val MAX_MESSAGE_CHARS = 400
    }
}
