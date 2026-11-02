package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentProtocolMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkflowPreflightTest {

    @Test
    fun resolvesUserTextAndWorkspaceInputsIntoTheGoal() {
        val recipe = testWorkflowRecipe(
            inputs = listOf(testUserInput(), testWorkspaceInput()),
            goalTemplate = "总结 {{input.topic}}，并参考 {{input.brief}}。"
        )
        val preflight = AgentWorkflowPreflight.evaluate(
            recipe = recipe,
            inputValues = mapOf("topic" to "发布计划"),
            modelBinding = testWorkflowBinding()
        )
        assertTrue(preflight.canLaunch)
        assertEquals("总结 发布计划，并参考 inputs/brief.md。", preflight.resolvedGoal)
        assertTrue(preflight.violations.isEmpty())
        assertEquals(7, preflight.checks.size)
        assertTrue(preflight.checks.all { it.passed })
    }

    @Test
    fun missingRequiredInputBlocksLaunch() {
        val preflight = AgentWorkflowPreflight.evaluate(
            recipe = testWorkflowRecipe(),
            inputValues = emptyMap(),
            modelBinding = testWorkflowBinding()
        )
        assertFalse(preflight.canLaunch)
        assertEquals(AgentWorkflowErrorCodes.INPUT_REQUIRED, preflight.violations.first().code)
    }

    @Test
    fun optionalMissingInputStillResolves() {
        val preflight = AgentWorkflowPreflight.evaluate(
            recipe = testWorkflowRecipe(inputs = listOf(testUserInput(required = false))),
            inputValues = emptyMap(),
            modelBinding = testWorkflowBinding()
        )
        assertTrue(preflight.canLaunch)
        assertEquals("总结  的要点", preflight.resolvedGoal)
    }

    @Test
    fun unknownInputKeyIsRejected() {
        val preflight = AgentWorkflowPreflight.evaluate(
            recipe = testWorkflowRecipe(),
            inputValues = mapOf("topic" to "计划", "extra" to "x"),
            modelBinding = testWorkflowBinding()
        )
        assertFalse(preflight.canLaunch)
        assertTrue(
            preflight.violations.any { it.code == AgentWorkflowErrorCodes.INPUT_UNKNOWN }
        )
    }

    @Test
    fun oversizedInputIsRejected() {
        val preflight = AgentWorkflowPreflight.evaluate(
            recipe = testWorkflowRecipe(inputs = listOf(testUserInput(maxChars = 5))),
            inputValues = mapOf("topic" to "123456"),
            modelBinding = testWorkflowBinding()
        )
        assertFalse(preflight.canLaunch)
        assertTrue(
            preflight.violations.any { it.code == AgentWorkflowErrorCodes.INPUT_TOO_LONG }
        )
    }

    @Test
    fun controlCharactersInInputAreRejected() {
        val preflight = AgentWorkflowPreflight.evaluate(
            recipe = testWorkflowRecipe(),
            inputValues = mapOf("topic" to "计划\u0000A"),
            modelBinding = testWorkflowBinding()
        )
        assertFalse(preflight.canLaunch)
        assertTrue(
            preflight.violations.any { it.code == AgentWorkflowErrorCodes.INPUT_INVALID }
        )
    }

    @Test
    fun forgedNetworkEnabledRecipeIsAlwaysRejected() {
        val recipe = testWorkflowRecipe(
            modelPolicy = AgentWorkflowModelPolicy(
                networkPolicy = AgentNetworkPolicy.NETWORK_ENABLED,
                allowedProviderIds = emptyList()
            )
        )
        val preflight = AgentWorkflowPreflight.evaluate(
            recipe = recipe,
            inputValues = mapOf("topic" to "计划"),
            modelBinding = testWorkflowBinding()
        )
        assertFalse(preflight.canLaunch)
        assertEquals(
            AgentWorkflowErrorCodes.NETWORK_POLICY_UNSUPPORTED,
            preflight.violations.first().code
        )
        assertTrue(
            preflight.checks.first { it.check == AgentWorkflowCheck.NETWORK_LOCAL_ONLY }.let {
                !it.passed
            }
        )
    }

    @Test
    fun forgedWriteToolIsAlwaysRejected() {
        val recipe = testWorkflowRecipe(
            enabledToolKeys = listOf("artifact.write_text")
        )
        val preflight = AgentWorkflowPreflight.evaluate(
            recipe = recipe,
            inputValues = mapOf("topic" to "计划"),
            modelBinding = testWorkflowBinding()
        )
        assertFalse(preflight.canLaunch)
        assertEquals(
            AgentWorkflowErrorCodes.WRITE_TOOL_NOT_ALLOWED,
            preflight.violations.first().code
        )
    }

    @Test
    fun cloudModelIsRejectedOnALocalOnlyRecipe() {
        val preflight = AgentWorkflowPreflight.evaluate(
            recipe = testWorkflowRecipe(),
            inputValues = mapOf("topic" to "计划"),
            modelBinding = testWorkflowBinding(
                providerId = "cloud:openai",
                runtime = "CLOUD_RESPONSES"
            )
        )
        assertFalse(preflight.canLaunch)
        assertEquals(
            AgentWorkflowErrorCodes.MODEL_REQUIRES_NETWORK,
            preflight.violations.first().code
        )
    }

    @Test
    fun providerAllowlistIsEnforced() {
        val recipe = testWorkflowRecipe(
            modelPolicy = AgentWorkflowModelPolicy(
                networkPolicy = AgentNetworkPolicy.LOCAL_ONLY,
                allowedProviderIds = listOf("local")
            )
        )
        val preflight = AgentWorkflowPreflight.evaluate(
            recipe = recipe,
            inputValues = mapOf("topic" to "计划"),
            modelBinding = testWorkflowBinding(providerId = "other")
        )
        assertFalse(preflight.canLaunch)
        assertEquals(
            AgentWorkflowErrorCodes.MODEL_PROVIDER_NOT_ALLOWED,
            preflight.violations.first().code
        )
    }

    @Test
    fun protocolNoneBlocksLaunch() {
        val preflight = AgentWorkflowPreflight.evaluate(
            recipe = testWorkflowRecipe(),
            inputValues = mapOf("topic" to "计划"),
            modelBinding = testWorkflowBinding(protocolMode = AgentProtocolMode.NONE)
        )
        assertFalse(preflight.canLaunch)
        assertEquals(
            AgentWorkflowErrorCodes.MODEL_BINDING_INVALID,
            preflight.violations.first().code
        )
    }

    @Test
    fun oversizedResolvedGoalIsRejected() {
        val inputs = (1..5).map { index ->
            testUserInput(id = "part$index", label = "片段$index", maxChars = 2_000)
        }
        val recipe = testWorkflowRecipe(
            inputs = inputs,
            goalTemplate = inputs.joinToString(separator = "\n") { "{{input.${it.id}}}" },
            steps = testWorkflowSteps(inputs)
        )
        val values = inputs.associate { it.id to "汉".repeat(2_000) }
        val preflight = AgentWorkflowPreflight.evaluate(
            recipe = recipe,
            inputValues = values,
            modelBinding = testWorkflowBinding()
        )
        assertFalse(preflight.canLaunch)
        assertTrue(
            preflight.violations.any { it.code == AgentWorkflowErrorCodes.GOAL_TOO_LONG }
        )
    }

    @Test
    fun declaredStepsNeverHideMandatoryChecks() {
        val recipe = testWorkflowRecipe(
            steps = listOf(
                AgentWorkflowStep(
                    id = "topic_check",
                    check = AgentWorkflowCheck.INPUTS_RESOLVED,
                    title = "只声明输入检查",
                    inputId = "topic"
                )
            )
        )
        val preflight = AgentWorkflowPreflight.evaluate(
            recipe = recipe,
            inputValues = mapOf("topic" to "计划"),
            modelBinding = testWorkflowBinding()
        )
        assertTrue(preflight.canLaunch)
        assertEquals(
            AgentWorkflowCheck.entries.filterNot { it == AgentWorkflowCheck.INPUTS_RESOLVED }.size + 1,
            preflight.checks.size
        )
        AgentWorkflowCheck.entries.forEach { check ->
            assertTrue(
                "mandatory check $check must be visible",
                preflight.checks.any { it.check == check }
            )
        }
    }
}
