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

    /**
     * Clamps [params] to what [isLocal] models can honour.
     *
     * A **cloud** binding is left exactly as the preset author wrote it: providers differ wildly
     * (a few thousand vs tens of thousands of output tokens) and each one enforces its own ceiling,
     * so silently rewriting the number here would second-guess the preset and hide a real mistake
     * behind a value the user never chose. Only local models get a ceiling, because a phone context
     * window cannot honour a web-scale budget at all.
     */
    fun apply(params: GenerationParams, isLocal: Boolean): GenerationParams {
        if (!isLocal) return params
        if (params.nPredict <= LOCAL_MAX_OUTPUT_TOKENS) return params
        return params.copy(nPredict = LOCAL_MAX_OUTPUT_TOKENS)
    }

    /** `true` when the preset asked for more output than this model can honour. */
    fun wasClamped(params: GenerationParams, isLocal: Boolean): Boolean =
        isLocal && params.nPredict > LOCAL_MAX_OUTPUT_TOKENS
}
