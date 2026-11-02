package com.muyuchat.core.engine

/**
 * Field-level override policy shared by model presets, assistant cards and the editor.
 *
 * A model profile owns the semantic defaults.  An assistant card may override only the
 * fields the user explicitly changed; everything else keeps following the model profile.
 * `systemPrompt` is deliberately absent: persona text is always assistant-scoped.
 */
object GenerationParamOverrideFields {
    const val N_PREDICT = "n_predict"
    const val TEMPERATURE = "temperature"
    const val TOP_K = "top_k"
    const val TOP_P = "top_p"
    const val MIN_P = "min_p"
    const val REPEAT_PENALTY = "repeat_penalty"
    const val PRESENCE_PENALTY = "presence_penalty"
    const val FREQUENCY_PENALTY = "frequency_penalty"
    const val SEED = "seed"
    const val STOP_WORDS = "stop_words"
    const val REASONING_MODE = "reasoning_mode"
    const val HIDE_REASONING = "hide_reasoning"

    /** Fields a role card may override over a model preset. */
    val semanticFields: Set<String> = linkedSetOf(
        N_PREDICT,
        TEMPERATURE,
        TOP_K,
        TOP_P,
        MIN_P,
        REPEAT_PENALTY,
        PRESENCE_PENALTY,
        FREQUENCY_PENALTY,
        STOP_WORDS,
        REASONING_MODE
    )

    /** Legacy cards without override metadata kept their whole generation snapshot. */
    val legacyFields: Set<String> = semanticFields + setOf(SEED, HIDE_REASONING)

    /**
     * Applies only [fields] from [source] on top of [base].  Fields outside the set keep the
     * model value; this is what makes inherited parameters change when the model preset changes.
     */
    fun apply(base: GenerationParams, source: GenerationParams, fields: Set<String>): GenerationParams =
        base.copy(
            nPredict = if (N_PREDICT in fields) source.nPredict else base.nPredict,
            temperature = if (TEMPERATURE in fields) source.temperature else base.temperature,
            topK = if (TOP_K in fields) source.topK else base.topK,
            topP = if (TOP_P in fields) source.topP else base.topP,
            minP = if (MIN_P in fields) source.minP else base.minP,
            repeatPenalty = if (REPEAT_PENALTY in fields) source.repeatPenalty else base.repeatPenalty,
            presencePenalty = if (PRESENCE_PENALTY in fields) source.presencePenalty else base.presencePenalty,
            frequencyPenalty = if (FREQUENCY_PENALTY in fields) source.frequencyPenalty else base.frequencyPenalty,
            seed = if (SEED in fields) source.seed else base.seed,
            stopWords = if (STOP_WORDS in fields) source.stopWords else base.stopWords,
            reasoningMode = if (REASONING_MODE in fields) source.reasoningMode else base.reasoningMode,
            hideReasoning = if (HIDE_REASONING in fields || REASONING_MODE in fields) {
                source.hideReasoning
            } else {
                base.hideReasoning
            }
        )

    /** The semantic fields whose values differ; used by the editor to store sparse overrides. */
    fun changedSemanticFields(base: GenerationParams, candidate: GenerationParams): Set<String> = buildSet {
        if (base.nPredict != candidate.nPredict) add(N_PREDICT)
        if (base.temperature != candidate.temperature) add(TEMPERATURE)
        if (base.topK != candidate.topK) add(TOP_K)
        if (base.topP != candidate.topP) add(TOP_P)
        if (base.minP != candidate.minP) add(MIN_P)
        if (base.repeatPenalty != candidate.repeatPenalty) add(REPEAT_PENALTY)
        if (base.presencePenalty != candidate.presencePenalty) add(PRESENCE_PENALTY)
        if (base.frequencyPenalty != candidate.frequencyPenalty) add(FREQUENCY_PENALTY)
        if (base.stopWords != candidate.stopWords) add(STOP_WORDS)
        if (base.reasoningMode != candidate.reasoningMode) add(REASONING_MODE)
    }
}
