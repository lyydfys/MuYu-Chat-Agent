package com.muyuchat.feature.agent.workspace

import com.muyuchat.core.agent.contract.AgentModelBinding

/**
 * Feature-local manual-launch surface for a task recipe.
 *
 * It is deliberately separate from [AgentWorkflowAdminPort] (recipe list/manual create): launch is
 * an explicit second user action that creates one task and starts one run through the existing
 * Agent owner. There is no trigger, schedule, interval or background field in this contract, and
 * the page cannot call the owner directly.
 */

/** One declared recipe input as the preview needs to render it. */
data class AgentWorkflowLaunchInputUi(
    val inputId: String,
    val label: String,
    val editable: Boolean,
    val required: Boolean,
    val maxChars: Int?,
    val pathHint: String? = null
) {
    init {
        require(inputId.isNotBlank()) { "workflow input id must not be blank" }
        require(label.isNotBlank()) { "workflow input label must not be blank" }
        require(maxChars == null || maxChars > 0) { "workflow input maxChars must be positive" }
    }
}

/** One declarative preflight row; never an executor status. */
data class AgentWorkflowLaunchCheckUi(
    val title: String,
    val passed: Boolean,
    val detail: String? = null
)

/** Pure preview of a recipe resolved with the current inputs and explicit model choice. */
data class AgentWorkflowLaunchPreviewUi(
    val recipeId: String,
    val versionLabel: String,
    val title: String,
    val goal: String,
    val modelLabel: String,
    val inputs: List<AgentWorkflowLaunchInputUi>,
    val checks: List<AgentWorkflowLaunchCheckUi>,
    val canCreate: Boolean,
    val blockedMessage: String? = null
) {
    init {
        require(recipeId.isNotBlank()) { "workflow recipeId must not be blank" }
        require(title.isNotBlank()) { "workflow title must not be blank" }
        require(versionLabel.isNotBlank()) { "workflow version label must not be blank" }
    }
}

sealed interface AgentWorkflowLaunchPreviewResult {
    data class Loaded(val preview: AgentWorkflowLaunchPreviewUi) : AgentWorkflowLaunchPreviewResult

    data class Rejected(val code: String, val safeMessage: String) : AgentWorkflowLaunchPreviewResult {
        init {
            require(code.isNotBlank()) { "preview rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "preview rejection message must not be blank" }
        }
    }
}

/** Outcomes of the one explicit manual launch. [Started] means task + run were accepted. */
sealed interface AgentWorkflowLaunchOutcome {
    data class Started(val taskId: String, val runId: String?) : AgentWorkflowLaunchOutcome {
        init {
            require(taskId.isNotBlank()) { "started taskId must not be blank" }
        }
    }

    /** The task exists but the owner rejected the start; this is surfaced, never retried. */
    data class TaskCreatedRunRejected(
        val taskId: String,
        val code: String,
        val safeMessage: String
    ) : AgentWorkflowLaunchOutcome {
        init {
            require(taskId.isNotBlank()) { "created taskId must not be blank" }
            require(code.isNotBlank()) { "run rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "run rejection message must not be blank" }
        }
    }

    data class Rejected(val code: String, val safeMessage: String) : AgentWorkflowLaunchOutcome {
        init {
            require(code.isNotBlank()) { "launch rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "launch rejection message must not be blank" }
        }
    }
}

/**
 * Narrow preview + manual-launch port. [model] is the user's explicit current model choice; the
 * host validates it against the recipe policy and freezes it into the created task. No port method
 * accepts a task id, run id, tool chain or schedule.
 */
interface AgentWorkflowLaunchPort {
    suspend fun preview(
        recipeId: String,
        inputValues: Map<String, String>,
        model: AgentModelBinding
    ): AgentWorkflowLaunchPreviewResult

    suspend fun createManualTask(
        recipeId: String,
        inputValues: Map<String, String>,
        model: AgentModelBinding
    ): AgentWorkflowLaunchOutcome

    companion object {
        /** Preview/test fallback only; production wiring must provide a real port. */
        val Unavailable: AgentWorkflowLaunchPort = object : AgentWorkflowLaunchPort {
            override suspend fun preview(
                recipeId: String,
                inputValues: Map<String, String>,
                model: AgentModelBinding
            ): AgentWorkflowLaunchPreviewResult = AgentWorkflowLaunchPreviewResult.Rejected(
                code = "WORKFLOW_LAUNCH_UNAVAILABLE",
                safeMessage = "任务模板启动尚未接入。"
            )

            override suspend fun createManualTask(
                recipeId: String,
                inputValues: Map<String, String>,
                model: AgentModelBinding
            ): AgentWorkflowLaunchOutcome = AgentWorkflowLaunchOutcome.Rejected(
                code = "WORKFLOW_LAUNCH_UNAVAILABLE",
                safeMessage = "任务模板启动尚未接入。"
            )
        }
    }
}
