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
 * T2B21-T4（b 工位）拆片：runCandidateCanary / candidateMeasurement / measurementPoint /
 * asTuningExecutionProfile 端口转发以 TuningOwner 扩展承载（逐字节平移，仅声明行登记）。
 */

    /**
     * The ViewModel side of [TuningCanaryPort]. The canary result port object stays here: it is the
     * adapter that binds that interface to this ViewModel.
     */

internal suspend fun TuningOwner.runCandidateCanary(
        candidate: TuningExecutionProfile,
        canaryParams: com.muyuchat.core.tuning.CanaryEvaluationParams,
        authorization: com.muyuchat.core.engine.LoadAuthorization,
        disposition: com.muyuchat.core.engine.PendingProfileDisposition =
            com.muyuchat.core.engine.PendingProfileDisposition.ROLLBACK_AFTER_REQUEST,
        lifecycleLease: com.muyuchat.core.engine.EngineLifecycleLease? = null
    ): CandidateCanaryResult {
        return runCanary(port = canaryPort, candidate = candidate, canaryParams = canaryParams, authorization = authorization, disposition = disposition, lifecycleLease = lifecycleLease)
    }



/** Delegates to [canaryMeasurement]. Signature kept verbatim. */

internal fun TuningOwner.candidateMeasurement(
        profileId: String,
        jobId: String?,
        phase: String,
        result: CandidateCanaryResult
    ): CandidateMeasurementEntity {
        return canaryMeasurement(port = canaryPort, profileId = profileId, jobId = jobId, phase = phase, result = result)
    }

/** Delegates to [asTuningProfile]. Signature kept verbatim. */

internal fun TuningOwner.measurementPoint(device: DeviceProfile = deviceProfileReader.read()): MeasurementPoint {
        return currentMeasurementPoint(port = canaryPort, device = device)
    }

    /**
     * The ViewModel side of [CandidateCanaryResultPort]: two members, no logic of its own. The
     * conversion moved to [toCandidateCanaryResult], which is now the only place a probe result
     * becomes a canary verdict.
     *
     * `this@MainViewModel.` is spelled out on purpose: the port member shares its name with the
     * ViewModel member it forwards to.
     */

internal fun TuningOwner.asTuningExecutionProfile(
        profile: ModelExecutionProfile,
        kind: ExecutionProfileKind,
        verificationLevel: ProfileVerificationLevel,
        reason: String
    ): TuningExecutionProfile {
        return profile.asTuningProfile(port = canaryPort, kind = kind, verificationLevel = verificationLevel, reason = reason)
    }

/** Delegates to [currentMeasurementPoint]. Signature kept verbatim. */
