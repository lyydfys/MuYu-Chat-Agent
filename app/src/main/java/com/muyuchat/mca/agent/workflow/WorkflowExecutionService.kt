package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentModelPort
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.contract.workflow.WorkflowNodeType
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import com.muyuchat.core.agent.runtime.run.AgentLoopResume
import com.muyuchat.core.agent.runtime.workflow.WorkflowNodeSuspension
import com.muyuchat.mca.agent.tools.WorkflowToolGatewayFactory
import com.muyuchat.mca.agent.tools.WorkflowToolRunState
import com.muyuchat.core.agent.runtime.workflow.NoWorkflowAgentTaskRunner
import com.muyuchat.core.agent.runtime.workflow.WorkflowAgentTaskRunner
import com.muyuchat.core.agent.runtime.workflow.WorkflowCheckpointLoad
import com.muyuchat.core.agent.runtime.workflow.WorkflowCheckpointSink
import com.muyuchat.core.agent.runtime.workflow.WorkflowDefinitionValidator
import com.muyuchat.core.agent.runtime.workflow.WorkflowGraphResult
import com.muyuchat.core.agent.runtime.workflow.WorkflowGraphRunner
import com.muyuchat.core.agent.runtime.workflow.WorkflowNodeExecution
import com.muyuchat.core.agent.runtime.workflow.WorkflowNodeExecutionRequest
import com.muyuchat.core.agent.runtime.workflow.WorkflowNodeExecutor
import com.muyuchat.core.agent.runtime.workflow.WorkflowNodeOutcome
import com.muyuchat.core.agent.runtime.workflow.WorkflowUserWait
import com.muyuchat.core.agent.runtime.workflow.WorkflowRunCheckpoint
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Stable execution codes of the graph execution service. */
object WorkflowExecutionErrorCodes {
    const val VERSION_NOT_FOUND = "WF_VERSION_NOT_FOUND"
    const val RUN_ID_INVALID = "WF_RUN_ID_INVALID"
    const val STORE_FAILURE = "WF_STORE_FAILURE"
    const val CHECKPOINT_STORE_UNAVAILABLE = "WF_CHECKPOINT_STORE_UNAVAILABLE"
    const val EXECUTION_FAILED = "WF_EXECUTION_FAILED"
    const val POLICY_UNSUPPORTED = "WF_POLICY_UNSUPPORTED"
    const val WAIT_NOT_SUPPORTED = "WF_WAIT_NOT_SUPPORTED"
    const val FRAME_NOT_SUPPORTED = "WF_FRAME_NOT_SUPPORTED"
    const val NODE_NOT_SUPPORTED = "WF_NODE_NOT_SUPPORTED"
    const val NODE_UNEXPECTED = "WF_NODE_UNEXPECTED"
    const val OUTPUT_INPUT_MISSING = "WF_OUTPUT_INPUT_MISSING"
    const val OUTPUT_INPUT_AMBIGUOUS = "WF_OUTPUT_INPUT_AMBIGUOUS"
    const val CONFIG_INVALID = "WF_CONFIG_INVALID"
    const val TOOL_NOT_REGISTERED = "WF_TOOL_NOT_REGISTERED"
    const val TOOL_POLICY_DENIED = "WF_TOOL_POLICY_DENIED"
    const val TOOL_APPROVAL_MISMATCH = "WF_TOOL_APPROVAL_MISMATCH"
    const val TOOL_RECEIPT_CORRUPT = "WF_TOOL_RECEIPT_CORRUPT"
    const val TOOL_RECEIPT_MISMATCH = "WF_TOOL_RECEIPT_MISMATCH"
    const val TOOL_CONTEXT_UNAVAILABLE = "WF_TOOL_CONTEXT_UNAVAILABLE"
    const val TOOL_ARGUMENTS_INVALID = "WF_TOOL_ARGUMENTS_INVALID"
    const val TOOL_RESULT_INVALID = "WF_TOOL_RESULT_INVALID"
    const val TOOL_FAILED = "WF_TOOL_FAILED"
    const val TOOL_DENIED = "WF_TOOL_DENIED"
    const val TOOL_NOT_EXECUTED = "WF_TOOL_NOT_EXECUTED"
    const val TOOL_CANCELLED = "WF_TOOL_CANCELLED"
    const val TOOL_UNKNOWN = "WF_TOOL_UNKNOWN"
    const val BUDGET_EXHAUSTED = "WF_BUDGET_EXHAUSTED"
    const val AGENT_TASK_FAILED = "WF_AGENT_TASK_FAILED"
    const val SUBWORKFLOW_UNRESOLVED = "WF_SUBWORKFLOW_UNRESOLVED"
    const val SUBWORKFLOW_DEPTH = "WF_SUBWORKFLOW_DEPTH"
    const val SUBWORKFLOW_INPUTS_UNSUPPORTED = "WF_SUBWORKFLOW_INPUTS_UNSUPPORTED"
    const val SUBWORKFLOW_FAILED = "WF_SUBWORKFLOW_FAILED"
    const val FOREACH_CONFIG = "WF_FOREACH_CONFIG"
    const val FOREACH_INPUT_MISSING = "WF_FOREACH_INPUT_MISSING"
    const val FOREACH_INPUT_AMBIGUOUS = "WF_FOREACH_INPUT_AMBIGUOUS"
    const val FOREACH_LIMIT = "WF_FOREACH_LIMIT"
    const val FOREACH_ITEM_FAILED = "WF_FOREACH_ITEM_FAILED"
    const val FOREACH_DEPTH = "WF_FOREACH_DEPTH"
    const val MODEL_BINDING_MISSING = "WF_MODEL_BINDING_MISSING"
    const val MODEL_BUDGET = "WF_MODEL_BUDGET"
    const val MODEL_FAILED = "WF_MODEL_FAILED"
    const val MODEL_CANCELLED = "WF_MODEL_CANCELLED"
    const val MODEL_LIMIT = "WF_MODEL_LIMIT"
    const val MODEL_CONFIG = "WF_MODEL_CONFIG"
    const val EXTRACT_SOURCE_UNSUPPORTED = "WF_EXTRACT_SOURCE_UNSUPPORTED"
    const val EXTRACT_INPUT_MISSING = "WF_EXTRACT_INPUT_MISSING"
    const val EXTRACT_INPUT_AMBIGUOUS = "WF_EXTRACT_INPUT_AMBIGUOUS"
    const val EXTRACT_PATH_INVALID = "WF_EXTRACT_PATH_INVALID"
    const val EXTRACT_PATH_MISSING = "WF_EXTRACT_PATH_MISSING"
}

/**
 * One explicit execution request.
 *
 * [version] is mandatory: a run always reads that immutable published version and never follows the
 * latest draft/version. [runId] is optional: a new id is generated for a fresh run, while an existing
 * id resumes from its checkpoint. [toolScope] is the existing task/workspace identity a tool node
 * executes under; without it a tool node fails closed instead of inventing an identity. [agentTaskPlan]
 * is the frozen, credential-free profile an `AGENT_TASK` node starts its child task with; null keeps
 * the typed NOT_SUPPORTED answer.
 */
data class WorkflowExecutionRequest(
    val definitionId: String,
    val version: Long,
    val runId: String? = null,
    val toolScope: WorkflowToolScope? = null,
    val agentTaskPlan: WorkflowAgentTaskPlan? = null,
    /** Durable answer for a parked wait; only the run-owned path supplies this in production. */
    val userDecision: String? = null
)

/** Terminal execution result. Node facts are the runner's per-node attempt terminal states. */
sealed interface WorkflowExecutionResult {
    val runId: String?

    data class Completed(
        override val runId: String,
        val outputs: Map<String, AgentJsonValue>,
        val outcomes: List<WorkflowNodeOutcome>
    ) : WorkflowExecutionResult

    data class Failed(
        override val runId: String,
        val errorCode: String,
        val safeMessage: String,
        val outcomes: List<WorkflowNodeOutcome>
    ) : WorkflowExecutionResult

    /** Refused before/while starting; [runId] is null only when the definition could not be read. */
    data class Rejected(
        override val runId: String?,
        val errorCode: String,
        val safeMessage: String
    ) : WorkflowExecutionResult

    /** The graph parked at one node; only a run-owned execution can resume it. */
    data class Suspended(
        override val runId: String,
        val suspension: WorkflowNodeSuspension,
        val outcomes: List<WorkflowNodeOutcome>
    ) : WorkflowExecutionResult

    /** The graph parked on a user decision (WAIT_APPROVAL node or WAIT_FOR_USER failure). */
    data class SuspendedForUser(
        override val runId: String,
        val wait: WorkflowUserWait,
        val outcomes: List<WorkflowNodeOutcome>
    ) : WorkflowExecutionResult
}

/** Result of one graph step inside an existing Agent run (N18/N19 run-owned execution). */
sealed interface WorkflowFrameOutcome {
    data class Completed(
        val outputs: Map<String, AgentJsonValue>,
        val outcomes: List<WorkflowNodeOutcome>
    ) : WorkflowFrameOutcome

    data class Failed(
        val errorCode: String,
        val safeMessage: String,
        val outcomes: List<WorkflowNodeOutcome>
    ) : WorkflowFrameOutcome

    /** The graph parked at one node until the owner resolves the frozen invocation's approval. */
    data class Suspended(
        val invocationId: String,
        val callHash: String,
        val approval: com.muyuchat.core.agent.contract.AgentApproval,
        val outcomes: List<WorkflowNodeOutcome>
    ) : WorkflowFrameOutcome

    /** The frame parked on a user decision; the owner parks the run and re-enters on resume. */
    data class SuspendedForUser(
        val wait: WorkflowUserWait,
        val outcomes: List<WorkflowNodeOutcome>
    ) : WorkflowFrameOutcome

    data class Rejected(val errorCode: String, val safeMessage: String) : WorkflowFrameOutcome
}

/**
 * App-layer graph execution service (N19/N20/N21 service slice).
 *
 * It reads one immutable published definition version, owns a durable per-run checkpoint through
 * [FileWorkflowCheckpointStore], and advances the existing [WorkflowGraphRunner]. A resumed run
 * restores the checkpoint and never re-executes a terminal node.
 *
 * Node boundaries:
 * - `TOOL` only through the narrow [WorkflowToolGateway];
 * - `AGENT_TASK` only through [WorkflowAgentTaskRunner]; when no owner adapter is wired (or the
 *   request carries no [WorkflowAgentTaskPlan]) it is a typed `WF_NODE_NOT_SUPPORTED`;
 * - `SUBWORKFLOW` executes a pinned immutable version in the same service with a bounded recursion
 *   depth and the same shared [WorkflowRunBudget];
 * - `EXTRACT` is a pure, bounded dot-path extraction from one committed predecessor value;
 * - `MODEL` and `SCRIPT` are adapter nodes: MODEL delegates to the bound Agent model port
 *   (`WF_NODE_NOT_SUPPORTED` only when the required state/port is absent) and SCRIPT resolves its
 *   `scriptRef` through the same narrow tool gateway, never through a second script engine;
 * - `TRIGGER`, `CONDITION`, `LOGIC`, `COMPENSATE`, and `WAIT_APPROVAL` are executed by
 *   [WorkflowGraphRunner] itself; this service keeps typed `WF_NODE_UNEXPECTED`/`WAIT_NOT_SUPPORTED`
 *   answers only as a guard if one ever reaches an executor;
 * - `WAIT_APPROVAL` and a `WAIT_FOR_USER` node failure park the frame before the node commits, so
 *   a resumed execution re-enters it with the durable answer and never replays a committed node.
 *
 * This is still not a run owner: it has no epoch/lease/admission and does not write a journal. The
 * parent budget is accounted here because the graph is the only caller that knows every frame.
 */
class WorkflowExecutionService(
    private val definitions: AgentWorkflowDefinitionStore,
    checkpointRoot: File,
    /**
     * N19 follow-up: the composition root chooses the durable frame backend (Room in production).
     * Null keeps the file CAS store used by tests and legacy imports.
     */
    private val checkpointStoreFactory: WorkflowCheckpointStoreFactory? = null,
    private val toolGateway: WorkflowToolGateway,
    private val agentTaskRunner: WorkflowAgentTaskRunner = NoWorkflowAgentTaskRunner,
    /**
     * The same model surface the Agent loop uses. A MODEL node performs one bounded completion
     * through the exact provider adapter/lease/stop proof; null keeps the node typed NOT_SUPPORTED.
     */
    private val modelPort: com.muyuchat.core.agent.contract.AgentModelPort? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val runIdFactory: () -> String = { "wfrun-" + UUID.randomUUID().toString() },
    /**
     * Run-owned execution (N18/N19): when present, [executeFrame] builds the tool gateway from the
     * exact Agent run/task and the shared invocation pipeline. The legacy [toolGateway] is never
     * consulted on this path.
     */
    private val runScopedToolGatewayFactory: WorkflowToolGatewayFactory? = null,
    /**
     * The one existing model loop, used for a prompt-only `AGENT_TASK` **same-root frame**.
     *
     * The node runs under the parent run/task identity and never creates a child task/run. Tool or
     * approval frames still reject: the full frame transcript/catalog/approval contract is not yet
     * wired. Null keeps the S1 rejection.
     */
    private val agentFrameExecutor: WorkflowAgentFrameExecutor? = null
) {
    private val checkpointRoot: File = checkpointRoot.absoluteFile

    private fun openCheckpointStore(
        key: String,
        legacyKey: String? = null
    ): WorkflowCheckpointStore =
        checkpointStoreFactory?.create(key, legacyKey)
            ?: FileWorkflowCheckpointStore(checkpointRoot, key, legacyKey)
    private val codec: StrictJsonCodec = StrictAgentJsonCodec()
    private val externalNodes = WorkflowExternalNodeAdapters(modelPort, agentFrameExecutor, agentTaskRunner, codec)
    private val subgraphRunner = object : WorkflowSubgraphRunner {
        override suspend fun executeGraph(
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
            state: WorkflowToolRunState?,
            ownerFrame: Boolean,
            ownerRun: AgentRun?,
            ownerTask: AgentTask?,
            ownerResume: AgentLoopResume?,
            ownerProgress: AgentModelProgressSink?,
            ownerAgentFrameExecutor: WorkflowAgentFrameExecutor?,
            userDecision: String?
        ): WorkflowExecutionResult = this@WorkflowExecutionService.executeGraph(
            definition, runId, frameKey, legacyFrameKey, toolScope, plan, depth, budget, checkpoint,
            store, referencedDefinitions, toolGateway, state, ownerFrame, ownerRun, ownerTask,
            ownerResume, ownerProgress, ownerAgentFrameExecutor, userDecision
        )
    }
    private val compositionNodes = WorkflowCompositionNodeAdapters(
        definitions = definitions,
        codec = codec,
        openCheckpointStore = object : WorkflowChildCheckpointOpener {
            override fun open(key: String, legacyKey: String?): WorkflowCheckpointStore =
                this@WorkflowExecutionService.openCheckpointStore(key, legacyKey)
        },
        subgraphs = subgraphRunner
    )
    suspend fun execute(request: WorkflowExecutionRequest): WorkflowExecutionResult {
        val definition = try {
            definitions.version(request.definitionId, request.version)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AgentWorkflowException) {
            return rejected(request.runId, failure.code, failure.safeMessage)
        } catch (_: Throwable) {
            return rejected(
                request.runId,
                WorkflowExecutionErrorCodes.STORE_FAILURE,
                "The workflow definition store is unavailable."
            )
        } ?: return rejected(
            request.runId,
            WorkflowExecutionErrorCodes.VERSION_NOT_FOUND,
            "The requested workflow definition version was not found."
        )

        val runId = request.runId ?: runIdFactory()
        if (!RUN_ID_PATTERN.matches(runId)) {
            return rejected(
                runId,
                WorkflowExecutionErrorCodes.RUN_ID_INVALID,
                "The workflow run id is not a safe checkpoint key."
            )
        }
        val checkpointStore = try {
            openCheckpointStore(runId)
        } catch (_: Throwable) {
            return rejected(
                runId,
                WorkflowExecutionErrorCodes.CHECKPOINT_STORE_UNAVAILABLE,
                "The workflow checkpoint store is unavailable."
            )
        }
        val referencedDefinitions = try {
            resolveReferencedDefinitions(definition)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return rejected(
                runId,
                WorkflowExecutionErrorCodes.STORE_FAILURE,
                "A referenced subworkflow definition could not be resolved."
            )
        }
        val checkpoint = when (val load = checkpointStore.load(definition)) {
            is WorkflowCheckpointLoad.Absent -> null
            is WorkflowCheckpointLoad.Loaded -> load.checkpoint
            is WorkflowCheckpointLoad.Rejected -> return rejected(runId, load.errorCode, load.safeMessage)
        }
        return executeGraph(
            definition = definition,
            runId = runId,
            frameKey = runId,
            legacyFrameKey = runId,
            toolScope = request.toolScope,
            plan = request.agentTaskPlan,
            depth = 0,
            budget = WorkflowRunBudget(definition.budget, clock),
            checkpoint = checkpoint,
            store = checkpointStore,
            referencedDefinitions = referencedDefinitions,
            toolGateway = toolGateway,
            userDecision = request.userDecision
        )
    }

    /**
     * Executes one published graph as the strategy of an existing Agent run (N18/N19).
     *
     * Everything that can pause or execute a side effect is owned by the caller: the run id keys the
     * checkpoint, the run/task identity feeds the shared invocation pipeline, and a suspension is
     * returned instead of being written as a node failure. The graph never creates a run, a model
     * attempt or a tool executor of its own.
     */
    suspend fun executeFrame(
        run: AgentRun,
        task: AgentTask,
        resume: AgentLoopResume?,
        state: WorkflowToolRunState,
        progress: AgentModelProgressSink = AgentModelProgressSink {}
    ): WorkflowFrameOutcome {
        val spec = run.policy.workflow
            ?: return WorkflowFrameOutcome.Rejected(
                WorkflowExecutionErrorCodes.NODE_UNEXPECTED,
                "The run does not pin a workflow definition."
            )
        val definition = try {
            definitions.version(spec.definitionId, spec.version)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return WorkflowFrameOutcome.Rejected(
                WorkflowExecutionErrorCodes.STORE_FAILURE,
                "The workflow definition store is unavailable."
            )
        } ?: return WorkflowFrameOutcome.Rejected(
            WorkflowExecutionErrorCodes.VERSION_NOT_FOUND,
            "The pinned workflow definition version was not found."
        )
        val checkpointKey = WorkflowCheckpointKeys.taskRoot(task.taskId)
        val checkpointStore = try {
            openCheckpointStore(
                key = checkpointKey,
                legacyKey = run.runId
            )
        } catch (_: Throwable) {
            return WorkflowFrameOutcome.Rejected(
                WorkflowExecutionErrorCodes.CHECKPOINT_STORE_UNAVAILABLE,
                "The workflow checkpoint store is unavailable."
            )
        }
        val referencedDefinitions = try {
            resolveReferencedDefinitions(definition)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return WorkflowFrameOutcome.Rejected(
                WorkflowExecutionErrorCodes.STORE_FAILURE,
                "A referenced subworkflow definition could not be resolved."
            )
        }
        unsupportedOwnerFrame(definition, referencedDefinitions, run.policy)?.let { (code, message) ->
            return WorkflowFrameOutcome.Rejected(code, message)
        }
        val checkpoint = when (val load = checkpointStore.load(definition)) {
            is WorkflowCheckpointLoad.Absent -> null
            is WorkflowCheckpointLoad.Loaded -> load.checkpoint
            is WorkflowCheckpointLoad.Rejected -> return WorkflowFrameOutcome.Rejected(
                load.errorCode,
                load.safeMessage
            )
        }
        val gateway = runScopedToolGatewayFactory?.create(run, task, state, resume)
            ?: WorkflowToolGateway {
                WorkflowToolOutcome.Failed(
                    WorkflowExecutionErrorCodes.TOOL_NOT_REGISTERED,
                    "No run-scoped tool gateway is configured for this execution."
                )
            }
        val result = executeGraph(
            definition = definition,
            runId = run.runId,
            frameKey = checkpointKey,
            legacyFrameKey = run.runId,
            toolScope = WorkflowToolScope(task.taskId, task.workspaceId),
            plan = WorkflowAgentTaskPlan(
                modelBinding = run.modelBinding,
                policy = run.policy.copy(workflow = null),
                displayTitle = task.goal.take(MAX_AGENT_TASK_TITLE_CHARS)
            ),
            depth = 0,
            budget = WorkflowRunBudget(definition.budget, clock),
            checkpoint = checkpoint,
            store = checkpointStore,
            referencedDefinitions = referencedDefinitions,
            toolGateway = gateway,
            state = state,
            ownerFrame = true,
            ownerRun = run,
            ownerTask = task,
            ownerResume = resume,
            ownerProgress = progress,
            ownerAgentFrameExecutor = agentFrameExecutor,
            // Only a durable user answer can satisfy a workflow wait; the text is normalized to a
            // stable decision token, and an unknown answer fails closed inside the runner.
            userDecision = WorkflowWaitText.decisionFor(resume?.userAnswer)
        )
        return when (result) {
            is WorkflowExecutionResult.Completed -> WorkflowFrameOutcome.Completed(
                result.outputs,
                result.outcomes
            )
            is WorkflowExecutionResult.Failed -> WorkflowFrameOutcome.Failed(
                result.errorCode,
                result.safeMessage,
                result.outcomes
            )
            is WorkflowExecutionResult.SuspendedForUser -> WorkflowFrameOutcome.SuspendedForUser(
                wait = result.wait,
                outcomes = result.outcomes
            )
            is WorkflowExecutionResult.Rejected -> WorkflowFrameOutcome.Rejected(
                result.errorCode,
                result.safeMessage
            )
            is WorkflowExecutionResult.Suspended -> WorkflowFrameOutcome.Suspended(
                invocationId = result.suspension.invocationId,
                callHash = result.suspension.callHash,
                approval = result.suspension.approval,
                outcomes = result.outcomes
            )
        }
    }

    /**
     * Resolves every pinned subworkflow reachable from [root] from the immutable definition store.
     *
     * The graph runner's static validator needs these pins (it never executes the child). The walk is
     * bounded twice: by the same nesting cap the validator enforces and by a hard number of resolved
     * definitions, so a hostile/corrupt store cannot make resolution unbounded. A missing pin stays
     * missing, and the validator then rejects the graph instead of silently skipping it.
     */
    private fun resolveReferencedDefinitions(
        root: WorkflowDefinition
    ): Map<WorkflowDefinitionValidator.SubworkflowRef, WorkflowDefinition> {
        val resolved =
            LinkedHashMap<WorkflowDefinitionValidator.SubworkflowRef, WorkflowDefinition>()
        fun visit(definition: WorkflowDefinition, depth: Int) {
            if (depth > MAX_SUBWORKFLOW_DEPTH || resolved.size >= MAX_REFERENCED_DEFINITIONS) return
            definition.nodes.forEach { node ->
                val ref = when (node.type) {
                    WorkflowNodeType.SUBWORKFLOW -> {
                        val definitionId = node.subworkflowDefinitionId ?: return@forEach
                        val version = node.subworkflowVersion ?: return@forEach
                        WorkflowDefinitionValidator.SubworkflowRef(definitionId, version)
                    }
                    WorkflowNodeType.FOREACH -> {
                        val fields = workflowConfigFields(codec, node.configJson) ?: return@forEach
                        val definitionId = (fields["subworkflowDefinitionId"] as? AgentJsonValue.Str)
                            ?.value?.takeIf { it.isNotBlank() } ?: return@forEach
                        val version = (fields["subworkflowVersion"] as? AgentJsonValue.Num)
                            ?.literal?.toLongOrNull() ?: return@forEach
                        WorkflowDefinitionValidator.SubworkflowRef(definitionId, version)
                    }
                    else -> return@forEach
                }
                if (ref in resolved || resolved.size >= MAX_REFERENCED_DEFINITIONS) return@forEach
                val child = definitions.version(ref.definitionId, ref.version) ?: return@forEach
                resolved[ref] = child
                visit(child, depth + 1)
            }
        }
        visit(root, 0)
        return resolved
    }

    /**
     * Run-owned `AGENT_TASK` requires the same-root frame executor.
     *
     * The executor resolves the exact run catalog and refuses any write/network/external tool
     * before the first model request, because a nested approval suspension is not checkpointed in
     * the parent frame yet. Read-only tools, memory and prompt-only skills therefore run inside the
     * parent run; the guard stays before the checkpoint/gateway so an absent executor still has
     * zero side effects.
     */
    private fun unsupportedOwnerFrame(
        root: WorkflowDefinition,
        referenced: Map<WorkflowDefinitionValidator.SubworkflowRef, WorkflowDefinition>,
        policy: AgentRunPolicy
    ): Pair<String, String>? {
        val definitions = listOf(root) + referenced.values
        val hasAgentTask = definitions.any { definition ->
            definition.nodes.any { it.type == WorkflowNodeType.AGENT_TASK }
        }
        if (!hasAgentTask) return null
        if (agentFrameExecutor == null) {
            return WorkflowExecutionErrorCodes.FRAME_NOT_SUPPORTED to
                "AGENT_TASK needs the same-root frame executor, which this process does not provide."
        }
        return null
    }

    private suspend fun executeGraph(        definition: WorkflowDefinition,
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
    ): WorkflowExecutionResult {
        val context = WorkflowNodeContext(
            runId = runId,
            frameKey = frameKey,
            legacyFrameKey = legacyFrameKey,
            toolScope = toolScope,
            plan = plan,
            depth = depth,
            budget = budget,
            referencedDefinitions = referencedDefinitions,
            toolGateway = toolGateway,
            state = state,
            ownerFrame = ownerFrame,
            ownerRun = ownerRun,
            ownerTask = ownerTask,
            ownerResume = ownerResume,
            ownerProgress = ownerProgress,
            agentFrameExecutor = ownerAgentFrameExecutor,
            userDecision = userDecision
        )
        val runner = WorkflowGraphRunner(
            executor = nodeExecutor(context),
            referencedDefinitions = referencedDefinitions
        )
        val outcome = try {
            runner.run(definition, checkpoint, store, userDecision)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return WorkflowExecutionResult.Failed(
                runId = runId,
                errorCode = WorkflowExecutionErrorCodes.EXECUTION_FAILED,
                safeMessage = "The graph execution stopped unexpectedly.",
                outcomes = emptyList()
            )
        }
        return when (outcome) {
            is WorkflowGraphResult.Completed -> WorkflowExecutionResult.Completed(
                runId = runId,
                outputs = outcome.outputs,
                outcomes = outcome.outcomes
            )
            is WorkflowGraphResult.Failed -> WorkflowExecutionResult.Failed(
                runId = runId,
                errorCode = outcome.errorCode,
                safeMessage = outcome.safeMessage,
                outcomes = outcome.outcomes
            )
            is WorkflowGraphResult.Rejected -> WorkflowExecutionResult.Rejected(
                runId = runId,
                errorCode = outcome.errorCode,
                safeMessage = outcome.safeMessage
            )
            is WorkflowGraphResult.SuspendedForUser -> WorkflowExecutionResult.SuspendedForUser(
                runId = runId,
                wait = outcome.wait,
                outcomes = outcome.outcomes
            )
            is WorkflowGraphResult.Suspended -> WorkflowExecutionResult.Suspended(
                runId = runId,
                suspension = outcome.suspension,
                outcomes = outcome.outcomes
            )
        }
    }

    private fun nodeExecutor(context: WorkflowNodeContext): WorkflowNodeExecutor =
        WorkflowNodeExecutor { request -> executeNode(request, context) }

    private suspend fun executeNode(
        request: WorkflowNodeExecutionRequest,
        context: WorkflowNodeContext
    ): WorkflowNodeExecution {
        if (context.budget.durationExhausted()) {
            return failed(
                WorkflowExecutionErrorCodes.BUDGET_EXHAUSTED,
                "The workflow run exceeded its wall-clock budget."
            )
        }
        return when (request.node.type) {
            WorkflowNodeType.OUTPUT -> compositionNodes.outputNode(request)
            WorkflowNodeType.EXTRACT -> compositionNodes.extractNode(request)
            WorkflowNodeType.SUBWORKFLOW -> compositionNodes.subworkflowNode(request, context)
            WorkflowNodeType.FOREACH -> compositionNodes.foreachNode(request, context)
            WorkflowNodeType.TOOL -> externalNodes.toolNode(request, context)
            WorkflowNodeType.SCRIPT -> externalNodes.scriptNode(request, context)
            WorkflowNodeType.MODEL -> externalNodes.modelNode(request, context)
            WorkflowNodeType.AGENT_TASK -> externalNodes.agentTaskNode(request, context)
            WorkflowNodeType.WAIT_APPROVAL -> failed(
                WorkflowExecutionErrorCodes.WAIT_NOT_SUPPORTED,
                "The graph runner parks a wait node before it reaches a node executor."
            )
            WorkflowNodeType.LOGIC -> failed(
                WorkflowExecutionErrorCodes.NODE_NOT_SUPPORTED,
                "A logic node has no published V1 operation semantics; no operation is invented here."
            )
            WorkflowNodeType.COMPENSATE -> failed(
                WorkflowExecutionErrorCodes.NODE_UNEXPECTED,
                "The runner executes compensation nodes itself; they never reach a node executor."
            )
            WorkflowNodeType.TRIGGER,
            WorkflowNodeType.CONDITION -> failed(
                WorkflowExecutionErrorCodes.NODE_UNEXPECTED,
                "The runner must not dispatch a trigger or condition node to an executor."
            )
        }
    }

    private fun failed(code: String, message: String): WorkflowNodeExecution.Failed =
        WorkflowNodeExecution.Failed(code, message)

    private fun rejected(
        runId: String?,
        code: String,
        message: String
    ): WorkflowExecutionResult.Rejected =
        WorkflowExecutionResult.Rejected(runId, code, message)

    private companion object {
        /** Same safe-key rule as [FileWorkflowCheckpointStore]. */
        val RUN_ID_PATTERN = WorkflowExecutionLimits.RUN_ID_PATTERN

        /** Same pinned nesting cap the static validator enforces. */
        const val MAX_SUBWORKFLOW_DEPTH = WorkflowExecutionLimits.MAX_SUBWORKFLOW_DEPTH

        /** Hard cap on resolved pinned definitions per run, independent of the depth cap. */
        const val MAX_REFERENCED_DEFINITIONS = WorkflowExecutionLimits.MAX_REFERENCED_DEFINITIONS

        const val EXTRACT_SOURCE_INPUT = WorkflowExecutionLimits.EXTRACT_SOURCE_INPUT
        const val EXTRACT_PATH_WHOLE = WorkflowExecutionLimits.EXTRACT_PATH_WHOLE
        const val MAX_AGENT_TASK_TITLE_CHARS = WorkflowExecutionLimits.MAX_AGENT_TASK_TITLE_CHARS
        const val MAX_FOREACH_ITEMS = WorkflowExecutionLimits.MAX_FOREACH_ITEMS
        const val MAX_FOREACH_OUTPUT_BYTES = WorkflowExecutionLimits.MAX_FOREACH_OUTPUT_BYTES
        const val DEFAULT_MODEL_OUTPUT_TOKENS = WorkflowExecutionLimits.DEFAULT_MODEL_OUTPUT_TOKENS
        const val MAX_MODEL_OUTPUT_TOKENS = WorkflowExecutionLimits.MAX_MODEL_OUTPUT_TOKENS
        const val MAX_MODEL_INPUT_BYTES = WorkflowExecutionLimits.MAX_MODEL_INPUT_BYTES
        const val MAX_MODEL_OUTPUT_BYTES = WorkflowExecutionLimits.MAX_MODEL_OUTPUT_BYTES
        const val WORKFLOW_MODEL_SYSTEM_PROMPT =
            "You are a workflow model node. Answer the user prompt using the provided input JSON. " +
                "Return plain text only; do not emit tool calls."
    }
}
