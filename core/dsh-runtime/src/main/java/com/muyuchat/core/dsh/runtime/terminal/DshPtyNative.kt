package com.muyuchat.core.dsh.runtime.terminal

import com.muyuchat.core.dsh.contract.runtime.DshTerminalCloseOutcome
import com.muyuchat.core.dsh.contract.runtime.DshTerminalExit
import com.muyuchat.core.dsh.contract.runtime.DshTerminalSize
import com.muyuchat.core.dsh.contract.runtime.DshTerminalTransport
import com.muyuchat.core.dsh.contract.runtime.DshTerminalUnavailableReason

/**
 * JNI surface of `libdshpty.so` (see `src/main/cpp/dsh_pty.c`).
 *
 * The library is loaded lazily and a failure is a typed [DshPtyAvailability.Unavailable]; nothing
 * here throws at class-initialisation time, so a build without the native artifact (or a JVM unit
 * test) degrades honestly instead of crashing the runtime.
 */
internal object DshPtyNative {
    private const val LIBRARY = "dshpty"

    @Volatile
    private var loadState: DshPtyAvailability? = null

    fun availability(): DshPtyAvailability {
        loadState?.let { return it }
        synchronized(this) {
            loadState?.let { return it }
            val resolved = try {
                System.loadLibrary(LIBRARY)
                DshPtyAvailability.Loaded(LIBRARY, null)
            } catch (t: Throwable) {
                DshPtyAvailability.Unavailable(
                    DshTerminalUnavailableReason.NATIVE_LOAD_FAILED,
                    "lib$LIBRARY.so could not be loaded: " + describe(t)
                )
            }
            loadState = resolved
            return resolved
        }
    }

    /**
     * Opens a real PTY pair and starts [argv]. Throws only when the native call itself reports an
     * error; the caller converts it to a typed open failure.
     */
    external fun nativeOpen(
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        columns: Int,
        rows: Int
    ): LongArray?

    external fun nativeSlaveName(fd: Long): String?

    external fun nativeRead(fd: Long, buffer: ByteArray, timeoutMillis: Int): Int

    external fun nativeWrite(fd: Long, buffer: ByteArray, offset: Int, length: Int): Int

    external fun nativeResize(fd: Long, columns: Int, rows: Int): Boolean

    external fun nativeSize(fd: Long): IntArray?

    external fun nativeIsTty(fd: Long): Boolean

    external fun nativePollExit(pid: Int, timeoutMillis: Int): IntArray?

    external fun nativeClose(fd: Long, pid: Int, graceMillis: Int, killMillis: Int): IntArray?

    private fun describe(t: Throwable): String {
        val message = t.message?.take(160)
        return if (message.isNullOrBlank()) t.javaClass.simpleName else "${t.javaClass.simpleName}: $message"
    }
}

/** Production [DshPtyBridge] over the JNI surface. */
internal class DshNativePtyBridge : DshPtyBridge {
    override val nativeLibrary: String = "libdshpty.so"

    override fun availability(): DshPtyAvailability = DshPtyNative.availability()

    override fun open(
        command: List<String>,
        environment: Map<String, String>,
        workingDirectory: String,
        size: DshTerminalSize
    ): DshTerminalOpenResult {
        when (val state = availability()) {
            is DshPtyAvailability.Unavailable -> return DshTerminalOpenResult.Failed(state.reason, state.detail)
            is DshPtyAvailability.Loaded -> Unit
        }
        val argv = command.toTypedArray()
        val envp = environment.entries
            .filter { (key, value) -> key.isNotBlank() && !key.contains('=') && !value.contains('\u0000') }
            .map { (key, value) -> "$key=$value" }
            .toTypedArray()
        return try {
            val opened = DshPtyNative.nativeOpen(argv, envp, workingDirectory, size.columns, size.rows)
                ?: return DshTerminalOpenResult.Failed(
                    DshTerminalUnavailableReason.OPEN_FAILED,
                    "native pty open returned no descriptor"
                )
            if (opened.size != 2 || opened[0] < 0 || opened[1] <= 0) {
                return DshTerminalOpenResult.Failed(
                    DshTerminalUnavailableReason.OPEN_FAILED,
                    "native pty open returned an invalid pair"
                )
            }
            val fd = opened[0]
            val pid = opened[1].toInt()
            val slave = runCatching { DshPtyNative.nativeSlaveName(fd) }.getOrNull()
            DshTerminalOpenResult.Opened(
                DshNativePtyPort(
                    fd = fd,
                    pid = pid,
                    isAtty = runCatching { DshPtyNative.nativeIsTty(fd) }.getOrDefault(false),
                    initialSize = size,
                    slaveName = slave
                )
            )
        } catch (t: Throwable) {
            DshTerminalOpenResult.Failed(DshTerminalUnavailableReason.OPEN_FAILED, describe(t))
        }
    }

    private fun describe(t: Throwable): String {
        val message = t.message?.take(200)
        return if (message.isNullOrBlank()) t.javaClass.simpleName else "${t.javaClass.simpleName}: $message"
    }
}

/**
 * Real PTY port. Every call maps a native failure to the typed contract: EOF is 0, a bounded wait
 * is -1, and an actual I/O error is thrown so [DshTerminalSession] can record READ_FAILED /
 * WRITE_FAILED instead of looping forever.
 */
internal class DshNativePtyPort(
    private val fd: Long,
    private val pid: Int,
    override val isAtty: Boolean,
    initialSize: DshTerminalSize,
    private val slaveName: String? = null
) : DshTerminalPort {

    override val transport: DshTerminalTransport = DshTerminalTransport.REAL_PTY

    @Volatile
    private var geometry: DshTerminalSize = initialSize

    @Volatile
    private var released = false

    /** Slave device named by the kernel for this master; diagnostic proof they are a pair. */
    val ptySlaveName: String? get() = slaveName

    val processId: Int get() = pid

    override fun write(bytes: ByteArray): Int {
        check(!released) { "dsh: pty port is released" }
        return DshPtyNative.nativeWrite(fd, bytes, 0, bytes.size)
    }

    override fun read(buffer: ByteArray, timeoutMillis: Long): Int {
        check(!released) { "dsh: pty port is released" }
        val bounded = timeoutMillis.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        return DshPtyNative.nativeRead(fd, buffer, bounded)
    }

    override fun resize(size: DshTerminalSize): Boolean {
        if (released) return false
        val applied = runCatching { DshPtyNative.nativeResize(fd, size.columns, size.rows) }.getOrDefault(false)
        if (applied) geometry = size
        return applied
    }

    override fun size(): DshTerminalSize? {
        if (released) return null
        val raw = runCatching { DshPtyNative.nativeSize(fd) }.getOrNull() ?: return geometry
        if (raw.size != 2) return geometry
        return DshTerminalSize.sanitized(raw[0], raw[1]) ?: geometry
    }

    override fun pollExit(timeoutMillis: Long): DshTerminalExit? {
        if (released) return null
        val raw = runCatching {
            DshPtyNative.nativePollExit(pid, timeoutMillis.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
        }.getOrNull() ?: return null
        if (raw.size != 2) return null
        return when (raw[0]) {
            0 -> DshTerminalExit(code = raw[1], signal = null)
            1 -> DshTerminalExit(code = 128 + raw[1], signal = raw[1])
            else -> null
        }
    }

    override fun close(graceMillis: Long, killMillis: Long): DshTerminalCloseOutcome {
        if (released) {
            return DshTerminalCloseOutcome(settled = true, escalated = false, detail = "already released")
        }
        val raw = try {
            DshPtyNative.nativeClose(
                fd,
                pid,
                graceMillis.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                killMillis.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            )
        } catch (t: Throwable) {
            return DshTerminalCloseOutcome(
                settled = false,
                escalated = false,
                detail = "pty close failed: ${t.javaClass.simpleName}"
            )
        } finally {
            released = true
        }
        if (raw == null || raw.size != 2) {
            return DshTerminalCloseOutcome(settled = false, escalated = false, detail = "pty close returned no status")
        }
        val settled = raw[0] == 1
        val escalated = raw[1] == 1
        return DshTerminalCloseOutcome(
            settled = settled,
            escalated = escalated,
            detail = when {
                settled && escalated -> "SIGKILL after grace; terminal session empty"
                settled -> "SIGTERM; terminal session empty"
                else -> "terminal session still has survivors within the kill budget"
            }
        )
    }

    override fun abandon() {
        if (released) return
        released = true
        runCatching { DshPtyNative.nativeClose(fd, 0, 0, 0) }
    }

    override fun close() {
        close(DshTerminalSession.DEFAULT_GRACE_MILLIS, DshTerminalSession.DEFAULT_KILL_MILLIS)
    }
}

