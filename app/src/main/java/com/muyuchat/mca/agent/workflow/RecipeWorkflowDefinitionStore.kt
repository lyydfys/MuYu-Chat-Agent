package com.muyuchat.mca.agent.workflow

/**
 * Read-only graph-definition view over the existing manual workflow recipes.
 *
 * Recipes remain the single editable source; this adapter converts them on demand with the frozen
 * [AgentWorkflowRecipeToDefinition] mapping. It deliberately implements no draft/publish mutation:
 * the legacy recipe model has no graph drafts, and inventing a second editable definition store here
 * would create a conflicting writer. Graph runs therefore always read the current recipe version.
 */
class RecipeWorkflowDefinitionStore(
    private val recipes: AgentWorkflowRecipeStore
) : AgentWorkflowDefinitionStore {

    override fun list(): List<AgentWorkflowDefinitionSummary> =
        recipes.list().map { record ->
            AgentWorkflowDefinitionSummary(
                definitionId = definitionId(record.recipe.id),
                displayName = record.recipe.title,
                latestPublishedVersion = record.recipe.version,
                draftRevision = null,
                updatedAtMillis = record.updatedAtMillis
            )
        }

    override fun draft(definitionId: String): AgentWorkflowDefinitionDraft? = null

    override fun saveDraft(
        definition: com.muyuchat.core.agent.contract.workflow.WorkflowDefinition,
        expectedRevision: Long?,
        nowMillis: Long
    ): AgentWorkflowDefinitionDraft = throw UnsupportedOperationException(
        "recipe-backed definitions are edited through the recipe store"
    )

    override fun publish(
        definitionId: String,
        expectedDraftRevision: Long,
        nowMillis: Long,
        referencedDefinitions: Map<
            com.muyuchat.core.agent.runtime.workflow.WorkflowDefinitionValidator.SubworkflowRef,
            com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
            >
    ): com.muyuchat.core.agent.contract.workflow.WorkflowDefinition =
        throw UnsupportedOperationException("recipe-backed definitions are published as recipes")

    override fun latest(definitionId: String): com.muyuchat.core.agent.contract.workflow.WorkflowDefinition? {
        val recipeId = recipeId(definitionId) ?: return null
        val record = recipes.latest(recipeId) ?: return null
        if (!record.active) return null
        return AgentWorkflowRecipeToDefinition.convert(record.recipe)
    }

    override fun version(
        definitionId: String,
        version: Long
    ): com.muyuchat.core.agent.contract.workflow.WorkflowDefinition? {
        val recipeId = recipeId(definitionId) ?: return null
        val record = recipes.latest(recipeId) ?: return null
        if (!record.active || record.recipe.version != version) return null
        return AgentWorkflowRecipeToDefinition.convert(record.recipe)
    }

    companion object {
        private const val PREFIX = "recipe-"

        fun definitionId(recipeId: String): String = PREFIX + recipeId

        fun recipeId(definitionId: String): String? =
            definitionId.removePrefix(PREFIX).takeIf { definitionId.startsWith(PREFIX) }
                ?.takeIf { it.isNotBlank() }
    }
}
