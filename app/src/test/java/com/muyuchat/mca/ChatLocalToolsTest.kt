package com.muyuchat.mca

import com.muyuchat.core.engine.ChatToolCall
import com.muyuchat.mca.dsh.DshPromptedParse
import com.muyuchat.mca.dsh.DshPromptedToolProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * These tests pin the chat-page local tool contract: what the model is offered, what actually runs,
 * and — most importantly — that nothing unsafe can be reached through it. The clock and arithmetic
 * tools exist because a cloud model otherwise has no way to read the real time or compute exactly.
 */
class ChatLocalToolsTest {

    private fun call(name: String, arguments: String): ChatToolCall =
        ChatToolCall(id = "call-1", name = name, argumentsJson = arguments)

    private fun execute(
        name: String,
        arguments: String,
        now: Instant = Instant.parse("2026-11-03T13:35:07Z"),
        rolls: List<Int> = emptyList()
    ): ChatLocalToolResult {
        val queue = ArrayDeque(rolls)
        return ChatLocalToolExecutor.execute(
            call(name, arguments),
            now = { now },
            rollDie = { sides -> queue.removeFirstOrNull()?.coerceIn(1, sides) ?: 1 }
        )
    }

    private fun parse(raw: String): DshPromptedParse = DshPromptedToolProtocol.parse(
        raw = raw,
        offeredNames = ChatLocalToolCatalog.offeredNames,
        callId = "run-1",
        limits = ChatLocalToolCatalog.limits
    )

    // ---- catalog -------------------------------------------------------------------------

    @Test
    fun `instruction offers exactly the three local tools and both protocol markers`() {
        val instruction = ChatLocalToolCatalog.instruction()
        ChatLocalToolCatalog.offeredNames.forEach { name ->
            assertTrue("instruction must name $name", instruction.contains(name))
        }
        assertTrue(instruction.contains(DshPromptedToolProtocol.START_MARKER))
        assertTrue(instruction.contains(DshPromptedToolProtocol.END_MARKER))
        assertTrue(instruction.contains("\"tool_calls\""))
        assertTrue(instruction.contains("${ChatLocalToolCatalog.MAX_CALLS_PER_TURN} 个调用"))
        // Nothing that would let the model reach a shell, a file or the network.
        assertFalse(instruction.contains("bash"))
        assertFalse(instruction.contains("http"))
    }

    @Test
    fun `offered names stay in sync with the executor's switch`() {
        assertEquals(
            listOf("get_device_time", "calc", "roll_dice"),
            ChatLocalToolCatalog.offeredNames
        )
    }

    // ---- detection -----------------------------------------------------------------------

    @Test
    fun `a valid envelope resolves into one continuation with parsed arguments`() {
        val raw = "现在应该快到晚上了。\n" + DshPromptedToolProtocol.START_MARKER + "\n" +
            """{"tool_calls":[{"name":"get_device_time","arguments":{"timezone":"Asia/Shanghai"}}]}""" +
            "\n" + DshPromptedToolProtocol.END_MARKER
        val turn = resolveChatLocalToolTurn(parse(raw))
        assertTrue(turn is ChatLocalToolTurn.Continue)
        val continuation = turn as ChatLocalToolTurn.Continue
        assertEquals(1, continuation.calls.size)
        assertEquals("get_device_time", continuation.calls.single().name)
        assertEquals(
            "Asia/Shanghai",
            JSONObject(continuation.calls.single().argumentsJson).getString("timezone")
        )
    }

    @Test
    fun `plain prose is plain text and is never treated as a call`() {
        val turn = resolveChatLocalToolTurn(parse("现在是晚上九点，我猜的。"))
        assertEquals(ChatLocalToolTurn.Plain("现在是晚上九点，我猜的。"), turn)
    }

    @Test
    fun `an unknown tool name is suppressed instead of executed`() {
        val raw = DshPromptedToolProtocol.START_MARKER +
            """{"tool_calls":[{"name":"shell","arguments":{"command":"id"}}]}""" +
            DshPromptedToolProtocol.END_MARKER
        assertEquals(ChatLocalToolTurn.Suppressed, resolveChatLocalToolTurn(parse(raw)))
    }

    @Test
    fun `a malformed envelope is suppressed instead of leaking protocol text`() {
        val raw = DshPromptedToolProtocol.START_MARKER + "{\"tool_calls\":["
        assertEquals(ChatLocalToolTurn.Suppressed, resolveChatLocalToolTurn(parse(raw)))
    }

    @Test
    fun `more calls than the turn budget are rejected`() {
        val calls = (1..ChatLocalToolCatalog.MAX_CALLS_PER_TURN + 1).joinToString(",") {
            """{"name":"calc","arguments":{"expression":"$it+1"}}"""
        }
        val raw = DshPromptedToolProtocol.START_MARKER +
            """{"tool_calls":[$calls]}""" + DshPromptedToolProtocol.END_MARKER
        assertEquals(ChatLocalToolTurn.Suppressed, resolveChatLocalToolTurn(parse(raw)))
    }

    // ---- clock ---------------------------------------------------------------------------

    @Test
    fun `device time reports the real clock in the requested zone`() {
        val result = execute("get_device_time", """{"timezone":"Asia/Shanghai"}""")
        assertTrue(result.ok)
        assertTrue(result.text, result.text.contains("2026-11-03 21:35:07"))
        assertTrue(result.text, result.text.contains("Asia/Shanghai"))
        assertTrue(result.text, result.text.contains("UTC+08:00"))
    }

    @Test
    fun `device time without arguments uses the device zone`() {
        val result = ChatLocalToolExecutor.execute(
            call("get_device_time", "{}"),
            now = { Instant.parse("2026-11-03T13:35:07Z") },
            zone = { ZoneId.of("UTC") }
        )
        assertTrue(result.ok)
        assertTrue(result.text, result.text.contains("2026-11-03 13:35:07"))
        assertTrue(result.text, result.text.contains("UTC"))
    }

    @Test
    fun `an unknown zone is an explicit failure instead of a silent fallback`() {
        val result = execute("get_device_time", """{"timezone":"Mars/Olympus"}""")
        assertFalse(result.ok)
        assertTrue(result.text, result.text.contains("时区"))
    }

    @Test
    fun `device time rejects undocumented arguments`() {
        val result = execute("get_device_time", """{"timezone":"UTC","mode":"iso"}""")
        assertFalse(result.ok)
    }

    // ---- arithmetic ----------------------------------------------------------------------

    @Test
    fun `calc returns the arithmetic result the model asked for`() {
        val result = execute("calc", """{"expression":"(12.5*3+7)/2"}""")
        assertTrue(result.ok)
        assertEquals("(12.5*3+7)/2 = 22.25", result.text)
    }

    @Test
    fun `calc rejects malformed arguments and unsafe input`() {
        assertFalse(execute("calc", "{}").ok)
        assertFalse(execute("calc", """{"expression":"1+1","extra":1}""").ok)
        assertFalse(execute("calc", """{"expression":"1/0"}""").ok)
        assertFalse(execute("calc", """{"expression":"java.lang.Runtime"}""").ok)
        assertFalse(execute("calc", "not json").ok)
    }

    // ---- dice ----------------------------------------------------------------------------

    @Test
    fun `dice lists the individual rolls and the modifier`() {
        val result = execute("roll_dice", """{"dice":"2d6+1"}""", rolls = listOf(4, 6))
        assertTrue(result.ok)
        assertEquals("2d6+1 → 4 + 6 + 1 = 11", result.text)
    }

    @Test
    fun `a bare die expression defaults to one die`() {
        val result = execute("roll_dice", """{"dice":"d20"}""", rolls = listOf(17))
        assertTrue(result.ok)
        assertEquals("20 → 17", result.text)
    }

    @Test
    fun `dice bounds are enforced`() {
        assertFalse(execute("roll_dice", """{"dice":"0d6"}""").ok)
        assertFalse(execute("roll_dice", """{"dice":"99d6"}""").ok)
        assertFalse(execute("roll_dice", """{"dice":"2d1"}""").ok)
        assertFalse(execute("roll_dice", """{"dice":"drop table"}""").ok)
        assertFalse(execute("roll_dice", "{}").ok)
    }

    // ---- executor surface ----------------------------------------------------------------

    @Test
    fun `an unoffered tool name is rejected by the executor too`() {
        val result = execute("read_file", """{"path":"/etc/passwd"}""")
        assertFalse(result.ok)
        assertTrue(result.text, result.text.contains("不支持"))
    }

    // ---- continuation context -------------------------------------------------------------

    @Test
    fun `continuation context carries the results and the visible tail`() {
        val result = ChatLocalToolResult("calc", true, "1+1 = 2")
        val context = ChatLocalToolCatalog.continuationContext(listOf(result), "让我算算。")
        assertTrue(context.contains("calc"))
        assertTrue(context.contains("1+1 = 2"))
        assertTrue(context.contains("让我算算。"))
        assertTrue(context.contains("不要再输出工具调用块"))
        assertFalse(context.contains(DshPromptedToolProtocol.START_MARKER))
        assertTrue(context.length <= ChatLocalToolCatalog.MAX_CONTINUATION_CHARS)
    }

    @Test
    fun `a failed tool result is labelled instead of being presented as a fact`() {
        val context = ChatLocalToolCatalog.continuationContext(
            listOf(ChatLocalToolResult("calc", false, "不能除以零。")),
            ""
        )
        assertTrue(context.contains("执行失败"))
        assertTrue(context.contains("不能除以零。"))
    }

    @Test
    fun `an oversized continuation context is bounded`() {
        val context = ChatLocalToolCatalog.continuationContext(
            listOf(ChatLocalToolResult("calc", true, "x".repeat(5000))),
            "y".repeat(5000)
        )
        assertTrue(context.length <= ChatLocalToolCatalog.MAX_CONTINUATION_CHARS)
    }
}
