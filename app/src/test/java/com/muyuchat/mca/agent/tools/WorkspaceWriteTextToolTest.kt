package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolResultStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceWriteTextToolTest {
    private val port = FakeAgentWorkspaceFilePort()
    private val tool = WorkspaceWriteTextTool(port)

    private fun command(json: String) = ExtendedToolTestSupport.command(tool.descriptor, json)

    @Test
    fun `create-only write commits below artifacts and reports the hash`() = runBlocking {
        val result = tool.execute(
            command("""{"content":"hello","expectedOldHash":null,"path":"artifacts/notes/a.txt"}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        assertTrue(result.dataJson!!.contains("\"created\":true"))
        assertTrue(result.dataJson!!.contains("\"path\":\"artifacts/notes/a.txt\""))
        assertEquals(1, port.writeCalls)
        assertNull(port.lastWriteCall!!.expectedOldHash)
        assertEquals("hello", String(port.files.getValue("artifacts/notes/a.txt"), Charsets.UTF_8))
        assertEquals(listOf("artifact-1"), result.artifactIds)
    }

    @Test
    fun `overwrite requires and forwards the expected hash`() = runBlocking {
        port.putText("artifacts/a.txt", "old")
        val currentHash = port.sha256Of("artifacts/a.txt")

        val result = tool.execute(
            command("""{"content":"new","expectedOldHash":"$currentHash","path":"artifacts/a.txt"}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        assertTrue(result.dataJson!!.contains("\"created\":false"))
        assertEquals(currentHash, port.lastWriteCall!!.expectedOldHash)
        assertEquals("new", String(port.files.getValue("artifacts/a.txt"), Charsets.UTF_8))
    }

    @Test
    fun `write outside artifacts is rejected before the port is called`() = runBlocking {
        val result = tool.execute(
            command("""{"content":"x","expectedOldHash":null,"path":"inputs/a.txt"}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("TOOL_INPUT_INVALID", result.errorCode)
        assertEquals(0, port.writeCalls)
    }

    @Test
    fun `content above the 32 KiB budget is rejected`() = runBlocking {
        val oversized = "a".repeat(32 * 1024 + 1)
        val result = tool.execute(
            command("""{"content":"$oversized","expectedOldHash":null,"path":"artifacts/a.txt"}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("TOOL_INPUT_INVALID", result.errorCode)
        assertEquals(0, port.writeCalls)
    }

    @Test
    fun `malformed expected hash is rejected`() = runBlocking {
        val result = tool.execute(
            command("""{"content":"x","expectedOldHash":"NOT-A-HASH","path":"artifacts/a.txt"}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("TOOL_INPUT_INVALID", result.errorCode)
        assertEquals(0, port.writeCalls)
    }

    @Test
    fun `port precondition failure is surfaced as PRECONDITION_CHANGED`() = runBlocking {
        val result = tool.execute(
            command("""{"content":"x","expectedOldHash":null,"path":"artifacts/a.txt"}"""),
            ExtendedToolTestSupport.context()
        )
        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)

        val second = tool.execute(
            command("""{"content":"y","expectedOldHash":null,"path":"artifacts/a.txt"}"""),
            ExtendedToolTestSupport.context()
        )
        assertEquals(AgentToolResultStatus.FAILED, second.status)
        assertEquals("PRECONDITION_CHANGED", second.errorCode)
        assertEquals("x", String(port.files.getValue("artifacts/a.txt"), Charsets.UTF_8))
    }

    @Test
    fun `unknown argument field is rejected`() = runBlocking {
        val result = tool.execute(
            command("""{"content":"x","expectedOldHash":null,"path":"artifacts/a.txt","mode":"force"}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("TOOL_INPUT_INVALID", result.errorCode)
        assertEquals(0, port.writeCalls)
    }

    @Test
    fun `a command for another tool is not executed`() = runBlocking {
        val listTool = WorkspaceListTool(port)
        val result = tool.execute(
            ExtendedToolTestSupport.command(
                listTool.descriptor,
                """{"content":"x","expectedOldHash":null,"path":"artifacts/a.txt"}"""
            ),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.NOT_EXECUTED, result.status)
        assertEquals("TOOL_NOT_FOUND", result.errorCode)
        assertEquals(0, port.writeCalls)
    }

    @Test
    fun `descriptor is frozen as a workspace write`() {
        assertEquals("workspace.write_text", tool.descriptor.toolKey)
        assertEquals("1", tool.descriptor.version)
        assertEquals(64, tool.descriptor.schemaHash.length)
        assertTrue(tool.descriptor.schemaJson.contains("\"additionalProperties\":false"))
        assertFalse(tool.descriptor.description.isBlank())
    }
}
