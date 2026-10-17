package com.muyuchat.mca

import java.io.File
import com.muyuchat.core.engine.compareUtf8Unsigned

internal fun resolveStableDiffusionCompatibilityPromptComponents(
    root: File,
    primary: File
): List<File> {
    val modelExtensions = setOf("gguf", "safetensors", "ckpt", "pth", "pt", "sft")
    val selected = linkedMapOf<String, File>()
    root.walkTopDown()
        .filter { file -> file.isFile && file.extension.lowercase() in modelExtensions }
        .map(File::getCanonicalFile)
        .sortedWith { left, right ->
            compareUtf8Unsigned(left.invariantSeparatorsPath, right.invariantSeparatorsPath)
        }
        .forEach { file ->
            if (file == primary) return@forEach
            val lower = file.invariantSeparatorsPath.lowercase()
            when {
                "high" in lower && "noise" in lower && "diffusion" in lower ->
                    selected["high_noise"] = file
                "clip_vision" in lower || "clip-vision" in lower || "vision_h" in lower -> Unit
                "clip_g" in lower || "clip-g" in lower -> selected["clip_g"] = file
                "clip_l" in lower || "clip-l" in lower -> selected["clip_l"] = file
                "t5xxl" in lower || "umt5" in lower || "t5-xxl" in lower ->
                    selected["t5xxl"] = file
                "vae" in lower || lower.endsWith("ae.sft") ||
                    lower.endsWith("ae.safetensors") || lower.endsWith("_ae.safetensors") ||
                    lower.endsWith("-ae.safetensors") || lower.endsWith("_ae.gguf") ||
                    lower.endsWith("-ae.gguf") || "/ae." in lower -> Unit
                "embeddings" in lower && "connector" in lower ->
                    selected["embeddings_connectors"] = file
                "controlnet" in lower || "control_net" in lower -> Unit
                "llm_vision" in lower || "llm-vision" in lower ->
                    selected["llm_vision"] = file
                "qwen" in lower || "mistral" in lower || "gemma" in lower || "llm" in lower ->
                    selected["llm"] = file
                "diffusion" in lower || "unet" in lower || "dit" in lower -> Unit
            }
        }
    return selected.values.toList()
}

/** A quality-bound MNN SD1.5 profile can only use the strict direct runner. */
internal fun resolveMnnDiffusionProfileRunner(
    profile: ImageExecutionProfile,
    requestedRunner: String?
): String {
    val resolved = resolveMnnDiffusionRunner(profile.family, requestedRunner)
    if (
        profile.runtime == LocalImageRuntime.MNN_DIFFUSION &&
        profile.profileId == "mnn.sd15.official.512"
    ) {
        require(resolved == "direct") {
            "The MNN SD1.5 quality profile requires runner=direct; module cannot satisfy its execution contract."
        }
        return "direct"
    }
    return resolved
}

/** Resolves MNN conditioning storage without guessing from an unknown token-table size. */
internal fun resolveMnnConditioningDiskDataType(
    graphInternal: Boolean,
    tokenEmbeddingByteSize: Long?
): ImageEmbeddingDiskDataType {
    val tokenTableDataType = when (tokenEmbeddingByteSize) {
        null -> null
        75_890_688L -> ImageEmbeddingDiskDataType.FP16
        151_781_376L -> ImageEmbeddingDiskDataType.FP32
        else -> throw IllegalArgumentException(
            "PACKAGE_FORMAT_INVALID: token_emb.bin must be exactly 75890688 bytes (FP16) " +
                "or 151781376 bytes (FP32); found $tokenEmbeddingByteSize bytes."
        )
    }
    return if (graphInternal) {
        ImageEmbeddingDiskDataType.GRAPH_INTERNAL
    } else {
        tokenTableDataType ?: ImageEmbeddingDiskDataType.RUNTIME_NATIVE
    }
}
