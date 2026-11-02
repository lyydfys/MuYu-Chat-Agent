package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.FrozenToolCommand
import kotlinx.coroutines.CancellationException

/**
 * Shared execution envelope for the four built-in tools.
 *
 * The broker (K07) has already performed the approval/TTL/epoch checks before calling the executor,
 * so this wrapper only enforces the invariants the executor itself can still observe: the frozen
 * command must target this exact tool version/schema and a read command must still see a live
 * workspace read grant. Cancellation is always propagated, never converted into a result.
 */
internal suspend fun runBuiltInTool(
    command: FrozenToolCommand,
    descriptor: AgentToolDescriptor,
    context: AgentToolContext,
    requiresReadGrant: Boolean,
    block: suspend () -> AgentToolResult
): AgentToolResult {
    if (command.toolKey != descriptor.toolKey) {
        return AgentToolResults.notExecuted(
            command,
            descriptor,
            AgentErrorCodes.TOOL_NOT_FOUND,
            "The frozen command does not target this registered tool."
        )
    }
    if (command.toolVersion != descriptor.version || command.schemaHash != descriptor.schemaHash) {
        return AgentToolResults.notExecuted(
            command,
            descriptor,
            AgentErrorCodes.TOOL_INPUT_INVALID,
            "The frozen command schema does not match the current tool descriptor."
        )
    }
    if (requiresReadGrant && !context.workspaceReadGranted) {
        return AgentToolResults.denied(
            command,
            descriptor,
            AgentErrorCodes.PERMISSION_DENIED,
            "Workspace read scope is not active for this task."
        )
    }
    return try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (invalid: AgentToolInputException) {
        AgentToolResults.invalidArguments(
            command,
            descriptor,
            invalid.message ?: "Tool arguments are invalid."
        )
    } catch (unexpected: Exception) {
        AgentToolResults.failure(
            command,
            descriptor,
            AgentErrorCodes.TOOL_FAILED,
            "Tool execution failed without a safe detail."
        )
    }
}
