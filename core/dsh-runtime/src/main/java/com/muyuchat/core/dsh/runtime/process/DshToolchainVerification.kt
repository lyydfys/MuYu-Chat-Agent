package com.muyuchat.core.dsh.runtime.process

import java.io.File
import kotlin.math.max

/**
 * Real, bounded executability verification of the DSH command closure.
 *
 * The closure resolver ([DshCommandClosure]) says what *should* be startable; it never starts
 * anything. This verifier is the production proof step: every role is executed with its exact
 * resolved argv (never a shell string), a fixed, side-effect-free probe, and a typed outcome.
 *
 * It is Android-free: production passes [DefaultDshProcessRunner], JVM tests pass a fake. A
 * missing/broken role becomes FAILED/BLOCKED/SPAWN_FAILED/TIMED_OUT with the real exit code and
 * a bounded diagnostic line; nothing here converts an unrun probe into success.
 *
 * Probe facts this intentionally checks (C07/D11):
 *  - `npm`/`npx`/`pnpm` run as `node <payload>.js`, so they do not depend on exec()ing the
 *    writable-home wrapper scripts (the targetSdk 36 EACCES condition);
 *  - `pip` runs as `<python> -m pip`;
 *  - `bash` is the sh compatibility layer, so it is probed with `-c` and a marker, never with a
 *    GNU-bash-only flag;
 *  - every PROBE role is executed; remote git helpers have no version surface and are covered by
 *    dependency evidence + the `git` probe instead of being faked as passing.
 */
enum class DshToolProbeState {
    /** The probe executed, exited 0 and produced the expected marker. */
    PASSED,

    /** The probe executed and completed, but the exit code or output did not prove the role. */
    FAILED,

    /** The closure itself refused this role (missing library/entry/shell). */
    BLOCKED,

    /** The bounded deadline expired; the process was force-stopped by the runner. */
    TIMED_OUT,

    /** No process could be started at all (exec denied, missing loader, ...). */
    SPAWN_FAILED
}

/** One executed (or refused) probe. [output] is a bounded, single-line diagnostic. */
data class DshToolProbe(
    val role: DshCommandRole,
    val argv: List<String>,
    val launch: String,
    val state: DshToolProbeState,
    val exitCode: Int?,
    val output: String,
    val detail: String,
    val durationMillis: Long
) {
    val passed: Boolean get() = state == DshToolProbeState.PASSED
}

/** One packaging fact the command closure depends on, with its concrete path. */
data class DshToolDependency(
    val name: String,
    val kind: Kind,
    val path: String,
    val present: Boolean,
    val detail: String
) {
    enum class Kind { NATIVE_LIBRARY, PAYLOAD_FILE, PAYLOAD_DIRECTORY }
}

/**
 * Immutable verification result for one environment snapshot.
 *
 * [usable] is true only when every executed probe passed and every required dependency exists.
 * Callers must report [failures] verbatim instead of collapsing them into "terminal available".
 *
 * [shellCompatibility] is the typed answer to "is this GNU bash?" for the `bash` role. It is
 * deliberately separate from the probe list: a passing `bash` probe (an `sh` compatibility marker)
 * must never be presented as GNU bash support.
 */
data class DshToolchainReport(
    val probes: List<DshToolProbe>,
    val dependencies: List<DshToolDependency>,
    val verifiedAtMillis: Long,
    val shellCompatibility: DshShellCompatibility = DshShellCompatibility.of(null)
) {
    val failures: List<DshToolProbe> get() = probes.filterNot { it.passed }
    val missingDependencies: List<DshToolDependency> get() = dependencies.filterNot { it.present }
    val usable: Boolean get() = failures.isEmpty() && missingDependencies.isEmpty()

    /** Bounded, log-safe one-line facts for a console/diagnostic projection. */
    fun summary(): String = buildString {
        append("toolchain probes=").append(probes.count { it.passed }).append('/').append(probes.size)
        if (failures.isNotEmpty()) {
            append(" failed=")
            append(failures.joinToString(",") { "${it.role.name}:${it.state.name}" })
        }
        val native = dependencies.count { it.kind == DshToolDependency.Kind.NATIVE_LIBRARY && it.present }
        append(" native=").append(native).append('/')
        append(dependencies.count { it.kind == DshToolDependency.Kind.NATIVE_LIBRARY })
        if (missingDependencies.isNotEmpty()) {
            append(" missing=")
            append(missingDependencies.joinToString(",") { it.name })
        }
        append(' ').append(shellCompatibility.summary())
    }
}

/**
 * Fixed filesystem/environment snapshot the verifier and the terminal host share.
 *
 * The paths are the real ones used to launch the official Node host ([DshNodeCommand.environment]),
 * so the probes execute in the same PATH/LD_LIBRARY_PATH/PYTHONHOME/GIT_EXEC_PATH world as the
 * tools the agent later runs.
 */
data class DshToolchainEnvironment(
    val payloadDir: File,
    val nativeLibraryDir: File,
    val runtimeLibraryDir: File,
    val payloadBinDir: File,
    val homeDir: File,
    val workspaceDir: File,
    val configDir: File,
    val tmpDir: File,
    val nodeNeedsLinker: Boolean = false
) {
    /** Filesystem scan of the real payload tree; no execution. */
    fun closureModel(): DshCommandClosureModel = DshCommandProbe.scan(
        payloadDir = payloadDir,
        nativeLibraryDir = nativeLibraryDir,
        nodeNeedsLinker = nodeNeedsLinker
    )

    /** The exact child environment the official kernel receives for tools. */
    fun childEnvironment(): Map<String, String> = DshNodeCommand.environment(
        homeDir = homeDir,
        configDir = configDir,
        workspaceDir = workspaceDir,
        tmpDir = tmpDir,
        payloadDir = payloadDir,
        runtimeLibraryDir = runtimeLibraryDir,
        payloadBinDir = payloadBinDir
    )

    fun workingDirectory(): String = workspaceDir.absolutePath
}

/**
 * Runs the fixed probe suite. One instance is cheap; call [verify] off the main thread.
 *
 * The runner seam bounds output and time; this class only decides what a result *means*. A probe
 * is never retried with a weaker command to manufacture a pass, and a timeout is never reported
 * as an exit code.
 */
class DshToolchainVerifier(
    private val runner: DshProcessRunner,
    private val clock: () -> Long = System::currentTimeMillis
) {

    fun verify(
        model: DshCommandClosureModel,
        environment: Map<String, String>,
        workingDir: File?,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        roles: List<DshCommandRole> = PROBE_ROLES
    ): DshToolchainReport {
        val resolved = DshCommandClosure.resolve(model)
        val probes = roles.map { role -> probe(role, resolved[role], environment, workingDir, timeoutMillis) }
        return DshToolchainReport(
            probes = probes,
            dependencies = dependencies(model),
            verifiedAtMillis = clock(),
            shellCompatibility = DshShellCompatibility.of(resolved[DshCommandRole.BASH])
        )
    }

    private fun probe(
        role: DshCommandRole,
        availability: DshCommandAvailability?,
        environment: Map<String, String>,
        workingDir: File?,
        timeoutMillis: Long
    ): DshToolProbe {
        val launch = availability?.launch
            ?: return DshToolProbe(
                role = role,
                argv = emptyList(),
                launch = "UNRESOLVED",
                state = DshToolProbeState.BLOCKED,
                exitCode = null,
                output = "",
                detail = "the closure did not resolve this role",
                durationMillis = 0L
            )
        if (launch is DshCommandLaunch.Blocked) {
            return DshToolProbe(
                role = role,
                argv = emptyList(),
                launch = "BLOCKED",
                state = DshToolProbeState.BLOCKED,
                exitCode = null,
                output = "",
                detail = "${launch.reason}: ${launch.detail}",
                durationMillis = 0L
            )
        }
        val arguments = probeArguments(role)
        val argv = try {
            DshCommandClosure.argv(launch, arguments)
        } catch (t: IllegalStateException) {
            return DshToolProbe(
                role = role,
                argv = emptyList(),
                launch = availability.report(),
                state = DshToolProbeState.BLOCKED,
                exitCode = null,
                output = "",
                detail = t.message ?: "argv cannot be built",
                durationMillis = 0L
            )
        }
        val startedAt = clock()
        val result = runner.run(argv, environment, workingDir, timeoutMillis)
        val duration = max(0L, clock() - startedAt)
        val combined = boundedOutput(result.stdout, result.stderr)
        val state = when {
            result.timedOut -> DshToolProbeState.TIMED_OUT
            result.exitCode == Int.MIN_VALUE -> DshToolProbeState.SPAWN_FAILED
            result.ok && provesRole(role, result.stdout, result.stderr) -> DshToolProbeState.PASSED
            else -> DshToolProbeState.FAILED
        }
        return DshToolProbe(
            role = role,
            argv = argv,
            launch = availability.report(),
            state = state,
            exitCode = result.exitCode.takeIf { it != Int.MIN_VALUE },
            output = combined,
            detail = when (state) {
                DshToolProbeState.TIMED_OUT -> "bounded deadline ${timeoutMillis}ms elapsed"
                DshToolProbeState.SPAWN_FAILED -> "the process could not be started"
                DshToolProbeState.PASSED -> "probe accepted"
                else -> "probe exited without proving ${role.pathName}"
            },
            durationMillis = duration
        )
    }

    /** The fixed, side-effect-free probe argv for one role. */
    fun probeArguments(role: DshCommandRole): List<String> = when (role) {
        // Never pass a GNU-bash-only flag here: the packaged name is the sh compatibility layer.
        DshCommandRole.BASH, DshCommandRole.SHELL -> listOf("-c", MARKER_COMMAND)
        DshCommandRole.GIT_REMOTE_HTTP, DshCommandRole.GIT_REMOTE_HTTPS ->
            // git-remote-* has no stable --version surface; it is verified via the git probe and
            // its packaged dependency, not by inventing output.
            listOf("--version")
        else -> listOf("--version")
    }

    private fun provesRole(role: DshCommandRole, stdout: String, stderr: String): Boolean {
        val out = (stdout + "\n" + stderr).trim()
        // Every probe is a version/marker command: no output is never proof of the role.
        if (out.isEmpty()) return false
        return when (role) {
            DshCommandRole.BASH, DshCommandRole.SHELL -> out == MARKER
            DshCommandRole.NODE -> Regex("(?m)^v\\d+\\.\\d+").containsMatchIn(out)
            DshCommandRole.NPM -> Regex("(?m)^\\d+\\.\\d+").containsMatchIn(out)
            DshCommandRole.NPX -> Regex("(?m)^\\d+\\.\\d+").containsMatchIn(out)
            DshCommandRole.PNPM -> Regex("(?m)^\\d+\\.\\d+").containsMatchIn(out)
            DshCommandRole.PIP -> out.contains("pip", ignoreCase = true)
            DshCommandRole.PYTHON -> out.contains("Python", ignoreCase = true)
            DshCommandRole.RIPGREP -> out.contains("ripgrep", ignoreCase = true)
            DshCommandRole.CURL -> out.contains("curl", ignoreCase = true)
            DshCommandRole.GIT -> out.contains("git version", ignoreCase = true)
            DshCommandRole.GIT_REMOTE_HTTP, DshCommandRole.GIT_REMOTE_HTTPS ->
                out.contains("git", ignoreCase = true)
        }
    }

    private fun dependencies(model: DshCommandClosureModel): List<DshToolDependency> = buildList {
        fun native(name: String) {
            add(
                DshToolDependency(
                    name = name,
                    kind = DshToolDependency.Kind.NATIVE_LIBRARY,
                    path = model.nativeLibraryDir.trimEnd('/') + "/" + name,
                    present = model.nativeLibrary(name),
                    detail = if (model.nativeLibrary(name)) {
                        "packaged in nativeLibraryDir"
                    } else {
                        "not packaged in nativeLibraryDir; no Android equivalent of this role"
                    }
                )
            )
        }
        fun payloadFile(relative: String, label: String) {
            add(
                DshToolDependency(
                    name = label,
                    kind = DshToolDependency.Kind.PAYLOAD_FILE,
                    path = model.payloadDir.trimEnd('/') + "/" + relative,
                    present = model.payloadFile(relative),
                    detail = if (model.payloadFile(relative)) "present in the verified payload" else "absent from the verified payload"
                )
            )
        }
        fun payloadDir(relative: String, label: String) {
            val present = model.payloadDirectory(relative) || model.payloadFile(relative)
            add(
                DshToolDependency(
                    name = label,
                    kind = DshToolDependency.Kind.PAYLOAD_DIRECTORY,
                    path = model.payloadDir.trimEnd('/') + "/" + relative,
                    present = present,
                    detail = if (present) "present in the verified payload" else "absent from the verified payload"
                )
            )
        }
        native(DshCommandClosure.Native.NODE)
        native(DshCommandClosure.DISPATCHER_LIBRARY)
        native(DshCommandClosure.PTY_LIBRARY)
        native(DshCommandClosure.Native.PYTHON)
        native(DshCommandClosure.Native.RIPGREP)
        native(DshCommandClosure.Native.CURL)
        native(DshCommandClosure.Native.GIT)
        native(DshCommandClosure.Native.GIT_REMOTE_HTTP)
        native(DshCommandClosure.Native.GIT_REMOTE_HTTPS)
        payloadFile(DshCommandClosure.Payload.NPM_CLI, "npm-cli.js")
        payloadFile(DshCommandClosure.Payload.NPX_CLI, "npx-cli.js")
        payloadFile(DshCommandClosure.Payload.PNPM_CLI, "pnpm.cjs")
        payloadDir(DshCommandClosure.Payload.PYTHON_STDLIB, "python stdlib")
        payloadFile(DshCommandClosure.Payload.BASH, "bash wrapper")
        payloadFile(DshCommandClosure.Payload.GIT, "git launcher")
        payloadFile(DshCommandClosure.Payload.RG, "rg launcher")
        payloadFile(DshCommandClosure.Payload.CURL, "curl launcher")
    }

    companion object {
        /** The roles whose real execution is proven; git remote helpers are dependency-covered. */
        val PROBE_ROLES: List<DshCommandRole> = listOf(
            DshCommandRole.NODE,
            DshCommandRole.NPM,
            DshCommandRole.NPX,
            DshCommandRole.PNPM,
            DshCommandRole.PIP,
            DshCommandRole.PYTHON,
            DshCommandRole.RIPGREP,
            DshCommandRole.CURL,
            DshCommandRole.GIT,
            DshCommandRole.BASH
        )

        /** Per-probe deadline. Long enough for a cold Bionic loader, short enough to fail honestly. */
        const val DEFAULT_TIMEOUT_MILLIS = 8_000L

        const val MARKER = "dsh-toolchain-ok"
        internal const val MARKER_COMMAND = "printf $MARKER"
        private const val MAX_OUTPUT_CHARS = 180

        /** First non-empty, control-free line of stdout/stderr, bounded. Never a token. */
        fun boundedOutput(stdout: String, stderr: String): String {
            val source = stdout.lineSequence().firstOrNull { it.isNotBlank() }
                ?: stderr.lineSequence().firstOrNull { it.isNotBlank() }
                ?: ""
            return source
                .map { if (it.code < 0x20 || it.code == 0x7F) ' ' else it }
                .joinToString("")
                .trim()
                .take(MAX_OUTPUT_CHARS)
        }
    }
}
