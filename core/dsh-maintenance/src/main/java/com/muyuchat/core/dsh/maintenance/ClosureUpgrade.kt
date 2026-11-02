package com.muyuchat.core.dsh.maintenance

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Whole-closure upgrade with a serial trial and explicit rollback boundary (D24).
 *
 * Candidate identity covers source, payload, Node, native ABI, addons, dependencies, Android/MCA
 * patches, profile schema and the managed plugin lock -"JS version only" is not an identity.
 * Order is fixed: verify ->stop confirmation (one managed host, ever) ->isolated data trial with
 * no real account/user plugin/external side effect ->real-home precheck + backup ->selector
 * switch ->production read-only health pending ->commit.
 *
 * Trial and production share the same lease, so two hosts can never run at once. A crash leaves
 * the selector plus the upgrade state file readable: `resume()` finishes a health-pending commit
 * or returns to the previous closure; when the new APK replaced nativeLibraryDir without a
 * verified slot/ABI compatibility it reports RECOVERY_REQUIRED instead of pretending a rollback.
 */

val DSH_CLOSURE_COMPONENTS: Set<String> = setOf(
    "source", "payload", "node", "nativeAbi", "addons",
    "dependencies", "androidPatch", "mcaPatch", "profileSchema", "pluginLock"
)

data class DshClosureIdentity(val components: Map<String, String>) {
    val fingerprint: String = DshMaintenanceIo.sha256(
        components.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value}" }
            .toByteArray(Charsets.UTF_8)
    )

    fun missingComponents(): List<String> =
        DSH_CLOSURE_COMPONENTS.filter { (components[it] ?: "").isEmpty() }

    fun invalidHashes(): List<String> =
        components.filterValues { !DshMaintenanceIo.isSha256Hex(it) }.keys.toList()
}

data class DshUpgradeCandidate(
    val candidateId: String,
    val stagedRoot: File,
    /** Component name -> real staged artifact; hashes are computed here, not trusted. */
    val artifacts: Map<String, File>,
    val targetAbi: String,
    val dataSchemaVersion: Int,
    val minReadableDataSchema: Int,
    val migratesData: Boolean,
    val nativeLibraryReplaced: Boolean,
    val prePackagedSlotVerified: Boolean,
    val abiCompatibleWithPrevious: Boolean,
    /**
     * Real executable payload tree of this candidate (not the installed one). Null means the
     * candidate is identity-only; the manager refuses it before any trial could run.
     */
    val payloadRoot: File? = null,
    /** Manifest kernel entry relative to [payloadRoot]. */
    val payloadKernelEntry: String = "",
    /** Declared critical file hashes (relative path -> sha256) verified against the real bytes. */
    val payloadCriticalFiles: Map<String, String> = emptyMap(),
    val payloadArchiveSha256: String = "",
    val payloadFileCount: Long = 0L
) {
    fun identity(): DshClosureIdentity {
        val components = LinkedHashMap<String, String>()
        for (name in DSH_CLOSURE_COMPONENTS) {
            if (name == PAYLOAD_COMPONENT && payloadRoot != null) {
                // Bind the closure identity to the payload actually launched, not to a descriptor.
                components[name] = payloadDigest()
                continue
            }
            val file = artifacts[name] ?: continue
            if (file.isFile) components[name] = DshMaintenanceIo.sha256(file)
        }
        return DshClosureIdentity(components)
    }

    /** Deterministic digest of the executable payload identity this candidate would launch. */
    fun payloadDigest(): String {
        val lines = ArrayList<String>()
        lines += "archive:$payloadArchiveSha256"
        lines += "kernel:$payloadKernelEntry"
        payloadCriticalFiles.toSortedMap().forEach { (path, sha) -> lines += "$path:$sha" }
        return DshMaintenanceIo.sha256(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val PAYLOAD_COMPONENT = "payload"
        /** Directory (below the staged candidate root) that carries the executable payload. */
        const val PAYLOAD_DIR_NAME = "payload"
    }
}

data class DshClosureSelector(
    val fingerprint: String,
    val root: String,
    val dataSchemaVersion: Int,
    val committedAtMillis: Long,
    val previousFingerprint: String = "",
    val previousRoot: String = "",
    /** Active closure that was last proven healthy; survives later rollbacks for audit. */
    val lastGoodFingerprint: String = "",
    val lastGoodRoot: String = "",
    /** Identity of the payload bundled with the installed APK (from its manifest marker). */
    val installedPayloadVersion: String = "",
    val installedArchiveSha256: String = ""
)

/** Installed-build payload identity, supplied by the app composition from its own manifest. */
data class DshInstalledPayloadIdentity(
    val payloadVersion: String = "",
    val archiveSha256: String = ""
)

class DshClosureSelectorStore(private val file: File) {
    fun read(): DshClosureSelector? {
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(DshMaintenanceIo.readText(file))
            DshClosureSelector(
                fingerprint = json.optString("fingerprint"),
                root = json.optString("root"),
                dataSchemaVersion = json.optInt("dataSchemaVersion"),
                committedAtMillis = json.optLong("committedAt"),
                previousFingerprint = json.optString("previousFingerprint"),
                previousRoot = json.optString("previousRoot"),
                lastGoodFingerprint = json.optString("lastGoodFingerprint"),
                lastGoodRoot = json.optString("lastGoodRoot"),
                installedPayloadVersion = json.optString("installedPayloadVersion"),
                installedArchiveSha256 = json.optString("installedArchiveSha256")
            )
        }.getOrNull()
    }

    fun write(selector: DshClosureSelector) {
        val json = JSONObject()
        json.put("fingerprint", selector.fingerprint)
        json.put("root", selector.root)
        json.put("dataSchemaVersion", selector.dataSchemaVersion)
        json.put("committedAt", selector.committedAtMillis)
        json.put("previousFingerprint", selector.previousFingerprint)
        json.put("previousRoot", selector.previousRoot)
        json.put("lastGoodFingerprint", selector.lastGoodFingerprint)
        json.put("lastGoodRoot", selector.lastGoodRoot)
        json.put("installedPayloadVersion", selector.installedPayloadVersion)
        json.put("installedArchiveSha256", selector.installedArchiveSha256)
        DshMaintenanceIo.writeDurably(file, json.toString())
    }

    /**
     * Removes the selector so the installed payload is the active closure again. Used by rollback
     * when the failed upgrade was the first commit (there is no previous closure to restore).
     */
    fun clear(): Boolean = runCatching {
        if (file.exists()) file.delete()
        !file.exists()
    }.getOrDefault(false)
}

enum class DshUpgradePhase {
    IDLE,
    VERIFIED,
    TRIAL_RUNNING,
    TRIAL_PASSED,
    APPLYING,
    HEALTH_PENDING,
    COMMITTED,
    ROLLED_BACK,
    RECOVERY_REQUIRED
}

data class DshUpgradeState(
    val phase: DshUpgradePhase = DshUpgradePhase.IDLE,
    val candidateId: String = "",
    val candidateFingerprint: String = "",
    val candidateRoot: String = "",
    val previousFingerprint: String = "",
    val previousRoot: String = "",
    val previousDataSchemaVersion: Int = 0,
    val updatedAtMillis: Long = 0L,
    val detail: String = ""
)

class DshUpgradeStateStore(private val file: File) {
    fun read(): DshUpgradeState {
        if (!file.isFile) return DshUpgradeState()
        return runCatching {
            val json = JSONObject(DshMaintenanceIo.readText(file))
            DshUpgradeState(
                phase = DshUpgradePhase.entries.firstOrNull { it.name == json.optString("phase") }
                    ?: DshUpgradePhase.IDLE,
                candidateId = json.optString("candidateId"),
                candidateFingerprint = json.optString("candidateFingerprint"),
                candidateRoot = json.optString("candidateRoot"),
                previousFingerprint = json.optString("previousFingerprint"),
                previousRoot = json.optString("previousRoot"),
                previousDataSchemaVersion = json.optInt("previousDataSchemaVersion"),
                updatedAtMillis = json.optLong("updatedAt"),
                detail = json.optString("detail")
            )
        }.getOrDefault(DshUpgradeState())
    }

    fun write(state: DshUpgradeState) {
        val json = JSONObject()
        json.put("phase", state.phase.name)
        json.put("candidateId", state.candidateId)
        json.put("candidateFingerprint", state.candidateFingerprint)
        json.put("candidateRoot", state.candidateRoot)
        json.put("previousFingerprint", state.previousFingerprint)
        json.put("previousRoot", state.previousRoot)
        json.put("previousDataSchemaVersion", state.previousDataSchemaVersion)
        json.put("updatedAt", state.updatedAtMillis)
        json.put("detail", state.detail.take(300))
        DshMaintenanceIo.writeDurably(file, json.toString())
    }
}

sealed interface DshUpgradeResult {
    data class Committed(
        val transactionId: String,
        val candidateId: String,
        val fingerprint: String
    ) : DshUpgradeResult

    data class TrialFailed(val failure: DshMaintenanceFailure) : DshUpgradeResult
    data class Rejected(val failure: DshMaintenanceFailure) : DshUpgradeResult
    data class RolledBack(val failure: DshMaintenanceFailure) : DshUpgradeResult
    data class RecoveryRequired(val failure: DshMaintenanceFailure) : DshUpgradeResult
    data class NoChange(val reason: String) : DshUpgradeResult
}

/**
 * Closure upgrade owner. [run] must be called while the DSH console proves maintenance
 * quiescence; the manager additionally forces the managed host to STOPPED before any switch.
 */
class DshClosureUpgradeManager(
    private val engine: DshMaintenanceEngine,
    private val host: DshHostLifecyclePort,
    private val supportedAbis: Set<String>,
    private val selectorStore: DshClosureSelectorStore =
        DshClosureSelectorStore(File(engine.dataRootDirectory, "closure/selector.json")),
    private val stateStore: DshUpgradeStateStore =
        DshUpgradeStateStore(File(engine.dataRootDirectory, "maintenance/upgrade.json")),
    private val clock: () -> Long = System::currentTimeMillis,
    private val stopTimeoutMillis: Long = 30_000L,
    /** Installed APK payload identity; persisted with every selector write for audit. */
    private val installedIdentity: () -> DshInstalledPayloadIdentity = { DshInstalledPayloadIdentity() }
) {
    fun state(): DshUpgradeState = stateStore.read()

    fun selector(): DshClosureSelector? = selectorStore.read()

    /**
     * Runs the full upgrade. [realHomeBackup] is the app composition's backup callback; it must
     * complete a consistent backup of the real home before the selector switches. Returning a
     * failure aborts with the previous closure untouched.
     */
    fun run(
        candidate: DshUpgradeCandidate,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence,
        realHomeBackup: (() -> DshMaintenanceFailure?)? = null
    ): DshUpgradeResult {
        var result: DshUpgradeResult? = null
        val outcome = engine.execute(
            DshMaintenanceRequest(
                kind = DshMaintenanceKind.UPGRADE,
                requester = requester,
                quiescence = quiescence,
                targetRoot = engine.dataRootDirectory,
                steps = emptyList(),
                healthCheckRequired = false,
                detail = "upgrade=${candidate.candidateId}",
                body = {
                    val flowFailure = executeFlow(candidate, realHomeBackup) { result = it }
                    flowFailure
                }
            )
        )
        result?.let { return it }
        return when (outcome) {
            is DshMaintenanceOutcome.Committed -> DshUpgradeResult.NoChange(
                "upgrade flow returned no typed result"
            )

            is DshMaintenanceOutcome.Rejected -> DshUpgradeResult.Rejected(outcome.failure)
            is DshMaintenanceOutcome.RolledBack -> DshUpgradeResult.RolledBack(outcome.failure)
            is DshMaintenanceOutcome.RecoveryRequired -> DshUpgradeResult.RecoveryRequired(outcome.failure)
        }
    }

    /**
     * Startup completion of an interrupted upgrade. Must run after the journal sweep and before
     * the production host starts. Never starts a second host: it probes/rolls back only.
     */
    fun resume(requester: DshMaintenanceRequester, quiescence: DshQuiescenceEvidence): DshUpgradeResult {
        val state = stateStore.read()
        if (state.phase == DshUpgradePhase.IDLE || state.phase == DshUpgradePhase.COMMITTED) {
            return DshUpgradeResult.NoChange("upgrade state is stable (${state.phase.name})")
        }
        if (state.phase == DshUpgradePhase.ROLLED_BACK || state.phase == DshUpgradePhase.RECOVERY_REQUIRED) {
            return DshUpgradeResult.NoChange("upgrade state is terminal (${state.phase.name})")
        }
        var result: DshUpgradeResult? = null
        val outcome = engine.execute(
            DshMaintenanceRequest(
                kind = DshMaintenanceKind.UPGRADE,
                requester = requester,
                quiescence = quiescence,
                targetRoot = engine.dataRootDirectory,
                steps = emptyList(),
                healthCheckRequired = false,
                detail = "resume-upgrade=${state.phase.name}",
                body = {
                    resumeFlow(state) { result = it }
                    when (val captured = result) {
                        is DshUpgradeResult.RecoveryRequired -> captured.failure
                        is DshUpgradeResult.Rejected -> captured.failure
                        is DshUpgradeResult.RolledBack -> captured.failure
                        else -> null
                    }
                }
            )
        )
        result?.let { return it }
        return when (outcome) {
            is DshMaintenanceOutcome.Committed -> DshUpgradeResult.NoChange("upgrade resume completed")
            is DshMaintenanceOutcome.Rejected -> DshUpgradeResult.Rejected(outcome.failure)
            is DshMaintenanceOutcome.RolledBack -> DshUpgradeResult.RolledBack(outcome.failure)
            is DshMaintenanceOutcome.RecoveryRequired -> DshUpgradeResult.RecoveryRequired(outcome.failure)
        }
    }

    /**
     * Post-start confirmation of a [DshUpgradePhase.HEALTH_PENDING] upgrade. The runtime is now
     * serving the committed selector, so the read-only health probe decides the transaction
     * without starting anything. Healthy commits; unhealthy stops the candidate first (a mutation
     * transaction must begin from a confirmed-stopped host) and then runs the same selector revert
     * and previous-closure restore as any other failed apply.
     */
    fun confirmAfterStart(
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshUpgradeResult {
        val state = stateStore.read()
        if (state.phase != DshUpgradePhase.HEALTH_PENDING) {
            return DshUpgradeResult.NoChange("no upgrade is awaiting post-start confirmation")
        }
        val selector = selectorStore.read()
        if (selector == null || selector.fingerprint != state.candidateFingerprint) {
            return DshUpgradeResult.NoChange("the selector never switched to this candidate")
        }
        if (host.status().state != DshHostState.RUNNING) {
            return DshUpgradeResult.NoChange("the host is not serving the committed selector yet")
        }
        val health = host.probeReadOnlyHealth()
        if (health.healthy) {
            stateStore.write(state.copy(phase = DshUpgradePhase.COMMITTED, detail = "post-start-confirmed"))
            return DshUpgradeResult.Committed("", state.candidateId, state.candidateFingerprint)
        }
        val stopped = host.stopAndConfirm(stopTimeoutMillis)
        if (stopped.state != DshHostState.STOPPED) {
            val failure = DshMaintenanceFailure(
                DshMaintenanceError.RECOVERY_REQUIRED,
                "the unhealthy candidate is not confirmed stopped; the previous closure cannot be restored",
                retryable = true,
                detail = stopped.detail.take(200)
            )
            stateStore.write(state.copy(phase = DshUpgradePhase.RECOVERY_REQUIRED, detail = "post-start-unstopped"))
            return DshUpgradeResult.RecoveryRequired(failure)
        }
        var result: DshUpgradeResult? = null
        val outcome = engine.execute(
            DshMaintenanceRequest(
                kind = DshMaintenanceKind.UPGRADE,
                requester = requester,
                quiescence = quiescence,
                targetRoot = engine.dataRootDirectory,
                steps = emptyList(),
                healthCheckRequired = false,
                detail = "confirm-after-start",
                body = {
                    val failure = DshMaintenanceFailure(
                        DshMaintenanceError.HEALTH_FAILED,
                        "the candidate that served production is unhealthy; the previous closure was restored",
                        detail = health.detail.take(200)
                    )
                    val reverted = revertSelector(
                        previousFingerprint = state.previousFingerprint,
                        previousRoot = state.previousRoot,
                        previousSchema = state.previousDataSchemaVersion
                    )
                    val restoreFailure = if (reverted) {
                        restoreProductionAfterRollback(
                            previousFingerprint = state.previousFingerprint,
                            previousRoot = state.previousRoot
                        )
                    } else {
                        DshMaintenanceFailure(
                            DshMaintenanceError.RECOVERY_REQUIRED,
                            "the selector switch could not be reverted; data and evidence preserved"
                        )
                    }
                    if (restoreFailure == null) {
                        stateStore.write(state.copy(phase = DshUpgradePhase.ROLLED_BACK, detail = "post-start-health-failed"))
                        result = DshUpgradeResult.RolledBack(failure)
                        failure
                    } else {
                        stateStore.write(state.copy(phase = DshUpgradePhase.RECOVERY_REQUIRED, detail = "post-start"))
                        result = DshUpgradeResult.RecoveryRequired(restoreFailure)
                        restoreFailure
                    }
                }
            )
        )
        result?.let { return it }
        return when (outcome) {
            is DshMaintenanceOutcome.Committed -> DshUpgradeResult.NoChange("post-start confirmation completed")
            is DshMaintenanceOutcome.Rejected -> DshUpgradeResult.Rejected(outcome.failure)
            is DshMaintenanceOutcome.RolledBack -> DshUpgradeResult.RolledBack(outcome.failure)
            is DshMaintenanceOutcome.RecoveryRequired -> DshUpgradeResult.RecoveryRequired(outcome.failure)
        }
    }

    /**
     * Operator rescue for a [DshUpgradePhase.RECOVERY_REQUIRED] upgrade; without it that phase is
     * a permanent boot block with no in-app way out. It releases any surviving trial and records
     * ROLLED_BACK, leaving the selector (the serving closure) untouched. While the interrupted
     * trial cannot be proven stopped it refuses and stays in RECOVERY_REQUIRED.
     */
    fun abandon(requester: DshMaintenanceRequester, quiescence: DshQuiescenceEvidence): DshUpgradeResult {
        val state = stateStore.read()
        if (state.phase != DshUpgradePhase.RECOVERY_REQUIRED) {
            return DshUpgradeResult.NoChange("upgrade state is not recovery-required (${state.phase.name})")
        }
        var result: DshUpgradeResult? = null
        val outcome = engine.execute(
            DshMaintenanceRequest(
                kind = DshMaintenanceKind.UPGRADE,
                requester = requester,
                quiescence = quiescence,
                targetRoot = engine.dataRootDirectory,
                steps = emptyList(),
                healthCheckRequired = false,
                detail = "abandon-upgrade",
                body = {
                    val stopped = host.stopTrial(stopTimeoutMillis)
                    if (stopped.state != DshHostState.STOPPED) {
                        val failure = DshMaintenanceFailure(
                            DshMaintenanceError.RECOVERY_REQUIRED,
                            "the interrupted trial is not confirmed stopped; the pending upgrade stays recovery-required",
                            retryable = true,
                            detail = stopped.detail.take(200)
                        )
                        result = DshUpgradeResult.RecoveryRequired(failure)
                        failure
                    } else {
                        stateStore.write(
                            state.copy(phase = DshUpgradePhase.ROLLED_BACK, detail = "operator-abandoned")
                        )
                        val failure = DshMaintenanceFailure(
                            DshMaintenanceError.HEALTH_FAILED,
                            "the pending upgrade was abandoned by the operator; the previous closure stays active"
                        )
                        result = DshUpgradeResult.RolledBack(failure)
                        failure
                    }
                }
            )
        )
        result?.let { return it }
        return when (outcome) {
            is DshMaintenanceOutcome.Committed -> DshUpgradeResult.NoChange("abandon returned no typed result")
            is DshMaintenanceOutcome.Rejected -> DshUpgradeResult.Rejected(outcome.failure)
            is DshMaintenanceOutcome.RolledBack -> DshUpgradeResult.RolledBack(outcome.failure)
            is DshMaintenanceOutcome.RecoveryRequired -> DshUpgradeResult.RecoveryRequired(outcome.failure)
        }
    }

    // ---------------------------------------------------------------- flow

    private fun executeFlow(
        candidate: DshUpgradeCandidate,
        realHomeBackup: (() -> DshMaintenanceFailure?)?,
        publish: (DshUpgradeResult) -> Unit
    ): DshMaintenanceFailure? {
        val identity = candidate.identity()
        verify(candidate, identity)?.let {
            publish(DshUpgradeResult.Rejected(it))
            stateStore.write(failureState(candidate, identity, it.code.name))
            return it
        }
        val active = selectorStore.read()
        val schemaFailure = schemaCompatibility(candidate, active)
        if (schemaFailure != null) {
            publish(DshUpgradeResult.Rejected(schemaFailure))
            stateStore.write(failureState(candidate, identity, schemaFailure.code.name))
            return schemaFailure
        }
        val stopped = ensureStopped()
        if (stopped.state != DshHostState.STOPPED) {
            val failure = DshMaintenanceFailure(
                DshMaintenanceError.HOST_STILL_RUNNING,
                "the managed host is not confirmed stopped; trial and production are strictly serial",
                retryable = true,
                detail = stopped.detail.take(200)
            )
            publish(DshUpgradeResult.Rejected(failure))
            stateStore.write(failureState(candidate, identity, failure.code.name))
            return failure
        }
        stateStore.write(
            DshUpgradeState(
                phase = DshUpgradePhase.VERIFIED,
                candidateId = candidate.candidateId,
                candidateFingerprint = identity.fingerprint,
                candidateRoot = candidate.stagedRoot.absolutePath,
                previousFingerprint = active?.fingerprint ?: "",
                previousRoot = active?.root ?: "",
                previousDataSchemaVersion = active?.dataSchemaVersion ?: 0,
                updatedAtMillis = clock()
            )
        )
        stateStore.write(stateStore.read().copy(phase = DshUpgradePhase.TRIAL_RUNNING))
        val trialStatus = runCatching {
            host.startTrial(candidate.stagedRoot, identity.fingerprint)
        }.getOrElse { DshHostStatus(DshHostState.UNKNOWN, detail = "trial start threw") }
        if (trialStatus.state != DshHostState.RUNNING) {
            val failedTrialStop = host.stopTrial(stopTimeoutMillis)
            if (failedTrialStop.state != DshHostState.STOPPED) {
                val recovery = DshMaintenanceFailure(
                    DshMaintenanceError.RECOVERY_REQUIRED,
                    "a partially started trial host is not confirmed released",
                    detail = failedTrialStop.detail.take(200)
                )
                publish(DshUpgradeResult.RecoveryRequired(recovery))
                stateStore.write(
                    stateStore.read().copy(phase = DshUpgradePhase.RECOVERY_REQUIRED, detail = recovery.code.name)
                )
                return recovery
            }
            val failure = DshMaintenanceFailure(
                DshMaintenanceError.TRIAL_FAILED,
                "the isolated trial host did not start",
                retryable = true,
                detail = trialStatus.detail.take(200)
            )
            publish(DshUpgradeResult.TrialFailed(failure))
            stateStore.write(stateStore.read().copy(phase = DshUpgradePhase.ROLLED_BACK, detail = failure.code.name))
            return failure
        }
        val trialHealth = host.probeTrialHealth()
        // A new user has no committed session to migrate, so there is no container to verify. The
        // trial then proves the other positive fact: the official session store was initialized and
        // is readable. When the production home did have sessions, a verified copied container is
        // still required, so a failed copy can never pass as a "new user".
        val sessionProof = trialHealth.sessionReaderReady ||
            (trialHealth.newUserProfile && trialHealth.sessionStoreInitialized)
        val trialFailure = if (trialHealth.hostReady && trialHealth.homeReadable && sessionProof) {
            null
        } else {
            DshMaintenanceFailure(
                DshMaintenanceError.TRIAL_FAILED,
                "the isolated trial did not reach a read-only healthy state",
                detail = trialHealth.detail.take(200)
            )
        }
        val trialStopped = host.stopTrial(stopTimeoutMillis)
        if (trialStopped.state != DshHostState.STOPPED) {
            val failure = DshMaintenanceFailure(
                DshMaintenanceError.RECOVERY_REQUIRED,
                "the trial host did not confirm release; production must not start",
                retryable = false,
                detail = trialStopped.detail.take(200)
            )
            publish(DshUpgradeResult.RecoveryRequired(failure))
            stateStore.write(stateStore.read().copy(phase = DshUpgradePhase.RECOVERY_REQUIRED, detail = failure.code.name))
            return failure
        }
        if (trialFailure != null) {
            publish(DshUpgradeResult.TrialFailed(trialFailure))
            stateStore.write(stateStore.read().copy(phase = DshUpgradePhase.ROLLED_BACK, detail = trialFailure.code.name))
            return trialFailure
        }
        stateStore.write(stateStore.read().copy(phase = DshUpgradePhase.TRIAL_PASSED))
        if (realHomeBackup != null) {
            val backupFailure = realHomeBackup()
            if (backupFailure != null) {
                publish(DshUpgradeResult.Rejected(backupFailure))
                stateStore.write(stateStore.read().copy(phase = DshUpgradePhase.ROLLED_BACK, detail = "backup"))
                return backupFailure
            }
        }
        stateStore.write(stateStore.read().copy(phase = DshUpgradePhase.APPLYING))
        val installed = installedIdentity()
        val nextSelector = DshClosureSelector(
            fingerprint = identity.fingerprint,
            root = candidate.stagedRoot.absolutePath,
            dataSchemaVersion = candidate.dataSchemaVersion,
            committedAtMillis = clock(),
            previousFingerprint = active?.fingerprint ?: "",
            previousRoot = active?.root ?: "",
            lastGoodFingerprint = identity.fingerprint,
            lastGoodRoot = candidate.stagedRoot.absolutePath,
            installedPayloadVersion = installed.payloadVersion,
            installedArchiveSha256 = installed.archiveSha256
        )
        stateStore.write(stateStore.read().copy(phase = DshUpgradePhase.HEALTH_PENDING))
        selectorStore.write(nextSelector)
        val production = host.startProduction(candidate.stagedRoot, identity.fingerprint)
        val health = host.probeReadOnlyHealth()
        val healthy = production.state == DshHostState.RUNNING && health.healthy
        if (healthy) {
            stateStore.write(stateStore.read().copy(phase = DshUpgradePhase.COMMITTED))
            publish(DshUpgradeResult.Committed("", candidate.candidateId, identity.fingerprint))
            return null
        }
        val failure = DshMaintenanceFailure(
            DshMaintenanceError.HEALTH_FAILED,
            "production read-only health did not pass after the selector switch",
            detail = ("host=${production.state.name};" + health.detail).take(200)
        )
        if (!rollbackAllowed(candidate)) {
            val recovery = failure.copy(
                code = DshMaintenanceError.RECOVERY_REQUIRED,
                message = "the new APK replaced nativeLibraryDir without a verified slot/ABI compatibility; " +
                    "the previous closure cannot be claimed as a binary rollback"
            )
            publish(DshUpgradeResult.RecoveryRequired(recovery))
            stateStore.write(stateStore.read().copy(phase = DshUpgradePhase.RECOVERY_REQUIRED, detail = "rollback-impossible"))
            return recovery
        }
        val rollback = rollbackSelector(active)
        if (!rollback) {
            val recovery = failure.copy(
                code = DshMaintenanceError.RECOVERY_REQUIRED,
                message = "the selector switch could not be reverted; data and evidence preserved"
            )
            publish(DshUpgradeResult.RecoveryRequired(recovery))
            stateStore.write(stateStore.read().copy(phase = DshUpgradePhase.RECOVERY_REQUIRED, detail = "selector-revert-failed"))
            return recovery
        }
        // The selector alone is not a rollback: the unhealthy candidate generation must be
        // stopped and the previous closure (or the installed payload) must serve again before
        // this transaction may report ROLLED_BACK.
        val restoreFailure = restoreProductionAfterRollback(
            previousFingerprint = active?.fingerprint.orEmpty(),
            previousRoot = active?.root.orEmpty()
        )
        if (restoreFailure != null) {
            publish(DshUpgradeResult.RecoveryRequired(restoreFailure))
            stateStore.write(stateStore.read().copy(phase = DshUpgradePhase.RECOVERY_REQUIRED, detail = "restore-failed"))
            return restoreFailure
        }
        publish(DshUpgradeResult.RolledBack(failure))
        stateStore.write(stateStore.read().copy(phase = DshUpgradePhase.ROLLED_BACK, detail = "health-failed"))
        return failure
    }

    /**
     * Restores production after a reverted selector: stop the candidate generation, start the
     * previous closure (or the installed payload when there was no previous closure) and verify it
     * really serves. Any unconfirmed step is RECOVERY_REQUIRED - never a silent "rolled back".
     */
    private fun restoreProductionAfterRollback(
        previousFingerprint: String,
        previousRoot: String
    ): DshMaintenanceFailure? {
        val stopped = host.stopAndConfirm(stopTimeoutMillis)
        if (stopped.state != DshHostState.STOPPED) {
            return DshMaintenanceFailure(
                DshMaintenanceError.RECOVERY_REQUIRED,
                "the failed candidate generation is not confirmed stopped; the previous closure cannot be restored",
                retryable = false,
                detail = stopped.detail.take(200)
            )
        }
        // A root without a `payload` subtree is how the lifecycle port is asked for the installed
        // payload; it is used only when this upgrade was the first commit.
        val root = if (previousRoot.isEmpty()) File(engine.dataRootDirectory, "closure") else File(previousRoot)
        val restored = host.startProduction(root, previousFingerprint)
        val health = host.probeReadOnlyHealth()
        if (restored.state != DshHostState.RUNNING || !health.healthy) {
            return DshMaintenanceFailure(
                DshMaintenanceError.RECOVERY_REQUIRED,
                "the previous closure did not reach a healthy running state after rollback",
                retryable = false,
                detail = ("host=${restored.state.name};" + health.detail).take(200)
            )
        }
        return null
    }

    private fun resumeFlow(state: DshUpgradeState, publish: (DshUpgradeResult) -> Unit) {
        val selector = selectorStore.read()
        when (state.phase) {
            DshUpgradePhase.APPLYING,
            DshUpgradePhase.HEALTH_PENDING -> {
                val switched = selector != null && selector.fingerprint == state.candidateFingerprint
                if (!switched) {
                    // Crash before the selector switch: the previous closure is still active.
                    host.stopTrial(stopTimeoutMillis)
                    stateStore.write(state.copy(phase = DshUpgradePhase.ROLLED_BACK, detail = "resume-before-switch"))
                    publish(DshUpgradeResult.RolledBack(
                        DshMaintenanceFailure(
                            DshMaintenanceError.HEALTH_FAILED,
                            "upgrade crashed before the selector switch; previous closure kept"
                        )
                    ))
                    return
                }
                // The sweep runs inside the runtime's own pre-start admission, so starting the host
                // from here would re-enter the start it guards (device: 15 s stop timeout). The
                // runtime starts from this committed selector anyway; the post-start confirmation
                // ([confirmAfterStart]) proves health once the host is actually serving.
                if (host.status().state != DshHostState.RUNNING) {
                    stateStore.write(state.copy(detail = "awaiting-host-start"))
                    publish(DshUpgradeResult.NoChange(
                        "the committed selector is verified after the managed host starts"
                    ))
                    return
                }
                val health = host.probeReadOnlyHealth()
                if (health.healthy) {
                    stateStore.write(state.copy(phase = DshUpgradePhase.COMMITTED))
                    publish(DshUpgradeResult.Committed("", state.candidateId, state.candidateFingerprint))
                } else {
                    // Reverting the selector is only half the rollback: the unhealthy generation
                    // must be stopped and the previous closure (or the installed payload) must
                    // serve again before ROLLED_BACK is reported.
                    val reverted = revertSelector(
                        previousFingerprint = state.previousFingerprint,
                        previousRoot = state.previousRoot,
                        previousSchema = state.previousDataSchemaVersion
                    )
                    val restoreFailure = if (reverted) {
                        restoreProductionAfterRollback(
                            previousFingerprint = state.previousFingerprint,
                            previousRoot = state.previousRoot
                        )
                    } else {
                        DshMaintenanceFailure(
                            DshMaintenanceError.RECOVERY_REQUIRED,
                            "the selector switch could not be reverted; data and evidence preserved"
                        )
                    }
                    if (restoreFailure == null) {
                        stateStore.write(state.copy(phase = DshUpgradePhase.ROLLED_BACK, detail = "resume-health-failed"))
                        publish(DshUpgradeResult.RolledBack(
                            DshMaintenanceFailure(
                                DshMaintenanceError.HEALTH_FAILED,
                                "production health failed after restart; previous closure restored and healthy"
                            )
                        ))
                    } else {
                        stateStore.write(state.copy(phase = DshUpgradePhase.RECOVERY_REQUIRED, detail = "resume"))
                        publish(DshUpgradeResult.RecoveryRequired(restoreFailure))
                    }
                }
            }

            DshUpgradePhase.TRIAL_RUNNING,
            DshUpgradePhase.TRIAL_PASSED,
            DshUpgradePhase.VERIFIED -> {
                // The selector never moved, so the previous closure stays authoritative — but a
                // rollback is only real once the interrupted trial is confirmed gone. Reporting
                // ROLLED_BACK over a still-running trial would let a second host start.
                val stoppedTrial = host.stopTrial(stopTimeoutMillis)
                if (stoppedTrial.state != DshHostState.STOPPED) {
                    val failure = DshMaintenanceFailure(
                        DshMaintenanceError.RECOVERY_REQUIRED,
                        "the interrupted trial is not confirmed stopped; the previous closure stays active " +
                            "but the host slot is unproven",
                        retryable = false,
                        detail = stoppedTrial.detail.take(200)
                    )
                    stateStore.write(
                        state.copy(phase = DshUpgradePhase.RECOVERY_REQUIRED, detail = "resume-trial-unstopped")
                    )
                    publish(DshUpgradeResult.RecoveryRequired(failure))
                    return
                }
                stateStore.write(state.copy(phase = DshUpgradePhase.ROLLED_BACK, detail = "resume-before-apply"))
                publish(DshUpgradeResult.NoChange("interrupted before the selector switch; previous closure active"))
            }

            DshUpgradePhase.COMMITTED,
            DshUpgradePhase.ROLLED_BACK,
            DshUpgradePhase.RECOVERY_REQUIRED,
            DshUpgradePhase.IDLE -> publish(DshUpgradeResult.NoChange("upgrade state is stable"))
        }
    }

    private fun verify(candidate: DshUpgradeCandidate, identity: DshClosureIdentity): DshMaintenanceFailure? {
        if (candidate.candidateId.isBlank()) {
            return DshMaintenanceFailure(DshMaintenanceError.UPGRADE_INCOMPATIBLE, "candidate id is missing")
        }
        payloadFailure(candidate)?.let { return it }
        val missing = identity.missingComponents()
        if (missing.isNotEmpty()) {
            return DshMaintenanceFailure(
                DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                "candidate closure is incomplete; mixed versions are refused",
                detail = missing.take(6).joinToString(",")
            )
        }
        identity.invalidHashes().let {
            if (it.isNotEmpty()) {
                return DshMaintenanceFailure(
                    DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                    "candidate component hashes are invalid",
                    detail = it.take(6).joinToString(",")
                )
            }
        }
        if (!candidate.stagedRoot.isDirectory) {
            return DshMaintenanceFailure(DshMaintenanceError.UPGRADE_INCOMPATIBLE, "candidate staging tree is absent")
        }
        if (candidate.targetAbi !in supportedAbis) {
            return DshMaintenanceFailure(
                DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                "candidate ABI is not supported by this build",
                detail = candidate.targetAbi.take(32)
            )
        }
        return null
    }

    /**
     * Proves the candidate can actually be launched before anything is started: a real payload
     * tree below the staging root, a kernel entry, and critical file bytes that still match their
     * declared hashes (recomputed here, not trusted from staging time). Identity-only candidates
     * are refused here, so a "trial" can never pass by launching the installed payload instead.
     */
    private fun payloadFailure(candidate: DshUpgradeCandidate): DshMaintenanceFailure? {
        val root = candidate.payloadRoot
            ?: return DshMaintenanceFailure(
                DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                "the candidate carries no executable payload tree; it cannot be trialled"
            )
        if (DshMaintenanceIo.relativeWithin(candidate.stagedRoot, root) == null || root == candidate.stagedRoot) {
            return DshMaintenanceFailure(
                DshMaintenanceError.PATH_ESCAPE,
                "the candidate payload tree escapes its staging root"
            )
        }
        if (!root.isDirectory) {
            return DshMaintenanceFailure(
                DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                "the candidate payload tree is missing"
            )
        }
        if (!DshMaintenanceIo.isSafeRelativePath(candidate.payloadKernelEntry)) {
            return DshMaintenanceFailure(
                DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                "the candidate declares no usable kernel entry"
            )
        }
        val kernel = File(root, candidate.payloadKernelEntry)
        if (DshMaintenanceIo.relativeWithin(root, kernel) == null || !kernel.isFile || kernel.length() <= 0L) {
            return DshMaintenanceFailure(
                DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                "the candidate payload kernel entry is missing or empty"
            )
        }
        if (candidate.payloadCriticalFiles.isEmpty()) {
            return DshMaintenanceFailure(
                DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                "the candidate payload carries no integrity proof"
            )
        }
        if (candidate.payloadCriticalFiles.size > MAX_PAYLOAD_CRITICAL_FILES) {
            return DshMaintenanceFailure(
                DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                "the candidate declares more critical files than a bounded verification allows"
            )
        }
        for ((path, digest) in candidate.payloadCriticalFiles) {
            if (!DshMaintenanceIo.isSafeRelativePath(path) || !DshMaintenanceIo.isSha256Hex(digest)) {
                return DshMaintenanceFailure(
                    DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                    "a candidate critical file entry is malformed"
                )
            }
            val file = File(root, path)
            if (DshMaintenanceIo.relativeWithin(root, file) == null || !file.isFile) {
                return DshMaintenanceFailure(
                    DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                    "a candidate critical file is missing",
                    detail = path.take(120)
                )
            }
            if (!DshMaintenanceIo.sha256(file).equals(digest, ignoreCase = false)) {
                return DshMaintenanceFailure(
                    DshMaintenanceError.ARCHIVE_INVALID,
                    "a candidate critical file no longer matches its declared hash",
                    detail = path.take(120)
                )
            }
        }
        return null
    }

    private fun schemaCompatibility(
        candidate: DshUpgradeCandidate,
        active: DshClosureSelector?
    ): DshMaintenanceFailure? {
        // A first install has no active data schema yet; only candidate-side sanity applies.
        if (active == null) {
            return if (candidate.dataSchemaVersion < 1) {
                DshMaintenanceFailure(
                    DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                    "candidate does not declare a data schema"
                )
            } else {
                null
            }
        }
        val activeSchema = active.dataSchemaVersion
        if (activeSchema > candidate.dataSchemaVersion) {
            return DshMaintenanceFailure(
                DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                "the candidate cannot read the current data schema; a downgrade needs forward repair or a verified restore point",
                detail = "active=$activeSchema candidate=${candidate.dataSchemaVersion}"
            )
        }
        if (activeSchema < candidate.minReadableDataSchema && !candidate.migratesData) {
            return DshMaintenanceFailure(
                DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                "the candidate requires a newer data schema but provides no migration",
                detail = "active=$activeSchema minReadable=${candidate.minReadableDataSchema}"
            )
        }
        return null
    }

    private fun rollbackAllowed(candidate: DshUpgradeCandidate): Boolean =
        !candidate.nativeLibraryReplaced || candidate.prePackagedSlotVerified || candidate.abiCompatibleWithPrevious

    private fun ensureStopped(): DshHostStatus {
        val status = host.status()
        if (status.state == DshHostState.STOPPED) return status
        return host.stopAndConfirm(stopTimeoutMillis)
    }

    private fun rollbackSelector(previous: DshClosureSelector?): Boolean =
        revertSelector(
            previousFingerprint = previous?.fingerprint.orEmpty(),
            previousRoot = previous?.root.orEmpty(),
            previousSchema = previous?.dataSchemaVersion ?: 0
        )

    /**
     * Reverts the selector: writes the previous closure back, or clears the selector when this
     * upgrade was the first commit, so the installed payload becomes the active closure again.
     */
    private fun revertSelector(
        previousFingerprint: String,
        previousRoot: String,
        previousSchema: Int
    ): Boolean {
        if (previousFingerprint.isEmpty() || previousRoot.isEmpty()) return selectorStore.clear()
        return runCatching {
            val installed = installedIdentity()
            selectorStore.write(
                DshClosureSelector(
                    fingerprint = previousFingerprint,
                    root = previousRoot,
                    dataSchemaVersion = previousSchema,
                    committedAtMillis = clock(),
                    previousFingerprint = "",
                    previousRoot = "",
                    // The reverted closure is the one that was serving, so it is also the last
                    // proven-good identity after this rollback.
                    lastGoodFingerprint = previousFingerprint,
                    lastGoodRoot = previousRoot,
                    installedPayloadVersion = installed.payloadVersion,
                    installedArchiveSha256 = installed.archiveSha256
                )
            )
            true
        }.getOrDefault(false)
    }

    private fun failureState(
        candidate: DshUpgradeCandidate,
        identity: DshClosureIdentity,
        code: String
    ) = DshUpgradeState(
        phase = DshUpgradePhase.ROLLED_BACK,
        candidateId = candidate.candidateId,
        candidateFingerprint = identity.fingerprint,
        candidateRoot = candidate.stagedRoot.absolutePath,
        updatedAtMillis = clock(),
        detail = code
    )

    private companion object {
        const val MAX_PAYLOAD_CRITICAL_FILES = 64
    }
}
