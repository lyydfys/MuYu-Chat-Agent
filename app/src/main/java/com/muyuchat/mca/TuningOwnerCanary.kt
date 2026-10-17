package com.muyuchat.mca

import android.app.Application
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import com.muyuchat.core.advisor.AgentAdvisor
import com.muyuchat.core.benchmark.BenchmarkHistoryRecord
import com.muyuchat.core.benchmark.BenchmarkHistoryLogger
import com.muyuchat.core.engine.EngineLifecycleLease
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import com.muyuchat.core.benchmark.BenchmarkResult
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LoadAuthorization
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.PendingProfileDisposition
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelStoreRepository
import com.muyuchat.core.tuning.AdaptiveTuningRecommendation
import com.muyuchat.core.tuning.CanaryEvaluationParams
import com.muyuchat.core.tuning.CandidateProcessBoundary
import com.muyuchat.core.tuning.ExecutionProfileKind
import com.muyuchat.core.tuning.MeasurementPoint
import com.muyuchat.core.tuning.ModelTuningCapabilities
import com.muyuchat.core.tuning.ProfileVerificationLevel
import com.muyuchat.core.tuning.SafetyEnvelope
import com.muyuchat.core.tuning.StagedCandidateSelectionPolicy
import com.muyuchat.core.tuning.TuningCandidateCanaryPlanner
import com.muyuchat.core.tuning.TuningExecutionProfile
import com.muyuchat.core.tuning.UserPreference
import com.muyuchat.feature.agent.AgentCandidateProgress
import com.muyuchat.feature.agent.AgentPendingProfile
import com.muyuchat.feature.agent.AgentProfileRecordState
import com.muyuchat.feature.agent.AgentProfileVerification
import com.muyuchat.feature.agent.AgentRollbackProfile
import com.muyuchat.feature.agent.AgentTuningJobState
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * T2B21-T4（b 工位）拆片：failedIsolatedTuningCandidateResult /
 * runIsolatedTuningCandidateCanary 以 TuningOwner 扩展承载（逐字节平移，仅声明行登记）。
 */

internal fun TuningOwner.failedIsolatedTuningCandidateResult(
        candidate: TuningExecutionProfile,
        start: MeasurementPoint,
        error: Throwable
    ): CandidateCanaryResult {
        return failedIsolatedCanaryResult(port = canaryPort, candidate = candidate, start = start, error = error)
    }


/** Delegates to [jsonOptionalFiniteDouble]. Signature kept verbatim. */


internal suspend fun TuningOwner.runIsolatedTuningCandidateCanary(
        candidate: TuningExecutionProfile,
        committed: ModelExecutionProfile,
        model: ModelManifest,
        jobId: String,
        stage: String,
        onProgress: (TuningProbeWorkerProtocol.Progress) -> Unit
    ): CandidateCanaryResult {
        require(candidate.identityHash == committed.runtimeIdentity.identityHash) {
            "隔离探测候选与 committed profile 身份不一致"
        }
        val probeStart = measurementPoint()
        val transactionId = "probe-$jobId-${UUID.randomUUID()}"
        val snapshot = candidate.engineProfile.toPersistedExecutionProfileSnapshot(
            parentCommittedProfileId = committed.profileId,
            verificationLevel = PersistedProfileVerificationLevel.COMPATIBLE,
            sourceSummaryJson = JSONObject()
                .put("kind", "isolated_load_bound_probe")
                .put("probeKind", TuningProbeWorkerProtocol.ProbeKind.TUNING_CANDIDATE.name)
                .put("jobId", jobId)
                .put("stage", stage)
                .put("profileId", candidate.profileId)
                .put("resolvedLoadSignature", candidate.engineProfile.resolvedLoadSignature.digest)
                .put("committedExecutionSignature", candidate.engineProfile.committedExecutionSignature.digest)
                .toString()
        )
        runtimeProfileStore.stageCandidate(
            snapshot = snapshot,
            transactionId = transactionId,
            rollbackTargetProfileId = committed.profileId,
            job = null
        )
        runtimeProfileStore.updateJournalStage(
            transactionId = transactionId,
            state = TuningJournalState.APPLYING,
            stage = "MAIN_UNLOAD_BEFORE_ISOLATED_PROBE"
        )

        var workerResult: TuningProbeWorkerProtocol.Result? = null
        var failure: Throwable? = null
        var restored = false
        try {
            // Do not keep a second 35B native mapping in the app process while :tuning loads the
            // candidate. unloadModel() also stops and joins any in-flight UI/API generation.
            engine.unloadModel()
            workerResult = TuningProbeWorkerClient(application).probe(
                probeKind = TuningProbeWorkerProtocol.ProbeKind.TUNING_CANDIDATE,
                transactionId = transactionId,
                identityKey = candidate.identityHash,
                modelId = model.id,
                profileId = candidate.profileId,
                resolvedLoadSignature = candidate.engineProfile.resolvedLoadSignature.digest,
                committedExecutionSignature = candidate.engineProfile.committedExecutionSignature.digest,
                onProgress = onProgress
            )
        } catch (error: CancellationException) {
            // withTimeout uses CancellationException for a candidate-local timeout even while the
            // parent tuning job remains active. Only a real caller/job cancellation may abort the
            // whole search; a timed-out disposable worker is simply an ineligible candidate.
            currentCoroutineContext().ensureActive()
            failure = error
        } catch (error: Throwable) {
            failure = error
        } finally {
            withContext(NonCancellable) {
                restored = runCatching { restoreExactRuntimeProfile(model, committed) }.getOrDefault(false)
                if (restored) {
                    val result = workerResult
                    val summary = when {
                        failure != null -> failure?.message ?: "isolated probe failed"
                        result == null -> "isolated probe returned no evidence"
                        result.passed -> "isolated probe passed; committed profile restored"
                        else -> result.detail
                    }
                    runtimeProfileStore.completeIsolatedProbe(
                        transactionId = transactionId,
                        passed = result?.passed == true,
                        summary = summary
                    )
                }
            }
        }
        check(restored) {
            "隔离探测结束后无法恢复 committed profile；pending journal 已保留供进程恢复"
        }
        failure?.let { error ->
            return failedIsolatedTuningCandidateResult(candidate, probeStart, error)
        }
        return requireNotNull(workerResult).canaryResultFrom(port = canaryPort, candidate = candidate)
    }
