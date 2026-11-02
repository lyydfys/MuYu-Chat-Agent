package com.muyuchat.core.dsh.runtime.config

import java.io.File

/**
 * Explicit filesystem roots of one host generation.
 *
 * The production generation derives every root from [DshRuntimeConfig.dataRoot]. An isolated
 * trial generation must run the *same verified payload tree* while keeping its own home,
 * workspace and tmp below `files/dsh/trial`, so it supplies this explicit set instead of a second
 * `dataRoot`. Naming the directories is all this type does: containment, symlink refusal and the
 * "never the real home" rule belong to the trial launcher
 * ([com.muyuchat.core.dsh.runtime.trial.DshTrialRoots]).
 */
data class DshRoots(
    val home: File,
    val workspace: File,
    val tmp: File,
    /** Verified installed payload tree. A trial reuses the production one; it never installs. */
    val payload: File
)

/**
 * Immutable host configuration for one Android DSH runtime installation.
 *
 * All DSH-owned state lives below [dataRoot] (`files/dsh` for the app), separate from
 * MCA chat/model/Kotlin-Agent data:
 *
 * ```
 * files/dsh/
 *   home/       DSH_HOME (official sessions, credentials, profiles, settings)
 *   workspace/  DSH_WORKSPACE (tool working directory)
 *   payload/    verified official kernel + Node runtime libraries
 *   tmp/        TMPDIR
 * ```
 *
 * The class is platform-free so the same values can be validated and used by JVM tests.
 * Use `DshRuntimeConfigs.forApp(...)` for the Android default.
 */
class DshRuntimeConfig(
    /** Absolute app-private DSH root, normally `File(context.filesDir, "dsh")`. */
    val dataRoot: File,
    /** APK asset carrying the official payload zip. */
    val payloadAsset: String = "dsh/payload.zip",
    /** APK asset carrying the signed payload manifest. */
    val manifestAsset: String = "dsh/MANIFEST.json",
    /** jniLibs name of the packaged Node executable (extracted by PackageManager). */
    val nodeLibraryName: String = "libdshnode.so",
    /**
     * Absolute path of the packaged Android Landlock runner (`libdshsandbox.so`), or null when the
     * caller has none (JVM tests, a build without native staging). A null/missing/unusable runner
     * makes the seeder compose the honest advisory shell path instead of claiming confinement.
     */
    val sandboxRunnerFile: File? = null,
    /** Bind address. Only concrete loopback literals are accepted. */
    val host: String = "127.0.0.1",
    /** 0 lets the official web server pick a free port. */
    val port: Int = 0,
    /** Time to wait for the `dsh web:` authenticated entry line. */
    val authLineTimeoutMillis: Long = 150_000L,
    /** Time to wait for the full official readiness surface after the URL line. */
    val readinessTimeoutMillis: Long = 60_000L,
    /** Delay between the two index fetches that prove a stable server. */
    val stabilityDelayMillis: Long = 350L,
    /** How long SIGTERM may take before the stop escalates. */
    val stopGraceMillis: Long = 10_000L,
    /** How long SIGKILL escalation may take before the stop fence is raised. */
    val stopKillMillis: Long = 5_000L,
    /** Bounded stdout/stderr ring buffer line count. */
    val logRingLines: Int = 400,
    /** Timeout for the one-shot `node --version` exec-strategy probe. */
    val nodeExecProbeTimeoutMillis: Long = 40_000L,
    /** Notification channel used by the foreground `:dsh` service. */
    val notificationChannelId: String = "mca_dsh_runtime",
    /** Stable notification id, distinct from MCA's generation service. */
    val notificationId: Int = 0x44534801,
    /** Name of the isolated DSH tool workspace below [dataRoot]. */
    val workspaceDirName: String = "workspace",
    /** Interval used when waiting on a process to settle. */
    val settlePollMillis: Long = 100L,
    /** Maximum bytes kept for one captured log line. */
    val maxLogLineChars: Int = 4_096,
    /**
     * Explicit roots for an isolated generation. Null (production) means every root is derived
     * from [dataRoot]; a trial passes [DshRoots] so it can keep [dataRoot] on its own trial tree
     * (for the seeder's sibling markers) while still executing the verified production payload.
     */
    val roots: DshRoots? = null
) {
    val homeDir: File get() = roots?.home ?: File(dataRoot, "home")
    val workspaceDir: File get() = roots?.workspace ?: File(dataRoot, workspaceDirName)
    val payloadDir: File get() = roots?.payload ?: File(dataRoot, "payload")
    val tmpDir: File get() = roots?.tmp ?: File(dataRoot, "tmp")
    val configDir: File get() = File(homeDir, "config")
    val payloadMarkerFile: File get() = File(dataRoot, "payload.marker.json")

    /**
     * Single app-private lock file guarding payload reservation + publish. Never deleted while a
     * process may hold it: the kernel releases the OS file lock when the holder exits or crashes.
     */
    val payloadInstallLockFile: File get() = File(dataRoot, "payload.install.lock")

    /** Path of the official CLI entry inside the verified payload tree. */
    val kernelEntryRelativePath: String get() = "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"

    /** Path of the Node runtime shared libraries inside the payload tree. */
    val runtimeLibraryRelativePath: String get() = "runtime/lib"

    /** Path of the shell shims inside the payload tree. */
    val payloadBinRelativePath: String get() = "bin"

    /** Path of the official kernel home template inside the payload tree. */
    val dshHomeTemplateRelativePath: String get() = "dshhome"

    fun kernelEntry(): File = File(payloadDir, kernelEntryRelativePath)
    fun runtimeLibraryDir(): File = File(payloadDir, runtimeLibraryRelativePath)
    fun payloadBinDir(): File = File(payloadDir, payloadBinRelativePath)

    /**
     * The same runtime contract (payload verification inputs, assets, timeouts, stop budgets)
     * running on an isolated data root and root set.
     *
     * Used by exactly one caller: the isolated trial generation. It keeps the production payload
     * tree through [roots] while `dataRoot` moves to the trial tree, so the seeder's sibling
     * markers (`<dataRoot>/.mca-home-seeded.json`) can never overwrite the production record.
     */
    fun forIsolatedGeneration(
        dataRoot: File,
        roots: DshRoots,
        port: Int = this.port
    ): DshRuntimeConfig = DshRuntimeConfig(
        dataRoot = dataRoot,
        payloadAsset = payloadAsset,
        manifestAsset = manifestAsset,
        nodeLibraryName = nodeLibraryName,
        sandboxRunnerFile = sandboxRunnerFile,
        host = host,
        port = port,
        authLineTimeoutMillis = authLineTimeoutMillis,
        readinessTimeoutMillis = readinessTimeoutMillis,
        stabilityDelayMillis = stabilityDelayMillis,
        stopGraceMillis = stopGraceMillis,
        stopKillMillis = stopKillMillis,
        logRingLines = logRingLines,
        nodeExecProbeTimeoutMillis = nodeExecProbeTimeoutMillis,
        notificationChannelId = notificationChannelId,
        notificationId = notificationId,
        workspaceDirName = workspaceDirName,
        settlePollMillis = settlePollMillis,
        maxLogLineChars = maxLogLineChars,
        roots = roots
    )

    /**
     * Validates the configuration. A rejected configuration is an integration defect and
     * maps to [com.muyuchat.core.dsh.contract.DshFailureCode.INTERNAL]; it must fail before
     * any process is started.
     */
    fun validated(): DshRuntimeConfig {
        require(dataRoot.isAbsolute) { "dsh: dataRoot must be absolute, was ${dataRoot.path}" }
        require(host == "127.0.0.1" || host == "::1" || host == "localhost") {
            "dsh: host must be a loopback literal, was \"$host\""
        }
        require(port == 0 || port in 1..65535) { "dsh: port out of range: $port" }
        require(authLineTimeoutMillis in 1_000L..600_000L) { "dsh: authLineTimeoutMillis out of range" }
        require(readinessTimeoutMillis in 1_000L..600_000L) { "dsh: readinessTimeoutMillis out of range" }
        require(stopGraceMillis in 1_000L..120_000L) { "dsh: stopGraceMillis out of range" }
        require(stopKillMillis in 500L..60_000L) { "dsh: stopKillMillis out of range" }
        require(logRingLines in 20..4_000) { "dsh: logRingLines out of range" }
        require(nodeExecProbeTimeoutMillis in 2_000L..120_000L) { "dsh: nodeExecProbeTimeoutMillis out of range" }
        require(notificationId != 0) { "dsh: notificationId must not be 0" }
        require(payloadAsset.isNotBlank() && manifestAsset.isNotBlank()) { "dsh: payload assets must be named" }
        require(nodeLibraryName.startsWith("lib") && nodeLibraryName.endsWith(".so")) {
            "dsh: nodeLibraryName must be an APK native-library name (lib*.so), was $nodeLibraryName"
        }
        sandboxRunnerFile?.let { runner ->
            require(runner.isAbsolute) { "dsh: sandboxRunnerFile must be absolute, was ${runner.path}" }
        }
        // The managed runtime cwd is the user workspace, never the replaceable payload tree.
        // `workspaceDirName` is configurable in tests/integration, so reject any value that could
        // resolve the tool working directory onto the verified payload (or reuse another managed
        // DSH root). A tool writing into the payload would corrupt the hash-verified tree and be
        // deleted by the next payload swap.
        require(
            workspaceDirName.isNotBlank() &&
                workspaceDirName != "." &&
                workspaceDirName != ".." &&
                '/' !in workspaceDirName &&
                '\\' !in workspaceDirName
        ) {
            "dsh: workspaceDirName must be one path segment below dataRoot, was \"$workspaceDirName\""
        }
        require(MANAGED_ROOT_NAMES.none { it.equals(workspaceDirName, ignoreCase = File.separatorChar == '\\') }) {
            "dsh: workspaceDirName must not reuse a managed DSH directory, was \"$workspaceDirName\""
        }
        val explicit = roots
        if (explicit != null) {
            // An explicit root set only has to be absolute and internally consistent. Whether it is
            // the *right* generation's root set (isolated trial below files/dsh/trial, never the
            // real home) is an ownership decision made by the trial launcher, not by this value.
            for ((name, dir) in listOf(
                "home" to explicit.home,
                "workspace" to explicit.workspace,
                "tmp" to explicit.tmp,
                "payload" to explicit.payload
            )) {
                require(dir.isAbsolute) { "dsh: explicit $name root must be absolute, was ${dir.path}" }
            }
            require(
                explicit.home != explicit.workspace &&
                    explicit.home != explicit.tmp &&
                    explicit.workspace != explicit.tmp
            ) { "dsh: explicit home/workspace/tmp roots must be distinct" }
        }
        val caseInsensitive = File.separatorChar == '\\'
        val workspacePath = runCatching { workspaceDir.canonicalFile }.getOrElse { workspaceDir.absoluteFile }
        val payloadPath = runCatching { payloadDir.canonicalFile }.getOrElse { payloadDir.absoluteFile }
        val samePath = workspacePath.path.equals(payloadPath.path, ignoreCase = caseInsensitive)
        val nestedInPayload = workspacePath.path.startsWith(
            payloadPath.path + File.separator,
            ignoreCase = caseInsensitive
        )
        require(!samePath && !nestedInPayload) {
            "dsh: the tool workspace must not live inside the replaceable payload tree: ${workspacePath.path}"
        }
        return this
    }

    private companion object {
        /** Sibling roots directly below [dataRoot] with distinct owners/semantics. */
        val MANAGED_ROOT_NAMES = setOf("home", "payload", "tmp")
    }
}
