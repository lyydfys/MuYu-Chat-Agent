package com.muyuchat.core.dsh.runtime.process

import java.io.File

/** One immutable executable description: argv prefix plus a human label. */
data class DshNodeExecutable(
    val argvPrefix: List<String>,
    val strategy: DshNodeExecStrategy.Mode
) {
    init {
        require(argvPrefix.isNotEmpty()) { "dsh: node argv prefix must not be empty" }
        require(argvPrefix.all { it.isNotBlank() }) { "dsh: node argv prefix contains a blank argument" }
    }
}

/**
 * Builds the official Web profile command line and its isolated environment.
 *
 * The official CLI invocation is:
 * `node --expose-internals <dsh>/lib/bin.js web --no-open --host 127.0.0.1 --port <port>`
 * (`--expose-internals` is required by the official loader/HMR path; `--port 0` lets the
 * official web server pick a free port and print it in the readiness line).
 */
object DshNodeCommand {

    fun arguments(
        executable: DshNodeExecutable,
        kernelEntry: File,
        host: String,
        port: Int
    ): List<String> = buildList {
        addAll(executable.argvPrefix)
        add("--expose-internals")
        add(kernelEntry.absolutePath)
        add("web")
        add("--no-open")
        add("--host")
        add(host)
        add("--port")
        add(port.toString())
    }

    /**
     * Isolated environment for the DSH generation. HOME/DSH_HOME/DSH_CONFIG_DIR/TMPDIR all
     * point inside MCA's dedicated `files/dsh` tree; the official kernel and its sessions never
     * see MCA chat/model/Kotlin-Agent directories. Only the loopback bind address is exposed.
     */
    fun environment(
        homeDir: File,
        configDir: File,
        workspaceDir: File,
        tmpDir: File,
        payloadDir: File,
        runtimeLibraryDir: File,
        payloadBinDir: File,
        /**
         * Durable host generation. It becomes `MCA_DSH_GENERATION` in the child environment so
         * every tool/job process spawned by this host can be attributed to this installation
         * after the host dies. Null keeps it absent: an isolated trial generation must never be
         * recovered by production.
         */
        generation: Long? = null
    ): Map<String, String> = linkedMapOf(
        "HOME" to homeDir.absolutePath,
        "DSH_HOME" to homeDir.absolutePath,
        "DSH_CONFIG_DIR" to configDir.absolutePath,
        "DSH_WORKSPACE" to workspaceDir.absolutePath,
        "TMPDIR" to tmpDir.absolutePath,
        "LD_LIBRARY_PATH" to runtimeLibraryDir.absolutePath,
        "PATH" to listOf(
            payloadBinDir.absolutePath,
            File(payloadDir, "runtime/bin").absolutePath,
            "/system/bin",
            "/system/xbin"
        ).joinToString(":"),
        "SHELL" to "/system/bin/sh",
        "TERM" to "xterm",
        // The packaged exec dispatcher (libdshcmd.so, see src/main/cpp/dsh_cmd.c) resolves the
        // payload's JS/Python entry points from this root. Without it the dispatcher refuses to
        // start instead of guessing a path.
        "DSH_PAYLOAD_DIR" to payloadDir.absolutePath,
        // The official surface prints structured startup errors on stderr; keep warnings off
        // but not errors. Telemetry is disabled for the embedded profile.
        "NODE_NO_WARNINGS" to "1",
        "DSH_TELEMETRY_DISABLED" to "1",
        // Filesystem skill provider: the official package only reads a bundled root from this
        // variable, and with no value the catalog has no MCA-shipped content. The root is a
        // subdirectory of the MCA-managed home (installed from the APK by DshBundledSkillInstaller),
        // never a payload path: the payload tree is hash-verified and replaceable. Its rank (600)
        // is the lowest, so project/user skill roots (100/400/...) override the same name.
        DshBundledSkillRoot.ENV_NAME to DshBundledSkillRoot.environmentValue(homeDir)
    ).also { env ->
        if (generation != null && generation > 0L) {
            env[DshJobIdentity.GENERATION_ENV] = generation.toString()
        }
        val openssl = File(payloadDir, "runtime/etc/openssl.cnf")
        if (openssl.isFile) env["OPENSSL_CONF"] = openssl.absolutePath
        // Bundled tool support: git needs its exec path and template dir when the wrapper
        // scripts are unavailable; python needs its stdlib root because the executable is a
        // libdshpython.so symlink (argv[0] would otherwise derive the nativeLibraryDir).
        val gitExec = File(payloadDir, "git/libexec/git-core")
        if (gitExec.isDirectory) {
            env["GIT_EXEC_PATH"] = gitExec.absolutePath
            env["GIT_TEMPLATE_DIR"] = File(payloadDir, "git/templates").absolutePath
            env["GIT_PAGER"] = "cat"
            env["GIT_TERMINAL_PROMPT"] = "0"
            env["GIT_CONFIG_NOSYSTEM"] = "1"
            env["GIT_ATTR_NOSYSTEM"] = "1"
        }
        val pythonHome = File(payloadDir, "python")
        if (File(pythonHome, "lib").isDirectory) env["PYTHONHOME"] = pythonHome.absolutePath
    }
}
