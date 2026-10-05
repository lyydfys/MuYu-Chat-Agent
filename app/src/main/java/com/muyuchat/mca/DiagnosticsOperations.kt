package com.muyuchat.mca

import android.app.Application
import com.muyuchat.core.advisor.AgentDecisionLog
import com.muyuchat.core.benchmark.BenchmarkHistoryRecord
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.McaInferenceService
import org.json.JSONArray
import org.json.JSONObject

/**
 * R3/C1（裁定 B：严格零行为子集）：诊断族读写 refreshLogs / refreshDiagnostics /
 * buildDiagnosticReport 自 MainViewModel 移出，engine 改由构造注入。
 *
 * 体逐字等价：state、日志器、设备档案均在原评测点经 lambda 调用；update 仍走同一
 * UiStateStore 流（CAS 语义不变）；报告体仅将 `_uiState.value` → currentState()、
 * `getApplication()` → 注入的 application 作参数替换。
 */
internal class DiagnosticsOperations(
    private val engine: McaInferenceService,
    private val currentState: () -> MainUiState,
    private val updateUiState: ((MainUiState) -> MainUiState) -> Unit,
    private val recentAgentLogs: () -> List<AgentDecisionLog>,
    private val recentBenchmarkHistory: () -> List<BenchmarkHistoryRecord>,
    private val currentDeviceProfile: () -> DeviceProfile,
    private val application: Application
) {
    fun refreshLogs() {
        // R3/S16: 诊断族写入统一经 UiStateStore（同一底层流，CAS 语义不变）。
        updateUiState {
            it.copy(
                logs = engine.recentLogs(),
                agentLogs = recentAgentLogs(),
                benchmarkHistory = recentBenchmarkHistory(),
                nativeStatsJson = engine.nativeStatsJson(),
                diagnosticReport = buildDiagnosticReport()
            )
        }
    }

    fun refreshDiagnostics() {
        // R3/S16: 诊断族写入统一经 UiStateStore。
        updateUiState {
            it.copy(
                nativeStatsJson = engine.nativeStatsJson(),
                benchmarkHistory = recentBenchmarkHistory(),
                diagnosticReport = buildDiagnosticReport(),
                statusMessage = "诊断报告已刷新"
            )
        }
    }

    fun buildDiagnosticReport(): String {
        val state = currentState()
        val loadedModel = state.models.firstOrNull { it.id == state.loadedModelId }
        val device = currentDeviceProfile()
        val nativeStats = runCatching { JSONObject(engine.nativeStatsJson()) }.getOrElse { JSONObject() }
        return JSONObject()
            .put("time", System.currentTimeMillis())
            .put("startup", StartupDiagnostics.read(application))
            .put("loadedModelId", state.loadedModelId)
            .put("loadedModelName", state.loadedModelName)
            .put("modelPath", loadedModel?.path ?: state.stats.modelPath)
            .put("modelSizeBytes", loadedModel?.sizeBytes ?: 0L)
            .put("modelSha256", loadedModel?.sha256)
            .put("modelQuant", loadedModel?.quant)
            .put("modelArchitecture", loadedModel?.architecture)
            .put("backendReady", nativeStats.optBoolean("backendReady"))
            .put("backendDeviceCount", nativeStats.optInt("backendDeviceCount"))
            .put("nativeLibDir", nativeStats.optString("nativeLibDir"))
            .put("nativeLastError", nativeStats.optString("lastError"))
            .put("runtimeStats", nativeStats)
            .put(
                "androidMemory",
                JSONObject()
                    .put("processPssKb", state.stats.nativePssKb)
                    .put("processRssKb", state.stats.processRssKb)
                    .put("nativeHeapKb", state.stats.nativeHeapKb)
                    .put("nativeHeapSizeKb", state.stats.nativeHeapSizeKb)
                    .put("javaHeapKb", state.stats.javaHeapKb)
                    .put("availMemKb", state.stats.availMemKb)
            )
            .put("deviceProfile", device.toJson())
            .put("params", JSONObject(state.params.toJson()))
            .put("lastBenchmark", state.benchmark?.toJson())
            .put("benchmarkHistory", JSONArray(state.benchmarkHistory.take(10).map { it.toJson() }))
            .put("lastAutoTuningSummary", state.lastAutoTuningSummary)
            .put("localStabilitySmokeSummary", state.localStabilitySmokeSummary)
            .toString(2)
    }
}
