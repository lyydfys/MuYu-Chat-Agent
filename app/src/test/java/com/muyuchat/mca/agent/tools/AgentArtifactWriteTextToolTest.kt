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

class AgentArtifactWriteTextToolTest {
    private val fake = FakeAgentWorkspaceFilePort()
    private val tool = ArtifactWriteTextTool(fake)
    private val context = toolContext()

    @Test
    fun `create only write returns the artifact identity and freezes invocation metadata`() {
        runBlocking {
            val command = toolCommand(
                tool.descriptor,
                arguments("path" to "artifacts/report.md", "content" to "# note", "expectedOldHash" to null),
                invocationId = "inv-7",
                policyVersion = 7L
            )
            val result = tool.execute(command, context)

            assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
            assertEquals(1, result.artifactIds.size)
            assertEquals("artifact-1", result.artifactIds.single())
            val write = fake.lastWriteCall!!
            assertEquals("inv-7", write.invocationId)
            assertEquals(7L, write.policyVersion)
            assertEquals("artifacts/report.md", write.logicalPath)
            assertEquals("# note", write.content)
            assertNull(write.expectedOldHash)

            val data = resultData(result)
            assertTrue(data.getBoolean("created"))
            assertEquals("artifact-1", data.getString("artifactId"))
            assertEquals("# note".toByteArray(Charsets.UTF_8).size.toLong(), data.getLong("sizeBytes"))
            assertEquals(fake.sha256Of("artifacts/report.md"), data.getString("sha256"))
            assertEquals("artifact-1", resultEnvelope(result).getJSONArray("artifactRefs").getString(0))
        }
    }

    @Test
    fun `create only never overwrites an existing artifact`() {
        runBlocking {
            fake.putText("artifacts/report.md", "existing")
            val result = tool.execute(
                toolCommand(
                    tool.descriptor,
                    arguments("path" to "artifacts/report.md", "content" to "new", "expectedOldHash" to null)
                ),
                context
            )
            assertEquals(AgentToolResultStatus.FAILED, result.status)
            assertEquals("PRECONDITION_CHANGED", result.errorCode)
            assertEquals("existing", fake.files.getValue("artifacts/report.md").toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `overwrite requires the current SHA-256`() {
        runBlocking {
            fake.putText("artifacts/report.md", "existing")
            val currentHash = fake.sha256Of("artifacts/report.md")
            val good = tool.execute(
                toolCommand(
                    tool.descriptor,
                    arguments(
                        "path" to "artifacts/report.md",
                        "content" to "replaced",
                        "expectedOldHash" to currentHash
                    )
                ),
                context
            )
            assertEquals(AgentToolResultStatus.SUCCEEDED, good.status)
            assertFalse(resultData(good).getBoolean("created"))
            assertEquals("replaced", fake.files.getValue("artifacts/report.md").toString(Charsets.UTF_8))

            val wrongHash = tool.execute(
                toolCommand(
                    tool.descriptor,
                    arguments(
                        "path" to "artifacts/report.md",
                        "content" to "again",
                        "expectedOldHash" to "f".repeat(64)
                    )
                ),
                context
            )
            assertEquals(AgentToolResultStatus.FAILED, wrongHash.status)
            assertEquals("PRECONDITION_CHANGED", wrongHash.errorCode)
            assertEquals("replaced", fake.files.getValue("artifacts/report.md").toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `content is limited to 32 KiB of UTF-8`() {
        runBlocking {
            val result = tool.execute(
                toolCommand(
                    tool.descriptor,
                    arguments(
                        "path" to "artifacts/big.txt",
                        "content" to "a".repeat(32_769),
                        "expectedOldHash" to null
                    )
                ),
                context
            )
            assertEquals(AgentToolResultStatus.FAILED, result.status)
            assertEquals("TOOL_INPUT_INVALID", result.errorCode)
            assertEquals(0, fake.writeCalls)
        }
    }

    @Test
    fun `write target must be a file under artifacts`() {
        runBlocking {
            listOf("inputs/report.md", "artifacts", "/tmp/report.md", "../report.md", "C:\\report.md")
                .forEach { path ->
                    val result = tool.execute(
                        toolCommand(
                            tool.descriptor,
                            arguments("path" to path, "content" to "x", "expectedOldHash" to null)
                        ),
                        context
                    )
                    assertEquals("path '$path' must be rejected", AgentToolResultStatus.FAILED, result.status)
                    assertEquals("TOOL_INPUT_INVALID", result.errorCode)
                }
            assertEquals(0, fake.writeCalls)
        }
    }

    @Test
    fun `expectedOldHash must be explicit and either 64 lowercase hex or null`() {
        runBlocking {
            val missing = tool.execute(
                toolCommand(tool.descriptor, arguments("path" to "artifacts/a.txt", "content" to "x")),
                context
            )
            assertEquals(AgentToolResultStatus.FAILED, missing.status)
            assertEquals("TOOL_INPUT_INVALID", missing.errorCode)

            val malformed = tool.execute(
                toolCommand(
                    tool.descriptor,
                    arguments("path" to "artifacts/a.txt", "content" to "x", "expectedOldHash" to "ABC")
                ),
                context
            )
            assertEquals(AgentToolResultStatus.FAILED, malformed.status)
            assertEquals("TOOL_INPUT_INVALID", malformed.errorCode)
            assertEquals(0, fake.writeCalls)
        }
    }

    @Test
    fun `write is not gated by the read grant but is gated by the broker role`() {
        runBlocking {
            // The executor is only reached after the K07 broker/approval path; the read grant flag
            // must not silently block or allow a write on its own.
            val result = tool.execute(
                toolCommand(
                    tool.descriptor,
                    arguments("path" to "artifacts/a.txt", "content" to "ok", "expectedOldHash" to null)
                ),
                toolContext(workspaceReadGranted = false)
            )
            assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        }
    }

    @Test
    fun `port precondition failure is mapped with possible side effects truth`() {
        runBlocking {
            fake.forcedWriteFailure = AgentFileResult.Failure(
                "PRECONDITION_CHANGED",
                "Artifact content changed since the expected hash was recorded.",
                possibleSideEffects = true
            )
            val result = tool.execute(
                toolCommand(
                    tool.descriptor,
                    arguments("path" to "artifacts/a.txt", "content" to "x", "expectedOldHash" to null)
                ),
                context
            )
            assertEquals(AgentToolResultStatus.FAILED, result.status)
            assertEquals("PRECONDITION_CHANGED", result.errorCode)
            assertTrue(result.possibleSideEffects)
        }
    }

    @Test
    fun `stale schema is NOT_EXECUTED`() {
        runBlocking {
            val stale = toolCommand(
                tool.descriptor,
                arguments("path" to "artifacts/a.txt", "content" to "x", "expectedOldHash" to null)
            ).copy(schemaHash = "0".repeat(64))
            val result = tool.execute(stale, context)
            assertEquals(AgentToolResultStatus.NOT_EXECUTED, result.status)
            assertEquals(0, fake.writeCalls)
        }
    }
}
