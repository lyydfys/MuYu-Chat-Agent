package com.muyuchat.mca

import com.muyuchat.core.download.RecommendedImageDefaults

/** Whether a model default negative prompt must use the chat-model bridge. */
internal fun chatImageNegativePromptNeedsBridge(value: String?): Boolean =
    !value.isNullOrBlank() && value.containsHanScript()

/**
 * Chooses the negative prompt that can actually be sent to an image encoder.
 *
 * A Chinese model default is never allowed to reach an English-dominant encoder unchanged. If
 * the chat bridge produced a non-blank translation it wins; a verified native-multilingual
 * encoder may keep the original; otherwise the default is cleared so generation can proceed
 * without a hidden language rejection.
 */
internal fun effectiveChatImageNegativePrompt(
    original: String?,
    translated: String?,
    nativeMultilingual: Boolean
): String? {
    if (original == null) return null
    val source = original.trim()
    if (source.isBlank()) return ""
    if (!source.containsHanScript()) return source
    translated?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    if (nativeMultilingual) return source
    return ""
}

/**
 * Combines negative clauses while retaining their authored order and removing exact duplicates.
 * This is used when the chat model returns a structured negative branch alongside a model
 * default.  Treating the branches as a set avoids silently dropping user exclusions while also
 * preventing the same tag from being sent twice to the image encoder.
 */
internal fun mergeChatImageNegativePrompts(vararg values: String?): String? {
    val clauses = values.asSequence()
        .filterNotNull()
        .flatMap { value ->
            value.split(',', '，', ';', '；', '\n')
                .map(String::trim)
                .filter(String::isNotBlank)
                .asSequence()
        }
        .distinctBy { it.lowercase() }
        .toList()
    return clauses.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

/**
 * Returns a safe chat-image default only when the selected profile explicitly supports a
 * separate negative branch. A missing manifest default is common for imported models, but it
 * must not mean that every model receives negative conditioning: CFG-disabled and
 * negative-unsupported topologies would either ignore it or reject the request.
 */
internal fun chatImageDefaultNegativePromptForProfile(
    profile: ImageExecutionProfile
): String? {
    return chatImageDefaultNegativePromptForTopology(
        family = profile.family,
        supportsNegativePrompt = profile.capabilities.supportsNegativePrompt,
        useCfg = profile.defaults.useCfg,
        declaredDefault = profile.defaults.defaultNegativePrompt
    )
}

/**
 * Conservative fallback for legacy/imported records whose sidecar cannot be resolved yet.  It is
 * only used for the ordinary CFG diffusion families; CFG-off/flow families stay opt-out so a
 * prompt bridge can never inject a negative branch that the native graph cannot consume.
 */
internal fun chatImageFallbackNegativePromptForModel(
    family: LocalImageModelFamily,
    runtime: LocalImageRuntime
): String? {
    if (runtime !in setOf(LocalImageRuntime.QNN_HTP, LocalImageRuntime.MNN_DIFFUSION,
            LocalImageRuntime.STABLE_DIFFUSION_CPP)) return null
    return when (family) {
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.SD21,
        LocalImageModelFamily.CUSTOM -> RecommendedImageDefaults.SD15_NEGATIVE_PROMPT
        LocalImageModelFamily.SDXL -> RecommendedImageDefaults.SDXL_NEGATIVE_PROMPT
        LocalImageModelFamily.SANA,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.GLM_IMAGE -> RecommendedImageDefaults.LANGUAGE_CONDITIONED_NEGATIVE_PROMPT
        LocalImageModelFamily.LONGCAT_IMAGE,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.SD_TURBO,
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.WAN -> null
    }
}

/** Pure topology selector kept separate so imported-model fallback behavior is unit-testable. */
internal fun chatImageDefaultNegativePromptForTopology(
    family: LocalImageModelFamily,
    supportsNegativePrompt: Boolean,
    useCfg: Boolean,
    declaredDefault: String?
): String? {
    if (!supportsNegativePrompt || !useCfg) return null

    // A non-null profile value, including an explicit empty string, is authoritative. This lets
    // model authors opt out for a family whose text encoder does not benefit from generic tags.
    declaredDefault?.trim()?.let { declared ->
        return declared.takeIf(String::isNotBlank)
    }

    return when (family) {
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.SD21 -> RecommendedImageDefaults.SD15_NEGATIVE_PROMPT
        LocalImageModelFamily.SDXL -> RecommendedImageDefaults.SDXL_NEGATIVE_PROMPT
        LocalImageModelFamily.QWEN_IMAGE -> RecommendedImageDefaults.QWEN_IMAGE_2512_NEGATIVE_PROMPT
        LocalImageModelFamily.SANA,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.GLM_IMAGE -> RecommendedImageDefaults.LANGUAGE_CONDITIONED_NEGATIVE_PROMPT
        // Imported/custom profiles may still expose a normal CFG + negative branch. The generic
        // SD vocabulary is deliberately conservative and ASCII-only, so it is safe for an
        // English-dominant tokenizer while remaining bounded for CLIP-style token budgets.
        LocalImageModelFamily.CUSTOM -> RecommendedImageDefaults.SD15_NEGATIVE_PROMPT
        LocalImageModelFamily.LONGCAT_IMAGE,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.SD_TURBO,
        LocalImageModelFamily.WAN -> null
    }
}
