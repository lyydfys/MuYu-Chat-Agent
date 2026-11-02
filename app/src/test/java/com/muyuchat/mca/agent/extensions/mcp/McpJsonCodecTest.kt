package com.muyuchat.mca.agent.extensions.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpJsonCodecTest {
    private val codec = McpJsonCodec()

    @Test
    fun parsesObjectAndScalars() {
        val result = codec.parse("""{"a":1,"b":[true,null,"x"],"c":{"d":2.5}}""")
        val root = (result as McpJsonParseResult.Parsed).value.asObj()!!
        assertEquals("1", root.numberOrNull("a")?.literal)
        assertEquals(3, root.arrayOrNull("b")?.items?.size)
        assertEquals("2.5", root.objectOrNull("c")?.numberOrNull("d")?.literal)
    }

    @Test
    fun rejectsDuplicateKeys() {
        val result = codec.parse("""{"a":1,"a":2}""")
        assertTrue(result is McpJsonParseResult.Failed)
    }

    @Test
    fun rejectsTrailingRootContent() {
        val result = codec.parse("""{"a":1} {"b":2}""")
        assertTrue(result is McpJsonParseResult.Failed)
    }

    @Test
    fun rejectsCommentsSingleQuotesAndTrailingCommas() {
        listOf(
            """{"a":1} // comment""",
            """{'a':1}""",
            """{"a":1,}""",
            """{"a":NaN}"""
        ).forEach { text ->
            assertTrue("must reject: $text", codec.parse(text) is McpJsonParseResult.Failed)
        }
    }

    @Test
    fun rejectsExcessiveNesting() {
        val text = "[".repeat(McpProtocol.MAX_JSON_DEPTH + 2) +
            "0" + "]".repeat(McpProtocol.MAX_JSON_DEPTH + 2)
        assertTrue(codec.parse(text) is McpJsonParseResult.Failed)
    }

    @Test
    fun rejectsOversizedMessage() {
        val text = "{\"a\":\"" + "x".repeat(McpProtocol.MAX_MESSAGE_BYTES) + "\"}"
        assertTrue(codec.parse(text) is McpJsonParseResult.Failed)
    }

    @Test
    fun writerRoundTripsAndEscapes() {
        val value = McpJson.obj(
            "text" to McpJson.Str("line1\n\"quoted\"\\tail"),
            "list" to McpJson.Arr(listOf(McpJson.Bool(true), McpJson.Null))
        )
        val written = codec.write(value)
        val parsed = (codec.parse(written) as McpJsonParseResult.Parsed).value.asObj()!!
        assertEquals("line1\n\"quoted\"\\tail", parsed.string("text"))
        assertEquals(2, parsed.arrayOrNull("list")?.items?.size)
        assertFalse(written.contains('\n'))
    }
}
