package com.muyuchat.core.dsh.runtime.process

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Result of one bounded, non-interactive command execution. */
data class DshCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean
) {
    val ok: Boolean get() = !timedOut && exitCode == 0
}

/**
 * Narrow process-runner seam so the exec-strategy probe can be exercised on the JVM and on a
 * device with the same logic. Implementations must bound output and wait with a timeout.
 */
fun interface DshProcessRunner {
    fun run(
        argv: List<String>,
        environment: Map<String, String>,
        workingDir: File?,
        timeoutMillis: Long
    ): DshCommandResult
}

/** Production runner: inherits the app environment, then applies the DSH overrides. */
class DefaultDshProcessRunner : DshProcessRunner {
    override fun run(
        argv: List<String>,
        environment: Map<String, String>,
        workingDir: File?,
        timeoutMillis: Long
    ): DshCommandResult {
        var process: Process? = null
        var processId: Int? = null
        return try {
            val builder = ProcessBuilder(argv)
            builder.redirectErrorStream(false)
            if (workingDir != null) builder.directory(workingDir)
            builder.environment().putAll(environment)
            val started = builder.start()
            process = started
            processId = pidOf(started)
            val out = CapturedStream(started.inputStream, 512 * 1024)
            val err = CapturedStream(started.errorStream, 256 * 1024)
            out.start()
            err.start()
            val finished = started.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                // A timed-out probe may already have spawned helpers; stop the whole managed range
                // before the direct child so the bounded deadline cannot leave an orphan.
                processId?.let { pid -> runCatching { DshProcessTree.signalTree(pid, DshProcessTree.SIGKILL) } }
                started.destroyForcibly()
                started.waitFor(5, TimeUnit.SECONDS)
            }
            out.join(5_000)
            err.join(5_000)
            DshCommandResult(
                exitCode = if (finished) started.exitValue() else Int.MIN_VALUE,
                stdout = out.text(),
                stderr = err.text(),
                timedOut = !finished
            )
        } catch (t: Throwable) {
            try {
                processId?.let { pid -> runCatching { DshProcessTree.signalTree(pid, DshProcessTree.SIGKILL) } }
                process?.destroyForcibly()
            } catch (ignored: Throwable) {
            }
            DshCommandResult(exitCode = Int.MIN_VALUE, stdout = "", stderr = t.toString(), timedOut = false)
        }
    }

    /** Android's ProcessImpl hides the OS pid in a private field; a null pid degrades honestly. */
    private fun pidOf(process: Process): Int? = try {
        val field = process.javaClass.getDeclaredField("pid")
        field.isAccessible = true
        field.getInt(process)
    } catch (t: Throwable) {
        null
    }

    private class CapturedStream(
        private val input: InputStream,
        private val cap: Int
    ) {
        private val buffer = ByteArrayOutputStream()
        private var thread: Thread? = null

        fun start() {
            thread = Thread {
                try {
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        synchronized(buffer) {
                            if (buffer.size() < cap) buffer.write(buf, 0, read)
                        }
                    }
                } catch (ignored: Throwable) {
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        fun join(millis: Long) {
            try {
                thread?.join(millis)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        fun text(): String = synchronized(buffer) { buffer.toByteArray().toString(Charsets.UTF_8) }
    }
}
