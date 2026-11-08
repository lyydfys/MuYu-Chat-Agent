package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retry guard: block only an identical repeat of a call that has just failed.
 *
 * The negative cases matter as much as the positive one. Blocking a *successful* repeat would break
 * legitimate re-reads; blocking a call with *different* arguments would break the normal fix
 * ("the path was wrong, try the right one"). Both would be worse than the wasted tokens this guard
 * exists to prevent.
 */
class AgentRetryGuardTest {

    @Test
    fun `an identical call that failed is blocked`() {
        val entries = listOf(
            call("workspace.read_text", """{"path":"inputs/missing.txt"}"""),
            result("workspace.read_text", "r1", failed = true)
        )

        assertTrue(
            shouldBlockIdenticalRetry(entries, "workspace.read_text", argumentsHash("""{"path":"inputs/missing.txt"}"""))
        )
    }

    @Test
    fun `an identical call that succeeded is not blocked`() {
        val entries = listOf(
            call("workspace.read_text", """{"path":"inputs/brief.txt"}"""),
            result("workspace.read_text", "r1", failed = false)
        )

        assertFalse(
            shouldBlockIdenticalRetry(entries, "workspace.read_text", argumentsHash("""{"path":"inputs/brief.txt"}"""))
        )
    }

    @Test
    fun `the same tool with different arguments is not blocked`() {
        val entries = listOf(
            call("workspace.read_text", """{"path":"inputs/missing.txt"}"""),
            result("workspace.read_text", "r1", failed = true)
        )

        assertFalse(
            shouldBlockIdenticalRetry(entries, "workspace.read_text", argumentsHash("""{"path":"inputs/brief.txt"}"""))
        )
    }

    @Test
    fun `a failure older than the window is not blocked`() {
        // Six newer calls push the failing one out of the 5-entry window.
        val entries = ArrayList<AgentTranscriptEntry>()
        entries += call("workspace.read_text", """{"path":"inputs/missing.txt"}""")
        entries += result("workspace.read_text", "r0", failed = true)
        repeat(AGENT_RETRY_GUARD_WINDOW) { index ->
            entries += call("workspace.read_text", """{"path":"inputs/file$index.txt"}""")
            entries += result("workspace.read_text", "r${index + 1}", failed = false)
        }

        assertFalse(
            shouldBlockIdenticalRetry(
                entries,
                "workspace.read_text",
                argumentsHash("""{"path":"inputs/missing.txt"}""")
            )
        )
    }

    @Test
    fun `a failure still inside the window is blocked`() {
        val entries = ArrayList<AgentTranscriptEntry>()
        repeat(AGENT_RETRY_GUARD_WINDOW - 1) { index ->
            entries += call("workspace.read_text", """{"path":"inputs/file$index.txt"}""")
            entries += result("workspace.read_text", "ok$index", failed = false)
        }
        entries += call("workspace.read_text", """{"path":"inputs/missing.txt"}""")
        entries += result("workspace.read_text", "r-fail", failed = true)

        assertTrue(
            shouldBlockIdenticalRetry(
                entries,
                "workspace.read_text",
                argumentsHash("""{"path":"inputs/missing.txt"}""")
            )
        )
    }

    @Test
    fun `an empty history never blocks`() {
        assertFalse(shouldBlockIdenticalRetry(emptyList(), "workspace.read_text", "a".repeat(64)))
    }

    @Test
    fun `a blank tool key or hash never blocks`() {
        val entries = listOf(
            call("workspace.read_text", """{"path":"inputs/missing.txt"}"""),
            result("workspace.read_text", "r1", failed = true)
        )

        assertFalse(shouldBlockIdenticalRetry(entries, "", argumentsHash("""{"path":"inputs/missing.txt"}""")))
        assertFalse(shouldBlockIdenticalRetry(entries, "workspace.read_text", ""))
    }

    @Test
    fun `the frozen call hash also identifies the same call`() {
        // A native call whose arguments cannot be re-derived still matches through its call hash.
        val entries = listOf(
            AgentTranscriptEntry.AssistantEnvelope(rawEnvelope = """{"opaque":"envelope"}"""),
            AgentTranscriptEntry.ToolResult(
                invocationId = "inv-1",
                callHash = FROZEN_CALL_HASH,
                toolKey = "workspace.read_text",
                result = observation(failed = true)
            )
        )

        assertTrue(
            shouldBlockIdenticalRetry(entries, "workspace.read_text", FROZEN_CALL_HASH)
        )
    }

    @Test
    fun `advice names the tool the reason and a different approach`() {
        val advice = retryAdvice("workspace.read_text", "文件不存在")

        assertTrue(advice.contains("workspace.read_text"))
        assertTrue(advice.contains("文件不存在"))
        assertTrue(advice.contains("不要原样重试") || advice.contains("而不是原样重试"))
    }

    @Test
    fun `advice stays bounded and single line`() {
        val advice = retryAdvice("tool", "很长的原因\n".repeat(500))

        assertTrue("advice must be one line", advice.none { it == '\n' })
        assertTrue("advice must stay short", advice.length < 260)
        assertNotEquals("", advice)
    }

    @Test
    fun `advice survives a blank tool and reason`() {
        val advice = retryAdvice("  ", "   ")

        assertTrue(advice.isNotBlank())
        assertTrue(advice.contains("原因未知"))
    }

    private fun call(toolKey: String, argumentsJson: String): AgentTranscriptEntry =
        AgentTranscriptEntry.AssistantEnvelope(
            rawEnvelope = """{"protocol":"mca.agent.v1","kind":"tool","call":{"name":"$toolKey","arguments":$argumentsJson}}"""
        )

    private fun result(toolKey: String, invocationId: String, failed: Boolean): AgentTranscriptEntry =
        AgentTranscriptEntry.ToolResult(
            invocationId = invocationId,
            callHash = "callhash-$invocationId",
            toolKey = toolKey,
            result = observation(failed)
        )

    private fun observation(failed: Boolean): AgentJsonValue = AgentJsonValue.Obj(
        listOf(
            "schemaVersion" to AgentJsonValue.Num("1"),
            "status" to AgentJsonValue.Str(if (failed) "FAILED" else "SUCCEEDED"),
            "ok" to AgentJsonValue.Bool(!failed),
            "summary" to AgentJsonValue.Str(if (failed) "File not found." else "ok"),
            "artifactIds" to AgentJsonValue.Arr(emptyList()),
            "truncated" to AgentJsonValue.Bool(false),
            "possibleSideEffects" to AgentJsonValue.Bool(false)
        )
    )

    /** Same canonicalization the validator applies, so the guard sees what the runtime saw. */
    private fun argumentsHash(argumentsJson: String): String {
        val parsed = StrictAgentJsonCodec().parse(argumentsJson)
        val value = (parsed as com.muyuchat.core.agent.runtime.AgentJsonParseResult.Parsed).value
        return AgentHashes.sha256HexUtf8(
            StrictAgentJsonCodec().write(AgentJsonValues.canonical(value))
        )
    }

    private companion object {
        const val FROZEN_CALL_HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
