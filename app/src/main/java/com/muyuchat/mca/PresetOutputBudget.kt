package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParams

/**
 * Output-token budget applied when a preset is written onto a model profile.
 *
 * A Tavern preset is authored for a web model: `openai_max_tokens` of 30k-60k is normal because the
 * provider streams a whole chapter. Two different limits apply on this device:
 *
 * - **Cloud models** legitimately support long output (Gemini-class endpoints allow tens of
 *   thousands of tokens), so the imported value is kept — clamping it at import would silently
 *   down-grade a cloud user's preset for no reason.
 * - **Local models** run inside a phone-sized context window; asking for 60k output makes every
 *   request over-budget before a single token is generated, so the value is clamped to the local
 *   ceiling.
 *
 * That is why the limit lives here rather than in the importer: the same preset may be bound to both
 * a phone model and a cloud model, and each binding gets the budget it can actually honour.
 */
internal object PresetOutputBudget {

    /** Local ceiling: matches the sampler ceiling the generation dialog exposes. */
    const val LOCAL_MAX_OUTPUT_TOKENS: Int = 8_192

    /** Cloud ceiling: above any current provider's per-response cap, so it only stops nonsense. */
    const val CLOUD_MAX_OUTPUT_TOKENS: Int = 65_536

    /** Clamps [params] to what [isLocal] models can honour. Pure; returns the same object when unchanged. */
    fun apply(params: GenerationParams, isLocal: Boolean): GenerationParams {
        val ceiling = if (isLocal) LOCAL_MAX_OUTPUT_TOKENS else CLOUD_MAX_OUTPUT_TOKENS
        if (params.nPredict <= ceiling) return params
        return params.copy(nPredict = ceiling)
    }

    /** `true` when the preset asked for more output than this model can honour. */
    fun wasClamped(params: GenerationParams, isLocal: Boolean): Boolean =
        params.nPredict > (if (isLocal) LOCAL_MAX_OUTPUT_TOKENS else CLOUD_MAX_OUTPUT_TOKENS)
}
