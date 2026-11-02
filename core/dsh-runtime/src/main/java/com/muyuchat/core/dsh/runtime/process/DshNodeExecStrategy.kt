package com.muyuchat.core.dsh.runtime.process

import java.io.File

/**
 * Chooses how the packaged Android Node executable can actually be started on this device and
 * target SDK.
 *
 * Android 10+ forbids exec() of files in the writable app home for apps targeting 29+, so the
 * host never relies on `filesDir` execution. Candidates, tried in order:
 *
 * 1. [Mode.NATIVE_LIBRARY] — the Node ELF is packaged as an APK native library and executed
 *    from `nativeLibraryDir`, a PackageManager-managed location outside the writable home;
 * 2. [Mode.LINKER64] — invoke the system dynamic linker with the native-library path, which is
 *    the documented workaround when direct exec of that location is denied.
 *
 * The choice is made by a real `--version` run (not by file permissions or a device model
 * check) and cached for the process lifetime by the caller.
 */
class DshNodeExecStrategy(private val runner: DshProcessRunner) {

    enum class Mode {
        NATIVE_LIBRARY,
        LINKER64
    }

    data class Choice(
        val executable: DshNodeExecutable,
        val nodeVersion: String
    )

    /**
     * @param nodeLibrary APK-extracted `libdshnode.so` inside `nativeLibraryDir`.
     * @return the first strategy that really executed, or null when neither did.
     */
    fun resolve(
        nodeLibrary: File,
        environment: Map<String, String>,
        workingDir: File?,
        timeoutMillis: Long
    ): Choice? {
        if (!nodeLibrary.isFile) return null
        val direct = runner.run(listOf(nodeLibrary.absolutePath, "--version"), environment, workingDir, timeoutMillis)
        if (direct.ok && isNodeVersion(direct.stdout)) {
            return Choice(
                executable = DshNodeExecutable(
                    argvPrefix = listOf(nodeLibrary.absolutePath),
                    strategy = Mode.NATIVE_LIBRARY
                ),
                nodeVersion = direct.stdout.trim()
            )
        }
        val linker = runner.run(
            listOf(LINKER64, nodeLibrary.absolutePath, "--version"),
            environment,
            workingDir,
            timeoutMillis
        )
        if (linker.ok && isNodeVersion(linker.stdout)) {
            return Choice(
                executable = DshNodeExecutable(
                    argvPrefix = listOf(LINKER64, nodeLibrary.absolutePath),
                    strategy = Mode.LINKER64
                ),
                nodeVersion = linker.stdout.trim()
            )
        }
        return null
    }

    /**
     * The exec probe's failure detail for diagnostics: no tokens, only exit codes and the
     * first line of stderr (bounded, redacted by the caller's log sink).
     */
    fun probeFailureDetail(
        nodeLibrary: File,
        environment: Map<String, String>,
        workingDir: File?,
        timeoutMillis: Long
    ): String {
        val direct = runner.run(listOf(nodeLibrary.absolutePath, "--version"), environment, workingDir, timeoutMillis)
        val linker = runner.run(
            listOf(LINKER64, nodeLibrary.absolutePath, "--version"),
            environment,
            workingDir,
            timeoutMillis
        )
        return "direct[exit=${direct.exitCode},timedOut=${direct.timedOut},err=${direct.stderr.trim().take(200)}] " +
            "linker64[exit=${linker.exitCode},timedOut=${linker.timedOut},err=${linker.stderr.trim().take(200)}]"
    }

    private fun isNodeVersion(stdout: String): Boolean {
        val trimmed = stdout.trim()
        return trimmed.startsWith("v") && trimmed.substring(1).firstOrNull()?.isDigit() == true
    }

    companion object {
        const val LINKER64 = "/system/bin/linker64"
    }
}
