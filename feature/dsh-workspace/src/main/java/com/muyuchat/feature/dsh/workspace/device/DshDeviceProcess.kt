package com.muyuchat.feature.dsh.workspace.device

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * One already-spawned child process, narrowed to exactly what a channel needs.
 *
 * The seam exists so the root channel can be unit-tested without a `su` binary: tests inject a fake
 * launcher and never spawn a real privileged process.
 */
internal interface DshBackgroundProcess {

    fun stdout(): InputStream

    fun stderr(): InputStream

    /** True when the process exited within [timeoutMs]; false means it is still running. */
    fun waitFor(timeoutMs: Long): Boolean

    fun exitCode(): Int

    /** Force-kill. Called on timeout, on output-limit hits and when the caller is cancelled. */
    fun destroy()
}

/** Process factory seam; production uses [DeviceSuProcessLauncher]. */
internal fun interface DshProcessLauncher {

    /** @throws IOException when the binary is missing or the fork fails. */
    fun start(argv: List<String>): DshBackgroundProcess
}

/** Production transport: `su -c <script>` through the device's root manager. */
internal object DeviceSuProcessLauncher : DshProcessLauncher {

    override fun start(argv: List<String>): DshBackgroundProcess {
        val process = ProcessBuilder(argv).start()
        return object : DshBackgroundProcess {
            override fun stdout(): InputStream = process.inputStream
            override fun stderr(): InputStream = process.errorStream
            override fun waitFor(timeoutMs: Long): Boolean =
                process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

            override fun exitCode(): Int = process.exitValue()
            override fun destroy() {
                // Force-kill: a `su` waiting for a root prompt would otherwise outlive the page.
                runCatching { process.destroyForcibly() }
            }
        }
    }
}

/** One captured stream: UTF-8 text plus whether the byte ceiling was hit. */
internal data class DshBoundedOutput(val text: String, val overflowed: Boolean)

/** One bounded process run; `spawnFailed` and `timedOut` are mutually exclusive with a normal exit. */
internal data class DshProcessRun(
    val exitCode: Int,
    val stdout: DshBoundedOutput,
    val stderr: DshBoundedOutput,
    val timedOut: Boolean,
    val spawnFailed: Boolean
)

/**
 * Reads at most [limitBytes] from [stream] as UTF-8 and reports overflow instead of growing.
 *
 * Diagnostics are text; a truncated multi-byte character at the ceiling is acceptable. The stream is
 * always closed, and a read error is treated as end-of-stream so a killed process cannot throw into
 * the caller.
 */
internal fun readBounded(stream: InputStream, limitBytes: Int): DshBoundedOutput {
    val buffer = ByteArray(READ_CHUNK_BYTES)
    val sink = ByteArrayOutputStream(minOf(limitBytes, INITIAL_BUFFER_BYTES))
    var total = 0
    var overflowed = false
    try {
        while (true) {
            val read = runCatching { stream.read(buffer) }.getOrDefault(-1)
            if (read <= 0) break
            val remaining = limitBytes - total
            if (read > remaining) {
                if (remaining > 0) sink.write(buffer, 0, remaining)
                total = limitBytes
                overflowed = true
                break
            }
            sink.write(buffer, 0, read)
            total += read
        }
    } finally {
        runCatching { stream.close() }
    }
    return DshBoundedOutput(sink.toByteArray().toString(Charsets.UTF_8), overflowed)
}

/**
 * Runs [argv], bounds both streams, enforces [timeoutMs] and always leaves the process dead.
 *
 * Blocking work happens on [dispatcher] (IO in production); the process is destroyed in `finally`, so
 * a cancelled page cannot leak a `su` child. The dispatcher is injectable so JVM tests can run the
 * whole path on a deterministic test scheduler with a fake launcher.
 */
internal suspend fun runDshProcess(
    launcher: DshProcessLauncher,
    argv: List<String>,
    timeoutMs: Long,
    outputLimitBytes: Int = DSH_DEVICE_OUTPUT_LIMIT_BYTES,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
): DshProcessRun = withContext(dispatcher) {
    val process = try {
        launcher.start(argv)
    } catch (error: IOException) {
        return@withContext spawnFailure()
    } catch (error: SecurityException) {
        return@withContext spawnFailure()
    }
    try {
        val stdout = async { readBounded(process.stdout(), outputLimitBytes) }
        val stderr = async { readBounded(process.stderr(), outputLimitBytes) }
        val finished = runCatching { process.waitFor(timeoutMs) }.getOrDefault(false)
        if (!finished) {
            // Unblock the readers before awaiting them: a hung `su` never closes its pipes.
            runCatching { process.destroy() }
        }
        val out = stdout.await()
        val err = stderr.await()
        val exitCode = if (finished) runCatching { process.exitCode() }.getOrDefault(-1) else -1
        DshProcessRun(
            exitCode = exitCode,
            stdout = out,
            stderr = err,
            timedOut = !finished,
            spawnFailed = false
        )
    } finally {
        runCatching { process.destroy() }
    }
}

private fun spawnFailure(): DshProcessRun = DshProcessRun(
    exitCode = DSH_DEVICE_EXIT_NOT_EXECUTED,
    stdout = DshBoundedOutput("", false),
    stderr = DshBoundedOutput("", false),
    timedOut = false,
    spawnFailed = true
)

private const val READ_CHUNK_BYTES = 8 * 1024
private const val INITIAL_BUFFER_BYTES = 16 * 1024
