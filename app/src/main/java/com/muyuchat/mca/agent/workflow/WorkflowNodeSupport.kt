package com.muyuchat.mca.agent.workflow
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.run.AgentLoopResume
import com.muyuchat.mca.agent.tools.WorkflowToolRunState
import com.muyuchat.core.agent.runtime.workflow.WorkflowCheckpointSink
import com.muyuchat.core.agent.runtime.workflow.WorkflowDefinitionValidator
import com.muyuchat.core.agent.runtime.workflow.WorkflowRunCheckpoint

/** Bounded limits shared by the run loop and the node adapters (values unchanged). */
internal object WorkflowExecutionLimits {
        /** Same safe-key rule as [FileWorkflowCheckpointStore]. */
        val RUN_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

        /** Same pinned nesting cap the static validator enforces. */
        const val MAX_SUBWORKFLOW_DEPTH = 8

        /** Hard cap on resolved pinned definitions per run, independent of the depth cap. */
        const val MAX_REFERENCED_DEFINITIONS = 64

        const val EXTRACT_SOURCE_INPUT = "input"
        const val EXTRACT_PATH_WHOLE = "\$"
        const val MAX_AGENT_TASK_TITLE_CHARS = 200
        const val MAX_FOREACH_ITEMS = 100
        const val MAX_FOREACH_OUTPUT_BYTES = 64 * 1024
        const val DEFAULT_MODEL_OUTPUT_TOKENS = 512
        const val MAX_MODEL_OUTPUT_TOKENS = 8192
        const val MAX_MODEL_INPUT_BYTES = 12 * 1024
        const val MAX_MODEL_OUTPUT_BYTES = 16 * 1024
        const val WORKFLOW_MODEL_SYSTEM_PROMPT =
            "You are a workflow model node. Answer the user prompt using the provided input JSON. " +
                "Return plain text only; do not emit tool calls."
}

/**
 * The single run loop as seen by child-graph nodes: a pinned subworkflow or a foreach child
 * re-enters this executor with the same budget, depth counter and checkpoint sink.
 */
internal interface WorkflowSubgraphRunner {
    suspend fun executeGraph(
        definition: WorkflowDefinition,
        runId: String,
        frameKey: String,
        legacyFrameKey: String,
        toolScope: WorkflowToolScope?,
        plan: WorkflowAgentTaskPlan?,
        depth: Int,
        budget: WorkflowRunBudget,
        checkpoint: WorkflowRunCheckpoint?,
        store: WorkflowCheckpointSink,
        referencedDefinitions: Map<WorkflowDefinitionValidator.SubworkflowRef, WorkflowDefinition>,
        toolGateway: WorkflowToolGateway,
        state: WorkflowToolRunState? = null,
        ownerFrame: Boolean = false,
        ownerRun: AgentRun? = null,
        ownerTask: AgentTask? = null,
        ownerResume: AgentLoopResume? = null,
        ownerProgress: AgentModelProgressSink? = null,
        ownerAgentFrameExecutor: WorkflowAgentFrameExecutor? = null,
        userDecision: String? = null
    ): WorkflowExecutionResult
}

/** Opens the checkpoint store of a child run; the service owns the backend choice. */
internal interface WorkflowChildCheckpointOpener {
    fun open(key: String, legacyKey: String? = null): WorkflowCheckpointStore
}

    internal data class WorkflowNodeContext(
        val runId: String,
        /** Stable root key for checkpoints that must survive a successor run of the same task. */
        val frameKey: String,
        val legacyFrameKey: String,
        val toolScope: WorkflowToolScope?,
        val plan: WorkflowAgentTaskPlan?,
        val depth: Int,
        val budget: WorkflowRunBudget,
        val referencedDefinitions:
            Map<WorkflowDefinitionValidator.SubworkflowRef, WorkflowDefinition>,
        val toolGateway: WorkflowToolGateway,
        /** Present only for an owner-anchored run; the legacy direct path has no run budget. */
        val state: WorkflowToolRunState?,
        /** True only for [executeFrame]; a prompt-only AGENT_TASK may use the same-root loop. */
        val ownerFrame: Boolean = false,
        val ownerRun: AgentRun? = null,
        val ownerTask: AgentTask? = null,
        val ownerResume: AgentLoopResume? = null,
        val ownerProgress: AgentModelProgressSink? = null,
        val agentFrameExecutor: WorkflowAgentFrameExecutor? = null,
        /** Durable answer for a nested wait; composite nodes forward it to their child frame. */
        val userDecision: String? = null
    )

    internal fun workflowConfigFields(codec: StrictJsonCodec, configJson: String): Map<String, AgentJsonValue>? {
        val parsed = codec.parse(configJson) as? AgentJsonParseResult.Parsed ?: return null
        return (parsed.value as? AgentJsonValue.Obj)?.fields?.toMap()
    }
