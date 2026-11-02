package com.muyuchat.core.dsh.maintenance

import java.io.File

/**
 * Wiring entry point for `:core:dsh-maintenance` (D21-D24).
 *
 * The app composition creates exactly one owner next to the single DSH runtime owner and hands it
 * narrow ports it does not own:
 *
 * ```kotlin
 * val maintenance = DshMaintenanceOwner(
 *     dataRoot = File(context.filesDir, "dsh"),
 *     admission = platformAdmissionPort,      // DshCapabilityMaintenanceAdmissionContract adapter
 *     probe = officialReaderProbe,            // real session reader / host health, read-only
 *     host = runtimeHostLifecyclePort,        // serial managed-host start/stop
 *     supportedAbis = Build.SUPPORTED_ABIS.toSet(),
 *     sessionCodec = officialSessionCodec,    // official reader/writer or zstd JNI
 *     selfCheckPort = realSelfCheckProbes,
 *     managedFiles = buildManagedFileManifest()
 * )
 * // on app start, before the DSH host is started:
 * maintenance.recoverOnStart()
 * ```
 *
 * Startup order is mandatory: journal sweep → safe-mode enter/exit resume → upgrade resume. The
 * upgrade resume may ensure the committed production host is running, so it must come after the
 * selector has been read and before the console shows the DSH page.
 */
data class DshMaintenanceStartupReport(
    val journalRecoveries: List<DshMaintenanceRecoveryResult>,
    val safeMode: DshSafeModeResult?,
    val upgrade: DshUpgradeResult?,
    val decision: DshSafeModeDecision,
    val failure: DshMaintenanceFailure? = null,
    /** Interrupted isolated trial (D24) released or requiring the rescue surface. */
    val trialRecovery: DshTrialRecovery = DshTrialRecovery.None,
    /**
     * Exceptions raised by individual recovery phases. A phase that threw leaves its state
     * unproven, so it is recorded here and always produces a blocking failure.
     */
    val phaseErrors: List<DshMaintenanceFailure> = emptyList()
)

/**
 * Typed verdict of the startup sweep. Only [Proceed] may be followed by a host start.
 *
 * [Blocked] is not a silent log line: it carries the exact failure the workspace must show, and
 * the caller must not start any host generation. A later start attempt re-runs the sweep, which is
 * the retry path (a transient busy/IO obstruction can clear without a process restart).
 */
sealed interface DshStartupOutcome {
    val report: DshMaintenanceStartupReport

    data class Proceed(override val report: DshMaintenanceStartupReport) : DshStartupOutcome

    data class Blocked(
        override val report: DshMaintenanceStartupReport,
        val failure: DshMaintenanceFailure
    ) : DshStartupOutcome
}

class DshMaintenanceOwner(
    dataRoot: File,
    admission: DshMaintenanceAdmissionPort,
    probe: DshMaintenanceRecoveryProbe,
    private val host: DshHostLifecyclePort,
    supportedAbis: Set<String>,
    sessionCodec: DshSessionCodec? = null,
    private val selfCheckPort: DshSelfCheckPort? = null,
    private val managedFiles: List<DshManagedFile> = emptyList(),
    clock: () -> Long = System::currentTimeMillis,
    /**
     * Deferred codec construction. The real codec proves itself by decoding a session header; that
     * proof needs a process start, so it must not run during owner construction on the main thread.
     */
    private val sessionCodecProvider: () -> DshSessionCodec? = { sessionCodec },
    /** Staged closure candidate (D24). The default source is "nothing staged". */
    private val candidateLookup: () -> DshClosureCandidateLookup = { DshClosureCandidateLookup.None },
    /** Installed APK payload identity, persisted with the closure selector for audit. */
    installedIdentity: () -> DshInstalledPayloadIdentity = { DshInstalledPayloadIdentity() }
) {
    private val dataRoot: File = dataRoot
    private val clock: () -> Long = clock

    val engine: DshMaintenanceEngine = DshMaintenanceEngine(
        dataRoot = dataRoot,
        admission = admission,
        probe = probe,
        clock = clock
    )
    val layout: DshHomeLayout = DshHomeLayout(File(dataRoot, "home"))
    val sessions: DshSessionInventory = DshSessionInventory(layout)
    val attachments: DshAttachmentObjectVerifier = DshAttachmentObjectVerifier(layout.attachmentsRoot)

    /** Built on first real use; the codec proves itself by decoding a header before it appears here. */
    val repairer: DshSessionRepairer? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        sessionCodecProvider()?.let { codec -> DshSessionRepairer(engine, layout, codec, attachments) }
    }
    val recycler: DshSessionRecycler = DshSessionRecycler(engine, layout, clock)

    private val startHistory = DshStartHistoryStore(File(dataRoot, "maintenance/start-history.json"))
    private val safeModeState = DshSafeModeStateStore(File(dataRoot, "maintenance/safe-mode.json"))
    val safeMode: DshSafeModeController = DshSafeModeController(
        engine = engine,
        homeDir = layout.homeDir,
        historyStore = startHistory,
        stateStore = safeModeState,
        clock = clock
    )
    val backups: DshBackupService = DshBackupService(engine, clock = clock)
    val patches: DshConsolePatchManager = DshConsolePatchManager(engine, layout.homeDir)
    val themes: DshConsoleThemeManager = DshConsoleThemeManager(engine)
    val upgrade: DshClosureUpgradeManager = DshClosureUpgradeManager(
        engine = engine,
        host = host,
        supportedAbis = supportedAbis,
        clock = clock,
        installedIdentity = installedIdentity
    )
    val diagnostics: DshDiagnosticsExporter = DshDiagnosticsExporter(engine, clock)
    private val selfCheckPolicy: DshSelfCheckPolicy? =
        if (selfCheckPort != null) {
            DshSelfCheckPolicy(
                managedFiles = managedFiles,
                // Payload-scoped managed files resolve against the verified payload tree, never
                // against the user home: mixing the roots would report permanent false mismatches.
                payloadDir = java.io.File(dataRoot, "payload")
            )
        } else {
            null
        }

    /** True when this build can check and repair its own declared managed files. */
    fun managedRepairAvailable(): Boolean = selfCheckPolicy != null

    /** Number of declared managed files; the console only advertises repair when this is non-zero. */
    fun managedFileCount(): Int = selfCheckPolicy?.managedFileCount ?: 0

    /** Default backup sources: user home plus the tool workspace (missing roots are skipped). */
    fun defaultBackupSources(): List<DshBackupSource> = listOf(
        DshBackupSource(layout.homeDir, "home"),
        DshBackupSource(File(dataRoot, "workspace"), "workspace")
    )

    /**
     * Startup recovery. Must run on the single startup thread before the DSH host starts. It never
     * starts a second host and never deletes user data: the journal decides resume/abort per
     * transaction, safe mode finishes its stash move in whichever direction was in flight, and the
     * upgrade either health-checks the committed selector or restores the previous one.
     *
     * Every phase is wrapped: an exception leaves that phase's state unproven, is recorded in
     * [DshMaintenanceStartupReport.phaseErrors] and makes the whole sweep [DshStartupOutcome.Blocked].
     * A failed journal sweep stops the sweep before safe-mode/upgrade resume, because those actions
     * move files and must not run on top of an unknown transaction state.
     */
    fun recoverOnStart(): DshStartupOutcome {
        val requester = DshMaintenanceRequester(
            owner = "startup",
            operationId = "startup-${clock()}",
            reason = "recover interrupted maintenance before the host starts"
        )
        val evidence = DshQuiescenceEvidence.hostStopped("startup sweep before the managed host starts")
        val phaseErrors = ArrayList<DshMaintenanceFailure>()
        // The trial is released before the journal sweep: the upgrade resume may then start the
        // production host from the committed selector, and no second host may exist at that point.
        val trialRecovery = runCatching { host.recoverInterruptedTrial() }
            .getOrElse { failure ->
                phaseErrors += phaseError(
                    "trial",
                    "an interrupted closure trial could not be released; the host slot is unproven",
                    failure
                )
                DshTrialRecovery.RequiresAttention("trial recovery threw " + failure.javaClass.simpleName)
            }
        val journalOutcome = runCatching { engine.recoverOnStart() }
        val journalSweepFailure = journalOutcome.exceptionOrNull()
        val journal: List<DshMaintenanceRecoveryResult> = journalOutcome.getOrDefault(emptyList())
        if (journalSweepFailure != null) {
            phaseErrors += phaseError(
                "journal",
                "the maintenance journal sweep failed; every pending transaction is unproven",
                journalSweepFailure
            )
            // Safe-mode/upgrade resume move files. Running them over an unknown journal could
            // finish the wrong direction, so the sweep stops at the first unproven phase.
            val decision = runCatching { safeMode.decide(clock()) }
                .getOrElse { DshSafeModeDecision.RECOVERY_REQUIRED }
            val report = DshMaintenanceStartupReport(
                journalRecoveries = emptyList(),
                safeMode = null,
                upgrade = null,
                decision = decision,
                failure = phaseErrors.first(),
                trialRecovery = trialRecovery,
                phaseErrors = phaseErrors.toList()
            )
            return DshStartupOutcome.Blocked(report, phaseErrors.first())
        }
        val safeModeResult = runCatching { safeMode.resume(requester, evidence) }
            .getOrElse { failure ->
                phaseErrors += phaseError("safe-mode", "safe-mode resume failed; DSH may not start", failure)
                null
            }
        val upgradeResult = runCatching { upgrade.resume(requester, evidence) }
            .getOrElse { failure ->
                phaseErrors += phaseError(
                    "upgrade",
                    "the closure upgrade resume failed; the active closure is unproven",
                    failure
                )
                null
            }
        val decision = runCatching { safeMode.decide(clock()) }
            .getOrElse { failure ->
                phaseErrors += phaseError("safe-mode-decision", "the safe-mode decision could not be computed", failure)
                DshSafeModeDecision.RECOVERY_REQUIRED
            }
        val failure = when {
            phaseErrors.isNotEmpty() -> phaseErrors.first()
            trialRecovery is DshTrialRecovery.RequiresAttention -> DshMaintenanceFailure(
                DshMaintenanceError.RECOVERY_REQUIRED,
                "an interrupted closure trial could not be released"
            )

            safeModeResult is DshSafeModeResult.Rejected -> safeModeResult.failure
            upgradeResult is DshUpgradeResult.RecoveryRequired -> upgradeResult.failure
            journal.any { it is DshMaintenanceRecoveryResult.RequiresAttention } -> DshMaintenanceFailure(
                DshMaintenanceError.RECOVERY_REQUIRED,
                "at least one maintenance transaction requires the rescue surface"
            )

            decision == DshSafeModeDecision.RECOVERY_REQUIRED -> DshMaintenanceFailure(
                DshMaintenanceError.RECOVERY_REQUIRED,
                "safe mode exhausted its recovery attempts; automatic start is disabled"
            )

            else -> null
        }
        val report = DshMaintenanceStartupReport(
            journalRecoveries = journal,
            safeMode = safeModeResult,
            upgrade = upgradeResult,
            decision = decision,
            failure = failure,
            trialRecovery = trialRecovery,
            phaseErrors = phaseErrors.toList()
        )
        return if (failure == null) {
            DshStartupOutcome.Proceed(report)
        } else {
            DshStartupOutcome.Blocked(report, failure)
        }
    }

    /**
     * Post-start confirmation for an upgrade interrupted between the selector switch and its
     * health proof. Runs only while the runtime serves the committed selector; it never starts a
     * host, so it is safe to call from the runtime's READY observation.
     */
    fun confirmStartedUpgrade(): DshUpgradeResult {
        val requester = DshMaintenanceRequester(
            owner = "post-start",
            operationId = "confirm-${clock()}",
            reason = "confirm the committed closure after the managed host reached READY"
        )
        return runCatching {
            upgrade.confirmAfterStart(
                requester,
                DshQuiescenceEvidence.hostStopped("post-start confirmation of the committed selector")
            )
        }.getOrElse { failure ->
            DshUpgradeResult.NoChange("post-start confirmation failed: " + failure.javaClass.simpleName)
        }
    }

    /** Records one thrown recovery phase as a blocking failure without leaking its payload. */
    private fun phaseError(phase: String, message: String, failure: Throwable): DshMaintenanceFailure =
        DshMaintenanceFailure(
            DshMaintenanceError.RECOVERY_REQUIRED,
            message,
            retryable = true,
            detail = "$phase=${failure.javaClass.simpleName}"
        )

    /** Batch session counts through the proven official reader; null entries mean unknown. */
    fun sessionStats(files: List<File>): List<DshSessionStats?> = sessionCodecProvider()?.stats(files) ?: emptyList()

    /** Positive header proof used by the self-check and the read-only production health. */
    fun sessionHeaderProof(file: File): DshSessionHeaderProof =
        sessionCodecProvider()?.inspectHeader(file) ?: DshSessionHeaderProof.CODEC_UNAVAILABLE

    /** True only when a real official session codec is available; never inferred from a directory. */
    fun sessionCodecAvailable(): Boolean = sessionCodecProvider() != null

    fun findClosureCandidate(): DshClosureCandidateLookup = candidateLookup()

    /** Runs the injected real probes and evaluates them against the managed-file inventory. */
    fun selfCheck(): DshSelfCheckOutcome? {
        val port = selfCheckPort ?: return null
        val policy = selfCheckPolicy ?: return null
        val report = DshSelfCheckReport(port.run(), clock())
        return policy.evaluate(report, layout.homeDir)
    }

    /**
     * Repairs only the managed files whose expected hash is known and whose source hash matches.
     * Bounded by the caller through [DshSafeModeController.canAutoRepair]/[consumedAutoRepair].
     */
    fun repairManagedFiles(
        mismatched: List<String>,
        managedSourceRoot: File?,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshMaintenanceOutcome? {
        val policy = selfCheckPolicy ?: return null
        val plan = policy.planRepairs(layout.homeDir, managedSourceRoot, mismatched)
        return policy.applyRepairs(engine, plan, requester, quiescence)
    }
}
