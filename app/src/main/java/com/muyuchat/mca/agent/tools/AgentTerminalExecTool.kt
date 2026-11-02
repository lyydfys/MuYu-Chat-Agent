package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.FrozenToolCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * `terminal.exec` skeleton: whitelist, argv-only execution, hard timeout, output budget, cancel.
 *
 * What this slice does **not** do, on purpose:
 * - it never starts a shell: `args` is split on ASCII spaces and passed as an argv list, and any
 *   shell metacharacter is rejected instead of being interpreted;
 * - it never executes anything itself: all process handling belongs to [AgentTerminalSandbox];
 * - without an available sandbox it returns `NOT_EXECUTED` with `TOOL_NOT_SUPPORTED`, never a fake
 *   success and never a silent different transport.
 *
 * Cancellation propagates as coroutine cancellation (the tool does not swallow it); a timeout is a
 * typed `TOOL_TIMEOUT` failure with `possibleSideEffects=true` because the process may have started
 * and its effects cannot be recalled.
 */
class AgentTerminalExecTool(
    private val sandbox: AgentTerminalSandbox = AgentTerminalSandbox.UNAVAILABLE,
    private val whitelist: Set<String> = AgentTerminalWhitelist.DEFAULT
) : AgentToolExecutor {
    override val descriptor: AgentToolDescriptor = AgentExtendedToolDescriptors.TERMINAL_EXEC

    override suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult =
        runBuiltInTool(command, descriptor, context, requiresReadGrant = false) {
            if (!sandbox.isAvailable) {
                return@runBuiltInTool AgentToolResults.notExecuted(
                    command,
                    descriptor,
                    TOOL_NOT_SUPPORTED,
                    "This build has no executable terminal sandbox; terminal.exec cannot run commands."
                )
            }
            val fields = AgentToolArguments.parse(command)
            AgentToolArguments.rejectUnknown(fields, setOf("command", "args", "timeoutMillis"))
            val rawCommand = AgentToolArguments.requiredText(fields, "command", 64).trim()
            if (!AgentTerminalWhitelist.isValidCommandName(rawCommand)) {
                throw AgentToolInputException("The command name contains unsupported characters.")
            }
            if (rawCommand !in whitelist) {
                return@runBuiltInTool AgentToolResults.denied(
                    command,
                    descriptor,
                    "TOOL_COMMAND_NOT_ALLOWED",
                    "The command is not on this task's terminal whitelist."
                )
            }
            val rawArgs = AgentToolArguments.optionalText(fields, "args", "", 1_024)
            if (AgentTerminalWhitelist.containsShellSyntax(rawArgs)) {
                throw AgentToolInputException(
                    "Shell syntax is not supported; pass plain space-separated tokens."
                )
            }
            val tokens = rawArgs.split(' ').filter { it.isNotEmpty() }
            if (tokens.size > MAX_ARGUMENTS) {
                throw AgentToolInputException("Too many command arguments.")
            }
            tokens.forEach { token ->
                if (token.length > MAX_ARGUMENT_CHARS) {
                    throw AgentToolInputException("A command argument exceeds its length limit.")
                }
                if (token.any { it.isISOControl() }) {
                    throw AgentToolInputException("A command argument contains a control character.")
                }
            }
            val timeoutMillis = AgentToolArguments.optionalInteger(
                fields,
                "timeoutMillis",
                DEFAULT_TIMEOUT_MILLIS,
                MIN_TIMEOUT_MILLIS,
                MAX_TIMEOUT_MILLIS
            )

            val outcome = try {
                withTimeout(timeoutMillis) {
                    sandbox.execute(
                        AgentTerminalRequest(
                            invocationId = command.invocationId,
                            command = rawCommand,
                            arguments = tokens,
                            timeoutMillis = timeoutMillis,
                            maxOutputBytes = MAX_OUTPUT_BYTES
                        )
                    )
                }
            } catch (cancelled: CancellationException) {
                if (cancelled is TimeoutCancellationException) {
                    return@runBuiltInTool AgentToolResults.failure(
                        command,
                        descriptor,
                        "TOOL_TIMEOUT",
                        "The command did not finish before its deadline; it may still have run.",
                        possibleSideEffects = true
                    )
                }
                throw cancelled
            }

            when (outcome) {
                AgentTerminalOutcome.TimedOut -> AgentToolResults.failure(
                    command,
                    descriptor,
                    "TOOL_TIMEOUT",
                    "The command did not finish before its deadline; it may still have run.",
                    possibleSideEffects = true
                )
                AgentTerminalOutcome.Unsupported -> AgentToolResults.notExecuted(
                    command,
                    descriptor,
                    TOOL_NOT_SUPPORTED,
                    "The executable sandbox refused the request as unsupported."
                )
                is AgentTerminalOutcome.Refused -> AgentToolResults.denied(
                    command,
                    descriptor,
                    outcome.code.ifBlank { "TOOL_COMMAND_NOT_ALLOWED" },
                    outcome.safeMessage.ifBlank { "The executable sandbox refused the command." }
                )
                is AgentTerminalOutcome.Completed -> {
                    val stdout = bounded(outcome.stdout)
                    val stderr = bounded(outcome.stderr)
                    val truncated = outcome.truncated ||
                        stdout.length < outcome.stdout.length ||
                        stderr.length < outcome.stderr.length
                    val data = JSONObject()
                        .put("command", rawCommand)
                        .put("arguments", tokens.size)
                        .put("exitCode", outcome.exitCode)
                        .put("stdout", stdout)
                        .put("stderr", stderr)
                        .put("truncated", truncated)
                    AgentToolResults.success(
                        command = command,
                        descriptor = descriptor,
                        summary = "$rawCommand exited with code ${outcome.exitCode}.",
                        data = data,
                        truncated = truncated
                    )
                }
            }
        }

    /** Hard cap applied again on the host side even if a sandbox returns more than requested. */
    private fun bounded(text: String): String {
        if (AgentTextWriteSupport.utf8Size(text) <= MAX_OUTPUT_BYTES) return text
        var end = text.length
        while (end > 0 && AgentTextWriteSupport.utf8Size(text.substring(0, end)) > MAX_OUTPUT_BYTES) {
            end -= 1
        }
        return text.substring(0, end)
    }

    companion object {
        /** Not part of the core contract codes yet; keep the TOOL_* family shape and document it. */
        const val TOOL_NOT_SUPPORTED: String = "TOOL_NOT_SUPPORTED"

        const val MAX_OUTPUT_BYTES: Int = 64 * 1024
        const val MAX_ARGUMENTS: Int = 16
        const val MAX_ARGUMENT_CHARS: Int = 256
        const val DEFAULT_TIMEOUT_MILLIS: Long = 15_000L
        const val MIN_TIMEOUT_MILLIS: Long = 1_000L
        const val MAX_TIMEOUT_MILLIS: Long = 30_000L
    }
}
