package com.muyuchat.mca.agent.workflow
import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.contract.workflow.WorkflowNodeType
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import com.muyuchat.core.agent.runtime.workflow.WorkflowCheckpointLoad
import com.muyuchat.core.agent.runtime.workflow.WorkflowNodeExecution
import com.muyuchat.core.agent.runtime.workflow.WorkflowNodeExecutionRequest
import com.muyuchat.core.agent.runtime.workflow.WorkflowRunCheckpoint
import kotlinx.coroutines.CancellationException

/**
 * Node adapters that compose structure: value shaping, pinned subworkflows and bounded foreach
 * expansion. Child checkpoints are only *read*; the single committed write path stays with the
 * graph runner, and child graphs re-enter the same run loop through [WorkflowSubgraphRunner].
 */
internal class WorkflowCompositionNodeAdapters(
    private val definitions: AgentWorkflowDefinitionStore,
    private val codec: StrictJsonCodec,
    private val openCheckpointStore: WorkflowChildCheckpointOpener,
    private val subgraphs: WorkflowSubgraphRunner
) {
    private fun failed(code: String, message: String): WorkflowNodeExecution.Failed =
        WorkflowNodeExecution.Failed(code, message)

    private companion object {
        const val EXTRACT_SOURCE_INPUT = WorkflowExecutionLimits.EXTRACT_SOURCE_INPUT
        const val EXTRACT_PATH_WHOLE = WorkflowExecutionLimits.EXTRACT_PATH_WHOLE
        const val MAX_FOREACH_ITEMS = WorkflowExecutionLimits.MAX_FOREACH_ITEMS
        const val MAX_FOREACH_OUTPUT_BYTES = WorkflowExecutionLimits.MAX_FOREACH_OUTPUT_BYTES
        const val MAX_SUBWORKFLOW_DEPTH = WorkflowExecutionLimits.MAX_SUBWORKFLOW_DEPTH
        val RUN_ID_PATTERN = WorkflowExecutionLimits.RUN_ID_PATTERN
    }

    internal fun outputNode(request: WorkflowNodeExecutionRequest): WorkflowNodeExecution {
        val inputs = request.inputs
        if (inputs.isEmpty()) {
            return failed(
                WorkflowExecutionErrorCodes.OUTPUT_INPUT_MISSING,
                "An output node needs exactly one committed predecessor value."
            )
        }
        if (inputs.size > 1) {
            return failed(
                WorkflowExecutionErrorCodes.OUTPUT_INPUT_AMBIGUOUS,
                "An output node has more than one active predecessor; this slice has no merge rule."
            )
        }
        return WorkflowNodeExecution.Succeeded(inputs.single())
    }

    internal fun extractNode(request: WorkflowNodeExecutionRequest): WorkflowNodeExecution {
        val fields = workflowConfigFields(codec, request.node.configJson)
            ?: return failed(
                WorkflowExecutionErrorCodes.CONFIG_INVALID,
                "The extract node config is not one JSON object."
            )
        val source = (fields["source"] as? AgentJsonValue.Str)?.value?.takeIf { it.isNotBlank() }
            ?: return failed(
                WorkflowExecutionErrorCodes.CONFIG_INVALID,
                "The extract node has no source."
            )
        val path = (fields["path"] as? AgentJsonValue.Str)?.value?.takeIf { it.isNotBlank() }
            ?: return failed(
                WorkflowExecutionErrorCodes.CONFIG_INVALID,
                "The extract node has no path."
            )
        if (source != EXTRACT_SOURCE_INPUT) {
            return failed(
                WorkflowExecutionErrorCodes.EXTRACT_SOURCE_UNSUPPORTED,
                "Only the single committed predecessor value is a supported extract source."
            )
        }
        if (request.inputs.isEmpty()) {
            return failed(
                WorkflowExecutionErrorCodes.EXTRACT_INPUT_MISSING,
                "An extract node needs exactly one committed predecessor value."
            )
        }
        if (request.inputs.size > 1) {
            return failed(
                WorkflowExecutionErrorCodes.EXTRACT_INPUT_AMBIGUOUS,
                "An extract node has more than one active predecessor; this slice has no merge rule."
            )
        }
        val root = request.inputs.single()
        if (path == EXTRACT_PATH_WHOLE) {
            return WorkflowNodeExecution.Succeeded(root)
        }
        val segments = path.split('.')
        if (segments.any { it.isBlank() || it.length > MAX_PATH_SEGMENT_CHARS } ||
            segments.size > MAX_PATH_SEGMENTS
        ) {
            return failed(
                WorkflowExecutionErrorCodes.EXTRACT_PATH_INVALID,
                "The extract path is empty, too deep or has an over-long segment."
            )
        }
        var current: AgentJsonValue = root
        for (segment in segments) {
            val objectValue = current as? AgentJsonValue.Obj
                ?: return failed(
                    WorkflowExecutionErrorCodes.EXTRACT_PATH_MISSING,
                    "The extract path does not resolve through an object."
                )
            current = objectValue.fields.firstOrNull { it.first == segment }?.second
                ?: return failed(
                    WorkflowExecutionErrorCodes.EXTRACT_PATH_MISSING,
                    "The extract path does not exist in the predecessor value."
                )
        }
        return WorkflowNodeExecution.Succeeded(current)
    }

    internal suspend fun subworkflowNode(
        request: WorkflowNodeExecutionRequest,
        context: WorkflowNodeContext
    ): WorkflowNodeExecution {
        val childDefinitionId = request.node.subworkflowDefinitionId
            ?: return failed(
                WorkflowExecutionErrorCodes.CONFIG_INVALID,
                "A subworkflow node needs a pinned definition id."
            )
        val childVersion = request.node.subworkflowVersion
            ?: return failed(
                WorkflowExecutionErrorCodes.CONFIG_INVALID,
                "A subworkflow node needs a pinned version."
            )
        if (context.depth >= MAX_SUBWORKFLOW_DEPTH) {
            return failed(
                WorkflowExecutionErrorCodes.SUBWORKFLOW_DEPTH,
                "Subworkflow nesting exceeded $MAX_SUBWORKFLOW_DEPTH levels."
            )
        }
        val childDefinition = try {
            definitions.version(childDefinitionId, childVersion)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return failed(
                WorkflowExecutionErrorCodes.STORE_FAILURE,
                "The subworkflow definition store is unavailable."
            )
        } ?: return failed(
            WorkflowExecutionErrorCodes.SUBWORKFLOW_UNRESOLVED,
            "The pinned subworkflow version is not available."
        )
        if (childDefinition.inputs.isNotEmpty()) {
            return failed(
                WorkflowExecutionErrorCodes.SUBWORKFLOW_INPUTS_UNSUPPORTED,
                "This slice does not map inputs into a pinned subworkflow."
            )
        }
        val childRunId = childRunId(context.runId, request.node.nodeId)
        val childFrameKey = childRunId(context.frameKey, request.node.nodeId)
        val childLegacyKey = childRunId(context.legacyFrameKey, request.node.nodeId)
        if (!RUN_ID_PATTERN.matches(childRunId) || !RUN_ID_PATTERN.matches(childFrameKey)) {
            return failed(
                WorkflowExecutionErrorCodes.RUN_ID_INVALID,
                "The derived subworkflow run id is not a safe checkpoint key."
            )
        }
        val childStore = try {
            openCheckpointStore.open(
                key = childFrameKey,
                legacyKey = childLegacyKey.takeIf { it != childFrameKey } ?: childRunId
            )
        } catch (_: Throwable) {
            return failed(
                WorkflowExecutionErrorCodes.CHECKPOINT_STORE_UNAVAILABLE,
                "The subworkflow checkpoint store is unavailable."
            )
        }
        val childCheckpoint = when (val load = childStore.load(childDefinition)) {
            is WorkflowCheckpointLoad.Absent -> null
            is WorkflowCheckpointLoad.Loaded -> load.checkpoint
            is WorkflowCheckpointLoad.Rejected -> return failed(load.errorCode, load.safeMessage)
        }
        val result = subgraphs.executeGraph(
            definition = childDefinition,
            runId = childRunId,
            frameKey = childFrameKey,
            legacyFrameKey = childLegacyKey,
            toolScope = context.toolScope,
            plan = context.plan,
            depth = context.depth + 1,
            budget = context.budget,
            checkpoint = childCheckpoint,
            store = childStore,
            referencedDefinitions = context.referencedDefinitions,
            toolGateway = context.toolGateway,
            state = context.state,
            ownerFrame = context.ownerFrame,
            ownerRun = context.ownerRun,
            ownerTask = context.ownerTask,
            ownerResume = context.ownerResume,
            ownerProgress = context.ownerProgress,
            ownerAgentFrameExecutor = context.agentFrameExecutor,
            userDecision = context.userDecision
        )
        return when (result) {
            is WorkflowExecutionResult.Completed -> WorkflowNodeExecution.Succeeded(
                AgentJsonValues.obj(result.outputs.map { output -> output.key to output.value })
            )

            // A shared-budget exhaustion is a run-level terminal condition, not a child-specific
            // failure: keep the code so callers can trace it to the run budget instead of parsing a
            // wrapper message.
            is WorkflowExecutionResult.Failed -> if (
                result.errorCode == WorkflowExecutionErrorCodes.BUDGET_EXHAUSTED
            ) {
                failed(result.errorCode, result.safeMessage)
            } else {
                failed(
                    WorkflowExecutionErrorCodes.SUBWORKFLOW_FAILED,
                    "The pinned subworkflow failed (${result.errorCode})."
                )
            }

            // The child parked on a user decision: park the parent frame with the child's wait and
            // leave this node uncommitted, so the resume re-enters the same child checkpoint with
            // the answer forwarded through the node context.
            is WorkflowExecutionResult.SuspendedForUser ->
                WorkflowNodeExecution.WaitingUser(result.wait)

            // A child frame parks through the same owner approval; the parent must not turn that
            // into a failure or retry the child while its invocation is still pending.
            is WorkflowExecutionResult.Suspended -> WorkflowNodeExecution.Suspended(result.suspension)

            is WorkflowExecutionResult.Rejected -> failed(result.errorCode, result.safeMessage)
        }
    }

    /**
     * Bounded FOREACH (MCA enhancement, N18/N19).
     *
     * The node iterates a JSON array and executes one pinned child graph per item, sequentially.
     * Each item has a stable child run id derived from `(parent run, node, index)`, so a resumed
     * parent reuses that child's checkpoint and tool receipts instead of re-executing completed
     * items. The parent budget is shared; the item is injected as the child trigger's output. An
     * approval inside a child propagates as a suspension of the parent node.
     */
    internal suspend fun foreachNode(
        request: WorkflowNodeExecutionRequest,
        context: WorkflowNodeContext
    ): WorkflowNodeExecution {
        val fields = workflowConfigFields(codec, request.node.configJson)
            ?: return failed(
                WorkflowExecutionErrorCodes.FOREACH_CONFIG,
                "The foreach node config is not one JSON object."
            )
        val definitionId = (fields["subworkflowDefinitionId"] as? AgentJsonValue.Str)
            ?.value?.takeIf { it.isNotBlank() }
            ?: return failed(
                WorkflowExecutionErrorCodes.FOREACH_CONFIG,
                "The foreach node has no pinned subworkflow definition id."
            )
        val version = (fields["subworkflowVersion"] as? AgentJsonValue.Num)?.literal?.toLongOrNull()
            ?: return failed(
                WorkflowExecutionErrorCodes.FOREACH_CONFIG,
                "The foreach node has no pinned subworkflow version."
            )
        val itemsPath = (fields["itemsPath"] as? AgentJsonValue.Str)
            ?.value?.takeIf { it.isNotBlank() }
            ?: return failed(
                WorkflowExecutionErrorCodes.FOREACH_CONFIG,
                "The foreach node has no itemsPath."
            )
        val maxItems = (fields["maxItems"] as? AgentJsonValue.Num)?.literal?.toIntOrNull()
            ?: MAX_FOREACH_ITEMS
        if (maxItems !in 1..MAX_FOREACH_ITEMS) {
            return failed(
                WorkflowExecutionErrorCodes.FOREACH_CONFIG,
                "The foreach maxItems must be in 1..$MAX_FOREACH_ITEMS."
            )
        }
        val parallel = (fields["parallel"] as? AgentJsonValue.Num)?.literal?.toIntOrNull() ?: 1
        if (parallel != 1) {
            return failed(
                WorkflowExecutionErrorCodes.FOREACH_CONFIG,
                "This slice only supports sequential foreach (parallel=1)."
            )
        }
        if (context.depth >= MAX_SUBWORKFLOW_DEPTH) {
            return failed(
                WorkflowExecutionErrorCodes.FOREACH_DEPTH,
                "Foreach nesting exceeded $MAX_SUBWORKFLOW_DEPTH levels."
            )
        }
        val childDefinition = try {
            definitions.version(definitionId, version)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return failed(
                WorkflowExecutionErrorCodes.STORE_FAILURE,
                "The foreach definition store is unavailable."
            )
        } ?: return failed(
            WorkflowExecutionErrorCodes.SUBWORKFLOW_UNRESOLVED,
            "The pinned foreach child version is not available."
        )
        if (childDefinition.inputs.isNotEmpty()) {
            return failed(
                WorkflowExecutionErrorCodes.FOREACH_CONFIG,
                "A foreach child must declare no external inputs; the item is its trigger output."
            )
        }
        if (request.inputs.isEmpty()) {
            return failed(
                WorkflowExecutionErrorCodes.FOREACH_INPUT_MISSING,
                "A foreach node needs exactly one committed predecessor value."
            )
        }
        if (request.inputs.size > 1) {
            return failed(
                WorkflowExecutionErrorCodes.FOREACH_INPUT_AMBIGUOUS,
                "A foreach node has more than one active predecessor."
            )
        }
        val items = resolveForeachItems(request.inputs.single(), itemsPath)
            ?: return failed(
                WorkflowExecutionErrorCodes.FOREACH_INPUT_MISSING,
                "The foreach itemsPath does not resolve to an array."
            )
        if (items.size > maxItems) {
            return failed(
                WorkflowExecutionErrorCodes.FOREACH_LIMIT,
                "The foreach collection exceeds maxItems=$maxItems."
            )
        }
        if (items.isEmpty()) {
            return WorkflowNodeExecution.Succeeded(AgentJsonValue.Arr(emptyList()))
        }
        val trigger = childDefinition.nodes.singleOrNull { it.type == WorkflowNodeType.TRIGGER }
            ?: return failed(
                WorkflowExecutionErrorCodes.FOREACH_CONFIG,
                "The pinned foreach child has no single trigger node."
            )
        val results = ArrayList<AgentJsonValue>(items.size)
        var totalOutputBytes = 0
        items.forEachIndexed { index, item ->
            val childRunId = foreachChildRunId(context.runId, request.node.nodeId, index)
            val childFrameKey = foreachChildRunId(context.frameKey, request.node.nodeId, index)
            val childLegacyKey = foreachChildRunId(context.legacyFrameKey, request.node.nodeId, index)
            val childStore = try {
                openCheckpointStore.open(
                    key = childFrameKey,
                    legacyKey = childLegacyKey.takeIf { it != childFrameKey } ?: childRunId
                )
            } catch (_: Throwable) {
                return failed(
                    WorkflowExecutionErrorCodes.CHECKPOINT_STORE_UNAVAILABLE,
                    "The foreach item checkpoint store is unavailable."
                )
            }
            val childCheckpoint = when (val loaded = childStore.load(childDefinition)) {
                is WorkflowCheckpointLoad.Absent -> initialForeachCheckpoint(childDefinition, trigger, item)
                is WorkflowCheckpointLoad.Loaded -> loaded.checkpoint
                is WorkflowCheckpointLoad.Rejected -> return failed(loaded.errorCode, loaded.safeMessage)
            }
            val childResult = subgraphs.executeGraph(
                definition = childDefinition,
                runId = childRunId,
                frameKey = childFrameKey,
                legacyFrameKey = childLegacyKey,
                toolScope = context.toolScope,
                plan = context.plan,
                depth = context.depth + 1,
                budget = context.budget,
                checkpoint = childCheckpoint,
                store = childStore,
                referencedDefinitions = context.referencedDefinitions,
                toolGateway = context.toolGateway,
                state = context.state,
                ownerFrame = context.ownerFrame,
                ownerRun = context.ownerRun,
                ownerTask = context.ownerTask,
                ownerResume = context.ownerResume,
                ownerProgress = context.ownerProgress,
                ownerAgentFrameExecutor = context.agentFrameExecutor,
                userDecision = context.userDecision
            )
            when (childResult) {
                is WorkflowExecutionResult.Completed -> {
                    val itemValue = childValue(childDefinition, childResult.outputs)
                    totalOutputBytes += codec.write(itemValue).toByteArray(Charsets.UTF_8).size
                    if (totalOutputBytes > MAX_FOREACH_OUTPUT_BYTES) {
                        return failed(
                            WorkflowExecutionErrorCodes.FOREACH_LIMIT,
                            "The foreach aggregated output exceeds its byte limit."
                        )
                    }
                    results += itemValue
                }
                // Same rule as SUBWORKFLOW: an exhausted shared budget stays visible as itself.
                is WorkflowExecutionResult.Failed -> return if (
                    childResult.errorCode == WorkflowExecutionErrorCodes.BUDGET_EXHAUSTED
                ) {
                    failed(childResult.errorCode, childResult.safeMessage)
                } else {
                    failed(
                        WorkflowExecutionErrorCodes.FOREACH_ITEM_FAILED,
                        "Foreach item $index failed (${childResult.errorCode})."
                    )
                }
                is WorkflowExecutionResult.Suspended ->
                    return WorkflowNodeExecution.Suspended(childResult.suspension)
                // The item parked: the whole foreach node parks, and the resume re-enters from the
                // committed per-item checkpoints, so completed items never re-run.
                is WorkflowExecutionResult.SuspendedForUser ->
                    return WorkflowNodeExecution.WaitingUser(childResult.wait)
                is WorkflowExecutionResult.Rejected ->
                    return failed(childResult.errorCode, childResult.safeMessage)
            }
        }
        return WorkflowNodeExecution.Succeeded(AgentJsonValue.Arr(results))
    }

    private fun childValue(
        childDefinition: WorkflowDefinition,
        outputs: Map<String, AgentJsonValue>
    ): AgentJsonValue {
        if (childDefinition.outputs.size == 1) {
            return outputs[childDefinition.outputs.single().name] ?: AgentJsonValue.Null
        }
        return AgentJsonValues.obj(
            outputs.entries.sortedBy { it.key }.map { it.key to it.value }
        )
    }

    private fun resolveForeachItems(root: AgentJsonValue, path: String): List<AgentJsonValue>? =
        resolveForeachPathItems(root, path)

    private fun initialForeachCheckpoint(
        childDefinition: WorkflowDefinition,
        trigger: com.muyuchat.core.agent.contract.workflow.WorkflowNode,
        item: AgentJsonValue
    ): WorkflowRunCheckpoint = newForeachCheckpoint(childDefinition, trigger, item)

    private fun foreachChildRunId(parentRunId: String, nodeId: String, index: Int): String =
        deriveForeachChildRunId(parentRunId, nodeId, index)

    private fun childRunId(parentRunId: String, nodeId: String): String =
        deriveChildRunId(parentRunId, nodeId)
}
