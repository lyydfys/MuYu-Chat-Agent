package com.muyuchat.core.dsh.contract.runtime

/**
 * How a DSH terminal session is actually backed.
 *
 * The distinction is load-bearing: on targetSdk 36 an app may execute its APK native libraries
 * but not write+execute scripts in its own home. A "terminal" that only forwards pipe output is
 * NOT a PTY (no controlling tty, no `isatty`, no SIGWINCH, no job control) and must be reported
 * as [PIPE_FALLBACK] instead of being presented as a working terminal.
 */
enum class DshTerminalTransport {
    REAL_PTY,
    PIPE_FALLBACK
}

/** Typed reason a real PTY cannot be provided. Never a silent success. */
enum class DshTerminalUnavailableReason {
    /** The `libdshpty.so` native library is not packaged in this build. */
    NATIVE_LIBRARY_MISSING,
    /** The library exists but the JVM could not load it. */
    NATIVE_LOAD_FAILED,
    /** The native open of the master/slave pair or the child fork failed. */
    OPEN_FAILED,
    /** The platform denies the operation (no devpts, no exec of the payload shell). */
    PLATFORM_UNSUPPORTED
}

/** What the runtime can actually do for terminals in this build. */
sealed interface DshTerminalCapability {
    val transport: DshTerminalTransport

    /** Real kernel PTY backed by [nativeLibrary]. */
    data class RealPty(val nativeLibrary: String = "libdshpty.so") : DshTerminalCapability {
        override val transport: DshTerminalTransport = DshTerminalTransport.REAL_PTY
    }

    /**
     * Explicit degradation: pipes only. [reason] is the concrete missing condition (not a device
     * classification) and must be surfaced to the caller instead of being hidden.
     */
    data class PipeFallback(
        val reason: DshTerminalUnavailableReason,
        val detail: String
    ) : DshTerminalCapability {
        override val transport: DshTerminalTransport = DshTerminalTransport.PIPE_FALLBACK
    }

    /** Nothing can be started at all; the caller must not offer a terminal entry. */
    data class Unavailable(
        val reason: DshTerminalUnavailableReason,
        val detail: String
    ) : DshTerminalCapability {
        override val transport: DshTerminalTransport = DshTerminalTransport.PIPE_FALLBACK
    }
}

/** Terminal geometry. Values outside [MIN]..[MAX] are rejected by construction. */
data class DshTerminalSize(val columns: Int, val rows: Int) {
    init {
        require(columns in MIN..MAX) { "dsh: terminal columns out of range: $columns" }
        require(rows in MIN..MAX) { "dsh: terminal rows out of range: $rows" }
    }

    companion object {
        const val MIN = 1
        const val MAX = 1_000
        val DEFAULT = DshTerminalSize(columns = 80, rows = 24)

        /** Clamps unvalidated UI/pty values into the legal range; null means "unusable input". */
        fun sanitized(columns: Int, rows: Int): DshTerminalSize? =
            if (columns in MIN..MAX && rows in MIN..MAX) DshTerminalSize(columns, rows) else null
    }
}

/**
 * One open request. [command] is an exact argv (never a shell string): element 0 is the
 * executable, the rest are forwarded verbatim so spaces and quotes stay one argument.
 */
data class DshTerminalOpenRequest(
    val command: List<String>,
    val workingDirectory: String,
    val environment: Map<String, String> = emptyMap(),
    val size: DshTerminalSize = DshTerminalSize.DEFAULT,
    val term: String = DEFAULT_TERM
) {
    init {
        require(command.isNotEmpty()) { "dsh: terminal command must not be empty" }
        require(command.first().isNotBlank()) { "dsh: terminal executable must not be blank" }
        require(command.none { it.contains('\u0000') }) {
            "dsh: terminal argv must not contain NUL"
        }
        require(workingDirectory.isNotBlank()) { "dsh: terminal working directory must not be blank" }
        require(term.isNotBlank() && term.length <= 64) { "dsh: terminal TERM must be a short name" }
    }

    companion object {
        const val DEFAULT_TERM = "xterm-256color"
    }
}

/** Which slave-side descriptor produced one output chunk. */
enum class DshTerminalStream { STDOUT, STDERR, COMBINED }

/** Typed terminal failures. Each maps to a concrete condition, not to a device category. */
enum class DshTerminalFailureCode {
    NATIVE_LIBRARY_MISSING,
    OPEN_FAILED,
    NOT_ATTACHED,
    ALREADY_CLOSED,
    WRITE_FAILED,
    READ_FAILED,
    RESIZE_UNSUPPORTED,
    EXIT_UNOBSERVABLE
}

/** Why a terminal session is closing. */
enum class DshTerminalCloseReason {
    CLIENT_CLOSED,
    SERVICE_STOPPED,
    PROCESS_EXITED,
    FAILED
}

/** Exit fact of the terminal child. [signal] is set when the child died from a signal. */
data class DshTerminalExit(val code: Int, val signal: Int? = null)

/**
 * Result of the bounded stop of a terminal child.
 *
 * [settled] means the child was actually reaped (or proven gone). [escalated] means SIGKILL was
 * needed. A timeout leaves both false and the session must not be reported as closed.
 */
data class DshTerminalCloseOutcome(
    val settled: Boolean,
    val escalated: Boolean,
    val detail: String
)

/** Events a terminal session owner publishes. Output bytes are opaque terminal bytes. */
sealed interface DshTerminalEvent {
    class Output(val stream: DshTerminalStream, val bytes: ByteArray) : DshTerminalEvent {
        val size: Int get() = bytes.size
        override fun toString(): String = "Output(stream=$stream, bytes=${bytes.size})"
    }

    /** The kernel (or the fallback) accepted a new size; [requested] is what the caller asked. */
    data class Resized(val size: DshTerminalSize) : DshTerminalEvent

    /** The child exited; the session stays open for final output until it is closed. */
    data class Exited(val exit: DshTerminalExit) : DshTerminalEvent

    data class Closed(
        val reason: DshTerminalCloseReason,
        val outcome: DshTerminalCloseOutcome
    ) : DshTerminalEvent

    data class Failed(
        val code: DshTerminalFailureCode,
        val detail: String
    ) : DshTerminalEvent
}
