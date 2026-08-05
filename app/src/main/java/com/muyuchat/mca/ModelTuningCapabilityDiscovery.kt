package com.muyuchat.mca

import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.modelstore.GgufMetadataReader
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.tuning.ModelKnowledgeLevel
import com.muyuchat.core.tuning.ModelTuningCapabilities
import com.muyuchat.core.tuning.TuningRuntime
import java.io.File

/**
 * Returns only a model-declared limit read from the GGUF metadata header.
 * File names, device profiles and the currently selected n_ctx are not evidence
 * of a model maximum and therefore cannot unlock context search.
 */
internal fun trustedModelMaxContextTokens(
    model: ModelManifest,
    identity: ModelRuntimeIdentity
): Int? {
    if (identity.runtime !in setOf(LocalChatRuntime.LLAMA_CPP, LocalChatRuntime.GENIEX_LLAMA_CPP)) {
        return null
    }
    val file = File(model.path)
    if (!file.isFile) return null
    return runCatching { GgufMetadataReader.read(file) }
        .getOrNull()
        ?.takeIf { it.isGguf }
        ?.contextLength
}

/**
 * Discovers only runtime/model capabilities backed by concrete local evidence.
 * Device identity never participates in feature admission here; it is reserved
 * for per-device tuning values after the runtime capability is available.
 */
internal fun discoverModelTuningCapabilities(
    model: ModelManifest,
    identity: ModelRuntimeIdentity,
    qairtAdmissionPassed: Boolean
): ModelTuningCapabilities {
    val metadataReadable = model.path.isNotBlank() && File(model.path).exists()
    val architectureKnown = !model.architecture.isNullOrBlank()
    val known = metadataReadable && architectureKnown && model.sha256.isNotBlank()
    val llama = identity.runtime == LocalChatRuntime.LLAMA_CPP ||
        identity.runtime == LocalChatRuntime.GENIEX_LLAMA_CPP
    val capabilities = identity.capabilities
    return ModelTuningCapabilities(
        runtime = when (identity.runtime) {
            LocalChatRuntime.MNN_CPU -> TuningRuntime.MNN
            LocalChatRuntime.GENIEX_QAIRT -> TuningRuntime.QAIRT
            LocalChatRuntime.LLAMA_CPP,
            LocalChatRuntime.GENIEX_LLAMA_CPP -> TuningRuntime.LLAMA_CPP
        },
        knowledgeLevel = if (known) ModelKnowledgeLevel.KNOWN else ModelKnowledgeLevel.UNKNOWN,
        metadataReadable = metadataReadable,
        chatTemplateReady = metadataReadable,
        maxContextTokens = trustedModelMaxContextTokens(model, identity),
        supportsBatchTuning = llama && known,
        supportsQuantizedKv = llama && known,
        supportsFlashAttention = llama && known,
        // These are user-selectable candidates.  Unknown metadata/device
        // profiles remain advisory; the native load and smoke path decide
        // whether a particular model can actually use them.
        supportsGpuOffload = llama && "gpu_offload" in capabilities,
        supportsCpuMoeTuning = llama && known && "cpu_moe" in capabilities,
        supportsSpeculativeMtp = llama && "draft_mtp" in capabilities,
        qairtAdmissionPassed = qairtAdmissionPassed
    )
}
