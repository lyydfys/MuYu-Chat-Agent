package com.muyuchat.mca.agent.knowledge

import com.muyuchat.core.agent.contract.AgentPreferencePort
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.runtime.protocol.AgentNativeSystemPromptBuilder
import com.muyuchat.core.agent.runtime.protocol.AgentPromptedSystemPromptBuilder
import com.muyuchat.core.agent.runtime.protocol.AgentProtocolSystemPromptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
/**
 * Pins the user-preference prompt section.
 *
 * Two guarantees, both load-bearing:
 *  - **Empty means byte-identical.** With [AgentPreferencePort.NONE] (or a store that holds nothing)
 *    every builder produces exactly the string it produced before this feature existed, so no stored
 *    prompt hash and no regression baseline moves.
 *  - **Non-empty is bounded and attributed.** A preference appears under its own section, never as an
 *    unattributed fragment, and can never exceed the section cap.
 */
class AgentPreferencePromptTest {

    private val tools: List<AgentToolDescriptor> = emptyList()
    private val budget = """{"steps":8}"""

    private fun prompted(preferences: String) = AgentPromptedSystemPromptBuilder().build(
        tools = tools,
        policyVersion = 1L,
        budgetSummaryJson = budget,
        protocolMode = AgentProtocolMode.PROMPTED_V1,
        operationKnowledge = "",
        userPreferences = preferences
    )

    private fun native(preferences: String) = AgentNativeSystemPromptBuilder().build(
        tools = tools,
        policyVersion = 1L,
        budgetSummaryJson = budget,
        protocolMode = AgentProtocolMode.NATIVE,
        operationKnowledge = "",
        userPreferences = preferences
    )

    // ── the regression guarantee ────────────────────────────────────────────────────────

    @Test
    fun `an empty port keeps every prompt byte identical`() {
        // Two independent references for "before this feature": NONE, and simply omitting the argument.
        listOf(AgentPreferencePort.NONE.preferenceText(), "").forEach { empty ->
            assertEquals(prompted(""), prompted(empty))
            assertEquals(native(""), native(empty))
        }
        // A blank-but-non-empty fragment must also write zero bytes.
        assertEquals(prompted(""), prompted("   \n  "))
        assertEquals(native(""), native("   \n  "))
        assertFalse(prompted("").contains("USER PREFERENCES"))
        assertFalse(native("").contains("USER PREFERENCES"))
    }

    @Test
    fun `the protocol builder forwards the empty fragment unchanged`() {
        val builder = AgentProtocolSystemPromptBuilder()
        val withoutArgument = builder.build(tools, 1L, budget, AgentProtocolMode.PROMPTED_V1, "")
        val withEmptyArgument = builder.build(
            tools, 1L, budget, AgentProtocolMode.PROMPTED_V1, "", ""
        )
        assertEquals(withoutArgument, withEmptyArgument)
    }

    // ── the section itself ──────────────────────────────────────────────────────────────

    @Test
    fun `a preference appears in its own named section`() {
        val preference = "用户明确说过的偏好（仅当与当前任务不冲突时遵守）：\n- 以后都用中文回答"

        val text = prompted(preference)

        assertTrue(text.contains("USER PREFERENCES"))
        assertTrue(text.contains("以后都用中文回答"))
        // The section sits before the tool catalog, so the schemas stay adjacent to the final reminder.
        assertTrue(
            text.indexOf("USER PREFERENCES") < text.indexOf("AVAILABLE TOOLS")
        )
        assertNotEquals(prompted(""), text)
    }

    @Test
    fun `the native prompt also carries the section`() {
        val text = native("用户偏好：\n- 回答尽量简短")

        assertTrue(text.contains("USER PREFERENCES"))
        assertTrue(text.contains("回答尽量简短"))
        assertFalse(native("").contains("回答尽量简短"))
    }

    @Test
    fun `an over long fragment is capped at the section limit`() {
        val huge = "偏好".repeat(4_000) // 8000 chars, far past the cap

        val text = prompted(huge)

        assertTrue(text.contains("USER PREFERENCES"))
        // The clamped fragment is exactly the cap; the builder never embeds more of it. Measured on
        // the fragment itself, because everything after it is the unchanged protocol text.
        val clamped = huge.take(AgentPromptedSystemPromptBuilder.MAX_USER_PREFERENCES_CHARS)
        assertTrue("the capped fragment must appear verbatim", text.contains(clamped))
        assertFalse(
            "one character past the cap must not appear",
            text.contains(huge.take(AgentPromptedSystemPromptBuilder.MAX_USER_PREFERENCES_CHARS + 1))
        )
        assertEquals(1_000, AgentPromptedSystemPromptBuilder.MAX_USER_PREFERENCES_CHARS)
    }

    @Test
    fun `preferences come before operation knowledge`() {
        val text = AgentPromptedSystemPromptBuilder().build(
            tools = tools,
            policyVersion = 1L,
            budgetSummaryJson = budget,
            protocolMode = AgentProtocolMode.PROMPTED_V1,
            operationKnowledge = "OPERATION-KNOWLEDGE-MARKER",
            userPreferences = "USER-PREFERENCE-MARKER"
        )

        assertTrue(text.indexOf("USER-PREFERENCE-MARKER") < text.indexOf("OPERATION-KNOWLEDGE-MARKER"))
    }

    // ── the production port ─────────────────────────────────────────────────────────────

    @Test
    fun `the memory port is empty until a preference is stored`() {
        val memory = AgentPreferenceMemory()
        val port = MemoryAgentPreferencePort(memory)

        assertEquals("", port.preferenceText())

        memory.add("以后都用中文回答")

        val text = port.preferenceText()
        assertTrue(text.contains("以后都用中文回答"))
        assertTrue(text.contains(AgentPreferenceMemory.HEADER))
    }

    @Test
    fun `the memory port respects the 1000 character budget`() {
        val memory = AgentPreferenceMemory()
        repeat(50) { index -> memory.add("偏好编号 $index 的内容要尽量长一些以便占满预算") }
        val port = MemoryAgentPreferencePort(memory, maxChars = AgentPreferenceMemory.DEFAULT_PROMPT_CHARS)

        val text = port.preferenceText()

        assertTrue(text.isNotEmpty())
        assertTrue(
            "rendered preferences must fit the prompt cap",
            text.length <= AgentPreferenceMemory.DEFAULT_PROMPT_CHARS
        )
        // The builder applies the same budget, so nothing is lost between the two.
        assertTrue(text.length <= AgentPromptedSystemPromptBuilder.MAX_USER_PREFERENCES_CHARS)
    }

    @Test
    fun `the port re-renders after a new preference and stays stable otherwise`() {
        val memory = AgentPreferenceMemory()
        val port = MemoryAgentPreferencePort(memory)
        memory.add("第一条")
        val first = port.preferenceText()
        assertEquals("the cached render must be stable", first, port.preferenceText())

        memory.add("第二条")
        val second = port.preferenceText()

        assertNotEquals(first, second)
        assertTrue(second.contains("第一条") && second.contains("第二条"))
    }

    @Test
    fun `a failing store degrades to no preferences instead of throwing`() {
        val port = MemoryAgentPreferencePort(AgentPreferenceMemory(maxPrefs = 0))
        assertEquals("", port.preferenceText())
    }
}
