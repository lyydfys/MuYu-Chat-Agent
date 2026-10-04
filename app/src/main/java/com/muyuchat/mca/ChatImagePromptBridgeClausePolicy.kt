package com.muyuchat.mca

/**
 * How the bridge reads model and user text when it has to decide whether a clause is a
 * real prompt clause or provider scaffolding. Three predicates, moved verbatim:
 *
 * * structured metadata clauses describe the generation request, not visual exclusions,
 *   and must never become negative prompt text;
 * * provider placeholders (`none`, `N/A`, `不适用`, ...) mean an absent negative branch
 *   rather than executable prompt text;
 * * an explicit user opt-out of the automatic quality negative branch.
 *
 * No threshold, list entry, regex or message changed with the move; only the names gained
 * the `chatImagePrompt` responsibility prefix. All three were object-private, so they
 * became `internal` to stay reachable from `ChatImagePromptBridge`.
 */

/**
 * Filters transport metadata that can appear after a YAML/JSON negative section. These keys
 * describe the generation request, not visual exclusions, and must never become negative prompt
 * text when the chat model has already returned the canonical branch.
 */
internal fun chatImagePromptIsStructuredMetadataClause(value: String): Boolean {
    val normalized = value.trim().lowercase()
    if (normalized.isBlank()) return true
    return normalized == "```" || normalized.startsWith("```") ||
        normalized == "metadata" || normalized == "metadata:" ||
        normalized.startsWith("metadata:") ||
        normalized.matches(
            Regex("(?:size|width|height|steps|step|seed|sampler|cfg|guidance|batch|count|n|token|tokens)\\s*[:=].*")
        )
}

/** Treat provider placeholders as an absent negative branch rather than executable prompt text. */
internal fun chatImagePromptIsEmptyNegativePromptPlaceholder(value: String): Boolean {
    val normalized = value.trim().lowercase()
        .replace(Regex("[\\s._-]+"), "")
    return normalized.isBlank() || normalized in setOf(
        "none",
        "na",
        "notapplicable",
        "notprovided",
        "无",
        "无负面",
        "无负面提示词",
        "不适用"
    )
}

/** The user can explicitly opt out of the automatic quality branch. */
internal fun chatImagePromptInputDisablesNegativePrompt(value: String): Boolean {
    val normalized = value.trim().lowercase()
    return Regex(
        "(?is)(?:no|without|none|n/?a|无|没有|不需要|不要)\\s*(?:a\\s+)?" +
            "(?:negative(?:\\s+prompt)?|负面(?:提示词)?|负向(?:提示词)?)"
    ).containsMatchIn(normalized) || Regex(
        "(?is)(?:negative(?:\\s+prompt)?|negative_prompt|负面(?:提示词)?|负向(?:提示词)?)" +
            "\\s*[:：=]\\s*(?:none|n/?a|无|无负面|不适用)"
    ).containsMatchIn(normalized)
}
