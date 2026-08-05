package com.muyuchat.core.tuning

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.ThermalStatus
import com.muyuchat.core.engine.ActiveLoadedSignature
import com.muyuchat.core.engine.CanonicalParameterSet
import com.muyuchat.core.engine.EffectiveExecutionSignature
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LlamaAdvancedParams
import com.muyuchat.core.engine.LlamaCppRuntimeParameterAdapter
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

/** Runtime families intentionally kept independent from model-store/UI enums. */
enum class TuningRuntime {
    LLAMA_CPP,
    MNN,
    QAIRT,
    UNKNOWN
}

enum class ModelKnowledgeLevel {
    KNOWN,
    UNKNOWN
}

enum class ExecutionProfileKind {
    SAFE_BASELINE,
    BALANCED,
    SPEED,
    QUALITY,
    LONG_CONTEXT,
    POWER_SAVE
}

enum class ProfileVerificationLevel {
    UNVERIFIED,
    SAFE,
    COMPATIBLE,
    DEVICE_VERIFIED
}

enum class ProfileEligibility {
    ELIGIBLE,
    BLOCKED_WITH_ACTION
}

/** Fields that require a native session reload. Sampling fields never belong here. */
data class LoadBoundExecutionParams(
    val nCtx: Int,
    val nBatch: Int? = null,
    val nUbatch: Int? = null,
    val cacheTypeK: String? = null,
    val cacheTypeV: String? = null,
    val flashAttention: String? = null,
    val gpuLayers: Int? = null,
    val mainGpu: Int? = null,
    val cpuMoeLayers: Int? = null,
    val speculativeType: String? = null,
    val speculativeDraftMax: Int? = null,
    val nParallel: Int = 1,
    val mmap: Boolean = true,
    val mlock: Boolean = false,
    val backend: String = "cpu",
    val extensions: Map<String, String> = emptyMap()
) {
    init {
        require(nCtx > 0) { "nCtx must be positive" }
        require(nParallel > 0) { "nParallel must be positive" }
        require(nBatch == null || nBatch > 0) { "nBatch must be positive" }
        require(nUbatch == null || nUbatch > 0) { "nUbatch must be positive" }
        require(nBatch == null || nUbatch == null || nUbatch <= nBatch) {
            "nUbatch must not exceed nBatch"
        }
    }

}

/** Fields explicitly registered as hot execution values by a runtime adapter. */
data class HotExecutionParams(
    val nThreads: Int,
    val nThreadsBatch: Int? = null,
    val extensions: Map<String, String> = emptyMap()
) {
    init {
        require(nThreads > 0) { "nThreads must be positive" }
        require(nThreadsBatch == null || nThreadsBatch > 0) { "nThreadsBatch must be positive" }
    }

}

/**
 * The only persistent tuning output. It contains execution values and cannot
 * silently carry temperature, prompts, reasoning mode, seed, or output length.
 */
data class TuningExecutionProfile(
    val engineProfile: EngineModelExecutionProfile,
    val kind: ExecutionProfileKind,
    val loadBound: LoadBoundExecutionParams,
    val hotExecution: HotExecutionParams,
    val verificationLevel: ProfileVerificationLevel = ProfileVerificationLevel.UNVERIFIED,
    val eligibility: ProfileEligibility = ProfileEligibility.ELIGIBLE,
    val blockedAction: String? = null,
    val reason: String = ""
) {
    init {
        require(eligibility == ProfileEligibility.ELIGIBLE || !blockedAction.isNullOrBlank()) {
            "blocked profiles must provide an action"
        }
        (engineProfile.resolvedLoadBoundValues.value("n_ctx") as? Number)?.toInt()?.let { resolved ->
            require(loadBound.nCtx == resolved) { "typed load view must match the authoritative engine profile" }
        }
        (engineProfile.hotExecutionValues.value("n_threads") as? Number)?.toInt()?.let { resolved ->
            require(hotExecution.nThreads == resolved) { "typed hot view must match the authoritative engine profile" }
        }
    }

    val profileId: String
        get() = engineProfile.profileId

    val revision: Long
        get() = engineProfile.revision

    val identityHash: String
        get() = engineProfile.runtimeIdentity.identityHash

    val runtimeIdentity: ModelRuntimeIdentity
        get() = engineProfile.runtimeIdentity

    fun expectedSignatures(runtimeOverride: RuntimeThreadOverride? = null): CandidateExpectedSignatures {
        val active = ActiveLoadedSignature.of(
            engineProfile.runtimeIdentity,
            engineProfile.resolvedLoadBoundValues
        )
        val overrideSignature = runtimeOverride?.let {
            RuntimeOverrideSignature.of(
                engineProfile.runtimeIdentity,
                effectiveHot(it).toCanonicalParameterSet()
            )
        } ?: RuntimeOverrideSignature.none(engineProfile.runtimeIdentity)
        val effective = EffectiveExecutionSignature.of(
            engineProfile.runtimeIdentity,
            active,
            engineProfile.committedExecutionSignature,
            overrideSignature
        )
        return CandidateExpectedSignatures(activeLoaded = active, effectiveExecution = effective)
    }

    fun effectiveHot(runtimeOverride: RuntimeThreadOverride?): HotExecutionParams {
        if (runtimeOverride == null) return hotExecution
        require(runtimeOverride.profileId == profileId) {
            "Runtime override belongs to ${runtimeOverride.profileId}, not $profileId"
        }
        return hotExecution.copy(
            nThreads = runtimeOverride.nThreads,
            nThreadsBatch = runtimeOverride.nThreadsBatch ?: hotExecution.nThreadsBatch
        )
    }
}

data class CandidateExpectedSignatures(
    val activeLoaded: ActiveLoadedSignature,
    val effectiveExecution: EffectiveExecutionSignature
)

/** Sampling/output advice is opt-in and is never committed as an execution profile. */
data class GenerationRecommendation(
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val minP: Float,
    val repeatPenalty: Float,
    val presencePenalty: Float,
    val frequencyPenalty: Float,
    val maxOutputTokens: Int,
    val reasoningMode: ReasoningMode = ReasoningMode.OFF,
    val explicitApplyRequired: Boolean = true,
    val reason: String = ""
) {
    fun applyTo(base: GenerationParams): GenerationParams = base.copy(
        nPredict = maxOutputTokens,
        temperature = temperature,
        topK = topK,
        topP = topP,
        minP = minP,
        repeatPenalty = repeatPenalty,
        presencePenalty = presencePenalty,
        frequencyPenalty = frequencyPenalty,
        reasoningMode = reasoningMode
    )
}

/** Fixed, disposable generation settings used only by the correctness canary. */
data class CanaryEvaluationParams(
    val suiteId: String = CorrectnessSuite.MINIMUM_TEXT_SUITE_ID,
    val promptVersion: String = "text-v1",
    val seed: Int = 17,
    val temperature: Float = 0.0f,
    val topK: Int = 1,
    val topP: Float = 1.0f,
    val minP: Float = 0.0f,
    val repeatPenalty: Float = 1.0f,
    val presencePenalty: Float = 0.0f,
    val frequencyPenalty: Float = 0.0f,
    val maxOutputTokens: Int = 96,
    val systemPrompt: String = "You are a deterministic local-model validation runner.",
    val reasoningMode: ReasoningMode = ReasoningMode.OFF
) {
    fun toGenerationParams(profile: TuningExecutionProfile): GenerationParams = GenerationParams(
        nCtx = profile.loadBound.nCtx,
        nPredict = maxOutputTokens,
        nThreads = profile.hotExecution.nThreads,
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
        hideReasoning = true
    )
}

data class RuntimeThreadOverride(
    val profileId: String,
    val nThreads: Int,
    val nThreadsBatch: Int? = null,
    val reason: String,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    init {
        require(nThreads > 0) { "override nThreads must be positive" }
    }
}

data class AdaptiveTuningRecommendation(
    val executionProfile: TuningExecutionProfile,
    val generationRecommendation: GenerationRecommendation,
    val canaryParams: CanaryEvaluationParams,
    val runtimeOverride: RuntimeThreadOverride? = null
) {
    /** Compatibility only; new callers must apply each output through its own lifecycle. */
    fun toLegacyPlan(): TuningPlan {
        val load = executionProfile.loadBound
        val hot = executionProfile.hotExecution
        val advanced = LlamaAdvancedParams(
            nThreadsBatch = hot.nThreadsBatch,
            nBatch = load.nBatch,
            nUbatch = load.nUbatch,
            nGpuLayers = load.gpuLayers,
            mainGpu = load.mainGpu,
            nCpuMoe = load.cpuMoeLayers,
            cacheTypeK = load.cacheTypeK,
            cacheTypeV = load.cacheTypeV,
            flashAttn = load.flashAttention,
            specType = load.speculativeType,
            specDraftNMax = load.speculativeDraftMax,
            nParallel = load.nParallel,
            mmap = load.mmap,
            mlock = load.mlock,
            preservedJson = extensionsToJson(load.extensions + hot.extensions)
        ).toJsonString()
        return TuningPlan(
            nCtx = load.nCtx,
            nPredict = generationRecommendation.maxOutputTokens,
            nThreads = hot.nThreads,
            temperature = generationRecommendation.temperature,
            topK = generationRecommendation.topK,
            topP = generationRecommendation.topP,
            minP = generationRecommendation.minP,
            repeatPenalty = generationRecommendation.repeatPenalty,
            presencePenalty = generationRecommendation.presencePenalty,
            frequencyPenalty = generationRecommendation.frequencyPenalty,
            mmap = load.mmap,
            mlock = load.mlock,
            advancedJson = advanced,
            backend = load.backend,
            reason = executionProfile.reason
        )
    }
}

fun TuningPlan.toAdaptive(
    runtimeIdentity: ModelRuntimeIdentity,
    capabilities: ModelTuningCapabilities,
    profileKind: ExecutionProfileKind,
    device: DeviceProfile
): AdaptiveTuningRecommendation {
    require(capabilities.runtime.accepts(runtimeIdentity.runtime)) {
        "Runtime mismatch: identity=${runtimeIdentity.runtime} capabilities=${capabilities.runtime}"
    }
    val safeBaseline = SafeBaselineFactory.create(runtimeIdentity, device, capabilities)
    val executionProfile = if (
        capabilities.knowledgeLevel == ModelKnowledgeLevel.UNKNOWN ||
        safeBaseline.eligibility == ProfileEligibility.BLOCKED_WITH_ACTION
    ) {
        safeBaseline
    } else {
        val advanced = LlamaAdvancedParams.parse(advancedJson).params
        val requestedSpeculativeType = advanced?.specType
        val speculativeType = when {
            !capabilities.supportsSpeculativeMtp -> null
            requestedSpeculativeType != null -> requestedSpeculativeType
            else -> "draft-mtp"
        }
        val speculativeDraftMax = when {
            !capabilities.supportsSpeculativeMtp -> null
            speculativeType == "draft-mtp" -> advanced?.specDraftNMax ?: 2
            else -> 0
        }
        val cappedContext = capabilities.maxContextTokens
            ?.let { maximum -> nCtx.coerceAtMost(maximum.coerceAtLeast(512)) }
            ?: nCtx
        val llamaRuntime = capabilities.runtime == TuningRuntime.LLAMA_CPP
        buildTuningExecutionProfile(
            runtimeIdentity = runtimeIdentity,
            kind = profileKind,
            loadBound = LoadBoundExecutionParams(
                nCtx = cappedContext,
                nBatch = advanced?.nBatch.takeIf { capabilities.supportsBatchTuning },
                nUbatch = advanced?.nUbatch.takeIf { capabilities.supportsBatchTuning },
                cacheTypeK = advanced?.cacheTypeK.takeIf { capabilities.supportsQuantizedKv },
                cacheTypeV = advanced?.cacheTypeV.takeIf { capabilities.supportsQuantizedKv },
                flashAttention = advanced?.flashAttn.takeIf { capabilities.supportsFlashAttention },
                gpuLayers = advanced?.nGpuLayers.takeIf { capabilities.supportsGpuOffload }
                    ?: if (llamaRuntime) 0 else null,
                mainGpu = advanced?.mainGpu.takeIf { capabilities.supportsGpuOffload }
                    ?: if (llamaRuntime) 0 else null,
                cpuMoeLayers = advanced?.nCpuMoe.takeIf { capabilities.supportsCpuMoeTuning },
                speculativeType = speculativeType,
                speculativeDraftMax = speculativeDraftMax,
                nParallel = advanced?.nParallel ?: 1,
                mmap = advanced?.mmap ?: mmap,
                mlock = advanced?.mlock ?: mlock,
                backend = when (capabilities.runtime) {
                    TuningRuntime.LLAMA_CPP, TuningRuntime.UNKNOWN -> backend.ifBlank { "cpu" }
                    TuningRuntime.MNN -> "cpu"
                    TuningRuntime.QAIRT -> "qairt"
                }
            ),
            hotExecution = HotExecutionParams(
                nThreads = nThreads,
                nThreadsBatch = advanced?.nThreadsBatch
            ),
            reason = reason,
            profileId = "${profileKind.name.lowercase()}-${runtimeIdentity.identityHash.take(16)}"
        )
    }
    val unknown = capabilities.knowledgeLevel == ModelKnowledgeLevel.UNKNOWN
    val generation = GenerationRecommendation(
        temperature = if (unknown) 0.7f else temperature,
        topK = if (unknown) 20 else topK,
        topP = if (unknown) 0.8f else topP,
        minP = if (unknown) 0.0f else minP,
        repeatPenalty = if (unknown) 1.0f else repeatPenalty,
        presencePenalty = if (unknown) 0.0f else presencePenalty,
        frequencyPenalty = if (unknown) 0.0f else frequencyPenalty,
        maxOutputTokens = if (unknown) nPredict.coerceAtMost(2048) else nPredict,
        explicitApplyRequired = true,
        reason = if (unknown) {
            "未知模型仅提供保守、可选的生成建议；不会根据文件名猜测模型专项采样。"
        } else {
            "这是可选的助手生成建议，不会随执行 profile 自动写入。"
        }
    )
    val currentPoint = MeasurementPoint(
        thermalStatus = device.thermalStatus,
        batteryPercent = device.batteryPercent,
        isCharging = device.isCharging,
        availableMemoryBytes = device.availableRamBytes,
        lowMemoryTriggered = device.isLowMemory
    )
    return AdaptiveTuningRecommendation(
        executionProfile = executionProfile,
        generationRecommendation = generation,
        canaryParams = CanaryEvaluationParams(),
        runtimeOverride = RuntimeOverridePolicy.forCurrentConditions(executionProfile, currentPoint)
    )
}

data class ModelTuningCapabilities(
    val runtime: TuningRuntime,
    val knowledgeLevel: ModelKnowledgeLevel = ModelKnowledgeLevel.KNOWN,
    val metadataReadable: Boolean = true,
    val chatTemplateReady: Boolean = true,
    val maxContextTokens: Int? = null,
    val supportsBatchTuning: Boolean = false,
    val supportsQuantizedKv: Boolean = false,
    val supportsFlashAttention: Boolean = false,
    val supportsGpuOffload: Boolean = false,
    val supportsCpuMoeTuning: Boolean = false,
    val supportsSpeculativeMtp: Boolean = false,
    val qairtAdmissionPassed: Boolean = false
) {
    companion object {
        fun forIdentity(
            identity: ModelRuntimeIdentity,
            knowledgeLevel: ModelKnowledgeLevel = ModelKnowledgeLevel.KNOWN
        ): ModelTuningCapabilities = ModelTuningCapabilities(
            runtime = identity.runtime.toTuningRuntime(),
            knowledgeLevel = knowledgeLevel,
            supportsGpuOffload = "gpu_offload" in identity.capabilities,
            supportsSpeculativeMtp = "draft_mtp" in identity.capabilities,
            qairtAdmissionPassed = "qairt_admitted" in identity.capabilities
        )
    }
}

object SafeBaselineFactory {
    fun create(
        runtimeIdentity: ModelRuntimeIdentity,
        device: DeviceProfile,
        capabilities: ModelTuningCapabilities
    ): TuningExecutionProfile {
        require(capabilities.runtime.accepts(runtimeIdentity.runtime)) {
            "Runtime mismatch: identity=${runtimeIdentity.runtime} capabilities=${capabilities.runtime}"
        }
        val unknown = capabilities.knowledgeLevel == ModelKnowledgeLevel.UNKNOWN
        val maxContext = capabilities.maxContextTokens?.coerceAtLeast(MIN_CONTEXT) ?: SAFE_CONTEXT
        val nCtx = minOf(SAFE_CONTEXT, maxContext)
        val threads = device.estimatedBigCores
            .takeIf { it > 0 }
            ?.coerceAtMost(4)
            ?: minOf(device.cpuCores.coerceAtLeast(1), 4)
        val blockedAction = when {
            !capabilities.metadataReadable -> "请重新导入可读取 metadata 的完整模型包"
            !capabilities.chatTemplateReady -> "请补充或选择经过验证的 chat template"
            else -> null
        }
        val backend = when (capabilities.runtime) {
            TuningRuntime.MNN -> "cpu"
            TuningRuntime.QAIRT -> "qairt"
            TuningRuntime.LLAMA_CPP, TuningRuntime.UNKNOWN -> "cpu"
        }
        return buildTuningExecutionProfile(
            runtimeIdentity = runtimeIdentity,
            kind = ExecutionProfileKind.SAFE_BASELINE,
            loadBound = LoadBoundExecutionParams(
                nCtx = nCtx,
                cacheTypeK = if (capabilities.runtime == TuningRuntime.LLAMA_CPP) "f16" else null,
                cacheTypeV = if (capabilities.runtime == TuningRuntime.LLAMA_CPP) "f16" else null,
                flashAttention = if (capabilities.runtime == TuningRuntime.LLAMA_CPP) "off" else null,
                gpuLayers = if (capabilities.runtime == TuningRuntime.LLAMA_CPP || capabilities.runtime == TuningRuntime.UNKNOWN) 0 else null,
                mainGpu = if (capabilities.runtime == TuningRuntime.LLAMA_CPP || capabilities.runtime == TuningRuntime.UNKNOWN) 0 else null,
                cpuMoeLayers = if (unknown) 0 else null,
                speculativeType = null,
                speculativeDraftMax = null,
                nParallel = 1,
                mmap = true,
                mlock = false,
                backend = backend
            ),
            hotExecution = HotExecutionParams(nThreads = threads),
            verificationLevel = ProfileVerificationLevel.UNVERIFIED,
            eligibility = if (blockedAction == null) ProfileEligibility.ELIGIBLE else ProfileEligibility.BLOCKED_WITH_ACTION,
            blockedAction = blockedAction,
            reason = if (unknown) {
                "未知模型使用独立 CPU 安全基线；未继承其他模型的 GPU、MTP、量化 KV 或上下文参数。"
            } else {
                "首次加载使用保守安全基线，正确性通过后再进入性能搜索。"
            },
            profileId = "safe-${runtimeIdentity.identityHash.take(16)}"
        )
    }

    private const val MIN_CONTEXT = 512
    private const val SAFE_CONTEXT = 4096
}

private fun buildTuningExecutionProfile(
    runtimeIdentity: ModelRuntimeIdentity,
    kind: ExecutionProfileKind,
    loadBound: LoadBoundExecutionParams,
    hotExecution: HotExecutionParams,
    verificationLevel: ProfileVerificationLevel = ProfileVerificationLevel.UNVERIFIED,
    eligibility: ProfileEligibility = ProfileEligibility.ELIGIBLE,
    blockedAction: String? = null,
    reason: String = "",
    profileId: String
): TuningExecutionProfile {
    val requested = JSONObject()
    when (runtimeIdentity.runtime) {
        LocalChatRuntime.LLAMA_CPP, LocalChatRuntime.GENIEX_LLAMA_CPP -> requested.apply {
            put("n_ctx", loadBound.nCtx)
            putIfNotNull("n_batch", loadBound.nBatch)
            putIfNotNull("n_ubatch", loadBound.nUbatch)
            putIfNotNull("cache_type_k", loadBound.cacheTypeK)
            putIfNotNull("cache_type_v", loadBound.cacheTypeV)
            putIfNotNull("flash_attn", loadBound.flashAttention)
            putIfNotNull("n_gpu_layers", loadBound.gpuLayers)
            putIfNotNull("main_gpu", loadBound.mainGpu)
            putIfNotNull("n_cpu_moe", loadBound.cpuMoeLayers)
            putIfNotNull("spec_type", loadBound.speculativeType)
            putIfNotNull("spec_draft_n_max", loadBound.speculativeDraftMax)
            put("n_parallel", loadBound.nParallel)
            put("mmap", loadBound.mmap)
            put("mlock", loadBound.mlock)
            put("n_threads", hotExecution.nThreads)
            putIfNotNull("n_threads_batch", hotExecution.nThreadsBatch)
        }
        LocalChatRuntime.MNN_CPU -> requested.apply {
            put("n_ctx", loadBound.nCtx)
            put("backend", loadBound.backend)
            put("mmap", loadBound.mmap)
            put("n_threads", hotExecution.nThreads)
        }
        LocalChatRuntime.GENIEX_QAIRT -> requested.apply {
            put("backend", loadBound.backend)
        }
    }
    (loadBound.extensions + hotExecution.extensions).toSortedMap().forEach { (field, value) ->
        requested.put(field, value)
    }
    val resolution = runtimeParameterAdapter(runtimeIdentity.runtime).resolveLoadProfile(
        identity = runtimeIdentity,
        rawJson = requested.toString(),
        profileId = profileId
    )
    val engineProfile = resolution.profile
    val resolvedLoad = loadBound.copy(
        nCtx = engineProfile.resolvedLoadBoundValues.intValue("n_ctx") ?: loadBound.nCtx,
        nBatch = engineProfile.resolvedLoadBoundValues.intValue("n_batch") ?: loadBound.nBatch,
        nUbatch = engineProfile.resolvedLoadBoundValues.intValue("n_ubatch") ?: loadBound.nUbatch,
        cacheTypeK = engineProfile.resolvedLoadBoundValues.stringValue("cache_type_k") ?: loadBound.cacheTypeK,
        cacheTypeV = engineProfile.resolvedLoadBoundValues.stringValue("cache_type_v") ?: loadBound.cacheTypeV,
        flashAttention = engineProfile.resolvedLoadBoundValues.stringValue("flash_attn") ?: loadBound.flashAttention,
        gpuLayers = engineProfile.resolvedLoadBoundValues.intValue("n_gpu_layers") ?: loadBound.gpuLayers,
        mainGpu = engineProfile.resolvedLoadBoundValues.intValue("main_gpu") ?: loadBound.mainGpu,
        cpuMoeLayers = engineProfile.resolvedLoadBoundValues.intValue("n_cpu_moe") ?: loadBound.cpuMoeLayers,
        speculativeType = engineProfile.resolvedLoadBoundValues.stringValue("spec_type") ?: loadBound.speculativeType,
        speculativeDraftMax = engineProfile.resolvedLoadBoundValues.intValue("spec_draft_n_max") ?: loadBound.speculativeDraftMax,
        nParallel = engineProfile.resolvedLoadBoundValues.intValue("n_parallel") ?: loadBound.nParallel,
        mmap = engineProfile.resolvedLoadBoundValues.booleanValue("mmap") ?: loadBound.mmap,
        mlock = engineProfile.resolvedLoadBoundValues.booleanValue("mlock") ?: loadBound.mlock,
        backend = engineProfile.resolvedLoadBoundValues.stringValue("backend") ?: loadBound.backend
    )
    val resolvedHot = hotExecution.copy(
        nThreads = engineProfile.hotExecutionValues.intValue("n_threads") ?: hotExecution.nThreads,
        nThreadsBatch = engineProfile.hotExecutionValues.intValue("n_threads_batch") ?: hotExecution.nThreadsBatch
    )
    return TuningExecutionProfile(
        engineProfile = engineProfile,
        kind = kind,
        loadBound = resolvedLoad,
        hotExecution = resolvedHot,
        verificationLevel = verificationLevel,
        eligibility = eligibility,
        blockedAction = blockedAction,
        reason = buildString {
            append(reason)
            if (resolution.warnings.isNotEmpty()) {
                if (isNotEmpty()) append(' ')
                append(resolution.warnings.joinToString("；"))
            }
        }
    )
}

private fun runtimeParameterAdapter(runtime: LocalChatRuntime): RuntimeParameterAdapter = when (runtime) {
    LocalChatRuntime.LLAMA_CPP, LocalChatRuntime.GENIEX_LLAMA_CPP -> LlamaCppRuntimeParameterAdapter()
    LocalChatRuntime.MNN_CPU -> MnnRuntimeParameterAdapter()
    LocalChatRuntime.GENIEX_QAIRT -> QairtRuntimeParameterAdapter()
}

private fun TuningRuntime.accepts(runtime: LocalChatRuntime): Boolean = when (this) {
    TuningRuntime.LLAMA_CPP -> runtime == LocalChatRuntime.LLAMA_CPP || runtime == LocalChatRuntime.GENIEX_LLAMA_CPP
    TuningRuntime.MNN -> runtime == LocalChatRuntime.MNN_CPU
    TuningRuntime.QAIRT -> runtime == LocalChatRuntime.GENIEX_QAIRT
    TuningRuntime.UNKNOWN -> true
}

internal fun LocalChatRuntime.toTuningRuntime(): TuningRuntime = when (this) {
    LocalChatRuntime.LLAMA_CPP, LocalChatRuntime.GENIEX_LLAMA_CPP -> TuningRuntime.LLAMA_CPP
    LocalChatRuntime.MNN_CPU -> TuningRuntime.MNN
    LocalChatRuntime.GENIEX_QAIRT -> TuningRuntime.QAIRT
}

private fun HotExecutionParams.toCanonicalParameterSet(): CanonicalParameterSet = CanonicalParameterSet.of(
    buildMap<String, Any?> {
        put("n_threads", nThreads)
        nThreadsBatch?.let { put("n_threads_batch", it) }
        extensions.toSortedMap().forEach { (field, value) -> put(field, value) }
    }
)

private fun CanonicalParameterSet.intValue(field: String): Int? = (value(field) as? Number)?.toInt()

private fun CanonicalParameterSet.stringValue(field: String): String? = value(field)?.toString()

private fun CanonicalParameterSet.booleanValue(field: String): Boolean? = value(field) as? Boolean

private fun JSONObject.putIfNotNull(field: String, value: Any?) {
    if (value != null) put(field, value)
}

data class MeasurementPoint(
    val timeMs: Long = System.currentTimeMillis(),
    val thermalStatus: ThermalStatus = ThermalStatus.Unknown,
    val batteryPercent: Int = -1,
    val isCharging: Boolean = false,
    val availableMemoryBytes: Long = 0,
    val pssBytes: Long = 0,
    val rssBytes: Long = 0,
    val lowMemoryTriggered: Boolean = false,
    val appInForeground: Boolean = true,
    val cpuSet: String? = null,
    val systemPressure: String? = null
)

data class MeasurementEnvelope(
    val start: MeasurementPoint,
    val end: MeasurementPoint,
    val coolingTimeMs: Long = 0,
    val samples: List<PerformanceSample> = emptyList()
) {
    val peakPssBytes: Long
        get() = max(max(start.pssBytes, end.pssBytes), samples.maxOfOrNull { it.pssBytes } ?: 0)
    val peakRssBytes: Long
        get() = max(max(start.rssBytes, end.rssBytes), samples.maxOfOrNull { it.rssBytes } ?: 0)
    val minimumAvailableMemoryBytes: Long
        get() = listOf(start.availableMemoryBytes, end.availableMemoryBytes)
            .filter { it > 0 }
            .minOrNull() ?: 0
}

data class PerformanceSample(
    val ttftMs: Long,
    val decodeTps: Double,
    val pssBytes: Long = 0,
    val rssBytes: Long = 0,
    val availableMemoryBytes: Long = 0
)

data class SafetyEnvelope(
    val version: String = "safety-v1",
    val minimumAvailableMemoryBytes: Long,
    val maximumPssBytes: Long,
    val maximumRssBytes: Long,
    val maximumThermalStatus: ThermalStatus = ThermalStatus.Severe,
    val minimumBatteryPercent: Int = 15,
    val requireCharging: Boolean = false,
    val requireForeground: Boolean = true,
    val perCandidateTimeoutMs: Long = 120_000,
    val totalTuningTimeoutMs: Long = 30 * 60_000L,
    val maximumCandidates: Int = 24,
    val maximumReloads: Int = 12
) {
    init {
        require(perCandidateTimeoutMs > 0)
        require(totalTuningTimeoutMs >= perCandidateTimeoutMs)
        require(maximumCandidates > 0)
        require(maximumReloads >= 0)
    }

    fun assess(point: MeasurementPoint): SafetyAssessment = assess(
        MeasurementEnvelope(start = point, end = point)
    )

    fun assess(measurement: MeasurementEnvelope): SafetyAssessment {
        val violations = buildList {
            if (measurement.start.lowMemoryTriggered || measurement.end.lowMemoryTriggered) {
                add(SafetyViolation("low_memory", "系统触发 low-memory"))
            }
            if (measurement.minimumAvailableMemoryBytes in 1 until minimumAvailableMemoryBytes) {
                add(SafetyViolation("memory_headroom", "可用内存低于安全下限"))
            }
            if (measurement.peakPssBytes > maximumPssBytes) {
                add(SafetyViolation("pss_limit", "PSS 超过安全上限"))
            }
            if (measurement.peakRssBytes > maximumRssBytes) {
                add(SafetyViolation("rss_limit", "RSS 超过安全上限"))
            }
            if (thermalSeverity(measurement.start.thermalStatus) > thermalSeverity(maximumThermalStatus) ||
                thermalSeverity(measurement.end.thermalStatus) > thermalSeverity(maximumThermalStatus)
            ) {
                add(SafetyViolation("thermal_limit", "温控状态超过安全上限"))
            }
            val battery = listOf(measurement.start.batteryPercent, measurement.end.batteryPercent)
                .filter { it >= 0 }
                .minOrNull()
            if (battery != null && battery < minimumBatteryPercent &&
                !measurement.start.isCharging && !measurement.end.isCharging
            ) {
                add(SafetyViolation("battery_limit", "电量低于安全下限"))
            }
            if (requireCharging && !measurement.start.isCharging) {
                add(SafetyViolation("charging_required", "当前调优模式要求充电"))
            }
            if (requireForeground && (!measurement.start.appInForeground || !measurement.end.appInForeground)) {
                add(SafetyViolation("background", "App 已进入后台"))
            }
        }
        return SafetyAssessment(passed = violations.isEmpty(), violations = violations)
    }

    companion object {
        fun forDevice(device: DeviceProfile): SafetyEnvelope {
            val total = device.displayTotalRamBytes.coerceAtLeast(1)
            val threshold = device.memoryThresholdBytes.takeIf { it > 0 } ?: 256L * MB
            return SafetyEnvelope(
                minimumAvailableMemoryBytes = max(threshold * 2, total / 12),
                maximumPssBytes = (total * 0.78).toLong(),
                maximumRssBytes = (total * 0.84).toLong()
            )
        }

        private const val MB = 1024L * 1024L
    }
}

data class SafetyViolation(val code: String, val message: String)

data class SafetyAssessment(
    val passed: Boolean,
    val violations: List<SafetyViolation>
)

enum class CorrectnessCaseCategory {
    DETERMINISTIC_FORMAT,
    CHINESE_INSTRUCTION,
    ROLE_TEMPLATE,
    MULTI_TURN_CONTEXT,
    OUTPUT_HYGIENE,
    LONG_CONTEXT,
    VISION_COUNTERFACTUAL,
    MODEL_FAMILY
}

data class CorrectnessCaseDefinition(
    val id: String,
    val category: CorrectnessCaseCategory,
    val promptVersion: String,
    val description: String
)

data class CanaryCaseResult(
    val caseId: String,
    val passed: Boolean,
    val detail: String? = null
)

data class CanaryRunResult(
    val cases: List<CanaryCaseResult>,
    val outputVisible: Boolean,
    val templateValid: Boolean,
    val crashCount: Int = 0,
    val anrCount: Int = 0,
    val nativeFatalSignalCount: Int = 0,
    val lowMemoryTriggered: Boolean = false,
    val samples: List<PerformanceSample> = emptyList()
)

data class CorrectnessSuite(
    val id: String,
    val version: String,
    val cases: List<CorrectnessCaseDefinition>
) {
    init {
        require(cases.isNotEmpty()) { "CorrectnessSuite must contain cases" }
        require(cases.map { it.id }.distinct().size == cases.size) { "Correctness case ids must be unique" }
    }

    fun evaluate(run: CanaryRunResult): CorrectnessAssessment {
        val byId = run.cases.associateBy { it.caseId }
        val missing = cases.map { it.id }.filterNot(byId::containsKey)
        val failed = cases.mapNotNull { definition ->
            byId[definition.id]?.takeUnless { it.passed }
        }
        val passed = missing.isEmpty() && failed.isEmpty() && run.outputVisible && run.templateValid
        return CorrectnessAssessment(
            passed = passed,
            missingCaseIds = missing,
            failedCases = failed,
            outputVisible = run.outputVisible,
            templateValid = run.templateValid
        )
    }

    companion object {
        const val MINIMUM_TEXT_SUITE_ID = "minimum-text-v1"

        fun minimumText(): CorrectnessSuite = CorrectnessSuite(
            id = MINIMUM_TEXT_SUITE_ID,
            version = "1",
            cases = listOf(
                CorrectnessCaseDefinition("deterministic-format", CorrectnessCaseCategory.DETERMINISTIC_FORMAT, "v1", "固定数字和格式输出"),
                CorrectnessCaseDefinition("chinese-instruction", CorrectnessCaseCategory.CHINESE_INSTRUCTION, "v1", "中文指令遵循"),
                CorrectnessCaseDefinition("role-template", CorrectnessCaseCategory.ROLE_TEMPLATE, "v1", "role 与模板顺序"),
                CorrectnessCaseDefinition("two-turn-context", CorrectnessCaseCategory.MULTI_TURN_CONTEXT, "v1", "两轮上下文"),
                CorrectnessCaseDefinition("output-hygiene", CorrectnessCaseCategory.OUTPUT_HYGIENE, "v1", "空输出、乱码、重复和特殊 token 检测")
            )
        )
    }
}

data class CorrectnessAssessment(
    val passed: Boolean,
    val missingCaseIds: List<String>,
    val failedCases: List<CanaryCaseResult>,
    val outputVisible: Boolean,
    val templateValid: Boolean
)

data class CandidateHardGate(
    val correctnessPassed: Boolean,
    val crashCount: Int,
    val anrCount: Int,
    val nativeFatalSignalCount: Int,
    val lowMemoryTriggered: Boolean,
    val outputVisible: Boolean,
    val templateValid: Boolean,
    val safetyPassed: Boolean,
    val signaturesMatch: Boolean
) {
    val passed: Boolean
        get() = correctnessPassed && crashCount == 0 && anrCount == 0 &&
            nativeFatalSignalCount == 0 && !lowMemoryTriggered && outputVisible &&
            templateValid && safetyPassed && signaturesMatch
}

data class CandidateScore(
    val eligible: Boolean,
    val value: Double?,
    val medianDecodeTps: Double,
    val medianTtftMs: Double,
    val decodeVariance: Double,
    val reason: String? = null
)

object CandidateScorer {
    fun score(
        hardGate: CandidateHardGate,
        measurement: MeasurementEnvelope
    ): CandidateScore {
        if (!hardGate.passed) {
            return CandidateScore(
                eligible = false,
                value = null,
                medianDecodeTps = median(measurement.samples.map { it.decodeTps }),
                medianTtftMs = median(measurement.samples.map { it.ttftMs.toDouble() }),
                decodeVariance = variance(measurement.samples.map { it.decodeTps }),
                reason = "候选未通过正确性/安全硬门槛"
            )
        }
        val decode = measurement.samples.map { it.decodeTps }.filter { it.isFinite() && it >= 0.0 }
        val ttft = measurement.samples.map { it.ttftMs.toDouble() }.filter { it >= 0.0 }
        val medianDecode = median(decode)
        val medianTtft = median(ttft)
        val decodeVariance = variance(decode)
        val memoryHeadroomGiB = measurement.minimumAvailableMemoryBytes / GIB.toDouble()
        val thermalPenalty = max(
            thermalSeverity(measurement.end.thermalStatus) - thermalSeverity(measurement.start.thermalStatus),
            0
        ) * 0.75
        val repeatabilityPenalty = decodeVariance * 0.25
        val score = medianDecode - medianTtft / 5_000.0 + memoryHeadroomGiB.coerceAtMost(4.0) * 0.15 -
            thermalPenalty - repeatabilityPenalty
        return CandidateScore(
            eligible = true,
            value = score,
            medianDecodeTps = medianDecode,
            medianTtftMs = medianTtft,
            decodeVariance = decodeVariance
        )
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private fun variance(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val average = values.average()
        return values.sumOf { value -> (value - average) * (value - average) } / values.size
    }

    private const val GIB = 1024L * 1024L * 1024L
}

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

private fun extensionsToJson(extensions: Map<String, String>): String {
    if (extensions.isEmpty()) return "{}"
    return extensions.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "\"${key.replace("\"", "\\\"")}\":\"${value.replace("\"", "\\\"")}\""
    }
}
