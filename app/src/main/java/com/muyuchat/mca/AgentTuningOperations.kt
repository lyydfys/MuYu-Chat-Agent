package com.muyuchat.mca

import android.app.Application
import com.muyuchat.core.advisor.AgentAdvisor
import com.muyuchat.core.advisor.AgentDecisionLog
import com.muyuchat.core.advisor.AgentDecisionLogger
import com.muyuchat.core.advisor.AgentRecommendation
import com.muyuchat.core.benchmark.BenchmarkHistoryLogger
import com.muyuchat.core.benchmark.BenchmarkRunner
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.download.ModelScopeClient
import com.muyuchat.core.download.ModelScopeRecommendedModel
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.tuning.AdaptiveTuningRecommendation
import com.muyuchat.core.tuning.PerformanceMode
import com.muyuchat.core.tuning.UserPreference
import com.muyuchat.feature.agent.AgentTuningJobState
import com.muyuchat.feature.agent.AgentTuningMode
import com.muyuchat.core.engine.LoadParams
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.modelstore.ModelStoreRepository
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R4-S16：调优与建议整族下沉 (AgentTuningOperations)。
 * 统一管理调优任务生命周期、状态机、建议应用与基准评测。
 */
internal class AgentTuningCoordinator(
    private val application: Application,
    private val store: UiStateWriter<RuntimeProfileFields>,
    private val scope: CoroutineScope,
    private val engine: McaInferenceService,
    private val tuningOwner: TuningOwner,
    private val modelStore: ModelStoreRepository,
    private val cloudApiStore: CloudApiStore,
    private val runtimeProfileStore: ModelRuntimeProfileStore,
    private val modelScopeClient: ModelScopeClient,
    private val advisor: AgentAdvisor,
    private val agentLogger: AgentDecisionLogger,
    private val benchmarkRunner: BenchmarkRunner,
    private val benchmarkHistoryLogger: BenchmarkHistoryLogger,
    private val deviceProfileReader: DeviceProfileReader,
    private val diagnosticsOperations: DiagnosticsOperations,
    private val managedRuntimeReadinessRefreshGate: ManagedRuntimeReadinessRefreshGate,
    private val managedRuntimeReadinessPort: () -> ManagedRuntimeReadinessPort,
    private val localApiRuntimeOwner: LocalApiRuntimeOwner,
    private val cancelPersistedTuningJob: suspend (String) -> TuningJobEntity,
    private val activeRuntimeIdentity: () -> ModelRuntimeIdentity?,
    private val activeAdaptiveRecommendation: () -> AdaptiveTuningRecommendation?,
    private val setActiveAdaptiveRecommendation: (AdaptiveTuningRecommendation?) -> Unit,
    private val busy: (String) -> Unit,
    private val fail: (String) -> Unit,
    private val showStatus: (String) -> Unit,
    private val persistModelGenerationParams: (ChatBackend, String?, GenerationParams) -> Unit,
    private val updateParams: (GenerationParams) -> Unit,
    private val loadModel: (ModelManifest) -> Unit,
    private val timedLoadModel: suspend (ModelManifest, LocalChatRuntime, LoadParams) -> Long,
    private val runLocalGenerationSmoke: suspend (GenerationParams) -> LocalGenerationSmokeResult,
    private val cancelGenerationJob: () -> Unit,
    private val activeModelForRuntimeProfile: () -> ModelManifest?,
    private val runtimeProfileTransactionPort: RuntimeProfileTransactionPort,
    private val getPendingProfileTransactionId: () -> String?,
    private val setPendingProfileTransactionId: (String?) -> Unit
) {
    @Volatile
    var activeTuningJobId: String? = null

    @Volatile
    var adaptiveTuningJob: Job? = null

    val adaptiveTuningPauseRequested = AtomicBoolean(false)
    val adaptiveTuningCancelRequested = AtomicBoolean(false)

    suspend fun applyPendingRuntimeTransaction(pending: PendingRuntimeProfileTransaction) =
        applyPendingRuntimeTransaction(port = runtimeProfileTransactionPort, pending = pending)

    suspend fun rollbackCommittedRuntimeProfile(
        identity: ModelRuntimeIdentity,
        model: ModelManifest
    ) = rollbackCommittedRuntimeProfile(port = runtimeProfileTransactionPort, identity = identity, model = model)

    fun currentDeviceProfile(): DeviceProfile = deviceProfileReader.read()

    fun buildRecommendation(
        preference: UserPreference,
        device: DeviceProfile = currentDeviceProfile(),
        lastDecodeTps: Double? = store.current.benchmark?.decodeTps?.takeIf { it > 0.0 }
            ?: store.current.stats.decodeTps.takeIf { it > 0.0 }
    ): AgentRecommendation = advisor.recommend(
        device = device,
        localModels = modelStore.listModels(),
        remoteFiles = store.current.remoteFiles,
        preference = preference,
        lastDecodeTps = lastDecodeTps
    )

    fun updateAgentPreference(preference: UserPreference) {
        val state = store.current
        val recommendation = runCatching { buildRecommendation(preference) }.getOrNull()
        val updatedParams = recommendation?.tuningPlan?.applyTo(state.params)
        val paramsChanged = updatedParams != null && updatedParams != state.params
        if (updatedParams != null) updateParams(updatedParams)
        store.update { current ->
            current.copy(
                preference = preference,
                agentRecommendation = recommendation,
                rollbackParams = if (paramsChanged) {
                    state.rollbackParams ?: state.params
                } else {
                    current.rollbackParams
                },
                statusMessage = when {
                    updatedParams == null -> recommendation?.explanation ?: current.statusMessage
                    current.reloadRequired ->
                        "已切换为${preference.mode.label}参数：n_ctx=${updatedParams.nCtx}，threads=${updatedParams.nThreads}；需要重新加载，当前模型继续使用原配置。"
                    paramsChanged && current.loadedModelId == null ->
                        "已切换为${preference.mode.label}参数：n_ctx=${updatedParams.nCtx}，threads=${updatedParams.nThreads}；已保存，下次加载模型时生效。"
                    else ->
                        "已切换为${preference.mode.label}参数：n_ctx=${updatedParams.nCtx}，threads=${updatedParams.nThreads}，n_predict=${updatedParams.nPredict}。"
                }
            )
        }
    }

    fun scanAgent() {
        scanAgentFor(
            store = store,
            scope = scope,
            tuningOwner = tuningOwner,
            modelScopeClient = modelScopeClient,
            agentLogger = agentLogger,
            currentDeviceProfile = ::currentDeviceProfile,
            buildRecommendation = ::buildRecommendation,
            activeRuntimeIdentity = activeRuntimeIdentity,
            setActiveAdaptiveRecommendation = setActiveAdaptiveRecommendation,
            busy = busy,
            fail = fail
        )
    }

    fun applyAgentRecommendation() {
        applyAgentRecommendationFor(
            store = store,
            application = application,
            agentLogger = agentLogger,
            activeAdaptiveRecommendation = activeAdaptiveRecommendation(),
            currentDeviceProfile = ::currentDeviceProfile,
            persistModelGenerationParams = { state, params ->
                persistModelGenerationParams(state.selectedChatBackend, state.loadedModelId, params)
            }
        )
    }

    fun rollbackAgentParams() {
        rollbackAgentParamsFor(
            store = store,
            application = application,
            persistModelGenerationParams = { state, params ->
                persistModelGenerationParams(state.selectedChatBackend, state.loadedModelId, params)
            }
        )
    }

    fun loadAgentRecommendedModel() {
        loadAgentRecommendedModelFor(
            store = store,
            loadModel = loadModel
        )
    }

    fun runAgentBenchmark() {
        runAgentBenchmarkFor(
            store = store,
            scope = scope,
            engine = engine,
            benchmarkRunner = benchmarkRunner,
            benchmarkHistoryLogger = benchmarkHistoryLogger,
            currentDeviceProfile = ::currentDeviceProfile,
            busy = busy,
            fail = fail
        )
    }

    fun runAgentStabilitySmoke() {
        runAgentStabilitySmokeFor(
            scope,
            store,
            engine,
            modelStore,
            cloudApiStore,
            managedRuntimeReadinessRefreshGate,
            managedRuntimeReadinessPort(),
            deviceProfileReader,
            diagnosticsOperations,
            cancelGeneration = { cancelGenerationJob() },
            busy = { busy(it) },
            loadSmoke = { model, runtime, params -> timedLoadModel(model, runtime, params) },
            generationSmoke = { params -> runLocalGenerationSmoke(params) },
            apiSmoke = { localApiRuntimeOwner.runLoopbackApiStreamSmoke() },
            engineSmoke = { params -> localApiRuntimeOwner.runLoopbackEngineStreamSmoke(params) }
        )
    }

    fun startAgentTuning() = startAgentTuning(AgentTuningMode.QUICK, false)

    fun startAgentTuning(mode: AgentTuningMode, autoApply: Boolean) {
        val debugMode = when (mode) {
            AgentTuningMode.QUICK -> AgentDebugMode.Quick
            AgentTuningMode.STANDARD -> AgentDebugMode.Standard
            AgentTuningMode.DEEP -> AgentDebugMode.Deep
            AgentTuningMode.POWER_SAVE -> AgentDebugMode.PowerSave
        }
        if (mode == AgentTuningMode.POWER_SAVE) {
            store.update { it.copy(preference = it.preference.copy(mode = PerformanceMode.PowerSave)) }
        }
        tuningOwner.startAgentTuning(debugMode = debugMode, autoApply = autoApply)
    }

    fun pauseAgentTuning() {
        adaptiveTuningPauseRequested.set(true)
        val jobId = activeTuningJobId
        if (jobId != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { runtimeProfileStore.pauseTuningJob(jobId) }
                    .onSuccess {
                        store.update { state ->
                            state.copy(
                                tuningJobState = AgentTuningJobState.PAUSED,
                                tuningPhase = "等待用户续跑",
                                statusMessage = "调优已暂停；不会提交新的候选。"
                            )
                        }
                    }
            }
        } else {
            store.update { it.copy(tuningJobState = AgentTuningJobState.PAUSED, tuningPhase = "等待任务") }
        }
    }

    fun resumeAgentTuning() {
        adaptiveTuningPauseRequested.set(false)
        val jobId = activeTuningJobId
        if (jobId != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { runtimeProfileStore.resumeTuningJob(jobId) }
                    .onSuccess {
                        store.update { state ->
                            state.copy(
                                tuningJobState = AgentTuningJobState.RUNNING,
                                tuningPhase = "继续执行",
                                statusMessage = "调优已继续。"
                            )
                        }
                    }
            }
        } else {
            store.update { it.copy(tuningJobState = AgentTuningJobState.IDLE) }
        }
    }

    fun cancelAgentTuning() {
        adaptiveTuningCancelRequested.set(true)
        val jobId = activeTuningJobId
        if (jobId != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { cancelPersistedTuningJob(jobId) }
                    .onSuccess { canceled ->
                        store.update {
                            it.copy(
                                tuningJobState = agentJobState(canceled),
                                tuningPhase = canceled.phase,
                                statusMessage = if (canceled.state == PersistedTuningJobState.FAILED.name) {
                                    "调优已取消，稳定 profile 已保留。"
                                } else {
                                    "正在取消调优并恢复当前稳定配置…"
                                }
                            )
                        }
                    }
            }
        } else {
            store.update { it.copy(tuningJobState = AgentTuningJobState.IDLE, statusMessage = "当前没有运行中的调优任务。") }
        }
    }

    fun queryAgentTuningJob() {
        val identity = activeRuntimeIdentity() ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { runtimeProfileStore.currentRuntimeState(identity.identityHash) }
                .onSuccess { snapshot ->
                    val job = snapshot?.activeJob
                    store.update { state ->
                        state.copy(
                            tuningJobState = job?.let(::agentJobState) ?: state.tuningJobState,
                            tuningPhase = job?.phase ?: state.tuningPhase,
                            statusMessage = job?.let { "调优任务 ${it.jobId.take(8)}：${it.phase}" }
                                ?: state.statusMessage
                        )
                    }
                }
        }
    }

    fun applyPendingAgentProfile() {
        val identity = activeRuntimeIdentity() ?: return
        if (adaptiveTuningJob?.isActive == true) {
            showStatus("运行时正在执行其他调优生命周期操作。")
            return
        }
        adaptiveTuningJob = scope.launch(Dispatchers.IO) {
            try {
                val pending = runCatching { runtimeProfileStore.pendingTransaction(identity.identityHash) }
                    .getOrNull()
                if (pending == null) {
                    showStatus("当前没有待确认的调优建议。")
                    return@launch
                }
                applyPendingRuntimeTransaction(pending)
            } finally {
                activeTuningJobId = null
                adaptiveTuningJob = null
            }
        }
    }

    fun discardPendingAgentProfile() {
        val identity = activeRuntimeIdentity() ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val pending = runtimeProfileStore.pendingTransaction(identity.identityHash)
                    ?: error("待应用配置已不存在")
                val transactionId = pending.journal.transactionId
                val recovery = runtimeProfileStore.rejectCandidate(
                    transactionId = transactionId,
                    failureStage = "USER_DISCARDED",
                    failureCode = "DISCARDED",
                    failureSummary = "用户撤销了尚未应用的运行参数修改。"
                )
                runtimeProfileStore.completeRecovery(
                    transactionId = transactionId,
                    restoredProfileId = recovery.rollbackProfileId
                )
                if (getPendingProfileTransactionId() == transactionId) {
                    setPendingProfileTransactionId(null)
                }
                val restoredProfile = engine.activeExecutionProfile()
                    ?: runtimeProfileStore.currentRuntimeState(identity.identityHash)?.activeExecutionProfile
                    ?: error("稳定运行配置恢复记录缺失")
                val restored = mergedExecutionProfile(store.current.params, restoredProfile)
                GenerationPreferences.persistGenerationParams(application, restored)
                store.update {
                    it.copy(
                        params = restored,
                        pendingProfile = null,
                        reloadRequired = false,
                        tuningJobState = AgentTuningJobState.IDLE,
                        tuningPhase = null,
                        statusMessage = "已撤销未应用的运行参数修改。"
                    )
                }
            }.onFailure { error ->
                store.update {
                    it.copy(statusMessage = "撤销修改失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    fun rollbackAgentProfile() {
        val identity = activeRuntimeIdentity() ?: return
        val model = activeModelForRuntimeProfile() ?: return
        scope.launch(Dispatchers.IO) {
            rollbackCommittedRuntimeProfile(identity, model)
        }
    }

    fun runAgentQuickDebug() = startAgentTuning(AgentTuningMode.QUICK, false)
    fun runAgentStandardDebug() = startAgentTuning(AgentTuningMode.STANDARD, false)
    fun runAgentDeepDebug() = startAgentTuning(AgentTuningMode.DEEP, false)
    fun runAgentPowerDebug() = startAgentTuning(AgentTuningMode.POWER_SAVE, false)

    fun showAgentDebugExplanation() {
        store.update {
            it.copy(
                statusMessage = "智能调试由规则包执行参数搜索，当前已加载模型只用于解释调试结果。"
            )
        }
    }
}

internal fun sortRecommendedModels(
    models: List<ModelScopeRecommendedModel>,
    device: DeviceProfile
): List<ModelScopeRecommendedModel> {
    return models.filter { it.visibleInRecommendations }.sortedWith(
        compareBy<ModelScopeRecommendedModel> { model ->
            model.group.ordinal
        }.thenBy { model ->
            model.priority
        }.thenBy { model ->
            model.id
        }
    )
}

internal fun scanAgentFor(
    store: UiStateWriter<RuntimeProfileFields>,
    scope: CoroutineScope,
    tuningOwner: TuningOwner,
    modelScopeClient: ModelScopeClient,
    agentLogger: AgentDecisionLogger,
    currentDeviceProfile: () -> DeviceProfile,
    buildRecommendation: (UserPreference, DeviceProfile) -> AgentRecommendation,
    activeRuntimeIdentity: () -> ModelRuntimeIdentity?,
    setActiveAdaptiveRecommendation: (AdaptiveTuningRecommendation?) -> Unit,
    busy: (String) -> Unit,
    fail: (String) -> Unit
) {
    scope.launch(Dispatchers.IO) {
        busy("Agent 正在扫描设备和模型...")
        runCatching {
            val device = currentDeviceProfile()
            val recommendation = buildRecommendation(store.current.preference, device)
            activeRuntimeIdentity()?.let { identity ->
                val loaded = store.current.models.firstOrNull { it.id == store.current.loadedModelId }
                if (loaded != null) {
                    val adaptive = tuningOwner.buildAdaptiveTuningRecommendation(
                        loaded,
                        identity,
                        device,
                        tuningOwner.modelTuningCapabilities(
                            loaded,
                            identity,
                            loaded.runtime == ChatModelRuntime.GENIEX_QAIRT
                        )
                    )
                    setActiveAdaptiveRecommendation(adaptive)
                }
            }
            agentLogger.append(device, recommendation)
            val recommendedModels = sortRecommendedModels(modelScopeClient.userFacingRecommendedModels(), device)
            val agentLogs = agentLogger.recent()
            store.update {
                it.copy(
                    deviceProfile = device,
                    agentRecommendation = recommendation,
                    recommendedRemoteModels = recommendedModels,
                    agentLogs = agentLogs,
                    busy = false,
                    statusMessage = recommendation.explanation
                )
            }
        }.onFailure { error ->
            fail("Agent 体检失败：${error.message}")
        }
    }
}

internal fun applyAgentRecommendationFor(
    store: UiStateWriter<RuntimeProfileFields>,
    application: Application,
    agentLogger: AgentDecisionLogger,
    activeAdaptiveRecommendation: AdaptiveTuningRecommendation?,
    currentDeviceProfile: () -> DeviceProfile,
    persistModelGenerationParams: (MainUiState, GenerationParams) -> Unit
) {
    val state = store.current
    val recommendation = state.agentRecommendation ?: return
    val previousParams = state.params
    val generationRecommendation = activeAdaptiveRecommendation?.generationRecommendation
    val updatedParams = generationRecommendation?.applyTo(state.params) ?: state.params.copy(
        nPredict = recommendation.tuningPlan.nPredict,
        temperature = recommendation.tuningPlan.temperature,
        topK = recommendation.tuningPlan.topK,
        topP = recommendation.tuningPlan.topP,
        minP = recommendation.tuningPlan.minP,
        repeatPenalty = recommendation.tuningPlan.repeatPenalty,
        presencePenalty = recommendation.tuningPlan.presencePenalty,
        frequencyPenalty = recommendation.tuningPlan.frequencyPenalty,
        seed = recommendation.tuningPlan.seed ?: state.params.seed
    )
    val device = state.deviceProfile ?: currentDeviceProfile()
    agentLogger.append(
        device = device,
        recommendation = recommendation,
        benchmark = state.benchmark,
        appliedPlan = recommendation.tuningPlan,
        userConfirmed = true
    )
    GenerationPreferences.persistGenerationParams(application, updatedParams)
    persistModelGenerationParams(state, updatedParams)
    val agentLogs = agentLogger.recent()
    store.update {
        it.copy(
            params = updatedParams,
            rollbackParams = previousParams,
            agentLogs = agentLogs,
            statusMessage = "已应用助手生成建议；模型加载、线程、模板和 native 参数未被静默修改。"
        )
    }
}

internal fun rollbackAgentParamsFor(
    store: UiStateWriter<RuntimeProfileFields>,
    application: Application,
    persistModelGenerationParams: (MainUiState, GenerationParams) -> Unit
) {
    val state = store.current
    val previous = state.rollbackParams ?: return
    GenerationPreferences.persistGenerationParams(application, previous)
    persistModelGenerationParams(state, previous)
    store.update {
        it.copy(
            params = previous,
            rollbackParams = null,
            statusMessage = "已回退到上一次助手生成参数；模型执行 profile 保持不变。"
        )
    }
}

internal fun loadAgentRecommendedModelFor(
    store: UiStatusWriter,
    loadModel: (ModelManifest) -> Unit
) {
    val recommendation = store.current.agentRecommendation?.recommended
    val modelId = recommendation?.model?.id
    val model = store.current.models.firstOrNull { it.id == modelId }
    if (model != null) {
        loadModel(model)
    } else {
        store.showStatus("推荐模型尚未在本机。请先到模型页下载或导入，再由 Agent 加载。")
    }
}

internal fun runAgentBenchmarkFor(
    store: UiStateWriter<RuntimeProfileFields>,
    scope: CoroutineScope,
    engine: McaInferenceService,
    benchmarkRunner: BenchmarkRunner,
    benchmarkHistoryLogger: BenchmarkHistoryLogger,
    currentDeviceProfile: () -> DeviceProfile,
    busy: (String) -> Unit,
    fail: (String) -> Unit
) {
    scope.launch(Dispatchers.IO) {
        val currentParams = engineApiGenerationParams(engine, store.current)
        busy("正在按当前参数测速...")
        runCatching {
            val device = currentDeviceProfile()
            val result = benchmarkRunner.runCurrentParamsBenchmark(currentParams)
            appendBenchmarkHistoryFor(store.current, benchmarkHistoryLogger, device, result, currentParams)
            val benchmarkHistory = benchmarkHistoryLogger.recent()
            store.update {
                it.copy(
                    deviceProfile = device,
                    benchmark = result,
                    benchmarkHistory = benchmarkHistory,
                    busy = false,
                    statusMessage = result.error
                        ?: "当前参数测速完成：decode ${"%.2f".format(result.decodeTps)} token/s，threads=${currentParams.nThreads}"
                )
            }
        }.onFailure { error ->
            fail("当前参数测速失败：${error.message}")
        }
    }
}
