package com.muyuchat.core.tuning

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.ThermalStatus
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LlamaAdvancedParams
import com.muyuchat.core.engine.ModelRuntimeIdentity
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
    val advancedJson: String = "{}",
    val backend: String = "cpu",
    val reason: String = ""
) {
    fun applyTo(baseParams: GenerationParams): GenerationParams {
        val planLoadPatch = JSONObject()
            .put(LlamaAdvancedParams.KEY_MMAP, mmap)
            .put(LlamaAdvancedParams.KEY_MLOCK, mlock)
            .toString()
        val planAdvanced = sanitizeAdvancedForBackend(
            LlamaAdvancedParams.merge(advancedJson, planLoadPatch).json,
            backend
        )
        val cleanBaseAdvanced = sanitizeAdvancedForBackend(baseParams.advancedJson, backend)
        val mergedAdvanced = sanitizeAdvancedForBackend(
            LlamaAdvancedParams.merge(cleanBaseAdvanced, planAdvanced).json,
            backend
        )
        return baseParams.copy(
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
            seed = seed ?: baseParams.seed,
            advancedJson = mergedAdvanced
        )
    }

    fun toGenerationParams(
        systemPrompt: String = GenerationParams().systemPrompt,
        reasoningMode: ReasoningMode = GenerationParams().reasoningMode,
        hideReasoning: Boolean = GenerationParams().hideReasoning
    ): GenerationParams = applyTo(
        GenerationParams(
            systemPrompt = systemPrompt,
            reasoningMode = reasoningMode,
            hideReasoning = hideReasoning
        )
    )

    fun toJson(): JSONObject {
        val advanced = LlamaAdvancedParams.parse(
            sanitizeAdvancedForBackend(
                LlamaAdvancedParams.merge(
                    advancedJson,
                    JSONObject()
                        .put(LlamaAdvancedParams.KEY_MMAP, mmap)
                        .put(LlamaAdvancedParams.KEY_MLOCK, mlock)
                        .toString()
                ).json,
                backend
            )
        )
        return JSONObject()
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
            .apply {
                advanced.putCanonicalFields(this)
                put("advanced_json", advanced.advancedJsonValue())
            }
    }

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
                mmap = LlamaAdvancedParams.parse(params.advancedJson).params?.mmap ?: true,
                mlock = LlamaAdvancedParams.parse(params.advancedJson).params?.mlock ?: false,
                advancedJson = params.advancedJson,
                reason = reason
            )
    }
}

private fun sanitizeAdvancedForBackend(rawJson: String, backend: String): String {
    if (!backend.equals("cpu", ignoreCase = true)) return rawJson
    val parsed = LlamaAdvancedParams.parse(rawJson)
    val root = parsed.params?.toJsonObject() ?: return rawJson
    setOf(
        LlamaAdvancedParams.KEY_N_GPU_LAYERS,
        LlamaAdvancedParams.KEY_MAIN_GPU,
        LlamaAdvancedParams.KEY_SPLIT_MODE,
        LlamaAdvancedParams.KEY_N_CPU_MOE
    ).forEach(root::remove)
    return LlamaAdvancedParams.parse(root.toString()).advancedJsonString()
}

class TuningEngine {
    /**
     * New tuning entry point. The persistent profile is computed without
     * transient thermal/battery pressure; such pressure becomes a request-only
     * [RuntimeThreadOverride]. The legacy [recommend] API remains for existing UI.
     */
    fun recommendAdaptive(
        device: DeviceProfile,
        modelParametersB: Double?,
        modelName: String? = null,
        runtimeIdentity: ModelRuntimeIdentity,
        modelKnowledge: ModelKnowledgeLevel = ModelKnowledgeLevel.KNOWN,
        capabilities: ModelTuningCapabilities = ModelTuningCapabilities(
            runtime = runtimeIdentity.runtime.toTuningRuntime(),
            knowledgeLevel = modelKnowledge
        ),
        preference: UserPreference = UserPreference(),
        lastDecodeTps: Double? = null
    ): AdaptiveTuningRecommendation {
        val stableDevice = device.copy(
            thermalStatus = ThermalStatus.None,
            batteryPercent = 100,
            isCharging = true,
            isLowMemory = false
        )
        val stablePlan = recommend(
            device = stableDevice,
            modelParametersB = modelParametersB,
            modelName = modelName,
            preference = preference,
            lastDecodeTps = lastDecodeTps
        )
        val adaptivePlan = stablePlan.withVerifiedSpeculativeMtp(capabilities)
        return adaptivePlan.toAdaptive(
            runtimeIdentity = runtimeIdentity,
            capabilities = capabilities.copy(knowledgeLevel = modelKnowledge),
            profileKind = preference.mode.toExecutionProfileKind(),
            device = device
        )
    }

    fun recommend(
        device: DeviceProfile,
        modelParametersB: Double?,
        modelName: String? = null,
        preference: UserPreference = UserPreference(),
        lastDecodeTps: Double? = null
    ): TuningPlan {
        val cores = device.cpuCores.coerceAtLeast(1)
        val bigCores = device.estimatedBigCores.coerceIn(1, cores)
        val ramGb = device.displayTotalRamBytes / GB
        val hotOrLowBattery = device.thermalStatus >= ThermalStatus.Moderate ||
            (device.batteryPercent in 0..19 && !device.isCharging)
        val scale = modelParametersB ?: 3.0
        val qwen36A3bMtp = modelName.isQwen36A3bMtpModel()

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
        if (qwen36A3bMtp) {
            nCtx = 4096
        }

        val sampling = if (qwen36A3bMtp) {
            SamplingPreset.qwen36A3bMtp()
        } else {
            when (preference.mode) {
                PerformanceMode.Speed, PerformanceMode.Balanced, PerformanceMode.PowerSave -> SamplingPreset.nonThinkingText()
                PerformanceMode.Quality, PerformanceMode.LongContext -> SamplingPreset.thinkingText()
            }
        }
        val reason = buildString {
            append("${preference.mode.label}：")
            append("按 ${device.socFamily.name}、${cores} 核、约 ${ramGb}GB 内存推荐。")
            if (qwen36A3bMtp) {
                append(" 已识别 Qwen3.6 35B-A3B/APEX MTP，按激活约 3B 的 Android CPU 安全预设配置。")
            }
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
            // The legacy recommendation API has no parsed GGUF metadata or
            // per-request draft/accept witness. Keep its conservative cache
            // tuning, but never turn an MTP-looking filename into a
            // speculative-decoding request. The adaptive path receives a
            // metadata-backed capability and validates it in the isolated
            // canary before an MTP profile can be committed.
            advancedJson = if (qwen36A3bMtp) qwen36A3bMtpAdvancedJson() else "{}",
            backend = "cpu",
            reason = reason
        )
    }

    private companion object {
        const val GB = 1024L * 1024L * 1024L

        fun String?.isQwen36A3bMtpModel(): Boolean {
            val value = this?.lowercase().orEmpty()
            return ("qwen3.6" in value || "qwen36" in value) &&
                ("35b-a3b" in value || "apex" in value || "mtp" in value)
        }

        fun qwen36A3bMtpAdvancedJson(): String = LlamaAdvancedParams(
            nBatch = 2048,
            nUbatch = 256,
            cacheTypeK = "q4_0",
            cacheTypeV = "q4_0",
            flashAttn = "on",
            cacheReuse = 256,
            specType = "none",
            specDraftNMax = 0,
            nParallel = 1,
            perf = true,
            useJinja = true
        ).toJsonString()

        fun TuningPlan.withVerifiedSpeculativeMtp(
            capabilities: ModelTuningCapabilities
        ): TuningPlan {
            if (!capabilities.supportsSpeculativeMtp) return this
            val advanced = LlamaAdvancedParams.parse(advancedJson).params ?: return this
            if (advanced.specType != "none" || advanced.specDraftNMax != 0) return this
            return copy(
                advancedJson = LlamaAdvancedParams.merge(
                    advancedJson,
                    JSONObject()
                        .put(LlamaAdvancedParams.KEY_SPEC_TYPE, "draft-mtp")
                        .put(LlamaAdvancedParams.KEY_SPEC_DRAFT_N_MAX, 2)
                        .toString()
                ).json
            )
        }
    }
}

private fun PerformanceMode.toExecutionProfileKind(): ExecutionProfileKind = when (this) {
    PerformanceMode.Speed -> ExecutionProfileKind.SPEED
    PerformanceMode.Balanced -> ExecutionProfileKind.BALANCED
    PerformanceMode.Quality -> ExecutionProfileKind.QUALITY
    PerformanceMode.LongContext -> ExecutionProfileKind.LONG_CONTEXT
    PerformanceMode.PowerSave -> ExecutionProfileKind.POWER_SAVE
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

        fun qwen36A3bMtp(): SamplingPreset = SamplingPreset(
            temperature = 0.6f,
            topK = 20,
            topP = 0.95f,
            minP = 0.0f,
            repeatPenalty = 1.05f,
            presencePenalty = 0.0f,
            frequencyPenalty = 0.0f
        )
    }
}
