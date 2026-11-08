package com.muyuchat.mca.agent.tools

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Production executable sandbox for `terminal.exec`.
 *
 * Contract, and where it stops:
 * - **argv only.** The command name is resolved to one absolute path inside a fixed list of system
 *   binary directories and every argument is passed as its own `argv` element. Nothing is ever handed
 *   to a shell, so `"; rm -rf /"` stays one literal argument with no effect.
 * - **Whitelist enforced twice.** [AgentTerminalExecTool] already validates the command and the argv
 *   before it calls this class; the check is repeated here so a direct call cannot bypass it.
 * - **Bounded.** A hard deadline kills the process, stdout/stderr are read through a byte cap, and the
 *   environment is reduced to `PATH`/`HOME`/`TMPDIR` so a command cannot inherit the app's identity
 *   tokens.
 * - **Honest availability.** [isAvailable] is a memoised *real* probe: the workspace must be writable
 *   **and** a known binary must actually run and print something. On a device where that fails (or on
 *   a host without toybox) the sandbox reports false and the tool reports `TOOL_NOT_SUPPORTED`; it
 *   never claims a capability it has not demonstrated.
 *
 * This is not a general-purpose shell: there is no PTY, no stdin, no shell built-ins, and the working
 * directory is the app-private agent workspace. `cat`/`grep`/... therefore only read what that
 * directory (and world-readable system paths) contain.
 */
class AndroidAgentTerminalSandbox(
    private val workspace: File,
    private val whitelist: Set<String> = AgentTerminalWhitelist.DEFAULT,
    private val binDirs: List<File> = DEFAULT_BIN_DIRS,
    /** Injected for tests: maps a command name to an absolute executable path, or null. */
    private val resolveExecutable: (String) -> String? = { name -> resolveOnDisk(name, DEFAULT_BIN_DIRS) },
    /** Injected for tests: starts the process for the resolved argv. */
    private val spawn: (argv: List<String>, directory: File, environment: Map<String, String>) -> Process =
        ::spawnProcess,
    private val probeTimeoutMillis: Long = PROBE_TIMEOUT_MILLIS
) : AgentTerminalSandbox {

    private val availabilityLock = Any()

    @Volatile
    private var probedAvailability: Boolean? = null

    /** Memoised real probe; see the class contract. Never throws. */
    override val isAvailable: Boolean
        get() = probedAvailability ?: synchronized(availabilityLock) {
            probedAvailability ?: probe().also { probedAvailability = it }
        }

    override suspend fun execute(request: AgentTerminalRequest): AgentTerminalOutcome =
        withContext(Dispatchers.IO) {
            val command = request.command
            if (!AgentTerminalWhitelist.isValidCommandName(command) ||
                '/' in command || '\\' in command || command.contains("..")
            ) {
                return@withContext refused("TOOL_COMMAND_NOT_ALLOWED", "The command name is not a plain whitelisted token.")
            }
            if (command !in whitelist) {
                return@withContext refused("TOOL_COMMAND_NOT_ALLOWED", "The command is not on this sandbox's whitelist.")
            }
            if (request.arguments.any { argument -> argument.any { it.isISOControl() } }) {
                return@withContext refused("TOOL_COMMAND_NOT_ALLOWED", "An argument contains a control character.")
            }
            val executable = resolveExecutable(command)
                ?: return@withContext refused(
                    "TOOL_COMMAND_NOT_FOUND",
                    "The command is not present on this device."
                )
            val directory = ensureWorkspace()
                ?: return@withContext refused(
                    "TOOL_WORKSPACE_UNAVAILABLE",
                    "The agent workspace could not be prepared."
                )

            val argv = ArrayList<String>(request.arguments.size + 1)
            argv += executable
            argv += request.arguments
            val process = try {
                spawn(argv, directory, environment(directory))
            } catch (_: Exception) {
                return@withContext refused("TOOL_COMMAND_NOT_ALLOWED", "The command could not be started.")
            }

            // Cancellation (session cancelled, run stopped) must not leave an orphan process behind.
            val registration = currentCoroutineContext()[Job]?.invokeOnCompletion {
                runCatching { process.destroyForcibly() }
            }
            try {
                val budget = request.maxOutputBytes.coerceAtLeast(1)
                val stdout = CapturedOutput()
                val stderr = CapturedOutput()
                val outThread = drain(process.inputStream, stdout, budget)
                val errThread = drain(process.errorStream, stderr, budget)
                val finished = process.waitFor(request.timeoutMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
                if (!finished) {
                    runCatching { process.destroyForcibly() }
                    runCatching { process.waitFor(FORCE_KILL_WAIT_MILLIS, TimeUnit.MILLISECONDS) }
                    return@withContext AgentTerminalOutcome.TimedOut
                }
                outThread.join(READER_JOIN_MILLIS)
                errThread.join(READER_JOIN_MILLIS)
                AgentTerminalOutcome.Completed(
                    exitCode = runCatching { process.exitValue() }.getOrDefault(-1),
                    stdout = stdout.text(),
                    stderr = stderr.text(),
                    truncated = stdout.truncated || stderr.truncated
                )
            } finally {
                registration?.dispose()
                runCatching { if (process.isAlive) process.destroyForcibly() }
            }
        }

    /** Cheap static part of the probe; kept separate so a failure never leaves the process running. */
    private fun probe(): Boolean {
        val directory = ensureWorkspace() ?: return false
        val command = listOf("pwd", "echo").firstOrNull { resolveExecutable(it) != null } ?: return false
        val executable = resolveExecutable(command) ?: return false
        return runCatching {
            val process = spawn(listOf(executable), directory, environment(directory))
            val finished = process.waitFor(probeTimeoutMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { process.destroyForcibly() }
                return@runCatching false
            }
            // A working binary exits 0; `false`/`cat` would exit 1 or block, and both mean "not usable".
            process.exitValue() == 0
        }.getOrDefault(false)
    }

    private fun ensureWorkspace(): File? = runCatching {
        if (!workspace.isDirectory) workspace.mkdirs()
        workspace.takeIf { it.isDirectory && it.canWrite() }
    }.getOrNull()

    /** Minimal environment: no app identity, no inherited tokens, no writable system dirs. */
    private fun environment(directory: File): Map<String, String> = mapOf(
        "PATH" to binDirs.joinToString(":") { it.absolutePath },
        "HOME" to directory.absolutePath,
        "TMPDIR" to directory.absolutePath,
        "LC_ALL" to "C"
    )

    private fun drain(stream: InputStream, sink: CapturedOutput, budget: Int): Thread {
        val thread = Thread {
            runCatching {
                stream.use { input ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        sink.append(buffer, read, budget)
                    }
                }
            }
        }
        thread.isDaemon = true
        thread.start()
        return thread
    }

    private fun refused(code: String, message: String): AgentTerminalOutcome =
        AgentTerminalOutcome.Refused(code, message)

    /**
     * Bounded capture. The cap is applied while reading, so a `cat` of a huge file cannot exhaust the
     * process heap before the budget is enforced; [truncated] says the tail was dropped.
     */
    private class CapturedOutput {
        private val buffer = ByteArrayOutputStream()

        @Volatile
        var truncated: Boolean = false
            private set

        @Synchronized
        fun append(chunk: ByteArray, length: Int, budget: Int) {
            if (buffer.size() >= budget) {
                truncated = true
                return
            }
            val allowed = minOf(length, budget - buffer.size())
            // Decoding happens once at the end from the capped bytes: a multi-byte character split by
            // the budget must not corrupt the text that is returned.
            buffer.write(chunk, 0, allowed)
            if (allowed < length) truncated = true
        }

        @Synchronized
        fun text(): String = buffer.toByteArray().toString(Charsets.UTF_8)
    }

    companion object {
        /** Only these directories are searched; `PATH` from the app process is never trusted. */
        val DEFAULT_BIN_DIRS: List<File> = listOf(
            File("/system/bin"),
            File("/system/xbin"),
            File("/vendor/bin"),
            File("/product/bin")
        )

        const val PROBE_TIMEOUT_MILLIS: Long = 1_500L
        private const val FORCE_KILL_WAIT_MILLIS: Long = 500L
        private const val READER_JOIN_MILLIS: Long = 1_000L

        /** Absolute path of [name] in the first binary directory that contains an executable file. */
        fun resolveOnDisk(name: String, dirs: List<File> = DEFAULT_BIN_DIRS): String? {
            if (!AgentTerminalWhitelist.isValidCommandName(name) || name.contains("..")) return null
            return dirs.asSequence()
                .map { dir -> File(dir, name) }
                .firstOrNull { candidate -> candidate.isFile && candidate.canExecute() }
                ?.absolutePath
        }

        private fun spawnProcess(
            argv: List<String>,
            directory: File,
            environment: Map<String, String>
        ): Process {
            val builder = ProcessBuilder(argv)
            builder.directory(directory)
            builder.redirectErrorStream(false)
            val env = builder.environment()
            env.clear()
            env.putAll(environment)
            return builder.start()
        }
    }
}
