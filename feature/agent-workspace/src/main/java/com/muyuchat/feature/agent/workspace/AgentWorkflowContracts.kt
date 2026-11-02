package com.muyuchat.feature.agent.workspace

/**
 * Feature-local workflow recipe administration port.
 *
 * A recipe is versioned data that later launches the existing task/run owner; it is not a node
 * graph and not a second execution loop. This slice is manual-only by construction: neither the
 * item, the create request nor the port has a trigger, schedule, interval or next-run field. A UI
 * cannot render a schedule it cannot receive, and no WorkManager/worker surface is exposed here.
 *
 * "Manual create" stores a name and a goal template through the host adapter. No task is created or
 * started from this page.
 */
object AgentWorkflowLimits {
    const val MAX_NAME_CHARS: Int = 80
    const val MAX_GOAL_TEMPLATE_CHARS: Int = 4_000
    const val MAX_LABEL_CHARS: Int = 80
}

/**
 * One manual recipe row. There is deliberately no `trigger`, `schedule`, `nextRunAt` or `enabled`
 * field: the current slice must not imply automation it does not own.
 */
data class AgentWorkflowRecipeItem(
    val recipeId: String,
    val name: String,
    val goalTemplate: String,
    val versionLabel: String,
    val updatedLabel: String? = null
) {
    init {
        require(recipeId.isNotBlank()) { "recipeId must not be blank" }
        require(name.isNotBlank()) { "workflow name must not be blank" }
        require(goalTemplate.isNotBlank()) { "workflow goalTemplate must not be blank" }
        require(versionLabel.isNotBlank()) { "versionLabel must not be blank" }
    }
}

sealed interface AgentWorkflowListResult {
    data class Loaded(val recipes: List<AgentWorkflowRecipeItem>) : AgentWorkflowListResult

    data class Rejected(val code: String, val safeMessage: String) : AgentWorkflowListResult {
        init {
            require(code.isNotBlank()) { "list rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "list rejection message must not be blank" }
        }
    }
}

/** Typed create outcomes; [Rejected] is a normal rendered value, never an exception message. */
sealed interface AgentWorkflowAdminOutcome {
    data class Created(val item: AgentWorkflowRecipeItem) : AgentWorkflowAdminOutcome

    data class Rejected(val code: String, val safeMessage: String) : AgentWorkflowAdminOutcome {
        init {
            require(code.isNotBlank()) { "rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "rejection message must not be blank" }
        }
    }
}

/** One immutable-version graph definition row for the management surface. */
data class AgentWorkflowDefinitionItem(
    val definitionId: String,
    val displayName: String,
    val latestPublishedVersion: Long,
    val draftRevision: Long? = null,
    val updatedLabel: String? = null
) {
    init {
        require(definitionId.isNotBlank()) { "definitionId must not be blank" }
        require(displayName.isNotBlank()) { "definition displayName must not be blank" }
        require(latestPublishedVersion >= 0L) { "published version must not be negative" }
        require(draftRevision == null || draftRevision >= 0L) { "draft revision must not be negative" }
    }
}

sealed interface AgentWorkflowDefinitionsResult {
    data class Loaded(
        val definitions: List<AgentWorkflowDefinitionItem>
    ) : AgentWorkflowDefinitionsResult

    data class Rejected(val code: String, val safeMessage: String) : AgentWorkflowDefinitionsResult {
        init {
            require(code.isNotBlank()) { "definitions rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "definitions rejection message must not be blank" }
        }
    }
}

/** Canonical JSON of one editable draft, plus the CAS revision the editor must send back. */
data class AgentWorkflowDraftSnapshot(
    val definitionId: String,
    val revision: Long,
    val definitionJson: String
) {
    init {
        require(definitionId.isNotBlank()) { "draft definitionId must not be blank" }
        require(revision >= 0L) { "draft revision must not be negative" }
        require(definitionJson.isNotBlank()) { "draft JSON must not be blank" }
    }
}

sealed interface AgentWorkflowDraftResult {
    /** No draft was ever saved for this definition. */
    data object Absent : AgentWorkflowDraftResult

    data class Loaded(val draft: AgentWorkflowDraftSnapshot) : AgentWorkflowDraftResult

    data class Saved(val definitionId: String, val revision: Long) : AgentWorkflowDraftResult {
        init {
            require(definitionId.isNotBlank()) { "saved definitionId must not be blank" }
            require(revision >= 0L) { "saved revision must not be negative" }
        }
    }

    data class Rejected(val code: String, val safeMessage: String) : AgentWorkflowDraftResult {
        init {
            require(code.isNotBlank()) { "draft rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "draft rejection message must not be blank" }
        }
    }
}

sealed interface AgentWorkflowPublishOutcome {
    data class Published(
        val definitionId: String,
        val version: Long,
        val definitionHash: String
    ) : AgentWorkflowPublishOutcome {
        init {
            require(definitionId.isNotBlank()) { "published definitionId must not be blank" }
            require(version >= 1L) { "published version must be positive" }
            require(definitionHash.isNotBlank()) { "published hash must not be blank" }
        }
    }

    data class Rejected(val code: String, val safeMessage: String) : AgentWorkflowPublishOutcome {
        init {
            require(code.isNotBlank()) { "publish rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "publish rejection message must not be blank" }
        }
    }
}

/**
 * Manual list/create surface. `create` takes only a display name and a goal template; it has no
 * trigger parameter, so a future scheduled slice must add a separate, explicitly gated contract
 * after the owner-lift ADR instead of silently widening this method.
 *
 * The graph-definition methods are additive defaults: preview/fake ports compile unchanged and
 * report a typed unavailability instead of pretending a draft or a published version exists.
 * Publishing is a CAS over one draft revision; it never rewrites a previously published version,
 * so an already-started run keeps its pinned recipe.
 */
interface AgentWorkflowAdminPort {
    /**
     * Executes the graph view of one manual recipe through the host entry facade.
     *
     * Defaults to a typed rejection so preview/fake ports never pretend a run happened.
     */
    suspend fun runWorkflow(
        recipeId: String,
        version: Long
    ): com.muyuchat.feature.agent.workspace.workflow.WorkflowRunReceipt =
        com.muyuchat.feature.agent.workspace.workflow.WorkflowRunReceipt(
            commandId = "workflow-unavailable",
            definitionId = "recipe-$recipeId",
            version = version.coerceAtLeast(1L),
            trigger = com.muyuchat.feature.agent.workspace.workflow.WorkflowRunTrigger.MANUAL,
            runId = null,
            status = com.muyuchat.feature.agent.workspace.workflow.WorkflowRunReceiptStatus.REJECTED,
            errorCode = "WORKFLOW_RUN_UNAVAILABLE",
            safeMessage = "工作流运行尚未接入。",
            completedAtMillis = System.currentTimeMillis()
        )

    /** Recent graph-run history; empty when the host has none. */
    suspend fun workflowRunHistory(limit: Int): List<
        com.muyuchat.feature.agent.workspace.workflow.WorkflowRunHistoryEntry
        > = emptyList()

    /** Graph definitions that have a draft or a published version. */
    suspend fun workflowDefinitions(): AgentWorkflowDefinitionsResult =
        AgentWorkflowDefinitionsResult.Rejected(
            code = "WORKFLOW_DEFINITIONS_UNAVAILABLE",
            safeMessage = "工作流定义管理尚未接入。"
        )

    /** Reads the editable draft of one definition; [AgentWorkflowDraftResult.Absent] means none. */
    suspend fun workflowDraft(definitionId: String): AgentWorkflowDraftResult =
        AgentWorkflowDraftResult.Rejected(
            code = "WORKFLOW_DEFINITIONS_UNAVAILABLE",
            safeMessage = "工作流定义管理尚未接入。"
        )

    /**
     * Creates or CAS-updates one draft from canonical definition JSON. A non-null
     * [expectedRevision] that no longer matches the stored draft is rejected, never overwritten.
     */
    suspend fun saveWorkflowDraft(
        definitionJson: String,
        expectedRevision: Long?
    ): AgentWorkflowDraftResult = AgentWorkflowDraftResult.Rejected(
        code = "WORKFLOW_DEFINITIONS_UNAVAILABLE",
        safeMessage = "工作流定义管理尚未接入。"
    )

    /** Publishes the current draft as the next immutable version, CAS-bound to [expectedDraftRevision]. */
    suspend fun publishWorkflowDefinition(
        definitionId: String,
        expectedDraftRevision: Long
    ): AgentWorkflowPublishOutcome = AgentWorkflowPublishOutcome.Rejected(
        code = "WORKFLOW_DEFINITIONS_UNAVAILABLE",
        safeMessage = "工作流定义管理尚未接入。"
    )

    suspend fun list(): AgentWorkflowListResult

    suspend fun create(name: String, goalTemplate: String): AgentWorkflowAdminOutcome

    companion object {
        /** Preview/test fallback only; production wiring must provide a real port. */
        val Unavailable: AgentWorkflowAdminPort = object : AgentWorkflowAdminPort {
            override suspend fun list(): AgentWorkflowListResult =
                AgentWorkflowListResult.Rejected(
                    code = "WORKFLOW_ADMIN_UNAVAILABLE",
                    safeMessage = "工作流管理尚未接入。"
                )

            override suspend fun create(
                name: String,
                goalTemplate: String
            ): AgentWorkflowAdminOutcome = AgentWorkflowAdminOutcome.Rejected(
                code = "WORKFLOW_ADMIN_UNAVAILABLE",
                safeMessage = "工作流管理尚未接入。"
            )
        }
    }
}
