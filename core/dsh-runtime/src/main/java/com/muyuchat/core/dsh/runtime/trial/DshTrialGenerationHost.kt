package com.muyuchat.core.dsh.runtime.trial

import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import com.muyuchat.core.dsh.contract.DshTrialHealthSnapshot
import com.muyuchat.core.dsh.contract.DshTrialPhase
import com.muyuchat.core.dsh.contract.DshTrialSnapshot
import com.muyuchat.core.dsh.runtime.config.DshGatewayConfigSeeder
import com.muyuchat.core.dsh.runtime.config.DshRoots
import com.muyuchat.core.dsh.runtime.config.DshRuntimeConfig
import com.muyuchat.core.dsh.runtime.config.DshSeedOutcome
import com.muyuchat.core.dsh.runtime.launch.DshHostLauncher
import com.muyuchat.core.dsh.runtime.launch.DshLaunchOutcome
import com.muyuchat.core.dsh.runtime.launch.DshLaunchProfile
import com.muyuchat.core.dsh.runtime.launch.DshSeedAttempt
import com.muyuchat.core.dsh.runtime.process.DshNodeCommand
import com.muyuchat.core.dsh.runtime.process.DshNodeHost
import com.muyuchat.core.dsh.runtime.startup.DshStartupStage
import com.muyuchat.core.dsh.runtime.state.DshStopFence
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** What the one owner decided about admitting a non-publishing trial into the host slot. */
internal sealed interface DshTrialAdmission {
    /** The slot is settled, `STARTING` was published and [epoch] owns the generation. */
    data class Admitted(val epoch: Long) : DshTrialAdmission

    /** Nothing was started; the reason is path-free and maps to a trial refusal. */
    data class Refused(val reason: String) : DshTrialAdmission

    /** The slot could not be settled positively; the trial must stay UNKNOWN. */
    data class Unknown(val detail: String) : DshTrialAdmission
}

/**
 * The isolated closure-trial generation of [com.muyuchat.core.dsh.runtime.DshRuntimeOwner] (D24).
 *
 * The trial is **not** a second runtime owner: it runs in the very same host slot, operation mutex
 * and stop fence as production, and the owner's
 * [com.muyuchat.core.dsh.runtime.state.DshRuntimeStateMachine] stays the single lifecycle
 * authority. This collaborator only owns the trial-specific decision sequence:
 *
 * - a non-publishing start: `STARTING` is published so production is refused while the trial
 *   occupies the slot, but no `READY`, authenticated entry, last-ready binding or exit watcher is
 *   ever recorded;
 * - its own isolated home/workspace/tmp below `files/dsh/trial`, the candidate's own executable
 *   payload tree (`<candidate>/payload`), `--port 0`, no payload install/link rewrite and no
 *   production secret/bridge;
 * - an actual stop proof (SIGTERM -> SIGKILL + bounded survivor scan), durable journal recovery
 *   after a process death with `/proc` attribution, and UNKNOWN on any doubt.
 *
 * The owner mediates everything that belongs to the single slot through [Bridge]: the operation
 * mutex, admission (phase + fence + `STARTING` publication), the host slot, startup publication,
 * the shared failure/abort cleanup and the runtime snapshot. The trial marker itself lives here.
 *
 * The constructor takes the owner's real collaborators ([DshRuntimeConfig], [DshHostLauncher],
 * [DshStopFence], [Bridge]) plus individual function seams instead of the whole owner `Deps`, so
 * the trial cannot reach a capability it does not use.
 */
internal class DshTrialGenerationHost(
    private val config: DshRuntimeConfig,
    private val launcher: DshHostLauncher,
    private val fence: DshStopFence,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val isProcessAlive: (Int) -> Boolean,
    private val processCommandLine: (Int) -> String?,
    private val signalProcess: (Int, Int) -> Boolean,
    private val nativeLibraryDir: () -> File,
    private val log: (String) -> Unit,
    private val bridge: Bridge
) {
    /**
     * The owner-owned capabilities the trial may use. Deliberately narrow: the trial never receives
     * the state machine, the production launcher profile or a mutable root snapshot, so it cannot
     * publish READY or start a second generation.
     */
    internal interface Bridge {
        /** Runs [block] under the one owner operation mutex. */
        suspend fun <T> withOperationLock(block: suspend () -> T): T

        /** The owner's current stop-request fact. */
        fun stopRequested(): Boolean

        /** Settles the slot, publishes `STARTING` and reports whether the trial may launch. */
        fun admitTrialGeneration(): DshTrialAdmission

        /** Publishes the process the launcher just created into the one host slot. */
        fun attachTrialHost(host: DshNodeHost)

        /** Clears the authenticated entry and releases [expected] from the host slot when it owns it. */
        fun detachTrialHost(expected: DshNodeHost)

        /** Records a verified startup boundary for the admitted generation. */
        fun recordStartup(epoch: Long, stage: DshStartupStage): Boolean

        /** Publishes the owner's positive STOPPED transition. */
        fun publishTrialStopped(): DshRuntimeSnapshot

        /** Publishes the owner's fenced STOP_TIMEOUT transition. */
        fun publishTrialStopTimeout(detail: String): DshRuntimeSnapshot

        /** Runs the owner's ordinary launch-failure cleanup and publishes the failure. */
        suspend fun failTrialLaunch(failure: DshLaunchOutcome.Failed): DshRuntimeSnapshot

        /** Runs the owner's ordinary stop-during-start settlement. */
        suspend fun abortTrialForStop(): DshRuntimeSnapshot
    }

    private val trialGenerations = AtomicLong(0)

    /**
     * The one isolated trial generation that may occupy the host slot. Null means no in-memory
     * trial exists; after a process death the durable journal and `/proc` are the only honest
     * source (see [trialStatus] and [stopTrial]).
     */
    @Volatile
    private var trial: DshTrialSession? = null

    private val journal = DshTrialJournal(
        File(File(config.dataRoot, DshTrialRoots.TRIAL_DIR_NAME), "active.json")
    )

    private val recovery = DshTrialRecovery(
        ioDispatcher = ioDispatcher,
        isAlive = isProcessAlive,
        commandLine = processCommandLine,
        signal = signalProcess,
        scan = { fence.scanOrNull() },
        needles = listOf(
            config.payloadDir.absolutePath,
            File(nativeLibraryDir(), config.nodeLibraryName).absolutePath
        ),
        pollMillis = config.settlePollMillis,
        stopGraceMillis = config.stopGraceMillis,
        stopKillMillis = config.stopKillMillis,
        clock = clock,
        log = log
    )

    /** True while an in-memory trial generation owns the host slot. */
    fun hasActiveTrial(): Boolean = trial != null

    /**
     * Releases the in-memory trial marker after the owner positively stopped its host through the
     * production stop path. The trial's own durable journal remains the recovery source.
     */
    fun releaseAfterProductionStop() {
        trial = null
    }

    // ------------------------------------------------------------------ start

    /**
     * Starts the one isolated, **non-publishing** trial generation of this owner.
     *
     * Behaviour, in order:
     * 1. the candidate root must be a staged candidate below `files/dsh/closure`, the three data
     *    roots must share one trial root below `files/dsh/trial`, and the fingerprint must be a
     *    finite sha256 token; each refusal is typed and path-free;
     * 2. the production host must be confirmed stopped (no live host, `STOPPED`/`FAILED` phase) and
     *    the shared stop fence must be clear — the same pre-start scan production uses;
     * 3. the owner publishes `STARTING`, which is what makes a concurrent production
     *    `ensureStarted`/`apply` refuse to attach or launch a second host;
     * 4. the isolated home is seeded from the **verified official template** (account-only overlay:
     *    no MCA provider, no bearer token, no capability bridge in the child environment), the data
     *    half is copied read-only from the real session store, the isolated TMPDIR is probed, and
     *    the host is launched with `--port 0` from the trial workspace **using the candidate's own
     *    payload tree**. A candidate that carries no `payload/` kernel entry is refused rather than
     *    run against the installed payload (which would prove nothing about the candidate);
     * 5. the readiness gate must confirm the official surface; only then this returns `RUNNING`.
     *
     * It never records `READY`, the authenticated entry, the last-ready binding or an exit watcher:
     * a trial cannot appear as the production generation. `STOPPED` is returned for a *refusal*
     * (the app-side port maps it to a refusal); `FAILED`/`UNKNOWN` are never success.
     */
    suspend fun startTrial(
        candidateRoot: File,
        candidateFingerprint: String,
        homeRoot: File,
        workspaceRoot: File,
        tmpRoot: File
    ): DshTrialSnapshot {
        if (!isSha256(candidateFingerprint)) {
            return trialRefused("the candidate fingerprint is not a sha256 digest")
        }
        val roots = when (val resolution = DshTrialRoots.resolve(
            dataRoot = config.dataRoot,
            candidateRoot = candidateRoot,
            homeRoot = homeRoot,
            workspaceRoot = workspaceRoot,
            tmpRoot = tmpRoot
        )) {
            is DshTrialRoots.Resolution.Refused -> return trialRefused(resolution.reason)
            is DshTrialRoots.Resolution.Ok -> resolution.accepted
        }
        if (trial != null) return trialFailed("an isolated trial generation is already active")
        if (bridge.stopRequested()) {
            return trialRefused("a stop was requested before the trial could start")
        }

        return bridge.withOperationLock {
            if (trial != null) {
                return@withOperationLock trialFailed("an isolated trial generation is already active")
            }
            val epoch = when (val admission = bridge.admitTrialGeneration()) {
                is DshTrialAdmission.Refused -> return@withOperationLock trialRefused(admission.reason)
                is DshTrialAdmission.Unknown -> return@withOperationLock trialUnknown(admission.detail)
                is DshTrialAdmission.Admitted -> admission.epoch
            }

            val generation = trialGenerations.incrementAndGet()
            val candidatePayload = when (
                val resolution = DshTrialCandidatePayload.resolve(
                    candidateRoot = roots.candidate,
                    kernelEntryRelativePath = config.kernelEntryRelativePath
                )
            ) {
                is DshTrialCandidatePayload.Resolution.Refused ->
                    return@withOperationLock trialRefused(resolution.reason)

                is DshTrialCandidatePayload.Resolution.Ok -> resolution
            }
            val trialRoots = DshRoots(
                home = roots.home,
                workspace = roots.workspace,
                tmp = roots.tmp,
                payload = candidatePayload.payloadRoot
            )
            val trialConfig = config.forIsolatedGeneration(
                dataRoot = roots.trialRoot,
                roots = trialRoots,
                port = 0
            ).validated()
            val profile = DshLaunchProfile(
                logPrefix = "dsh: trial: ",
                hostNoun = "isolated trial host",
                roots = trialRoots,
                kernelEntry = candidatePayload.kernelEntry,
                bindHost = config.host,
                port = 0,
                publishReady = false,
                verifyPayload = false,
                prepareCommandLinks = false,
                watchExit = false
            )
            val tmpWritable = probeTmpWritable(roots.tmp, generation)
            var dataSeedResult = DshTrialDataSeed.Result.NONE
            val outcome = launcher.launch(
                DshHostLauncher.Request(
                    profile = profile,
                    environment = trialEnvironment(trialConfig),
                    // Seed the isolated home first (it must look like a first-run home to the
                    // seeder), then copy the data half into it; both happen before the process
                    // starts, so the trial cannot boot against a half-materialised home.
                    seed = {
                        seedTrialHome(trialConfig, roots.home) { result -> dataSeedResult = result }
                    },
                    onHostCreated = { created -> bridge.attachTrialHost(created) },
                    onStage = { stage -> bridge.recordStartup(epoch, stage) },
                    shouldAbort = { bridge.stopRequested() }
                )
            )
            when (outcome) {
                is DshLaunchOutcome.Launched -> {
                    val session = DshTrialSession(
                        generation = generation,
                        host = outcome.host,
                        trialRoot = roots.trialRoot,
                        roots = profile.roots,
                        candidateRoot = roots.candidate,
                        candidateFingerprint = candidateFingerprint,
                        hostReady = true,
                        tmpWritable = tmpWritable,
                        dataSeedDetail = dataSeedResult.detail,
                        sourceSessions = dataSeedResult.sourceLogs
                    )
                    trial = session
                    log(
                        "dsh: trial: RUNNING generation=$generation pid=${outcome.host.processId} " +
                            "origin=${outcome.ready.origin} (non-publishing: no READY entry, no watcher)"
                    )
                    DshTrialSnapshot(
                        phase = DshTrialPhase.RUNNING,
                        pid = outcome.host.processId?.toLong() ?: 0L,
                        generation = generation,
                        detail = "the isolated trial generation is serving its own isolated home"
                    )
                }

                is DshLaunchOutcome.Failed -> {
                    val failed = bridge.failTrialLaunch(outcome)
                    DshTrialSnapshot(
                        phase = DshTrialPhase.FAILED,
                        generation = generation,
                        detail = (failed.failure?.message ?: "the isolated trial generation did not start")
                            .take(MAX_DETAIL_CHARS)
                    )
                }

                DshLaunchOutcome.AbortedForStop -> {
                    val aborted = bridge.abortTrialForStop()
                    if (aborted.phase == DshRuntimePhase.STOPPED) {
                        trialRefused("the trial start was aborted by a stop request")
                    } else {
                        trialUnknown("the trial start was aborted and its process tree could not be settled")
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ stop

    /**
     * Releases the isolated trial generation with a real stop proof.
     *
     * With an in-memory trial the host runs the ordinary SIGTERM -> SIGKILL escalation and the
     * bounded survivor scan; `STOPPED` requires both. Without one (a fresh owner after process
     * death) the durable journal names the PID, `/proc/<pid>/cmdline` must still reference this
     * installation's payload before anything is signalled, and the survivor scan must be empty
     * afterwards. Without a journal an empty same-UID payload scan is the positive proof.
     * An unavailable scan is always `UNKNOWN`, never `STOPPED`.
     */
    suspend fun stopTrial(): DshTrialSnapshot = bridge.withOperationLock {
        val active = trial
        if (active == null) return@withOperationLock releaseJournaledTrial()
        val outcome = withContext(ioDispatcher) {
            active.host.stop(config.stopGraceMillis, config.stopKillMillis)
        }
        trial = null
        bridge.detachTrialHost(active.host)
        return@withOperationLock when (val verdict = DshTrialStopPolicy.verdict(outcome, fence.survivorsAfter(outcome))) {
            is DshTrialStopPolicy.Verdict.Stopped -> {
                fence.clear()
                bridge.publishTrialStopped()
                log("dsh: trial: released (${outcome.detail})")
                DshTrialSnapshot(
                    phase = DshTrialPhase.STOPPED,
                    generation = active.generation,
                    detail = "the isolated trial generation was released"
                )
            }

            is DshTrialStopPolicy.Verdict.Survived -> {
                fence.retain(verdict.pids)
                bridge.publishTrialStopTimeout(outcome.detail)
                log("dsh: trial: stop left survivors pids=${verdict.pids}")
                DshTrialSnapshot(
                    phase = DshTrialPhase.FAILED,
                    pid = verdict.pids.firstOrNull()?.toLong() ?: 0L,
                    generation = active.generation,
                    detail = "the isolated trial processes survived the stop pids=" +
                        verdict.pids.take(MAX_LISTED_PIDS).joinToString(",")
                )
            }

            is DshTrialStopPolicy.Verdict.Unverified -> {
                fence.retain(outcome.survivingPids.ifEmpty { outcome.trackedPids })
                bridge.publishTrialStopTimeout("${outcome.detail} (payload orphan scan unavailable)")
                log("dsh: trial: stop cannot be verified: ${verdict.detail}")
                DshTrialSnapshot(
                    phase = DshTrialPhase.UNKNOWN,
                    generation = active.generation,
                    detail = ("the isolated trial stop cannot be verified: " + verdict.detail)
                        .take(MAX_DETAIL_CHARS)
                )
            }
        }
    }

    /**
     * Fresh trial status. It never trusts a cached lifecycle value: with no in-memory marker it
     * re-reads the durable journal and the owner's `/proc` view every call, and it never answers
     * `STOPPED` when the survivor scan is unavailable or a journaled PID cannot be attributed.
     */
    fun trialStatus(): DshTrialSnapshot {
        val active = trial
        if (active != null) {
            val pid = active.host.processId?.toLong() ?: 0L
            if (active.host.isAlive) {
                return DshTrialSnapshot(
                    phase = DshTrialPhase.RUNNING,
                    pid = pid,
                    generation = active.generation,
                    detail = "the isolated trial generation is running"
                )
            }
            return DshTrialSnapshot(
                phase = DshTrialPhase.FAILED,
                pid = pid,
                generation = active.generation,
                detail = "the isolated trial host exited (code ${active.host.exitCode ?: 0})"
            )
        }
        val record = journal.read()
        if (record != null && record.unreadable) {
            return trialUnknown("the trial journal is unreadable; the trial state cannot be verified")
        }
        if (record != null && record.mayHoldProcess && record.pid > 0L) {
            val pid = record.pid.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (!isProcessAlive(pid)) {
                val scanned = fence.scanOrNull()
                    ?: return trialUnknown("the trial state cannot be verified: survivor scan unavailable")
                return if (scanned.isEmpty()) {
                    DshTrialSnapshot(
                        phase = DshTrialPhase.STOPPED,
                        pid = record.pid,
                        generation = record.generation,
                        detail = "the journaled trial process is gone"
                    )
                } else {
                    trialUnknown("payload processes remain that the journal does not name")
                }
            }
            if (!recovery.commandLineMatches(pid)) {
                return trialUnknown(
                    "the journaled trial pid does not reference the verified payload",
                    generation = record.generation,
                    pid = record.pid
                )
            }
            return DshTrialSnapshot(
                phase = DshTrialPhase.RUNNING,
                pid = record.pid,
                generation = record.generation,
                detail = "a journaled isolated trial generation is still alive"
            )
        }
        val scanned = fence.scanOrNull()
            ?: return trialUnknown("the trial state cannot be verified: survivor scan unavailable")
        if (scanned.isEmpty()) {
            return DshTrialSnapshot(
                phase = DshTrialPhase.STOPPED,
                detail = "no isolated trial generation is running"
            )
        }
        return trialUnknown("same-UID DSH payload processes exist without a trial journal")
    }

    /**
     * Read-only trial smoke. Every positive field comes from a positive observation:
     * - [DshTrialHealthSnapshot.hostReady] = the readiness gate proved the official surface **and**
     *   the trial process is still alive;
     * - [DshTrialHealthSnapshot.homeReadable] = a real read of the isolated home's official profile
     *   and config directory;
     * - [DshTrialHealthSnapshot.sessionReaderReady] = a real official session log in the isolated
     *   home whose frame container passes the official structural scan (see [DshTrialSessionReader]).
     * It never reports provider or auth readiness: the trial home has no credentials by design.
     */
    fun trialHealth(): DshTrialHealthSnapshot {
        val active = trial ?: return DshTrialHealthSnapshot(detail = "no isolated trial generation is active")
        val alive = active.host.isAlive
        val home = DshTrialHomeProof.prove(active.roots.home)
        // A new-user profile has no committed session to migrate: the positive proof is then an
        // initialized, readable, empty official store (never a fabricated session file).
        val expectSessions = active.sourceSessions > 0
        val session = DshTrialSessionReader.probe(active.roots.home, expectSessions)
        val detail = buildString {
            append(if (alive) "trial-host-alive" else "trial-host-exited")
            append(if (alive && active.hostReady) ",surface-verified" else ",surface-unproven")
            append(if (home.readable) ",home-readable" else ",home-unreadable")
            append(if (session.verified) ",session-container-verified" else ",session-reader-unproven")
            append(if (session.storeReady) ",session-store-initialized" else ",session-store-missing")
            append(if (!expectSessions) ",new-user-profile" else ",migrated-profile")
            if (!active.tmpWritable) append(",tmp-not-writable")
            append("; ").append(home.detail)
            append("; ").append(session.detail)
            append("; ").append(active.dataSeedDetail)
        }.take(MAX_DETAIL_CHARS)
        return DshTrialHealthSnapshot(
            hostReady = active.hostReady && alive,
            homeReadable = home.readable,
            sessionReaderReady = session.verified,
            sessionStoreInitialized = session.storeReady,
            newUserProfile = !expectSessions,
            detail = detail
        )
    }

    // ------------------------------------------------------------------ isolated home

    /**
     * Seeds the isolated trial home from the verified official template with the **account-only**
     * overlay, then copies the data half into it.
     *
     * No MCA gateway is bound and no credential is copied: the trial must never be able to
     * authenticate, replay a turn or claim a provider. The seeder writes its sibling marker below
     * the trial data root, so the production home-state record is untouched. The data-half copy is
     * read-only on the real home and bounded (see [DshTrialDataSeed]); its path-free description is
     * reported through [onDataSeeded] for the trial health detail.
     */
    private suspend fun seedTrialHome(
        trialConfig: DshRuntimeConfig,
        trialHome: File,
        onDataSeeded: (DshTrialDataSeed.Result) -> Unit
    ): DshSeedAttempt = withContext(ioDispatcher) {
        when (val outcome = DshGatewayConfigSeeder(trialConfig).seedAccountOnly()) {
            DshSeedOutcome.Seeded -> {
                val dataSeed = DshTrialDataSeed.seed(config.homeDir, trialHome)
                onDataSeeded(dataSeed)
                log("dsh: trial: ${dataSeed.detail}")
                DshSeedAttempt.Seeded
            }

            is DshSeedOutcome.Failed -> {
                log("dsh: trial: home seed refused code=${outcome.code.name} detail=${outcome.detail}")
                DshSeedAttempt.Refused(
                    message = "the isolated trial home could not be seeded from the verified official template",
                    diagnostics = outcome.code.name
                )
            }
        }
    }

    /** Isolated child environment of the trial generation: only its own roots, no token, no bridge. */
    private fun trialEnvironment(trialConfig: DshRuntimeConfig): Map<String, String> =
        DshNodeCommand.environment(
            homeDir = trialConfig.homeDir,
            configDir = trialConfig.configDir,
            workspaceDir = trialConfig.workspaceDir,
            tmpDir = trialConfig.tmpDir,
            payloadDir = trialConfig.payloadDir,
            runtimeLibraryDir = trialConfig.runtimeLibraryDir(),
            payloadBinDir = trialConfig.payloadBinDir()
        )

    /**
     * Positive write/read/delete observation in the isolated TMPDIR. A trial whose TMPDIR is not
     * usable cannot run official tool children, so the fact is recorded and reported by health.
     */
    private suspend fun probeTmpWritable(tmp: File, generation: Long): Boolean = withContext(ioDispatcher) {
        runCatching {
            if (!tmp.isDirectory && !tmp.mkdirs() && !tmp.isDirectory) return@runCatching false
            val probe = File(tmp, "trial-probe-$generation-${clock()}.tmp")
            probe.writeText("mca-trial-probe")
            val readBack = probe.readText()
            probe.delete()
            readBack == "mca-trial-probe"
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ helpers

    private fun isSha256(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun trialRefused(reason: String): DshTrialSnapshot =
        DshTrialSnapshot(phase = DshTrialPhase.STOPPED, detail = reason.take(MAX_DETAIL_CHARS))

    private fun trialFailed(detail: String): DshTrialSnapshot =
        DshTrialSnapshot(phase = DshTrialPhase.FAILED, detail = detail.take(MAX_DETAIL_CHARS))

    private fun trialUnknown(
        detail: String,
        generation: Long = 0L,
        pid: Long = 0L
    ): DshTrialSnapshot = DshTrialSnapshot(
        phase = DshTrialPhase.UNKNOWN,
        pid = pid,
        generation = generation,
        detail = detail.take(MAX_DETAIL_CHARS)
    )

    /** Releases a journaled trial with no in-memory marker (fresh owner after a process death). */
    private suspend fun releaseJournaledTrial(): DshTrialSnapshot {
        val record = journal.read()
        if (record != null && record.unreadable) {
            return trialUnknown("the trial journal is unreadable; the trial state cannot be verified")
        }
        if (record == null || !record.mayHoldProcess) {
            // Nothing may be running; an empty same-UID payload scan is the positive proof.
            val scanned = fence.scanOrNull()
                ?: return trialUnknown("the trial state cannot be verified: survivor scan unavailable")
            if (scanned.isEmpty()) {
                fence.clear()
                log("dsh: trial: no trial generation to release")
                return DshTrialSnapshot(
                    phase = DshTrialPhase.STOPPED,
                    detail = "no isolated trial generation is running"
                )
            }
            return trialUnknown("same-UID DSH payload processes exist without a trial journal")
        }
        return when (val verdict = recovery.release(record)) {
            is DshTrialStopPolicy.Verdict.Stopped -> {
                fence.clear()
                log("dsh: trial: recovered generation released")
                DshTrialSnapshot(
                    phase = DshTrialPhase.STOPPED,
                    generation = record.generation,
                    detail = "the recovered isolated trial generation was released"
                )
            }

            is DshTrialStopPolicy.Verdict.Survived -> {
                fence.retain(verdict.pids)
                log("dsh: trial: recovered generation survived pids=${verdict.pids}")
                DshTrialSnapshot(
                    phase = DshTrialPhase.FAILED,
                    pid = verdict.pids.firstOrNull()?.toLong() ?: 0L,
                    generation = record.generation,
                    detail = "the isolated trial processes survived the stop pids=" +
                        verdict.pids.take(MAX_LISTED_PIDS).joinToString(",")
                )
            }

            is DshTrialStopPolicy.Verdict.Unverified -> DshTrialSnapshot(
                phase = DshTrialPhase.UNKNOWN,
                pid = record.pid,
                generation = record.generation,
                detail = ("the isolated trial stop cannot be verified: " + verdict.detail)
                    .take(MAX_DETAIL_CHARS)
            )
        }
    }

    private companion object {
        /** Bound of every path-free trial detail that crosses Binder. */
        const val MAX_DETAIL_CHARS = 200

        /** How many survivor PIDs one trial detail may name. */
        const val MAX_LISTED_PIDS = 8
    }
}
