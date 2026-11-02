package com.muyuchat.core.agent.runtime.workflow

import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.contract.workflow.WorkflowEdge
import com.muyuchat.core.agent.contract.workflow.WorkflowFailurePolicy
import com.muyuchat.core.agent.contract.workflow.WorkflowNode
import com.muyuchat.core.agent.contract.workflow.WorkflowNodeType
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec

/**
 * Durable checkpoint of one in-memory graph execution (N19 base).
 *
 * It is intentionally a plain value: persistence is the caller's transaction, and revisions are a
 * CAS token. A resumed runner restores node facts and never re-executes a SUCCEEDED node.
 */
data class WorkflowRunCheckpoint(
    val definitionId: String,
    val definitionVersion: Long,
    val revision: Long,
    val outcomes: List<WorkflowNodeOutcome>,
    val nodeOutputs: Map<String, AgentJsonValue>,
    val outputs: Map<String, AgentJsonValue>,
    val activeEdges: Set<String>,
    val skipped: Set<String>,
    /** Set once the graph stopped on a failure and its compensations are being applied. */
    val compensating: Boolean = false,
    /** Targets whose compensation already ran (or ran and failed); never retried. */
    val compensated: Set<String> = emptySet(),
    /** The original terminal failure, kept so a compensation resume reports it unchanged. */
    val failureCode: String? = null,
    val failureMessage: String? = null
) {
    init {
        require(definitionId.isNotBlank()) { "checkpoint definitionId must not be blank" }
        require(revision >= 0L) { "checkpoint revision must not be negative" }
        require(outcomes.map { it.nodeId }.distinct().size == outcomes.size) {
            "checkpoint outcomes must be unique per node"
        }
    }
}

/** CAS-style sink; returning false means another writer owns a newer revision. */
fun interface WorkflowCheckpointSink {
    fun save(checkpoint: WorkflowRunCheckpoint): Boolean
}

sealed interface WorkflowCheckpointLoad {
    data class Loaded(val checkpoint: WorkflowRunCheckpoint) : WorkflowCheckpointLoad

    data object Absent : WorkflowCheckpointLoad

    data class Rejected(val errorCode: String, val safeMessage: String) : WorkflowCheckpointLoad
}

fun interface WorkflowCheckpointSource {
    fun load(definition: WorkflowDefinition): WorkflowCheckpointLoad
}

/** Terminal state of one graph node in a single execution attempt. */
enum class WorkflowNodeState {
    SUCCEEDED,
    FAILED,
    SKIPPED
}

/** One node fact. A skipped node has no output and no error. */
data class WorkflowNodeOutcome(
    val nodeId: String,
    val state: WorkflowNodeState,
    val output: AgentJsonValue? = null,
    val errorCode: String? = null,
    val safeMessage: String? = null
)

/**
 * One durable pause produced by a node executor.
 *
 * The runner deliberately stops without recording the waiting node, so a resumed execution retries
 * exactly that node from the last committed checkpoint. The owner persists the corresponding run
 * state; the runner never treats the pause as success or failure.
 */
data class WorkflowNodeSuspension(
    val invocationId: String,
    val callHash: String,
    val approval: com.muyuchat.core.agent.contract.AgentApproval
)

/**
 * Why a workflow frame asks the user instead of continuing.
 *
 * Both kinds are answerable by a durable park: [Approval] is a `WAIT_APPROVAL` node, [NodeFailure]
 * is the `WAIT_FOR_USER` failure policy. The waiting node is never committed, so a duplicate wake
 * simply re-parks; the decision is supplied by the owner on the next execution.
 */
sealed interface WorkflowUserWait {
    data class Approval(val nodeId: String) : WorkflowUserWait

    data class NodeFailure(
        val nodeId: String,
        val errorCode: String,
        val safeMessage: String
    ) : WorkflowUserWait
}

/** Stable decision tokens; an unknown answer is a typed failure, never an implicit approval. */
object WorkflowUserDecisions {
    const val APPROVE = "approve"
    const val DENY = "deny"
    const val CONTINUE = "continue"
    const val ABORT = "abort"
}

/** What the injected node executor may return; no executor can bypass the runner's state machine. */
sealed interface WorkflowNodeExecution {
    data class Succeeded(val output: AgentJsonValue) : WorkflowNodeExecution

    data class Failed(val errorCode: String, val safeMessage: String) : WorkflowNodeExecution

    /** Requests a durable park; the node stays uncommitted and the run waits for the owner. */
    data class Suspended(val suspension: WorkflowNodeSuspension) : WorkflowNodeExecution

    /**
     * A composite node whose pinned child parked on a user decision.
     *
     * The parent frame parks with the child's wait and never commits the node, so a resumed
     * execution re-enters the same child checkpoint with the durable answer.
     */
    data class WaitingUser(val wait: WorkflowUserWait) : WorkflowNodeExecution
}

/** Executor seam for non-trigger/non-condition nodes (tool/agent/model/script/...). */
fun interface WorkflowNodeExecutor {
    suspend fun execute(request: WorkflowNodeExecutionRequest): WorkflowNodeExecution
}

data class WorkflowNodeExecutionRequest(
    val node: WorkflowNode,
    /** Outputs of the activated direct predecessors, in edge order. */
    val inputs: List<AgentJsonValue>
)

sealed interface WorkflowGraphResult {
    data class Completed(
        val outputs: Map<String, AgentJsonValue>,
        val outcomes: List<WorkflowNodeOutcome>
    ) : WorkflowGraphResult

    data class Failed(
        val errorCode: String,
        val safeMessage: String,
        val outcomes: List<WorkflowNodeOutcome>
    ) : WorkflowGraphResult

    /** The graph parked at one node; [outcomes] are the committed facts before that node. */
    data class Suspended(
        val suspension: WorkflowNodeSuspension,
        val outcomes: List<WorkflowNodeOutcome>
    ) : WorkflowGraphResult

    /**
     * The graph parked on a user decision. Nothing was committed for the waiting node, so the
     * resumed execution re-enters it with the durable answer and cannot replay prior side effects.
     */
    data class SuspendedForUser(
        val wait: WorkflowUserWait,
        val outcomes: List<WorkflowNodeOutcome>
    ) : WorkflowGraphResult

    data class Rejected(val errorCode: String, val safeMessage: String) : WorkflowGraphResult
}

/**
 * In-memory DAG walker for one workflow definition (N18 base, N19 checkpoint seam).
 *
 * It is deliberately *not* a second run state machine: it produces node facts for a caller that
 * owns persistence/approvals. Processing follows the validator's topological order, activates edges
 * from conditions (`true`/`false` routes only), runs a node when at least one active incoming edge
 * exists, and marks unreachable nodes SKIPPED. FAIL_FAST stops at the first failure; WAIT_FOR_USER
 * is rejected up front because no approval/park machinery is wired here.
 *
 * After every node fact the runner offers a checkpoint to the sink; a `false` answer (CAS lost) or a
 * throwing sink ends the attempt with a typed error. A resumed run restores the checkpoint and never
 * re-executes an already-terminal node.
 */
class WorkflowGraphRunner(
    private val executor: WorkflowNodeExecutor,
    private val validator: WorkflowDefinitionValidator = WorkflowDefinitionValidator(),
    private val codec: StrictJsonCodec = StrictAgentJsonCodec(),
    /**
     * Pinned subworkflow definitions available to static validation. The caller resolves them from
     * the immutable definition store; an empty map keeps the historical fail-closed behavior for a
     * graph that references a subworkflow. Resolution is still bounded by the validator's depth cap.
     */
    private val referencedDefinitions: Map<WorkflowDefinitionValidator.SubworkflowRef, WorkflowDefinition> =
        emptyMap()
) {
    suspend fun run(
        definition: WorkflowDefinition,
        checkpoint: WorkflowRunCheckpoint? = null,
        sink: WorkflowCheckpointSink? = null,
        /**
         * Durable answer for the frame's parked wait, supplied by the owner on resume. It is
         * consumed by exactly one wait: the `WAIT_APPROVAL` node, or the `WAIT_FOR_USER` failure.
         * An unknown token is a typed failure, never an implicit approval.
         */
        userDecision: String? = null
    ): WorkflowGraphResult {
        var pendingDecision = userDecision?.takeIf { it.isNotBlank() }
        val validation = validator.validate(definition, referencedDefinitions)
        val order = when (validation) {
            is WorkflowValidationResult.Valid -> validation.topologicalOrder
            is WorkflowValidationResult.Invalid -> return WorkflowGraphResult.Rejected(
                "WF_DEFINITION_INVALID",
                "The definition is invalid: " +
                    validation.issues.joinToString { it.code }.take(200)
            )
        }
        val byId = definition.nodes.associateBy { it.nodeId }
        if (checkpoint != null) {
            if (checkpoint.definitionId != definition.definitionId ||
                checkpoint.definitionVersion != definition.version
            ) {
                return WorkflowGraphResult.Rejected(
                    "WF_CHECKPOINT_MISMATCH",
                    "The checkpoint belongs to another definition version."
                )
            }
            if (checkpoint.outcomes.any { it.nodeId !in byId }) {
                return WorkflowGraphResult.Rejected(
                    "WF_CHECKPOINT_MISMATCH",
                    "The checkpoint references nodes that no longer exist."
                )
            }
        }
        val outcomes = ArrayList<WorkflowNodeOutcome>(checkpoint?.outcomes ?: emptyList())
        val nodeOutputs = HashMap<String, AgentJsonValue>(checkpoint?.nodeOutputs ?: emptyMap())
        val outputs = LinkedHashMap<String, AgentJsonValue>(checkpoint?.outputs ?: emptyMap())
        val activeEdges = HashSet<String>(checkpoint?.activeEdges ?: emptySet())
        val skipped = HashSet<String>(checkpoint?.skipped ?: emptySet())
        val completedNodes = outcomes.mapTo(HashSet()) { it.nodeId }
        var revision = checkpoint?.revision ?: 0L
        var compensating = checkpoint?.compensating == true
        val compensatedTargets = HashSet<String>(checkpoint?.compensated ?: emptySet())
        var failureCode = checkpoint?.failureCode
        var failureMessage = checkpoint?.failureMessage
        val trigger = definition.nodes.single { it.type == WorkflowNodeType.TRIGGER }

        var firstFailure: Pair<String, String>? = outcomes
            .firstOrNull { it.state == WorkflowNodeState.FAILED }
            ?.let { (it.errorCode ?: "WF_NODE_FAILED") to (it.safeMessage ?: "A node failed.") }

        fun checkpointValue(): WorkflowRunCheckpoint = WorkflowRunCheckpoint(
            definitionId = definition.definitionId,
            definitionVersion = definition.version,
            revision = revision + 1L,
            outcomes = outcomes.toList(),
            nodeOutputs = nodeOutputs.toMap(),
            outputs = outputs.toMap(),
            activeEdges = activeEdges.toSet(),
            skipped = skipped.toSet(),
            compensating = compensating,
            compensated = compensatedTargets.toSet(),
            failureCode = failureCode,
            failureMessage = failureMessage
        )

        fun persist(): WorkflowGraphResult.Failed? {
            if (sink == null) return null
            val next = checkpointValue()
            val saved = try {
                sink.save(next)
            } catch (_: Exception) {
                return WorkflowGraphResult.Failed(
                    "WF_CHECKPOINT_FAILED",
                    "The checkpoint sink failed after a node fact was produced.",
                    outcomes.toList()
                )
            }
            if (!saved) {
                return WorkflowGraphResult.Failed(
                    "WF_CHECKPOINT_CONFLICT",
                    "A newer checkpoint revision already exists; this attempt must stop.",
                    outcomes.toList()
                )
            }
            revision = next.revision
            return null
        }

        val compensationByTarget: Map<String, WorkflowNode> = definition.nodes
            .filter { it.type == WorkflowNodeType.COMPENSATE }
            .mapNotNull { node -> compensationTarget(node)?.let { target -> target to node } }
            .toMap()

        // Compensations run only after the graph reached a terminal failure, in reverse completion
        // order, for nodes that actually succeeded. Each step goes through the same executor, so an
        // approval parks the run exactly like a tool node; a resume re-enters this phase from the
        // checkpoint instead of replaying the graph, and a failed compensation is recorded without
        // masking the original failure.
        suspend fun compensate(code: String, message: String): WorkflowGraphResult {
            compensating = true
            if (failureCode == null) {
                failureCode = code
                failureMessage = message
            }
            persist()?.let { return it }
            val pending = outcomes
                .filter { it.state == WorkflowNodeState.SUCCEEDED }
                .mapNotNull { outcome ->
                    compensationByTarget[outcome.nodeId]?.let { node -> outcome.nodeId to node }
                }
                .filterNot { it.first in compensatedTargets }
                .asReversed()
            for ((targetId, node) in pending) {
                val step = WorkflowNode(node.nodeId, WorkflowNodeType.TOOL, node.configJson)
                val execution = try {
                    executor.execute(WorkflowNodeExecutionRequest(step, emptyList()))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    WorkflowNodeExecution.Failed(
                        COMPENSATION_FAILED,
                        "The compensation executor failed."
                    )
                }
                when (execution) {
                    is WorkflowNodeExecution.Succeeded -> {
                        compensatedTargets += targetId
                        outcomes += WorkflowNodeOutcome(
                            node.nodeId,
                            WorkflowNodeState.SUCCEEDED,
                            execution.output
                        )
                        nodeOutputs[node.nodeId] = execution.output
                        persist()?.let { return it }
                    }
                    is WorkflowNodeExecution.Failed -> {
                        compensatedTargets += targetId
                        outcomes += WorkflowNodeOutcome(
                            node.nodeId,
                            WorkflowNodeState.FAILED,
                            errorCode = COMPENSATION_FAILED,
                            safeMessage = execution.safeMessage
                        )
                        persist()?.let { return it }
                    }
                    is WorkflowNodeExecution.Suspended -> {
                        persist()?.let { return it }
                        return WorkflowGraphResult.Suspended(execution.suspension, outcomes.toList())
                    }
                    is WorkflowNodeExecution.WaitingUser -> {
                        // A compensation node may not park the frame; that is a compensation failure.
                        compensatedTargets += targetId
                        outcomes += WorkflowNodeOutcome(
                            node.nodeId,
                            WorkflowNodeState.FAILED,
                            errorCode = COMPENSATION_FAILED,
                            safeMessage = "A compensation node must not wait for the user."
                        )
                        persist()?.let { return it }
                    }
                }
            }
            return fail(failureCode ?: code, failureMessage ?: message, outcomes)
        }

        suspend fun finish(code: String, message: String): WorkflowGraphResult =
            if (compensationByTarget.isEmpty()) fail(code, message, outcomes) else compensate(code, message)

        if (compensating) {
            return compensate(failureCode ?: "WF_NODE_FAILED", failureMessage ?: "A node failed.")
        }

        // A restored checkpoint that already committed a failure parks again when the definition
        // waits for the user; the answer decides whether the remaining branches run. Nothing is
        // replayed: the failed node stays committed and its successors only activate on "continue".
        val restoredFailure = outcomes.firstOrNull { it.state == WorkflowNodeState.FAILED }
        if (restoredFailure != null && definition.failurePolicy == WorkflowFailurePolicy.WAIT_FOR_USER) {
            val decision = pendingDecision
            pendingDecision = null
            when (decision) {
                // A failed node produced no output, so its branch cannot run; other branches that
                // are already active still do. This mirrors CONTINUE and never invents an input.
                WorkflowUserDecisions.CONTINUE -> Unit

                null -> return WorkflowGraphResult.SuspendedForUser(
                    WorkflowUserWait.NodeFailure(
                        restoredFailure.nodeId,
                        restoredFailure.errorCode ?: "WF_NODE_FAILED",
                        restoredFailure.safeMessage ?: "A node failed."
                    ),
                    outcomes.toList()
                )

                else -> return fail(
                    restoredFailure.errorCode ?: "WF_NODE_FAILED",
                    restoredFailure.safeMessage ?: "A node failed.",
                    outcomes
                )
            }
        }

        if (trigger.nodeId !in completedNodes) {
            outcomes += WorkflowNodeOutcome(trigger.nodeId, WorkflowNodeState.SUCCEEDED, AgentJsonValue.Obj(emptyList()))
            nodeOutputs[trigger.nodeId] = AgentJsonValue.Obj(emptyList())
            completedNodes += trigger.nodeId
            definition.edges.filter { it.fromNodeId == trigger.nodeId }.forEach { activeEdges += edgeKey(it) }
            persist()?.let { return it }
        }

        for (nodeId in order.filterNot { byId[it]?.type == WorkflowNodeType.COMPENSATE }) {
            if (nodeId == trigger.nodeId || nodeId in completedNodes) continue
            val node = byId.getValue(nodeId)
            val incoming = definition.edges.filter { it.toNodeId == nodeId }
            val activeIncoming = incoming.filter { edgeKey(it) in activeEdges }
            if (activeIncoming.isEmpty()) {
                skipped += nodeId
                outcomes += WorkflowNodeOutcome(nodeId, WorkflowNodeState.SKIPPED)
                completedNodes += nodeId
                persist()?.let { return it }
                continue
            }
            if (node.type == WorkflowNodeType.CONDITION) {
                val route = evaluateConditionRoute(node, nodeOutputs)
                    ?: return fail("WF_EXPR_ERROR", "Condition $nodeId could not be evaluated.", outcomes)
                definition.edges.filter { it.fromNodeId == node.nodeId }.forEach { edge ->
                    if (edge.whenLabel == route) activeEdges += edgeKey(edge)
                }
                outcomes += WorkflowNodeOutcome(nodeId, WorkflowNodeState.SUCCEEDED, AgentJsonValue.Str(route))
                nodeOutputs[nodeId] = AgentJsonValue.Str(route)
                completedNodes += nodeId
                persist()?.let { return it }
                continue
            }
            val inputs = activeIncoming.mapNotNull { nodeOutputs[it.fromNodeId] }
            if (node.type == WorkflowNodeType.EXTRACT) {
                val extracted = WorkflowExtract.execute(node.configJson, inputs)
                if (extracted is WorkflowExtractResult.Error) {
                    return fail(extracted.code, extracted.safeMessage, outcomes)
                }
                val output = (extracted as WorkflowExtractResult.Value).value
                outcomes += WorkflowNodeOutcome(nodeId, WorkflowNodeState.SUCCEEDED, output)
                nodeOutputs[nodeId] = output
                completedNodes += nodeId
                definition.edges.filter { it.fromNodeId == node.nodeId }.forEach { edge ->
                    activeEdges += edgeKey(edge)
                }
                persist()?.let { return it }
                continue
            }
            if (node.type == WorkflowNodeType.LOGIC) {
                val logical = WorkflowLogic.execute(node.configJson, inputs)
                if (logical is WorkflowLogicResult.Error) {
                    return fail(logical.code, logical.safeMessage, outcomes)
                }
                val output = (logical as WorkflowLogicResult.Value).value
                outcomes += WorkflowNodeOutcome(nodeId, WorkflowNodeState.SUCCEEDED, output)
                nodeOutputs[nodeId] = output
                completedNodes += nodeId
                definition.edges.filter { it.fromNodeId == node.nodeId }.forEach { edge ->
                    activeEdges += edgeKey(edge)
                }
                persist()?.let { return it }
                continue
            }
            if (node.type == WorkflowNodeType.WAIT_APPROVAL) {
                // The runner owns the wait contract, not the node executor: a wait has no tool,
                // command or external effect to run, and the answer can only decide the node.
                val decision = pendingDecision
                pendingDecision = null
                when (decision) {
                    null -> return WorkflowGraphResult.SuspendedForUser(
                        WorkflowUserWait.Approval(nodeId),
                        outcomes.toList()
                    )

                    WorkflowUserDecisions.APPROVE -> {
                        val approved = AgentJsonValue.Obj(
                            listOf("approved" to AgentJsonValue.Bool(true))
                        )
                        nodeOutputs[nodeId] = approved
                        outcomes += WorkflowNodeOutcome(nodeId, WorkflowNodeState.SUCCEEDED, approved)
                        completedNodes += nodeId
                        definition.edges.filter { it.fromNodeId == node.nodeId }.forEach { edge ->
                            activeEdges += edgeKey(edge)
                        }
                        persist()?.let { return it }
                        continue
                    }

                    WorkflowUserDecisions.DENY -> return fail(
                        "WF_WAIT_DENIED",
                        "The user declined to approve node $nodeId.",
                        outcomes
                    )

                    else -> return fail(
                        "WF_WAIT_DECISION_INVALID",
                        "The wait answer is not a known decision token.",
                        outcomes
                    )
                }
            }
            val execution = try {
                executor.execute(WorkflowNodeExecutionRequest(node, inputs))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                WorkflowNodeExecution.Failed("WF_NODE_FAILED", "The node executor failed.")
            }
            when (execution) {
                is WorkflowNodeExecution.Succeeded -> {
                    if (node.type == WorkflowNodeType.OUTPUT) {
                        val port = outputPort(node) ?: return fail(
                            "WF_OUTPUT_PORT",
                            "Output node $nodeId has no declared port.",
                            outcomes
                        )
                        outputs[port] = execution.output
                    }
                    nodeOutputs[nodeId] = execution.output
                    outcomes += WorkflowNodeOutcome(nodeId, WorkflowNodeState.SUCCEEDED, execution.output)
                    completedNodes += nodeId
                    definition.edges.filter { it.fromNodeId == node.nodeId }.forEach { edge ->
                        activeEdges += edgeKey(edge)
                    }
                    persist()?.let { return it }
                }
                is WorkflowNodeExecution.WaitingUser -> {
                    // Only facts committed before the child's wait are persisted; the composite node
                    // stays uncommitted so the resume re-enters the same child checkpoint.
                    persist()?.let { return it }
                    return WorkflowGraphResult.SuspendedForUser(execution.wait, outcomes.toList())
                }
                is WorkflowNodeExecution.Failed -> {
                    outcomes += WorkflowNodeOutcome(
                        nodeId,
                        WorkflowNodeState.FAILED,
                        errorCode = execution.errorCode,
                        safeMessage = execution.safeMessage
                    )
                    completedNodes += nodeId
                    persist()?.let { return it }
                    if (definition.failurePolicy == WorkflowFailurePolicy.CONTINUE) {
                        if (firstFailure == null) firstFailure = execution.errorCode to execution.safeMessage
                        continue
                    }
                    if (definition.failurePolicy == WorkflowFailurePolicy.WAIT_FOR_USER) {
                        val decision = pendingDecision
                        pendingDecision = null
                        when (decision) {
                            // The user explicitly chose to continue: the remaining branch runs,
                            // while the committed failure is still reported at the end. The failed
                            // node itself is never re-executed, so no side effect is replayed.
                            WorkflowUserDecisions.CONTINUE -> {
                                if (firstFailure == null) {
                                    firstFailure = execution.errorCode to execution.safeMessage
                                }
                                // The failed node has no output, so its own branch cannot be
                                // activated; independent branches continue and the run still
                                // reports this failure at the end.
                                continue
                            }

                            null -> return WorkflowGraphResult.SuspendedForUser(
                                WorkflowUserWait.NodeFailure(
                                    nodeId,
                                    execution.errorCode,
                                    execution.safeMessage
                                ),
                                outcomes.toList()
                            )

                            else -> return finish(execution.errorCode, execution.safeMessage)
                        }
                    }
                    return finish(execution.errorCode, execution.safeMessage)
                }
                is WorkflowNodeExecution.Suspended ->
                    return WorkflowGraphResult.Suspended(execution.suspension, outcomes.toList())
            }
        }
        firstFailure?.let { failure -> return finish(failure.first, failure.second) }
        val outputsDeclared = definition.outputs.map { it.name }.toSet()
        if (outputs.keys != outputsDeclared) {
            return finish("WF_OUTPUT_MISSING", "The run did not produce every declared output.")
        }
        return WorkflowGraphResult.Completed(outputs = outputs, outcomes = outcomes)
    }

    private fun compensationTarget(node: WorkflowNode): String? {
        val parsed = codec.parse(node.configJson) as? AgentJsonParseResult.Parsed ?: return null
        val fields = (parsed.value as? AgentJsonValue.Obj)?.fields?.toMap() ?: return null
        return (fields["compensates"] as? AgentJsonValue.Str)?.value?.takeIf { it.isNotBlank() }
    }

    private fun edgeKey(edge: WorkflowEdge): String =
        "${edge.fromNodeId}->${edge.toNodeId}:${edge.whenLabel.orEmpty()}"

    private fun outputPort(node: WorkflowNode): String? {
        val parsed = codec.parse(node.configJson) as? AgentJsonParseResult.Parsed ?: return null
        val fields = (parsed.value as? AgentJsonValue.Obj)?.fields?.toMap() ?: return null
        return (fields["port"] as? AgentJsonValue.Str)?.value?.takeIf { it.isNotBlank() }
    }

    private fun evaluateConditionRoute(
        node: WorkflowNode,
        nodeOutputs: Map<String, AgentJsonValue>
    ): String? {
        val parsed = codec.parse(node.configJson) as? AgentJsonParseResult.Parsed ?: return null
        val fields = (parsed.value as? AgentJsonValue.Obj)?.fields?.toMap() ?: return null
        val expressionJson = fields["expression"] as? AgentJsonValue.Obj ?: return null
        val expression = when (val parsedExpression = WorkflowExpressionParser.parse(expressionJson)) {
            is WorkflowExpressionParseResult.Parsed -> parsedExpression.expression
            is WorkflowExpressionParseResult.Invalid -> return null
        }
        val result = WorkflowExpressionEvaluator.evaluateCondition(expression) { reference ->
            resolveReference(reference, nodeOutputs)
        }
        val value = (result as? WorkflowExpressionResult.Value)?.value as? AgentJsonValue.Bool
            ?: return null
        return if (value.value) ROUTE_TRUE else ROUTE_FALSE
    }

    private fun resolveReference(reference: String, nodeOutputs: Map<String, AgentJsonValue>): AgentJsonValue? {
        if (!reference.startsWith("nodes.")) return null
        val rest = reference.removePrefix("nodes.")
        val separator = rest.indexOf('.')
        if (separator <= 0) return null
        val nodeId = rest.substring(0, separator)
        val output = nodeOutputs[nodeId] ?: return null
        val path = rest.substring(separator + 1)
        if (!path.startsWith("outputs.")) return null
        val port = path.removePrefix("outputs.")
        val fields = (output as? AgentJsonValue.Obj)?.fields?.toMap() ?: return null
        return fields[port]
    }

    private fun fail(
        code: String,
        message: String,
        outcomes: List<WorkflowNodeOutcome>
    ): WorkflowGraphResult.Failed =
        WorkflowGraphResult.Failed(code, message, outcomes)

    private companion object {
        const val ROUTE_TRUE = "true"
        const val ROUTE_FALSE = "false"
        const val COMPENSATION_FAILED = "WF_COMPENSATION_FAILED"
    }
}
