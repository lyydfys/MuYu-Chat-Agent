package com.muyuchat.core.dsh.runtime.terminal

import com.muyuchat.core.dsh.contract.runtime.DshTerminalCloseOutcome
import com.muyuchat.core.dsh.contract.runtime.DshTerminalExit
import com.muyuchat.core.dsh.contract.runtime.DshTerminalSize
import com.muyuchat.core.dsh.contract.runtime.DshTerminalTransport
import com.muyuchat.core.dsh.contract.runtime.DshTerminalUnavailableReason
import java.io.Closeable

/**
 * Outcome of opening one terminal child.
 *
 * A failed open is typed, never a silently substituted half-working terminal: the caller decides
 * whether an explicit PIPE fallback is acceptable (see [DshTerminalFactory]).
 */
sealed interface DshTerminalOpenResult {
    data class Opened(val port: DshTerminalPort) : DshTerminalOpenResult

    data class Failed(
        val reason: DshTerminalUnavailableReason,
        val detail: String
    ) : DshTerminalOpenResult
}

/**
 * Blocking terminal I/O seam shared by the native PTY and the explicit pipe fallback.
 *
 * Implementations are owned by one [DshTerminalSession]; they are not thread safe. The session
 * serialises calls, so the service can pump output and accept writes from different coroutines
 * without racing the descriptor.
 */
interface DshTerminalPort : Closeable {
    val transport: DshTerminalTransport

    /** True only when the master descriptor really is a tty (false for the pipe fallback). */
    val isAtty: Boolean

    /** Bytes accepted by the terminal, or -1 when the peer is already gone. */
    fun write(bytes: ByteArray): Int

    /** >0 = bytes read, 0 = EOF, -1 = the bounded wait expired. */
    fun read(buffer: ByteArray, timeoutMillis: Long): Int

    /** Applies a new geometry. False means this transport cannot resize (honest PIPE fallback). */
    fun resize(size: DshTerminalSize): Boolean

    /** Current kernel geometry, or null when the transport has none. */
    fun size(): DshTerminalSize?

    /** Non-blocking observation: the exit fact, or null while the child is still running. */
    fun pollExit(timeoutMillis: Long): DshTerminalExit?

    /** Idempotent bounded stop: SIGTERM, then SIGKILL after [graceMillis]. */
    fun close(graceMillis: Long, killMillis: Long): DshTerminalCloseOutcome

    /**
     * Releases descriptors without claiming the child was reaped. Used when a stop already failed
     * and the caller must not report a settled session.
     */
    fun abandon()
}

/** Real kernel PTY capability check; implemented by the JNI bridge. */
interface DshPtyBridge {
    val nativeLibrary: String

    /** Loads/verifies the native library. Never throws: a failure is a typed reason. */
    fun availability(): DshPtyAvailability

    fun open(
        command: List<String>,
        environment: Map<String, String>,
        workingDirectory: String,
        size: DshTerminalSize
    ): DshTerminalOpenResult
}

/** Typed native-library state. */
sealed interface DshPtyAvailability {
    data class Loaded(val nativeLibrary: String, val slaveName: String?) : DshPtyAvailability

    data class Unavailable(
        val reason: DshTerminalUnavailableReason,
        val detail: String
    ) : DshPtyAvailability
}
