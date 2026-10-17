package com.muyuchat.core.tuning

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.CanonicalParameterSet
import com.muyuchat.core.engine.LlamaAdvancedParams
import com.muyuchat.core.engine.LlamaCppRuntimeParameterAdapter
import com.muyuchat.core.engine.LiteRtLmRuntimeParameterAdapter
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.MnnRuntimeParameterAdapter
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.QairtRuntimeParameterAdapter
import com.muyuchat.core.engine.RuntimeParameterAdapter
import org.json.JSONObject

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
                backend = capabilities.preferredBackend
                    ?.takeIf { runtimeIdentity.runtime == LocalChatRuntime.LITERT_LM }
                    ?.takeIf(::isSupportedLiteRtBackend)
                    ?: when (capabilities.runtime) {
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

internal fun buildTuningExecutionProfile(
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
        LocalChatRuntime.LITERT_LM -> requested.apply {
            put("n_ctx", loadBound.nCtx)
            put("backend", loadBound.backend)
            put("n_threads", hotExecution.nThreads)
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
    LocalChatRuntime.LITERT_LM -> LiteRtLmRuntimeParameterAdapter()
}

internal fun TuningRuntime.accepts(runtime: LocalChatRuntime): Boolean = when (this) {
    TuningRuntime.LLAMA_CPP -> runtime == LocalChatRuntime.LLAMA_CPP || runtime == LocalChatRuntime.GENIEX_LLAMA_CPP
    TuningRuntime.MNN -> runtime == LocalChatRuntime.MNN_CPU
    TuningRuntime.QAIRT -> runtime == LocalChatRuntime.GENIEX_QAIRT
    TuningRuntime.UNKNOWN -> true
}

internal fun LocalChatRuntime.toTuningRuntime(): TuningRuntime = when (this) {
    LocalChatRuntime.LLAMA_CPP, LocalChatRuntime.GENIEX_LLAMA_CPP -> TuningRuntime.LLAMA_CPP
    LocalChatRuntime.MNN_CPU -> TuningRuntime.MNN
    LocalChatRuntime.GENIEX_QAIRT -> TuningRuntime.QAIRT
    LocalChatRuntime.LITERT_LM -> TuningRuntime.UNKNOWN
}

internal fun HotExecutionParams.toCanonicalParameterSet(): CanonicalParameterSet = CanonicalParameterSet.of(
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
