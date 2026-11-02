package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentCreationReceipt
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentRunSnapshot
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentWorkspaceCommands
import com.muyuchat.core.agent.contract.AgentWorkspaceQueries
import com.muyuchat.core.agent.contract.AnswerAgentQuestion
import com.muyuchat.core.agent.contract.CancelAgentRun
import com.muyuchat.core.agent.contract.ContinueAgentTask
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.core.agent.contract.PauseAgentRun
import com.muyuchat.core.agent.contract.ResolveAgentApproval
import com.muyuchat.core.agent.contract.StartAgentRun
import com.muyuchat.core.agent.contract.AgentEventPage
import com.muyuchat.core.agent.contract.AgentTaskPage
import com.muyuchat.core.agent.contract.AgentTaskQuery
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.workflow.WorkflowAgentTaskErrorCodes
import com.muyuchat.core.agent.runtime.workflow.WorkflowAgentTaskOutcome
import com.muyuchat.core.agent.runtime.workflow.WorkflowAgentTaskRequest
import com.muyuchat.core.agent.runtime.workflow.WorkspaceCommandWorkflowAgentTaskRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N18/N21 owner adapter: an `AGENT_TASK` node starts its child only through the existing owner
 * command surface, waits for a terminal projection, shares cancellation/budget and never turns a
 * non-success state into a node success.
 */
class WorkflowOwnerAdapterTest {

    private val request = WorkflowAgentTaskRequest(
        workflowRunId = "run-parent",
        nodeId = "agent",
        goal = "summarize",
        modelBinding = testWorkflowBinding(),
        policy = AgentRunPolicy(
            networkPolicy = AgentNetworkPolicy.LOCAL_ONLY,
            offlineForced = true
        ),
        budget = AgentBudget(modelAttempts = 3, toolInvocations = 2),
        displayTitle = "workflow child",
        waitTimeoutMillis = 5_000L
    )

    @Test
    fun theAdapterCreatesAndStartsTheChildThroughTheOwnerThenReturnsItsTerminalOutput() = runBlocking {
        val commands = FakeCommands()
        val queries = FakeQueries { runId ->
            flow {
                emit(snapshot(runId, AgentRunState.QUEUED, revision = 1, spent = AgentSpentBudget()))
                emit(
                    snapshot(
                        runId,
                        AgentRunState.SUCCEEDED,
                        revision = 2,
                        spent = AgentSpentBudget(
                            modelAttempts = 2,
                            toolInvocations = 1,
                            generatedTokens = 321,
                            runActiveTimeMillis = 4_000L
                        )
                    )
                )
            }
        }
        val runner = WorkspaceCommandWorkflowAgentTaskRunner(
            commands = commands,
            queries = queries
        )

        val outcome = runner.run(request) as WorkflowAgentTaskOutcome.Succeeded

        assertEquals("run-1", outcome.runId)
        assertEquals("task-1", outcome.taskId)
        assertEquals(2, outcome.spent.modelAttempts)
        assertEquals(321, outcome.spent.generatedTokens)
        val output = outcome.output as AgentJsonValue.Obj
        val fields = output.fields.toMap()
        assertEquals(AgentJsonValue.Str("run-1"), fields["runId"])
        assertEquals(AgentJsonValue.Str("task-1"), fields["taskId"])
        assertEquals(AgentJsonValue.Str("SUCCEEDED"), fields["state"])

        val create = commands.creates.single()
        assertEquals("summarize", create.goal)
        assertEquals("workflow child", create.displayTitle)
        assertEquals(testWorkflowBinding(), create.modelBinding)
        assertEquals(request.budget, create.budget)
        assertEquals("task-1", commands.starts.single().taskId)
        assertEquals(3L, commands.starts.single().expectedTaskRevision)
        assertTrue("no cancel for a normal success", commands.cancels.isEmpty())
    }

    @Test
    fun theSameParentNodeRetryReusesTheSameChildCommandIdentity() = runBlocking {
        val commands = FakeCommands()
        val runner = WorkspaceCommandWorkflowAgentTaskRunner(
            commands = commands,
            queries = FakeQueries { runId ->
                flow { emit(snapshot(runId, AgentRunState.SUCCEEDED)) }
            }
        )

        runner.run(request)
        runner.run(request)
        runner.run(request.copy(nodeId = "agent-second"))

        assertEquals(3, commands.creates.size)
        assertEquals(
            "a retry must replay the same create command",
            commands.creates[0].commandId,
            commands.creates[1].commandId
        )
        assertEquals(
            "a retry must replay the same start command",
            commands.starts[0].commandId,
            commands.starts[1].commandId
        )
        assertTrue(commands.creates[0].commandId.startsWith("wfchild-"))
        assertTrue(
            "a different node must not share the child identity",
            commands.creates[2].commandId != commands.creates[0].commandId
        )
    }

    @Test
    fun onlyASucceededRunBecomesSuccessAndEveryOtherSettledStateIsTyped() = runBlocking {
        val cases = listOf(
            AgentRunState.FAILED to WorkflowAgentTaskErrorCodes.FAILED,
            AgentRunState.CANCELLED to WorkflowAgentTaskErrorCodes.CANCELLED,
            AgentRunState.SUPERSEDED to WorkflowAgentTaskErrorCodes.SUPERSEDED,
            AgentRunState.PAUSED to WorkflowAgentTaskErrorCodes.PAUSED,
            AgentRunState.NEEDS_REVIEW to WorkflowAgentTaskErrorCodes.NEEDS_REVIEW,
            AgentRunState.INTERRUPTED to WorkflowAgentTaskErrorCodes.INTERRUPTED
        )
        cases.forEach { (state, expectedCode) ->
            val commands = FakeCommands()
            val queries = FakeQueries { runId ->
                flow { emit(snapshot(runId, state, revision = 2, spent = AgentSpentBudget())) }
            }
            val runner = WorkspaceCommandWorkflowAgentTaskRunner(commands, queries)

            val outcome = runner.run(request)

            assertTrue("$state must not be a success", outcome is WorkflowAgentTaskOutcome.Failed)
            assertEquals("$state code", expectedCode, (outcome as WorkflowAgentTaskOutcome.Failed).errorCode)
        }
    }

    @Test
    fun anAcceptedCreateWithoutAReceiptIsAnAnchorFailureAndNoRunIsStarted() = runBlocking {
        val commands = FakeCommands().apply {
            createResult = AgentCommandResult.Accepted(appliedRevision = 0L)
        }
        val runner = WorkspaceCommandWorkflowAgentTaskRunner(
            commands = commands,
            queries = FakeQueries { runId -> flow { emit(snapshot(runId, AgentRunState.SUCCEEDED)) } }
        )

        val outcome = runner.run(request) as WorkflowAgentTaskOutcome.Failed

        assertEquals(WorkflowAgentTaskErrorCodes.ANCHOR_MISSING, outcome.errorCode)
        assertTrue(commands.starts.isEmpty())
    }

    @Test
    fun aRejectedStartIsTypedAndTheCreateErrorIsPassedThrough() = runBlocking {
        val startRejected = FakeCommands().apply {
            startResult = AgentCommandResult.Rejected(rejectedError())
        }
        val startRunner = WorkspaceCommandWorkflowAgentTaskRunner(
            commands = startRejected,
            queries = FakeQueries { runId -> flow { emit(snapshot(runId, AgentRunState.SUCCEEDED)) } }
        )
        assertEquals(
            WorkflowAgentTaskErrorCodes.START_REJECTED,
            (startRunner.run(request) as WorkflowAgentTaskOutcome.Failed).errorCode
        )

        val createRejected = FakeCommands().apply {
            createResult = AgentCommandResult.Rejected(rejectedError())
        }
        val createRunner = WorkspaceCommandWorkflowAgentTaskRunner(
            commands = createRejected,
            queries = FakeQueries { runId -> flow { emit(snapshot(runId, AgentRunState.SUCCEEDED)) } }
        )
        val failure = createRunner.run(request) as WorkflowAgentTaskOutcome.Failed
        assertEquals(WorkflowAgentTaskErrorCodes.CREATE_REJECTED, failure.errorCode)
        assertTrue(createRejected.starts.isEmpty())
    }

    @Test
    fun aWaitThatTimesOutCancelsTheChildAndNeverReportsSuccess() = runBlocking {
        val commands = FakeCommands()
        val queries = FakeQueries { runId ->
            flow {
                emit(snapshot(runId, AgentRunState.QUEUED, revision = 1, spent = AgentSpentBudget()))
                awaitCancellation()
            }
        }
        val runner = WorkspaceCommandWorkflowAgentTaskRunner(commands, queries)

        val outcome = runner.run(request.copy(waitTimeoutMillis = 50L))
                as WorkflowAgentTaskOutcome.Failed

        assertEquals(WorkflowAgentTaskErrorCodes.TIMEOUT, outcome.errorCode)
        assertEquals(1, commands.cancels.size)
        assertEquals("run-1", commands.cancels.single().runId)
        assertEquals(1L, commands.cancels.single().expectedRunRevision)
    }

    @Test
    fun cancellingTheWorkflowCancelsTheChildAndPropagatesCancellation() = runBlocking {
        val commands = FakeCommands()
        val queries = FakeQueries { runId ->
            flow {
                emit(snapshot(runId, AgentRunState.QUEUED, revision = 1, spent = AgentSpentBudget()))
                awaitCancellation()
            }
        }
        val runner = WorkspaceCommandWorkflowAgentTaskRunner(commands, queries)

        val job = launch {
            try {
                runner.run(request)
            } catch (_: CancellationException) {
                throw CancellationException("propagated")
            }
        }
        delay(60)
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(1, commands.cancels.size)
        assertEquals("run-1", commands.cancels.single().runId)
    }

    // ------------------------------------------------------------------ fakes

    private fun rejectedError(): AgentError = AgentError(
        code = "COMMAND_INVALID_STATE",
        category = AgentErrorCategory.INTERNAL,
        retryability = AgentRetryability.NEVER,
        safeMessage = "命令被拒绝。",
        correlationId = "corr-1"
    )

    private fun snapshot(
        runId: String,
        state: AgentRunState,
        revision: Long = 1L,
        spent: AgentSpentBudget = AgentSpentBudget()
    ): AgentRunSnapshot {
        val task = AgentTask(
            taskId = "task-1",
            workspaceId = "workspace-1",
            goal = "summarize",
            lifecycle = AgentTaskLifecycle.ACTIVE,
            createdAtMillis = 1_000L,
            updatedAtMillis = 2_000L,
            displayStatus = AgentDisplayStatus.RUNNING,
            lastRunId = runId,
            revision = 1L
        )
        val run = AgentRun(
            runId = runId,
            taskId = "task-1",
            runOrdinal = 1L,
            state = state,
            reason = com.muyuchat.core.agent.contract.AgentPauseReason.NONE,
            epoch = 1L,
            modelBinding = testWorkflowBinding(),
            policy = AgentRunPolicy(
                networkPolicy = AgentNetworkPolicy.LOCAL_ONLY,
                offlineForced = true
            ),
            budget = AgentBudget(),
            spentBudget = spent,
            lastSequence = 0L,
            revision = revision,
            createdAtMillis = 1_000L,
            updatedAtMillis = 2_000L
        )
        return AgentRunSnapshot(run = run, task = task)
    }

    private class FakeQueries(
        private val flowForRun: (String) -> Flow<AgentRunSnapshot>
    ) : AgentWorkspaceQueries {
        override fun observeTasks(query: AgentTaskQuery): Flow<AgentTaskPage> = emptyFlow()

        override fun observeRun(runId: String): Flow<AgentRunSnapshot> = flowForRun(runId)

        override fun observeEvents(runId: String, afterSequence: Long, limit: Int): Flow<AgentEventPage> =
            emptyFlow()
    }

    private class FakeCommands : AgentWorkspaceCommands {
        var createResult: AgentCommandResult = AgentCommandResult.Accepted(
            appliedRevision = 0L,
            creationReceipt = AgentCreationReceipt(
                creationCommandId = "create-1",
                taskId = "task-1",
                workspaceId = "workspace-1",
                appliedRevision = 3L,
                idempotentReplay = false
            )
        )
        var startResult: AgentCommandResult = AgentCommandResult.Accepted(
            appliedRevision = 1L,
            runId = "run-1"
        )
        val creates = mutableListOf<CreateAgentTask>()
        val starts = mutableListOf<StartAgentRun>()
        val cancels = mutableListOf<CancelAgentRun>()

        override suspend fun createTask(command: CreateAgentTask): AgentCommandResult {
            creates += command
            return createResult
        }

        override suspend fun startRun(command: StartAgentRun): AgentCommandResult {
            starts += command
            return startResult
        }

        override suspend fun requestCancel(command: CancelAgentRun): AgentCommandResult {
            cancels += command
            return AgentCommandResult.Accepted(appliedRevision = 2L)
        }

        override suspend fun requestPause(command: PauseAgentRun): AgentCommandResult = unsupported()

        override suspend fun continueTask(command: ContinueAgentTask): AgentCommandResult = unsupported()

        override suspend fun answerQuestion(command: AnswerAgentQuestion): AgentCommandResult =
            unsupported()

        override suspend fun resolveApproval(command: ResolveAgentApproval): AgentCommandResult =
            unsupported()

        private fun unsupported(): AgentCommandResult =
            AgentCommandResult.Rejected(
                AgentError(
                    code = "COMMAND_INVALID_STATE",
                    category = AgentErrorCategory.INTERNAL,
                    retryability = AgentRetryability.NEVER,
                    safeMessage = "unsupported",
                    correlationId = "corr-unsupported"
                )
            )
    }
}
