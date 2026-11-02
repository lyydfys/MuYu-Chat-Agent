package com.muyuchat.core.dsh.runtime.terminal

import com.muyuchat.core.dsh.contract.runtime.DshTerminalCloseOutcome
import com.muyuchat.core.dsh.contract.runtime.DshTerminalExit
import com.muyuchat.core.dsh.contract.runtime.DshTerminalOpenRequest
import com.muyuchat.core.dsh.contract.runtime.DshTerminalSize
import com.muyuchat.core.dsh.contract.runtime.DshTerminalTransport
import com.muyuchat.core.dsh.contract.runtime.DshTerminalUnavailableReason
import com.muyuchat.core.dsh.runtime.process.DshProcessTree
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit non-PTY fallback: one merged pipe around a child process.
 *
 * It is offered only when the caller allows it and the real PTY is unavailable, and reports
 * [DshTerminalTransport.PIPE_FALLBACK] to every consumer. It deliberately does NOT emulate:
 * `isatty` is false, `resize` returns false (no SIGWINCH exists), there is no controlling tty and
 * no job control. Interactive full-screen programs that require a terminal will misbehave here —
 * that is the honest consequence of the missing native primitive, not a hidden success.
 *
 * Backpressure: output chunks go through a bounded queue. Once [queueChunks] chunks are waiting,
 * the pump thread blocks in `put` instead of growing the app heap; a fast child is throttled by the
 * consumer, which is the only correct behavior for a terminal stream.
 */
internal class DshPipeTerminalPort private constructor(
    private val process: Process,
    val commandLine: List<String>,
    private val queueChunks: Int
) : DshTerminalPort {

    override val transport: DshTerminalTransport = DshTerminalTransport.PIPE_FALLBACK

    /** A pipe is never a tty; never claim otherwise. */
    override val isAtty: Boolean = false

    private val queue = LinkedBlockingQueue<ByteArray>(queueChunks)
    private val eofMarker = ByteArray(0)
    private val released = AtomicBoolean(false)

    @Volatile
    private var endOfStream = false

    @Volatile
    private var pumpFailure: String? = null

    private val pump: Thread = Thread({ pumpLoop() }, "dsh-terminal-pipe-pump").apply {
        isDaemon = true
        start()
    }

    override fun write(bytes: ByteArray): Int {
        if (released.get()) return -1
        if (bytes.isEmpty()) return 0
        return try {
            process.outputStream.write(bytes)
            process.outputStream.flush()
            bytes.size
        } catch (t: Throwable) {
            -1 /* peer closed: the session records PeerClosed, never a fake write */
        }
    }

    override fun read(buffer: ByteArray, timeoutMillis: Long): Int {
        require(buffer.isNotEmpty()) { "dsh: pipe read buffer must not be empty" }
        if (released.get()) return 0
        if (endOfStream) return 0
        val chunk = try {
            queue.poll(timeoutMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            return -1
        } ?: return -1 /* bounded wait expired (or the pump died: the caller sees timeout, never data) */
        if (chunk === eofMarker) {
            endOfStream = true
            return 0
        }
        val copied = minOf(chunk.size, buffer.size)
        System.arraycopy(chunk, 0, buffer, 0, copied)
        if (copied < chunk.size) {
            // Hand the remainder back to the head of the queue (the consumer supplied a smaller
            // buffer than the chunk); the slot we removed is free again.
            queue.offer(chunk.copyOfRange(copied, chunk.size))
        }
        return copied
    }

    /** Pipes have no kernel geometry; a fabricated size would be a lie. */
    override fun resize(size: DshTerminalSize): Boolean = false

    override fun size(): DshTerminalSize? = null

    override fun pollExit(timeoutMillis: Long): DshTerminalExit? {
        val finished = try {
            process.waitFor(timeoutMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        if (!finished) return null
        val code = runCatching { process.exitValue() }.getOrNull() ?: return null
        // Java does not report the terminating signal: a 128+N code stays an exit code and the
        // signal field stays null instead of being guessed.
        return DshTerminalExit(code = code, signal = null)
    }

    override fun close(graceMillis: Long, killMillis: Long): DshTerminalCloseOutcome {
        if (!released.compareAndSet(false, true)) {
            return DshTerminalCloseOutcome(settled = true, escalated = false, detail = "already released")
        }
        val childPid = pidOf(process)
        queue.clear()
        queue.offer(eofMarker)
        var escalated = false
        // The fallback child is still a managed process: stop its descendants with it instead of
        // letting a shell's grandchildren outlive the terminal.
        childPid?.let { pid -> runCatching { DshProcessTree.signalTree(pid, DshProcessTree.SIGTERM) } }
        runCatching { process.destroy() }
        var settled = runCatching { process.waitFor(graceMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        if (!settled) {
            escalated = true
            childPid?.let { pid -> runCatching { DshProcessTree.signalTree(pid, DshProcessTree.SIGKILL) } }
            runCatching { process.destroyForcibly() }
            settled = runCatching { process.waitFor(killMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS) }
                .getOrDefault(false)
        }
        runCatching { process.inputStream.close() }
        runCatching { process.outputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { pump.join(500) }
        val alive = runCatching { process.isAlive }.getOrDefault(true)
        return DshTerminalCloseOutcome(
            settled = settled && !alive,
            escalated = escalated,
            detail = when {
                !settled || alive -> "pipe child not reaped within the stop budget"
                escalated -> "SIGKILL after grace (pipe fallback)"
                else -> "destroy after grace (pipe fallback)"
            }
        )
    }

    override fun abandon() {
        if (!released.compareAndSet(false, true)) return
        queue.clear()
        queue.offer(eofMarker)
        runCatching { process.inputStream.close() }
        runCatching { process.outputStream.close() }
        runCatching { process.errorStream.close() }
        pidOf(process)?.let { pid -> runCatching { DshProcessTree.signalTree(pid, DshProcessTree.SIGKILL) } }
        runCatching { process.destroyForcibly() }
    }

    /** Android's ProcessImpl hides the OS pid in a private field; null degrades honestly. */
    private fun pidOf(process: Process): Int? = try {
        val field = process.javaClass.getDeclaredField("pid")
        field.isAccessible = true
        field.getInt(process)
    } catch (t: Throwable) {
        null
    }

    override fun close() {
        close(DshTerminalSession.DEFAULT_GRACE_MILLIS, DshTerminalSession.DEFAULT_KILL_MILLIS)
    }

    private fun pumpLoop() {
        try {
            val buffer = ByteArray(8 * 1024)
            val input = process.inputStream
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                queue.put(buffer.copyOf(read))
            }
        } catch (t: Throwable) {
            pumpFailure = t.javaClass.simpleName
        } finally {
            runCatching { queue.offer(eofMarker, 200, TimeUnit.MILLISECONDS) }
        }
    }

    companion object {
        /** 32 chunks of up to 8 KiB: the bounded buffer that provides terminal backpressure. */
        const val DEFAULT_QUEUE_CHUNKS = 32
        private const val MIN_QUEUE_CHUNKS = 2

        fun open(
            request: DshTerminalOpenRequest,
            queueChunks: Int = DEFAULT_QUEUE_CHUNKS
        ): DshTerminalOpenResult {
            val builder = try {
                ProcessBuilder(request.command)
            } catch (t: Throwable) {
                return DshTerminalOpenResult.Failed(
                    DshTerminalUnavailableReason.OPEN_FAILED,
                    "invalid pipe command: ${t.javaClass.simpleName}"
                )
            }
            builder.directory(File(request.workingDirectory))
            builder.redirectErrorStream(true)
            val environment = try {
                builder.environment()
            } catch (t: Throwable) {
                return DshTerminalOpenResult.Failed(
                    DshTerminalUnavailableReason.OPEN_FAILED,
                    "pipe environment unavailable: ${t.javaClass.simpleName}"
                )
            }
            environment.putAll(request.environment)
            environment["TERM"] = request.term
            val process = try {
                builder.start()
            } catch (t: Throwable) {
                return DshTerminalOpenResult.Failed(
                    DshTerminalUnavailableReason.OPEN_FAILED,
                    "pipe child could not be started: ${t.javaClass.simpleName}" +
                        (t.message?.take(120)?.let { ": $it" } ?: "")
                )
            }
            return DshTerminalOpenResult.Opened(
                DshPipeTerminalPort(process, request.command, queueChunks.coerceAtLeast(MIN_QUEUE_CHUNKS))
            )
        }
    }
}
