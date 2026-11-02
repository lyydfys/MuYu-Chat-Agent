package com.muyuchat.core.dsh.runtime

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import com.muyuchat.core.dsh.contract.DshTrialHealthSnapshot
import com.muyuchat.core.dsh.contract.DshTrialSnapshot
import com.muyuchat.core.dsh.contract.DshWebEntry
import com.muyuchat.core.dsh.contract.capability.DshCapabilityBridgeEndpoint
import com.muyuchat.core.dsh.runtime.config.DshGatewayConfigSeeder
import com.muyuchat.core.dsh.runtime.config.DshRoots
import com.muyuchat.core.dsh.runtime.config.DshRuntimeConfig
import com.muyuchat.core.dsh.runtime.config.DshSeedOutcome
import com.muyuchat.core.dsh.runtime.launch.DshHostLauncher
import com.muyuchat.core.dsh.runtime.launch.DshLaunchOutcome
import com.muyuchat.core.dsh.runtime.launch.DshLaunchProfile
import com.muyuchat.core.dsh.runtime.launch.DshSeedAttempt
import com.muyuchat.core.dsh.runtime.payload.DshClosurePayloadAdmission
import com.muyuchat.core.dsh.runtime.log.DshLogRedactor
import com.muyuchat.core.dsh.runtime.payload.DshPayloadProvisioner
import com.muyuchat.core.dsh.runtime.process.DshJobOrphan
import com.muyuchat.core.dsh.runtime.process.DshJobSurvival
import com.muyuchat.core.dsh.runtime.process.DshNodeCommand
import com.muyuchat.core.dsh.runtime.process.DshNodeExecStrategy
import com.muyuchat.core.dsh.runtime.process.DshNodeExecutable
import com.muyuchat.core.dsh.runtime.process.DshNodeHost
import com.muyuchat.core.dsh.runtime.readiness.DshReadinessGate
import com.muyuchat.core.dsh.runtime.startup.DshStartupProgress
import com.muyuchat.core.dsh.runtime.startup.DshStartupRecorder
import com.muyuchat.core.dsh.runtime.startup.DshStartupStage
import com.muyuchat.core.dsh.runtime.state.DshApplyRequest
import com.muyuchat.core.dsh.runtime.state.DshApplyResult
import com.muyuchat.core.dsh.runtime.state.DshBindingId
import com.muyuchat.core.dsh.runtime.state.DshEpochStore
import com.muyuchat.core.dsh.runtime.state.DshHostConfig
import com.muyuchat.core.dsh.runtime.state.DshRuntimeStateMachine
import com.muyuchat.core.dsh.runtime.state.DshStopFence
import com.muyuchat.core.dsh.runtime.trial.DshTrialAdmission
import com.muyuchat.core.dsh.runtime.trial.DshTrialGenerationHost
import com.muyuchat.core.dsh.runtime.trial.readProcCommandLine
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The single owner of the Android DSH host generation.
 *
 * It serializes start/restart/stop, verifies the packaged payload, chooses the proven Node
 * execution strategy, launches the official Web profile, waits for the official readiness
 * surface, and publishes an immutable [DshRuntimeSnapshot] with a monotonically increasing
 * epoch. Repeated `ensureStarted` calls attach the same generation; they never start a second
 * Node process. A generation's authenticated entry is kept in memory only and is cleared when
 * the generation changes.
 *
 * Next to the frozen snapshot it publishes a typed [DshStartupProgress] timeline
 * (process alive -> port listening -> HTTP authenticated -> plugins loaded), so the DSHA-style
 * startup boundaries are observable instead of one opaque STARTING phase. A rendered WebView
 * frame is app-process state: it is never inferred here and is recorded only from the explicit,
 * epoch-scoped [reportFirstFrameRendered] observation of the owning WebView.
 *
 * Binding reconciliation: the host reads its gateway, workspace and bridge once at process start,
 * so [ensureApplied] replaces a READY generation whenever that binding changed (gateway port or
 * token, model catalog, capability bridge, composition identity) and confirms it via readiness.
 *
 * Isolated closure trial (D24): [startTrial]/[stopTrial]/[trialStatus]/[trialHealth] delegate the
 * one non-publishing generation to [DshTrialGenerationHost]; the slot, mutex and fence stay here.
 *
 * The class is platform-free (no Android imports); the `:dsh` service supplies the Android
 * seams through [Deps]. Blocking install/process/HTTP work runs on [ioDispatcher].
 */
class DshRuntimeOwner(
    private val config: DshRuntimeConfig,
    private val deps: Deps,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Durable last-epoch high-water mark. Without it a recreated `:dsh` Service starts its
     * in-memory counter at zero and can republish epoch 1 (device evidence 2026-10-25).
     */
    private val epochStore: DshEpochStore? = null,
    /**
     * Durable background-job ledger directory (`files/dsh/jobs` from the `:dsh` service). It
     * enables the READY census and the prior-generation recovery; null disables both, so a JVM
     * test or a composition without job survival never touches `/proc` from a background thread.
     */
    private val jobLedgerDir: File? = null
) {
    /** Android seams supplied by the `:dsh` service composition. */
    interface Deps {
        val payload: DshPayloadProvisioner
        val execStrategy: DshNodeExecStrategy
        val readinessGate: DshReadinessGate
        val supportedAbis: List<String>
        val packagedAbis: List<String>
        fun nativeLibraryDir(): File
        fun newNodeHost(): DshNodeHost
        fun isProcessAlive(pid: Int): Boolean

        /**
         * Same-UID processes that still reference the verified payload / native host after a
         * stop (bounded; never another app). Used to catch children reparented when the Node
         * root exited, and to re-check the fence before a new generation may start.
         * **null means the scan failed/incomplete and must fail closed** (never "no orphans").
         */
        fun findOrphanedHostProcesses(): List<Int>?

        /**
         * Materializes the tool link farm inside the payload tree this generation actually serves
         * ([payloadRoot]), not necessarily the installed payload: a committed closure is a
         * different root, and a reinstall changes `nativeLibraryDir`, dangling every old link.
         */
        fun prepareRuntimeLinks(executable: DshNodeExecutable, payloadRoot: File)
        fun log(line: String)

        /**
         * Command line of [pid] (`/proc/<pid>/cmdline`), or null when unreadable. Used only to
         * attribute a journaled trial PID before signalling it after owner-process death; a
         * recycled PID, another app or unreadable `/proc` must return null so the trial stays
         * UNKNOWN. The default reads `/proc` directly; there is no Android type in this seam.
         */
        fun processCommandLine(pid: Int): String? = readProcCommandLine(pid)

        /**
         * Delivers SIGTERM=15 / SIGKILL=9 to [pid]; the default refuses, so a composition without
         * this seam can never claim a recovered trial was stopped.
         */
        fun signalProcess(pid: Int, signal: Int): Boolean = false

        /**
         * Loopback MCA model gateway for the next host generation. Called on every start and
         * restart so the base URL and bearer token never outlive one runtime epoch.
         *
         * Null means the gateway itself is unavailable (catalog read failed or the listener could
         * not start): the host fails GATEWAY_UNAVAILABLE and must not launch Node. A successfully
         * read EMPTY catalog is not null and is a valid endpoint: the official frontend must open
         * before a model is loaded, with a truthful empty model listing.
         */
        suspend fun gatewayEndpoint(): com.muyuchat.core.dsh.contract.DshGatewayEndpoint?

        /**
         * Per-generation App-local capability bridge for the official mobile tool packages
         * (D15). The app process starts the authenticated loopback server and hands its port and
         * token to this process over Binder; [DshCapabilityBridgeEndpoint.environment] becomes
         * part of the Node child environment. Null means no mobile tool package is usable this
         * generation: the host still starts (the official frontend and desktop tools must work
         * without it), and the bridge reports its families as NOT_ENABLED instead of faking them.
         */
        suspend fun capabilityEndpoint(): DshCapabilityBridgeEndpoint? = null

        /**
         * Seeds the isolated MCA-owned DSH home for this generation: copies the official
         * profile template out of the verified payload on first run and writes the MCA config
         * overlay (permission preset `workspace-write` + approval `ask`, MCA model provider
         * with `apiKeyEnv=MCA_DSH_GATEWAY_TOKEN`, no reference-only Android tool plugins, no
         * danger-full-access default). Must be idempotent and must not let DSH write into the
         * payload verification tree. Returns false when seeding failed; the host then fails
         * GATEWAY_UNAVAILABLE without launching Node.
         */
        suspend fun seedDshHome(endpoint: com.muyuchat.core.dsh.contract.DshGatewayEndpoint): Boolean

        /**
         * Seeds when no MCA model gateway exists this generation (account-only boot, D02).
         *
         * The official DeepSeek account provider and the official frontend must open even when
         * MCA has no local/cloud model bound. The seeder writes the same managed overlay without
         * an invented MCA provider; the host then starts without MCA_DSH_GATEWAY_TOKEN. The
         * default refuses, so a composition that has not opted in keeps the old gate.
         */
        suspend fun seedDshHomeAccountOnly(): Boolean = false

        /**
         * Identity of the app-process composition that produced these seams (see [DshHostConfig]).
         * A rebuild can keep the same catalog and loopback binding while replacing the inference
         * owner; a new value here forces the reload. Token-free; empty means "not supplied".
         */
        fun hostBuildRevision(): String = ""
    }

    companion object {
        const val MCA_DSH_GATEWAY_TOKEN_ENV = "MCA_DSH_GATEWAY_TOKEN"
    }

    private val machine =
        DshRuntimeStateMachine(
            initialEpoch = epochStore?.lastEpoch() ?: 0L,
            clock = clock
        )
    private val startup = DshStartupRecorder(clock)
    private val _snapshot = MutableStateFlow(machine.snapshot())
    val snapshot: StateFlow<DshRuntimeSnapshot> = _snapshot.asStateFlow()

    private val _startupProgress = MutableStateFlow(startup.progress())

    /**
     * Typed startup boundaries of the current (or last) host generation. Additive observability
     * on top of [snapshot]: `READY` still means the authenticated HTTP surface is stable, never a
     * painted WebView. [DshStartupStage.FIRST_FRAME_RENDERED] is recorded only through
     * [reportFirstFrameRendered] after [DshStartupStage.PLUGINS_LOADED]. After a stop the last
     * verified stage of that epoch is kept until the next start; [snapshot] remains the phase
     * authority.
     */
    val startupProgress: StateFlow<DshStartupProgress> = _startupProgress.asStateFlow()

    private val operationMutex = Mutex()
    private val controlLock = Any()
    private val watchGeneration = AtomicLong(0)

    @Volatile
    private var host: DshNodeHost? = null

    @Volatile
    private var entry: DshWebEntry? = null

    @Volatile
    private var stopRequested = false

    /**
     * Committed closure `payload` tree the production generation must run instead of the installed
     * payload, or null for the installed payload. Set only through [setActiveClosurePayload] after
     * the caller admitted the tree; the value is the generation's payload root, not a token.
     */
    @Volatile
    private var activeClosurePayload: File? = null

    /** The binding that last reached READY; it holds tokens, so stop/exit clears it. */
    @Volatile
    private var lastReadyConfig: DshHostConfig? = null

    @Volatile
    private var lastReadyId: DshBindingId? = null

    private val fence = DshStopFence(
        isAlive = { pid -> deps.isProcessAlive(pid) },
        scan = { runCatching { deps.findOrphanedHostProcesses() }.getOrNull()?.distinct()?.sorted() },
        log = { line -> deps.log(line) }
    )

    private val launcher = DshHostLauncher(
        ioDispatcher = ioDispatcher,
        payload = deps.payload,
        execStrategy = deps.execStrategy,
        readinessGate = deps.readinessGate,
        supportedAbis = deps.supportedAbis,
        packagedAbis = deps.packagedAbis,
        nativeLibraryDir = { deps.nativeLibraryDir() },
        nodeLibraryName = config.nodeLibraryName,
        newNodeHost = { deps.newNodeHost() },
        prepareRuntimeLinks = { executable, payloadRoot -> deps.prepareRuntimeLinks(executable, payloadRoot) },
        nodeExecProbeTimeoutMillis = config.nodeExecProbeTimeoutMillis,
        authLineTimeoutMillis = config.authLineTimeoutMillis,
        log = { line -> deps.log(line) }
    )

    /** Owns the isolated closure-trial generation; every slot/lifecycle decision stays here. */
    private val trialHost = DshTrialGenerationHost(
        config = config,
        launcher = launcher,
        fence = fence,
        ioDispatcher = ioDispatcher,
        clock = clock,
        isProcessAlive = { pid -> deps.isProcessAlive(pid) },
        processCommandLine = { pid -> deps.processCommandLine(pid) },
        signalProcess = { pid, signal -> deps.signalProcess(pid, signal) },
        nativeLibraryDir = { deps.nativeLibraryDir() },
        log = { line -> deps.log(line) },
        bridge = TrialHostBridge()
    )

    /**
     * Background-job survival of this installation: durable ledger, READY census and the
     * prior-generation recovery. One instance for the owner's lifetime; it never starts a host,
     * publishes state or signals a process without the identity proof in [DshJobSurvival].
     */
    private val jobSurvival = jobLedgerDir?.let { dir ->
        DshJobSurvival(
            ledgerDir = dir,
            ioDispatcher = ioDispatcher,
            clock = clock,
            log = { line -> deps.log(line) }
        )
    }

    /** Reconciliation half of this owner: decide, start, roll back, report. Never starts twice. */
    private val reload = DshHostReloadCoordinator(
        log = { line -> deps.log(line) },
        snapshot = { machine.snapshot() },
        appliedBinding = { lastReadyConfig },
        lastConfirmedId = { lastReadyId },
        hostAlive = { host?.isAlive == true },
        stopRequested = { isStopRequested() },
        start = { binding, replacing -> startInternal(binding, replacing) }
    )

    /**
     * Makes the host run [request] against the binding its seams currently describe. An identical
     * binding is a strict no-op; a changed one replaces the generation (APPLIED only after the
     * readiness gate confirmed it, with one rollback when the new binding fails).
     */
    suspend fun ensureApplied(request: DshApplyRequest = DshApplyRequest()): DshApplyResult {
        synchronized(controlLock) { stopRequested = false }
        return operationMutex.withLock { reload.apply(currentConfig(), request) }
    }

    /** Starts the host on demand; a READY generation with the same binding is returned unchanged. */
    suspend fun ensureStarted(): DshRuntimeSnapshot = ensureApplied(DshApplyRequest(source = "ensureStarted")).snapshot

    /**
     * Selects the payload tree the next production generation executes: a committed closure
     * `payload` directory, or null for the installed payload. Callers admit the tree first
     * ([com.muyuchat.core.dsh.runtime.payload.DshClosurePayloadAdmission]); this only records the
     * generation-scoped choice. Switching while a host is READY takes effect on the next start.
     */
    fun setActiveClosurePayload(payloadRoot: File?) {
        activeClosurePayload = payloadRoot?.let { runCatching { it.canonicalFile }.getOrNull() ?: it }
    }

    /** The payload tree the production generation uses: the committed closure or the installed one. */
    private val effectivePayloadDir: File get() = activeClosurePayload ?: config.payloadDir

    private fun effectiveKernelEntry(): File =
        File(effectivePayloadDir, config.kernelEntryRelativePath)

    /**
     * Restarts a failed host with a new epoch; a READY host is returned unchanged. A stop fence is
     * never bypassed, and an identical binding is never a no-op on this path.
     */
    suspend fun restart(): DshRuntimeSnapshot {
        val current = machine.snapshot()
        if (current.phase == DshRuntimePhase.READY && current.origin != null && host?.isAlive == true) {
            return current
        }
        synchronized(controlLock) { stopRequested = false }
        return operationMutex.withLock { startInternal(currentConfig()) }
    }

    /** Stops the host generation and waits for the stop fence. Other MCA modes are untouched. */
    suspend fun stop() {
        synchronized(controlLock) { stopRequested = true }
        watchGeneration.incrementAndGet()
        operationMutex.withLock {
            stopInternal()
        }
    }

    /**
     * The current generation's authenticated entry, in memory only. Returns null when the host
     * is not READY, the generation changed/failed, or the entry was cleared by a crash.
     */
    fun webEntry(): DshWebEntry? {
        val current = snapshot.value
        val candidate = entry
        if (candidate == null) return null
        if (current.phase != DshRuntimePhase.READY || candidate.epoch != current.epoch) return null
        if (host?.isAlive != true) return null
        return candidate
    }

    /**
     * Bounded read model of job processes this installation observed and never resolved.
     *
     * Honest boundary: the official `job_list` registry lives only in the Node host's memory and
     * is not reconstructable after a host death. These entries are process identities from MCA's
     * own ledger, so a console can show what is still running without inventing job records.
     */
    fun jobOrphans(limit: Int = 32): List<DshJobOrphan> = jobSurvival?.orphans(limit) ?: emptyList()

    /**
     * Records the app-process WebView's first visible frame for [epoch].
     *
     * Additive observation only: this never changes [snapshot], the phase, origin, authenticated
     * entry or failure. A report is accepted only while [epoch] is the current READY generation
     * and only after the readiness gate recorded
     * [DshStartupStage.PLUGINS_LOADED]; the startup recorder rejects stale epochs, duplicates and
     * an early first frame on its own, so a page can never be observed as rendered before the
     * verified HTTP/plugin surface. Returns true only when the timeline advanced.
     */
    fun reportFirstFrameRendered(epoch: Long): Boolean {
        val current = machine.snapshot()
        if (current.phase != DshRuntimePhase.READY || current.epoch != epoch) return false
        return recordStartup(epoch, DshStartupStage.FIRST_FRAME_RENDERED)
    }

    /** Final teardown used by the service when its process/last client goes away. */
    suspend fun shutdown() {
        stop()
    }

    // ------------------------------------------------------------------ isolated trial (D24)

    /** Starts the one isolated, non-publishing trial generation; rules live in [DshTrialGenerationHost]. */
    suspend fun startTrial(
        candidateRoot: File,
        candidateFingerprint: String,
        homeRoot: File,
        workspaceRoot: File,
        tmpRoot: File
    ): DshTrialSnapshot = trialHost.startTrial(
        candidateRoot = candidateRoot,
        candidateFingerprint = candidateFingerprint,
        homeRoot = homeRoot,
        workspaceRoot = workspaceRoot,
        tmpRoot = tmpRoot
    )

    /** Releases the trial generation with its real stop proof; see [DshTrialGenerationHost]. */
    suspend fun stopTrial(): DshTrialSnapshot = trialHost.stopTrial()

    /** Fresh trial status from memory, the durable journal and `/proc`; never cached. */
    fun trialStatus(): DshTrialSnapshot = trialHost.trialStatus()

    /** Positive-observation-only trial smoke; see [DshTrialGenerationHost]. */
    fun trialHealth(): DshTrialHealthSnapshot = trialHost.trialHealth()

    // ------------------------------------------------------------------ start

    /** Reads the binding the app process currently advertises; both providers are idempotent. */
    private suspend fun currentConfig(): DshHostConfig =
        DshHostConfig(
            gateway = deps.gatewayEndpoint(),
            capability = deps.capabilityEndpoint(),
            hostBuild = deps.hostBuildRevision()
        )

    private suspend fun startInternal(
        binding: DshHostConfig,
        replacingReadyGeneration: Boolean = false
    ): DshRuntimeSnapshot {
        // An isolated trial generation owns the host slot while it runs. Production must not
        // attach to it, replace it or kill it: only the trial's own stop proof may end it.
        if (trialHost.hasActiveTrial()) {
            deps.log("dsh: production start refused; an isolated trial generation is active")
            return machine.snapshot()
        }
        // A previous generation's census polls its old host pid; it must never outlive that host.
        jobSurvival?.stopCensus()
        if (replacingReadyGeneration) {
            // The old generation is being stopped on purpose: detach its exit watcher so the
            // intentional exit is never published as a PROCESS_EXITED crash.
            watchGeneration.incrementAndGet()
        }
        // 1) settle a previous generation / an unresolved stop fence first.
        val existing = host
        if (existing != null) {
            if (existing.isAlive) {
                val outcome = withContext(ioDispatcher) {
                    existing.stop(config.stopGraceMillis, config.stopKillMillis)
                }
                host = null
                val survivors = fence.survivorsAfter(outcome)
                if (survivors == null || !outcome.stopped || outcome.fenceRequired || survivors.isNotEmpty()) {
                    fence.retain(survivors ?: outcome.survivingPids.ifEmpty { outcome.trackedPids })
                    val detail = outcome.detail +
                        if (survivors == null) " (payload orphan scan unavailable)" else ""
                    deps.log("dsh: previous generation survived its stop; fence retained pids=${fence.pids}")
                    return publish(machine.markStopTimeout(detail))
                }
            } else {
                // A crashed host leaves no usable in-memory fence (and a fresh service instance
                // has none at all); the unconditional start-time orphan scan below is the guard.
                host = null
            }
        }
        settleBeforeStart()?.let { return it }
        if (isStopRequested()) return abortForStop()

        // The previous generation (if any) is already stopped and proved gone above. A reload
        // enters STARTING from READY without publishing STOPPED, which would release the gateway.
        val starting = (if (replacingReadyGeneration) machine.beginReload() else machine.beginStart())
            ?: return machine.snapshot()
        publish(starting)
        beginStartup(starting.epoch)
        epochStore?.record(starting.epoch)
        entry = null
        // A committed closure is admitted again for every generation: an on-disk change after the
        // commit (tamper, a partial copy) must never execute, and the refusal is a typed
        // generation failure rather than a silent fallback to another payload tree.
        activeClosurePayload?.let { closure ->
            when (
                val admitted = DshClosurePayloadAdmission.admit(
                    dataRoot = config.dataRoot,
                    payloadRoot = closure,
                    kernelEntryRelativePath = config.kernelEntryRelativePath
                )
            ) {
                is DshClosurePayloadAdmission.Result.Admitted -> Unit
                is DshClosurePayloadAdmission.Result.Refused -> {
                    deps.log("dsh: closure payload refused: ${admitted.detail}")
                    return publish(
                        machine.fail(
                            code = DshFailureCode.PAYLOAD_INTEGRITY,
                            message = "the committed closure payload failed its integrity admission",
                            retryable = false,
                            diagnostics = admitted.detail
                        )
                    )
                }
            }
        }
        // Settle job processes of older generations before this one may spawn new ones. The
        // pass is bounded and additive: its failure never blocks the launch.
        jobSurvival?.recoverOrphans(starting.epoch)

        return when (val outcome = launcher.launch(
            DshHostLauncher.Request(
                profile = productionProfile(),
                environment = productionEnvironment(binding, starting.epoch),
                seed = { seedProductionHome(binding) },
                onHostCreated = { created -> host = created },
                onStage = { stage -> recordStartup(starting.epoch, stage) },
                shouldAbort = { isStopRequested() }
            )
        )) {
            is DshLaunchOutcome.Launched -> {
                val ready = machine.markReady(outcome.ready.origin)
                entry = DshWebEntry(epoch = ready.epoch, authenticatedUrl = outcome.entry.authenticatedUrl)
                // The readiness gate proved this generation is serving with exactly this binding;
                // only now is it the rollback/unchanged reference.
                lastReadyConfig = binding
                lastReadyId = binding.id
                publish(ready)
                startExitWatcher(outcome.host, ready.epoch)
                // The census observes the READY generation's descendants only; it never signals
                // and is stopped with this generation (stop, failure cleanup or exit watcher).
                jobSurvival?.startCensus(outcome.host.processId, ready.epoch)
                deps.log(
                    "dsh: READY epoch=${ready.epoch} origin=${ready.origin} indexBytes=${outcome.ready.indexBytes} " +
                        "plugin=${outcome.ready.pluginAsset} stage=${startup.progress().stage?.name ?: "NONE"} " +
                        "binding=${binding.id} (${binding.summary})"
                )
                ready
            }

            is DshLaunchOutcome.Failed -> fail(
                outcome.code,
                outcome.message,
                outcome.retryable,
                outcome.diagnostics,
                leftHost = outcome.leftHost
            )

            DshLaunchOutcome.AbortedForStop -> abortForStop()
        }
    }

    /** The publishing generation: the active payload tree, READY entry and exit watcher. */
    private fun productionProfile(): DshLaunchProfile {
        val closure = activeClosurePayload
        return DshLaunchProfile(
            logPrefix = "dsh: ",
            hostNoun = "DSH host",
            roots = DshRoots(config.homeDir, config.workspaceDir, config.tmpDir, effectivePayloadDir),
            kernelEntry = effectiveKernelEntry(),
            bindHost = config.host,
            port = config.port,
            publishReady = true,
            // A committed closure was verified and trialled by the maintenance owner; the APK
            // installer would reinstall the packaged payload over it, so this generation only
            // executes the admitted tree. Links are still prepared there: the closure tree is a
            // different root and a reinstall invalidates every link to the old nativeLibraryDir.
            verifyPayload = closure == null,
            // Even while a closure serves the host, keep the packaged payload installed so an APK
            // update that ships a new payload still has a source for the next upgrade transaction.
            installPackagedPayload = true,
            prepareCommandLinks = true,
            watchExit = true
        )
    }

    /**
     * Child environment of the production generation: isolated DSH roots, the per-generation MCA
     * bearer token (environment only, never a file/argv/log) and the capability bridge coordinates
     * of the same generation. Both are absent in account-only mode.
     */
    private fun productionEnvironment(binding: DshHostConfig, generation: Long): Map<String, String> {
        val payloadDir = effectivePayloadDir
        val environment = DshNodeCommand.environment(
            homeDir = config.homeDir,
            configDir = config.configDir,
            workspaceDir = config.workspaceDir,
            tmpDir = config.tmpDir,
            payloadDir = payloadDir,
            runtimeLibraryDir = File(payloadDir, config.runtimeLibraryRelativePath),
            payloadBinDir = File(payloadDir, config.payloadBinRelativePath),
            // Every child of this generation inherits this token; it is the ownership fact the
            // next generation re-verifies before it signals a leftover job process.
            generation = generation
        ).toMutableMap()
        val gateway = binding.gateway
        if (gateway != null) {
            // The bearer token reaches the DSH provider only through the child environment; it is
            // never written into the config file, argv, diagnostics or logs. Account-only mode has
            // no MCA provider, so the variable is simply absent.
            environment[MCA_DSH_GATEWAY_TOKEN_ENV] = gateway.bearerToken
        }
        // D15: the official mobile tool packages read APP_NOTIFY_PORT/APP_LOCAL_TOKEN/... from the
        // environment. Absent bridge coordinates mean those packages stay unregistered; the
        // official frontend and desktop tools must still start.
        val bridge = binding.capability
        if (bridge != null) {
            deps.log(
                "dsh: capability bridge generation=${bridge.generation} " +
                    "appPort=${bridge.appLocalPort} a11yPort=${bridge.accessibilityPort ?: 0} " +
                    "root=${bridge.rootAvailable} shizuku=${bridge.shizukuAvailable}"
            )
            environment.putAll(bridge.environment())
        } else {
            deps.log("dsh: capability bridge absent for this generation")
        }
        return environment
    }

    /**
     * Seeds the production home: the MCA gateway overlay when this generation has one, otherwise
     * the account-only overlay (D02). A refused seed is a typed launch refusal, never a silent
     * start without a provider.
     */
    private suspend fun seedProductionHome(binding: DshHostConfig): DshSeedAttempt {
        val gateway = binding.gateway
        val seeded = try {
            if (gateway != null) deps.seedDshHome(gateway) else deps.seedDshHomeAccountOnly()
        } catch (t: Throwable) {
            deps.log("dsh: DSH home seeding failed: ${t.javaClass.simpleName}")
            false
        }
        if (!seeded) {
            return DshSeedAttempt.Refused(
                message = if (gateway == null) {
                    "the MCA model gateway is absent and the account-only DSH home could not be seeded"
                } else {
                    "the MCA model gateway configuration could not be seeded into the DSH home"
                },
                diagnostics = gateway?.let { "catalogRevision=${it.catalogRevision}" }
            )
        }
        if (gateway != null) {
            deps.log(
                "dsh: model gateway seeded baseUrl=${gateway.baseUrl} catalogRevision=${gateway.catalogRevision}"
            )
        } else {
            deps.log("dsh: account-only home seeded (no MCA model gateway for this generation)")
        }
        return DshSeedAttempt.Seeded
    }

    // ------------------------------------------------------------------ stop

    private suspend fun stopInternal(): DshRuntimeSnapshot {
        jobSurvival?.stopCensus()
        publish(machine.beginStop())
        val active = host
        if (active == null) {
            entry = null
            // Node is a child of the :dsh service and can be reparented after the service dies.
            // Even a fresh owner with no in-memory fence must prove "no payload orphans" before
            // publishing STOPPED; an unavailable scan is not proof either.
            val scan = fence.scanOrNull()
            val survivors = (fence.aliveFenced() + (scan ?: emptyList())).distinct().sorted()
            if (scan != null && survivors.isEmpty()) {
                fence.clear()
                lastReadyConfig = null
                trialHost.releaseAfterProductionStop()
                deps.log("dsh: stop proved no host and no payload orphans")
                return publish(
                    if (machine.isStopFenceRaised()) machine.confirmExitedAfterFence() else machine.markStopped()
                )
            }
            fence.retain(survivors.ifEmpty { fence.pids })
            deps.log("dsh: stop could not prove the tree gone; fence retained pids=${fence.pids}")
            return publish(
                machine.markStopTimeout(
                    if (scan == null) {
                        "stop verification failed: payload orphan scan unavailable"
                    } else {
                        "stop verification found survivors pids=$survivors"
                    }
                )
            )
        }
        val outcome = withContext(ioDispatcher) {
            active.stop(config.stopGraceMillis, config.stopKillMillis)
        }
        entry = null
        host = null
        val survivors = fence.survivorsAfter(outcome)
        if (survivors == null) {
            fence.retain(outcome.survivingPids.ifEmpty {
                outcome.trackedPids.ifEmpty { listOfNotNull(active.processId) }
            })
            deps.log("dsh: stop cannot be verified; payload orphan scan unavailable")
            return publish(machine.markStopTimeout("${outcome.detail} (payload orphan scan unavailable)"))
        }
        if (outcome.stopped && !outcome.fenceRequired && survivors.isEmpty()) {
            fence.clear()
            lastReadyConfig = null
            // A production stop releases the single host slot, including an isolated trial
            // generation: the app-side trial journal then recovers from its own record.
            trialHost.releaseAfterProductionStop()
            deps.log("dsh: stopped (${outcome.detail})")
            return publish(machine.markStopped())
        }
        fence.retain(survivors.ifEmpty {
            outcome.trackedPids.ifEmpty { listOfNotNull(active.processId) }
        })
        deps.log("dsh: stop fence raised (${outcome.detail}) pids=${fence.pids}")
        return publish(machine.markStopTimeout(outcome.detail))
    }

    private suspend fun abortForStop(): DshRuntimeSnapshot {
        jobSurvival?.stopCensus()
        val active = host
        if (active != null) {
            val outcome = withContext(ioDispatcher) {
                active.stop(config.stopGraceMillis, config.stopKillMillis)
            }
            host = null
            entry = null
            val survivors = fence.survivorsAfter(outcome)
            if (survivors == null) {
                fence.retain(outcome.survivingPids.ifEmpty {
                    outcome.trackedPids.ifEmpty { listOfNotNull(active.processId) }
                })
                deps.log("dsh: stop during start cannot be verified; payload orphan scan unavailable")
                return publish(machine.markStopTimeout("${outcome.detail} (payload orphan scan unavailable)"))
            }
            if (!outcome.stopped || outcome.fenceRequired || survivors.isNotEmpty()) {
                fence.retain(survivors.ifEmpty {
                    outcome.trackedPids.ifEmpty { listOfNotNull(active.processId) }
                })
                deps.log("dsh: stop during start left survivors; stop fence raised pids=${fence.pids}")
                return publish(machine.markStopTimeout(outcome.detail))
            }
            fence.clear()
            lastReadyConfig = null
            return publish(machine.markStopped())
        }
        entry = null
        lastReadyConfig = null
        return publish(machine.markStopped())
    }

    // ------------------------------------------------------------------ helpers

    private fun isStopRequested(): Boolean = synchronized(controlLock) { stopRequested }

    /**
     * Runs the same pre-start fence/orphan verification for every generation. Returns the published
     * refusal snapshot, or null when the start may proceed.
     */
    private fun settleBeforeStart(): DshRuntimeSnapshot? =
        when (val decision = fence.checkBeforeStart(machine.isStopFenceRaised())) {
            DshStopFence.Decision.Clear -> null
            DshStopFence.Decision.ClearedFence -> {
                publish(machine.confirmExitedAfterFence())
                deps.log("dsh: previous stop fence cleared after the tree exited")
                null
            }

            is DshStopFence.Decision.Blocked -> publish(machine.markStopTimeout(decision.reason))
        }

    private fun publish(next: DshRuntimeSnapshot): DshRuntimeSnapshot {
        _snapshot.value = next
        return next
    }

    private fun beginStartup(epoch: Long) {
        publishStartup(startup.begin(epoch))
    }

    /** Records a verified boundary for [epoch] and logs the transition once per generation. */
    private fun recordStartup(epoch: Long, stage: DshStartupStage): Boolean {
        if (!startup.record(epoch, stage)) return false
        publishStartup(startup.progress())
        deps.log("dsh: startup stage=${stage.name} epoch=$epoch")
        return true
    }

    private fun publishStartup(next: DshStartupProgress) {
        _startupProgress.value = next
    }

    private suspend fun fail(
        code: DshFailureCode,
        message: String,
        retryable: Boolean,
        diagnostics: String? = null,
        leftHost: DshNodeHost? = null
    ): DshRuntimeSnapshot {
        jobSurvival?.stopCensus()
        entry = null
        val active = leftHost ?: host
        if (active != null) {
            val outcome = withContext(ioDispatcher) {
                active.stop(config.stopGraceMillis, config.stopKillMillis)
            }
            host = null
            val survivors = fence.survivorsAfter(outcome)
            if (survivors == null) {
                fence.retain(outcome.survivingPids.ifEmpty {
                    outcome.trackedPids.ifEmpty { listOfNotNull(active.processId) }
                })
                deps.log("dsh: failure cleanup cannot be verified; payload orphan scan unavailable")
                return publish(machine.markStopTimeout("${outcome.detail} (payload orphan scan unavailable)"))
            }
            if (!outcome.stopped || outcome.fenceRequired || survivors.isNotEmpty()) {
                fence.retain(survivors.ifEmpty {
                    outcome.trackedPids.ifEmpty { listOfNotNull(active.processId) }
                })
                deps.log("dsh: failure cleanup could not settle the tree; stop fence raised pids=${fence.pids}")
                return publish(machine.markStopTimeout(outcome.detail))
            }
        }
        val safeDiagnostics = diagnostics?.let { DshLogRedactor.redact(it, config.maxLogLineChars) }
        deps.log(
            "dsh: FAILED ${code.name}: $message${safeDiagnostics?.let { " | $it" } ?: ""} " +
                "startupStage=${startup.progress().stage?.name ?: "NONE"}"
        )
        return publish(machine.fail(code, message, retryable, safeDiagnostics))
    }

    /**
     * Watches a READY generation for an unexpected Node exit. An intentional stop changes the
     * phase/epoch first, so only a crash publishes PROCESS_EXITED.
     */
    private fun startExitWatcher(watched: DshNodeHost, epoch: Long) {
        val generation = watchGeneration.incrementAndGet()
        Thread {
            while (watchGeneration.get() == generation) {
                if (watched.exitCode != null) break
                try {
                    Thread.sleep(300)
                } catch (t: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
            if (watchGeneration.get() != generation) return@Thread
            synchronized(controlLock) {
                // A host this owner already detached (stop/reload/cleanup) is not live any more.
                if (host !== watched) return@Thread
                val current = machine.snapshot()
                if (current.phase != DshRuntimePhase.READY || current.epoch != epoch) return@Thread
                jobSurvival?.stopCensus()
                entry = null
                lastReadyConfig = null
                val code = watched.exitCode
                deps.log("dsh: host exited unexpectedly (code=$code)")
                publish(
                    machine.fail(
                        DshFailureCode.PROCESS_EXITED,
                        "the DSH host process exited unexpectedly",
                        retryable = true,
                        diagnostics = watched.tail(20)
                    )
                )
            }
        }.apply {
            name = "dsh-exit-watch-$epoch"
            isDaemon = true
            start()
        }
    }

    /**
     * The owner side of [DshTrialGenerationHost.Bridge]: only state this owner may write. The trial
     * never receives the state machine or the production profile, so it cannot publish READY or
     * start a second generation.
     */
    private inner class TrialHostBridge : DshTrialGenerationHost.Bridge {
        override suspend fun <T> withOperationLock(block: suspend () -> T): T = operationMutex.withLock { block() }

        override fun stopRequested(): Boolean = isStopRequested()

        override fun admitTrialGeneration(): DshTrialAdmission {
            val phase = machine.snapshot().phase
            if (host?.isAlive == true ||
                (phase != DshRuntimePhase.STOPPED && phase != DshRuntimePhase.FAILED)
            ) {
                return DshTrialAdmission.Refused("the production host is not confirmed stopped")
            }
            host = null
            settleBeforeStart()?.let {
                return DshTrialAdmission.Unknown("the host slot could not be settled for a trial")
            }
            if (isStopRequested()) return DshTrialAdmission.Refused("a stop was requested before the trial could start")
            val starting = machine.beginStart()
                ?: return DshTrialAdmission.Refused("the runtime refused a new generation")
            publish(starting)
            beginStartup(starting.epoch)
            entry = null
            return DshTrialAdmission.Admitted(starting.epoch)
        }

        override fun attachTrialHost(host: DshNodeHost) { this@DshRuntimeOwner.host = host }

        override fun detachTrialHost(expected: DshNodeHost) {
            entry = null
            if (host === expected) host = null
        }

        override fun recordStartup(epoch: Long, stage: DshStartupStage): Boolean =
            this@DshRuntimeOwner.recordStartup(epoch, stage)

        override fun publishTrialStopped(): DshRuntimeSnapshot = publish(machine.markStopped())

        override fun publishTrialStopTimeout(detail: String): DshRuntimeSnapshot = publish(machine.markStopTimeout(detail))

        override suspend fun failTrialLaunch(failure: DshLaunchOutcome.Failed): DshRuntimeSnapshot =
            fail(failure.code, failure.message, failure.retryable, failure.diagnostics, failure.leftHost)

        override suspend fun abortTrialForStop(): DshRuntimeSnapshot = abortForStop()
    }
}
