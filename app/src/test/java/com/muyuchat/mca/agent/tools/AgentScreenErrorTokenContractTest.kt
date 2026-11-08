package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolResultStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freezes the screen tool's error tokens and their guidance text.
 *
 * These strings are not just diagnostics: the model reads them back from a failed tool call and decides
 * what to tell the user. On a real device that produced the behaviour we want to keep — the run asked
 * the user "开启 MCA 无障碍服务后重试 / 暂时跳过" and parked in WAITING_USER — and that behaviour only
 * survives if the token and the instruction stay recognisable. Renaming a token or softening the message
 * silently degrades the advice the model can give.
 *
 * So this class pins three things per token: the literal wire value, the fact that it still reaches the
 * model as a typed failure, and that the message still names the action the user must take.
 */
class AgentScreenErrorTokenContractTest {

    @Test
    fun serviceDisabledTokenIsTheExactLiteral() {
        // The literal, not the constant reference: a rename of the property must not slip through.
        assertEquals("SCREEN_SERVICE_DISABLED", AgentScreenErrors.SERVICE_DISABLED)
    }

    @Test
    fun screenUnavailableTokenIsTheExactLiteral() {
        assertEquals("SCREEN_UNAVAILABLE", AgentScreenErrors.SCREEN_UNAVAILABLE)
    }

    @Test
    fun theTwoScreenTokensStayDistinct() {
        // A disabled service is actionable by the user; an unavailable screen is a runtime failure.
        // Collapsing them would make the model suggest a settings change for something settings cannot fix.
        assertTrue(AgentScreenErrors.SERVICE_DISABLED != AgentScreenErrors.SCREEN_UNAVAILABLE)
    }

    @Test
    fun serviceDisabledMessageTellsTheModelWhatToAskTheUserToEnable() {
        val result = screen(AgentScreenOutcome.ServiceDisabled, """{"action":"read"}""")

        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("SCREEN_SERVICE_DISABLED", result.errorCode)
        val text = result.summary.lowercase()
        // Both halves of the instruction the model paraphrases on the device.
        assertTrue(
            "the message must name the accessibility service, got: ${result.summary}",
            text.contains("accessibility")
        )
        assertTrue(
            "the message must name settings as the place to fix it, got: ${result.summary}",
            text.contains("settings")
        )
        assertTrue(
            "the message must ask the model to involve the user, got: ${result.summary}",
            text.contains("ask the user")
        )
        assertTrue(
            "the message must name our own service, got: ${result.summary}",
            text.contains("mca")
        )
    }

    @Test
    fun screenUnavailableKeepsThePortReasonAndIsNotAdvertisedAsActionable() {
        val result = screen(
            AgentScreenOutcome.Failed("no clickable control matched the request"),
            """{"action":"tap","text":"不存在的按钮"}"""
        )

        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("SCREEN_UNAVAILABLE", result.errorCode)
        // The port's own reason is passed through verbatim: the model needs the concrete cause.
        assertEquals("no clickable control matched the request", result.summary)
    }

    @Test
    fun unknownArgumentsUseTheirOwnTokenAndNeverReachThePort() {
        val port = CountingPort()
        val result = runBlocking {
            AgentScreenTool(port).execute(
                toolCommand(AgentScreenTool.DESCRIPTOR, """{"action":"reboot"}"""),
                toolContext()
            )
        }

        assertEquals("SCREEN_ARGUMENTS_INVALID", result.errorCode)
        assertEquals("invalid arguments must not act", 0, port.calls)
    }

    private fun screen(outcome: AgentScreenOutcome, json: String) = runBlocking {
        AgentScreenTool(FixedPort(outcome)).execute(
            toolCommand(AgentScreenTool.DESCRIPTOR, json),
            toolContext()
        )
    }

    private class FixedPort(private val outcome: AgentScreenOutcome) : AgentScreenPort {
        override suspend fun act(action: AgentScreenAction): AgentScreenOutcome = outcome
    }

    private class CountingPort : AgentScreenPort {
        var calls: Int = 0
            private set

        override suspend fun act(action: AgentScreenAction): AgentScreenOutcome {
            calls += 1
            return AgentScreenOutcome.Observed("")
        }
    }
}
