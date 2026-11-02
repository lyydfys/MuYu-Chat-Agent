package com.muyuchat.core.agent.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** N05b contract invariants for the provider-neutral model turn. */
class AgentModelTurnTest {

    @Test
    fun aToolCallTurnCarriesAtLeastOneCallAndNoFinalTextByDefault() {
        val turn = AgentModelTurn(
            protocol = AgentTurnProtocol.NATIVE,
            finish = AgentTurnFinish.TOOL_CALL,
            calls = listOf(AgentTurnToolCall(callId = "call-1", name = "workspace.list", argumentsJson = "{}"))
        )

        assertEquals(1, turn.calls.size)
        assertEquals("", turn.text)
        assertEquals(null, turn.question)
    }

    @Test
    fun onlyAToolCallTurnMayCarryCalls() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentModelTurn(
                protocol = AgentTurnProtocol.PROMPTED_V1,
                finish = AgentTurnFinish.COMPLETED,
                calls = listOf(AgentTurnToolCall(null, "workspace.list", "{}"))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentModelTurn(
                protocol = AgentTurnProtocol.PROMPTED_V1,
                finish = AgentTurnFinish.TOOL_CALL
            )
        }
    }

    @Test
    fun anAskUserTurnMustCarryAQuestionAndAQuestionIsOnlyValidThere() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentModelTurn(protocol = AgentTurnProtocol.PROMPTED_V1, finish = AgentTurnFinish.ASK_USER)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentModelTurn(
                protocol = AgentTurnProtocol.PROMPTED_V1,
                finish = AgentTurnFinish.COMPLETED,
                question = AgentTurnQuestion("which one?")
            )
        }
        val turn = AgentModelTurn(
            protocol = AgentTurnProtocol.PROMPTED_V1,
            finish = AgentTurnFinish.ASK_USER,
            question = AgentTurnQuestion("which one?", listOf("a", "b"))
        )
        assertEquals(2, turn.question!!.options.size)
    }

    @Test
    fun artifactReferencesAreUniqueAndBounded() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentModelTurn(
                protocol = AgentTurnProtocol.PROMPTED_V1,
                finish = AgentTurnFinish.COMPLETED,
                artifactIds = listOf("artifact-1", "artifact-1")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentModelTurn(
                protocol = AgentTurnProtocol.PROMPTED_V1,
                finish = AgentTurnFinish.COMPLETED,
                artifactIds = (1..21).map { "artifact-$it" }
            )
        }
    }

    @Test
    fun usageAndTerminalProofStayOpaqueProviderFacts() {
        val turn = AgentModelTurn(
            protocol = AgentTurnProtocol.NATIVE,
            finish = AgentTurnFinish.COMPLETED,
            text = "done",
            usage = AgentModelUsage(inputTokens = 10, outputTokens = 3, estimated = false),
            terminalProof = "response.completed:completed"
        )

        assertEquals(10, turn.usage!!.inputTokens)
        assertEquals("response.completed:completed", turn.terminalProof)
    }
}
