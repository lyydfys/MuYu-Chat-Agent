package com.muyuchat.feature.chat.tavern

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernRegexPostProcessorTest {

    private fun processor(scripts: List<TavernRegexScript>): TavernRegexPostProcessor =
        TavernRegexPostProcessor.fromScripts(scripts)

    private fun script(
        find: String = "foo",
        replace: String = "bar",
        placements: Set<TavernRegexPlacement> = setOf(TavernRegexPlacement.ASSISTANT_OUTPUT),
        disabled: Boolean = false
    ) = TavernRegexScript(
        name = "test",
        findRegex = find,
        replaceString = replace,
        placements = placements,
        disabled = disabled
    )

    @Test
    fun `placement filters user input and assistant output independently`() {
        val userOnly = processor(listOf(script(placements = setOf(TavernRegexPlacement.USER_INPUT))))
        assertEquals("bar", userOnly.applyToUserInput("foo"))
        assertEquals("foo", userOnly.applyToAssistantOutput("foo"))

        val assistantOnly = processor(listOf(script(placements = setOf(TavernRegexPlacement.ASSISTANT_OUTPUT))))
        assertEquals("foo", assistantOnly.applyToUserInput("foo"))
        assertEquals("bar", assistantOnly.applyToAssistantOutput("foo"))

        val both = processor(listOf(script(placements = TavernRegexPlacement.entries.toSet())))
        assertEquals("bar", both.applyToUserInput("foo"))
        assertEquals("bar", both.applyToAssistantOutput("foo"))
    }

    @Test
    fun `dollar capture groups follow Kotlin replace semantics`() {
        val result = processor(
            listOf(script(find = "([A-Z]+)", replace = "<$1>"))
        ).applyToAssistantOutput("hello WORLD")
        assertEquals("hello <WORLD>", result)
    }

    @Test
    fun `named groups use Kotlin brace syntax`() {
        val result = processor(
            listOf(script(find = "(?<word>[A-Z]+)", replace = "\${word}"))
        ).applyToAssistantOutput("say HELLO")
        assertEquals("say HELLO", result)
    }

    @Test
    fun `a backslash escapes a replacement dollar`() {
        val result = processor(
            listOf(script(find = "x", replace = "\\$1"))
        ).applyToAssistantOutput("x")
        assertEquals("$1", result)
    }

    @Test
    fun `javascript whole-match replacement is not translated`() {
        // JS uses $&; Kotlin has no $& group, so the rewrite is intentionally left literal and
        // documented as a compatibility difference.
        val result = processor(
            listOf(script(find = "x", replace = "$&"))
        ).applyToAssistantOutput("x")
        assertEquals("$&", result)
    }

    @Test
    fun `scripts run in array order`() {
        val processor = processor(
            listOf(
                script(find = "a", replace = "b"),
                script(find = "b", replace = "c")
            )
        )
        assertEquals("c", processor.applyToAssistantOutput("a"))
    }

    @Test
    fun `invalid regex skips only its own script and records a warning`() {
        val processor = processor(
            listOf(
                script(find = "[", replace = "bad"),
                script(find = "x", replace = "y")
            )
        )
        assertEquals("y", processor.applyToAssistantOutput("x"))
        assertTrue(processor.warnings.any { it.contains("无法编译") })
    }

    @Test
    fun `at most two thousand replacements run for one script`() {
        val input = "a".repeat(2_500)
        val detailed = processor(listOf(script(find = "a", replace = "b")))
            .applyDetailed(input, TavernRegexPlacement.ASSISTANT_OUTPUT)
        assertEquals(2_000, detailed.text.count { it == 'b' })
        assertEquals(500, detailed.text.count { it == 'a' })
        assertTrue(detailed.warnings.any { it.contains("替换上限") })
    }

    @Test
    fun `input over two hundred thousand characters is truncated per script`() {
        val input = "x".repeat(250_000)
        // A non-matching script isolates the per-script input truncation from the 2,000 replacement
        // cap tested above.
        val detailed = processor(listOf(script(find = "never-matches", replace = "y")))
            .applyDetailed(input, TavernRegexPlacement.ASSISTANT_OUTPUT)
        assertEquals(TAVERN_REGEX_MAX_INPUT_CHARS, detailed.text.length)
        assertTrue(detailed.text.all { it == 'x' })
        assertTrue(detailed.warnings.any { it.contains("截断") })
    }

    @Test
    fun `no matching text is returned unchanged`() {
        val input = "no match here"
        assertEquals(input, processor(listOf(script(find = "zzz"))).applyToAssistantOutput(input))
    }

    @Test
    fun `disabled scripts never run`() {
        val processor = processor(
            listOf(
                script(find = "foo", replace = "bar", disabled = true),
                script(find = "foo", replace = "baz")
            )
        )
        assertEquals("baz", processor.applyToAssistantOutput("foo"))
    }

    @Test
    fun `parser accepts find underscore aliases and placement arrays`() {
        val json = JSONObject()
            .put(
                "extensions",
                JSONObject().put(
                    "regex_scripts",
                    JSONArray().put(
                        JSONObject()
                            .put("find_regex", "foo")
                            .put("replace_string", "bar")
                            .put("placement", JSONArray().put(1).put(2))
                    )
                )
            )
        val parsed = TavernRegexScriptParser.parsePresetRoot(json)
        assertTrue(parsed.hasKey)
        assertEquals(1, parsed.scripts.size)
        assertEquals(setOf(TavernRegexPlacement.USER_INPUT, TavernRegexPlacement.ASSISTANT_OUTPUT),
            parsed.scripts.single().placements)
        assertEquals("bar", TavernRegexPostProcessor.fromScripts(parsed.scripts).applyToUserInput("foo"))
    }

    @Test
    fun `parser treats placement number two as assistant output and disabled false as enabled`() {
        val parsed = TavernRegexScriptParser.parseScriptsJsonForTest(
            JSONArray().put(
                JSONObject()
                    .put("findRegex", "foo")
                    .put("replaceString", "bar")
                    .put("placement", 2)
                    .put("disabled", false)
            ).toString()
        )
        assertEquals(setOf(TavernRegexPlacement.ASSISTANT_OUTPUT), parsed.scripts.single().placements)
        assertFalse(parsed.scripts.single().disabled)
    }

    @Test
    fun `parser treats enabled false as disabled`() {
        val parsed = TavernRegexScriptParser.parseScriptsJsonForTest(
            JSONArray().put(
                JSONObject()
                    .put("findRegex", "foo")
                    .put("replaceString", "bar")
                    .put("enabled", false)
            ).toString()
        )
        assertTrue(parsed.scripts.single().disabled)
        assertFalse(TavernRegexPostProcessor.fromScripts(parsed.scripts).hasScripts)
    }

    @Test
    fun `markdownOnly is parsed but has no effect`() {
        val parsed = TavernRegexScriptParser.parseScriptsJsonForTest(
            JSONArray().put(
                JSONObject()
                    .put("findRegex", "foo")
                    .put("replaceString", "bar")
                    .put("markdownOnly", true)
            ).toString()
        )
        assertTrue(parsed.scripts.single().markdownOnly)
        assertEquals("bar", TavernRegexPostProcessor.fromScripts(parsed.scripts).applyToAssistantOutput("foo"))
    }

    @Test
    fun `a missing preset key is a complete no-op`() {
        val processor = TavernRegexPostProcessor.fromParamsJson("""{"temperature":0.8,"top_p":0.9}""")
        assertFalse(processor.hasPresetKey)
        assertFalse(processor.hasScripts)
        assertEquals("unchanged", processor.applyToAssistantOutput("unchanged"))
    }

    @Test
    fun `stored scripts are read from preset params`() {
        val scripts = TavernRegexScriptParser.toJsonArray(
            listOf(
                script(
                    find = "foo",
                    replace = "bar",
                    placements = setOf(TavernRegexPlacement.ASSISTANT_OUTPUT)
                )
            )
        )
        val params = JSONObject().put(TAVERN_REGEX_SCRIPTS_KEY, scripts).toString()
        val processor = TavernRegexPostProcessor.fromPresetParams(params)
        assertTrue(processor.hasPresetKey)
        assertEquals("bar", processor.applyToAssistantOutput("foo"))
    }

    @Test
    fun `over sixty four scripts are capped with a warning`() {
        val scripts = List(70) { index ->
            script(find = "token-$index-", replace = "done-$index-")
        }
        val processor = TavernRegexPostProcessor.fromScripts(scripts)
        assertTrue(processor.warnings.any { it.contains("超过 64") })
        assertEquals("done-63-", processor.applyToAssistantOutput("token-63-"))
        assertEquals("token-64-", processor.applyToAssistantOutput("token-64-"))
    }

    @Test
    fun `a stringified scripts array is accepted`() {
        val array = JSONArray().put(
            JSONObject()
                .put("findRegex", "foo")
                .put("replaceString", "bar")
                .put("placement", 1)
        )
        val params = JSONObject().put(TAVERN_REGEX_SCRIPTS_KEY, array.toString()).toString()
        assertEquals("bar", TavernRegexPostProcessor.fromPresetParams(params).applyToUserInput("foo"))
    }

    @Test
    fun `script names and missing findRegex produce warnings without breaking others`() {
        val parsed = TavernRegexScriptParser.parseScriptsJsonForTest(
            JSONArray()
                .put(JSONObject().put("replaceString", "x"))
                .put(JSONObject().put("findRegex", "ok").put("replaceString", "fine"))
                .toString()
        )
        assertTrue(parsed.warnings.any { it.contains("缺少 findRegex") })
        assertEquals("fine", TavernRegexPostProcessor.fromScripts(parsed.scripts).applyToAssistantOutput("ok"))
    }
}

/** Test-only bridge so parser tests do not need to build a whole preset object. */
private fun TavernRegexScriptParser.parseScriptsJsonForTest(raw: String): TavernRegexParseResult {
    val array = JSONArray(raw)
    val root = JSONObject().put("extensions", JSONObject().put("regex_scripts", array))
    return parsePresetRoot(root)
}
