package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.runtime.workflow.WorkflowDefinitionValidator

/**
 * One read surface over two definition writers that never overlap.
 *
 * Legacy manual recipes keep their `recipe-` id namespace and stay authored through the recipe
 * store. Graph drafts/published versions live in the immutable [graphStore]. Reads route by the
 * reserved prefix, so a run entry sees both worlds while a graph id can never shadow a recipe (and
 * vice versa). Graph writes are refused for a recipe id instead of silently creating a second
 * writer over the same logical definition.
 */
class CompositeWorkflowDefinitionStore(
    private val recipes: AgentWorkflowDefinitionStore,
    private val graphStore: AgentWorkflowDefinitionStore
) : AgentWorkflowDefinitionStore {

    override fun list(): List<AgentWorkflowDefinitionSummary> =
        (recipes.list() + graphStore.list())
            .sortedWith(compareBy({ it.displayName.lowercase() }, { it.definitionId }))

    override fun draft(definitionId: String): AgentWorkflowDefinitionDraft? =
        if (isRecipe(definitionId)) null else graphStore.draft(definitionId)

    override fun saveDraft(
        definition: WorkflowDefinition,
        expectedRevision: Long?,
        nowMillis: Long
    ): AgentWorkflowDefinitionDraft {
        if (definition.definitionId.isBlank()) {
            workflowFailure(AgentWorkflowErrorCodes.RECIPE_INVALID, "A graph definition needs an id.")
        }
        requireGraphId(definition.definitionId, "save")
        return graphStore.saveDraft(definition, expectedRevision, nowMillis)
    }

    override fun publish(
        definitionId: String,
        expectedDraftRevision: Long,
        nowMillis: Long,
        referencedDefinitions: Map<WorkflowDefinitionValidator.SubworkflowRef, WorkflowDefinition>
    ): WorkflowDefinition {
        requireGraphId(definitionId, "publish")
        return graphStore.publish(definitionId, expectedDraftRevision, nowMillis, referencedDefinitions)
    }

    override fun latest(definitionId: String): WorkflowDefinition? =
        if (isRecipe(definitionId)) recipes.latest(definitionId) else graphStore.latest(definitionId)

    override fun version(definitionId: String, version: Long): WorkflowDefinition? =
        if (isRecipe(definitionId)) {
            recipes.version(definitionId, version)
        } else {
            graphStore.version(definitionId, version)
        }

    private fun isRecipe(definitionId: String): Boolean =
        RecipeWorkflowDefinitionStore.recipeId(definitionId) != null

    private fun requireGraphId(definitionId: String, operation: String) {
        if (isRecipe(definitionId)) {
            workflowFailure(
                AgentWorkflowErrorCodes.RECIPE_INVALID,
                "Legacy recipe definitions are edited through the recipe store; a graph $operation is refused."
            )
        }
    }
}
