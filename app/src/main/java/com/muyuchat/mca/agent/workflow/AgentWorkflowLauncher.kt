package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentCreationReceipt
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentWorkspaceCommands
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.core.agent.contract.StartAgentRun
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** One explicit manual launch request. The model binding is the caller's earlier explicit choice. */
data class AgentWorkflowLaunchRequest(
    val recipeId: String,
    val inputValues: Map<String, String> = emptyMap(),
    val modelBinding: AgentModelBinding
)

sealed interface AgentWorkflowLaunchOutcome {
    data class Started(
        val recipeId: String,
        val recipeVersion: Long,
        val taskId: String,
        val runId: String?
    ) : AgentWorkflowLaunchOutcome

    /** The owner accepted task creation but rejected the start command; no retry is attempted. */
    data class TaskCreatedRunRejected(
        val recipeId: String,
        val recipeVersion: Long,
        val taskId: String,
        val code: String,
        val safeMessage: String
    ) : AgentWorkflowLaunchOutcome

    data class Rejected(
        val code: String,
        val safeMessage: String
    ) : AgentWorkflowLaunchOutcome
}

/** Result of the narrow task-creation gateway; the durable receipt is required before any start. */
sealed interface AgentWorkflowTaskCreation {
    data class Created(val receipt: AgentCreationReceipt) : AgentWorkflowTaskCreation

    data class Rejected(val error: com.muyuchat.core.agent.contract.AgentError) :
        AgentWorkflowTaskCreation

    data class AnchorMissing(val commandId: String) : AgentWorkflowTaskCreation
}

/**
 * The two existing owner calls a manual workflow launch is allowed to make.
 *
 * This is an adapter seam, not a second loop: implementations must only delegate to
 * [AgentWorkspaceCommands.createTask] and [AgentWorkspaceCommands.startRun]. The launcher itself
 * never holds a tool executor, model adapter, scheduler or Android component.
 */
interface AgentWorkflowTaskGateway {
    suspend fun createTask(command: CreateAgentTask): AgentWorkflowTaskCreation

    suspend fun startRun(command: StartAgentRun): AgentCommandResult
}

/** Production gateway over the one Agent owner path. */
class AgentWorkspaceCommandTaskGateway(
    private val commands: AgentWorkspaceCommands
) : AgentWorkflowTaskGateway {

    /**
     * The owner's [AgentCommandResult.Accepted.creationReceipt] is the only task anchor. A create
     * command that reports success without a receipt is reported as [AgentWorkflowTaskCreation.AnchorMissing]
     * so the caller can surface a typed, non-retryable error instead of guessing a task by goal text.
     */
    override suspend fun createTask(command: CreateAgentTask): AgentWorkflowTaskCreation =
        when (val result = commands.createTask(command)) {
            is AgentCommandResult.Accepted -> {
                val receipt = result.creationReceipt
                if (receipt == null) {
                    AgentWorkflowTaskCreation.AnchorMissing(command.commandId)
                } else {
                    AgentWorkflowTaskCreation.Created(receipt)
                }
            }

            is AgentCommandResult.Rejected -> AgentWorkflowTaskCreation.Rejected(result.error)
        }

    override suspend fun startRun(command: StartAgentRun): AgentCommandResult =
        commands.startRun(command)
}

/** Optional model availability seam; null means "not checked" and is reported as such by the UI. */
fun interface AgentWorkflowModelAvailability {
    suspend fun isAvailable(binding: AgentModelBinding): Boolean
}

/**
 * Manual-only workflow launcher.
 *
 * One call performs exactly one preflight, one `CreateAgentTask` and at most one `StartAgentRun`
 * through the injected gateway. There is no retry, no loop, no WorkManager, no scheduler and no
 * direct tool call here: approval, budgets, stop and artifacts stay with the existing run owner.
 */
class AgentWorkflowLauncher(
    private val store: AgentWorkflowRecipeStore,
    private val gateway: AgentWorkflowTaskGateway,
    private val modelAvailability: AgentWorkflowModelAvailability? = null,
    private val commandIdFactory: () -> String = { "wf-" + UUID.randomUUID().toString() }
) {

    suspend fun launch(request: AgentWorkflowLaunchRequest): AgentWorkflowLaunchOutcome {
        val record = try {
            store.latest(request.recipeId)
        } catch (failure: AgentWorkflowException) {
            return AgentWorkflowLaunchOutcome.Rejected(failure.code, failure.safeMessage)
        } catch (failure: Throwable) {
            return rejected(AgentWorkflowErrorCodes.STORE_FAILURE)
        }
        if (record == null) return rejected(AgentWorkflowErrorCodes.NOT_FOUND)

        when (record.state) {
            AgentWorkflowRecipeState.ARCHIVED -> return rejected(AgentWorkflowErrorCodes.ARCHIVED)
            AgentWorkflowRecipeState.REVOKED -> return rejected(AgentWorkflowErrorCodes.REVOKED)
            AgentWorkflowRecipeState.ACTIVE -> Unit
        }

        val preflight = AgentWorkflowPreflight.evaluate(
            recipe = record.recipe,
            inputValues = request.inputValues,
            modelBinding = request.modelBinding
        )
        if (!preflight.canLaunch) {
            val violation = preflight.violations.first()
            return AgentWorkflowLaunchOutcome.Rejected(violation.code, violation.message)
        }

        if (modelAvailability != null) {
            val available = try {
                modelAvailability.isAvailable(request.modelBinding)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                false
            }
            if (!available) {
                return rejected(AgentWorkflowErrorCodes.MODEL_UNAVAILABLE)
            }
        }

        val policy = AgentRunPolicy(
            networkPolicy = AgentNetworkPolicy.LOCAL_ONLY,
            policyVersion = 1L,
            enabledToolKeys = record.recipe.enabledToolKeys.toSet(),
            skillIds = emptySet(),
            offlineForced = true
        )
        val createCommand = CreateAgentTask(
            commandId = commandIdFactory(),
            goal = preflight.resolvedGoal,
            displayTitle = record.recipe.title,
            modelBinding = request.modelBinding,
            policy = policy,
            budget = AgentBudget()
        )

        val creation = try {
            gateway.createTask(createCommand)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: AgentWorkflowException) {
            return AgentWorkflowLaunchOutcome.Rejected(failure.code, failure.safeMessage)
        } catch (_: Throwable) {
            return rejected(AgentWorkflowErrorCodes.LAUNCH_FAILED)
        }

        return when (creation) {
            is AgentWorkflowTaskCreation.Created -> startCreatedTask(record.recipe, creation.receipt)
            is AgentWorkflowTaskCreation.Rejected -> AgentWorkflowLaunchOutcome.Rejected(
                AgentWorkflowErrorCodes.TASK_CREATE_REJECTED,
                creation.error.safeMessage
            )

            is AgentWorkflowTaskCreation.AnchorMissing -> rejected(
                AgentWorkflowErrorCodes.TASK_ANCHOR_MISSING
            )
        }
    }

    private suspend fun startCreatedTask(
        recipe: AgentWorkflowRecipe,
        receipt: AgentCreationReceipt
    ): AgentWorkflowLaunchOutcome {
        val startCommand = StartAgentRun(
            commandId = commandIdFactory(),
            taskId = receipt.taskId,
            expectedTaskRevision = receipt.appliedRevision
        )
        val started = try {
            gateway.startRun(startCommand)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: AgentWorkflowException) {
            return AgentWorkflowLaunchOutcome.TaskCreatedRunRejected(
                recipeId = recipe.id,
                recipeVersion = recipe.version,
                taskId = receipt.taskId,
                code = failure.code,
                safeMessage = failure.safeMessage
            )
        } catch (_: Throwable) {
            return AgentWorkflowLaunchOutcome.TaskCreatedRunRejected(
                recipeId = recipe.id,
                recipeVersion = recipe.version,
                taskId = receipt.taskId,
                code = AgentWorkflowErrorCodes.RUN_START_REJECTED,
                safeMessage = AgentWorkflowSafeMessages.forCode(
                    AgentWorkflowErrorCodes.RUN_START_REJECTED
                )
            )
        }
        return when (started) {
            is AgentCommandResult.Accepted -> AgentWorkflowLaunchOutcome.Started(
                recipeId = recipe.id,
                recipeVersion = recipe.version,
                taskId = receipt.taskId,
                runId = started.runId
            )

            is AgentCommandResult.Rejected -> AgentWorkflowLaunchOutcome.TaskCreatedRunRejected(
                recipeId = recipe.id,
                recipeVersion = recipe.version,
                taskId = receipt.taskId,
                code = AgentWorkflowErrorCodes.RUN_START_REJECTED,
                safeMessage = started.error.safeMessage
            )
        }
    }

    private fun rejected(code: String): AgentWorkflowLaunchOutcome.Rejected =
        AgentWorkflowLaunchOutcome.Rejected(code, AgentWorkflowSafeMessages.forCode(code))
}
