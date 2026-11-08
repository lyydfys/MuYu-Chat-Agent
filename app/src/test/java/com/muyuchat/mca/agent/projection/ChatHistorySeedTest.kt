package com.muyuchat.mca.agent.projection

import com.muyuchat.core.engine.AgentTurnTrace
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the chat-history seed that lets an agent turn see the conversation before it.
 *
 * `CreateAgentTask` has no context field and the goal is capped at 8 KiB, so the history travels as
 * a read-only `inputs/` file plus a one-sentence goal hint. These cases pin the four promises that
 * make that safe: the agent never re-ingests its own transcript, the file never exceeds its budget,
 * one huge message cannot dominate it, and an empty history produces no dangling instruction.
 */
class ChatHistorySeedTest {

    @Test
    fun `agent transcript messages are skipped instead of fed back to the agent`() {
        val history = listOf(
            message(Role.USER, "帮我整理一下昨天的会议纪要"),
            agentMessage("我正在读取 notes.md 并写入 summary.md"),
            message(Role.ASSISTANT, "好的，我来总结三个要点。"),
            agentMessage("工具调用：workspace_read_text"),
            message(Role.USER, "顺便把标题改成中文")
        )

        val seed = buildSeed(history)

        assertFalse("agent 自己的步骤不能出现在 seed 里", seed.seedText.contains("我正在读取"))
        assertFalse("agent 工具调用不能出现在 seed 里", seed.seedText.contains("workspace_read_text"))
        assertTrue(seed.seedText.contains("帮我整理一下昨天的会议纪要"))
        assertTrue(seed.seedText.contains("好的，我来总结三个要点。"))
        assertTrue(seed.seedText.contains("顺便把标题改成中文"))
        assertEquals(3, seed.includedMessageCount)
        assertEquals(2, seed.skippedAgentMessageCount)
    }

    @Test
    fun `total seed bytes never exceed the budget`() {
        val history = (1..40).map { index ->
            message(Role.USER, "第 $index 条中文消息，内容是重复的提示词填充。".repeat(3))
        }

        val budget = 1_024
        val seed = buildSeed(history, maxBytes = budget)

        assertTrue(
            "seed 实际 ${seed.seedText.toByteArray(Charsets.UTF_8).size} 字节，超过上限 $budget",
            seed.seedText.toByteArray(Charsets.UTF_8).size <= budget
        )
        assertFalse(seed.isEmpty)
        assertTrue("被预算截断时仍要保留最新的消息", seed.seedText.contains("第 40 条"))
    }

    @Test
    fun `an oversized single message is truncated and marked`() {
        val longBody = "这是一段非常长的中文提示词。".repeat(200)
        val seed = buildSeed(listOf(message(Role.USER, longBody)), maxBytes = 4 * 1024)

        assertTrue("超长单条必须带截断标记", seed.seedText.endsWith("…"))
        assertTrue(
            "单条不能超过 per-message 预算",
            seed.seedText.toByteArray(Charsets.UTF_8).size <=
                CHAT_HISTORY_SEED_MAX_MESSAGE_BYTES + 8
        )
        assertTrue(seed.seedText.startsWith("用户: "))
        assertTrue("被截断的消息要能被识别", seed.seedText.contains("这是一段非常长的中文提示词"))
        assertEquals(1, seed.truncatedMessageCount)
        assertEquals(1, seed.includedMessageCount)
    }

    @Test
    fun `truncation never splits a multi-byte character`() {
        // Every character is 3 UTF-8 bytes, so an odd byte budget forces a boundary decision.
        val seed = buildSeed(listOf(agentFreeMessage("汉".repeat(200))), maxBytes = 200)

        val text = seed.seedText
        assertTrue(text.endsWith("…"))
        // A naive byte cut would end with U+FFFD, which is what a broken boundary decodes to.
        assertFalse("不能在多字节字符中间截断", text.contains('\uFFFD'))
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= 200)
    }

    @Test
    fun `empty and agent-only histories produce no hint`() {
        assertNull("空历史不能产生 goalHint", buildSeed(emptyList()).goalHint)
        assertTrue(buildSeed(emptyList()).isEmpty)

        val agentOnly = listOf(
            agentMessage("步骤一"),
            agentMessage("步骤二")
        )
        val seed = buildSeed(agentOnly)
        assertNull("只有 agent 历史时也不能产生 goalHint", seed.goalHint)
        assertEquals("", seed.seedText)
        assertEquals(2, seed.skippedAgentMessageCount)

        val blankOnly = listOf(message(Role.USER, "   \n\t  "))
        assertNull("空白消息不构成可读历史", buildSeed(blankOnly).goalHint)
    }

    @Test
    fun `a non-empty seed names the input file in the goal hint`() {
        val fileName = chatHistorySeedFileName(1_762_560_000_000L)
        val seed = buildSeed(
            history = listOf(message(Role.USER, "继续上一次的重构")),
            inputFileName = fileName
        )

        val hint = requireNotNull(seed.goalHint)
        assertEquals("chat-history-1762560000000.txt", fileName)
        assertTrue("goalHint 必须指向 inputs/ 下的具体文件", hint.contains("inputs/$fileName"))
        assertTrue(seed.seedText.startsWith("用户: 继续上一次的重构"))
    }

    @Test
    fun `newlines inside one message are collapsed so a message stays one line`() {
        val seed = buildSeed(listOf(message(Role.USER, "第一段\n第二段\r\n\t第三段")))

        assertEquals(1, seed.seedText.lines().size)
        assertEquals("用户: 第一段 第二段 第三段", seed.seedText)
    }

    @Test
    fun `older messages are sacrificed before newer ones when the budget is tight`() {
        val history = listOf(
            message(Role.USER, "很旧的消息".repeat(30)),
            message(Role.USER, "较新的消息"),
            message(Role.USER, "最新的消息")
        )

        val seed = buildSeed(history, maxBytes = 220)

        // Ordering is oldest → newest, so the newest turn is the last line and always survives whole.
        assertTrue("最新的消息必须完整保留", seed.seedText.endsWith("用户: 最新的消息"))
        assertTrue(seed.seedText.contains("较新的消息"))
        // The squeezed-out budget comes off the oldest body, which is cut and marked rather than
        // allowed to push the newer turns past the limit.
        assertEquals(1, seed.truncatedMessageCount)
        assertTrue(seed.seedText.toByteArray(Charsets.UTF_8).size <= 220)
    }

    // ---------------------------------------------------------------- fixtures

    private fun message(role: Role, content: String): ChatMessage =
        ChatMessage(role = role, content = content)

    private fun agentMessage(content: String): ChatMessage =
        ChatMessage(
            role = Role.ASSISTANT,
            content = content,
            agentTrace = AgentTurnTrace(turnId = "run-1", kind = "step")
        )

    private fun agentFreeMessage(content: String): ChatMessage =
        ChatMessage(role = Role.USER, content = content)
}
