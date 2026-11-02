package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentFileResult
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkspaceListToolTest {
    private val fake = FakeAgentWorkspaceFilePort()
    private val tool = WorkspaceListTool(fake)
    private val context = toolContext()

    @Test
    fun `defaults resolve to the inputs root and limit 100`() {
        runBlocking {
            fake.putText("inputs/a.txt", "a")
            fake.putText("inputs/b.txt", "bb")
            fake.putText("inputs/sub/c.txt", "ccc")
            fake.putText("artifacts/out.md", "x")

            val result = tool.execute(toolCommand(tool.descriptor, "{}"), context)

            assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
            assertEquals("inputs", fake.lastListCall!!.path)
            assertEquals(100, fake.lastListCall!!.limit)
            assertNull(fake.lastListCall!!.cursor)

            val data = resultData(result)
            assertEquals("inputs", data.getString("path"))
            assertEquals(100, data.getInt("limit"))
            assertEquals(3, data.getInt("count"))
            val entries = data.getJSONArray("entries")
            assertEquals("inputs/a.txt", entries.getJSONObject(0).getString("path"))
            assertEquals("FILE", entries.getJSONObject(0).getString("kind"))
            assertEquals(1L, entries.getJSONObject(0).getLong("sizeBytes"))
            assertEquals("inputs/b.txt", entries.getJSONObject(1).getString("path"))
            assertEquals("inputs/sub", entries.getJSONObject(2).getString("path"))
            assertEquals("DIRECTORY", entries.getJSONObject(2).getString("kind"))
            assertTrue(data.isNull("nextCursor"))
        }
    }

    @Test
    fun `limit bounds are rejected before the port is called`() {
        runBlocking {
            val zero = tool.execute(toolCommand(tool.descriptor, arguments("limit" to 0)), context)
            assertEquals(AgentToolResultStatus.FAILED, zero.status)
            assertEquals("TOOL_INPUT_INVALID", zero.errorCode)

            val tooLarge = tool.execute(toolCommand(tool.descriptor, arguments("limit" to 201)), context)
            assertEquals(AgentToolResultStatus.FAILED, tooLarge.status)
            assertEquals("TOOL_INPUT_INVALID", tooLarge.errorCode)
            assertEquals(0, fake.listCalls)
        }
    }

    @Test
    fun `cursor continues and a changed directory reports CURSOR_STALE`() {
        runBlocking {
            fake.putText("inputs/a.txt", "a")
            fake.putText("inputs/b.txt", "b")
            val first = tool.execute(
                toolCommand(tool.descriptor, arguments("limit" to 1)),
                context
            )
            assertEquals(AgentToolResultStatus.SUCCEEDED, first.status)
            val cursor = resultData(first).getString("nextCursor")

            val second = tool.execute(
                toolCommand(tool.descriptor, arguments("limit" to 1, "cursor" to cursor)),
                context
            )
            assertEquals(AgentToolResultStatus.SUCCEEDED, second.status)
            assertEquals(
                "inputs/b.txt",
                resultData(second).getJSONArray("entries").getJSONObject(0).getString("path")
            )

            fake.putText("inputs/c.txt", "c")
            val stale = tool.execute(
                toolCommand(tool.descriptor, arguments("limit" to 1, "cursor" to cursor)),
                context
            )
            assertEquals(AgentToolResultStatus.FAILED, stale.status)
            assertEquals("CURSOR_STALE", stale.errorCode)
        }
    }

    @Test
    fun `missing read grant is a DENIED result and never reaches the port`() {
        runBlocking {
            val result = tool.execute(toolCommand(tool.descriptor, "{}"), toolContext(workspaceReadGranted = false))
            assertEquals(AgentToolResultStatus.DENIED, result.status)
            assertEquals("PERMISSION_DENIED", result.errorCode)
            assertEquals(0, fake.listCalls)
        }
    }

    @Test
    fun `stale frozen schema is NOT_EXECUTED`() {
        runBlocking {
            val stale = toolCommand(tool.descriptor, "{}").copy(schemaHash = "0".repeat(64))
            val result = tool.execute(stale, context)
            assertEquals(AgentToolResultStatus.NOT_EXECUTED, result.status)
            assertEquals(0, fake.listCalls)
        }
    }

    @Test
    fun `path guard rejects escapes absolute paths and foreign roots`() {
        runBlocking {
            val bad = listOf("/etc/passwd", "../escape", "inputs/../../escape", "C:\\temp", "unknown/x")
            bad.forEach { path ->
                val result = tool.execute(toolCommand(tool.descriptor, arguments("path" to path)), context)
                assertEquals("path '$path' must be rejected", AgentToolResultStatus.FAILED, result.status)
                assertEquals("TOOL_INPUT_INVALID", result.errorCode)
            }
            assertEquals(0, fake.listCalls)
        }
    }

    @Test
    fun `port failure is mapped with its stable error code`() {
        runBlocking {
            fake.forcedListFailure = AgentFileResult.Failure(
                "CURSOR_STALE",
                "Workspace directory changed since the cursor was issued."
            )
            val result = tool.execute(toolCommand(tool.descriptor, "{}"), context)
            assertEquals(AgentToolResultStatus.FAILED, result.status)
            assertEquals("CURSOR_STALE", result.errorCode)
            assertTrue(!resultEnvelope(result).getJSONObject("data").keys().hasNext())
        }
    }

    @Test
    fun `non recursive listing never descends into a directory`() {
        runBlocking {
            fake.putText("inputs/dir/deep.txt", "x")
            val result = tool.execute(toolCommand(tool.descriptor, "{}"), context)
            val entries = resultData(result).getJSONArray("entries")
            assertEquals(1, entries.length())
            assertEquals("inputs/dir", entries.getJSONObject(0).getString("path"))
            assertEquals("DIRECTORY", entries.getJSONObject(0).getString("kind"))
        }
    }
}
