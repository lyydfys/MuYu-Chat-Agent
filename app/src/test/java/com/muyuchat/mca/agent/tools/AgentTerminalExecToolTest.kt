package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.mca.agent.security.AgentArgumentNormalization
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTerminalExecToolTest {
    private fun command(tool: AgentTerminalExecTool, json: String) =
        ExtendedToolTestSupport.command(tool.descriptor, json)

    @Test
    fun `without a sandbox the tool reports a typed not-supported result`() = runBlocking {
        val tool = AgentTerminalExecTool(AgentTerminalSandbox.UNAVAILABLE)

        val result = tool.execute(
            command(tool, """{"command":"echo"}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.NOT_EXECUTED, result.status)
        assertEquals(AgentTerminalExecTool.TOOL_NOT_SUPPORTED, result.errorCode)
        assertFalse(result.possibleSideEffects)
    }

    @Test
    fun `whitelisted command runs through the sandbox with bounded output`() = runBlocking {
        val sandbox = FakeTerminalSandbox(AgentTerminalOutcome.Completed(0, "hello\n", "", false))
        val tool = AgentTerminalExecTool(sandbox)

        val result = tool.execute(
            command(tool, """{"args":"world","command":"echo","timeoutMillis":5000}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        val request = sandbox.requests.single()
        assertEquals("echo", request.command)
        assertEquals(listOf("world"), request.arguments)
        assertEquals(5_000L, request.timeoutMillis)
        assertEquals(AgentTerminalExecTool.MAX_OUTPUT_BYTES, request.maxOutputBytes)
        assertTrue(result.dataJson!!.contains("\"exitCode\":0"))
    }

    @Test
    fun `command outside the whitelist is denied and never dispatched`() = runBlocking {
        val sandbox = FakeTerminalSandbox()
        val tool = AgentTerminalExecTool(sandbox, whitelist = setOf("echo"))

        val result = tool.execute(
            command(tool, """{"command":"cat","args":"artifacts/x"}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.DENIED, result.status)
        assertEquals("TOOL_COMMAND_NOT_ALLOWED", result.errorCode)
        assertTrue(sandbox.requests.isEmpty())
    }

    @Test
    fun `shell syntax is rejected instead of being interpreted`() = runBlocking {
        val sandbox = FakeTerminalSandbox()
        val tool = AgentTerminalExecTool(sandbox)

        val result = tool.execute(
            command(tool, """{"args":"hi && cat /etc/passwd","command":"echo"}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("TOOL_INPUT_INVALID", result.errorCode)
        assertTrue(sandbox.requests.isEmpty())
    }

    @Test
    fun `sandbox timeout is reported as a possibly side-effecting failure`() = runBlocking {
        val sandbox = FakeTerminalSandbox(AgentTerminalOutcome.TimedOut)
        val tool = AgentTerminalExecTool(sandbox)

        val result = tool.execute(
            command(tool, """{"command":"date"}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("TOOL_TIMEOUT", result.errorCode)
        assertTrue(result.possibleSideEffects)
    }

    @Test
    fun `executor deadline cancels a slow sandbox`() = runBlocking {
        val sandbox = FakeTerminalSandbox(delayMillis = 3_000L)
        val tool = AgentTerminalExecTool(sandbox)

        val result = tool.execute(
            command(tool, """{"command":"date","timeoutMillis":1000}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("TOOL_TIMEOUT", result.errorCode)
        assertTrue(result.possibleSideEffects)
    }

    @Test
    fun `sandbox refusal is surfaced as a denial`() = runBlocking {
        val sandbox = FakeTerminalSandbox(
            AgentTerminalOutcome.Refused("TOOL_SANDBOX_REFUSED", "The sandbox refused this command.")
        )
        val tool = AgentTerminalExecTool(sandbox)

        val result = tool.execute(
            command(tool, """{"command":"echo"}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.DENIED, result.status)
        assertEquals("TOOL_SANDBOX_REFUSED", result.errorCode)
    }

    @Test
    fun `output above the host budget is truncated even if the sandbox ignores the limit`() = runBlocking {
        val oversized = "x".repeat(AgentTerminalExecTool.MAX_OUTPUT_BYTES + 1_000)
        val sandbox = FakeTerminalSandbox(AgentTerminalOutcome.Completed(0, oversized, "", false))
        val tool = AgentTerminalExecTool(sandbox)

        val result = tool.execute(
            command(tool, """{"command":"echo"}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        assertTrue(result.truncated)
    }

    @Test
    fun `too many arguments are rejected`() = runBlocking {
        val sandbox = FakeTerminalSandbox()
        val tool = AgentTerminalExecTool(sandbox)
        val args = (1..20).joinToString(" ") { "a$it" }

        val result = tool.execute(
            command(tool, """{"args":"$args","command":"echo"}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals("TOOL_INPUT_INVALID", result.errorCode)
        assertTrue(sandbox.requests.isEmpty())
    }

    @Test
    fun `caller cancellation propagates`() = runBlocking {
        val sandbox = FakeTerminalSandbox(delayMillis = 5_000L)
        val tool = AgentTerminalExecTool(sandbox)
        var cancelled = false
        val job = launch {
            try {
                tool.execute(
                    command(tool, """{"command":"echo"}"""),
                    ExtendedToolTestSupport.context()
                )
            } catch (_: CancellationException) {
                cancelled = true
                throw CancellationException("stop")
            }
        }
        delay(200)
        job.cancel()
        job.join()

        assertTrue(cancelled)
    }

    @Test
    fun `normalizer applies defaults and blocks non-whitelisted commands`() {
        val normalizer = AgentExtendedToolArgumentNormalizer()
        val descriptor = AgentExtendedToolDescriptors.TERMINAL_EXEC

        val normalized = normalizer.normalize(descriptor, """{"command":" echo ","args":"a b"}""")
        assertTrue(normalized is AgentArgumentNormalization.Normalized)
        assertEquals(
            """{"args":"a b","command":"echo","timeoutMillis":15000}""",
            (normalized as AgentArgumentNormalization.Normalized).normalizedArgumentsJson
        )

        val denied = normalizer.normalize(descriptor, """{"command":"sh"}""")
        assertTrue(denied is AgentArgumentNormalization.Rejected)

        val shell = normalizer.normalize(descriptor, """{"args":"x > y","command":"echo"}""")
        assertTrue(shell is AgentArgumentNormalization.Rejected)
    }

    @Test
    fun `process typed scope binds the argv hash`() {
        val commandJson = """{"args":"a b","command":"echo","timeoutMillis":15000}"""
        val scope = AgentExtendedToolScopes.terminalExec(
            normalizedArgumentsJson = commandJson,
            runtimeId = "sandbox-1",
            cwdHandle = "task-root",
            environmentPolicy = "minimal"
        )
        assertTrue(scope!!.scopes.single() is com.muyuchat.core.agent.contract.AgentResourceScope.Process)
        val other = AgentExtendedToolScopes.terminalExec(commandJson, "sandbox-1", "task-root", "minimal")
        assertEquals(scope, other)
        val different = AgentExtendedToolScopes.terminalExec(
            """{"args":"a c","command":"echo","timeoutMillis":15000}""",
            "sandbox-1",
            "task-root",
            "minimal"
        )
        assertTrue(different != other)
    }
}
