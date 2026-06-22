package com.muyuchat.core.tuning

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.ThermalStatus
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import org.json.JSONObject
import kotlin.math.roundToInt

enum class PerformanceMode(val label: String) {
    Speed("速度优先"),
    Balanced("均衡"),
    Quality("质量优先"),
    LongContext("长上下文"),
    PowerSave("省电")
}

data class UserPreference(
    val mode: PerformanceMode = PerformanceMode.Balanced,
    val allowExperimentalBackend: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("mode", mode.name)
        .put("label", mode.label)
        .put("allowExperimentalBackend", allowExperimentalBackend)

    companion object {
        fun fromJson(json: String): UserPreference {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return UserPreference()
            val mode = PerformanceMode.entries.firstOrNull {
                it.name.equals(root.optString("mode"), ignoreCase = true) ||
                    it.label == root.optString("mode")
            } ?: PerformanceMode.Balanced
            return UserPreference(
                mode = mode,
                allowExperimentalBackend = root.optBoolean("allowExperimentalBackend", false)
            )
        }
    }
}

data class TuningPlan(
    val nCtx: Int,
    val nPredict: Int,
    val nThreads: Int,
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val minP: Float,
    val repeatPenalty: Float,
    val presencePenalty: Float,
    val frequencyPenalty: Float = 0.0f,
    val seed: Int? = null,
    val mmap: Boolean = true,
    val mlock: Boolean = false,
    val backend: String = "cpu",
    val reason: String = ""
) {
    fun toGenerationParams(
        systemPrompt: String = GenerationParams().systemPrompt,
        reasoningMode: ReasoningMode = GenerationParams().reasoningMode,
        hideReasoning: Boolean = GenerationParams().hideReasoning
    ): GenerationParams =
        GenerationParams(
            nCtx = nCtx,
            nPredict = nPredict,
            nThreads = nThreads,
            temperature = temperature,
            topK = topK,
            topP = topP,
            minP = minP,
            repeatPenalty = repeatPenalty,
            presencePenalty = presencePenalty,
            frequencyPenalty = frequencyPenalty,
            seed = seed,
            systemPrompt = systemPrompt,
            reasoningMode = reasoningMode,
            hideReasoning = hideReasoning,
            advancedJson = "{}"
        )

    fun toJson(): JSONObject = JSONObject()
        .put("n_ctx", nCtx)
        .put("n_predict", nPredict)
        .put("n_threads", nThreads)
        .put("temperature", temperature.toDouble())
        .put("top_k", topK)
        .put("top_p", topP.toDouble())
        .put("min_p", minP.toDouble())
        .put("repeat_penalty", repeatPenalty.toDouble())
        .put("repetition_penalty", repeatPenalty.toDouble())
        .put("presence_penalty", presencePenalty.toDouble())
        .put("frequency_penalty", frequencyPenalty.toDouble())
        .put("seed", seed)
        .put("mmap", mmap)
        .put("mlock", mlock)
        .put("backend", backend)
        .put("reason", reason)

    companion object {
        fun fromParams(params: GenerationParams, reason: String = "当前手动参数"): TuningPlan =
            TuningPlan(
                nCtx = params.nCtx,
                nPredict = params.nPredict,
                nThreads = params.nThreads,
                temperature = params.temperature,
                topK = params.topK,
                topP = params.topP,
                minP = params.minP,
                repeatPenalty = params.repeatPenalty,
                presencePenalty = params.presencePenalty,
                frequencyPenalty = params.frequencyPenalty,
                seed = params.seed,
                reason = reason
            )
    }
}

class TuningEngine {
    fun recommend(
        device: DeviceProfile,
        modelParametersB: Double?,
        preference: UserPreference = UserPreference(),
        lastDecodeTps: Double? = null
    ): TuningPlan {
        val cores = device.cpuCores.coerceAtLeast(1)
        val bigCores = device.estimatedBigCores.coerceIn(1, cores)
        val ramGb = device.displayTotalRamBytes / GB
        val hotOrLowBattery = device.thermalStatus >= ThermalStatus.Moderate ||
            (device.batteryPercent in 0..19 && !device.isCharging)
        val scale = modelParametersB ?: 3.0

        var nCtx = when {
            ramGb < 6 -> 4096
            scale <= 1.6 -> 8192
            scale <= 3.5 -> if (ramGb >= 8) 8192 else 4096
            scale <= 10.5 && ramGb >= 12 -> 8192
            else -> 4096
        }
        var nPredict = when (preference.mode) {
            PerformanceMode.Speed -> 2048
            PerformanceMode.Balanced -> 4096
            PerformanceMode.Quality -> 8192
            PerformanceMode.LongContext -> 8192
            PerformanceMode.PowerSave -> 1024
        }
        var threads = when (preference.mode) {
            PerformanceMode.Speed -> (bigCores + 2).coerceAtMost(cores)
            PerformanceMode.Balanced -> bigCores.coerceAtLeast((cores * 0.6f).roundToInt())
            PerformanceMode.Quality -> (bigCores + 1).coerceAtMost(cores)
            PerformanceMode.LongContext -> bigCores.coerceAtMost(cores)
            PerformanceMode.PowerSave -> bigCores.coerceAtMost(4)
        }.coerceIn(1, 12)

        if (preference.mode == PerformanceMode.LongContext) {
            nCtx = when {
                ramGb >= 16 && scale <= 10.5 -> 16384
                ramGb >= 12 && scale <= 7.5 -> 16384
                ramGb >= 8 && scale <= 3.5 -> 8192
                else -> nCtx.coerceAtLeast(4096)
            }
        }
        if (hotOrLowBattery) {
            threads = threads.coerceAtMost(4)
            nCtx = nCtx.coerceAtMost(8192)
            nPredict = nPredict.coerceAtMost(2048)
        }
        val measuredTps = lastDecodeTps
        if ((measuredTps ?: Double.MAX_VALUE) < 4.0) {
            threads = (threads - 2).coerceAtLeast(2)
            nCtx = nCtx.coerceAtMost(8192)
            nPredict = nPredict.coerceAtMost(4096)
        } else if ((measuredTps ?: Double.MAX_VALUE) < 6.0) {
            threads = (threads - 1).coerceAtLeast(2)
            nCtx = nCtx.coerceAtMost(8192)
            nPredict = nPredict.coerceAtMost(4096)
        } else if (measuredTps != null && measuredTps >= 14.0 && !hotOrLowBattery) {
            nPredict = when (preference.mode) {
                PerformanceMode.PowerSave -> nPredict.coerceAtLeast(1024)
                PerformanceMode.Speed -> nPredict.coerceAtLeast(2048)
                PerformanceMode.Balanced -> nPredict.coerceAtLeast(4096)
                PerformanceMode.Quality -> nPredict.coerceAtLeast(8192)
                PerformanceMode.LongContext -> nPredict.coerceAtLeast(8192)
            }
        }

        val sampling = when (preference.mode) {
            PerformanceMode.Speed, PerformanceMode.Balanced, PerformanceMode.PowerSave -> SamplingPreset.nonThinkingText()
            PerformanceMode.Quality, PerformanceMode.LongContext -> SamplingPreset.thinkingText()
        }
        val reason = buildString {
            append("${preference.mode.label}：")
            append("按 ${device.socFamily.name}、${cores} 核、约 ${ramGb}GB 内存推荐。")
            if (hotOrLowBattery) append(" 当前电量或温控状态偏紧，已降到保守参数。")
            when {
                measuredTps == null -> Unit
                measuredTps < 4.0 -> append(" 短基准速度低于 4 token/s，已明显收缩上下文和输出预算。")
                measuredTps < 6.0 -> append(" 短基准速度偏低，已降低线程/输出预算。")
                measuredTps >= 14.0 && !hotOrLowBattery -> append(" 短基准速度充足，保持当前档位的输出预算。")
                else -> append(" 短基准速度正常，采用稳态参数。")
            }
        }
        return TuningPlan(
            nCtx = nCtx,
            nPredict = nPredict,
            nThreads = threads,
            temperature = sampling.temperature,
            topK = sampling.topK,
            topP = sampling.topP,
            minP = sampling.minP,
            repeatPenalty = sampling.repeatPenalty,
            presencePenalty = sampling.presencePenalty,
            frequencyPenalty = sampling.frequencyPenalty,
            mmap = true,
            mlock = false,
            backend = "cpu",
            reason = reason
        )
    }

    private companion object {
        const val GB = 1024L * 1024L * 1024L
    }
}

private data class SamplingPreset(
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val minP: Float,
    val repeatPenalty: Float,
    val presencePenalty: Float,
    val frequencyPenalty: Float = 0.0f
) {
    companion object {
        fun nonThinkingText(): SamplingPreset = SamplingPreset(
            temperature = 0.7f,
            topK = 20,
            topP = 0.8f,
            minP = 0.0f,
            repeatPenalty = 1.0f,
            presencePenalty = 0.0f
        )

        fun thinkingText(): SamplingPreset = SamplingPreset(
            temperature = 0.6f,
            topK = 20,
            topP = 0.95f,
            minP = 0.0f,
            repeatPenalty = 1.08f,
            presencePenalty = 0.0f,
            frequencyPenalty = 0.2f
        )
    }
}
