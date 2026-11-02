package com.muyuchat.core.dsh.maintenance

import java.io.File
import org.json.JSONObject

/**
 * Isolated closure trial host (D24) above the single managed runtime.
 *
 * The production host is owned by the runtime owner in the dedicated `:dsh` process. A trial must
 * be the *same* owner's non-publishing generation: launching Node from this (app) process would
 * create a second host owner and bypass the stop fence / orphan scan. This class therefore keeps
 * the arbitration, the durable trial journal, the stop proof and the commit/rollback boundaries,
 * and delegates the actual launch to a narrow [DshTrialLaunchPort] that the runtime owner supplies
 * (see the handoff doc for the exact runtime patch). When that port is not connected, [startTrial]
 * fails with a typed status instead of pretending to run a trial.
 *
 * Invariants:
 * - production and trial are strictly serial, in both directions;
 * - `LAUNCHING` is journaled before the port is called, so a crash can always prove whether a
 *   trial may exist;
 * - `stopTrial` reports STOPPED only from a positive stop proof; `Survived`/`Unverified` become
 *   `RECOVERY_REQUIRED` and keep the journal for the rescue surface;
 * - the trial home/workspace/tmp live under `dataRoot/trial`, never under the real home.
 */

/** Typed outcome of a trial launch attempt; never optimistic. */
sealed interface DshTrialLaunchResult {
    data class Started(val pid: Long, val generation: Long = 0L, val detail: String = "") : DshTrialLaunchResult
    data class Refused(val reason: String) : DshTrialLaunchResult
    data class Failed(val detail: String) : DshTrialLaunchResult
}

/** What the runtime-side trial generation knows about itself; `running = null` means unknown. */
data class DshTrialRuntimeStatus(val running: Boolean?, val pid: Long = 0L, val detail: String = "")

/** Positive stop proof. Only [Stopped] means the process and its writable child tree are gone. */
sealed interface DshTrialStopProof {
    data object Stopped : DshTrialStopProof
    data class Survived(val pids: List<Int>) : DshTrialStopProof
    data class Unverified(val detail: String) : DshTrialStopProof
}

/** One isolated trial launch request. All roots are app-private and already validated here. */
data class DshTrialLaunchRequest(
    val candidateRoot: File,
    val candidateFingerprint: String,
    val homeRoot: File,
    val workspaceRoot: File,
    val tmpRoot: File,
    val activeJournal: File
)

/**
 * Platform seam owned by the runtime owner (`:dsh` process). Implementations must reuse the one
 * host slot and must never publish the trial generation as the production READY generation.
 */
interface DshTrialLaunchPort {
    fun launch(request: DshTrialLaunchRequest): DshTrialLaunchResult

    /** Read-only trial smoke: host ready, isolated home readable, official session reader up. */
    fun probeHealth(): DshHostHealth

    fun stopAndProve(timeoutMillis: Long): DshTrialStopProof

    fun status(): DshTrialRuntimeStatus
}

/** Outcome of the startup recovery of an interrupted trial. */
sealed interface DshTrialRecovery {
    data object None : DshTrialRecovery
    data class Recovered(val detail: String) : DshTrialRecovery
    data class RequiresAttention(val detail: String) : DshTrialRecovery
}

enum class DshTrialPhase { LAUNCHING, RUNNING, STOPPING, STOPPED, FAILED, RECOVERY_REQUIRED }

data class DshTrialActiveRecord(
    val phase: DshTrialPhase,
    val candidateId: String,
    val candidateFingerprint: String,
    val candidateRoot: String,
    val homeRoot: String,
    val workspaceRoot: String,
    val tmpRoot: String,
    val pid: Long = 0L,
    val generation: Long = 0L,
    val startedAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
    val detail: String = ""
) {
    val mayHoldProcess: Boolean
        get() = phase == DshTrialPhase.LAUNCHING || phase == DshTrialPhase.RUNNING ||
            phase == DshTrialPhase.STOPPING || phase == DshTrialPhase.RECOVERY_REQUIRED
}

/** Durable `trial/active.json`; a missing file is positive evidence that nothing was launched. */
class DshTrialActiveStore(private val file: File) {
    fun read(): DshTrialActiveRecord? {
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(DshMaintenanceIo.readText(file))
            DshTrialActiveRecord(
                phase = DshTrialPhase.entries.firstOrNull { it.name == json.optString("phase") }
                    ?: DshTrialPhase.RECOVERY_REQUIRED,
                candidateId = json.optString("candidateId"),
                candidateFingerprint = json.optString("candidateFingerprint"),
                candidateRoot = json.optString("candidateRoot"),
                homeRoot = json.optString("homeRoot"),
                workspaceRoot = json.optString("workspaceRoot"),
                tmpRoot = json.optString("tmpRoot"),
                pid = json.optLong("pid", 0L),
                generation = json.optLong("generation", 0L),
                startedAtMillis = json.optLong("startedAt", 0L),
                updatedAtMillis = json.optLong("updatedAt", 0L),
                detail = json.optString("detail")
            )
        }.getOrElse {
            // An unreadable journal must never be treated as "no trial": fail closed.
            DshTrialActiveRecord(
                phase = DshTrialPhase.RECOVERY_REQUIRED,
                candidateId = "",
                candidateFingerprint = "",
                candidateRoot = "",
                homeRoot = "",
                workspaceRoot = "",
                tmpRoot = "",
                detail = "trial journal is unreadable"
            )
        }
    }

    fun write(record: DshTrialActiveRecord) {
        val json = JSONObject()
            .put("phase", record.phase.name)
            .put("candidateId", record.candidateId)
            .put("candidateFingerprint", record.candidateFingerprint)
            .put("candidateRoot", record.candidateRoot)
            .put("homeRoot", record.homeRoot)
            .put("workspaceRoot", record.workspaceRoot)
            .put("tmpRoot", record.tmpRoot)
            .put("pid", record.pid)
            .put("generation", record.generation)
            .put("startedAt", record.startedAtMillis)
            .put("updatedAt", record.updatedAtMillis)
            .put("detail", record.detail.take(300))
        DshMaintenanceIo.writeDurably(file, json.toString())
    }

    fun clear() {
        if (file.exists() && !file.delete()) DshMaintenanceIo.writeDurably(file, "{}")
    }
}

/**
 * [DshHostLifecyclePort] that adds exactly one isolated trial generation to the managed host slot.
 *
 * Every operation is serialized on one lock, so a concurrent console/maintenance call can never
 * interleave a trial launch with a production start.
 */
class DshClosureTrialHost(
    private val production: DshHostLifecyclePort,
    private val trialPort: () -> DshTrialLaunchPort?,
    dataRoot: File,
    trialRoot: File = File(dataRoot, "trial"),
    private val clock: () -> Long = System::currentTimeMillis,
    private val defaultStopTimeoutMillis: Long = 30_000L,
    /**
     * Cross-process stop proof: does any live process still reference this candidate root?
     *
     * The launcher cannot prove a trial stopped after the app process died (a fresh process owns no
     * trial generation), so recovery asks the platform instead. Conservative default: assume alive.
     */
    private val candidateProcessAlive: (File) -> Boolean = { true }
) : DshHostLifecyclePort {

    private val lock = Any()
    private val trialRoot: File = trialRoot
    private val store = DshTrialActiveStore(File(trialRoot, "active.json"))
    private val homeRoot: File = File(trialRoot, "home")
    private val workspaceRoot: File = File(trialRoot, "workspace")
    private val tmpRoot: File = File(trialRoot, "tmp")

    /** Staged candidates live here; the runtime's own trial roots require the same containment. */
    private val closureRoot: File = File(dataRoot, "closure")

    init {
        require(dataRoot.isAbsolute) { "dsh: trial host data root must be absolute" }
        require(DshMaintenanceIo.relativeWithin(dataRoot, trialRoot) != null) {
            "dsh: the trial root must live below the managed data root"
        }
        require(DshMaintenanceIo.relativeWithin(dataRoot, homeRoot) != null) {
            "dsh: the trial home must live below the trial root"
        }
    }

    override fun status(): DshHostStatus = synchronized(lock) {
        val record = store.read()
        if (record != null && record.mayHoldProcess) {
            val state = when (record.phase) {
                DshTrialPhase.RUNNING -> DshHostState.RUNNING
                DshTrialPhase.LAUNCHING, DshTrialPhase.STOPPING -> DshHostState.UNKNOWN
                else -> DshHostState.FAILED
            }
            return DshHostStatus(state, generation = record.generation, detail = "trial ${record.phase.name}")
        }
        val runtime = trialPort()
        if (runtime != null &&
            (record == null || record.phase == DshTrialPhase.STOPPED || record.phase == DshTrialPhase.FAILED)
        ) {
            val reported = runCatching { runtime.status() }.getOrNull()
            when (reported?.running) {
                true -> return DshHostStatus(
                    DshHostState.RUNNING,
                    generation = reported.pid,
                    detail = "a trial host is alive without an active journal"
                )

                false -> Unit
                null -> {
                    // The journal is terminal (STOPPED/FAILED), so any live trial would have had to
                    // journal a new LAUNCHING first. A serving production host is therefore the
                    // answer: it may itself be running from the candidate root, which makes the
                    // crash-proof root scan inconclusive after a start.
                    val productionStatus = production.status()
                    if (productionStatus.state == DshHostState.RUNNING) {
                        return productionStatus
                    }
                    // Nothing is serving: a dead candidate root proves no old trial survives; a
                    // live process with a stopped journal contradicts it and stays unknown.
                    val root = record?.candidateRoot?.takeIf { it.isNotBlank() }?.let(::File)
                    if (root == null || candidateProcessAlive(root)) {
                        return DshHostStatus(
                            DshHostState.UNKNOWN,
                            detail = "trial state cannot be verified"
                        )
                    }
                }
            }
        }
        production.status()
    }

    override fun stopAndConfirm(timeoutMillis: Long): DshHostStatus {
        stopTrial(timeoutMillis)
        return production.stopAndConfirm(timeoutMillis)
    }

    override fun startTrial(candidateRoot: File, candidateFingerprint: String): DshHostStatus =
        synchronized(lock) {
            val runtimeStatus = production.status()
            if (runtimeStatus.state != DshHostState.STOPPED) {
                return DshHostStatus(
                    DshHostState.FAILED,
                    generation = runtimeStatus.generation,
                    detail = "the production host is not confirmed stopped; trial refused"
                )
            }
            val candidateProblem = candidateRefusal(candidateRoot)
            if (candidateProblem != null) {
                return DshHostStatus(DshHostState.FAILED, detail = candidateProblem)
            }
            if (!isSha256(candidateFingerprint)) {
                return DshHostStatus(DshHostState.FAILED, detail = "candidate fingerprint is not sha256")
            }
            val existing = store.read()
            // Recovery is checked before "already active": a RECOVERY_REQUIRED journal may have no
            // live process, and reporting RUNNING would pretend a trial exists that must be rescued.
            if (existing != null && existing.phase == DshTrialPhase.RECOVERY_REQUIRED) {
                return DshHostStatus(
                    DshHostState.FAILED,
                    detail = "the previous trial still needs recovery; run the startup sweep first"
                )
            }
            if (existing != null && existing.mayHoldProcess) {
                return DshHostStatus(
                    DshHostState.RUNNING,
                    generation = existing.generation,
                    detail = "a trial generation is already active (${existing.phase.name})"
                )
            }
            val runtime = trialPort()
                ?: return DshHostStatus(
                    DshHostState.FAILED,
                    detail = "the isolated trial launcher is not connected to the managed runtime owner"
                )
            val dirsReady = listOf(homeRoot, workspaceRoot, tmpRoot).all { dir ->
                dir.isDirectory || dir.mkdirs() || dir.isDirectory
            }
            if (!dirsReady) {
                return DshHostStatus(DshHostState.FAILED, detail = "the isolated trial roots could not be created")
            }
            val launching = DshTrialActiveRecord(
                phase = DshTrialPhase.LAUNCHING,
                candidateId = candidateRoot.name,
                candidateFingerprint = candidateFingerprint,
                candidateRoot = candidateRoot.absolutePath,
                homeRoot = homeRoot.absolutePath,
                workspaceRoot = workspaceRoot.absolutePath,
                tmpRoot = tmpRoot.absolutePath,
                startedAtMillis = clock(),
                updatedAtMillis = clock()
            )
            store.write(launching)
            val request = DshTrialLaunchRequest(
                candidateRoot = candidateRoot,
                candidateFingerprint = candidateFingerprint,
                homeRoot = homeRoot,
                workspaceRoot = workspaceRoot,
                tmpRoot = tmpRoot,
                activeJournal = File(trialRoot, "active.json")
            )
            val result = runCatching { runtime.launch(request) }.getOrElse { failure ->
                DshTrialLaunchResult.Failed("trial launch threw ${failure.javaClass.simpleName}")
            }
            return when (result) {
                is DshTrialLaunchResult.Started -> {
                    store.write(
                        launching.copy(
                            phase = DshTrialPhase.RUNNING,
                            pid = result.pid,
                            generation = result.generation,
                            updatedAtMillis = clock(),
                            detail = result.detail.take(300)
                        )
                    )
                    DshHostStatus(
                        DshHostState.RUNNING,
                        generation = result.generation,
                        detail = "trial generation started"
                    )
                }

                is DshTrialLaunchResult.Refused -> {
                    store.write(
                        launching.copy(
                            phase = DshTrialPhase.STOPPED,
                            updatedAtMillis = clock(),
                            detail = "refused: ${result.reason}".take(300)
                        )
                    )
                    DshHostStatus(DshHostState.STOPPED, detail = "trial refused")
                }

                is DshTrialLaunchResult.Failed -> {
                    store.write(
                        launching.copy(
                            phase = DshTrialPhase.FAILED,
                            updatedAtMillis = clock(),
                            detail = result.detail.take(300)
                        )
                    )
                    DshHostStatus(DshHostState.FAILED, detail = result.detail.take(200))
                }
            }
        }

    override fun probeTrialHealth(): DshHostHealth {
        val record = synchronized(lock) { store.read() }
        if (record == null || (record.phase != DshTrialPhase.RUNNING && record.phase != DshTrialPhase.LAUNCHING)) {
            return DshHostHealth(detail = "no trial generation is active")
        }
        val runtime = trialPort() ?: return DshHostHealth(detail = "the trial launcher is not connected")
        val reported = runCatching { runtime.probeHealth() }.getOrElse { failure ->
            return DshHostHealth(detail = "trial health probe failed: ${failure.javaClass.simpleName}")
        }
        val homeReadable = homeRoot.isDirectory && homeRoot.canRead()
        return DshHostHealth(
            hostReady = reported.hostReady,
            homeReadable = reported.homeReadable && homeReadable,
            // The trial serves an isolated home with no real account by design: auth/provider are
            // reported false instead of copying production credentials into the trial.
            sessionReaderReady = reported.sessionReaderReady,
            sessionStoreInitialized = reported.sessionStoreInitialized,
            newUserProfile = reported.newUserProfile,
            providerConfigured = false,
            authReady = false,
            detail = buildString {
                append("trial ")
                append(if (reported.hostReady) "host-ready" else "host-not-ready")
                append(if (reported.homeReadable && homeReadable) ",home-readable" else ",home-unreadable")
                append(if (reported.sessionReaderReady) ",session-reader-ready" else ",session-reader-unavailable")
                append(if (reported.sessionStoreInitialized) ",session-store-initialized" else ",session-store-missing")
                append(if (reported.newUserProfile) ",new-user-profile" else ",migrated-profile")
                if (reported.detail.isNotBlank()) append("; ").append(reported.detail.take(120))
            }.take(240)
        )
    }

    override fun stopTrial(timeoutMillis: Long): DshHostStatus = synchronized(lock) {
        stopTrialLocked(timeoutMillis)
    }

    private fun stopTrialLocked(timeoutMillis: Long): DshHostStatus {
        val record = store.read()
        if (record == null || record.phase == DshTrialPhase.STOPPED) {
            val runtime = trialPort()
                ?: return DshHostStatus(DshHostState.STOPPED, detail = "no trial host was ever launched")
            val reported = runCatching { runtime.status() }.getOrNull()
                ?: return DshHostStatus(DshHostState.UNKNOWN, detail = "trial state cannot be verified")
            if (reported.running == false) {
                return DshHostStatus(DshHostState.STOPPED, detail = "no trial host")
            }
            if (reported.running == null) {
                // A process that never launched the trial cannot see the previous generation, so its
                // "unknown" is not evidence of a survivor. A journal already recorded STOPPED plus a
                // candidate-root scan uses the same proof standard as a proven release; a process
                // still referencing that root contradicts the journal and stays unknown.
                val root = record?.candidateRoot?.takeIf { it.isNotBlank() }?.let(::File)
                if (root != null && !candidateProcessAlive(root)) {
                    return DshHostStatus(
                        DshHostState.STOPPED,
                        detail = "journal stopped and no process references the candidate root"
                    )
                }
                return DshHostStatus(DshHostState.UNKNOWN, detail = "trial state is unknown")
            }
            // A process exists without a journal; the port still owns the stop proof.
            return releaseTrial(runtime, timeoutMillis, record)
        }
        if (record.phase == DshTrialPhase.FAILED) {
            val runtime = trialPort()
                ?: return DshHostStatus(DshHostState.STOPPED, detail = "the failed trial never started")
            return releaseTrial(runtime, timeoutMillis, record)
        }
        val runtime = trialPort()
            ?: return requireRecovery(record, "the trial launcher disappeared; the process cannot be released")
        return releaseTrial(runtime, timeoutMillis, record)
    }

    private fun releaseTrial(
        runtime: DshTrialLaunchPort,
        timeoutMillis: Long,
        record: DshTrialActiveRecord?
    ): DshHostStatus {
        val base = record ?: DshTrialActiveRecord(
            phase = DshTrialPhase.STOPPING,
            candidateId = "",
            candidateFingerprint = "",
            candidateRoot = "",
            homeRoot = homeRoot.absolutePath,
            workspaceRoot = workspaceRoot.absolutePath,
            tmpRoot = tmpRoot.absolutePath,
            updatedAtMillis = clock()
        )
        store.write(base.copy(phase = DshTrialPhase.STOPPING, updatedAtMillis = clock()))
        val proof = runCatching { runtime.stopAndProve(timeoutMillis) }.getOrNull()
            ?: return requireRecovery(base, "the trial stop proof failed")
        return when (proof) {
            is DshTrialStopProof.Stopped -> {
                store.write(
                    base.copy(
                        phase = DshTrialPhase.STOPPED,
                        pid = 0L,
                        updatedAtMillis = clock(),
                        detail = "trial released"
                    )
                )
                DshHostStatus(DshHostState.STOPPED, detail = "trial host released")
            }

            is DshTrialStopProof.Survived -> requireRecovery(
                base,
                "the trial process survived its stop: pids=" + proof.pids.take(8).joinToString(",")
            )

            is DshTrialStopProof.Unverified -> {
                val root = record?.candidateRoot?.takeIf { it.isNotBlank() }?.let(::File)
                if (root != null && !candidateProcessAlive(root)) {
                    store.write(
                        base.copy(
                            phase = DshTrialPhase.STOPPED,
                            pid = 0L,
                            updatedAtMillis = clock(),
                            detail = "trial released: no process references the candidate root"
                        )
                    )
                    DshHostStatus(DshHostState.STOPPED, detail = "trial host released (candidate scan)")
                } else {
                    requireRecovery(base, "the trial stop cannot be verified: " + proof.detail.take(160))
                }
            }
        }
    }

    private fun requireRecovery(record: DshTrialActiveRecord, detail: String): DshHostStatus {
        store.write(
            record.copy(
                phase = DshTrialPhase.RECOVERY_REQUIRED,
                updatedAtMillis = clock(),
                detail = detail.take(300)
            )
        )
        return DshHostStatus(DshHostState.FAILED, detail = detail.take(200))
    }

    override fun startProduction(activeRoot: File, signature: String): DshHostStatus {
        val released = stopTrial(defaultStopTimeoutMillis)
        if (released.state != DshHostState.STOPPED) {
            return DshHostStatus(
                DshHostState.FAILED,
                generation = released.generation,
                detail = "the trial host is not confirmed released; production must not start"
            )
        }
        return production.startProduction(activeRoot, signature)
    }

    override fun probeReadOnlyHealth(): DshHostHealth = production.probeReadOnlyHealth()

    /**
     * Startup recovery: releases a trial left behind by a crash before the upgrade resume or any
     * production start runs. Never starts Node.
     */
    override fun recoverInterruptedTrial(): DshTrialRecovery = synchronized(lock) {
        val record = store.read() ?: return DshTrialRecovery.None
        if (record.phase == DshTrialPhase.STOPPED || record.phase == DshTrialPhase.FAILED) {
            return DshTrialRecovery.None
        }
        val status = stopTrialLocked(defaultStopTimeoutMillis)
        return if (status.state == DshHostState.STOPPED) {
            DshTrialRecovery.Recovered("interrupted trial released")
        } else {
            DshTrialRecovery.RequiresAttention(
                "an interrupted trial could not be released: " + status.detail.take(160)
            )
        }
    }

    /** Current trial journal for read-only console projections (never mutates it). */
    fun activeTrial(): DshTrialActiveRecord? = synchronized(lock) { store.read() }

    private fun isSha256(value: String): Boolean = DshMaintenanceIo.isSha256Hex(value)

    /**
     * Early, typed refusal of a root that can never be a candidate. The runtime owner's own
     * `DshTrialRoots` validation remains authoritative; this check keeps an invalid candidate from
     * crossing the process boundary and journaling a `LAUNCHING` attempt it cannot honour.
     *
     * Only containment and manifest identity are checked here. Component hashes stay the
     * responsibility of [DshClosureCandidateSource] / [DshUpgradeCandidate.identity], so the trial
     * host does not become a second, divergent candidate verifier.
     */
    private fun candidateRefusal(candidateRoot: File): String? {
        if (!candidateRoot.isAbsolute || !candidateRoot.isDirectory) {
            return "the candidate staging tree is absent"
        }
        if (DshMaintenanceIo.relativeWithin(closureRoot, candidateRoot) == null) {
            return "the candidate staging tree is outside the managed closure root"
        }
        if (java.nio.file.Files.isSymbolicLink(candidateRoot.toPath())) {
            return "the candidate staging tree is a symbolic link"
        }
        val manifest = File(candidateRoot, MANIFEST_NAME)
        if (!manifest.isFile || manifest.length() <= 0L || manifest.length() > MAX_MANIFEST_BYTES) {
            return "the candidate staging tree carries no readable candidate manifest"
        }
        val json = runCatching { JSONObject(DshMaintenanceIo.readText(manifest)) }.getOrNull()
            ?: return "the candidate manifest is not valid JSON"
        if (json.optString("candidateId", "") != candidateRoot.name) {
            return "the candidate id does not match its staging directory"
        }
        return null
    }

    private companion object {
        const val MANIFEST_NAME = "candidate.json"
        const val MAX_MANIFEST_BYTES = 64L * 1024L
    }
}
