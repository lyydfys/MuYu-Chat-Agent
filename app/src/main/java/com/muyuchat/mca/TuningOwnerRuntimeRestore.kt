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
 * T2B21-T4（b 工位）拆片：awaitTuningBoundary / estimateTuningEtaSeconds /
 * restoreExactRuntimeProfile / modelTuningCapabilities / buildAdaptiveTuningRecommendation
 * 以 TuningOwner 扩展承载（逐字节平移，仅声明行登记）。
 */

internal suspend fun TuningOwner.awaitTuningBoundary(jobId: String) {
        while (adaptiveTuningPauseRequested.get() && !adaptiveTuningCancelRequested.get()) {
            runCatching { runtimeProfileStore.pauseTuningJob(jobId) }
            store.update { it.copy(tuningJobState = AgentTuningJobState.PAUSED, tuningPhase = "等待续跑") }
            delay(250L)
        }
        if (adaptiveTuningCancelRequested.get()) error("用户取消调优")
        runCatching { runtimeProfileStore.resumeTuningJob(jobId) }
    }

internal fun TuningOwner.estimateTuningEtaSeconds(model: ModelManifest, mode: AgentDebugMode): Long {
        val history = store.current.benchmarkHistory
            .filter { it.modelId == model.id }
        return tuningEtaSecondsFromPreviousLoad(history.firstOrNull()?.result?.loadMs, mode)
    }

    /**
     * The ViewModel side of [RuntimeProfileTransactionPort]: one route per member, no logic of its
     * own. Both decisions moved to [RuntimeProfileTransactionOperations], which is now the only place
     * a runtime profile is applied or rolled back.
     *
     * Every override keeps the `this@MainViewModel.` prefix on purpose: several port members share
     * their name with the ViewModel member they forward to, and an unqualified call would recurse.
     */

internal suspend fun TuningOwner.restoreExactRuntimeProfile(
        model: ModelManifest,
        profile: ModelExecutionProfile
    ): Boolean {
        val active = engine.activeExecutionProfile()
        if (engine.stats.value.loaded &&
            active?.profileId == profile.profileId &&
            active.resolvedLoadSignature.digest == profile.resolvedLoadSignature.digest &&
            active.committedExecutionSignature.digest == profile.committedExecutionSignature.digest
        ) return true
        val qairtSha = currentQairtBundleSha256(model, modelStore.listModels())
        return engine.loadModel(
            modelPath = model.path,
            runtime = model.runtime.toLocalChatRuntime(),
            params = model.loadParamsForExecutionProfile(profile),
            qairtBundleSha256 = qairtSha,
            runtimeIdentity = profile.runtimeIdentity,
            executionProfile = profile
        ).isSuccess
    }

internal fun TuningOwner.modelTuningCapabilities(
        model: ModelManifest,
        identity: ModelRuntimeIdentity,
        qairtAdmissionPassed: Boolean
    ): ModelTuningCapabilities = discoverModelTuningCapabilities(
        model = model,
        identity = identity,
        qairtAdmissionPassed = qairtAdmissionPassed
    )

internal fun TuningOwner.buildAdaptiveTuningRecommendation(
        model: ModelManifest,
        identity: ModelRuntimeIdentity,
        device: DeviceProfile,
        capabilities: ModelTuningCapabilities,
        preference: UserPreference = store.current.preference
    ): AdaptiveTuningRecommendation {
        return advisor.recommendAdaptiveForModel(
            device = device,
            model = model,
            runtimeIdentity = identity,
            capabilities = capabilities,
            preference = preference,
            lastDecodeTps = null
        )
    }
