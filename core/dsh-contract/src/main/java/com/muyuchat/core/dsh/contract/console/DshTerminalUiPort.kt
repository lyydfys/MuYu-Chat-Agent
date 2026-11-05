package com.muyuchat.core.dsh.contract.console

/**
 * Terminal seam of the native terminal page.
 *
 * The page (feature module) owns no process: it declares a tab, asks the owner to open the PTY, then
 * polls [read] for output and forwards user input through [write]/[control]. Everything crossing the
 * seam is a small, self-contained shape so the feature module never links the runtime implementation.
 *
 * Sessions are addressed by the opaque id the owner returned; the page never invents one, and every
 * failure is a returned value rather than an exception, so a tab can always show the reason.
 */
interface DshTerminalUiPort {

    /** Sessions this owner already has (for restoring the strip after a configuration change). */
    fun list(): List<DshTerminalSessionInfo>

    /**
     * Opens a shell session.
     *
     * [columns]/[rows] describe the initial PTY window; the owner clamps them and says what it
     * actually allocated. [requireRealPty] asks for a real pty rather than a pipe fallback.
     */
    fun open(
        name: String?,
        columns: Int,
        rows: Int,
        requireRealPty: Boolean = true
    ): DshTerminalOpenResult

    /**
     * Reads output starting at [offset].
     *
     * A read with no new bytes returns [DshTerminalRead.Idle] — the page polls, so "nothing yet" is
     * normal and must not look like an error.
     */
    fun read(sessionId: String, offset: Long, maxBytes: Int = DEFAULT_READ_BYTES): DshTerminalRead

    /** Sends typed text. [submit] appends the newline a shell expects for a command. */
    fun write(sessionId: String, text: String, submit: Boolean = false): DshTerminalWrite

    /** Sends a key that has no text form. */
    fun control(sessionId: String, key: DshTerminalControlKey): DshTerminalWrite

    fun resize(sessionId: String, columns: Int, rows: Int): DshTerminalWrite

    fun close(sessionId: String): Boolean

    companion object {
        /** A phone-sized PTY writes a lot at startup; one read should not truncate a prompt. */
        const val DEFAULT_READ_BYTES: Int = 16 * 1024
    }
}

/** One session the owner already knows about. */
data class DshTerminalSessionInfo(
    val sessionId: String,
    val name: String? = null,
    val columns: Int = 80,
    val rows: Int = 24,
    val running: Boolean = true
)

/** Outcome of opening a session: the owner's session id, or why there is none. */
sealed interface DshTerminalOpenResult {
    data class Opened(
        val sessionId: String,
        val columns: Int,
        val rows: Int,
        /** False when the owner had to fall back from a real pty to a pipe. */
        val realPty: Boolean = true
    ) : DshTerminalOpenResult

    /** The owner cannot start a terminal here; [detail] is user-facing and already sanitized. */
    data class Refused(val detail: String) : DshTerminalOpenResult
}

/** Result of one poll. */
sealed interface DshTerminalRead {
    /**
     * New output as already-decoded text, plus the offset to pass to the next read.
     *
     * The owner performs the UTF-8 decoding (it owns the byte buffer), so a page never has to guess
     * where a code point was split between two reads.
     */
    data class Data(val text: String, val nextOffset: Long) : DshTerminalRead

    /** Nothing new since [nextOffset]; the session is still alive. */
    data class Idle(val nextOffset: Long) : DshTerminalRead

    /** The read failed or the session ended; [detail] is user-facing. */
    data class Failed(val detail: String) : DshTerminalRead
}

/** Result of any mutation (write/control/resize). */
data class DshTerminalWrite(
    val ok: Boolean,
    val detail: String? = null
)

/** Keys that cannot be expressed as text. */
enum class DshTerminalControlKey { INTERRUPT, EOF, SUSPEND, QUIT, KILL }
