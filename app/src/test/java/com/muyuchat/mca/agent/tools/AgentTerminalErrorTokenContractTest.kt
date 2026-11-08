package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolResultStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freezes the terminal tool's "there is no sandbox" token and its guidance text.
 *
 * Same reason as [AgentScreenErrorTokenContractTest]: the model reads the failed call's `errorCode` and
 * message and turns them into advice for the user. `TOOL_NOT_SUPPORTED` is the token that means "this
 * build cannot run commands at all" — as opposed to `TOOL_TIMEOUT` (it ran and took too long) or a
 * whitelist `DENIED` (it ran and was refused). If those collapse, the model starts suggesting retries
 * for a capability that does not exist in this build.
 */
class AgentTerminalErrorTokenContractTest {

    @Test
    fun notSupportedTokenIsTheExactLiteral() {
        assertEquals("TOOL_NOT_SUPPORTED", AgentTerminalExecTool.TOOL_NOT_SUPPORTED)
    }

    @Test
    fun anUnavailableSandboxReportsNotSupportedAndNeverExecutes() {
        val sandbox = FakeTerminalSandbox(available = false)
        val tool = AgentTerminalExecTool(sandbox)

        val result = runBlocking {
            tool.execute(
                ExtendedToolTestSupport.command(
                    tool.descriptor,
                    """{"args":"world","command":"echo"}"""
                ),
                ExtendedToolTestSupport.context()
            )
        }

        assertEquals("TOOL_NOT_SUPPORTED", result.errorCode)
        assertEquals(AgentToolResultStatus.NOT_EXECUTED, result.status)
        // Nothing ran: a missing sandbox must not advertise side effects.
        assertFalse(result.possibleSideEffects)
        assertTrue("the sandbox must never be called", sandbox.requests.isEmpty())
    }

    @Test
    fun theNotSupportedMessageSaysWhatIsMissingAndWhatCannotHappen() {
        val sandbox = FakeTerminalSandbox(available = false)
        val tool = AgentTerminalExecTool(sandbox)

        val result = runBlocking {
            tool.execute(
                ExtendedToolTestSupport.command(
                    tool.descriptor,
                    """{"args":"world","command":"echo"}"""
                ),
                ExtendedToolTestSupport.context()
            )
        }

        val text = result.summary.lowercase()
        assertTrue(
            "the message must name the missing sandbox, got: ${result.summary}",
            text.contains("sandbox")
        )
        assertTrue(
            "the message must say this build cannot run commands, got: ${result.summary}",
            text.contains("cannot run")
        )
        assertTrue(
            "the message must not read as a transient failure, got: ${result.summary}",
            !text.contains("retry") && !text.contains("temporar")
        )
    }

    @Test
    fun aDeniedCommandUsesADifferentOutcomeThanNotSupported() {
        val sandbox = FakeTerminalSandbox()
        val tool = AgentTerminalExecTool(sandbox, whitelist = setOf("echo"))

        val result = runBlocking {
            tool.execute(
                ExtendedToolTestSupport.command(
                    tool.descriptor,
                    """{"command":"cat","args":"artifacts/x"}"""
                ),
                ExtendedToolTestSupport.context()
            )
        }

        // A refusal is a policy decision on a sandbox that exists; it must not reuse the
        // "this build cannot do it" token.
        assertTrue(
            "a whitelist refusal must not be reported as TOOL_NOT_SUPPORTED",
            result.errorCode != AgentTerminalExecTool.TOOL_NOT_SUPPORTED
        )
        assertEquals(AgentToolResultStatus.DENIED, result.status)
    }
}
