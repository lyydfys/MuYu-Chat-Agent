package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentCreationReceipt
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.core.agent.contract.StartAgentRun
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkflowLauncherTest {

    private val recipe = testWorkflowRecipe()

    private class FakeGateway : AgentWorkflowTaskGateway {
        var createCount = 0
        var startCount = 0
        val createCommands = mutableListOf<CreateAgentTask>()
        val startCommands = mutableListOf<StartAgentRun>()
        var creationFactory: (CreateAgentTask) -> AgentWorkflowTaskCreation = {
            AgentWorkflowTaskCreation.Created(testReceipt())
        }
        var startResult: AgentCommandResult =
            AgentCommandResult.Accepted(appliedRevision = 4L, runId = "run-1")

        override suspend fun createTask(command: CreateAgentTask): AgentWorkflowTaskCreation {
            createCount++
            createCommands += command
            return creationFactory(command)
        }

        override suspend fun startRun(command: StartAgentRun): AgentCommandResult {
            startCount++
            startCommands += command
            return startResult
        }
    }

    @Test
    fun launchDelegatesCreateAndStartExactlyOnce() = runBlocking {
        val store = InMemoryAgentWorkflowRecipeStore(listOf(testWorkflowRecord(recipe)))
        val gateway = FakeGateway()
        val launcher = AgentWorkflowLauncher(store, gateway)

        val outcome = launcher.launch(
            AgentWorkflowLaunchRequest(
                recipeId = recipe.id,
                inputValues = mapOf("topic" to "计划"),
                modelBinding = testWorkflowBinding()
            )
        )

        assertTrue(outcome is AgentWorkflowLaunchOutcome.Started)
        outcome as AgentWorkflowLaunchOutcome.Started
        assertEquals("task-1", outcome.taskId)
        assertEquals("run-1", outcome.runId)
        assertEquals(1, gateway.createCount)
        assertEquals(1, gateway.startCount)

        val create = gateway.createCommands.single()
        assertEquals("总结 计划 的要点", create.goal)
        assertEquals("每周回顾", create.displayTitle)
        assertEquals(AgentNetworkPolicy.LOCAL_ONLY, create.policy.networkPolicy)
        assertTrue(create.policy.offlineForced)
        assertEquals(AgentWorkflowToolAllowlist.READ_ONLY_TOOL_KEYS.toSet(), create.policy.enabledToolKeys)
        assertTrue(create.policy.skillIds.isEmpty())
        assertEquals("local", create.modelBinding.providerId)

        val start = gateway.startCommands.single()
        assertEquals("task-1", start.taskId)
        assertEquals(3L, start.expectedTaskRevision)
        assertTrue(start.commandId != create.commandId)
    }

    @Test
    fun startRejectionSurfacesTheCreatedTaskAndNeverRetries() = runBlocking {
        val store = InMemoryAgentWorkflowRecipeStore(listOf(testWorkflowRecord(recipe)))
        val gateway = FakeGateway().apply {
            startResult = AgentCommandResult.Rejected(
                AgentError(
                    code = "COMMAND_INVALID_STATE",
                    category = AgentErrorCategory.INTERNAL,
                    retryability = AgentRetryability.NEVER,
                    safeMessage = "任务当前不能启动。",
                    correlationId = "corr-1"
                )
            )
        }
        val launcher = AgentWorkflowLauncher(store, gateway)

        val outcome = launcher.launch(launchRequest())

        assertTrue(outcome is AgentWorkflowLaunchOutcome.TaskCreatedRunRejected)
        outcome as AgentWorkflowLaunchOutcome.TaskCreatedRunRejected
        assertEquals("task-1", outcome.taskId)
        assertEquals(AgentWorkflowErrorCodes.RUN_START_REJECTED, outcome.code)
        assertEquals(1, gateway.createCount)
        assertEquals(1, gateway.startCount)
    }

    @Test
    fun createRejectionStartsNothing() = runBlocking {
        val store = InMemoryAgentWorkflowRecipeStore(listOf(testWorkflowRecord(recipe)))
        val gateway = FakeGateway().apply {
            creationFactory = { AgentWorkflowTaskCreation.Rejected(testError()) }
        }
        val launcher = AgentWorkflowLauncher(store, gateway)

        val outcome = launcher.launch(launchRequest())

        assertTrue(outcome is AgentWorkflowLaunchOutcome.Rejected)
        assertEquals(AgentWorkflowErrorCodes.TASK_CREATE_REJECTED, (outcome as AgentWorkflowLaunchOutcome.Rejected).code)
        assertEquals(1, gateway.createCount)
        assertEquals(0, gateway.startCount)
    }

    @Test
    fun missingTaskAnchorStartsNothing() = runBlocking {
        val store = InMemoryAgentWorkflowRecipeStore(listOf(testWorkflowRecord(recipe)))
        val gateway = FakeGateway().apply {
            creationFactory = { command -> AgentWorkflowTaskCreation.AnchorMissing(command.commandId) }
        }
        val launcher = AgentWorkflowLauncher(store, gateway)

        val outcome = launcher.launch(launchRequest())

        assertEquals(
            AgentWorkflowErrorCodes.TASK_ANCHOR_MISSING,
            (outcome as AgentWorkflowLaunchOutcome.Rejected).code
        )
        assertEquals(1, gateway.createCount)
        assertEquals(0, gateway.startCount)
    }

    @Test
    fun archivedRecipeNeverReachesTheOwner() = runBlocking {
        val store = InMemoryAgentWorkflowRecipeStore(listOf(testWorkflowRecord(recipe)))
        store.archive(recipe.id, 1_760_000_100_000L)
        val gateway = FakeGateway()
        val launcher = AgentWorkflowLauncher(store, gateway)

        val outcome = launcher.launch(launchRequest())

        assertEquals(
            AgentWorkflowErrorCodes.ARCHIVED,
            (outcome as AgentWorkflowLaunchOutcome.Rejected).code
        )
        assertEquals(0, gateway.createCount)
        assertEquals(0, gateway.startCount)
    }

    @Test
    fun revokedRecipeNeverReachesTheOwner() = runBlocking {
        val store = InMemoryAgentWorkflowRecipeStore(listOf(testWorkflowRecord(recipe)))
        store.revoke(recipe.id, 1_760_000_100_000L)
        val gateway = FakeGateway()
        val launcher = AgentWorkflowLauncher(store, gateway)

        val outcome = launcher.launch(launchRequest())

        assertEquals(
            AgentWorkflowErrorCodes.REVOKED,
            (outcome as AgentWorkflowLaunchOutcome.Rejected).code
        )
        assertEquals(0, gateway.createCount)
    }

    @Test
    fun missingRecipeNeverReachesTheOwner() = runBlocking {
        val store = InMemoryAgentWorkflowRecipeStore(emptyList())
        val gateway = FakeGateway()
        val launcher = AgentWorkflowLauncher(store, gateway)

        val outcome = launcher.launch(launchRequest())

        assertEquals(
            AgentWorkflowErrorCodes.NOT_FOUND,
            (outcome as AgentWorkflowLaunchOutcome.Rejected).code
        )
        assertEquals(0, gateway.createCount)
        assertEquals(0, gateway.startCount)
    }

    @Test
    fun forgedWriteToolNeverReachesTheOwner() = runBlocking {
        val forged = testWorkflowRecipe(enabledToolKeys = listOf("artifact.write_text"))
        val store = InMemoryAgentWorkflowRecipeStore(listOf(testWorkflowRecord(forged)))
        val gateway = FakeGateway()
        val launcher = AgentWorkflowLauncher(store, gateway)

        val outcome = launcher.launch(launchRequest())

        assertEquals(
            AgentWorkflowErrorCodes.WRITE_TOOL_NOT_ALLOWED,
            (outcome as AgentWorkflowLaunchOutcome.Rejected).code
        )
        assertEquals(0, gateway.createCount)
    }

    @Test
    fun forgedNetworkPolicyNeverReachesTheOwner() = runBlocking {
        val forged = testWorkflowRecipe(
            modelPolicy = AgentWorkflowModelPolicy(
                networkPolicy = AgentNetworkPolicy.NETWORK_ENABLED,
                allowedProviderIds = emptyList()
            )
        )
        val store = InMemoryAgentWorkflowRecipeStore(listOf(testWorkflowRecord(forged)))
        val gateway = FakeGateway()
        val launcher = AgentWorkflowLauncher(store, gateway)

        val outcome = launcher.launch(launchRequest())

        assertEquals(
            AgentWorkflowErrorCodes.NETWORK_POLICY_UNSUPPORTED,
            (outcome as AgentWorkflowLaunchOutcome.Rejected).code
        )
        assertEquals(0, gateway.createCount)
    }

    @Test
    fun cloudModelNeverReachesTheOwnerOnALocalOnlyRecipe() = runBlocking {
        val store = InMemoryAgentWorkflowRecipeStore(listOf(testWorkflowRecord(recipe)))
        val gateway = FakeGateway()
        val launcher = AgentWorkflowLauncher(store, gateway)

        val outcome = launcher.launch(
            launchRequest().copy(
                modelBinding = testWorkflowBinding(
                    providerId = "cloud:openai",
                    runtime = "CLOUD_RESPONSES"
                )
            )
        )

        assertEquals(
            AgentWorkflowErrorCodes.MODEL_REQUIRES_NETWORK,
            (outcome as AgentWorkflowLaunchOutcome.Rejected).code
        )
        assertEquals(0, gateway.createCount)
    }

    @Test
    fun unavailableModelNeverReachesTheOwner() = runBlocking {
        val store = InMemoryAgentWorkflowRecipeStore(listOf(testWorkflowRecord(recipe)))
        val gateway = FakeGateway()
        val launcher = AgentWorkflowLauncher(
            store = store,
            gateway = gateway,
            modelAvailability = AgentWorkflowModelAvailability { false }
        )

        val outcome = launcher.launch(launchRequest())

        assertEquals(
            AgentWorkflowErrorCodes.MODEL_UNAVAILABLE,
            (outcome as AgentWorkflowLaunchOutcome.Rejected).code
        )
        assertEquals(0, gateway.createCount)
    }

    @Test
    fun missingRequiredInputNeverReachesTheOwner() = runBlocking {
        val store = InMemoryAgentWorkflowRecipeStore(listOf(testWorkflowRecord(recipe)))
        val gateway = FakeGateway()
        val launcher = AgentWorkflowLauncher(store, gateway)

        val outcome = launcher.launch(launchRequest().copy(inputValues = emptyMap()))

        assertEquals(
            AgentWorkflowErrorCodes.INPUT_REQUIRED,
            (outcome as AgentWorkflowLaunchOutcome.Rejected).code
        )
        assertEquals(0, gateway.createCount)
    }

    @Test
    fun createExceptionNeverStartsARun() = runBlocking {
        val store = InMemoryAgentWorkflowRecipeStore(listOf(testWorkflowRecord(recipe)))
        val gateway = FakeGateway().apply {
            creationFactory = { throw AgentWorkflowException(AgentWorkflowErrorCodes.LAUNCH_FAILED, "boom") }
        }
        val launcher = AgentWorkflowLauncher(store, gateway)

        val outcome = launcher.launch(launchRequest())

        assertTrue(outcome is AgentWorkflowLaunchOutcome.Rejected)
        assertEquals(0, gateway.startCount)
    }

    @Test
    fun defaultPolicyFreezesReadOnlyLocalOnlyOnEveryLaunch() {
        val store = InMemoryAgentWorkflowRecipeStore(listOf(testWorkflowRecord(recipe)))
        val gateway = FakeGateway()
        val launcher = AgentWorkflowLauncher(store, gateway)
        runBlocking {
            launcher.launch(launchRequest())
            launcher.launch(launchRequest())
        }
        assertEquals(2, gateway.createCommands.size)
        gateway.createCommands.forEach { command ->
            assertEquals(AgentNetworkPolicy.LOCAL_ONLY, command.policy.networkPolicy)
            assertTrue(command.policy.offlineForced)
            assertTrue(command.policy.enabledToolKeys!!.all {
                it in AgentWorkflowToolAllowlist.READ_ONLY_TOOL_KEYS
            })
            assertFalse(command.policy.enabledToolKeys!!.any { it == "artifact.write_text" })
        }
    }

    private fun launchRequest(): AgentWorkflowLaunchRequest = AgentWorkflowLaunchRequest(
        recipeId = recipe.id,
        inputValues = mapOf("topic" to "计划"),
        modelBinding = testWorkflowBinding()
    )

    private fun testError(): AgentError = AgentError(
        code = "COMMAND_INVALID_STATE",
        category = AgentErrorCategory.INTERNAL,
        retryability = AgentRetryability.NEVER,
        safeMessage = "任务创建被拒绝。",
        correlationId = "corr-1"
    )
}

/** The durable creation anchor the production owner now returns (N01). */
internal fun testReceipt(
    commandId: String = "wf-create-1",
    taskId: String = "task-1",
    workspaceId: String = "workspace-1",
    appliedRevision: Long = 3L,
    idempotentReplay: Boolean = false
): AgentCreationReceipt = AgentCreationReceipt(
    creationCommandId = commandId,
    taskId = taskId,
    workspaceId = workspaceId,
    appliedRevision = appliedRevision,
    idempotentReplay = idempotentReplay
)
