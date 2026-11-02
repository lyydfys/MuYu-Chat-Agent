package com.muyuchat.core.agent.runtime.protocol

import com.muyuchat.core.agent.contract.AgentModelTurn
import com.muyuchat.core.agent.contract.AgentTurnFinish
import com.muyuchat.core.agent.runtime.AgentControlMessage

/**
 * Maps a canonical [AgentModelTurn] onto the execution decision the current run strategy understands.
 *
 * Batch turns are intentionally not executed yet: the bounded batch planner is N06, so more than one
 * call is reported as an unsupported turn instead of silently executing only the first call. The
 * loop turns [AgentTurnExecution.Unsupported] into the same bounded protocol-repair path as any
 * other invalid model output.
 */
sealed interface AgentTurnExecution {
    data class Executable(val message: AgentControlMessage) : AgentTurnExecution

    data class Unsupported(val errorCode: String, val safeMessage: String) : AgentTurnExecution
}

fun AgentModelTurn.toExecutionMessage(): AgentTurnExecution = when (finish) {
    AgentTurnFinish.TOOL_CALL -> {
        val call = calls.singleOrNull()
        if (call == null) {
            AgentTurnExecution.Unsupported(
                AgentProtocolErrorCodes.TURN_BATCH_UNSUPPORTED,
                "This run executes one tool call per turn; batch turns are not enabled yet."
            )
        } else {
            AgentTurnExecution.Executable(
                AgentControlMessage.ToolCall(name = call.name, argumentsJson = call.argumentsJson)
            )
        }
    }

    AgentTurnFinish.ASK_USER -> {
        val question = question
        if (question == null) {
            AgentTurnExecution.Unsupported(
                AgentProtocolErrorCodes.TURN_NOT_EXECUTABLE,
                "An ask-user turn must carry its question."
            )
        } else {
            AgentTurnExecution.Executable(
                AgentControlMessage.AskUser(question = question.question, options = question.options)
            )
        }
    }

    AgentTurnFinish.COMPLETED -> AgentTurnExecution.Executable(
        AgentControlMessage.Final(text = text, artifactIds = artifactIds)
    )

    AgentTurnFinish.TRUNCATED,
    AgentTurnFinish.REFUSED,
    AgentTurnFinish.ERROR,
    AgentTurnFinish.EOF -> AgentTurnExecution.Unsupported(
        AgentProtocolErrorCodes.TURN_NOT_EXECUTABLE,
        "The model turn did not reach an executable terminal state ($finish)."
    )
}
