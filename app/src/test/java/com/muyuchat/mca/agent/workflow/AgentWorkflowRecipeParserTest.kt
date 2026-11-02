package com.muyuchat.mca.agent.workflow

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkflowRecipeParserTest {

    @Test
    fun validRecipeRoundTripsThroughCanonicalJson() {
        val recipe = testWorkflowRecipe(
            inputs = listOf(testUserInput(), testWorkspaceInput()),
            goalTemplate = "总结 {{input.topic}}，并参考 {{input.brief}}。"
        )
        val encoded = AgentWorkflowRecipeParser.encode(recipe)
        val decoded = AgentWorkflowRecipeParser.parse(encoded)
        assertEquals(recipe, decoded)
        assertEquals(encoded, AgentWorkflowRecipeParser.encode(decoded))
    }

    @Test
    fun unknownExecutableFieldIsRejected() {
        val json = encodedRecipe()
        json.put("script", "print('no')")
        assertCode(AgentWorkflowErrorCodes.RECIPE_INVALID) {
            AgentWorkflowRecipeParser.parse(json.toString())
        }
    }

    @Test
    fun unknownNodeOrTriggerFieldIsRejected() {
        listOf("node", "trigger", "schedule", "entry").forEach { field ->
            val json = encodedRecipe()
            json.put(field, "x")
            assertCode(AgentWorkflowErrorCodes.RECIPE_INVALID) {
                AgentWorkflowRecipeParser.parse(json.toString())
            }
        }
    }

    @Test
    fun unsupportedSchemaVersionIsRejected() {
        val json = encodedRecipe()
        json.put("schemaVersion", 2)
        assertCode(AgentWorkflowErrorCodes.UNSUPPORTED_SCHEMA) {
            AgentWorkflowRecipeParser.parse(json.toString())
        }
    }

    @Test
    fun nonIntegralVersionIsRejected() {
        val json = encodedRecipe()
        json.put("version", 1.5)
        assertCode(AgentWorkflowErrorCodes.RECIPE_INVALID) {
            AgentWorkflowRecipeParser.parse(json.toString())
        }
    }

    @Test
    fun writeToolIsRejectedAtParseTime() {
        val json = encodedRecipe()
        json.put("tools", JSONArray(listOf("artifact.write_text")))
        assertCode(AgentWorkflowErrorCodes.WRITE_TOOL_NOT_ALLOWED) {
            AgentWorkflowRecipeParser.parse(json.toString())
        }
    }

    @Test
    fun unknownToolIsRejectedAtParseTime() {
        val json = encodedRecipe()
        json.put("tools", JSONArray(listOf("mcp:files:read")))
        assertCode(AgentWorkflowErrorCodes.TOOL_NOT_ALLOWED) {
            AgentWorkflowRecipeParser.parse(json.toString())
        }
    }

    @Test
    fun networkEnabledPolicyIsRejected() {
        val json = encodedRecipe()
        json.getJSONObject("modelPolicy").put("networkPolicy", "NETWORK_ENABLED")
        assertCode(AgentWorkflowErrorCodes.NETWORK_POLICY_UNSUPPORTED) {
            AgentWorkflowRecipeParser.parse(json.toString())
        }
    }

    @Test
    fun autoApprovalPolicyIsRejected() {
        val json = encodedRecipe()
        json.put("approvalPolicy", "ALWAYS_ALLOW")
        assertCode(AgentWorkflowErrorCodes.RECIPE_INVALID) {
            AgentWorkflowRecipeParser.parse(json.toString())
        }
    }

    @Test
    fun unknownGoalPlaceholderIsRejected() {
        val json = encodedRecipe()
        json.put("goalTemplate", "总结 {{input.missing}}")
        assertCode(AgentWorkflowErrorCodes.RECIPE_INVALID) {
            AgentWorkflowRecipeParser.parse(json.toString())
        }
    }

    @Test
    fun malformedPlaceholderIsRejected() {
        val json = encodedRecipe()
        json.put("goalTemplate", "总结 {{ input.topic }}")
        assertCode(AgentWorkflowErrorCodes.RECIPE_INVALID) {
            AgentWorkflowRecipeParser.parse(json.toString())
        }
    }

    @Test
    fun duplicateStepIdIsRejected() {
        val json = encodedRecipe()
        val steps = json.getJSONArray("steps")
        steps.put(steps.getJSONObject(0))
        assertCode(AgentWorkflowErrorCodes.DUPLICATE_ID) {
            AgentWorkflowRecipeParser.parse(json.toString())
        }
    }

    @Test
    fun duplicateInputIdIsRejected() {
        val json = encodedRecipe()
        val inputs = json.getJSONArray("inputs")
        inputs.put(inputs.getJSONObject(0))
        assertCode(AgentWorkflowErrorCodes.DUPLICATE_ID) {
            AgentWorkflowRecipeParser.parse(json.toString())
        }
    }

    @Test
    fun duplicateToolKeyIsRejected() {
        val json = encodedRecipe()
        val tools = json.getJSONArray("tools")
        tools.put(tools.getString(0))
        assertCode(AgentWorkflowErrorCodes.RECIPE_INVALID) {
            AgentWorkflowRecipeParser.parse(json.toString())
        }
    }

    @Test
    fun unsortedToolListIsRejected() {
        val json = encodedRecipe()
        json.put(
            "tools",
            JSONArray(listOf("workspace.read_text", "workspace.list"))
        )
        assertCode(AgentWorkflowErrorCodes.RECIPE_INVALID) {
            AgentWorkflowRecipeParser.parse(json.toString())
        }
    }

    @Test
    fun oversizedGoalTemplateIsRejected() {
        val json = encodedRecipe()
        json.put("goalTemplate", "x".repeat(AgentWorkflowRecipeLimits.MAX_GOAL_TEMPLATE_BYTES + 1))
        assertCode(AgentWorkflowErrorCodes.RECIPE_INVALID) {
            AgentWorkflowRecipeParser.parse(json.toString())
        }
    }

    @Test
    fun missingStepsIsRejected() {
        val json = encodedRecipe()
        json.put("steps", JSONArray())
        assertCode(AgentWorkflowErrorCodes.RECIPE_INVALID) {
            AgentWorkflowRecipeParser.parse(json.toString())
        }
    }

    @Test
    fun workspaceInputWithMaxCharsIsRejected() {
        val json = encodedRecipe()
        val input = JSONObject()
            .put("id", "brief")
            .put("kind", "WORKSPACE_INPUT")
            .put("label", "简报")
            .put("path", "inputs/brief.md")
            .put("maxChars", 100)
        json.put("inputs", JSONArray(listOf(input)))
        json.put("goalTemplate", "参考 {{input.brief}}")
        assertCode(AgentWorkflowErrorCodes.RECIPE_INVALID) {
            AgentWorkflowRecipeParser.parse(json.toString())
        }
    }

    @Test
    fun rawNonObjectJsonIsRejected() {
        assertCode(AgentWorkflowErrorCodes.RECIPE_INVALID) {
            AgentWorkflowRecipeParser.parse("[1,2,3]")
        }
    }

    @Test
    fun parserNeverAcceptsAutomationFields() {
        val json = encodedRecipe()
        listOf("cron", "interval", "nextRunAt", "workflowTrigger").forEach { field ->
            json.put(field, "x")
            assertCode(AgentWorkflowErrorCodes.RECIPE_INVALID) {
                AgentWorkflowRecipeParser.parse(json.toString())
            }
            json.remove(field)
        }
    }

    private fun encodedRecipe(): JSONObject =
        JSONObject(AgentWorkflowRecipeParser.encode(testWorkflowRecipe()))

    private fun assertCode(expected: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("expected a typed workflow failure, got $failure", failure is AgentWorkflowException)
        assertEquals(expected, (failure as AgentWorkflowException).code)
    }
}
