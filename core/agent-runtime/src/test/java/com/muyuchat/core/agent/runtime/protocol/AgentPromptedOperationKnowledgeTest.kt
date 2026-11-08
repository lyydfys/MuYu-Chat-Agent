package com.muyuchat.core.agent.runtime.protocol

import com.muyuchat.core.agent.contract.AgentKnowledgePort
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the optional "operation knowledge" section of the SYSTEM prompt.
 *
 * The whole feature is additive: a run without a knowledge port must produce the byte-identical
 * prompt it produced before the port existed, because the prompt string is hashed into the run's
 * evidence. So these cases pin two things and nothing else — that `""` changes nothing, and that
 * real knowledge is appended, bounded, and cannot rewrite the protocol rules above it.
 */
class AgentPromptedOperationKnowledgeTest {

    @Test
    fun `an empty port leaves the prompted prompt byte-identical`() {
        val builder = AgentPromptedSystemPromptBuilder()

        val withoutArgument = builder.build(TOOLS, POLICY_VERSION, BUDGET_JSON)
        val withEmptyPort = builder.build(TOOLS, POLICY_VERSION, BUDGET_JSON, PROMPTED, "")

        assertEquals(withoutArgument, withEmptyPort)
        assertFalse(withoutArgument.contains("OPERATION KNOWLEDGE"))
    }

    @Test
    fun `knowledge is appended after the budget and before the tool catalog`() {
        val prompt = AgentPromptedSystemPromptBuilder().build(
            TOOLS, POLICY_VERSION, BUDGET_JSON, PROMPTED, "微信：点底部「通讯录」进入联系人列表。"
        )

        val knowledgeAt = prompt.indexOf("OPERATION KNOWLEDGE")
        assertTrue("知识段落必须出现", knowledgeAt > 0)
        assertTrue(
            "知识必须排在预算之后",
            knowledgeAt > prompt.indexOf("BUDGET_JSON")
        )
        assertTrue(
            "知识必须排在工具目录之前，让 FINAL REMINDER 仍然最后",
            knowledgeAt < prompt.indexOf("AVAILABLE TOOLS")
        )
        assertTrue(prompt.contains("微信：点底部「通讯录」进入联系人列表。"))
        // The final reminder still has to be the last thing a small model reads.
        assertTrue(prompt.indexOf("FINAL REMINDER") > knowledgeAt)
    }

    @Test
    fun `the knowledge section never exceeds its cap`() {
        val oversized = "知".repeat(5_000)

        val prompt = AgentPromptedSystemPromptBuilder().build(
            TOOLS, POLICY_VERSION, BUDGET_JSON, PROMPTED, oversized
        )

        val section = prompt.substringAfter("OPERATION KNOWLEDGE")
            .substringBefore("AVAILABLE TOOLS")
        val body = section.lines().drop(1).joinToString("\n").trim()
        assertTrue(
            "知识正文 ${body.length} 字，超过上限",
            body.length <= AgentPromptedSystemPromptBuilder.MAX_OPERATION_KNOWLEDGE_CHARS
        )
        assertEquals(2_000, AgentPromptedSystemPromptBuilder.MAX_OPERATION_KNOWLEDGE_CHARS)
    }

    @Test
    fun `blank knowledge writes nothing at all`() {
        val builder = AgentPromptedSystemPromptBuilder()
        val baseline = builder.build(TOOLS, POLICY_VERSION, BUDGET_JSON)

        assertEquals(baseline, builder.build(TOOLS, POLICY_VERSION, BUDGET_JSON, PROMPTED, "   \n\t "))
        assertEquals(baseline, builder.build(TOOLS, POLICY_VERSION, BUDGET_JSON, PROMPTED, ""))
    }

    @Test
    fun `the native prompt also carries knowledge only when it is non-empty`() {
        val native = AgentNativeSystemPromptBuilder()
        val baseline = native.build(TOOLS, POLICY_VERSION, BUDGET_JSON, AgentProtocolMode.NATIVE)

        assertEquals(
            baseline,
            native.build(TOOLS, POLICY_VERSION, BUDGET_JSON, AgentProtocolMode.NATIVE, "")
        )

        val withKnowledge = native.build(
            TOOLS, POLICY_VERSION, BUDGET_JSON, AgentProtocolMode.NATIVE, "浏览器：先等页面加载完成。"
        )
        assertTrue(withKnowledge.contains("浏览器：先等页面加载完成。"))
        assertTrue(withKnowledge.contains("OPERATION KNOWLEDGE"))
    }

    @Test
    fun `the protocol selector forwards knowledge to the matching builder`() {
        val selector = AgentProtocolSystemPromptBuilder()

        val prompted = selector.build(TOOLS, POLICY_VERSION, BUDGET_JSON, PROMPTED, "微信：长按转语音。")
        assertTrue(prompted.contains("微信：长按转语音。"))
        assertTrue(prompted.contains("MCA AGENT SYSTEM PROMPT"))

        val native = selector.build(TOOLS, POLICY_VERSION, BUDGET_JSON, AgentProtocolMode.NATIVE, "微信：长按转语音。")
        assertTrue(native.contains("微信：长按转语音。"))
        assertTrue(native.contains("NATIVE tool calls"))

        // The two prompts are structurally different; the knowledge is the only shared addition.
        assertNotEquals(prompted, native)
    }

    @Test
    fun `the default port is empty so wiring it explicitly changes nothing`() {
        assertEquals("", AgentKnowledgePort.NONE.relevantKnowledge("任何目标"))
        assertEquals("", AgentKnowledgePort.NONE.relevantKnowledge(""))
        assertEquals(
            AgentPromptedSystemPromptBuilder().build(TOOLS, POLICY_VERSION, BUDGET_JSON),
            AgentPromptedSystemPromptBuilder().build(
                TOOLS, POLICY_VERSION, BUDGET_JSON, PROMPTED,
                AgentKnowledgePort.NONE.relevantKnowledge("打开微信")
            )
        )
    }

    private companion object {
        const val POLICY_VERSION = 3L
        const val BUDGET_JSON = """{"modelAttempts":8}"""
        val PROMPTED = AgentProtocolMode.PROMPTED_V1
        val TOOLS = listOf(
            AgentToolDescriptor(
                toolKey = "device.screen",
                version = "1",
                displayName = "Phone Screen",
                description = "Read and act on the phone screen.",
                schemaJson = """{"type":"object"}""",
                schemaHash = "hash-1",
                sideEffect = com.muyuchat.core.agent.contract.AgentToolSideEffect.READ_ONLY,
                dataClass = com.muyuchat.core.agent.contract.AgentDataClass.PRIVATE
            )
        )
    }
}
