package com.muyuchat.core.tuning

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.ThermalStatus
import com.muyuchat.core.engine.ActiveLoadedSignature
import com.muyuchat.core.engine.CanonicalParameterSet
import com.muyuchat.core.engine.EffectiveExecutionSignature
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LlamaAdvancedParams
import com.muyuchat.core.engine.LlamaCppRuntimeParameterAdapter
import com.muyuchat.core.engine.LiteRtLmRuntimeParameterAdapter
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.MnnRuntimeParameterAdapter
import com.muyuchat.core.engine.ModelExecutionProfile as EngineModelExecutionProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.ParameterSignatureSnapshot
import com.muyuchat.core.engine.QairtRuntimeParameterAdapter
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.RuntimeOverrideSignature
import com.muyuchat.core.engine.RuntimeParameterAdapter
import org.json.JSONObject
import kotlin.math.max

internal fun isSupportedLiteRtBackend(value: String): Boolean =
    value.trim().lowercase().replace('-', '_') in setOf(
        "cpu",
        "gpu",
        "npu",
        "google_tensor"
    )

/** Creates a request-scoped override without mutating the committed profile. */
object RuntimeOverridePolicy {
    fun forCurrentConditions(
        profile: TuningExecutionProfile,
        point: MeasurementPoint
    ): RuntimeThreadOverride? {
        val constrained = thermalSeverity(point.thermalStatus) >= thermalSeverity(ThermalStatus.Moderate) ||
            (point.batteryPercent in 0..19 && !point.isCharging) || point.lowMemoryTriggered
        if (!constrained || profile.hotExecution.nThreads <= 2 ||
            "n_threads" !in profile.engineProfile.hotExecutionValues.fields
        ) return null
        return RuntimeThreadOverride(
            profileId = profile.profileId,
            nThreads = minOf(profile.hotExecution.nThreads, 4).coerceAtLeast(2),
            nThreadsBatch = profile.hotExecution.nThreadsBatch?.coerceAtMost(4),
            reason = "当前温控、电量或内存压力触发 request-scoped 线程降级"
        )
    }
}

internal fun thermalSeverity(status: ThermalStatus): Int = when (status) {
    ThermalStatus.Unknown, ThermalStatus.None -> 0
    ThermalStatus.Light -> 1
    ThermalStatus.Moderate -> 2
    ThermalStatus.Severe -> 3
    ThermalStatus.Critical -> 4
    ThermalStatus.Emergency -> 5
    ThermalStatus.Shutdown -> 6
}

internal fun extensionsToJson(extensions: Map<String, String>): String {
    if (extensions.isEmpty()) return "{}"
    return extensions.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "\"${key.replace("\"", "\\\"")}\":\"${value.replace("\"", "\\\"")}\""
    }
}
