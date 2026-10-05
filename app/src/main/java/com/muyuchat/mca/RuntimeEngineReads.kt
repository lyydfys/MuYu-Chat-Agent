package com.muyuchat.mca

import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.telemetry.RuntimeMetrics
import org.json.JSONObject

// R3/S15: Runtime 读取缝——从 MainViewModel 移出的 engine 读取封装（行为逐字等价：
// runCatching + 固定默认值）。后续 R3 的 Runtime owner 将复用本缝，而不是各自读 engine。
internal fun McaInferenceService.nativeStatsJsonOrEmpty(): String =
    runCatching { nativeStatsJson() }.getOrDefault("{}")

internal fun McaInferenceService.recentEngineLogsOrEmpty(): List<RuntimeMetrics> =
    runCatching { recentLogs() }.getOrDefault(emptyList())

// R3/S18: 从 MainViewModel 移出的 API 生成参数投影（engine 活跃 profile × 状态合并；体逐字保留，engine 作参数）。
internal fun engineApiGenerationParams(engine: McaInferenceService, state: MainUiState): GenerationParams {
    val active = engine.activeExecutionProfile()
        .takeIf { state.loadedModelId != null && engine.stats.value.loaded }
    return if (active == null) state.params else mergedExecutionProfile(state.params, active)
}

// R3/S19: 从 MainViewModel 移出的 runtime profile JSON 投影（identity 参数化；体逐字等价，
// 仅 activeRuntimeIdentity → identity）。
internal fun runtimeProfileJsonFor(
    state: MainUiState,
    engine: McaInferenceService,
    identity: ModelRuntimeIdentity?
): String {
    val runtimeLoaded = state.loadedModelId != null && engine.stats.value.loaded
    val profile = engine.activeExecutionProfile().takeIf { runtimeLoaded }
    val signatures = engine.parameterSignatureSnapshot().takeIf { runtimeLoaded }
    return JSONObject()
        .put("modelId", state.loadedModelId)
        .put("modelName", state.loadedModelName)
        .put("identityHash", identity?.identityHash.takeIf { runtimeLoaded })
        .put("profileId", if (runtimeLoaded) state.profileId ?: profile?.profileId else null)
        .put("revision", if (runtimeLoaded) state.revision ?: profile?.revision else null)
        .put("recordState", state.profileRecordState.name.lowercase())
        .put("verification", state.verification.name.lowercase())
        .put("engineLifecycle", state.engineLifecycle.name.lowercase())
        .put("reloadRequired", state.reloadRequired)
        .put("signatures", JSONObject().apply {
            signatures?.let { value ->
                put("desired", value.desired.digest)
                put("resolvedLoad", value.resolved.digest)
                put("activeLoaded", value.active?.digest)
                put("committedExecution", value.committed.digest)
                put("runtimeOverride", value.override.digest)
                put("effectiveExecution", value.effective?.digest)
            }
        })
        .put("pending", state.pendingProfile?.let { pending ->
            JSONObject()
                .put("profileId", pending.profileId)
                .put("revision", pending.revision)
                .put("summary", pending.summary)
                .put("readyToApply", pending.readyToApply)
        } ?: JSONObject.NULL)
        .put("rollback", state.rollbackProfile?.let { rollback ->
            JSONObject()
                .put("profileId", rollback.targetProfileId)
                .put("revision", rollback.targetRevision)
                .put("summary", rollback.summary)
                .put("available", rollback.available)
        } ?: JSONObject.NULL)
        .toString()
}
