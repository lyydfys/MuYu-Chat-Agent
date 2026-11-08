package com.muyuchat.mca.agent.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the preference store and the extraction rules.
 *
 * Two properties matter more than coverage: the store must never grow past its bound, and the
 * extractor must never invent a preference (a missed one is recoverable, an invented one silently
 * changes every later answer). The privacy rules are asserted as separate cases because
 * "记住我的密码是…" must never be stored even though it matches a trigger.
 */
class AgentPreferenceMemoryTest {

    // ------------------------------------------------------------------ store

    @Test
    fun `an explicit preference is stored and readable`() {
        val memory = AgentPreferenceMemory()

        assertTrue(memory.add("以后都用中文回答", nowMillis = 1_000L))
        assertEquals(1, memory.size)
        val stored = memory.all().single()
        assertEquals("以后都用中文回答", stored.text)
        assertEquals("user", stored.source)
        assertEquals(1_000L, stored.createdAt)
        assertTrue(stored.id.isNotBlank())
    }

    @Test
    fun `duplicates are ignored and keep the original entry`() {
        val memory = AgentPreferenceMemory()
        memory.add("以后都用中文回答", nowMillis = 1_000L)

        assertFalse("an identical statement must not be stored twice", memory.add("  以后都用中文回答  ", nowMillis = 2_000L))
        assertFalse(memory.add("以后都用中文回答。", nowMillis = 3_000L))
        assertEquals(1, memory.size)
        assertEquals(1_000L, memory.all().single().createdAt)
    }

    @Test
    fun `blank input is refused`() {
        val memory = AgentPreferenceMemory()

        assertFalse(memory.add(""))
        assertFalse(memory.add("   \n "))
        assertTrue(memory.isEmpty())
        assertEquals("", memory.asPromptText())
    }

    @Test
    fun `the store evicts the oldest entry when full`() {
        val memory = AgentPreferenceMemory(maxPrefs = 3)
        memory.add("偏好一", nowMillis = 1L)
        memory.add("偏好二", nowMillis = 2L)
        memory.add("偏好三", nowMillis = 3L)

        assertTrue(memory.add("偏好四", nowMillis = 4L))

        assertEquals(3, memory.size)
        assertEquals(listOf("偏好二", "偏好三", "偏好四"), memory.all().map { it.text })
    }

    @Test
    fun `a zero sized store accepts nothing`() {
        val memory = AgentPreferenceMemory(maxPrefs = 0)

        assertFalse(memory.add("以后都用中文回答"))
        assertTrue(memory.isEmpty())
    }

    @Test
    fun `clear empties the store`() {
        val memory = AgentPreferenceMemory()
        memory.add("以后都用中文回答")
        memory.clear()

        assertTrue(memory.isEmpty())
        assertEquals("", memory.asPromptText())
    }

    // ------------------------------------------------------------------ prompt text

    @Test
    fun `prompt text lists preferences newest first under a header`() {
        val memory = AgentPreferenceMemory()
        memory.add("以后都用中文回答")
        memory.add("不要删文件前问我")

        val text = memory.asPromptText()

        assertTrue(text.startsWith(AgentPreferenceMemory.HEADER))
        val first = text.indexOf("不要删文件前问我")
        val second = text.indexOf("以后都用中文回答")
        assertTrue("newest preference comes first", first in 0 until second)
        assertTrue(text.contains("\n- "))
    }

    @Test
    fun `prompt text is capped and reports how many were omitted`() {
        val memory = AgentPreferenceMemory()
        repeat(10) { index -> memory.add("第 $index 条偏好：回答尽量简短并且给出步骤") }

        val text = memory.asPromptText(maxChars = 120)

        assertTrue("must respect the budget, was ${text.length}", text.length <= 120)
        assertTrue(text, text.contains("未显示"))
        assertFalse("the omitted entries must not leak", text.contains("第 0 条偏好"))
    }

    @Test
    fun `a single oversized preference is dropped rather than cut mid sentence`() {
        val memory = AgentPreferenceMemory()
        memory.add("以后都" + "很长的偏好内容".repeat(200))

        val text = memory.asPromptText(maxChars = 100)

        assertEquals("", text)
        assertFalse(text.contains("很长的偏好内容"))
    }

    @Test
    fun `a non positive budget yields no block`() {
        val memory = AgentPreferenceMemory()
        memory.add("以后都用中文回答")

        assertEquals("", memory.asPromptText(maxChars = 0))
        assertEquals("", memory.asPromptText(maxChars = -5))
    }

    // ------------------------------------------------------------------ extraction

    @Test
    fun `the four explicit phrasings are recognised`() {
        assertEquals(listOf("以后都用中文回答"), AgentPreferenceExtractor.extract("以后都用中文回答"))
        assertEquals(
            listOf("记住我不喜欢表格"),
            AgentPreferenceExtractor.extract("请记住：我不喜欢表格")
        )
        assertEquals(
            listOf("不要再自动删除文件"),
            AgentPreferenceExtractor.extract("不要再自动删除文件")
        )
        assertEquals(
            listOf("始终先给结论再给步骤"),
            AgentPreferenceExtractor.extract("请始终先给结论再给步骤")
        )
    }

    @Test
    fun `a trigger inside a longer message is extracted on its own`() {
        val extracted = AgentPreferenceExtractor.extract("今天天气不错。以后都用中文回答。谢谢")

        assertEquals(listOf("以后都用中文回答"), extracted)
    }

    @Test
    fun `ordinary chat yields nothing`() {
        listOf(
            "",
            "   ",
            "今天天气怎么样",
            "帮我看看这个文件",
            "这个错误是什么意思？",
            "记住"
        ).forEach { message ->
            assertTrue("\"$message\" must not produce a preference", AgentPreferenceExtractor.extract(message).isEmpty())
        }
    }

    @Test
    fun `a bare trigger without content is not a preference`() {
        assertTrue(AgentPreferenceExtractor.extract("记住").isEmpty())
        assertTrue(AgentPreferenceExtractor.extract("以后都").isEmpty())
        assertTrue(AgentPreferenceExtractor.extract("请始终").isEmpty())
    }

    @Test
    fun `at most three preferences are taken from one message`() {
        val message = "记住我喜欢简短回答。以后都用中文。请始终给步骤。不要再问我确认。"

        val extracted = AgentPreferenceExtractor.extract(message)

        assertEquals(3, extracted.size)
    }

    @Test
    fun `an oversized statement is cut to the bound`() {
        val long = "以后都" + "很长的要求".repeat(100)

        val extracted = AgentPreferenceExtractor.extract(long)

        assertEquals(1, extracted.size)
        assertEquals(AgentPreferenceExtractor.MAX_PREFERENCE_CHARS, extracted.single().length)
    }

    // ------------------------------------------------------------------ privacy

    @Test
    fun `credentials are never extracted even with a trigger`() {
        listOf(
            "记住我的密码是 hunter2",
            "以后都用这个验证码 123456 登录",
            "请记住我的银行卡号 6222 8888 0000 1111",
            "记住 API key 是 sk-live-abcdef",
            "不要再忘记我的 token: abcdef123456",
            "记住我的 CVV 是 123"
        ).forEach { message ->
            assertTrue("must not store: $message", AgentPreferenceExtractor.extract(message).isEmpty())
        }
    }

    @Test
    fun `a bare long digit run is treated as a secret`() {
        assertTrue(AgentPreferenceExtractor.isSecret("6222888800001111"))
        assertTrue(AgentPreferenceExtractor.isSecret("记住 6222 8888 0000 1111"))
        assertFalse(AgentPreferenceExtractor.isSecret("以后都用中文回答"))
    }

    @Test
    fun `storing an extracted preference keeps only the statement`() {
        val memory = AgentPreferenceMemory()
        val extracted = AgentPreferenceExtractor.extract("以后都用中文回答，谢谢")

        extracted.forEach { memory.add(it) }

        assertEquals(listOf("以后都用中文回答"), memory.all().map { it.text })
    }
}
