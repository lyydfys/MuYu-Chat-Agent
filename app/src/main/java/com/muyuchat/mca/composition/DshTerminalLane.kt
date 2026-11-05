package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteTable
import com.muyuchat.core.dsh.contract.runtime.DshTerminalCapability
import com.muyuchat.core.dsh.runtime.config.DshRuntimeConfig
import com.muyuchat.core.dsh.runtime.process.DshTerminalAssembly
import com.muyuchat.core.dsh.runtime.process.DshTerminalHostShutdown
import com.muyuchat.core.dsh.runtime.process.DshTerminalSeamInstaller
import com.muyuchat.core.dsh.runtime.process.DshTerminalToolService
import com.muyuchat.core.dsh.runtime.process.DshTerminalSeamState
import java.io.File

/**
 * App-composition owner of the terminal lane: one [DshTerminalAssembly] per capability generation.
 *
 * Five ownership questions, answered here:
 *  1. who creates: [open] creates exactly one assembly for the requested generation, and only after
 *     the previous generation's owner was stopped;
 *  2. who uses: the capability bridge mounts [open]'s route table; no second runtime can be created
 *     by a route, because the routes belong to that one assembly;
 *  3. who cancels: [stop] (called by the bridge generation stop) runs the bounded
 *     SIGTERM -> SIGKILL process-group ladder of every session;
 *  4. who releases: the assembly closes its sessions and the route table becomes unreachable when
 *     the generation's server stops; the seam state and the stop facts stay readable;
 *  5. who recovers: the next generation calls [open] again with a new generation number; a closed
 *     assembly is never reused, and an unsettled stop is retained as evidence instead of being
 *     reported as a clean stop.
 *
 * It also installs the home-level JS seam (see [DshTerminalSeamInstaller]) for the generation. The
 * install is idempotent and refuses to touch a home that is not seeded yet; a refusal is a typed
 * state on [facts], never a silent success.
 *
 * Android-free: the asset text and the runtime config arrive as lambdas, so the whole generation
 * policy is unit tested on the JVM.
 */
internal class DshTerminalLane(
    private val configProvider: () -> DshRuntimeConfig,
    private val nativeLibraryDir: File,
    private val seamSource: () -> String?,
    /** Home-level shell-env contributor source; null keeps the generation without it. */
    private val shellEnvSource: () -> String? = { null },
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    /** Session-lifetime maintenance admission; null keeps the lane admission-free. */
    private val sessionAdmission: com.muyuchat.core.dsh.contract.capability.DshTerminalSessionAdmission? = null,
    private val createAssembly: (DshRuntimeConfig, File) -> DshTerminalAssembly = { config, nativeDir ->
        DshTerminalAssembly(
            config = config,
            nativeLibraryDir = nativeDir,
            log = log,
            sessionAdmission = sessionAdmission
        )
    }
) {

    private val lock = Any()

    private var assembly: DshTerminalAssembly? = null
    private var generation: Long = 0L
    private var unavailableReason: String? = null

    /** Install facts are published off the lock (the install does file IO) and read under it. */
    @Volatile
    private var seamState: DshTerminalSeamState? = null

    private var lastShutdown: DshTerminalHostShutdown? = null
    private var lastStopGeneration: Long = 0L
    private var lastStopFailure: String? = null

    /**
     * Opens the terminal owner for [generation] and returns its route table.
     *
     * Returns null (and records a typed [DshTerminalLaneFacts.unavailableReason]) when the runtime
     * config or the assembly itself cannot be built; the bridge then simply has no terminal family
     * instead of a half-wired one. Repeated calls for the same generation are idempotent.
     */
    fun open(generation: Long): DshCapabilityRouteTable? {
        val existing = synchronized(lock) {
            assembly?.takeIf { this.generation == generation }
        }
        if (existing != null) return existing.routes()

        val stale = synchronized(lock) {
            val previous = assembly
            val previousGeneration = this.generation
            assembly = null
            previous?.let { it to previousGeneration }
        }
        stale?.let { (previous, previousGeneration) ->
            stopAssembly(previous, previousGeneration, "replaced by generation $generation")
        }

        val config = runCatching { configProvider() }.getOrElse { failure ->
            recordUnavailable(
                "config: ${failure.javaClass.simpleName}" +
                    failure.message?.take(120)?.let { ": $it" }.orEmpty()
            )
            return null
        }
        seamState = installSeam(config)
        val created = runCatching { createAssembly(config, nativeLibraryDir) }.getOrElse { failure ->
            recordUnavailable("assembly: ${failure.javaClass.simpleName}")
            return null
        }
        val routes = runCatching { created.routes() }.getOrElse { failure ->
            // A started owner must never be orphaned by a failed route build.
            runCatching { created.stop() }
            recordUnavailable("routes: ${failure.javaClass.simpleName}")
            return null
        }
        synchronized(lock) {
            assembly = created
            this.generation = generation
            unavailableReason = null
        }
        log(
            "dsh terminal lane READY generation=$generation transport=${capabilityName(created)} " +
                "seam=${seamState?.summary}"
        )
        return routes
    }

    /**
     * Stops the current generation's owner and retains the real stop facts.
     *
     * The facts are kept on [facts] so an unsettled stop (a survivor of the bounded ladder) is
     * visible instead of being collapsed into "stopped".
     */
    fun stop(): DshTerminalHostShutdown? {
        val current = synchronized(lock) {
            val previous = assembly
            val previousGeneration = generation
            assembly = null
            previous?.let { it to previousGeneration }
        } ?: return null
        return stopAssembly(current.first, current.second, "generation stop")
    }

    /** True only while a generation's terminal owner is open. */
    val isOpen: Boolean
        get() = synchronized(lock) { assembly != null }

    /** Read-only projection of the lane; never creates or opens anything. */
    /**
     * The service of the generation that currently owns a terminal assembly, or null.
     *
     * The native terminal page reads this on every call instead of caching it, so a generation stop
     * closes the terminal rather than leaving the page writing into a dead owner.
     */
    fun service(): DshTerminalToolService? = synchronized(lock) { assembly }?.service

    fun facts(): DshTerminalLaneFacts {
        val snapshot = synchronized(lock) {
            Snapshot(
                assembly = assembly,
                generation = generation,
                unavailableReason = unavailableReason,
                seam = seamState,
                lastShutdown = lastShutdown,
                lastStopGeneration = lastStopGeneration,
                lastStopFailure = lastStopFailure
            )
        }
        val active = snapshot.assembly
        if (active == null) {
            return DshTerminalLaneFacts(
                active = false,
                generation = snapshot.generation,
                transport = null,
                realPty = false,
                sessions = 0,
                unavailableReason = snapshot.unavailableReason,
                seam = snapshot.seam,
                toolchain = null,
                lastStopSettled = snapshot.lastShutdown?.settled,
                lastStopFailure = snapshot.lastStopFailure,
                lastStopSurvivors = snapshot.lastShutdown?.survivors.orEmpty(),
                lastStopGeneration = snapshot.lastStopGeneration
            )
        }
        val capability = runCatching { active.service.capability() }.getOrNull()
        return DshTerminalLaneFacts(
            active = true,
            generation = snapshot.generation,
            transport = capability?.transport?.name,
            realPty = capability is DshTerminalCapability.RealPty,
            sessions = runCatching { active.service.list().size }.getOrDefault(0),
            unavailableReason = capabilityUnavailableDetail(capability),
            seam = snapshot.seam,
            toolchain = runCatching { active.service.lastToolchainReport()?.summary() }.getOrNull(),
            lastStopSettled = snapshot.lastShutdown?.settled,
            lastStopFailure = snapshot.lastStopFailure,
            lastStopSurvivors = snapshot.lastShutdown?.survivors.orEmpty(),
            lastStopGeneration = snapshot.lastStopGeneration
        )
    }

    private fun installSeam(config: DshRuntimeConfig): DshTerminalSeamState {
        val source = runCatching { seamSource() }.getOrNull()
        if (source.isNullOrBlank()) {
            return DshTerminalSeamState.Failed(
                DshTerminalSeamState.FailureCode.SOURCE_REJECTED,
                "the terminal seam asset is not packaged in this build"
            )
        }
        return runCatching { DshTerminalSeamInstaller(config.homeDir, clock).install(source) }
            .getOrElse { failure ->
                DshTerminalSeamState.Failed(
                    DshTerminalSeamState.FailureCode.OVERLAY_WRITE_FAILED,
                    "seam install raised ${failure.javaClass.simpleName}"
                )
            }
            .also { state ->
                if (state !is DshTerminalSeamState.Active) {
                    log("dsh terminal seam not active: ${state.summary}")
                }
            }
            .also { installShellEnvContributor(config) }
    }

    /**
     * Writes the shell-env contributor for the seeded profile. The overlay row is a package
     * specifier (`@mca/dsh-shell-env`) because a relative `.mjs` row is silently skipped by the
     * loader (device evidence 2026-10-25). A missing profile (first run before the seeder) is a
     * no-op: the next generation retries. The home copy is kept for diagnostics.
     */
    private fun installShellEnvContributor(config: DshRuntimeConfig) {
        if (!config.homeDir.isDirectory) return
        val source = shellEnvSource() ?: return
        if (source.isBlank()) return
        writeIfChanged(File(config.homeDir, "mca-shell-env.mjs"), source, "home")
        val profile = File(config.homeDir, "profiles/web")
        if (!File(profile, "cordis.yml").isFile) return
        val packageDir = File(profile, "node_modules/@mca/dsh-shell-env")
        writeIfChanged(File(packageDir, "index.mjs"), source, "package")
        writeIfChanged(
            File(packageDir, "package.json"),
            """{"name":"@mca/dsh-shell-env","version":"0.0.0","private":true,"type":"module","main":"index.mjs","exports":"./index.mjs"}""",
            "package manifest"
        )
    }

    private fun writeIfChanged(target: File, text: String, label: String) {
        if (target.isFile && runCatching { target.readText() == text }.getOrDefault(false)) return
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(text)
        }.onFailure { log("dsh shell-env $label write failed: ${it.javaClass.simpleName}") }
    }

    private fun stopAssembly(
        target: DshTerminalAssembly,
        generation: Long,
        reason: String
    ): DshTerminalHostShutdown? {
        val failure = runCatching { target.stop() }
        val shutdown = failure.getOrNull()
        if (failure.isFailure) {
            log(
                "dsh terminal stop raised generation=$generation ($reason): " +
                    failure.exceptionOrNull()?.javaClass?.simpleName
            )
        }
        if (shutdown != null && !shutdown.settled) {
            log("dsh terminal stop UNSETTLED generation=$generation survivors=${shutdown.survivors}")
        }
        synchronized(lock) {
            lastShutdown = shutdown
            lastStopGeneration = generation
            lastStopFailure = failure.exceptionOrNull()?.let { it.javaClass.simpleName }
        }
        return shutdown
    }

    private fun recordUnavailable(reason: String) {
        synchronized(lock) { unavailableReason = reason }
        log("dsh terminal lane unavailable: $reason")
    }

    private fun capabilityName(assembly: DshTerminalAssembly): String =
        runCatching { assembly.service.capability().transport.name }.getOrDefault("UNKNOWN")

    private fun capabilityUnavailableDetail(capability: DshTerminalCapability?): String? = when (capability) {
        is DshTerminalCapability.RealPty -> null
        is DshTerminalCapability.PipeFallback -> "${capability.reason.name}: ${capability.detail}"
        is DshTerminalCapability.Unavailable -> "${capability.reason.name}: ${capability.detail}"
        null -> "the terminal transport could not be determined"
    }

    private class Snapshot(
        val assembly: DshTerminalAssembly?,
        val generation: Long,
        val unavailableReason: String?,
        val seam: DshTerminalSeamState?,
        val lastShutdown: DshTerminalHostShutdown?,
        val lastStopGeneration: Long,
        val lastStopFailure: String?
    )
}

/**
 * Read-only terminal facts the console and diagnostics render.
 *
 * No token, no path and no child output: only the transport class, bounded counts and the typed
 * seam/stop states.
 */
internal data class DshTerminalLaneFacts(
    val active: Boolean,
    val generation: Long,
    /** `REAL_PTY` / `PIPE_FALLBACK`, or null while no owner is open. */
    val transport: String?,
    val realPty: Boolean,
    val sessions: Int,
    /** Concrete reason a real PTY is unavailable in this generation, or null when it is real. */
    val unavailableReason: String?,
    val seam: DshTerminalSeamState?,
    val toolchain: String?,
    /** Null when no generation was stopped yet, or when the stop itself raised. */
    val lastStopSettled: Boolean?,
    val lastStopFailure: String?,
    val lastStopSurvivors: List<String>,
    val lastStopGeneration: Long
) {

    /** A real PTY is the only state MCA presents as a working terminal. */
    val providesRealTerminal: Boolean get() = active && realPty

    /** Bounded prompt-free detail for the console capability row (contract limit: 300 chars). */
    fun consoleDetail(): String {
        val text = when {
            unavailableReason != null && !active -> "terminal owner unavailable: $unavailableReason"
            !active -> "the terminal owner is not open in this generation"
            realPty -> "real pty · sessions=$sessions · ${seamSummary()}"
            else -> "no real pty (${transport ?: "UNKNOWN"}: ${unavailableReason ?: "unreported"}) · " +
                "sessions=$sessions · ${seamSummary()}"
        }
        return text
            .map { if (it.code < 0x20 || it.code == 0x7F) ' ' else it }
            .joinToString("")
            .take(MAX_DETAIL_CHARS)
    }

    private fun seamSummary(): String = seam?.summary ?: "seam=unknown"

    private companion object {
        const val MAX_DETAIL_CHARS = 300
    }
}
