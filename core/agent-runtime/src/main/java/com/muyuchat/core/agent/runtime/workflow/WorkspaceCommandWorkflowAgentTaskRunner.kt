package com.muyuchat.core.agent.runtime.workflow

import com.muyuchat.core.agent.contract.AgentArtifactCommitState
import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentRunSnapshot
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentWorkspaceCommands
import com.muyuchat.core.agent.contract.AgentWorkspaceQueries
import com.muyuchat.core.agent.contract.CancelAgentRun
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.core.agent.contract.StartAgentRun
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Production adapter that runs one `AGENT_TASK` node as a child task through the existing owner.
 *
 * It uses exactly the two command operations a manual launch already uses
 * (`CreateAgentTask` + `StartAgentRun`) plus the read-only run projection, all of which the one
 * `AgentRuntimeAssembly` forwards to the single `AgentRunOwner`. It never constructs an engine,
 * loop, scheduler, tool executor or model adapter, and it never starts a second run in parallel:
 * the graph executor is sequential and awaits the child terminal state before returning.
 *
 * Boundaries shared with the parent workflow:
 * - **Budget**: the parent passes the remaining [AgentBudget] in the request; after the child settles
 *   its [com.muyuchat.core.agent.contract.AgentSpentBudget] is returned so the parent can charge the
 *   shared workflow budget.
 * - **Cancellation**: cancelling the workflow coroutine sends one scoped `CancelAgentRun` for the
 *   child run id/revision (best effort, non-cancellable cleanup) and rethrows, so the child never
 *   outlives the cancelled workflow silently.
 * - **Timeout**: the parent's remaining duration bounds the wait; a timeout cancels the child and
 *   returns the typed [WorkflowAgentTaskErrorCodes.TIMEOUT] failure.
 * - **Deadlock**: the adapter only awaits a read projection and issues commands through the owner's
 *   serial processor. It never holds the owner processor while waiting, so parent and child cannot
 *   wait on each other.
 *
 * A child that settles into a non-success state (FAILED/CANCELLED/SUPERSEDED/PAUSED/NEEDS_REVIEW/
 * INTERRUPTED) is always a typed failure; only `SUCCEEDED` becomes a node success.
 *
 * Cancellation while `createTask`/`startRun` is in flight is propagated as-is. In that narrow window
 * the child may exist but be unobserved; no fake success is returned and the run owner still owns
 * its lifecycle.
 */
class WorkspaceCommandWorkflowAgentTaskRunner(
    private val commands: AgentWorkspaceCommands,
    private val queries: AgentWorkspaceQueries,
    private val displayTitleLimit: Int = 200
) : WorkflowAgentTaskRunner {

    override suspend fun run(request: WorkflowAgentTaskRequest): WorkflowAgentTaskOutcome {
        val creation = try {
            commands.createTask(
                CreateAgentTask(
                    // Stable per (parent run, node): a retry after a crash replays the same child
                    // task instead of creating a second one.
                    commandId = stableCommandId(request, "create"),
                    goal = request.goal,
                    displayTitle = request.displayTitle.take(displayTitleLimit),
                    modelBinding = request.modelBinding,
                    policy = request.policy,
                    budget = request.budget
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return failed(
                WorkflowAgentTaskErrorCodes.CREATE_FAILED,
                "The child task could not be created."
            )
        }
        val receipt = when (creation) {
            is AgentCommandResult.Accepted -> creation.creationReceipt
                ?: return failed(
                    WorkflowAgentTaskErrorCodes.ANCHOR_MISSING,
                    "The child task was accepted without a durable creation receipt."
                )

            is AgentCommandResult.Rejected -> return failed(
                WorkflowAgentTaskErrorCodes.CREATE_REJECTED,
                creation.error.safeMessage
            )
        }

        val started = try {
            commands.startRun(
                StartAgentRun(
                    commandId = stableCommandId(request, "start"),
                    taskId = receipt.taskId,
                    expectedTaskRevision = receipt.appliedRevision
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return failed(
                WorkflowAgentTaskErrorCodes.CREATE_FAILED,
                "The child task was created but the run could not be started."
            )
        }
        val runId = when (started) {
            is AgentCommandResult.Accepted -> started.runId
                ?: return failed(
                    WorkflowAgentTaskErrorCodes.RUN_ID_MISSING,
                    "The run owner accepted the start command without a run id."
                )

            is AgentCommandResult.Rejected -> return failed(
                WorkflowAgentTaskErrorCodes.START_REJECTED,
                started.error.safeMessage
            )
        }

        return awaitTerminal(request, runId)
    }

    private suspend fun awaitTerminal(
        request: WorkflowAgentTaskRequest,
        runId: String
    ): WorkflowAgentTaskOutcome {
        var latest: AgentRunSnapshot? = null
        val settled = try {
            withTimeout(request.waitTimeoutMillis) {
                queries.observeRun(runId)
                    .onEach { snapshot ->
                        if (snapshot.run.runId == runId) latest = snapshot
                    }
                    .first { snapshot ->
                        snapshot.run.runId == runId &&
                            // A parked child (WAITING_USER) is not finished: it still owes an answer,
                            // so it must not release the parent. `!holdsActiveSlot` used to be true
                            // there and let the parent race ahead of a question. Every other settled
                            // state (PAUSED / NEEDS_REVIEW / INTERRUPTED / any terminal) does end the
                            // wait -- the adapter turns those into typed outcomes below.
                            snapshot.run.state.isSettledForWorkflow
                    }
            }
        } catch (_: TimeoutCancellationException) {
            cancelChild(latest)
            return failed(
                WorkflowAgentTaskErrorCodes.TIMEOUT,
                "The child agent task did not settle within the workflow's remaining time."
            )
        } catch (cancelled: CancellationException) {
            // The workflow was cancelled: settle the child through the same owner, then propagate.
            cancelChild(latest)
            throw cancelled
        } catch (_: Throwable) {
            return failed(
                WorkflowAgentTaskErrorCodes.OBSERVER_LOST,
                "The child run could not be observed to a terminal state."
            )
        }

        val run = settled.run
        return when (run.state) {
            AgentRunState.SUCCEEDED -> WorkflowAgentTaskOutcome.Succeeded(
                runId = run.runId,
                taskId = settled.task.taskId,
                output = successOutput(settled),
                spent = run.spentBudget
            )

            AgentRunState.FAILED -> failed(
                WorkflowAgentTaskErrorCodes.FAILED,
                "The child agent task run failed.",
                run.spentBudget
            )

            AgentRunState.CANCELLED -> failed(
                WorkflowAgentTaskErrorCodes.CANCELLED,
                "The child agent task run was cancelled.",
                run.spentBudget
            )

            AgentRunState.SUPERSEDED -> failed(
                WorkflowAgentTaskErrorCodes.SUPERSEDED,
                "The child agent task run was superseded by a newer run.",
                run.spentBudget
            )

            AgentRunState.PAUSED -> failed(
                WorkflowAgentTaskErrorCodes.PAUSED,
                "The child agent task run paused and needs the user to continue it.",
                run.spentBudget
            )

            AgentRunState.NEEDS_REVIEW -> failed(
                WorkflowAgentTaskErrorCodes.NEEDS_REVIEW,
                "The child agent task run needs review before its result can be used.",
                run.spentBudget
            )

            AgentRunState.INTERRUPTED -> failed(
                WorkflowAgentTaskErrorCodes.INTERRUPTED,
                "The child agent task run was interrupted by process recovery.",
                run.spentBudget
            )

            // QUEUED/RUNNING/WAITING_*/PAUSING/STOPPING still hold the active slot; the projection
            // should never settle here, but if it does it is not a success.
            else -> failed(
                WorkflowAgentTaskErrorCodes.NOT_SETTLED,
                "The child agent task reported a non-terminal state.",
                run.spentBudget
            )
        }
    }

    private suspend fun cancelChild(snapshot: AgentRunSnapshot?) {
        val run = snapshot?.run ?: return
        withContext(NonCancellable) {
            try {
                commands.requestCancel(
                    CancelAgentRun(
                        commandId = stableCancelId(run.runId),
                        runId = run.runId,
                        expectedRunRevision = run.revision
                    )
                )
            } catch (_: Throwable) {
                // The owner still owns the run; a rejected/raced cancel only means it already moved.
            }
        }
    }

    private fun successOutput(snapshot: AgentRunSnapshot): AgentJsonValue {
        val run = snapshot.run
        val artifactIds = snapshot.artifacts
            .filter { it.commitState == AgentArtifactCommitState.COMMITTED }
            .map { it.artifactId }
        return AgentJsonValues.obj(
            "runId" to AgentJsonValues.str(run.runId),
            "taskId" to AgentJsonValues.str(snapshot.task.taskId),
            "state" to AgentJsonValues.str(run.state.name),
            "artifactIds" to AgentJsonValues.arr(artifactIds.map(AgentJsonValues::str)),
            "spent" to AgentJsonValues.obj(
                "modelAttempts" to AgentJsonValues.int(run.spentBudget.modelAttempts),
                "toolInvocations" to AgentJsonValues.int(run.spentBudget.toolInvocations),
                "generatedTokens" to AgentJsonValues.int(run.spentBudget.generatedTokens),
                "runActiveTimeMillis" to AgentJsonValues.long(run.spentBudget.runActiveTimeMillis)
            )
        )
    }

    private fun failed(
        code: String,
        message: String,
        spent: AgentSpentBudget = AgentSpentBudget()
    ): WorkflowAgentTaskOutcome.Failed =
        WorkflowAgentTaskOutcome.Failed(code, message.take(MAX_MESSAGE_CHARS), spent)

    /**
     * Deterministic command identity for one child of one workflow run/node.
     *
     * The owner's command ledger replays a repeated command id (same fingerprint) instead of
     * creating a second child task/run, so a retry after a crash reattaches to the existing child.
     * A different node, parent run or operation yields a different id.
     */
    private fun stableCommandId(request: WorkflowAgentTaskRequest, operation: String): String =
        "wfchild-" + AgentHashes.sha256HexUtf8(
            "${request.workflowRunId}\u0000${request.nodeId}\u0000$operation"
        ).take(40)

    private fun stableCancelId(childRunId: String): String =
        "wfchild-" + AgentHashes.sha256HexUtf8("$childRunId\u0000cancel").take(40)

    private companion object {
        const val MAX_MESSAGE_CHARS = 400
    }
}
