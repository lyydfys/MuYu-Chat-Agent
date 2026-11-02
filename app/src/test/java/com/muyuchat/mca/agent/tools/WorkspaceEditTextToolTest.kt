package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolResultStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceEditTextToolTest {
    private val port = FakeAgentWorkspaceFilePort()
    private val tool = WorkspaceEditTextTool(port)

    private fun editJson(path: String, oldText: String, newText: String, hash: String): String =
        """{"expectedOldHash":"$hash","newText":"$newText","oldText":"$oldText","path":"$path"}"""

    private fun command(json: String) = ExtendedToolTestSupport.command(tool.descriptor, json)

    @Test
    fun `unique occurrence is replaced with the observed hash as precondition`() = runBlocking {
        port.putText("artifacts/doc.md", "# Title\n\nbody\n")
        val hash = port.sha256Of("artifacts/doc.md")

        val result = tool.execute(
            command(editJson("artifacts/doc.md", "body", "BODY", hash)),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        assertEquals("# Title\n\nBODY\n", String(port.files.getValue("artifacts/doc.md"), Charsets.UTF_8))
        assertEquals(hash, port.lastWriteCall!!.expectedOldHash)
        assertTrue(result.dataJson!!.contains("\"previousSha256\":\"$hash\""))
    }

    @Test
    fun `missing oldText fails without writing`() = runBlocking {
        port.putText("artifacts/doc.md", "alpha\n")
        val hash = port.sha256Of("artifacts/doc.md")

        val result = tool.execute(
            command(editJson("artifacts/doc.md", "beta", "x", hash)),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("TOOL_EDIT_NOT_FOUND", result.errorCode)
        assertEquals(0, port.writeCalls)
        assertEquals("alpha\n", String(port.files.getValue("artifacts/doc.md"), Charsets.UTF_8))
    }

    @Test
    fun `ambiguous oldText fails without writing`() = runBlocking {
        port.putText("artifacts/doc.md", "x\ny\nx\n")
        val hash = port.sha256Of("artifacts/doc.md")

        val result = tool.execute(
            command(editJson("artifacts/doc.md", "x", "z", hash)),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("TOOL_EDIT_AMBIGUOUS", result.errorCode)
        assertEquals(0, port.writeCalls)
    }

    @Test
    fun `stale expected hash is a precondition failure`() = runBlocking {
        port.putText("artifacts/doc.md", "alpha\n")

        val result = tool.execute(
            command(editJson("artifacts/doc.md", "alpha", "x", "0".repeat(64))),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("PRECONDITION_CHANGED", result.errorCode)
        assertEquals(0, port.writeCalls)
    }

    @Test
    fun `edit without the workspace read grant is denied`() = runBlocking {
        port.putText("artifacts/doc.md", "alpha\n")

        val result = tool.execute(
            command(editJson("artifacts/doc.md", "alpha", "x", port.sha256Of("artifacts/doc.md"))),
            ExtendedToolTestSupport.context(workspaceReadGranted = false)
        )

        assertEquals(AgentToolResultStatus.DENIED, result.status)
        assertEquals("PERMISSION_DENIED", result.errorCode)
        assertEquals(0, port.writeCalls)
    }

    @Test
    fun `file that grows beyond the edit budget is refused`() = runBlocking {
        port.putText("artifacts/big.txt", "x".repeat(33 * 1024))

        val result = tool.execute(
            command(editJson("artifacts/big.txt", "x", "y", port.sha256Of("artifacts/big.txt"))),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("TOOL_FILE_TOO_LARGE", result.errorCode)
        assertEquals(0, port.writeCalls)
    }

    @Test
    fun `edit result above the write budget is refused`() = runBlocking {
        port.putText("artifacts/big.txt", "a".repeat(30 * 1024) + "\nMARK\n")

        val result = tool.execute(
            command(
                editJson("artifacts/big.txt", "MARK", "b".repeat(8_000), port.sha256Of("artifacts/big.txt"))
            ),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("TOOL_INPUT_INVALID", result.errorCode)
        assertEquals(0, port.writeCalls)
    }

    @Test
    fun `binary file is reported by the port and never rewritten`() = runBlocking {
        port.putBytes("artifacts/bin.dat", byteArrayOf(0xC3.toByte(), 0x28))

        val result = tool.execute(
            command(editJson("artifacts/bin.dat", "a", "x", "0".repeat(64))),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("TOOL_INPUT_INVALID", result.errorCode)
        assertEquals(0, port.writeCalls)
    }

    @Test
    fun `unknown argument field is rejected`() = runBlocking {
        port.putText("artifacts/doc.md", "alpha\n")
        val json = """{"expectedOldHash":"${port.sha256Of("artifacts/doc.md")}","newText":"x",""" +
            """"oldText":"alpha","path":"artifacts/doc.md","all":true}"""

        val result = tool.execute(command(json), ExtendedToolTestSupport.context())

        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("TOOL_INPUT_INVALID", result.errorCode)
        assertEquals(0, port.writeCalls)
    }
}
