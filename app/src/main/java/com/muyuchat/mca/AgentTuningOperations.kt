package com.muyuchat.mca

import android.app.Application
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R4-S16：调优与建议整族下沉 (AgentTuningOperations)。
 * 统一管理调优任务生命周期、状态机、建议应用与基准评测。
 */
internal class AgentTuningCoordinator {
    @Volatile
    var activeTuningJobId: String? = null

    @Volatile
    var adaptiveTuningJob: Job? = null

    val adaptiveTuningPauseRequested = AtomicBoolean(false)
    val adaptiveTuningCancelRequested = AtomicBoolean(false)

    fun startAgentTuning(
        tuningOwner: TuningOwner,
        mode: AgentTuningMode,
        autoApply: Boolean,
        store: UiStateStore
    ) {
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

    fun pauseAgentTuning(
        runtimeProfileStore: ModelRuntimeProfileStore,
        store: UiStateStore,
        scope: CoroutineScope
    ) {
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

    fun resumeAgentTuning(
        runtimeProfileStore: ModelRuntimeProfileStore,
        store: UiStateStore,
        scope: CoroutineScope
    ) {
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

    fun cancelAgentTuning(
        cancelPersistedJob: suspend (String) -> TuningJobEntity,
        store: UiStateStore,
        scope: CoroutineScope
    ) {
        adaptiveTuningCancelRequested.set(true)
        val jobId = activeTuningJobId
        if (jobId != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { cancelPersistedJob(jobId) }
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

    fun queryAgentTuningJob(
        runtimeProfileStore: ModelRuntimeProfileStore,
        store: UiStateStore,
        scope: CoroutineScope,
        activeRuntimeIdentity: ModelRuntimeIdentity?
    ) {
        val identity = activeRuntimeIdentity ?: return
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

    fun applyPendingAgentProfile(
        runtimeProfileStore: ModelRuntimeProfileStore,
        scope: CoroutineScope,
        activeRuntimeIdentity: ModelRuntimeIdentity?,
        showStatus: (String) -> Unit,
        applyPendingRuntimeTransaction: suspend (PendingRuntimeProfileTransaction) -> Unit
    ) {
        val identity = activeRuntimeIdentity ?: return
        if (adaptiveTuningJob?.isActive == true) {
            showStatus("运行时正在执行其他调优生命周期操作。")
            return
        }
        adaptiveTuningJob = scope.launch(Dispatchers.IO) {
            try {
                val pending = runCatching { runtimeProfileStore.pendingTransaction(identity.identityHash) }
                    .getOrNull()
                if (pending == null) {
                    showStatus("当前没有可应用的 pending profile。")
                    return@launch
                }
                applyPendingRuntimeTransaction(pending)
            } finally {
                activeTuningJobId = null
                adaptiveTuningJob = null
            }
        }
    }

    fun discardPendingAgentProfile(
        runtimeProfileStore: ModelRuntimeProfileStore,
        store: UiStateStore,
        scope: CoroutineScope,
        application: Application,
        engine: McaInferenceService,
        activeRuntimeIdentity: ModelRuntimeIdentity?,
        getPendingProfileTransactionId: () -> String?,
        setPendingProfileTransactionId: (String?) -> Unit
    ) {
        val identity = activeRuntimeIdentity ?: return
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

    fun rollbackAgentProfile(
        scope: CoroutineScope,
        activeRuntimeIdentity: ModelRuntimeIdentity?,
        activeModelForRuntimeProfile: ModelManifest?,
        rollbackCommittedRuntimeProfile: suspend (ModelRuntimeIdentity, ModelManifest) -> Unit
    ) {
        val identity = activeRuntimeIdentity ?: return
        val model = activeModelForRuntimeProfile ?: return
        scope.launch(Dispatchers.IO) {
            rollbackCommittedRuntimeProfile(identity, model)
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
    store: UiStateStore,
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
            store.update {
                it.copy(
                    deviceProfile = device,
                    agentRecommendation = recommendation,
                    recommendedRemoteModels = sortRecommendedModels(
                        modelScopeClient.userFacingRecommendedModels(),
                        device
                    ),
                    agentLogs = agentLogger.recent(),
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
    store: UiStateStore,
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
    store.update {
        it.copy(
            params = updatedParams,
            rollbackParams = previousParams,
            agentLogs = agentLogger.recent(),
            statusMessage = "已应用助手生成建议；模型加载、线程、模板和 native 参数未被静默修改。"
        )
    }
}

internal fun rollbackAgentParamsFor(
    store: UiStateStore,
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
    store: UiStateStore,
    loadModel: (ModelManifest) -> Unit
) {
    val recommendation = store.current.agentRecommendation?.recommended
    val modelId = recommendation?.model?.id
    val model = store.current.models.firstOrNull { it.id == modelId }
    if (model != null) {
        loadModel(model)
    } else {
        store.update {
            it.copy(
                statusMessage = "推荐模型尚未在本机。请先到模型页下载或导入，再由 Agent 加载。"
            )
        }
    }
}

internal fun runAgentBenchmarkFor(
    store: UiStateStore,
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
            store.update {
                it.copy(
                    deviceProfile = device,
                    benchmark = result,
                    benchmarkHistory = benchmarkHistoryLogger.recent(),
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
