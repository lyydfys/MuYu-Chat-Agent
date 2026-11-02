package com.muyuchat.core.dsh.vscreen

import java.io.IOException
import java.io.Reader
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Health of the Shizuku transport, derived from a real execution and never from a stored flag.
 *
 * `READY` means the loader answered as uid 2000 on this device. Every other value is a typed
 * reason, so the wire layer can explain the blocker without echoing a process environment.
 */
enum class DshPrivilegedChannelState {
    /** No execution has completed yet. */
    UNKNOWN,

    /** The loader answered and the remote shell is uid 2000. */
    READY,

    /** The Shizuku manager package is not installed. */
    NOT_INSTALLED,

    /** Shizuku is installed but its server/binder is not running (or the request timed out). */
    SERVER_NOT_RUNNING,

    /** The app has no Shizuku permission, or the user refused it. */
    PERMISSION_DENIED,

    /** The `rish_shizuku.dex` loader is missing, unreadable, writable, or incompatible. */
    DEX_UNUSABLE,

    /** The channel answered before and is gone now; a new probe is required before reuse. */
    LOST
}

/** Terminal outcome of one privileged child process. Output is already bounded by the runner. */
data class DshPrivilegedProcessOutcome(
    val started: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    /** Sanitized start failure (`ENOENT`, `EACCES`, ...), never the argv. */
    val startFailure: String? = null
)

/** Starts one child process. Injectable so JVM tests never spawn a real `app_process`. */
fun interface DshPrivilegedProcessStarter {
    fun start(argv: List<String>, environment: Map<String, String>): Process
}

/**
 * Runs one privileged child process under hard budgets.
 *
 * Contract:
 *  - the child environment is exactly the filtered app environment plus the transport's own keys;
 *  - stdin is written (or closed) before any output is drained, and never logged;
 *  - stdout/stderr are captured through bounded sinks that keep draining;
 *  - the deadline and caller cancellation both force-kill the child within one 200 ms slice;
 *  - the runner returns a typed outcome and never throws for an expected negative result.
 *
 * Known limitation (reported, not hidden): only the direct child is killed. `app_process` that
 * starts a detached grandchild can outlive it; Android's public `Process` API exposes no portable
 * process-group kill, and the pinned reference has the same limitation.
 */
class DshPrivilegedProcessRunner(
    private val starter: DshPrivilegedProcessStarter = DshPrivilegedProcessStarter { argv, environment ->
        DefaultDshPrivilegedProcessStarter.start(argv, environment)
    },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val environment: () -> Map<String, String> = { System.getenv() }
) {

    suspend fun run(
        argv: List<String>,
        extraEnvironment: Map<String, String> = emptyMap(),
        stdin: String? = null,
        timeoutMillis: Long,
        stdoutLimit: Int,
        stderrLimit: Int
    ): DshPrivilegedProcessOutcome = withContext(dispatcher) {
        require(argv.isNotEmpty()) { "a privileged command needs an argv" }
        require(timeoutMillis in DshVscreenLimits.MIN_COMMAND_TIMEOUT_MILLIS..DshVscreenLimits.MAX_COMMAND_TIMEOUT_MILLIS) {
            "privileged timeout is out of range"
        }
        require(stdoutLimit in 1..DshVscreenLimits.MAX_COMMAND_STDOUT_CHARS) { "stdout limit is out of range" }
        require(stderrLimit in 1..DshVscreenLimits.MAX_COMMAND_STDERR_CHARS) { "stderr limit is out of range" }
        val childEnvironment = DshVscreenCommandFactory.sanitizeEnvironment(environment()) + extraEnvironment
        val process = try {
            starter.start(argv, childEnvironment)
        } catch (failure: IOException) {
            return@withContext notStarted(failure)
        } catch (failure: RuntimeException) {
            return@withContext notStarted(failure)
        }
        val stdout = DshBoundedText(stdoutLimit)
        val stderr = DshBoundedText(stderrLimit)
        try {
            writeStdin(process, stdin)
            val stdoutDrain = thread(isDaemon = true, name = "dsh-privileged-stdout") {
                drain(process.inputStream.bufferedReader(), stdout)
            }
            val stderrDrain = thread(isDaemon = true, name = "dsh-privileged-stderr") {
                drain(process.errorStream.bufferedReader(), stderr)
            }
            val finished = awaitTermination(process, timeoutMillis)
            if (!finished) {
                runCatching { process.destroyForcibly() }
            }
            stdoutDrain.join(DRAIN_JOIN_MILLIS)
            stderrDrain.join(DRAIN_JOIN_MILLIS)
            DshPrivilegedProcessOutcome(
                started = true,
                exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1,
                stdout = stdout.text(),
                stderr = stderr.text(),
                timedOut = !finished,
                stdoutTruncated = stdout.truncated,
                stderrTruncated = stderr.truncated
            )
        } finally {
            if (process.isAlive) runCatching { process.destroyForcibly() }
        }
    }

    private fun notStarted(failure: Throwable): DshPrivilegedProcessOutcome =
        DshPrivilegedProcessOutcome(
            started = false,
            exitCode = -1,
            stdout = "",
            stderr = "",
            timedOut = false,
            stdoutTruncated = false,
            stderrTruncated = false,
            startFailure = DshShizukuDiagnostics.sanitize(failure.message)
        )

    private fun writeStdin(process: Process, stdin: String?) {
        try {
            process.outputStream.use { stream ->
                if (stdin != null) {
                    stream.write(stdin.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
            }
        } catch (failure: IOException) {
            // A loader that exits before reading stdin is expected on a refused channel.
        }
    }

    /** Waits in cancellation-checkable slices so a cancelled caller kills the child promptly. */
    private suspend fun awaitTermination(process: Process, timeoutMillis: Long): Boolean {
        var remaining = timeoutMillis
        while (remaining > 0L) {
            if (!coroutineContext.isActive) return false
            val slice = minOf(remaining, WAIT_SLICE_MILLIS)
            val exited = try {
                process.waitFor(slice, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            if (exited) return true
            remaining -= slice
        }
        return false
    }

    private fun drain(reader: Reader, sink: DshBoundedText) {
        try {
            reader.use { source ->
                val buffer = CharArray(4_096)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read > 0) sink.append(buffer, read)
                }
            }
        } catch (failure: IOException) {
            // The child was force-killed at the deadline; the captured prefix stays valid.
        }
    }

    private companion object {
        const val WAIT_SLICE_MILLIS = 200L
        const val DRAIN_JOIN_MILLIS = 1_000L
    }
}

internal object DefaultDshPrivilegedProcessStarter {
    fun start(argv: List<String>, environment: Map<String, String>): Process {
        val builder = ProcessBuilder(argv)
        val target = builder.environment()
        target.clear()
        target.putAll(environment)
        return builder.start()
    }
}

/** Append-only, bounded character sink. One writer thread per sink; reads happen after join. */
internal class DshBoundedText(private val limit: Int) {
    private val builder = StringBuilder(minOf(limit, 4_096))

    var truncated: Boolean = false
        private set

    @Synchronized
    fun append(source: CharArray, length: Int) {
        val room = limit - builder.length
        if (room <= 0) {
            truncated = true
            return
        }
        if (length <= room) {
            builder.append(source, 0, length)
        } else {
            builder.append(source, 0, room)
            truncated = true
        }
    }

    @Synchronized
    fun text(): String = builder.toString()
}

/** The argv/environment pair the pinned package's own `rish` script produces. */
data class DshShizukuRishInvocation(
    val argv: List<String>,
    val environment: Map<String, String>
)

/**
 * One audited Shizuku invocation.
 *
 * Shape (mirrors `manager/src/main/assets/rish` in RikkaApps/Shizuku):
 *
 * ```text
 * /system/bin/app_process -Djava.class.path=<dex> /system/bin --nice-name=<name> \
 *     rikka.shizuku.shell.ShizukuShellLoader -c <command>
 * ```
 *
 * with `RISH_APPLICATION_ID=<app id>` in the child environment. The loader binds to the Shizuku
 * server and runs `command` in the shell (uid 2000); the app id is how Shizuku attributes the
 * permission request. No token ever appears here.
 */
object DshShizukuRishCommand {
    const val APP_PROCESS = "/system/bin/app_process"
    const val LOADER_CLASS = "rikka.shizuku.shell.ShizukuShellLoader"
    const val APPLICATION_ID_KEY = "RISH_APPLICATION_ID"
    const val DEFAULT_NICE_NAME = "dsh-vscreen"

    private val APPLICATION_ID = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+){1,10}")
    private val NICE_NAME = Regex("[A-Za-z0-9_.-]{1,32}")

    fun build(
        dexPath: String,
        applicationId: String,
        shellCommand: String,
        niceName: String = DEFAULT_NICE_NAME
    ): DshShizukuRishInvocation {
        require(isUsableDexPath(dexPath)) { "the rish loader dex must be an absolute bounded path" }
        require(APPLICATION_ID.matches(applicationId)) { "the Shizuku application id is not identifier-shaped" }
        require(NICE_NAME.matches(niceName)) { "the privileged process nice name is not identifier-shaped" }
        require(shellCommand.isNotBlank() && shellCommand.length <= DshVscreenLimits.MAX_COMMAND_ARG_CHARS) {
            "the privileged shell command must be non-empty and bounded"
        }
        require(shellCommand.none { it.code < 0x20 || it.code in 0x7F..0x9F }) {
            "the privileged shell command must be a single line"
        }
        return DshShizukuRishInvocation(
            argv = listOf(
                APP_PROCESS,
                "-Djava.class.path=$dexPath",
                "/system/bin",
                "--nice-name=$niceName",
                LOADER_CLASS,
                "-c",
                shellCommand
            ),
            environment = mapOf(APPLICATION_ID_KEY to applicationId)
        )
    }

    fun isUsableDexPath(path: String): Boolean =
        path.startsWith("/") &&
            path.length <= 240 &&
            path.none { it.code < 0x20 || it.code in 0x7F..0x9F }
}

/** Turns loader output into a typed channel state. Never echoes a full line to the caller. */
object DshShizukuDiagnostics {

    /**
     * Classifies only *transport* signatures. An ordinary non-zero command (for example `input`
     * rejecting a display id) must not be mistaken for a dead channel, so this returns null when
     * the output carries no known loader signature.
     */
    fun classify(outcome: DshPrivilegedProcessOutcome): DshPrivilegedChannelState? {
        if (!outcome.started) {
            val failure = outcome.startFailure?.lowercase(Locale.ROOT).orEmpty()
            return when {
                failure.contains("error=13") || failure.contains("eacces") || failure.contains("permission") ->
                    DshPrivilegedChannelState.DEX_UNUSABLE
                else -> DshPrivilegedChannelState.LOST
            }
        }
        val text = outcome.stderr.lowercase(Locale.ROOT)
        if (text.isEmpty() && outcome.exitCode == 0 && !outcome.timedOut) return DshPrivilegedChannelState.READY
        if (text.contains("server is not running") || text.contains("request timeout")) {
            return DshPrivilegedChannelState.SERVER_NOT_RUNNING
        }
        if (text.contains("rish_shizuku.dex") ||
            text.contains("writable dex") ||
            text.contains("shizuku v12") ||
            text.contains("class not found")
        ) {
            return DshPrivilegedChannelState.DEX_UNUSABLE
        }
        if (text.contains("shizuku") && (text.contains("denied") || text.contains("permission") || text.contains("授权"))) {
            return DshPrivilegedChannelState.PERMISSION_DENIED
        }
        if (text.contains("binder has been finalized") || text.contains("dead object") || text.contains("service has died")) {
            return DshPrivilegedChannelState.LOST
        }
        if (outcome.timedOut) return DshPrivilegedChannelState.LOST
        return null
    }

    /** Fixed, secret-free explanation of one state. */
    fun detailOf(state: DshPrivilegedChannelState): String = when (state) {
        DshPrivilegedChannelState.UNKNOWN -> "the Shizuku channel has not been probed yet"
        DshPrivilegedChannelState.READY -> "the Shizuku shell answered as uid 2000"
        DshPrivilegedChannelState.NOT_INSTALLED -> "the Shizuku manager package is not installed"
        DshPrivilegedChannelState.SERVER_NOT_RUNNING ->
            "Shizuku is installed but its service is not running; start Shizuku and retry"
        DshPrivilegedChannelState.PERMISSION_DENIED ->
            "MCA has no Shizuku permission; grant it in the Shizuku app and retry"
        DshPrivilegedChannelState.DEX_UNUSABLE ->
            "the rish loader dex is missing, unreadable, writable, or from an incompatible Shizuku"
        DshPrivilegedChannelState.LOST ->
            "the privileged channel stopped answering; re-probe before the next command"
    }

    /** One bounded, single-line diagnostic. Never the argv, the stdin or a full environment. */
    fun sanitize(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val builder = StringBuilder(minOf(raw.length, DshVscreenLimits.MAX_PRIVILEGED_DIAGNOSTIC_CHARS + 1))
        var pendingSpace = false
        for (character in raw) {
            val forbidden = character.code < 0x20 || character.code in 0x7F..0x9F
            if (forbidden) {
                pendingSpace = builder.isNotEmpty()
                continue
            }
            if (pendingSpace) {
                builder.append(' ')
                pendingSpace = false
            }
            builder.append(character)
            if (builder.length > DshVscreenLimits.MAX_PRIVILEGED_DIAGNOSTIC_CHARS) break
        }
        val text = builder.toString().trim()
        if (text.isEmpty()) return null
        return text.take(DshVscreenLimits.MAX_PRIVILEGED_DIAGNOSTIC_CHARS)
    }
}

/** Result of a real channel probe. */
data class DshPrivilegedProbe(
    val available: Boolean,
    val uid: Int?,
    val state: DshPrivilegedChannelState,
    val detail: String
) {
    init {
        require(detail.isNotBlank() && detail.length <= DshVscreenLimits.MAX_PRIVILEGED_DETAIL_CHARS) {
            "probe detail must be bounded"
        }
        require(detail.none { it.code < 0x20 || it.code in 0x7F..0x9F }) { "probe detail must be single-line" }
        require(!available || state == DshPrivilegedChannelState.READY) { "an available probe must be READY" }
    }
}

/** Result of one executed privileged shell command. */
data class DshPrivilegedExecution(
    val started: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val state: DshPrivilegedChannelState,
    val detail: String
) {
    init {
        require(detail.isNotBlank() && detail.length <= DshVscreenLimits.MAX_PRIVILEGED_DETAIL_CHARS) {
            "execution detail must be bounded"
        }
    }

    /** True when the failure is the transport, not the command the caller asked to run. */
    val channelLost: Boolean
        get() = state == DshPrivilegedChannelState.LOST ||
            state == DshPrivilegedChannelState.SERVER_NOT_RUNNING ||
            state == DshPrivilegedChannelState.PERMISSION_DENIED ||
            state == DshPrivilegedChannelState.DEX_UNUSABLE ||
            state == DshPrivilegedChannelState.NOT_INSTALLED
}

/**
 * The real Shizuku execution channel.
 *
 * Ownership: one instance per app process, created by the app composition. Every command is
 * serialized through [mutex] because the privileged lane is a user-visible, consent-gated
 * capability and two interleaved `input` sequences would be indistinguishable.
 *
 * Death and rebind: each invocation re-executes the loader, which re-binds to the Shizuku server.
 * [state] is therefore only a report of the last execution; a dead channel recovers on the next
 * [probe] without any cached "granted" flag being trusted.
 */
class DshShizukuShellChannel(
    private val runner: DshPrivilegedProcessRunner,
    private val dexPath: () -> String?,
    private val applicationId: String,
    private val niceName: String = DshShizukuRishCommand.DEFAULT_NICE_NAME
) {

    private val mutex = Mutex()

    @Volatile
    var state: DshPrivilegedChannelState = DshPrivilegedChannelState.UNKNOWN
        private set

    /**
     * Tri-state proof for read-only projections: null = never probed, true = a real `id -u`
     * (or a later successful shell command) proved uid 2000, false = the last proof failed.
     * A projection must never turn package presence into this fact.
     */
    @Volatile
    private var provedAvailable: Boolean? = null

    /** Last observed proof; null until this process has run a real probe or shell command. */
    fun lastProbeAvailable(): Boolean? = provedAvailable

    /** Proves the channel by running `id -u` through the loader and reading the real uid. */
    suspend fun probe(): DshPrivilegedProbe = mutex.withLock {
        val dex = dexPath()
        if (dex == null) {
            state = DshPrivilegedChannelState.DEX_UNUSABLE
            provedAvailable = false
            return@withLock DshPrivilegedProbe(
                available = false,
                uid = null,
                state = state,
                detail = "no rish loader dex is provisioned; " + DshShizukuDiagnostics.detailOf(state)
            )
        }
        val invocation = try {
            DshShizukuRishCommand.build(dex, applicationId, PROBE_COMMAND, niceName)
        } catch (rejected: IllegalArgumentException) {
            state = DshPrivilegedChannelState.DEX_UNUSABLE
            provedAvailable = false
            return@withLock DshPrivilegedProbe(
                available = false,
                uid = null,
                state = state,
                detail = ("the rish invocation was rejected: " + (rejected.message ?: "invalid configuration"))
                    .take(DshVscreenLimits.MAX_PRIVILEGED_DETAIL_CHARS)
            )
        }
        val outcome = runner.run(
            argv = invocation.argv,
            extraEnvironment = invocation.environment,
            timeoutMillis = PROBE_TIMEOUT_MILLIS,
            stdoutLimit = PROBE_STDOUT_CHARS,
            stderrLimit = PROBE_STDERR_CHARS
        )
        val uid = outcome.stdout.trim().toIntOrNull()
        val available = outcome.started && !outcome.timedOut && outcome.exitCode == 0 && uid == SHELL_UID
        provedAvailable = available
        val signal = DshShizukuDiagnostics.classify(outcome)
        state = when {
            available -> DshPrivilegedChannelState.READY
            signal != null && signal != DshPrivilegedChannelState.READY -> signal
            else -> DshPrivilegedChannelState.LOST
        }
        val extra = DshShizukuDiagnostics.sanitize(outcome.stderr)
        val base = DshShizukuDiagnostics.detailOf(state)
        val detail = when {
            available -> "Shizuku shell probe answered uid $SHELL_UID"
            uid != null && outcome.exitCode == 0 -> "$base (probe uid $uid)"
            extra != null -> "$base: $extra"
            else -> base
        }
        DshPrivilegedProbe(
            available = available,
            uid = uid,
            state = state,
            detail = detail.take(DshVscreenLimits.MAX_PRIVILEGED_DETAIL_CHARS)
        )
    }

    /** Builds the rish line for [shellCommand] and executes it. Serialized with [probe]. */
    suspend fun executeShell(
        shellCommand: String,
        timeoutMillis: Long,
        stdoutLimit: Int = DshVscreenLimits.MAX_COMMAND_STDOUT_CHARS,
        stderrLimit: Int = DshVscreenLimits.MAX_COMMAND_STDERR_CHARS
    ): DshPrivilegedExecution = mutex.withLock {
        val dex = dexPath()
            ?: return@withLock unavailable(
                DshPrivilegedChannelState.DEX_UNUSABLE,
                "no rish loader dex is provisioned"
            )
        val invocation = try {
            DshShizukuRishCommand.build(dex, applicationId, shellCommand, niceName)
        } catch (rejected: IllegalArgumentException) {
            return@withLock unavailable(
                DshPrivilegedChannelState.DEX_UNUSABLE,
                ("the rish invocation was rejected: ${rejected.message ?: "invalid configuration"}")
                    .take(DshVscreenLimits.MAX_PRIVILEGED_DETAIL_CHARS)
            )
        }
        executeLocked(
            argv = invocation.argv,
            environment = invocation.environment,
            stdin = null,
            timeoutMillis = timeoutMillis,
            stdoutLimit = stdoutLimit,
            stderrLimit = stderrLimit
        )
    }

    /** Executes an already-audited argv (used by the virtual-screen command factory). */
    suspend fun executeInvocation(
        argv: List<String>,
        environment: Map<String, String>,
        stdin: String?,
        timeoutMillis: Long,
        stdoutLimit: Int = DshVscreenLimits.MAX_COMMAND_STDOUT_CHARS,
        stderrLimit: Int = DshVscreenLimits.MAX_COMMAND_STDERR_CHARS
    ): DshPrivilegedExecution = mutex.withLock {
        executeLocked(argv, environment, stdin, timeoutMillis, stdoutLimit, stderrLimit)
    }

    private suspend fun executeLocked(
        argv: List<String>,
        environment: Map<String, String>,
        stdin: String?,
        timeoutMillis: Long,
        stdoutLimit: Int,
        stderrLimit: Int
    ): DshPrivilegedExecution {
        val outcome = runner.run(
            argv = argv,
            extraEnvironment = environment,
            stdin = stdin,
            timeoutMillis = timeoutMillis,
            stdoutLimit = stdoutLimit,
            stderrLimit = stderrLimit
        )
        DshShizukuDiagnostics.classify(outcome)?.let { signal -> state = signal }
        if (outcome.started && !outcome.timedOut && outcome.exitCode == 0 &&
            state != DshPrivilegedChannelState.LOST && state != DshPrivilegedChannelState.PERMISSION_DENIED &&
            state != DshPrivilegedChannelState.SERVER_NOT_RUNNING && state != DshPrivilegedChannelState.DEX_UNUSABLE &&
            state != DshPrivilegedChannelState.NOT_INSTALLED
        ) {
            provedAvailable = true
        }
        val detail = DshShizukuDiagnostics.detailOf(state)
        return DshPrivilegedExecution(
            started = outcome.started,
            exitCode = outcome.exitCode,
            stdout = outcome.stdout,
            stderr = outcome.stderr,
            timedOut = outcome.timedOut,
            stdoutTruncated = outcome.stdoutTruncated,
            stderrTruncated = outcome.stderrTruncated,
            state = state,
            detail = detail
        )
    }

    private fun unavailable(state: DshPrivilegedChannelState, detail: String): DshPrivilegedExecution {
        this.state = state
        return DshPrivilegedExecution(
            started = false,
            exitCode = -1,
            stdout = "",
            stderr = "",
            timedOut = false,
            stdoutTruncated = false,
            stderrTruncated = false,
            state = state,
            detail = detail.take(DshVscreenLimits.MAX_PRIVILEGED_DETAIL_CHARS)
        )
    }

    private companion object {
        const val PROBE_COMMAND = "id -u"
        const val SHELL_UID = 2000
        const val PROBE_TIMEOUT_MILLIS = 5_000L
        const val PROBE_STDOUT_CHARS = 64
        const val PROBE_STDERR_CHARS = 1_600
    }
}
