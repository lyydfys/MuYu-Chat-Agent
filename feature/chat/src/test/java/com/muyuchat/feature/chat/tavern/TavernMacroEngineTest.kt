package com.muyuchat.feature.chat.tavern

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernMacroEngineTest {

    private val fixedClock = Clock.fixed(
        Instant.parse("2026-01-02T03:04:05Z"),
        ZoneOffset.UTC
    )

    private fun engine(seed: Long = 7L): TavernMacroEngine =
        TavernMacroEngine(clock = fixedClock, randomSeedProvider = { seed })

    private fun context(
        sessionId: String = "session-a",
        userName: String = "Aria"
    ): TavernMacroContext =
        TavernMacroContext(
            sessionId = sessionId,
            userName = userName,
            charName = "Lina",
            description = "一位旅人",
            personality = "冷静",
            scenario = "雨夜的酒馆",
            persona = "调查员"
        )

    @Test
    fun `user char and card macros expand from the context`() {
        assertEquals(
            "Aria/Lina/一位旅人/冷静/雨夜的酒馆/调查员",
            engine().expand(
                "{{user}}/{{char}}/{{description}}/{{personality}}/{{scenario}}/{{persona}}",
                context()
            )
        )
    }

    @Test
    fun `getvar of an unset variable is empty`() {
        assertEquals("", engine().expand("{{getvar::missing}}", context()))
    }

    @Test
    fun `setvar is visible to a later getvar in the same expansion`() {
        assertEquals(
            "value",
            engine().expand("{{setvar::mood::value}}{{getvar::mood}}", context())
        )
    }

    @Test
    fun `variables survive across expansions of the same engine and session`() {
        val engine = engine()
        engine.expand("{{setvar::mood::calm}}", context())
        assertEquals("calm", engine.expand("{{getvar::mood}}", context()))
    }

    @Test
    fun `variables are isolated by session id`() {
        val engine = engine()
        engine.expand("{{setvar::mood::calm}}", context("session-a"))
        assertEquals("", engine.expand("{{getvar::mood}}", context("session-b")))
        assertEquals("calm", engine.expand("{{getvar::mood}}", context("session-a")))
    }

    @Test
    fun `addvar adds numbers and concatenates non-numeric text`() {
        val engine = engine()
        engine.expand("{{setvar::n::1}}", context())
        assertEquals("3", engine.expand("{{addvar::n::2}}{{getvar::n}}", context()))
        engine.expand("{{setvar::word::ab}}", context())
        assertEquals("abcd", engine.expand("{{addvar::word::cd}}{{getvar::word}}", context()))
    }

    @Test
    fun `incvar and decvar return the new value and default to zero`() {
        val engine = engine()
        assertEquals("1", engine.expand("{{incvar::counter}}", context()))
        assertEquals("2", engine.expand("{{incvar::counter}}", context()))
        assertEquals("1", engine.expand("{{decvar::counter}}", context()))
    }

    @Test
    fun `random and pick choose from the supplied options`() {
        val engine = engine(seed = 99L)
        assertTrue(engine.expand("{{random::a,b,c}}", context()) in setOf("a", "b", "c"))
        assertTrue(engine.expand("{{pick::a,b}}", context()) in setOf("a", "b"))
        assertEquals("only", engine.expand("{{random::only}}", context()))
    }

    @Test
    fun `random is deterministic for a fixed seed`() {
        val first = engine(seed = 123L).expand("{{random::a,b,c,d,e}}", context())
        val second = engine(seed = 123L).expand("{{random::a,b,c,d,e}}", context())
        assertEquals(first, second)
    }

    @Test
    fun `roll validates the dice expression`() {
        val result = engine().expand("{{roll:1d6}}", context()).toInt()
        assertTrue(result in 1..6)
        listOf("{{roll:0d6}}", "{{roll:1d1}}", "{{roll:abc}}", "{{roll:1000d6}}")
            .forEach { invalid ->
                assertEquals(invalid, engine().expand(invalid, context()))
            }
    }

    @Test
    fun `unknown macros are preserved verbatim`() {
        assertEquals(
            "before {{notARealMacro::x}} after",
            engine().expand("before {{notARealMacro::x}} after", context())
        )
    }

    @Test
    fun `comments are removed`() {
        assertEquals("ab", engine().expand("a{{// hidden comment}}b", context()))
    }

    @Test
    fun `escaped opening braces produce a literal macro without expansion`() {
        assertEquals("{{user}}", engine().expand("\\{{user}}", context()))
    }

    @Test
    fun `newline expands to a line break`() {
        assertEquals("a\nb", engine().expand("a{{newline}}b", context()))
    }

    @Test
    fun `time and date use the injected clock`() {
        assertEquals("03:04", engine().expand("{{time}}", context()))
        assertEquals("2026-01-02", engine().expand("{{date}}", context()))
    }

    @Test
    fun `a variable value containing another macro expands on a later round`() {
        val engine = engine()
        engine.expand("{{setvar::z::deep}}", context())
        engine.expand("{{setvar::y::{{getvar::z}}}}", context())
        // y stores the literal "{{getvar::z}}"; resolving y must take an extra round.
        assertEquals("deep", engine.expand("{{getvar::y}}", context()))
    }

    @Test
    fun `expansion stops after the recorded three rounds`() {
        val engine = engine()
        engine.expand("{{setvar::five::end}}", context())
        engine.expand("{{setvar::four::{{getvar::five}}}}", context())
        engine.expand("{{setvar::three::{{getvar::four}}}}", context())
        engine.expand("{{setvar::two::{{getvar::three}}}}", context())
        engine.expand("{{setvar::one::{{getvar::two}}}}", context())

        val result = engine.expand("{{getvar::one}}", context())
        assertTrue("the fourth nested macro must remain visible", result.contains("{{getvar::"))
    }

    @Test
    fun `input over the cap is truncated and marked`() {
        val result = engine().expand("a".repeat(TAVERN_MACRO_MAX_INPUT_CHARS + 1), context())
        assertTrue(result.contains(TAVERN_MACRO_INPUT_TRUNCATION_MARKER))
        assertTrue(result.startsWith("a".repeat(TAVERN_MACRO_MAX_INPUT_CHARS)))
    }

    @Test
    fun `result over the cap is truncated and marked`() {
        val huge = "x".repeat(TAVERN_MACRO_MAX_OUTPUT_CHARS + 5_000)
        val result = engine().expand("{{user}}", context(userName = huge))
        assertTrue(result.contains(TAVERN_MACRO_OUTPUT_TRUNCATION_MARKER))
        assertEquals(
            TAVERN_MACRO_MAX_OUTPUT_CHARS,
            result.substringBefore(TAVERN_MACRO_OUTPUT_TRUNCATION_MARKER).length
        )
    }

    @Test
    fun `plain text without macros is unchanged`() {
        val plain = "普通的提示词，没有任何宏。"
        assertEquals(plain, engine().expand(plain, context()))
        assertFalse(engine().expand(plain, context()).contains("{{"))
    }
}
