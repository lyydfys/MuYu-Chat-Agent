package com.muyuchat.core.dsh.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.Reader

/**
 * Starts one child process. Injectable so [RootProcessShellRunner] can be unit-tested with a fake
 * [Process] on the JVM: no test depends on a real `su` or on the host operating system.
 */
internal fun interface DshShellProcessStarter {
    fun start(argv: List<String>, environment: Map<String, String>): Process
}

/**
 * Shizuku transport port.
 *
 * This lane deliberately does not bind Shizuku (the Shizuku bridge/app-process API lane owns it),
 * so the runner exposes this seam and degrades with a typed `CAPABILITY_UNAVAILABLE` result when it
 * is not wired. An implementation must be cancellation-cooperative: when the calling coroutine is
 * cancelled it must terminate the remote command before returning.
 */
fun interface DshShizukuShellTransport {
    suspend fun execute(invocation: DshShellInvocation): DshShellRunResult
}

/**
 * Privileged shell runner for the root channel (`su -c <command>`), delegating Shizuku to an
 * injected transport.
 *
 * Behaviour that matters and is covered by JVM tests with a fake [Process]:
 *  - the command is passed as one argv element after `-c`; the runner never builds a second shell
 *    string, so MCA adds no quoting/injection surface of its own;
 *  - the child environment is filtered (linker variables and credential-looking keys removed);
 *  - stdout/stderr are captured through capped buffers that keep draining;
 *  - `onStarted` is called before draining, so the handler can kill a hung process at the deadline.
 *
 * Known gap (reported, not hidden): only the direct child is force-killed. A command that starts
 * its own detached children can leave grandchildren behind; Android's public `Process` API does not
 * expose a portable process-group kill, and the reference implementation has the same limitation.
 */
class RootProcessShellRunner internal constructor(
    private val starter: DshShellProcessStarter = DefaultProcessStarter,
    private val shizuku: DshShizukuShellTransport? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val environment: () -> Map<String, String> = { System.getenv() }
) : DshPrivilegedShellRunner {

    override suspend fun run(
        invocation: DshShellInvocation,
        onStarted: (DshShellHandle) -> Unit
    ): DshShellRunResult = when (invocation.channel) {
        DshPrivilegedChannel.ROOT -> runRoot(invocation, onStarted)
        DshPrivilegedChannel.SHIZUKU -> {
            val transport = shizuku ?: return unavailable(
                "Shizuku 传输未接线：本模块只暴露端口，未实现 Shizuku 绑定"
            )
            withContext(dispatcher) { transport.execute(invocation) }
        }
    }

    private suspend fun runRoot(
        invocation: DshShellInvocation,
        onStarted: (DshShellHandle) -> Unit
    ): DshShellRunResult = withContext(dispatcher) {
        val argv = listOf(SU_BINARY, "-c", invocation.command)
        val childEnvironment = DshShellPolicy.childEnvironment(environment())
        val process = try {
            starter.start(argv, childEnvironment)
        } catch (_: IOException) {
            return@withContext unavailable("无法启动 su 特权通道（设备未授予 root）")
        } catch (_: RuntimeException) {
            return@withContext unavailable("无法启动 su 特权通道（系统拒绝执行）")
        }

        onStarted(DshShellHandle { runCatching { process.destroyForcibly() } })

        val stdout = DshShellOutputBuffer(invocation.stdoutLimit)
        val stderr = DshShellOutputBuffer(invocation.stderrLimit)
        try {
            coroutineScope {
                val stderrDrain = launch { drainQuietly(process.errorStream.bufferedReader(), stderr) }
                drainQuietly(process.inputStream.bufferedReader(), stdout)
                stderrDrain.join()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }

        val exitCode = try {
            process.waitFor()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            -1
        }
        DshShellRunResult(
            exitCode = exitCode,
            stdout = stdout.text(),
            stderr = stderr.text(),
            stdoutTruncated = stdout.truncated,
            stderrTruncated = stderr.truncated
        )
    }

    private fun drainQuietly(reader: Reader, buffer: DshShellOutputBuffer) {
        try {
            reader.use { target ->
                val chunk = CharArray(8_192)
                while (true) {
                    val read = target.read(chunk)
                    if (read < 0) break
                    if (read > 0) buffer.append(String(chunk, 0, read))
                }
            }
        } catch (_: IOException) {
            // The process was force-killed at the deadline; the captured prefix is still valid.
        }
    }

    private fun unavailable(message: String): DshShellRunResult = DshShellRunResult(
        exitCode = -1,
        stdout = "",
        stderr = "",
        failure = DshAndroidActionFailure.of(
            DshAndroidActionErrorCode.CAPABILITY_UNAVAILABLE,
            message
        )
    )

    private companion object {
        const val SU_BINARY = "su"
    }
}

/** Starts `argv` with exactly [environment] as the child environment. */
internal object DefaultProcessStarter : DshShellProcessStarter {
    override fun start(argv: List<String>, environment: Map<String, String>): Process {
        val builder = ProcessBuilder(argv)
        val target = builder.environment()
        target.clear()
        target.putAll(environment)
        return builder.start()
    }
}
