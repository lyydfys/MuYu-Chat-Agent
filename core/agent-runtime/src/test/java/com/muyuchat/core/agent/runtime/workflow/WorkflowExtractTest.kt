package com.muyuchat.core.agent.runtime.workflow

import com.muyuchat.core.agent.runtime.AgentJsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowExtractTest {

    private fun run(op: String, source: String, path: String): WorkflowExtractResult =
        WorkflowExtract.execute("""{"op":"$op","source":${quote(source)},"path":${quote(path)}}""")

    private fun value(result: WorkflowExtractResult): AgentJsonValue =
        (result as WorkflowExtractResult.Value).value

    private fun error(result: WorkflowExtractResult): String =
        (result as WorkflowExtractResult.Error).code

    private fun quote(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test
    fun regexSubstringConcatAndJsonResolveValues() {
        assertEquals(
            "a-b-c",
            (value(run("REGEX", "a-b-c", "a-(b)-c")) as AgentJsonValue.Str).value
        )
        assertEquals(
            "ell",
            (value(run("SUB", "hello", "1,3")) as AgentJsonValue.Str).value
        )
        assertEquals(
            "left-right",
            (value(run("CONCAT", "left-", "right")) as AgentJsonValue.Str).value
        )
        val json = """{"a":{"b":"ok"},"list":[1,2]}"""
        assertEquals("ok", (value(run("JSON", json, "a.b")) as AgentJsonValue.Str).value)
        assertEquals(2, (value(run("JSON", json, "list.1")) as AgentJsonValue.Num).literal.toInt())
    }

    @Test
    fun randomValuesStayInsideTheirBounds() {
        val number = value(run("RANDOM_INT", "", "5,9")) as AgentJsonValue.Num
        assertTrue(number.literal.toInt() in 5..9)
        val text = (value(run("RANDOM_STRING", "", "32")) as AgentJsonValue.Str).value
        assertEquals(32, text.length)
        assertTrue(text.all { it.isLetterOrDigit() })
    }

    @Test
    fun invalidPatternsRangesAndJsonAreTypedErrors() {
        assertEquals("WF_EXTRACT_PATTERN", error(run("REGEX", "x", "[")))
        assertEquals("WF_EXTRACT_NO_MATCH", error(run("REGEX", "x", "y")))
        assertEquals("WF_EXTRACT_RANGE", error(run("SUB", "short", "3,10")))
        assertEquals("WF_EXTRACT_RANGE", error(run("RANDOM_INT", "", "9,5")))
        assertEquals("WF_EXTRACT_RANGE", error(run("RANDOM_STRING", "", "0")))
        assertEquals("WF_EXTRACT_JSON", error(run("JSON", "not json", "a")))
        assertEquals("WF_EXTRACT_JSON_PATH", error(run("JSON", """{"a":1}""", "b")))
        assertEquals("WF_EXTRACT_OP", error(run("EXEC", "x", "y")))
        assertEquals("WF_EXTRACT_PATTERN", error(run("REGEX", "x", "(a+)+")))
        assertEquals("WF_EXTRACT_LIMIT", error(run("REGEX", "x".repeat(4_097), "x")))
    }
}
