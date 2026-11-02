package com.muyuchat.core.agent.runtime.protocol

import com.muyuchat.core.agent.contract.AgentModelTurn
import com.muyuchat.core.agent.contract.AgentTurnFinish
import com.muyuchat.core.agent.contract.AgentTurnProtocol
import com.muyuchat.core.agent.contract.AgentTurnQuestion
import com.muyuchat.core.agent.contract.AgentTurnToolCall
import com.muyuchat.core.agent.runtime.AgentControlDecoder
import com.muyuchat.core.agent.runtime.AgentControlMessage
import com.muyuchat.core.agent.runtime.AgentDecodeResult
import com.muyuchat.core.agent.runtime.AgentTurnDecodeResult
import com.muyuchat.core.agent.runtime.AgentTurnDecoder

/**
 * Adapts the strict PROMPTED_V1 envelope decoder to the provider-neutral turn contract (N05b).
 *
 * V1 keeps its exact single-envelope rules: the adapter only translates an already-validated
 * [AgentControlMessage] into an [AgentModelTurn] and marks the terminal proof as "complete V1
 * envelope". It never repairs, never accepts prose and never invents a provider call id (V1 has
 * none, so [AgentTurnToolCall.callId] stays null).
 */
class PromptedV1TurnDecoder(
    private val decoder: AgentControlDecoder
) : AgentTurnDecoder {

    override fun decode(visibleText: String): AgentTurnDecodeResult =
        when (val decoded = decoder.decode(visibleText)) {
            is AgentDecodeResult.Rejected -> AgentTurnDecodeResult.Rejected(
                errorCode = decoded.errorCode,
                safeMessage = decoded.safeMessage,
                repairHintJson = decoded.repairHintJson
            )
            is AgentDecodeResult.Accepted -> AgentTurnDecodeResult.Accepted(decoded.message.toTurn())
        }

    private fun AgentControlMessage.toTurn(): AgentModelTurn = when (this) {
        is AgentControlMessage.ToolCall -> AgentModelTurn(
            protocol = AgentTurnProtocol.PROMPTED_V1,
            finish = AgentTurnFinish.TOOL_CALL,
            calls = listOf(AgentTurnToolCall(callId = null, name = name, argumentsJson = argumentsJson)),
            terminalProof = TERMINAL_PROOF
        )
        is AgentControlMessage.Final -> AgentModelTurn(
            protocol = AgentTurnProtocol.PROMPTED_V1,
            finish = AgentTurnFinish.COMPLETED,
            text = text,
            artifactIds = artifactIds,
            terminalProof = TERMINAL_PROOF
        )
        is AgentControlMessage.AskUser -> AgentModelTurn(
            protocol = AgentTurnProtocol.PROMPTED_V1,
            finish = AgentTurnFinish.ASK_USER,
            question = AgentTurnQuestion(question = question, options = options),
            terminalProof = TERMINAL_PROOF
        )
    }

    private companion object {
        /** Opaque marker: the provider turn was a complete, fully validated V1 envelope. */
        const val TERMINAL_PROOF = "prompted_v1:envelope-complete"
    }
}
