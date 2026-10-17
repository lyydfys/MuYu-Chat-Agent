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
 * C2/插队裁定 2/4/5：智能调参唯一 owner，由 McaAppContainer 工厂构造、构造注入真实依赖（无 host 适配器）。
 * startAgentTuning 体自 MainViewModel 移入（逐字等价）；调参域运行态与逻辑成员归 owner；
 * VM 只保留 intent 委托与别名。内嵌调参端口为具名适配器（不再新增 VM 匿名端口）。
 */
internal class TuningOwner(
    internal val store: UiStateStore,
    internal val scope: CoroutineScope,
    internal val engine: McaInferenceService,
    internal val runtimeProfileStore: ModelRuntimeProfileStore,
    internal val modelStore: ModelStoreRepository,
    internal val benchmarkHistoryLogger: BenchmarkHistoryLogger,
    internal val readinessGate: ManagedRuntimeReadinessRefreshGate,
    internal val deviceProfileReader: DeviceProfileReader,
    internal val application: Application,
    internal val advisor: AgentAdvisor,
    internal val admission: RuntimeAdmissionState
) {
    // 调参域运行态（原 VM 字段迁入；VM 以 get/set 别名共享同实例）。
    var adaptiveTuningJob: Job? = null

    val adaptiveTuningPauseRequested = AtomicBoolean(false)

    val adaptiveTuningCancelRequested = AtomicBoolean(false)

    var activeTuningJobId: String? = null

    // 调参端口：具名适配器，只持有真实依赖。
    private val candidateCanaryResultPort: CandidateCanaryResultPort =
        CandidateCanaryResultPortAdapter(deviceProfileReader)

    val canaryPort: TuningCanaryPort =
        TuningCanaryPortAdapter(store, deviceProfileReader, engine, candidateCanaryResultPort)
}
