package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentFileResult
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkspaceSearchTextToolTest {
    private val fake = FakeAgentWorkspaceFilePort()
    private val tool = WorkspaceSearchTextTool(fake)
    private val context = toolContext()

    @Test
    fun `defaults resolve to the inputs root and maxMatches 20`() {
        runBlocking {
            fake.putText("inputs/a.txt", "needle")
            val result = tool.execute(toolCommand(tool.descriptor, arguments("query" to "needle")), context)
            assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
            assertEquals("inputs", fake.lastSearchCall!!.path)
            assertEquals(20, fake.lastSearchCall!!.maxMatches)
            assertEquals("needle", fake.lastSearchCall!!.query)
            assertEquals(1, resultData(result).getInt("matchCount"))
        }
    }

    @Test
    fun `search is literal and case sensitive`() {
        runBlocking {
            fake.putText("inputs/a.txt", "Foo\nfoo\na.*b\naxxb")
            val lower = tool.execute(toolCommand(tool.descriptor, arguments("query" to "foo")), context)
            assertEquals(1, resultData(lower).getInt("matchCount"))
            assertEquals(2, resultData(lower).getJSONArray("matches").getJSONObject(0).getInt("line"))

            val pattern = tool.execute(toolCommand(tool.descriptor, arguments("query" to "a.*b")), context)
            assertEquals(1, resultData(pattern).getInt("matchCount"))
            assertEquals(
                "a.*b",
                resultData(pattern).getJSONArray("matches").getJSONObject(0).getString("snippet")
            )
        }
    }

    @Test
    fun `matches are ordered by path then line number`() {
        runBlocking {
            fake.putText("inputs/z.txt", "needle")
            fake.putText("inputs/a.txt", "one\nneedle\nthree\nneedle")
            val result = tool.execute(toolCommand(tool.descriptor, arguments("query" to "needle")), context)
            val matches = resultData(result).getJSONArray("matches")
            assertEquals(3, matches.length())
            assertEquals("inputs/a.txt", matches.getJSONObject(0).getString("path"))
            assertEquals(2, matches.getJSONObject(0).getInt("line"))
            assertEquals(4, matches.getJSONObject(1).getInt("line"))
            assertEquals("inputs/z.txt", matches.getJSONObject(2).getString("path"))
        }
    }

    @Test
    fun `page cap marks the scan partial and never claims completeness`() {
        runBlocking {
            fake.putText("inputs/a.txt", (1..25).joinToString("\n") { "needle-$it" })
            val result = tool.execute(toolCommand(tool.descriptor, arguments("query" to "needle")), context)
            assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
            val data = resultData(result)
            assertEquals(20, data.getInt("matchCount"))
            assertTrue(data.getBoolean("truncated"))
            assertFalse(data.getBoolean("complete"))
            assertTrue(result.truncated)
            assertEquals(20, fake.lastSearchCall!!.maxMatches)
        }
    }

    @Test
    fun `scan budget truncation is reported with scannedBytes and advertised limits`() {
        runBlocking {
            val cappedFake = FakeAgentWorkspaceFilePort(totalScanCapBytes = 10, perFileScanCapBytes = 10)
            val cappedTool = WorkspaceSearchTextTool(cappedFake)
            cappedFake.putText("inputs/a.txt", "x".repeat(100) + "needle")
            val result = cappedTool.execute(
                toolCommand(cappedTool.descriptor, arguments("query" to "needle")),
                context
            )
            assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
            val data = resultData(result)
            assertTrue(data.getBoolean("truncated"))
            assertFalse(data.getBoolean("complete"))
            assertTrue(result.truncated)
            assertEquals(10L, data.getLong("scannedBytes"))
            assertEquals(32L * 1024 * 1024, data.getJSONObject("scanLimits").getLong("totalBytes"))
            assertEquals(8L * 1024 * 1024, data.getJSONObject("scanLimits").getLong("perFileBytes"))
        }
    }

    @Test
    fun `snippets are bounded to 240 characters`() {
        runBlocking {
            fake.snippetPadding = 500
            fake.putText("inputs/a.txt", "needle")
            val result = tool.execute(toolCommand(tool.descriptor, arguments("query" to "needle")), context)
            val snippet = resultData(result).getJSONArray("matches").getJSONObject(0).getString("snippet")
            assertEquals(240, snippet.length)
            assertTrue(snippet.endsWith("\u2026"))
            assertTrue(resultData(result).getBoolean("truncated"))
        }
    }

    @Test
    fun `bounds and query validation run before the port`() {
        runBlocking {
            listOf(
                arguments("query" to "x", "maxMatches" to 0),
                arguments("query" to "x", "maxMatches" to 101),
                arguments("query" to "x".repeat(257)),
                arguments("query" to "line\nbreak"),
                "{}"
            ).forEach { argumentsJson ->
                val result = tool.execute(toolCommand(tool.descriptor, argumentsJson), context)
                assertEquals(AgentToolResultStatus.FAILED, result.status)
                assertEquals("TOOL_INPUT_INVALID", result.errorCode)
            }
            assertEquals(0, fake.searchCalls)
        }
    }

    @Test
    fun `persistence budget shrinks matches instead of claiming a full page`() {
        runBlocking {
            fake.snippetPadding = 240
            repeat(100) { index ->
                val path = "inputs/" + "d".repeat(470) + "_$index.txt"
                fake.putText(path, "needle")
            }
            val result = tool.execute(
                toolCommand(tool.descriptor, arguments("query" to "needle", "maxMatches" to 100)),
                context
            )
            assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
            assertTrue(result.truncated)
            assertNull(result.fullResultRef)
            assertTrue(result.dataJson!!.toByteArray(Charsets.UTF_8).size <= AgentToolResults.PERSISTED_RESULT_MAX_BYTES)
            assertTrue(resultData(result).getInt("matchCount") in 1 until 100)
        }
    }

    @Test
    fun `port failure and path guard are mapped to stable error codes`() {
        runBlocking {
            val escape = tool.execute(toolCommand(tool.descriptor, arguments("query" to "x", "path" to "../x")), context)
            assertEquals(AgentToolResultStatus.FAILED, escape.status)
            assertEquals("TOOL_INPUT_INVALID", escape.errorCode)
            assertEquals(0, fake.searchCalls)

            fake.forcedSearchFailure = AgentFileResult.Failure(
                "TOOL_FAILED",
                "The workspace search could not be completed."
            )
            val failed = tool.execute(toolCommand(tool.descriptor, arguments("query" to "x")), context)
            assertEquals(AgentToolResultStatus.FAILED, failed.status)
            assertEquals("TOOL_FAILED", failed.errorCode)
        }
    }

    @Test
    fun `search scans the artifacts root when requested`() {
        runBlocking {
            fake.putText("artifacts/report.md", "needle")
            val result = tool.execute(
                toolCommand(tool.descriptor, arguments("query" to "needle", "path" to "artifacts")),
                context
            )
            assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
            assertEquals(1, resultData(result).getInt("matchCount"))
        }
    }

    @Test
    fun `extra fields are rejected`() {
        runBlocking {
            val result = tool.execute(
                toolCommand(tool.descriptor, arguments("query" to "x", "regex" to true)),
                context
            )
            assertEquals(AgentToolResultStatus.FAILED, result.status)
            assertEquals("TOOL_INPUT_INVALID", result.errorCode)
            assertEquals(0, fake.searchCalls)
        }
    }
}
