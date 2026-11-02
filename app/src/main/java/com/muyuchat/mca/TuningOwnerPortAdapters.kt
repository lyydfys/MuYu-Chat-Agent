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
 * T2B21-T4（b 工位）拆片：appendBenchmarkHistoryFor 与两个具名端口适配 class。
 * 顶层声明整块平移，零改写（无成员依赖，原样可编译）。
 */

internal fun appendBenchmarkHistoryFor(
    state: MainUiState,
    logger: BenchmarkHistoryLogger,
        device: DeviceProfile,
        result: BenchmarkResult,
        params: GenerationParams
    ) {
        val state = state
        val loadedModel = state.models.firstOrNull { it.id == state.loadedModelId }
        logger.append(
            BenchmarkHistoryRecord(
                modelId = loadedModel?.id ?: state.loadedModelId,
                modelName = loadedModel?.displayName ?: state.loadedModelName,
                modelPath = loadedModel?.path ?: state.stats.modelPath,
                deviceSummary = "${device.socFamily.name} · ${device.socLabel}",
                paramsJson = params.toJson(),
                result = result
            )
        )
    }

/**
 * C2（插队裁定 2）：调参内嵌端口的具名实现——只依赖真实对象，不再反向捕获 ViewModel。
 */
internal class CandidateCanaryResultPortAdapter(
    private val deviceProfileReader: DeviceProfileReader
) : CandidateCanaryResultPort {
    override fun currentDeviceProfile(): DeviceProfile = deviceProfileReader.read()

    override fun optionalFiniteDouble(root: JSONObject, field: String): Double =
        root.jsonOptionalFiniteDouble(field = field)
}

internal class TuningCanaryPortAdapter(
    private val store: UiStateReadPort,
    private val deviceProfileReader: DeviceProfileReader,
    private val inferenceEngine: McaInferenceService,
    private val resultPort: CandidateCanaryResultPort
) : TuningCanaryPort {
    override fun uiState(): MainUiState = store.current

    override fun currentDeviceProfile(): DeviceProfile = deviceProfileReader.read()

    override val candidateCanaryResultPort: CandidateCanaryResultPort
        get() = resultPort

    override val engine: McaInferenceService
        get() = inferenceEngine
}
