package com.muyuchat.core.agent.runtime.protocol

import com.muyuchat.core.agent.contract.AgentModelTurn
import com.muyuchat.core.agent.contract.AgentTurnFinish
import com.muyuchat.core.agent.contract.AgentTurnProtocol
import com.muyuchat.core.agent.contract.AgentTurnQuestion
import com.muyuchat.core.agent.contract.AgentTurnToolCall
import com.muyuchat.core.agent.runtime.AgentControlMessage
import com.muyuchat.core.agent.runtime.AgentTurnDecodeResult
import com.muyuchat.core.agent.runtime.AgentTurnDecoder
import com.muyuchat.core.agent.runtime.testing.AgentTestFixtures
import com.muyuchat.core.agent.runtime.validation.AgentV1ToolSpecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** N05b: PROMPTED_V1 becomes one producer of the canonical turn contract. */
class PromptedV1TurnDecoderTest {

    private val codec = StrictAgentJsonCodec()

    private fun decoder(): AgentTurnDecoder = PromptedV1TurnDecoder(
        PromptedV1Decoder({ setOf(AgentV1ToolSpecs.WORKSPACE_READ_TEXT) }, codec)
    )

    private fun accepted(visibleText: String): AgentModelTurn {
        val decoded = decoder().decode(visibleText)
        assertTrue("expected an accepted turn, got $decoded", decoded is AgentTurnDecodeResult.Accepted)
        return (decoded as AgentTurnDecodeResult.Accepted).turn
    }

    @Test
    fun toolEnvelopeBecomesAToolCallTurnWithoutInventingAProviderCallId() {
        val turn = accepted(
            AgentTestFixtures.toolEnvelope(
                name = AgentV1ToolSpecs.WORKSPACE_READ_TEXT,
                argumentsJson = """{"path":"inputs/brief.txt"}"""
            )
        )

        assertEquals(AgentTurnProtocol.PROMPTED_V1, turn.protocol)
        assertEquals(AgentTurnFinish.TOOL_CALL, turn.finish)
        assertEquals(1, turn.calls.size)
        assertEquals(null, turn.calls.single().callId)
        assertEquals(AgentV1ToolSpecs.WORKSPACE_READ_TEXT, turn.calls.single().name)
        assertEquals("""{"path":"inputs/brief.txt"}""", turn.calls.single().argumentsJson)
        assertEquals("prompted_v1:envelope-complete", turn.terminalProof)
    }

    @Test
    fun finalEnvelopeCarriesTextArtifactsAndTheCompleteProof() {
        val turn = accepted(
            AgentTestFixtures.finalEnvelope(text = "完成", artifactIds = listOf("artifact-1"))
        )

        assertEquals(AgentTurnFinish.COMPLETED, turn.finish)
        assertEquals("完成", turn.text)
        assertEquals(listOf("artifact-1"), turn.artifactIds)
        val execution = turn.toExecutionMessage()
        assertTrue(execution is AgentTurnExecution.Executable)
        val message = (execution as AgentTurnExecution.Executable).message
        assertTrue(message is AgentControlMessage.Final)
        assertEquals("完成", (message as AgentControlMessage.Final).text)
    }

    @Test
    fun askUserEnvelopeBecomesAnAskUserTurnExecutableByTheLoop() {
        val turn = accepted(
            AgentTestFixtures.askUserEnvelope(
                question = "面向哪类读者？",
                options = listOf("开发者", "管理者")
            )
        )

        assertEquals(AgentTurnFinish.ASK_USER, turn.finish)
        assertEquals(AgentTurnQuestion("面向哪类读者？", listOf("开发者", "管理者")), turn.question)
        val execution = turn.toExecutionMessage() as AgentTurnExecution.Executable
        assertTrue(execution.message is AgentControlMessage.AskUser)
    }

    @Test
    fun protocolFailuresPassThroughWithTheSameRepairHint() {
        val decoded = decoder().decode("""{"protocol":"mca.agent.v1","kind":"unknown"}""")

        assertTrue(decoded is AgentTurnDecodeResult.Rejected)
        decoded as AgentTurnDecodeResult.Rejected
        assertEquals(AgentProtocolErrorCodes.UNKNOWN_KIND, decoded.errorCode)
        assertTrue(decoded.repairHintJson.isNotEmpty())
    }

    @Test
    fun aBatchTurnIsRefusedUntilTheBatchPlannerExists() {
        val batch = AgentModelTurn(
            protocol = AgentTurnProtocol.NATIVE,
            finish = AgentTurnFinish.TOOL_CALL,
            calls = listOf(
                AgentTurnToolCall("call-1", "workspace.list", "{}"),
                AgentTurnToolCall("call-2", "workspace.read_text", """{"path":"inputs/a"}""")
            )
        )

        val execution = batch.toExecutionMessage()

        assertTrue(execution is AgentTurnExecution.Unsupported)
        assertEquals(
            AgentProtocolErrorCodes.TURN_BATCH_UNSUPPORTED,
            (execution as AgentTurnExecution.Unsupported).errorCode
        )
    }

    @Test
    fun aTruncatedTurnIsNeverExecutedAsAFinalMessage() {
        val truncated = AgentModelTurn(
            protocol = AgentTurnProtocol.NATIVE,
            finish = AgentTurnFinish.TRUNCATED,
            text = "partial"
        )

        val execution = truncated.toExecutionMessage()

        assertTrue(execution is AgentTurnExecution.Unsupported)
        assertEquals(
            AgentProtocolErrorCodes.TURN_NOT_EXECUTABLE,
            (execution as AgentTurnExecution.Unsupported).errorCode
        )
    }
}
