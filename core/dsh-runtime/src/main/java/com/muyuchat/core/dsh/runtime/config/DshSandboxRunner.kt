package com.muyuchat.core.dsh.runtime.config

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * How model-initiated shell commands are confined for the current host generation.
 *
 * The pinned official payload has two honest compositions:
 * - [Kernel]: the official `bash-sandbox` executor wraps every command through the official
 *   `sandbox-local` provider, whose `runnerCommand` is MCA's Android Landlock runner. The kernel
 *   denies writes outside the session workspace/temp roots, including child processes.
 * - [Advisory]: no Landlock runner is usable on this device/kernel. The official `bash-local`
 *   executor is composed with `sandboxMode = workspace-write` only so the permission presets and
 *   tools accept it; the mode is **not enforced** and the UI must say so.
 *
 * This type is the single fact both the seeder (overlay composition) and the console projection
 * consume, so the composed configuration and the displayed capability cannot drift.
 */
internal sealed interface DshSandboxEnforcement {
    val summary: String

    data class Kernel(val landlockAbi: Int, val partial: Boolean) : DshSandboxEnforcement {
        override val summary: String
            get() = if (partial) {
                "kernel(landlock abi=$landlockAbi, partial)"
            } else {
                "kernel(landlock abi=$landlockAbi)"
            }
    }

    data class Advisory(val reason: Reason) : DshSandboxEnforcement {
        override val summary: String get() = "advisory(${reason.name})"
    }

    enum class Reason {
        /** The APK carries no `libdshsandbox.so` for this ABI. */
        RUNNER_MISSING,
        /** The runner exists but cannot be executed by this process. */
        RUNNER_NOT_EXECUTABLE,
        /** The functional probe failed or timed out; its detail is already logged without values. */
        KERNEL_PROBE_FAILED
    }
}

/** One functional probe outcome; the detail is a closed, value-free reason. */
internal sealed interface DshSandboxProbeResult {
    data class Enforced(val landlockAbi: Int, val partial: Boolean) : DshSandboxProbeResult
    data class Unavailable(val reason: DshSandboxEnforcement.Reason) : DshSandboxProbeResult
}

/**
 * Runs the native runner's `--probe` in [workingDir].
 *
 * The probe creates and removes a scratch directory below the working directory, applies a real
 * Landlock write ruleset and proves both that a granted write succeeds and that a denied write
 * fails with a denial errno. A missing/refusing kernel therefore degrades to [Advisory] instead of
 * publishing kernel confinement that does not exist.
 */
internal object DshSandboxProbe {

    fun probe(
        runner: File?,
        workingDir: File,
        timeoutMillis: Long = 3_000L
    ): DshSandboxProbeResult {
        if (runner == null || !runner.isFile) {
            return DshSandboxProbeResult.Unavailable(DshSandboxEnforcement.Reason.RUNNER_MISSING)
        }
        if (!runner.canExecute()) {
            return DshSandboxProbeResult.Unavailable(DshSandboxEnforcement.Reason.RUNNER_NOT_EXECUTABLE)
        }
        return try {
            val process = ProcessBuilder(runner.absolutePath, "--probe")
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()
            val output = readBounded(process)
            val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return DshSandboxProbeResult.Unavailable(DshSandboxEnforcement.Reason.KERNEL_PROBE_FAILED)
            }
            if (process.exitValue() != 0) {
                return DshSandboxProbeResult.Unavailable(DshSandboxEnforcement.Reason.KERNEL_PROBE_FAILED)
            }
            val abi = ABI_PATTERN.find(output)?.groupValues?.get(1)?.toIntOrNull()
                ?: return DshSandboxProbeResult.Unavailable(DshSandboxEnforcement.Reason.KERNEL_PROBE_FAILED)
            DshSandboxProbeResult.Enforced(
                landlockAbi = abi,
                partial = output.contains("partial enforcement")
            )
        } catch (_: Throwable) {
            DshSandboxProbeResult.Unavailable(DshSandboxEnforcement.Reason.KERNEL_PROBE_FAILED)
        }
    }

    private fun readBounded(process: Process): String {
        val bytes = ByteArray(MAX_PROBE_OUTPUT_BYTES)
        var length = 0
        process.inputStream.use { stream ->
            while (length < bytes.size) {
                val read = stream.read(bytes, length, bytes.size - length)
                if (read <= 0) break
                length += read
            }
        }
        return String(bytes, 0, length, Charsets.UTF_8)
    }

    private const val MAX_PROBE_OUTPUT_BYTES = 4_096
    private val ABI_PATTERN = Regex("""landlock abi=(\d+)""")
}
