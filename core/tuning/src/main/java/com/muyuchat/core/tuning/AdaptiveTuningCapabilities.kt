package com.muyuchat.core.tuning

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.ModelRuntimeIdentity

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
    val qairtAdmissionPassed: Boolean = false,
    /**
     * Advisory transport selected from the model's declared variant. This is
     * deliberately not an admission gate: a native load/graph execution must
     * still prove that the transport works on the current device.
     */
    val preferredBackend: String? = null
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
        val backend = capabilities.preferredBackend
            ?.takeIf { runtimeIdentity.runtime == LocalChatRuntime.LITERT_LM }
            ?.takeIf(::isSupportedLiteRtBackend)
            ?: when (capabilities.runtime) {
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
            // Prompt prefill uses llama.cpp's batch thread pool. Leaving this
            // unset falls back to the runtime default of one thread even when
            // decode is correctly tuned to the device's worker count.
            hotExecution = HotExecutionParams(
                nThreads = threads,
                nThreadsBatch = threads
            ),
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
