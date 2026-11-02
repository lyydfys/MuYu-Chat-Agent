package com.muyuchat.core.dsh.runtime.terminal

import com.muyuchat.core.dsh.contract.runtime.DshTerminalCloseOutcome
import com.muyuchat.core.dsh.contract.runtime.DshTerminalCloseReason
import com.muyuchat.core.dsh.contract.runtime.DshTerminalEvent
import com.muyuchat.core.dsh.contract.runtime.DshTerminalExit
import com.muyuchat.core.dsh.contract.runtime.DshTerminalFailureCode
import com.muyuchat.core.dsh.contract.runtime.DshTerminalSize
import com.muyuchat.core.dsh.contract.runtime.DshTerminalStream
import com.muyuchat.core.dsh.contract.runtime.DshTerminalTransport

/** Result of one write attempt: no exception-based control flow for the caller. */
sealed interface DshTerminalWriteResult {
    data class Written(val bytes: Int) : DshTerminalWriteResult

    /** The child/terminal is gone; the session keeps that fact instead of retrying silently. */
    data object PeerClosed : DshTerminalWriteResult

    data class Rejected(
        val code: DshTerminalFailureCode,
        val detail: String
    ) : DshTerminalWriteResult
}

/** Result of a resize request, including the coalescing branch. */
sealed interface DshTerminalResizeResult {
    data class Applied(val size: DshTerminalSize) : DshTerminalResizeResult

    /** Identical to the applied geometry; no ioctl/SIGWINCH is wasted on it. */
    data class Ignored(val size: DshTerminalSize) : DshTerminalResizeResult

    /** Inside the coalescing window: remembered and applied by [DshTerminalSession.flushResize]. */
    data class Coalesced(val size: DshTerminalSize) : DshTerminalResizeResult

    data class Unsupported(val size: DshTerminalSize, val detail: String) : DshTerminalResizeResult

    data class Rejected(
        val code: DshTerminalFailureCode,
        val detail: String
    ) : DshTerminalResizeResult
}

/**
 * Owner of one terminal session: phase transitions, resize coalescing, write/read routing, exit
 * observation and the idempotent bounded stop.
 *
 * The class is deliberately Android-free and talks only to [DshTerminalPort], so every branch
 * (resize before attach, coalescing, write after close, EOF with an unobservable exit, stop
 * escalation) is exercised on the JVM. The native/pipe ports own the OS handles; this class owns
 * the semantics.
 *
 * Lifecycle: created by [DshTerminalFactory] -> used by the service/UI -> closed once. After
 * [close] every operation returns a typed rejection and repeated close returns the first outcome.
 */
class DshTerminalSession(
    private val port: DshTerminalPort,
    initialSize: DshTerminalSize = DshTerminalSize.DEFAULT,
    private val clock: () -> Long = System::currentTimeMillis,
    private val resizeWindowMillis: Long = DEFAULT_RESIZE_WINDOW_MILLIS
) {

    enum class Phase { OPEN, EXITED, CLOSED, FAILED }

    private val monitor = Any()

    @Volatile
    var phase: Phase = Phase.OPEN
        private set

    @Volatile
    var exit: DshTerminalExit? = null
        private set

    @Volatile
    var lastFailure: DshTerminalFailureCode? = null
        private set

    private var attached = false
    private var appliedSize: DshTerminalSize? = port.size() ?: initialSize
    private var requestedSize: DshTerminalSize = appliedSize ?: initialSize
    private var pendingResize: DshTerminalSize? = null
    private var lastAppliedAtMillis = 0L
    private var closeOutcome: DshTerminalCloseOutcome? = null
    private var endOfStream = false

    val transport: DshTerminalTransport get() = port.transport

    /** True when this session is backed by a real kernel tty. */
    val isRealPty: Boolean get() = port.transport == DshTerminalTransport.REAL_PTY && port.isAtty

    /**
     * OS pid of the terminal child when the transport can prove it (the native PTY fork), or null.
     *
     * A pipe fallback has a Java [Process] but deliberately does not report a pid: this runtime
     * never derives process identity from a reusable pid, and callers use null as "not reported".
     */
    val processId: Int?
        get() = (port as? DshNativePtyPort)?.processId

    /** Current geometry known to this session (kernel value when the transport has one). */
    fun size(): DshTerminalSize? = synchronized(monitor) { port.size() ?: appliedSize }

    /**
     * First subscriber. Applies the pending geometry (a resize that arrived before attach, or the
     * size the caller attached with) exactly once; repeated calls are a no-op.
     */
    fun attach(size: DshTerminalSize? = null): List<DshTerminalEvent> {
        synchronized(monitor) {
            if (phase != Phase.OPEN) return emptyList()
            if (size != null) requestedSize = size
            if (attached) return emptyList()
            attached = true
            val target = pendingResize ?: if (size != null && size != appliedSize) size else null
            pendingResize = null
            if (target == null) return emptyList()
            return if (applyResize(target)) {
                listOf(DshTerminalEvent.Resized(target))
            } else {
                emptyList()
            }
        }
    }

    /**
     * Requests a geometry change. Rapid requests inside [resizeWindowMillis] are coalesced: only
     * the latest size survives, which prevents a SIGWINCH storm while the user drags the window.
     */
    fun resize(size: DshTerminalSize): DshTerminalResizeResult = synchronized(monitor) {
        if (phase == Phase.CLOSED || phase == Phase.FAILED) {
            return DshTerminalResizeResult.Rejected(
                DshTerminalFailureCode.ALREADY_CLOSED,
                "terminal session is ${phase.name.lowercase()}"
            )
        }
        if (size == appliedSize) return DshTerminalResizeResult.Ignored(size)
        requestedSize = size
        if (!attached) {
            pendingResize = size
            return DshTerminalResizeResult.Coalesced(size)
        }
        val now = clock()
        if (lastAppliedAtMillis != 0L && now - lastAppliedAtMillis < resizeWindowMillis) {
            pendingResize = size
            return DshTerminalResizeResult.Coalesced(size)
        }
        if (!port.resize(size)) {
            return DshTerminalResizeResult.Unsupported(
                size,
                "the ${transport.name.lowercase()} transport cannot apply a window size"
            )
        }
        lastAppliedAtMillis = now
        appliedSize = size
        pendingResize = null
        DshTerminalResizeResult.Applied(size)
    }

    /** Applies the newest coalesced request, if any. Returns the event the caller should publish. */
    fun flushResize(): DshTerminalEvent.Resized? = synchronized(monitor) {
        if (phase != Phase.OPEN || !attached) return null
        val target = pendingResize ?: return null
        pendingResize = null
        if (target == appliedSize) return null
        if (!port.resize(target)) return null
        lastAppliedSize(target)
        DshTerminalEvent.Resized(target)
    }

    /** Writes terminal input. Rejected after close/exit, and when the peer is gone. */
    fun write(bytes: ByteArray): DshTerminalWriteResult = synchronized(monitor) {
        if (phase == Phase.CLOSED || phase == Phase.FAILED) {
            return DshTerminalWriteResult.Rejected(
                DshTerminalFailureCode.ALREADY_CLOSED,
                "terminal session is ${phase.name.lowercase()}"
            )
        }
        if (bytes.isEmpty()) return DshTerminalWriteResult.Written(0)
        return try {
            val written = port.write(bytes)
            if (written < 0) DshTerminalWriteResult.PeerClosed else DshTerminalWriteResult.Written(written)
        } catch (t: Throwable) {
            lastFailure = DshTerminalFailureCode.WRITE_FAILED
            DshTerminalWriteResult.Rejected(DshTerminalFailureCode.WRITE_FAILED, describe(t))
        }
    }

    /**
     * Reads one chunk of terminal output.
     *
     * - bytes -> `[Output]`;
     * - EOF -> `[Exited]` when the exit fact is already observable, otherwise an empty list with
     *   [endOfStream] set (the caller keeps polling [pollExit]);
     * - timeout -> empty list;
     * - I/O failure -> `[Failed]` and the session becomes FAILED.
     */
    fun pump(buffer: ByteArray, timeoutMillis: Long): List<DshTerminalEvent> = synchronized(monitor) {
        if (phase == Phase.CLOSED || phase == Phase.FAILED) {
            return listOf(
                DshTerminalEvent.Failed(
                    DshTerminalFailureCode.ALREADY_CLOSED,
                    "terminal session is ${phase.name.lowercase()}"
                )
            )
        }
        require(buffer.isNotEmpty()) { "dsh: terminal read buffer must not be empty" }
        val read = try {
            port.read(buffer, timeoutMillis)
        } catch (t: Throwable) {
            phase = Phase.FAILED
            lastFailure = DshTerminalFailureCode.READ_FAILED
            return listOf(DshTerminalEvent.Failed(DshTerminalFailureCode.READ_FAILED, describe(t)))
        }
        when {
            read > 0 -> {
                val bytes = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
                return listOf(DshTerminalEvent.Output(DshTerminalStream.STDOUT, bytes))
            }
            read == 0 -> {
                endOfStream = true
                val observed = observeExit(0L)
                return if (observed != null) {
                    listOf(DshTerminalEvent.Exited(observed))
                } else {
                    emptyList()
                }
            }
            else -> return emptyList()
        }
    }

    /** True once the master side reported EOF; output is complete but the child may still run. */
    val observedEndOfStream: Boolean get() = endOfStream

    /** Polls the exit fact; the phase becomes EXITED the first time it is observed. */
    fun pollExit(timeoutMillis: Long): DshTerminalExit? = synchronized(monitor) {
        if (phase == Phase.CLOSED || phase == Phase.FAILED) return exit
        observeExit(timeoutMillis)
    }

    /**
     * Bounded, idempotent stop. Repeated calls never re-signal a closed session and return the
     * first outcome, so a service stop and a client close cannot double-count escalation.
     */
    fun close(
        reason: DshTerminalCloseReason = DshTerminalCloseReason.CLIENT_CLOSED,
        graceMillis: Long = DEFAULT_GRACE_MILLIS,
        killMillis: Long = DEFAULT_KILL_MILLIS
    ): DshTerminalEvent = synchronized(monitor) {
        closeOutcome?.let { return DshTerminalEvent.Closed(reason, it) }
        val outcome = if (phase == Phase.CLOSED) {
            DshTerminalCloseOutcome(settled = true, escalated = false, detail = "already closed")
        } else {
            try {
                port.close(graceMillis.coerceAtLeast(0L), killMillis.coerceAtLeast(0L))
            } catch (t: Throwable) {
                DshTerminalCloseOutcome(settled = false, escalated = false, detail = describe(t))
            }
        }
        closeOutcome = outcome
        phase = Phase.CLOSED
        if (!outcome.settled) {
            lastFailure = DshTerminalFailureCode.EXIT_UNOBSERVABLE
        }
        DshTerminalEvent.Closed(reason, outcome)
    }

    /** Releases handles without claiming the child is gone. Idempotent. */
    fun abandon() {
        synchronized(monitor) {
            if (closeOutcome == null) {
                closeOutcome = DshTerminalCloseOutcome(
                    settled = false,
                    escalated = false,
                    detail = "abandoned without a verified stop"
                )
            }
            phase = Phase.CLOSED
            runCatching { port.abandon() }
        }
    }

    private fun observeExit(timeoutMillis: Long): DshTerminalExit? {
        exit?.let {
            if (phase == Phase.OPEN) phase = Phase.EXITED
            return it
        }
        val observed = try {
            port.pollExit(timeoutMillis)
        } catch (t: Throwable) {
            lastFailure = DshTerminalFailureCode.EXIT_UNOBSERVABLE
            null
        } ?: return null
        exit = observed
        phase = Phase.EXITED
        return observed
    }

    private fun applyResize(size: DshTerminalSize): Boolean {
        if (size == appliedSize) return false
        if (!port.resize(size)) return false
        lastAppliedSize(size)
        return true
    }

    private fun lastAppliedSize(size: DshTerminalSize) {
        appliedSize = size
        requestedSize = size
        lastAppliedAtMillis = clock()
    }

    private fun describe(t: Throwable): String {
        val message = t.message?.take(160)
        return if (message.isNullOrBlank()) t.javaClass.simpleName else "${t.javaClass.simpleName}: $message"
    }

    companion object {
        /** Default UI resize coalescing window; one SIGWINCH per 80 ms is enough for drag-resizes. */
        const val DEFAULT_RESIZE_WINDOW_MILLIS = 80L
        const val DEFAULT_GRACE_MILLIS = 1_500L
        const val DEFAULT_KILL_MILLIS = 2_500L
    }
}
