package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentWorkspaceCommands
import com.muyuchat.core.agent.contract.AnswerAgentQuestion
import com.muyuchat.core.agent.contract.CancelAgentRun
import com.muyuchat.core.agent.contract.ContinueAgentTask
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.core.agent.contract.PauseAgentRun
import com.muyuchat.core.agent.contract.ResolveAgentApproval
import com.muyuchat.core.agent.contract.StartAgentRun
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N01: the workflow launcher must resolve the task created by a create command from the durable
 * creation receipt. An accepted result without a receipt is an anchor failure, never a goal-text
 * guess, and no start command may be issued from that path.
 */
class AgentWorkspaceCommandTaskGatewayTest {

    private class FakeCommands : AgentWorkspaceCommands {
        var createResult: AgentCommandResult = AgentCommandResult.Accepted(
            appliedRevision = 0L,
            creationReceipt = testReceipt()
        )
        var startResult: AgentCommandResult = AgentCommandResult.Accepted(appliedRevision = 1L, runId = "run-1")
        val createCommands = mutableListOf<CreateAgentTask>()
        val startCommands = mutableListOf<StartAgentRun>()
        var otherCalls = 0

        override suspend fun createTask(command: CreateAgentTask): AgentCommandResult {
            createCommands += command
            return createResult
        }

        override suspend fun startRun(command: StartAgentRun): AgentCommandResult {
            startCommands += command
            return startResult
        }

        override suspend fun requestPause(command: PauseAgentRun): AgentCommandResult = unsupported()
        override suspend fun requestCancel(command: CancelAgentRun): AgentCommandResult = unsupported()
        override suspend fun continueTask(command: ContinueAgentTask): AgentCommandResult = unsupported()
        override suspend fun answerQuestion(command: AnswerAgentQuestion): AgentCommandResult = unsupported()
        override suspend fun resolveApproval(command: ResolveAgentApproval): AgentCommandResult = unsupported()

        private fun unsupported(): AgentCommandResult {
            otherCalls++
            return AgentCommandResult.Rejected(commandRejectedError())
        }
    }

    @Test
    fun acceptedCreateWithReceiptReturnsTheSameReceipt() = runBlocking {
        val commands = FakeCommands()
        val gateway = AgentWorkspaceCommandTaskGateway(commands)

        val result = gateway.createTask(createCommand())

        assertTrue(result is AgentWorkflowTaskCreation.Created)
        val receipt = (result as AgentWorkflowTaskCreation.Created).receipt
        assertEquals("task-1", receipt.taskId)
        assertEquals("workspace-1", receipt.workspaceId)
        assertEquals(3L, receipt.appliedRevision)
        assertEquals(1, commands.createCommands.size)
        assertEquals(0, commands.otherCalls)
    }

    @Test
    fun acceptedCreateWithoutReceiptIsAnAnchorFailure() = runBlocking {
        val commands = FakeCommands().apply {
            createResult = AgentCommandResult.Accepted(appliedRevision = 0L)
        }
        val gateway = AgentWorkspaceCommandTaskGateway(commands)

        val result = gateway.createTask(createCommand())

        assertTrue(result is AgentWorkflowTaskCreation.AnchorMissing)
        assertEquals(
            "create-1",
            (result as AgentWorkflowTaskCreation.AnchorMissing).commandId
        )
        assertTrue(commands.startCommands.isEmpty())
    }

    @Test
    fun rejectedCreatePassesTheTypedErrorThrough() = runBlocking {
        val commands = FakeCommands().apply { createResult = AgentCommandResult.Rejected(commandRejectedError()) }
        val gateway = AgentWorkspaceCommandTaskGateway(commands)

        val result = gateway.createTask(createCommand())

        assertTrue(result is AgentWorkflowTaskCreation.Rejected)
        assertEquals(
            "COMMAND_INVALID_STATE",
            (result as AgentWorkflowTaskCreation.Rejected).error.code
        )
        assertTrue(commands.startCommands.isEmpty())
    }

    @Test
    fun launcherStartsExactlyTheReceiptTaskWithoutASecondCreate() = runBlocking {
        val commands = FakeCommands()
        val gateway = AgentWorkspaceCommandTaskGateway(commands)
        val store = InMemoryAgentWorkflowRecipeStore(listOf(testWorkflowRecord(testWorkflowRecipe())))
        val launcher = AgentWorkflowLauncher(store, gateway)

        val outcome = launcher.launch(
            AgentWorkflowLaunchRequest(
                recipeId = "weekly-review",
                inputValues = mapOf("topic" to "计划"),
                modelBinding = testWorkflowBinding()
            )
        )

        assertTrue(outcome is AgentWorkflowLaunchOutcome.Started)
        assertEquals("task-1", (outcome as AgentWorkflowLaunchOutcome.Started).taskId)
        assertEquals("run-1", outcome.runId)
        assertEquals(1, commands.createCommands.size)
        assertEquals(1, commands.startCommands.size)
        assertEquals("task-1", commands.startCommands.single().taskId)
        assertEquals(3L, commands.startCommands.single().expectedTaskRevision)
    }

    private fun createCommand(): CreateAgentTask = CreateAgentTask(
        commandId = "create-1",
        goal = "总结 计划 的要点",
        displayTitle = "每周回顾",
        modelBinding = testWorkflowBinding(),
        policy = AgentRunPolicy(
            networkPolicy = AgentNetworkPolicy.LOCAL_ONLY,
            offlineForced = true
        ),
        budget = AgentBudget()
    )
}

private fun commandRejectedError(): AgentError = AgentError(
    code = "COMMAND_INVALID_STATE",
    category = AgentErrorCategory.INTERNAL,
    retryability = AgentRetryability.NEVER,
    safeMessage = "命令被拒绝。",
    correlationId = "corr-1"
)
