package com.muyuchat.core.tuning

import com.muyuchat.core.engine.ActiveLoadedSignature
import com.muyuchat.core.engine.EffectiveExecutionSignature
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LlamaAdvancedParams
import com.muyuchat.core.engine.ModelExecutionProfile as EngineModelExecutionProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.RuntimeOverrideSignature

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
