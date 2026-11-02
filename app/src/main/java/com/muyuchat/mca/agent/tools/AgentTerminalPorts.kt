package com.muyuchat.mca.agent.tools

/**
 * One command execution request. It is constructed only by [AgentTerminalExecTool]; the sandbox
 * never receives model text directly, only the validated command name and its argv tokens.
 */
data class AgentTerminalRequest(
    val invocationId: String,
    val command: String,
    val arguments: List<String>,
    val timeoutMillis: Long,
    val maxOutputBytes: Int
)

/** Typed sandbox outcome. [Unsupported] is the honest answer when no executable sandbox exists. */
sealed interface AgentTerminalOutcome {
    data class Completed(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val truncated: Boolean
    ) : AgentTerminalOutcome

    data object TimedOut : AgentTerminalOutcome

    data object Unsupported : AgentTerminalOutcome

    data class Refused(val code: String, val safeMessage: String) : AgentTerminalOutcome
}

/**
 * Executable sandbox seam for `terminal.exec`.
 *
 * This build has **no** sandbox implementation: an Android app cannot start an arbitrary shell, and
 * a general-purpose PTY/JNI shell without FD, filesystem and network isolation is explicitly out of
 * bounds (N08f/N10; TOOL-17). The default [UNAVAILABLE] instance exists so the tool can report a
 * typed `TOOL_NOT_SUPPORTED` instead of pretending to run anything. A future sandbox must own the
 * process group, enforce the timeout by killing the group, and cap stdout/stderr itself.
 */
interface AgentTerminalSandbox {
    val isAvailable: Boolean

    suspend fun execute(request: AgentTerminalRequest): AgentTerminalOutcome

    companion object {
        val UNAVAILABLE: AgentTerminalSandbox = object : AgentTerminalSandbox {
            override val isAvailable: Boolean = false

            override suspend fun execute(request: AgentTerminalRequest): AgentTerminalOutcome =
                AgentTerminalOutcome.Unsupported
        }
    }
}

/**
 * Host-owned command whitelist for `terminal.exec`.
 *
 * The list is deliberately tiny and free of interpreters (`sh`, `bash`, `python`, ...) because a
 * shell interpreter would turn a one-token whitelist into arbitrary execution. A sandbox may narrow
 * this list further, never widen the executor's own validation.
 */
object AgentTerminalWhitelist {
    val DEFAULT: Set<String> = setOf("echo", "pwd", "date")

    private val COMMAND = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

    fun isValidCommandName(command: String): Boolean = COMMAND.matches(command)

    /**
     * Shell metacharacters are rejected rather than passed through: `args` is not parsed by a shell,
     * so accepting `&&` or `$(...)` would silently do something different from what the model asked
     * for. Whitespace-separated tokens only (no quoting, no globbing).
     */
    fun containsShellSyntax(text: String): Boolean =
        text.any { it == ';' || it == '|' || it == '&' || it == '$' || it == '>' || it == '<' ||
            it == '`' || it == '\n' || it == '\r' || it == '*' || it == '?' }
}
