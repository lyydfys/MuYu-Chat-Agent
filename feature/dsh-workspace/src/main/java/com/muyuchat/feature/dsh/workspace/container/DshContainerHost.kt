package com.muyuchat.feature.dsh.workspace.container

import kotlinx.coroutines.launch

import android.content.Context
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Process-level owner of the DSH container lane.
 *
 * One host per process, exactly like `DshDeviceChannelHost`: the console section and any future
 * caller must see the same answer for "can this device run proot right now?" instead of each
 * scanning the filesystem and disagreeing.
 *
 * Ownership:
 *  - the host owns the resolved [DshContainerLayout] and reads the filesystem facts on demand;
 *  - it owns nothing privileged: `proot` runs as the app's own uid, and `-0` only fakes root
 *    inside proot's user namespace;
 *  - the only executable argv is [DshContainerCommand.PROBE_ENTRY_COMMAND]; it is rebuilt from the
 *    spec, checked against [DshContainerCommandPolicy] and then handed to a [DshContainerRunner]
 *    with a hard timeout and a bounded output ceiling;
 *  - the long-term owner of this process lane is the existing terminal/command runtime; this
 *    feature-module host exists because this cut cannot change the `core` modules and must not open a
 *    second general-purpose exec surface. It exposes one fixed command, never a shell.
 *
 * What it never does: download proot or a rootfs (they are packaged assets installed by
 * [DshContainerInstaller]), accept user input, elevate the uid, or treat Termux / a SAF tree as a
 * dependency.
 */
class DshContainerHost internal constructor(
    private val layout: DshContainerLayout,
    private val safTreeUris: () -> List<String>,
    private val termuxInstalled: () -> Boolean,
    private val runner: DshContainerRunner = DshContainerProcessRunner(
        extraLibraryDirectory = layout.libraryDir,
        // proot exec()s its loader, not the guest program; the packaged copy lives where this app
        // is allowed to execute from. Without it the probe fails with a misleading "Permission
        // denied" even though every byte is in place.
        prootLoaderPath = layout.nativeLibraryDir
            .takeIf { it.isNotBlank() }
            ?.let { "$it/${DshContainerLayout.PACKAGED_PROOT_LOADER_NAME}" },
        // Scratch only: proot probes the filesystem here but never exec()s from it.
        prootTmpDirectory = "${layout.containerDir}/tmp"
    )
) {

    /**
     * Install progress, owned by the process-level host.
     *
     * It lives here, not in the composable, because an install takes a minute and a recomposition
     * (or a tab switch) must not cancel it or throw away the progress the user is watching.
     */
    internal val installState: kotlinx.coroutines.flow.MutableStateFlow<DshContainerInstallUi> =
        kotlinx.coroutines.flow.MutableStateFlow(DshContainerInstallUi())

    /** Scope for the install job; process-lifetime, never tied to a composition. */
    internal val installScope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )

    /**
     * Runs the six-step install on the host's own scope and publishes progress to
     * [installState].
     *
     * One entry point for the console UI and for the iteration broadcast, so both exercise the same
     * ownership model (progress and cancellation belong to the process-level host, never to a
     * composition).
     */
    fun startInstall(assets: DshContainerAssetSource, layout: DshContainerLayout) {
        val runner = DshContainerProcessRunner(
            extraLibraryDirectory = layout.libraryDir,
            prootLoaderPath = layout.nativeLibraryDir
                .takeIf { it.isNotBlank() }
                ?.let { "$it/${DshContainerLayout.PACKAGED_PROOT_LOADER_NAME}" },
            prootTmpDirectory = "${layout.containerDir}/tmp"
        )
        installScope.launch {
            installState.value = DshContainerInstallUi.installing()
            val result = DshContainerInstaller.install(assets, layout, runner::run) { event ->
                installState.value = installState.value.reduce(event)
            }
            installState.value = installState.value.finished(result)
            android.util.Log.i("MCA-CONTAINER", "install finished: $result")
        }
    }

    /** Current verdict + the spec to use when it is [DshContainerProbeState.SUPPORTED]. */
    suspend fun snapshot(): DshContainerState = withContext(Dispatchers.IO) { snapshotNow() }

    /** Runs the one allowlisted probe. Nothing else in this class executes anything. */
    suspend fun runProbe(): DshContainerProbeRun = withContext(Dispatchers.IO) {
        val state = snapshotNow()
        val spec = state.spec
            ?: return@withContext DshContainerProbeRun.NotRunnable(state.probe.state, state.probe.reason)
        val argv = when (val rendered = DshContainerCommand.probeArgv(spec)) {
            is DshContainerCommandResult.Rejected ->
                return@withContext DshContainerProbeRun.NotRunnable(state.probe.state, rendered.detail)

            is DshContainerCommandResult.Ready -> rendered.argv
        }
        if (!DshContainerCommandPolicy.allowsProbe(argv, spec)) {
            return@withContext DshContainerProbeRun.NotRunnable(
                state.probe.state,
                "探针命令不在允许列表内：容器只执行固定的「${DshContainerCommand.PROBE_ENTRY_COMMAND}」"
            )
        }
        val run = runner.run(argv, PROBE_TIMEOUT_MILLIS)
        DshContainerProbeRun.Finished(
            exitCode = run.exitCode,
            output = run.output,
            timedOut = run.timedOut,
            failure = run.failure
        )
    }

    private fun snapshotNow(): DshContainerState {
        val facts = dshContainerFactsFor(layout, safTreeUris(), termuxInstalled())
        val probe = dshContainerProbeFor(facts)
        val supported = probe.state == DshContainerProbeState.SUPPORTED
        val spec = if (supported) {
            DshContainerSpec(
                rootfsPath = requireNotNull(facts.rootfsPath),
                prootPath = requireNotNull(facts.prootPath),
                mounts = listOf(DshContainerMount(layout.appFilesMount))
            )
        } else {
            null
        }
        return DshContainerState(probe = probe, spec = spec)
    }

    companion object {
        /** Bound of the fixed probe; a container that cannot answer in this window is not ready. */
        const val PROBE_TIMEOUT_MILLIS: Long = 20_000L

        /**
         * Termux is probed for an *informational* detail line only. It is never a dependency, never
         * a gate and never a rejection reason (project rule: no device/app matching may block a
         * user-facing capability).
         */
        const val TERMUX_PACKAGE: String = "com.termux"

        private val lock = Any()
        private var instance: DshContainerHost? = null

        /** The process singleton. Idempotent and thread-safe. */
        fun of(context: Context): DshContainerHost = synchronized(lock) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        internal fun of(
            layout: DshContainerLayout,
            safTreeUris: () -> List<String> = { emptyList() },
            termuxInstalled: () -> Boolean = { false },
            runner: DshContainerRunner = DshContainerProcessRunner(
                extraLibraryDirectory = layout.libraryDir,
                prootLoaderPath = layout.nativeLibraryDir
                    .takeIf { it.isNotBlank() }
                    ?.let { "$it/${DshContainerLayout.PACKAGED_PROOT_LOADER_NAME}" },
                prootTmpDirectory = "${layout.containerDir}/tmp"
            )
        ): DshContainerHost = synchronized(lock) {
            instance ?: DshContainerHost(layout, safTreeUris, termuxInstalled, runner)
                .also { instance = it }
        }

        internal fun resetForTesting() {
            synchronized(lock) { instance = null }
        }

        private fun build(context: Context): DshContainerHost = DshContainerHost(
            layout = DshContainerLayout.forApp(
                filesDir = context.filesDir.absolutePath,
                nativeLibraryDir = context.applicationInfo.nativeLibraryDir
            ),
            safTreeUris = { persistedSafTreeUris(context) },
            termuxInstalled = { isTermuxInstalled(context) }
        )

        private fun persistedSafTreeUris(context: Context): List<String> = runCatching {
            context.contentResolver.persistedUriPermissions
                .filter { permission ->
                    permission.isReadPermission &&
                        runCatching { DocumentsContract.isTreeUri(permission.uri) }.getOrDefault(false)
                }
                .map { permission -> permission.uri.toString() }
        }.getOrDefault(emptyList())

        private fun isTermuxInstalled(context: Context): Boolean = runCatching {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        }.isSuccess
    }
}

/** Probe result of the current window: verdict plus the spec when it is supported. */
data class DshContainerState(
    val probe: DshContainerProbe,
    /** Null unless [DshContainerProbe.state] is [DshContainerProbeState.SUPPORTED]. */
    val spec: DshContainerSpec? = null
)

/** Outcome of the fixed probe command. `NotRunnable` keeps the probe state so the UI can explain. */
sealed interface DshContainerProbeRun {
    data class Finished(
        val exitCode: Int?,
        val output: String,
        val timedOut: Boolean,
        val failure: String?
    ) : DshContainerProbeRun {
        val succeeded: Boolean get() = exitCode == 0 && !timedOut && failure == null
    }

    data class NotRunnable(
        val state: DshContainerProbeState,
        val detail: String
    ) : DshContainerProbeRun
}

/** Raw run of the probe argv; kept tiny so the host can be tested with a fake runner. */
data class DshContainerRun(
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean,
    val failure: String?
)

/** Narrow execution seam of the container lane (the only call site is [DshContainerHost.runProbe]). */
fun interface DshContainerRunner {
    fun run(argv: List<String>, timeoutMillis: Long): DshContainerRun
}

/**
 * Default runner over `java.lang.ProcessBuilder`.
 *
 * Bounds: no shell, no stdin, merged output truncated to [maxOutputBytes], and a hard timeout that
 * force-kills the process. A timeout is reported as `timedOut`, never as success.
 */
internal class DshContainerProcessRunner(
    private val maxOutputBytes: Int = 8 * 1024,
    /** Directory holding proot's SONAME dependencies; see [DshContainerLayout.libraryDir]. */
    private val extraLibraryDirectory: String? = null,
    /**
     * proot's own EFI loader, shipped as `libproot_loader.so` in the native library directory.
     *
     * proot never exec()s the guest program: it exec()s this loader, which maps the guest ELF and
     * jumps into it. Left unset, proot writes the loader to a temp file and exec()s it there, which
     * Android denies for targetSdk>=29 ("execve(...): Permission denied"). Naming the packaged copy
     * puts that one exec() into the only directory this app may execute from.
     */
    private val prootLoaderPath: String? = null,
    /** Writable, non-executed scratch directory proot may use for its probes. */
    private val prootTmpDirectory: String? = null
) : DshContainerRunner {

    override fun run(argv: List<String>, timeoutMillis: Long): DshContainerRun {
        val process = try {
            val builder = ProcessBuilder(argv).redirectErrorStream(true)
            // proot links against libtalloc.so.2 and libandroid-shmem.so that sit next to it in
            // the APK native library directory. The app process has that directory on its search
            // path, but an exec()ed child does not inherit that by itself, so name it explicitly
            // and keep whatever the app already had.
            val executableDir = java.io.File(argv.first()).parent
            val searchPath = listOfNotNull(executableDir, extraLibraryDirectory)
                .filter { it.isNotBlank() }
                .joinToString(":")
            if (searchPath.isNotBlank()) {
                val environment = builder.environment()
                val existing = environment["LD_LIBRARY_PATH"]
                environment["LD_LIBRARY_PATH"] =
                    if (existing.isNullOrBlank()) searchPath else searchPath + ":" + existing
            }
            builder.environment().apply {
                // The loader must be exec()-able *at the path proot execs it from*; the native
                // library directory is the only such place for a targetSdk>=29 app.
                prootLoaderPath?.takeIf { it.isNotBlank() }?.let { put("PROOT_LOADER", it) }
                // Scratch space only (proot never execs from here), so app-private is correct.
                prootTmpDirectory?.takeIf { it.isNotBlank() }?.let { put("PROOT_TMP_DIR", it) }
            }
            builder.start()
        } catch (t: Throwable) {
            return DshContainerRun(
                exitCode = null,
                output = "",
                timedOut = false,
                failure = "无法启动 proot：${t.javaClass.simpleName}: ${t.message?.take(160)}"
            )
        }
        runCatching { process.outputStream.close() }
        val output = StringBuilder()
        val drain = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        if (output.length < maxOutputBytes) {
                            output.append(line).append('\n')
                        }
                        line = reader.readLine()
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val finished = runCatching {
            process.waitFor(timeoutMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!finished) {
            runCatching { process.destroyForcibly() }
            drain.join(500)
            return DshContainerRun(
                exitCode = null,
                output = output.toString(),
                timedOut = true,
                failure = "proot 探针在 ${timeoutMillis}ms 内没有结束，已强制结束"
            )
        }
        drain.join(1_000)
        return DshContainerRun(
            exitCode = runCatching { process.exitValue() }.getOrNull(),
            output = output.toString().take(maxOutputBytes),
            timedOut = false,
            failure = null
        )
    }
}
