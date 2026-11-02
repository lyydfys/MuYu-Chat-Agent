package com.muyuchat.mca.agent.tools

import com.muyuchat.mca.agent.security.AgentArgumentNormalization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentExtendedToolArgumentNormalizerTest {
    private val normalizer = AgentExtendedToolArgumentNormalizer()

    private fun normalized(toolKey: String, raw: String): AgentArgumentNormalization.Normalized {
        val result = normalizer.normalize(descriptor(toolKey), raw)
        assertTrue("expected normalization, got $result", result is AgentArgumentNormalization.Normalized)
        return result as AgentArgumentNormalization.Normalized
    }

    private fun descriptor(toolKey: String) = when (toolKey) {
        "workspace.write_text" -> AgentExtendedToolDescriptors.WORKSPACE_WRITE_TEXT
        "workspace.edit_text" -> AgentExtendedToolDescriptors.WORKSPACE_EDIT_TEXT
        "http.get" -> AgentExtendedToolDescriptors.HTTP_GET
        "terminal.exec" -> AgentExtendedToolDescriptors.TERMINAL_EXEC
        "workspace.list" -> AgentToolDescriptors.WORKSPACE_LIST
        else -> error("unknown test descriptor $toolKey")
    }

    private fun rejected(toolKey: String, raw: String): String {
        val result = normalizer.normalize(descriptor(toolKey), raw)
        assertTrue("expected rejection, got $result", result is AgentArgumentNormalization.Rejected)
        return (result as AgentArgumentNormalization.Rejected).error.code
    }

    @Test
    fun `write_text resolves the explicit null hash and canonical path`() {
        val result = normalized(
            "workspace.write_text",
            """{"content":"hi","expectedOldHash":null,"path":"artifacts/a.txt/"}"""
        )

        assertEquals("""{"content":"hi","expectedOldHash":null,"path":"artifacts/a.txt"}""", result.normalizedArgumentsJson)
        assertEquals(listOf("artifacts/a.txt"), result.logicalPaths)
        assertEquals(null, result.expectedOldHash)
    }

    @Test
    fun `write_text requires an explicit expectedOldHash`() {
        assertEquals("TOOL_INPUT_INVALID", rejected("workspace.write_text", """{"content":"x","path":"artifacts/a.txt"}"""))
    }

    @Test
    fun `write_text rejects outside paths and malformed hashes`() {
        assertEquals(
            "TOOL_INPUT_INVALID",
            rejected("workspace.write_text", """{"content":"x","expectedOldHash":null,"path":"inputs/a.txt"}""")
        )
        assertEquals(
            "TOOL_INPUT_INVALID",
            rejected("workspace.write_text", """{"content":"x","expectedOldHash":"zz","path":"artifacts/a.txt"}""")
        )
        assertEquals(
            "TOOL_INPUT_INVALID",
            rejected("workspace.write_text", """{"content":"x","expectedOldHash":null,"path":"artifacts/../a.txt"}""")
        )
    }

    @Test
    fun `write_text rejects content above the budget`() {
        val oversized = "a".repeat(32 * 1024 + 1)
        assertEquals(
            "TOOL_INPUT_INVALID",
            rejected(
                "workspace.write_text",
                """{"content":"$oversized","expectedOldHash":null,"path":"artifacts/a.txt"}"""
            )
        )
    }

    @Test
    fun `edit_text binds the expected hash`() {
        val hash = "a".repeat(64)
        val result = normalized(
            "workspace.edit_text",
            """{"expectedOldHash":"$hash","newText":"B","oldText":"A","path":"artifacts/doc.md"}"""
        )

        assertEquals(hash, result.expectedOldHash)
        assertEquals(listOf("artifacts/doc.md"), result.logicalPaths)
        assertTrue(result.normalizedArgumentsJson.contains("\"expectedOldHash\":\"$hash\""))
    }

    @Test
    fun `edit_text rejects empty or missing snippets and bad hashes`() {
        assertEquals(
            "TOOL_INPUT_INVALID",
            rejected(
                "workspace.edit_text",
                """{"expectedOldHash":"${"a".repeat(64)}","newText":"B","oldText":"","path":"artifacts/doc.md"}"""
            )
        )
        assertEquals(
            "TOOL_INPUT_INVALID",
            rejected(
                "workspace.edit_text",
                """{"expectedOldHash":"${"a".repeat(64)}","newText":"B","path":"artifacts/doc.md"}"""
            )
        )
        assertEquals(
            "TOOL_INPUT_INVALID",
            rejected(
                "workspace.edit_text",
                """{"expectedOldHash":"not-a-hash","newText":"B","oldText":"A","path":"artifacts/doc.md"}"""
            )
        )
    }

    @Test
    fun `http_get applies defaults and keeps the exact url`() {
        val result = normalized("http.get", """{"url":"https://example.com/a?b=c"}""")

        assertEquals(
            """{"maxBytes":32768,"timeoutMillis":10000,"url":"https://example.com/a?b=c"}""",
            result.normalizedArgumentsJson
        )
        assertTrue(result.logicalPaths.isEmpty())
    }

    @Test
    fun `http_get honours explicit budgets and rejects out of range values`() {
        val result = normalized("http.get", """{"maxBytes":1024,"timeoutMillis":1500,"url":"https://example.com/"}""")
        assertEquals(
            """{"maxBytes":1024,"timeoutMillis":1500,"url":"https://example.com/"}""",
            result.normalizedArgumentsJson
        )

        assertEquals("TOOL_INPUT_INVALID", rejected("http.get", """{"maxBytes":0,"url":"https://example.com/"}"""))
        assertEquals("TOOL_INPUT_INVALID", rejected("http.get", """{"timeoutMillis":999,"url":"https://example.com/"}"""))
        assertEquals("TOOL_INPUT_INVALID", rejected("http.get", """{"maxBytes":131073,"url":"https://example.com/"}"""))
    }

    @Test
    fun `http_get rejects cleartext, private hosts and unsupported fields`() {
        assertEquals("TOOL_INPUT_INVALID", rejected("http.get", """{"url":"http://example.com/"}"""))
        assertEquals("TOOL_INPUT_INVALID", rejected("http.get", """{"url":"https://localhost/"}"""))
        assertEquals("TOOL_INPUT_INVALID", rejected("http.get", """{"url":"https://127.0.0.1/"}"""))
        assertEquals("TOOL_INPUT_INVALID", rejected("http.get", """{"url":"https://example.com:99999/"}"""))
        assertEquals(
            "TOOL_INPUT_INVALID",
            rejected("http.get", """{"url":"https://example.com/","method":"POST"}""")
        )
    }

    @Test
    fun `terminal defaults are resolved before approval`() {
        val result = normalized("terminal.exec", """{"command":"echo"}""")
        assertEquals(
            """{"args":"","command":"echo","timeoutMillis":15000}""",
            result.normalizedArgumentsJson
        )
    }

    @Test
    fun `v1 tools still delegate to the built-in normalizer`() {
        val result = normalized("workspace.list", "{}")
        assertEquals("""{"cursor":null,"limit":100,"path":"inputs"}""", result.normalizedArgumentsJson)
        assertEquals(listOf("inputs"), result.logicalPaths)
    }
}
