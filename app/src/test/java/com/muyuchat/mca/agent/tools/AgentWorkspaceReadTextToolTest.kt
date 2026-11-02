package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolResultStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkspaceReadTextToolTest {
    private val fake = FakeAgentWorkspaceFilePort()
    private val tool = WorkspaceReadTextTool(fake)
    private val context = toolContext()
    private val text = "h\u00e9llo" // h (1 byte), e-acute (2 bytes), llo (3 bytes) = 6 bytes

    @Test
    fun `full read returns content eof totals and hash`() {
        runBlocking {
            fake.putText("inputs/a.txt", text)
            val result = tool.execute(
                toolCommand(tool.descriptor, arguments("path" to "inputs/a.txt")),
                context
            )
            assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
            assertEquals(0L, fake.lastReadCall!!.offsetBytes)
            assertEquals(4_096, fake.lastReadCall!!.limitBytes)

            val data = resultData(result)
            assertEquals("inputs/a.txt", data.getString("path"))
            assertEquals(text, data.getString("content"))
            assertEquals(0L, data.getLong("offsetBytes"))
            assertEquals(6L, data.getLong("nextOffsetBytes"))
            assertEquals(6L, data.getLong("totalBytes"))
            assertTrue(data.getBoolean("eof"))
            assertEquals(fake.sha256Of("inputs/a.txt"), data.getString("sha256"))
        }
    }

    @Test
    fun `window end shrinks to a complete character`() {
        runBlocking {
            fake.putText("inputs/a.txt", text)
            val result = tool.execute(
                toolCommand(tool.descriptor, options("inputs/a.txt", offset = 0, limit = 2)),
                context
            )
            assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
            val data = resultData(result)
            assertEquals("h", data.getString("content"))
            assertEquals(1L, data.getLong("nextOffsetBytes"))
            assertEquals(1L, data.getLong("returnedBytes"))
            assertFalse(data.getBoolean("eof"))
        }
    }

    @Test
    fun `non character boundary offset is TOOL_INPUT_INVALID`() {
        runBlocking {
            fake.putText("inputs/a.txt", text)
            val result = tool.execute(
                toolCommand(tool.descriptor, options("inputs/a.txt", offset = 2, limit = 4)),
                context
            )
            assertEquals(AgentToolResultStatus.FAILED, result.status)
            assertEquals("TOOL_INPUT_INVALID", result.errorCode)
        }
    }

    @Test
    fun `limit that cannot hold one character is LIMIT_TOO_SMALL`() {
        runBlocking {
            fake.putText("inputs/a.txt", text)
            val result = tool.execute(
                toolCommand(tool.descriptor, options("inputs/a.txt", offset = 1, limit = 1)),
                context
            )
            assertEquals(AgentToolResultStatus.FAILED, result.status)
            assertEquals("LIMIT_TOO_SMALL", result.errorCode)
        }
    }

    @Test
    fun `binary or invalid UTF-8 input is explicitly rejected`() {
        runBlocking {
            fake.putBytes("inputs/blob.bin", byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00))
            val result = tool.execute(
                toolCommand(tool.descriptor, arguments("path" to "inputs/blob.bin")),
                context
            )
            assertEquals(AgentToolResultStatus.FAILED, result.status)
            assertEquals("TOOL_INPUT_INVALID", result.errorCode)
            assertTrue(result.summary.contains("UTF-8"))
        }
    }

    @Test
    fun `offset and limit bounds are rejected before the port is called`() {
        runBlocking {
            val negative = tool.execute(
                toolCommand(tool.descriptor, options("inputs/a.txt", offset = -1, limit = 10)),
                context
            )
            assertEquals(AgentToolResultStatus.FAILED, negative.status)
            assertEquals("TOOL_INPUT_INVALID", negative.errorCode)

            val tooLarge = tool.execute(
                toolCommand(tool.descriptor, options("inputs/a.txt", offset = 0, limit = 16_385)),
                context
            )
            assertEquals(AgentToolResultStatus.FAILED, tooLarge.status)
            assertEquals("TOOL_INPUT_INVALID", tooLarge.errorCode)
            assertEquals(0, fake.readCalls)
        }
    }

    @Test
    fun `missing required path is rejected`() {
        runBlocking {
            val result = tool.execute(toolCommand(tool.descriptor, "{}"), context)
            assertEquals(AgentToolResultStatus.FAILED, result.status)
            assertEquals("TOOL_INPUT_INVALID", result.errorCode)
            assertEquals(0, fake.readCalls)
        }
    }

    @Test
    fun `read at exact end of file returns an empty eof window`() {
        runBlocking {
            fake.putText("inputs/a.txt", "abc")
            val result = tool.execute(
                toolCommand(tool.descriptor, options("inputs/a.txt", offset = 3, limit = 4)),
                context
            )
            assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
            val data = resultData(result)
            assertEquals("", data.getString("content"))
            assertTrue(data.getBoolean("eof"))
        }
    }

    @Test
    fun `read from the artifacts root is allowed but escapes are rejected`() {
        runBlocking {
            fake.putText("artifacts/report.md", "report")
            val allowed = tool.execute(
                toolCommand(tool.descriptor, arguments("path" to "artifacts/report.md")),
                context
            )
            assertEquals(AgentToolResultStatus.SUCCEEDED, allowed.status)

            val escape = tool.execute(
                toolCommand(tool.descriptor, arguments("path" to "../secret.txt")),
                context
            )
            assertEquals(AgentToolResultStatus.FAILED, escape.status)
            assertEquals("TOOL_INPUT_INVALID", escape.errorCode)
        }
    }

    @Test
    fun `stale schema is NOT_EXECUTED`() {
        runBlocking {
            val stale = toolCommand(tool.descriptor, arguments("path" to "inputs/a.txt"))
                .copy(schemaHash = "0".repeat(64))
            val result = tool.execute(stale, context)
            assertEquals(AgentToolResultStatus.NOT_EXECUTED, result.status)
            assertEquals(0, fake.readCalls)
        }
    }

    private fun options(path: String, offset: Long, limit: Int): String {
        val json = JSONObject()
            .put("path", path)
            .put("offset", offset)
            .put("limit", limit)
        return json.toString()
    }
}
