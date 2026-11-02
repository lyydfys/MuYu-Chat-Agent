package com.muyuchat.core.dsh.runtime.process

import com.muyuchat.core.dsh.contract.runtime.DshTerminalCloseOutcome
import com.muyuchat.core.dsh.contract.runtime.DshTerminalExit
import com.muyuchat.core.dsh.contract.runtime.DshTerminalSize
import com.muyuchat.core.dsh.contract.runtime.DshTerminalTransport

/** Lifecycle of one real terminal child. */
enum class DshTerminalStatus { RUNNING, EXITED, FAILED, CLOSED }

/** Why a terminal operation was refused before touching the child. */
enum class DshTerminalRefusal {
    UNSUPPORTED_TYPE,
    INVALID_WORKING_DIRECTORY,
    INVALID_NAME,
    LIMIT_REACHED,
    SHELL_UNAVAILABLE,
    PTY_UNAVAILABLE,
    OPEN_FAILED,
    HOST_CLOSED,
    /** An exclusive maintenance write owns the workspace; the session was not opened. */
    MAINTENANCE_ACTIVE,
    UNKNOWN_SESSION,
    SESSION_EXITED,
    SESSION_FAILED
}

/** Typed answer of [DshTerminalHost.open]. */
sealed interface DshTerminalOpenOutcome {
    data class Opened(
        val snapshot: DshTerminalSnapshot,
        /** Bounded startup output captured before the prompt wait expired; may be empty. */
        val motd: String
    ) : DshTerminalOpenOutcome

    data class Refused(val reason: DshTerminalRefusal, val detail: String) : DshTerminalOpenOutcome
}

/** Immutable view of one session. Safe to publish; never carries a token or a secret. */
data class DshTerminalSnapshot(
    val sessionId: String,
    val ownerKey: String,
    val name: String?,
    val type: String,
    val transport: DshTerminalTransport,
    val realPty: Boolean,
    val pid: Int?,
    val status: DshTerminalStatus,
    val exit: DshTerminalExit?,
    val size: DshTerminalSize?,
    val createdAtMillis: Long,
    val firstRetainedOffset: Long,
    val nextOffset: Long
)

/** Cursor-based output page. [truncated] means the cursor preceded the retained window. */
data class DshTerminalRead(
    val sessionId: String,
    val text: String,
    val bytes: Int,
    val nextOffset: Long,
    val truncated: Boolean,
    val status: DshTerminalStatus,
    val exit: DshTerminalExit?
)

sealed interface DshTerminalReadOutcome {
    data class Ok(val page: DshTerminalRead) : DshTerminalReadOutcome
    data class Refused(val reason: DshTerminalRefusal, val detail: String) : DshTerminalReadOutcome
}

sealed interface DshTerminalWriteOutcome {
    data class Written(val bytes: Int) : DshTerminalWriteOutcome
    data object PeerClosed : DshTerminalWriteOutcome
    data class Refused(val reason: DshTerminalRefusal, val detail: String) : DshTerminalWriteOutcome
}

sealed interface DshTerminalResizeOutcome {
    data class Applied(val size: DshTerminalSize, val transport: DshTerminalTransport) : DshTerminalResizeOutcome
    data class Coalesced(val size: DshTerminalSize, val transport: DshTerminalTransport) : DshTerminalResizeOutcome
    data class Unsupported(val size: DshTerminalSize, val transport: DshTerminalTransport, val detail: String) : DshTerminalResizeOutcome
    data class Refused(val reason: DshTerminalRefusal, val detail: String) : DshTerminalResizeOutcome
}

/** Control bytes a terminal client may send. Delivered through the tty line discipline. */
enum class DshTerminalControl(val byte: Byte) {
    /** ^C: the kernel's ISIG line discipline delivers SIGINT to the foreground group. */
    CTRL_C(0x03),
    /** ^D: EOF only at the start of a line, exactly like a physical terminal. */
    CTRL_D(0x04),
    /** ^Z: SIGTSTP for job control. */
    CTRL_Z(0x1A),
    /** ^L: redraw request for the foreground program. */
    CTRL_L(0x0C)
}

/** One settled session of [DshTerminalHost.closeAll]. */
data class DshTerminalCloseReport(
    val sessionId: String,
    val status: DshTerminalStatus,
    val outcome: DshTerminalCloseOutcome
)

/** Aggregate stop fact. [settled] is true only when every session was actually reaped. */
data class DshTerminalHostShutdown(
    val settled: Boolean,
    val sessions: List<DshTerminalCloseReport>
) {
    val survivors: List<String> get() = sessions.filterNot { it.outcome.settled }.map { it.sessionId }
}
