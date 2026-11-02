package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.runtime.workflow.WorkflowDefinitionValidator
import com.muyuchat.core.agent.runtime.workflow.WorkflowValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** N17: a legacy recipe converts into a valid, immutable graph definition. */
class AgentWorkflowRecipeToDefinitionTest {

    @Test
    fun aLegacyRecipeConvertsToAValidTriggerTaskOutputGraph() {
        val recipe = testWorkflowRecipe(
            id = "daily-brief",
            title = "每日简报",
            goalTemplate = "整理 {{input.scope}} 的简报",
            inputs = listOf(testUserInput(id = "scope", label = "范围"))
        )

        val definition = AgentWorkflowRecipeToDefinition.convert(recipe)

        assertEquals("recipe-daily-brief", definition.definitionId)
        assertEquals(recipe.version, definition.version)
        assertEquals("每日简报", definition.displayName)
        assertEquals(listOf("scope"), definition.inputs.map { it.name })
        assertEquals(listOf(AgentWorkflowRecipeToDefinition.RESULT_PORT), definition.outputs.map { it.name })
        assertEquals(listOf("trigger", "run", "done"), definition.nodes.map { it.nodeId })
        assertTrue(definition.nodes.single { it.nodeId == "run" }.configJson.contains("{{input.scope}}"))
        assertTrue(WorkflowDefinitionValidator().validate(definition) is WorkflowValidationResult.Valid)
    }

    @Test
    fun conversionDoesNotMutateTheRecipeAndKeepsItsIdentity() {
        val recipe = testWorkflowRecipe(id = "wf-a", title = "A", goalTemplate = "g", inputs = emptyList())
        val before = recipe.copy()

        val definition = AgentWorkflowRecipeToDefinition.convert(recipe)

        assertEquals(before, recipe)
        assertTrue(definition.definitionHash.isEmpty())
        assertEquals(recipe.version, definition.version)
    }

    @Test
    fun aRecipeWithoutInputsStillProducesAValidGraph() {
        val recipe = testWorkflowRecipe(id = "wf-b", title = "B", goalTemplate = "目标", inputs = emptyList())

        val definition = AgentWorkflowRecipeToDefinition.convert(recipe)

        assertTrue(definition.inputs.isEmpty())
        assertTrue(WorkflowDefinitionValidator().validate(definition) is WorkflowValidationResult.Valid)
    }
}
