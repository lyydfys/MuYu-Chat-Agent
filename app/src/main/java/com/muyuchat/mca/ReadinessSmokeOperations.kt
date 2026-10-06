package com.muyuchat.mca

import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LoadParams
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelStoreRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

// R3-S26：稳定性自检纵切片自 MainViewModel 迁出（体逐字；依赖参数化/对象注入）。

/** R3-S26：两冒烟结果类型自 MainViewModel 迁出（private → internal，仅修饰符/文件变化）。 */
internal data class LocalGenerationSmokeResult(
    val visibleChars: Int,
    val completionTokens: Int,
    val decodeTps: Double
)

internal data class LocalApiStreamSmokeResult(
    val visibleContentSeen: Boolean,
    val doneSeen: Boolean,
    val stopCalls: Int
)

internal fun runAgentStabilitySmokeFor(
    scope: CoroutineScope,
    store: UiStateStore,
    engine: McaInferenceService,
    modelStore: ModelStoreRepository,
    cloudApiStore: CloudApiStore,
    readinessGate: ManagedRuntimeReadinessRefreshGate,
    readinessPort: ManagedRuntimeReadinessPort,
    deviceReader: DeviceProfileReader,
    diagnostics: DiagnosticsOperations,
    cancelGeneration: () -> Unit,
    busy: (String) -> Unit,
    loadSmoke: suspend (ModelManifest, LocalChatRuntime, LoadParams) -> Long,
    generationSmoke: suspend (GenerationParams) -> LocalGenerationSmokeResult,
    apiSmoke: suspend () -> LocalApiStreamSmokeResult,
    engineSmoke: suspend (GenerationParams) -> LocalApiStreamSmokeResult
) {
        scope.launch(Dispatchers.IO) {
            val initialState = store.current
            if (initialState.isGenerating) {
                store.showStatus("请先停止当前生成，再运行稳定性自检")
                return@launch
            }
            val requestedModel = initialState.models.firstOrNull { it.id == initialState.loadedModelId }
            if (requestedModel == null) {
                store.showStatus("请先加载一个本地推理模型，再运行稳定性自检")
                return@launch
            }
            busy("正在运行稳定性自检...")
            runCatching {
                cancelGeneration()
                engine.stopGeneration()
                val preflight = modelStore.validateForLoad(requestedModel.id)
                val validatedCatalog = publishChatCatalogSnapshot(readinessPort)
                if (!preflight.canLoad) error("加载前检查失败：${preflight.message}")
                val model = validatedCatalog.models.firstOrNull { it.id == requestedModel.id }
                    ?: requestedModel
                LocalModelMemoryAdmissionPolicy.evaluate(model, deviceReader.read()).blocker
                    ?.let { error("加载前内存检查失败：$it") }

                val runtime = model.runtime.toLocalChatRuntime()
                val loadParams = model.buildLoadParamsFor(initialState.params)
                val firstLoadMs = loadSmoke(model, runtime, loadParams)
                val secondLoadMs = loadSmoke(model, runtime, loadParams)
                val generation = generationSmoke(initialState.params)
                val apiGuard = apiSmoke()
                val apiEngine = engineSmoke(initialState.params)
                val nativeStats = engine.nativeStatsJson()
                val summary = buildString {
                    append("Reload ").append(firstLoadMs).append("ms / ").append(secondLoadMs).append("ms")
                    append(" · Stream ").append(generation.visibleChars).append(" 字")
                    append(" · API 防截断 ").append(if (apiGuard.stopCalls == 0 && apiGuard.visibleContentSeen && apiGuard.doneSeen) "通过" else "需排查")
                    append(" · HTTP 实流 ").append(if (apiEngine.visibleContentSeen && apiEngine.doneSeen) "通过" else "需排查")
                    append(" · n_ctx ").append(JSONObject(nativeStats).optInt("nCtx", initialState.params.nCtx))
                }
                modelStore.markLoaded(model.id)
                cloudApiStore.saveSelectedBackend(ChatBackend.LOCAL)
                readinessGate.invalidate()
                store.update { state ->
                    state.copy(
                        loadedModelId = model.id,
                        loadedModelName = model.displayName,
                        selectedChatBackend = ChatBackend.LOCAL,
                        models = modelStore.listModels(),
                        busy = false,
                        nativeStatsJson = nativeStats,
                        logs = engine.recentLogs(),
                        localStabilitySmokeSummary = summary,
                        diagnosticReport = diagnostics.buildDiagnosticReport(),
                        statusMessage = "稳定性自检通过：$summary"
                    )
                }
            }.onFailure { error ->
                val message = "稳定性自检失败：${error.message ?: "未知错误"}"
                store.update {
                    it.copy(
                        busy = false,
                        nativeStatsJson = engine.nativeStatsJson(),
                        localStabilitySmokeSummary = message,
                        diagnosticReport = diagnostics.buildDiagnosticReport(),
                        statusMessage = message
                    )
                }
            }
        }
    }
