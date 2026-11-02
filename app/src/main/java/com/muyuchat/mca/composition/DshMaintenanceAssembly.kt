package com.muyuchat.mca.composition

import android.os.Build
import android.util.Log
import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshRuntimeClient
import com.muyuchat.core.dsh.contract.DshRuntimeFailure
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import com.muyuchat.core.dsh.contract.DshStartupAdmission
import com.muyuchat.core.dsh.maintenance.DshClosureCandidateLookup
import com.muyuchat.core.dsh.maintenance.DshClosureCandidateSource
import com.muyuchat.core.dsh.maintenance.DshClosureTrialHost
import com.muyuchat.core.dsh.maintenance.DshPackagedCandidateProvisioner
import com.muyuchat.core.dsh.maintenance.DshPackagedPayloadIdentity
import com.muyuchat.core.dsh.maintenance.DshProvisionResult
import com.muyuchat.core.dsh.maintenance.DshHomeLayout
import com.muyuchat.core.dsh.maintenance.DshHostHealth
import com.muyuchat.core.dsh.maintenance.DshHostLifecyclePort
import com.muyuchat.core.dsh.maintenance.DshHostState
import com.muyuchat.core.dsh.maintenance.DshHostStatus
import com.muyuchat.core.dsh.maintenance.DshMaintenanceAdmissionOutcome
import com.muyuchat.core.dsh.maintenance.DshMaintenanceAdmissionPort
import com.muyuchat.core.dsh.maintenance.DshMaintenanceAdmissionRequest
import com.muyuchat.core.dsh.maintenance.DshMaintenanceError
import com.muyuchat.core.dsh.maintenance.DshMaintenanceKind
import com.muyuchat.core.dsh.maintenance.DshMaintenanceLease
import com.muyuchat.core.dsh.maintenance.DshMaintenanceOwner
import com.muyuchat.core.dsh.maintenance.DshMaintenanceProbeResult
import com.muyuchat.core.dsh.maintenance.DshMaintenanceRecoveryProbe
import com.muyuchat.core.dsh.maintenance.DshMaintenanceRequester
import com.muyuchat.core.dsh.maintenance.DshMaintenanceStartupReport
import com.muyuchat.core.dsh.maintenance.DshQuiescenceEvidence
import com.muyuchat.core.dsh.maintenance.DshStartupOutcome
import com.muyuchat.mca.dsh.DshPayloadCriticalFiles
import com.muyuchat.mca.dsh.DshPayloadRepairSource
import com.muyuchat.core.dsh.maintenance.DshSessionCodec
import com.muyuchat.core.dsh.maintenance.DshSessionHeaderProof
import com.muyuchat.core.dsh.maintenance.DshSessionInventory
import com.muyuchat.core.dsh.maintenance.DshTrialLaunchPort
import com.muyuchat.core.dsh.maintenance.DshTrialRecovery
import com.muyuchat.core.dsh.maintenance.DshUpgradeResult
import com.muyuchat.core.dsh.platform.DshCapabilityMaintenanceAdmissionGate
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceAdmission
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceAdmissionSnapshot
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceLeaseKind
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * App composition for the DSH maintenance owner (D21-D24).
 *
 * It adapts the app-owned platform admission gate and the single DSH runtime client to the
 * maintenance module's narrow ports. The maintenance module stays the only writer of its journal,
 * quarantine and restore points; nothing here re-implements a transaction.
 *
 * Two linked capabilities are connected only after they prove themselves:
 * - the official session codec is returned by [sessionCodecProvider] only after the bundled Node
 *   helper decoded a real session header from the managed home; otherwise the owner keeps its
 *   honest null and repair stays unavailable;
 * - the closure trial host is a real [DshHostLifecyclePort] around the production runtime. Its
 *   isolated trial generation is delegated to the injected [trialLaunchPort], which the app
 *   composition wires to the DSH mode's one runtime client viewed through the typed
 *   `DshTrialHostClient` contract. A runtime module that does not implement that contract yields
 *   the exact "not connected" typed failure, so `startTrial` journals a FAILED attempt instead of
 *   pretending a trial ran; no app-process host is ever created. The default null port is kept for
 *   callers that do not wire the adapter at all.
 */
internal class DshMaintenanceAssembly(
    filesDir: File,
    private val runtime: DshRuntimeClient,
    private val gatewayReady: () -> Boolean,
    private val bridgeReady: () -> Boolean = { false },
    private val nativeLibrary: () -> File? = { null },
    private val expectedArchiveSha256: () -> String? = { null },
    private val expectedPayloadVersion: () -> String? = { null },
    private val expectedFileCount: () -> Long? = { null },
    private val installedAbi: () -> String? = { null },
    /** Packaged Node executable used by the session codec helper (nativeLibraryDir). */
    private val nodeExecutable: () -> File? = nativeLibrary,
    /** Text of `dsh-maintenance/session-v4-codec.mjs`; null keeps the codec honestly unavailable. */
    private val codecHelperSource: () -> String? = { null },
    /** Runtime-owner seam for the isolated trial generation (D24). Null until the owner patch lands. */
    private val trialLaunchPort: () -> DshTrialLaunchPort? = { null },
    /**
     * Text of the packaged `dsh/MANIFEST.json`. Its `criticalFiles` are the only managed files this
     * build may repair, so a missing manifest yields an empty (honest) repair inventory.
     */
    private val payloadManifestJson: () -> String? = { null },
    /** Opens the packaged payload archive; the verified source of a precise managed repair. */
    private val payloadArchiveOpen: () -> java.io.InputStream? = { null },
    /**
     * Admission gate shared with the capability bridge. This owner is the only barrier holder;
     * every capability request holds a MUTATION lease on the same instance, so a maintenance
     * barrier and a live tool call can never overlap.
     */
    private val gate: DshCapabilityMaintenanceAdmissionGate = DshCapabilityMaintenanceAdmissionGate()
) : DshManagedRepairPort {

/**
 * True when a live, same-UID process still references [root] on its command line. Defaults to
 * `true` (conservative) whenever `/proc` cannot be enumerated.
 */
private fun candidateRootHasLiveProcess(root: File): Boolean {
    val needle = runCatching { root.canonicalPath }.getOrNull() ?: return true
    val entries = File("/proc").listFiles() ?: return true
    for (entry in entries) {
        if (entry.name.isEmpty() || !entry.name.all { it.isDigit() }) continue
        val cmdline = runCatching { File(entry, "cmdline").readBytes() }.getOrNull() ?: continue
        if (cmdline.isEmpty()) continue
        if (String(cmdline, Charsets.UTF_8).contains(needle)) return true
    }
    return false
}


    /** Same root the maintenance owner uses; the console adapter must not invent a second one. */
    internal val dataRoot = File(filesDir, "dsh")
    private val generation = AtomicLong(0L)
    private val homeDir = File(dataRoot, "home")
    private val closureRoot = File(dataRoot, "closure")

    /**
     * Managed repair inventory from the build's own payload manifest (bounded, hash-verified).
     * Parsed lazily: it must not run on a path that never performs maintenance.
     */
    private val managedFiles: List<com.muyuchat.core.dsh.maintenance.DshManagedFile> by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED
    ) {
        DshPayloadCriticalFiles.parse(runCatching { payloadManifestJson() }.getOrNull())
    }

    private val payloadRepairSource: DshPayloadRepairSource by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DshPayloadRepairSource(payloadArchiveOpen, File(dataRoot, "maintenance/repair-source"))
    }

    private val sessionBridge = DshSessionV4NodeBridge(
        dataRoot = dataRoot,
        nodeExecutable = nodeExecutable,
        helperSource = codecHelperSource
    )

    private val sessionCodecProvider: () -> DshSessionCodec? = { sessionBridge.codec(homeDir) }

    private val candidates = DshClosureCandidateSource(
        closureRoot = closureRoot,
        supportedAbis = Build.SUPPORTED_ABIS.toSet(),
        protectedRoots = listOf(homeDir, File(dataRoot, "workspace")),
        // A committed closure keeps living below `incoming`; it is the active tree, not a
        // staged candidate, so a new candidate can be staged after a commit.
        activeRoot = { owner.upgrade.selector()?.root?.let(::File) },
        // The previous closure stays on disk for rollback; it must never be advertised as a new
        // upgrade candidate (that would present a downgrade as an upgrade).
        previousRoot = {
            owner.upgrade.selector()?.previousRoot?.takeIf { it.isNotBlank() }?.let(::File)
        }
    )

    /**
     * Production candidate source for a payload-only update: an APK that ships a different payload
     * than the active closure stages it before the console reads the candidate list. Fail-closed:
     * any doubt leaves the console exactly as it was.
     */
    private val packagedProvisioner: DshPackagedCandidateProvisioner by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DshPackagedCandidateProvisioner(
            closureRoot = closureRoot,
            activeRoot = { owner.upgrade.selector()?.root?.let(::File) },
            packagedIdentity = {
                val sha = expectedArchiveSha256().orEmpty()
                val version = expectedPayloadVersion().orEmpty()
                val count = expectedFileCount()
                if (sha.isBlank() || version.isBlank() || count == null || count <= 0L) {
                    null
                } else {
                    DshPackagedPayloadIdentity(
                        payloadVersion = version,
                        archiveSha256 = sha,
                        fileCount = count
                    )
                }
            },
            installedPayloadDir = { File(dataRoot, "payload") },
            // The selector's previous closure stays in `incoming` after a commit; the candidate
            // lookup already hides it, so it must not block staged-candidate staging checks.
            previousRoot = {
                owner.upgrade.selector()?.previousRoot?.takeIf { it.isNotBlank() }?.let(::File)
            }
        )
    }

    /**
     * Candidate state for the console. When nothing is staged, the packaged payload is offered as a
     * candidate only if it differs from the active closure and passes the production verification;
     * a staged directory the verifier rejects is removed again so it cannot wedge the console.
     */
    private fun preparedCandidateLookup(): DshClosureCandidateLookup {
        val before = candidates.lookup()
        if (before != DshClosureCandidateLookup.None) return before
        val staged = packagedProvisioner.ensureProvisioned()
        Log.i(
            TAG,
            "packaged candidate provision: ${staged.javaClass.simpleName} " +
                ((staged as? DshProvisionResult.Refused)?.detail ?: "").take(200)
        )
        if (staged !is DshProvisionResult.Staged) return before
        val after = candidates.lookup()
        if (after is DshClosureCandidateLookup.Rejected) {
            runCatching { File(closureRoot, "incoming/${staged.candidateId}").deleteRecursively() }
            return before
        }
        return after
    }

    private val admission = DshMaintenanceAdmissionPort { request ->
        acquire(request)
    }

    private val probe = DshMaintenanceRecoveryProbe { kind, targetRoot, _ ->
        if (kind == DshMaintenanceKind.SESSION_REPAIR) {
            verifySessionTree(targetRoot)
        } else if (targetRoot.isDirectory && targetRoot.canRead()) {
            DshMaintenanceProbeResult.HEALTHY
        } else {
            DshMaintenanceProbeResult.unhealthy("maintenance target tree is missing or unreadable")
        }
    }

    /**
     * Production-only half of the host lifecycle. The trial half is owned by [trialHost]; these
     * methods are unreachable through that wrapper but must stay typed instead of throwing.
     */
    private val productionHost = object : DshHostLifecyclePort {
        override fun status(): DshHostStatus {
            val snapshot = runtime.snapshot.value
            Log.i(
                TAG,
                "prod-status phase=${snapshot.phase} epoch=${snapshot.epoch} failure=${snapshot.failure?.code}"
            )
            return statusOf(snapshot)
        }

        override fun stopAndConfirm(timeoutMillis: Long): DshHostStatus {
            val beganAtMillis = System.currentTimeMillis()
            val before = runtime.snapshot.value
            Log.i(
                TAG,
                "stop-and-confirm begin phase=${before.phase} epoch=${before.epoch} timeout=$timeoutMillis"
            )
            try {
                runBlocking {
                    runtime.stop()
                    // The snapshot flow is updated by the runtime collector, so reading it right
                    // after stop() can still show the pre-stop phase (READY/STOPPING). Maintenance
                    // serializes trial and production on a confirmed STOPPED, so wait for the
                    // observation to settle instead of reporting a stale RUNNING.
                    withTimeoutOrNull(timeoutMillis.coerceAtLeast(1L)) {
                        runtime.snapshot.first {
                            it.phase == DshRuntimePhase.STOPPED || it.phase == DshRuntimePhase.FAILED
                        }
                    }
                }
            } catch (failure: Throwable) {
                Log.e(
                    TAG,
                    "stop-and-confirm threw ${failure.javaClass.simpleName} after " +
                        "${System.currentTimeMillis() - beganAtMillis}ms: ${failure.message}"
                )
                throw failure
            }
            val snapshot = runtime.snapshot.value
            Log.i(
                TAG,
                "stop-and-confirm end phase=${snapshot.phase} epoch=${snapshot.epoch} " +
                    "failure=${snapshot.failure?.code} elapsed=${System.currentTimeMillis() - beganAtMillis}ms"
            )
            if (snapshot.phase == DshRuntimePhase.FAILED &&
                snapshot.failure?.code == DshFailureCode.PROCESS_EXITED
            ) {
                // A process that already exited cannot be "still running": an intentional stop
                // whose child exit is observed as PROCESS_EXITED is quiescent for maintenance.
                return DshHostStatus(
                    state = DshHostState.STOPPED,
                    generation = snapshot.epoch,
                    detail = DshFailureCode.PROCESS_EXITED.name
                )
            }
            return statusOf(snapshot)
        }

        override fun startTrial(candidateRoot: File, candidateFingerprint: String): DshHostStatus =
            DshHostStatus(
                state = DshHostState.FAILED,
                detail = "the production lifecycle port does not own a trial generation"
            )

        override fun probeTrialHealth(): DshHostHealth = DshHostHealth(detail = "no trial generation")

        override fun stopTrial(timeoutMillis: Long): DshHostStatus =
            DshHostStatus(state = DshHostState.STOPPED, detail = "no trial generation")

        override fun startProduction(activeRoot: File, signature: String): DshHostStatus {
            // The committed closure tree is what production must execute; a root without a
            // payload tree (the installed layout, or the empty previous selector) returns to the
            // installed payload, and the stop guarantees no closure generation survives it.
            val payloadRoot = File(activeRoot, "payload")
            val snapshot = runBlocking {
                if (payloadRoot.isDirectory) {
                    runtime.ensureStartedFrom(payloadRoot)
                } else {
                    runtime.stop()
                    runtime.ensureStartedFrom(null)
                }
            }
            return statusOf(snapshot)
        }

        override fun probeReadOnlyHealth(): DshHostHealth = readOnlyProductionHealth()
    }

    private val trialHost = DshClosureTrialHost(
        production = productionHost,
        trialPort = trialLaunchPort,
        dataRoot = dataRoot,
        // After an app-process death nobody owns the trial generation any more; the only honest
        // stop proof left is "no live process still references the candidate root". Same-UID
        // command lines are readable, and an unreadable /proc fails closed (assume alive).
        candidateProcessAlive = ::candidateRootHasLiveProcess
    )

    val owner: DshMaintenanceOwner by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DshMaintenanceOwner(
            dataRoot = dataRoot,
            admission = admission,
            probe = probe,
            host = trialHost,
            supportedAbis = Build.SUPPORTED_ABIS.toSet(),
            sessionCodec = null,
            sessionCodecProvider = sessionCodecProvider,
            selfCheckPort = com.muyuchat.core.dsh.maintenance.DshRuntimeSelfCheckPort {
                com.muyuchat.core.dsh.maintenance.DshSelfCheckEnvironment(
                    payloadMarkerFile = File(dataRoot, "payload.marker.json"),
                    expectedArchiveSha256 = expectedArchiveSha256(),
                    expectedFileCount = expectedFileCount(),
                    nativeLibrary = nativeLibrary(),
                    installedAbi = installedAbi(),
                    homeMarkerFile = File(dataRoot, ".mca-home-seeded.json"),
                    sessionsDir = File(dataRoot, "home/sessions"),
                    sessionCodecAvailable = sessionCodecAvailable(),
                    sessionHeaderProof = sessionHeaderProof(),
                    runtimePhase = runtime.snapshot.value.phase.name,
                    gatewayReady = gatewayReady(),
                    bridgeReady = bridgeReady()
                )
            },
            managedFiles = managedFiles,
            candidateLookup = { preparedCandidateLookup() },
            installedIdentity = {
                com.muyuchat.core.dsh.maintenance.DshInstalledPayloadIdentity(
                    payloadVersion = expectedPayloadVersion().orEmpty(),
                    archiveSha256 = expectedArchiveSha256().orEmpty()
                )
            }
        )
    }

    /**
     * True only when this build declares managed files *and* the owner can run the bounded repair
     * transaction. The console advertises the repair action from this fact, never from a guess.
     */
    override val available: Boolean
        get() = managedFiles.isNotEmpty() && owner.managedRepairAvailable()

    /** Number of declared managed files (payload `criticalFiles`), for diagnostics only. */
    fun managedFileCount(): Int = managedFiles.size

    /** Fresh read-only self-check; null when no probe port is wired. */
    override fun selfCheck(): com.muyuchat.core.dsh.maintenance.DshSelfCheckOutcome? = owner.selfCheck()

    /**
     * Extracts exactly the declared payload files from this APK's payload archive and verifies each
     * one against the build manifest. Returns null when any file is missing or does not match, so
     * the caller refuses instead of installing unproven bytes.
     */
    override fun prepareSource(relativePaths: List<String>): File? {
        val wanted = managedFiles.filter { it.relativePath in relativePaths }
        if (wanted.isEmpty()) return null
        return payloadRepairSource.extract(wanted)
    }

    /** Bounded managed-file repair through the maintenance engine (quarantine + atomic install). */
    override fun repair(
        mismatched: List<String>,
        source: File,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): com.muyuchat.core.dsh.maintenance.DshMaintenanceOutcome? =
        owner.repairManagedFiles(mismatched, source, requester, quiescence)

    /** True only after a sweep that allowed the host to start (D22/D24 ordering). */
    @Volatile
    private var startupRecoveryDone = false

    /** Last sweep result, for the console; read-only and never used as an admission decision. */
    @Volatile
    private var lastStartupReport: DshMaintenanceStartupReport? = null

    /**
     * Post-READY confirmation of an upgrade interrupted between its selector switch and health
     * proof. Called from the runtime's READY observation off the main thread; never starts a host.
     */
    fun confirmStartedUpgrade(): DshUpgradeResult {
        val result = owner.confirmStartedUpgrade()
        val detail = when (result) {
            is DshUpgradeResult.NoChange -> result.reason
            is DshUpgradeResult.Rejected -> result.failure.message
            is DshUpgradeResult.TrialFailed -> result.failure.message
            is DshUpgradeResult.RolledBack -> result.failure.message
            is DshUpgradeResult.RecoveryRequired -> result.failure.message
            is DshUpgradeResult.Committed -> result.candidateId
        }
        Log.i(TAG, "post-start upgrade confirmation: ${result.javaClass.simpleName} ${detail.take(160)}")
        return result
    }

    fun recoverOnStart(): DshStartupOutcome {
        val outcome = owner.recoverOnStart()
        lastStartupReport = outcome.report
        val failure = outcome.report.failure
        if (failure != null) {
            Log.w(TAG, "maintenance startup needs attention: ${failure.code.name}")
        }
        if (outcome.report.trialRecovery is DshTrialRecovery.RequiresAttention) {
            Log.w(TAG, "interrupted closure trial requires attention")
        }
        return outcome
    }

    /**
     * Startup admission for the host start path. A blocked sweep is never rounded to "done":
     * the caller must not start the runtime, and the next start attempt re-runs the sweep so a
     * transient obstruction (busy lease, unreadable journal that a retry can re-read) can clear.
     */
    fun startupAdmission(): DshStartupAdmission {
        if (startupRecoveryDone) return DshStartupAdmission.Admitted
        synchronized(this) {
            if (startupRecoveryDone) return DshStartupAdmission.Admitted
            val outcome = try {
                recoverOnStart()
            } catch (throwable: Throwable) {
                // The sweep must not escape as an unchecked exception: an unproven state blocks
                // the start with a typed, retryable reason instead of starting anyway.
                Log.w(TAG, "maintenance startup sweep threw ${throwable.javaClass.simpleName}")
                return DshStartupAdmission.Refused(
                    DshRuntimeFailure(
                        code = DshFailureCode.STARTUP_RECOVERY_REQUIRED,
                        message = "startup recovery could not run to completion",
                        retryable = true,
                        diagnostics = "sweep=" + throwable.javaClass.simpleName
                    )
                )
            }
            return when (outcome) {
                is DshStartupOutcome.Proceed -> {
                    startupRecoveryDone = true
                    DshStartupAdmission.Admitted
                }

                is DshStartupOutcome.Blocked -> DshStartupAdmission.Refused(
                    DshRuntimeFailure(
                        code = DshFailureCode.STARTUP_RECOVERY_REQUIRED,
                        message = outcome.failure.message,
                        retryable = outcome.failure.retryable,
                        diagnostics = "maintenance=" + outcome.failure.code.name
                    )
                )
            }
        }
    }

    /** Read-only startup sweep for the maintenance console; never starts a host. */
    fun startupReport(): DshMaintenanceStartupReport? = lastStartupReport

    /** Read-only admission state for the D20 console projection. */
    fun admissionSnapshot(): DshMaintenanceAdmissionSnapshot = gate.snapshot()

    @Volatile
    private var consoleBarrierLeaseId: String? = null

    /**
     * Console-held exclusive barrier. It is useful only while the console shows the admission
     * state and the user runs a maintenance action; it is refused while any mutator/reader lease
     * is live, exactly like the maintenance transactions themselves.
     */
    fun acquireConsoleBarrier(): Boolean {
        if (consoleBarrierLeaseId != null) return true
        val gen = generation.get()
        return when (val result = gate.tryAcquire(DshMaintenanceLeaseKind.MAINTENANCE, gen)) {
            is DshMaintenanceAdmission.Admitted -> {
                consoleBarrierLeaseId = result.lease.leaseId
                true
            }

            is DshMaintenanceAdmission.Refused -> false
        }
    }

    fun releaseConsoleBarrier(): Boolean {
        val id = consoleBarrierLeaseId ?: return false
        gate.release(id, generation.get())
        consoleBarrierLeaseId = null
        return true
    }

    /** Read-only candidate state for the console; never promotes or extracts a candidate. */
    fun closureCandidate(): DshClosureCandidateLookup = preparedCandidateLookup()

    /** Current trial journal (metadata only) for diagnostics. */
    fun activeTrial(): com.muyuchat.core.dsh.maintenance.DshTrialActiveRecord? = trialHost.activeTrial()

    /**
     * The committed closure `payload` tree the production generation must execute, or null when
     * the installed payload is the active one (no selector, or a selector without a payload tree).
     */
    fun activeClosurePayload(): File? =
        owner.upgrade.selector()
            ?.let { selector -> File(selector.root, "payload").takeIf { it.isDirectory } }

    // ------------------------------------------------------------------ session codec proof

    private fun sessionCodecAvailable(): Boolean = sessionCodecProvider() != null

    /**
     * Decodes one real header from the managed home. This is the only acceptable SESSION_READER
     * proof; "the directory exists" is not.
     */
    private fun sessionHeaderProof(): DshSessionHeaderProof {
        val codec = sessionCodecProvider() ?: return DshSessionHeaderProof.CODEC_UNAVAILABLE
        val sessions = runCatching { DshSessionInventory(DshHomeLayout(homeDir)).list() }.getOrNull()
            ?: return DshSessionHeaderProof(false, "the managed session tree is unreadable")
        if (sessions.isEmpty()) return DshSessionHeaderProof.NO_SESSIONS
        var last = DshSessionHeaderProof.NO_SESSIONS
        for (session in sessions.take(MAX_HEADER_PROBES)) {
            if (session.bytes <= 0L) continue
            val proof = codec.inspectHeader(session.file)
            if (proof.verified) return proof
            last = proof
        }
        return last
    }

    /** Post-commit health for session repair: every probed session must still decode its header. */
    private fun verifySessionTree(home: File): DshMaintenanceProbeResult {
        val codec = sessionCodecProvider()
            ?: return DshMaintenanceProbeResult.unhealthy("no official session codec is available")
        val sessions = runCatching { DshSessionInventory(DshHomeLayout(home)).list() }.getOrElse {
            return DshMaintenanceProbeResult.unhealthy("the repaired session tree is unreadable")
        }
        if (sessions.isEmpty()) {
            return DshMaintenanceProbeResult.unhealthy("no session log remains after the repair")
        }
        for (session in sessions.take(MAX_HEADER_PROBES)) {
            val proof = codec.inspectHeader(session.file)
            if (!proof.verified) {
                return DshMaintenanceProbeResult.unhealthy("a session header no longer decodes")
            }
        }
        return DshMaintenanceProbeResult.HEALTHY
    }

    private fun readOnlyProductionHealth(): DshHostHealth {
        val snapshot = runtime.snapshot.value
        val proof = sessionHeaderProof()
        val credentials = File(homeDir, ".credentials.yaml")
        val credentialsPresent = credentials.isFile && credentials.length() > 0L
        return DshHostHealth(
            hostReady = snapshot.phase == DshRuntimePhase.READY,
            homeReadable = homeDir.isDirectory && homeDir.canRead(),
            sessionReaderReady = proof.verified,
            // The official provider is either the MCA gateway of this generation or the official
            // account provider backed by a present credential store; neither is assumed.
            providerConfigured = gatewayReady() || credentialsPresent,
            authReady = credentialsPresent,
            detail = "session-reader=${proof.detail}; credentials=" +
                (if (credentialsPresent) "present" else "absent")
        )
    }

    private fun acquire(request: DshMaintenanceAdmissionRequest): DshMaintenanceAdmissionOutcome {
        // Each maintenance transaction uses an exclusive barrier. The platform gate refuses while
        // any mutation/reader lease is live; the maintenance owner never assumes idleness.
        val gen = generation.get()
        return when (val result = gate.tryAcquire(DshMaintenanceLeaseKind.MAINTENANCE, gen)) {
            is DshMaintenanceAdmission.Admitted -> {
                val lease = result.lease
                DshMaintenanceAdmissionOutcome.Granted(
                    object : DshMaintenanceLease {
                        override val leaseId: String = lease.leaseId
                        override val owner: String = request.requester.owner
                        override fun release() {
                            gate.release(lease.leaseId, gen)
                        }
                    }
                )
            }

            is DshMaintenanceAdmission.Refused -> DshMaintenanceAdmissionOutcome.Busy(
                holders = listOf(result.reason.name),
                retryAfterMillis = 1_000L
            )
        }
    }

    private fun statusOf(snapshot: DshRuntimeSnapshot): DshHostStatus = DshHostStatus(
        state = when (snapshot.phase) {
            DshRuntimePhase.STOPPED -> DshHostState.STOPPED
            DshRuntimePhase.STOPPING -> DshHostState.STOPPING
            DshRuntimePhase.READY -> DshHostState.RUNNING
            DshRuntimePhase.FAILED -> DshHostState.FAILED
            DshRuntimePhase.STARTING -> DshHostState.UNKNOWN
        },
        generation = snapshot.epoch,
        detail = snapshot.failure?.code?.name.orEmpty()
    )

    private companion object {
        const val TAG = "McaDshMaintenance"
        const val MAX_HEADER_PROBES = 5
    }
}
