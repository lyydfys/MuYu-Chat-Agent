package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentWorkflowRunSpec
import com.muyuchat.core.agent.contract.AgentWorkspaceQueries
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.core.agent.contract.StartAgentRun
import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import com.muyuchat.core.agent.runtime.workflow.WorkflowCheckpointLoad
import com.muyuchat.core.agent.runtime.workflow.WorkflowNodeSuspension
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Production entry that runs a published workflow as one ordinary Agent run (N18/N19/N20).
 *
 * The task and run are created through the existing [AgentWorkspaceCommands]; the run policy pins
 * the immutable definition version, so the owner's executor selects the graph strategy and every
 * tool/approval/ledger fact belongs to a real run. The launcher waits only for a terminal or
 * parked state and never executes a node itself.
 *
 * The entry receipt is a cache, not the authority. Both commands are keyed deterministically by
 * [WorkflowExecutionRequest.runId] with distinct domain prefixes, so a replay after a lost receipt
 * or a process crash resolves through the owner's command ledger to the same task and run instead
 * of creating a second one. A ledger fingerprint conflict (same command id, different content) is
 * returned as a typed rejection before any start is attempted.
 */
class AgentOwnedWorkflowLauncher(
    private val gateway: AgentWorkflowTaskGateway,
    private val queries: AgentWorkspaceQueries,
    private val definitions: AgentWorkflowDefinitionStore,
    private val checkpointRoot: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
) {

    suspend fun run(request: WorkflowExecutionRequest): WorkflowExecutionResult {
        // A request without its own run key has no stable identity to replay; the per-call key keeps
        // the create/start pair correlated but cannot dedupe a later retry. Production entry points
        // (WorkflowEntryService) always pin a run id, including deterministic schedule ids.
        val replayKey = request.runId ?: UUID.randomUUID().toString()
        val definition = try {
            definitions.version(request.definitionId, request.version)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return WorkflowExecutionResult.Rejected(
                request.runId,
                WorkflowExecutionErrorCodes.STORE_FAILURE,
                "The workflow definition store is unavailable."
            )
        } ?: return WorkflowExecutionResult.Rejected(
            request.runId,
            WorkflowExecutionErrorCodes.VERSION_NOT_FOUND,
            "The requested workflow definition version was not found."
        )
        val title = ("Workflow: ${definition.displayName} v${definition.version}").take(MAX_TITLE_CHARS)
        val plan = request.agentTaskPlan
        val policy = (plan?.policy ?: AgentRunPolicy()).copy(
            workflow = AgentWorkflowRunSpec(definition.definitionId, definition.version)
        )
        val created = gateway.createTask(
            CreateAgentTask(
                commandId = workflowCommandId(CREATE_COMMAND_PREFIX, replayKey),
                goal = title,
                displayTitle = title,
                modelBinding = plan?.modelBinding ?: WORKFLOW_BINDING,
                policy = policy,
                budget = AgentBudget()
            )
        )
        val receipt = when (created) {
            is AgentWorkflowTaskCreation.Created -> created.receipt
            is AgentWorkflowTaskCreation.Rejected -> return WorkflowExecutionResult.Rejected(
                request.runId,
                stableCode(created.error.code),
                created.error.safeMessage
            )
            is AgentWorkflowTaskCreation.AnchorMissing -> return WorkflowExecutionResult.Rejected(
                request.runId,
                "WF_TASK_ANCHOR_MISSING",
                "The workflow task was created without a durable anchor."
            )
        }
        val started = gateway.startRun(
            StartAgentRun(
                commandId = workflowCommandId(START_COMMAND_PREFIX, replayKey),
                taskId = receipt.taskId,
                expectedTaskRevision = CREATED_TASK_REVISION
            )
        )
        val runId = when (started) {
            is AgentCommandResult.Accepted -> started.runId
            is AgentCommandResult.Rejected -> return WorkflowExecutionResult.Rejected(
                request.runId,
                stableCode(started.error.code),
                started.error.safeMessage
            )
        } ?: return WorkflowExecutionResult.Rejected(
            request.runId,
            "WF_RUN_NOT_STARTED",
            "The workflow run was accepted without a run id."
        )
        val snapshot = try {
            withTimeoutOrNull(timeoutMillis) {
                queries.observeRun(runId).first { snapshot -> snapshot.run.state.isSettled() }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        } ?: return WorkflowExecutionResult.Rejected(
            runId,
            "WF_RUN_TIMEOUT",
            "The workflow run did not reach a visible state before the launcher timeout."
        )
        return when (snapshot.run.state) {
            AgentRunState.SUCCEEDED -> {
                // A succeeded graph run always wrote its final checkpoint. Reporting COMPLETED with
                // `{}` when that checkpoint is gone would turn lost outputs into a fake success.
                val outputs = checkpointOutputs(runId, receipt.taskId, definition)
                    ?: return WorkflowExecutionResult.Failed(
                        runId = runId,
                        errorCode = "WF_OUTPUT_UNAVAILABLE",
                        safeMessage = "The workflow succeeded but its committed outputs could not be read.",
                        outcomes = emptyList()
                    )
                WorkflowExecutionResult.Completed(runId = runId, outputs = outputs, outcomes = emptyList())
            }
            AgentRunState.WAITING_APPROVAL -> {
                val approval = snapshot.pendingApproval
                    ?: return WorkflowExecutionResult.Rejected(
                        runId,
                        "WF_APPROVAL_UNBOUND",
                        "The workflow run is waiting for an approval that was not recorded."
                    )
                WorkflowExecutionResult.Suspended(
                    runId = runId,
                    suspension = WorkflowNodeSuspension(
                        invocationId = approval.invocationId,
                        callHash = OWNER_RESUME_CALL_HASH,
                        approval = approval
                    ),
                    outcomes = emptyList()
                )
            }
            AgentRunState.FAILED -> WorkflowExecutionResult.Failed(
                runId = runId,
                errorCode = "WF_RUN_FAILED",
                safeMessage = "The workflow run failed; its node facts and ledger are the evidence.",
                outcomes = emptyList()
            )
            AgentRunState.CANCELLED,
            AgentRunState.SUPERSEDED -> WorkflowExecutionResult.Failed(
                runId = runId,
                errorCode = "WF_RUN_CANCELLED",
                safeMessage = "The workflow run was cancelled.",
                outcomes = emptyList()
            )
            else -> WorkflowExecutionResult.Rejected(
                runId,
                "WF_RUN_PARKED",
                "The workflow run is parked; resume it from the Agent run detail."
            )
        }
    }

    private fun checkpointOutputs(
        runId: String,
        taskId: String,
        definition: com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
    ): Map<String, com.muyuchat.core.agent.runtime.AgentJsonValue>? {
        val store = try {
            FileWorkflowCheckpointStore(
                rootDirectory = checkpointRoot,
                key = WorkflowCheckpointKeys.taskRoot(taskId),
                legacyKey = runId
            )
        } catch (_: Throwable) {
            return null
        }
        return when (val load = store.load(definition)) {
            is WorkflowCheckpointLoad.Loaded -> load.checkpoint.outputs
            else -> null
        }
    }

    private fun AgentRunState.isSettled(): Boolean =
        isTerminal ||
            this == AgentRunState.WAITING_APPROVAL ||
            this == AgentRunState.PAUSED ||
            this == AgentRunState.NEEDS_REVIEW ||
            this == AgentRunState.INTERRUPTED

    private fun stableCode(code: String): String {
        val normalized = code.uppercase().filter { it.isLetterOrDigit() || it == '_' }
        return normalized.takeIf { it.length >= 2 } ?: "WF_ENTRY_FAILED"
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30L * 60L * 1000L
        const val MAX_TITLE_CHARS = 200
        const val OWNER_RESUME_CALL_HASH = "owner-resume"
        const val CREATE_COMMAND_PREFIX = "wf-create"
        const val START_COMMAND_PREFIX = "wf-start"

        /**
         * A workflow task is created with revision 0 and no run. The start command pins that
         * creation revision instead of [com.muyuchat.core.agent.contract.AgentCreationReceipt.appliedRevision],
         * because a replayed creation receipt reports the task's CURRENT revision (1 once the first
         * run exists). Deriving the start precondition from it would change the start command's
         * content hash, so the owner's command ledger would answer a harmless replay with an
         * idempotency conflict instead of the original run.
         */
        const val CREATED_TASK_REVISION = 0L

        /**
         * A workflow graph without model nodes never calls a model. The binding is still required by
         * the task contract, so it is explicit and clearly non-runnable instead of borrowing the
         * user's currently selected chat model.
         */
        val WORKFLOW_BINDING = AgentModelBinding(
            providerId = "workflow",
            modelId = "workflow",
            runtime = "none",
            assetFingerprint = "workflow",
            protocolMode = AgentProtocolMode.NONE,
            modelDisplayName = "Workflow"
        )
    }
}

/**
 * Deterministic, bounded idempotency key for one workflow entry command.
 *
 * The domain prefix keeps the create and start command ids distinct; the SHA-256 of the request's
 * run key makes them identical across process restarts and launcher instances. The value matches
 * the Agent id pattern (prefix + 48 lowercase hex characters).
 */
private fun workflowCommandId(domain: String, runKey: String): String =
    domain + "-" + AgentHashes.sha256HexUtf8(runKey).take(48)
