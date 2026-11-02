package com.muyuchat.mca.agent.workflow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecipeWorkflowDefinitionStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun recipeStore(): FileAgentWorkflowRecipeStore =
        FileAgentWorkflowRecipeStore(folder.newFolder("workflows"))

    private fun recipe(id: String = "review", version: Long = 3L): AgentWorkflowRecipe =
        AgentWorkflowRecipe(
            id = id,
            version = version,
            title = "Review notes",
            goalTemplate = "Read notes and summarise",
            steps = listOf(
                AgentWorkflowStep(
                    id = "review",
                    check = AgentWorkflowCheck.GOAL_BOUNDED,
                    title = "Review the goal"
                )
            )
        )

    @Test
    fun `list and version project the converted recipe`() {
        val recipes = recipeStore()
        recipes.publish(recipe(), nowMillis = 1_000L)
        val store = RecipeWorkflowDefinitionStore(recipes)

        val summary = store.list().single()
        assertEquals("recipe-review", summary.definitionId)
        assertEquals("Review notes", summary.displayName)
        assertEquals(3L, summary.latestPublishedVersion)

        val definition = store.version("recipe-review", 3L)
        assertNotNull(definition)
        assertEquals("recipe-review", definition!!.definitionId)
        assertEquals(3L, definition.version)
        assertNull(store.version("recipe-review", 2L))
        assertNotNull(store.latest("recipe-review"))
        assertNull(store.latest("recipe-missing"))
    }

    @Test
    fun `definition ids round trip without stripping unrelated prefixes`() {
        assertEquals("recipe-review", RecipeWorkflowDefinitionStore.definitionId("review"))
        assertEquals("review", RecipeWorkflowDefinitionStore.recipeId("recipe-review"))
        assertNull(RecipeWorkflowDefinitionStore.recipeId("review"))
        assertNull(RecipeWorkflowDefinitionStore.recipeId("recipe-"))
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `graph drafts are not a second writer over recipes`() {
        RecipeWorkflowDefinitionStore(recipeStore()).saveDraft(
            definition = AgentWorkflowRecipeToDefinition.convert(recipe()),
            expectedRevision = null,
            nowMillis = 1L
        )
    }
}
