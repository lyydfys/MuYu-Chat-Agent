package com.muyuchat.mca

import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

internal data class LocalImagePromptTranslation(
    val prompt: String,
    val negativePrompt: String?
)

internal fun parseLocalImagePromptTranslation(
    raw: String,
    originalPrompt: String,
    originalNegativePrompt: String?
): LocalImagePromptTranslation {
    val normalized = raw.trim().removeSingleJsonFence()
    require(normalized.length <= LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS * 2) {
        "本地翻译模型返回内容过长。"
    }
    val root = JSONObject(normalized)
    require(
        root.keys().asSequence().toSet() == setOf(
            "prompt",
            "negative_prompt",
            "contract_canary_prompt",
            "contract_canary_negative_prompt"
        )
    ) {
        "本地翻译模型没有返回严格的提示词 JSON。"
    }
    val rawTranslatedPrompt = root.getString("prompt").trim()
    val rawTranslatedNegative = if (root.isNull("negative_prompt")) {
        null
    } else {
        root.getString("negative_prompt").trim()
    }
    val rawTranslatedCanaryPrompt = root.getString("contract_canary_prompt").trim()
    val rawTranslatedCanaryNegativePrompt =
        root.getString("contract_canary_negative_prompt").trim()
    val translatedPrompt = normalizeControlledPositiveTranslation(
        source = originalPrompt,
        translated = rawTranslatedPrompt
    )
    val translatedNegative = rawTranslatedNegative?.let { translated ->
        normalizeControlledNegativeTranslation(
            source = originalNegativePrompt.orEmpty(),
            translated = translated
        )
    }
    val translatedCanaryPrompt = normalizeControlledPositiveTranslation(
        source = LOCAL_IMAGE_PROMPT_TRANSLATION_CANARY_PROMPT,
        translated = rawTranslatedCanaryPrompt
    )
    val translatedCanaryNegativePrompt = normalizeControlledNegativeTranslation(
        source = LOCAL_IMAGE_PROMPT_TRANSLATION_CANARY_NEGATIVE_PROMPT,
        translated = rawTranslatedCanaryNegativePrompt
    )
    require(translatedPrompt.isNotBlank() &&
        translatedPrompt.length <= LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS
    ) { "本地翻译模型返回的正向提示词无效。" }
    require(translatedNegative == null ||
        translatedNegative.length <= LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS
    ) { "本地翻译模型返回的负向提示词无效。" }
    require(translatedCanaryPrompt.isNotBlank() &&
        translatedCanaryPrompt.length <= LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS &&
        translatedCanaryNegativePrompt.isNotBlank() &&
        translatedCanaryNegativePrompt.length <= LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS
    ) { "本地翻译模型没有完成语义能力校验。" }
    validateLocalImagePromptTranslationSemantics(
        originalPrompt = LOCAL_IMAGE_PROMPT_TRANSLATION_CANARY_PROMPT,
        effectivePrompt = translatedCanaryPrompt,
        originalNegativePrompt = LOCAL_IMAGE_PROMPT_TRANSLATION_CANARY_NEGATIVE_PROMPT,
        effectiveNegativePrompt = translatedCanaryNegativePrompt
    )
    validateLocalImagePromptTranslationSemantics(
        originalPrompt = originalPrompt,
        effectivePrompt = translatedPrompt,
        originalNegativePrompt = originalNegativePrompt,
        effectiveNegativePrompt = translatedNegative
    )
    return LocalImagePromptTranslation(translatedPrompt, translatedNegative)
}

/**
 * Small local translators often render an explicit Chinese singular classifier as an English
 * indefinite article. Diffusion conditioning is more reliable when the requested quantity stays
 * explicit, so canonicalize only a uniquely bound `a/an + attributes + entity` occurrence. A
 * repeated target entity in the same clause is deliberately ambiguous and remains fail-closed in
 * the semantic validator below.
 */
private fun normalizeControlledPositiveTranslation(source: String, translated: String): String {
    if (!source.containsHanScript()) return translated
    val sourceFragments = source.protectedPromptFragments()
    val translatedFragments = translated.protectedPromptFragments()
    val nestedNormalized = if (sourceFragments.size == translatedFragments.size) {
        sourceFragments.zip(translatedFragments)
            .sortedByDescending { (_, translatedFragment) -> translatedFragment.range.first }
            .fold(translated) { result, (sourceFragment, translatedFragment) ->
                if (!sourceFragment.value.containsHanScript()) return@fold result
                val sourceInner = sourceFragment.innerPromptStructureValueOrNull()
                    ?: return@fold result
                val translatedInner = translatedFragment.innerPromptStructureValueOrNull()
                    ?: return@fold result
                val normalizedInner = normalizeControlledPositiveTranslation(
                    source = sourceInner,
                    translated = translatedInner
                )
                if (normalizedInner == translatedInner) {
                    result
                } else {
                    result.replaceRange(
                        translatedFragment.range.first + 1,
                        translatedFragment.range.last,
                        normalizedInner
                    )
                }
            }
    } else {
        translated
    }
    return normalizeControlledPositiveTranslationTopLevel(source, nestedNormalized)
}

private fun normalizeControlledPositiveTranslationTopLevel(
    source: String,
    translated: String
): String {
    if (!source.containsHanScript()) return translated
    val sourceClauses = source.promptClauses()
    val translatedClauses = translated.promptClauseSpans()
    if (sourceClauses.size != translatedClauses.size) return translated
    val singularAnchor = CANONICAL_TRANSLATION_ANCHORS.single { it.label == "一个" }
    val replacements = mutableSetOf<IntRange>()

    sourceClauses.zip(translatedClauses).forEach { (sourceClause, translatedClause) ->
        val sourceEntities = canonicalSourceOccurrences(sourceClause) { it.entity }
            .distinctBy { it.anchor.label to it.range }
        if (sourceEntities.isEmpty()) return@forEach
        val translatedTokens = translatedClause.value
            .maskProtectedPromptStructures()
            .englishPromptTokens()
        canonicalSourceOccurrences(sourceClause) { it == singularAnchor }
            .distinctBy(CanonicalSourceOccurrence::range)
            .forEach quantity@{ quantityOccurrence ->
                val nearestDistance = sourceEntities.minOf { entity ->
                    sourceOccurrenceDistance(quantityOccurrence, entity)
                }
                val nearestEntities = sourceEntities.filter { entity ->
                    sourceOccurrenceDistance(quantityOccurrence, entity) == nearestDistance
                }
                if (nearestEntities.size != 1) return@quantity
                val boundEntity = nearestEntities.single().anchor
                val translatedEntityIndexes = translatedTokens.indices.filter { index ->
                    translatedTokens[index].matchesSingleWordTarget(boundEntity)
                }
                if (translatedEntityIndexes.size != 1) return@quantity
                val entityIndex = translatedEntityIndexes.single()
                val explicitQuantities = translatedTokens.precedingQuantityIndexes(
                    entityIndex = entityIndex,
                    accepted = singularAnchor.targets
                )
                if (explicitQuantities.size == 1) return@quantity
                if (explicitQuantities.isNotEmpty()) return@quantity
                val articles = translatedTokens.precedingQuantityIndexes(
                    entityIndex = entityIndex,
                    accepted = setOf("a", "an")
                )
                if (articles.size != 1) return@quantity
                val articleRange = translatedTokens[articles.single()].range
                val globalRange =
                    (translatedClause.range.first + articleRange.first)..
                        (translatedClause.range.first + articleRange.last)
                if (translated.isInsideProtectedPromptStructure(globalRange)) return@quantity
                if (replacements.none { it == globalRange }) replacements += globalRange
            }
    }

    return replacements.sortedByDescending(IntRange::first).fold(translated) { result, range ->
        result.replaceRange(range.first, range.last + 1, "one")
    }
}

/**
 * A diffusion negative prompt is a concept list, not a natural-language prohibition. Remove one
 * tightly controlled negation prefix only when the matching Chinese source clause explicitly uses
 * a negative operator. The complete semantic validator still runs afterwards and rejects extra,
 * missing, reordered, or ambiguous concepts.
 */
private fun normalizeControlledNegativeTranslation(source: String, translated: String): String {
    if (!source.containsHanScript()) return translated
    val sourceClauses = source.promptClauses()
    val translatedClauses = translated.promptClauseSpans()
    if (sourceClauses.size != translatedClauses.size) return translated
    val replacements = mutableListOf<Pair<IntRange, String>>()

    sourceClauses.zip(translatedClauses).forEach { (sourceClause, translatedClause) ->
        if (CANONICAL_NEGATION_ANCHOR.sources.none(sourceClause::contains)) return@forEach
        val prefix = CONTROLLED_NEGATIVE_CONDITIONING_PREFIX.find(translatedClause.value)
            ?: return@forEach
        val normalizedClause = translatedClause.value
            .substring(prefix.range.last + 1)
            .trimStart()
        if (normalizedClause.isEmpty()) return@forEach
        replacements += translatedClause.range to normalizedClause
    }

    return replacements.sortedByDescending { it.first.first }.fold(translated) { result, item ->
        val (range, replacement) = item
        result.replaceRange(range.first, range.last + 1, replacement)
    }
}

internal fun normalizeLocalImagePromptTranslationClauseV4(
    source: String,
    translated: String,
    negativeConditioning: Boolean
): String = if (negativeConditioning) {
    normalizeControlledNegativeTranslation(source, translated)
} else {
    normalizeControlledPositiveTranslation(source, translated)
}


internal fun validateLocalImagePromptTranslationSemantics(
    originalPrompt: String,
    effectivePrompt: String,
    originalNegativePrompt: String?,
    effectiveNegativePrompt: String?
) = validateLocalImagePromptTranslationSemanticsInternal(
    originalPrompt = originalPrompt,
    effectivePrompt = effectivePrompt,
    originalNegativePrompt = originalNegativePrompt,
    effectiveNegativePrompt = effectiveNegativePrompt,
    requireControlledChineseVocabulary = true
)

internal fun validateLocalImagePromptTranslationHardContractsV4(
    originalPrompt: String,
    effectivePrompt: String,
    originalNegativePrompt: String?,
    effectiveNegativePrompt: String?
) = validateLocalImagePromptTranslationSemanticsInternal(
    originalPrompt = originalPrompt,
    effectivePrompt = effectivePrompt,
    originalNegativePrompt = originalNegativePrompt,
    effectiveNegativePrompt = effectiveNegativePrompt,
    requireControlledChineseVocabulary = false
)

private fun validateLocalImagePromptTranslationSemanticsInternal(
    originalPrompt: String,
    effectivePrompt: String,
    originalNegativePrompt: String?,
    effectiveNegativePrompt: String?,
    requireControlledChineseVocabulary: Boolean
) {
    require(effectivePrompt.isSafeAsciiDiffusionPrompt() &&
        effectiveNegativePrompt?.isSafeAsciiDiffusionPrompt() != false
    ) {
        "本地翻译模型返回了不安全的非 ASCII 提示词字符。"
    }
    require(!effectivePrompt.containsHanScript() && effectiveNegativePrompt?.containsHanScript() != true) {
        "本地翻译模型未完成中文提示词转换。"
    }
    require((originalNegativePrompt == null) == (effectiveNegativePrompt == null)) {
        "本地翻译模型改变了负向提示词的存在状态。"
    }
    if (!originalPrompt.containsHanScript()) {
        require(effectivePrompt == originalPrompt) {
            "本地翻译模型改写了无需翻译的正向提示词。"
        }
    }
    if (originalNegativePrompt != null && !originalNegativePrompt.containsHanScript()) {
        require(effectiveNegativePrompt == originalNegativePrompt) {
            "本地翻译模型改写了无需翻译的负向提示词。"
        }
    }
    val missingSemantics = buildList {
        addAll(unverifiedSourceUnicodeCharacters(originalPrompt))
        if (requireControlledChineseVocabulary) {
            addAll(unverifiedChinesePromptFragments(originalPrompt))
        }
        addAll(missingCanonicalTranslationAnchors(originalPrompt, effectivePrompt))
        if (requireControlledChineseVocabulary) {
            addAll(unexpectedCanonicalTranslationAnchors(originalPrompt, effectivePrompt))
        }
        if (requireControlledChineseVocabulary) {
            addAll(unexpectedControlledEnglishTokens(originalPrompt, effectivePrompt))
        }
        addAll(
            missingCanonicalClauseBindings(
                originalPrompt,
                effectivePrompt,
                requireControlledVocabulary = requireControlledChineseVocabulary
            )
        )
        addAll(
            missingProtectedPromptFragments(
                source = originalPrompt,
                translated = effectivePrompt,
                enforceAsciiLiteralEntityNeighborhoods = requireControlledChineseVocabulary
            )
        )
        if (originalNegativePrompt != null && effectiveNegativePrompt != null) {
            addAll(unverifiedSourceUnicodeCharacters(originalNegativePrompt))
            if (requireControlledChineseVocabulary) {
                addAll(unverifiedChinesePromptFragments(originalNegativePrompt))
            }
            addAll(
                missingCanonicalTranslationAnchors(
                    originalNegativePrompt,
                    effectiveNegativePrompt,
                    negativeConditioning = true
                )
            )
            addAll(
                missingCanonicalClauseBindings(
                    originalNegativePrompt,
                    effectiveNegativePrompt,
                    negativeConditioning = true,
                    requireControlledVocabulary = requireControlledChineseVocabulary
                )
            )
            if (requireControlledChineseVocabulary) {
                addAll(
                    unexpectedCanonicalTranslationAnchors(
                        originalNegativePrompt,
                        effectiveNegativePrompt,
                        negativeConditioning = true
                    )
                )
            }
            if (requireControlledChineseVocabulary) {
                addAll(unexpectedControlledEnglishTokens(originalNegativePrompt, effectiveNegativePrompt))
            }
            addAll(
                missingProtectedPromptFragments(
                    source = originalNegativePrompt,
                    translated = effectiveNegativePrompt,
                    enforceAsciiLiteralEntityNeighborhoods = requireControlledChineseVocabulary
                )
            )
        }
    }.distinct()
    require(missingSemantics.isEmpty()) {
        "本地翻译模型遗漏或改写了关键语义：${missingSemantics.joinToString()}。"
    }
}

private fun unverifiedSourceUnicodeCharacters(source: String): List<String> {
    val unsupported = mutableListOf<Int>()
    source.codePoints().forEach { codePoint ->
        val allowed = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN ||
            codePoint == '\n'.code || codePoint == '\r'.code ||
            codePoint in 0x20..0x7e ||
            codePoint in SUPPORTED_CHINESE_DIFFUSION_PROMPT_PUNCTUATION
        if (!allowed) unsupported += codePoint
    }
    return unsupported.distinct().map { codePoint ->
        "未覆盖源字符:U+${codePoint.toString(16).uppercase(Locale.ROOT).padStart(4, '0')}"
    }
}

internal fun localImagePromptTranslationPayload(prompt: String, negativePrompt: String?): String =
    JSONObject()
        .put("prompt", prompt)
        .put("negative_prompt", negativePrompt ?: JSONObject.NULL)
        .put("contract_canary_prompt", LOCAL_IMAGE_PROMPT_TRANSLATION_CANARY_PROMPT)
        .put(
            "contract_canary_negative_prompt",
            LOCAL_IMAGE_PROMPT_TRANSLATION_CANARY_NEGATIVE_PROMPT
        )
        .toString()

internal fun promptFingerprint(prompt: String): String = MessageDigest.getInstance("SHA-256")
    .digest(prompt.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun String.removeSingleJsonFence(): String {
    if (!startsWith("```") || !endsWith("```")) return this
    val firstLineEnd = indexOf('\n')
    require(firstLineEnd >= 0) { "本地翻译模型返回的 JSON 代码块无效。" }
    val language = substring(3, firstLineEnd).trim()
    require(language.isEmpty() || language.equals("json", ignoreCase = true)) {
        "本地翻译模型返回了非 JSON 代码块。"
    }
    return substring(firstLineEnd + 1, length - 3).trim()
}

private fun missingCanonicalTranslationAnchors(
    source: String,
    translated: String,
    negativeConditioning: Boolean = false
): List<String> {
    val lower = translated.lowercase(Locale.ROOT)
    val missing = CANONICAL_TRANSLATION_ANCHORS.mapNotNull { anchor ->
        if (anchor.sources.none(source::contains)) return@mapNotNull null
        if (negativeConditioning && anchor.negativeOperator) return@mapNotNull null
        anchor.label.takeUnless { anchor.targets.any { lower.containsCanonicalTarget(it) } }
    }.toMutableList()
    if (negativeConditioning && containsNegativeConditioningOperator(lower)) {
        missing += "负向条件包含否定操作词"
    }
    return missing
}

/**
 * A global word bag cannot detect swapped bindings such as "left red cat/right blue dog" becoming
 * "left blue dog/right red cat". Keep every verifiable source clause bound to one translated clause.
 */
private fun missingCanonicalClauseBindings(
    source: String,
    translated: String,
    negativeConditioning: Boolean = false,
    requireControlledVocabulary: Boolean = true
): List<String> {
    if (!source.containsHanScript()) return emptyList()
    val sourceClauses = source.promptClauses()
    val translatedClauses = translated.promptClauses()
        .map { it.lowercase(Locale.ROOT) }
    if (sourceClauses.size != translatedClauses.size) {
        return listOf("子句数量:${sourceClauses.size}->${translatedClauses.size}")
    }
    return buildList {
        sourceClauses.zip(translatedClauses).forEachIndexed { index, (sourceClause, translatedClause) ->
            val semanticTranslatedClause =
                translatedClause.maskLiteralProtectedFragmentsDeclaredBy(sourceClause)
            val comparableAnchors = CANONICAL_TRANSLATION_ANCHORS.filterNot { anchor ->
                negativeConditioning && anchor.negativeOperator
            }
            val sourceBindings = comparableAnchors.associate { anchor ->
                anchor.label to if (anchor.negativeOperator && !negativeConditioning) {
                    positiveNegationSourceEntities(sourceClause).size
                } else {
                    canonicalSourceCount(sourceClause, anchor)
                }
            }.filterValues { it > 0 }
            val translatedBindings = comparableAnchors.associate { anchor ->
                anchor.label to canonicalTargetCount(semanticTranslatedClause, anchor)
            }.filterValues { it > 0 }
            val strictKnownClause = requireControlledVocabulary ||
                unverifiedChinesePromptFragments(sourceClause).isEmpty()
            if (strictKnownClause && !requireControlledVocabulary) {
                addAll(
                    unexpectedControlledEnglishTokensInClause(
                        sourceClause = sourceClause,
                        translatedClause = translatedClause,
                        clauseNumber = index + 1
                    )
                )
            }
            val bindingMismatch = if (strictKnownClause) {
                sourceBindings != translatedBindings
            } else {
                sourceBindings.any { (label, count) -> translatedBindings[label] != count }
            }
            if (bindingMismatch) {
                add(
                    "子句${index + 1}:" +
                        sourceBindings.entries.joinToString("+") { "${it.key}x${it.value}" } +
                        "->" +
                        translatedBindings.entries.joinToString("+") { "${it.key}x${it.value}" }
                )
            }
            addAll(missingCanonicalAttributeBindings(sourceClause, translatedClause, index + 1))
            addAll(
                missingCanonicalRelationTuples(
                    sourceClause = sourceClause,
                    translatedClause = translatedClause,
                    clauseNumber = index + 1,
                    rejectUnexpectedRelations = strictKnownClause
                )
            )
            if (!negativeConditioning) {
                addAll(missingPositiveNegationBindings(sourceClause, translatedClause, index + 1))
            }
        }
    }
}

private fun positiveNegationSourceEntities(
    sourceClause: String
): List<CanonicalSourceOccurrence> {
    val operators = canonicalSourceOccurrences(sourceClause) { it.negativeOperator }
        .distinctBy(CanonicalSourceOccurrence::range)
        .sortedBy { it.range.first }
    val entities = canonicalSourceOccurrences(sourceClause) { it.entity }
        .distinctBy { it.anchor.label to it.range }
        .sortedBy { it.range.first }
    return operators.flatMapIndexed { index, operator ->
        val nextOperatorStart = operators.getOrNull(index + 1)?.range?.first ?: sourceClause.length
        val containingFragmentEnd = sourceClause.protectedPromptFragments()
            .singleOrNull { fragment -> fragment.range.contains(operator.range) }
            ?.range
            ?.last
            ?.plus(1)
            ?: sourceClause.length
        val endExclusive = minOf(nextOperatorStart, containingFragmentEnd)
        entities.filter { entity ->
            entity.range.first > operator.range.last && entity.range.last < endExclusive
        }
    }
}

private fun missingPositiveNegationBindings(
    sourceClause: String,
    translatedClause: String,
    clauseNumber: Int
): List<String> {
    val sourceEntities = canonicalSourceOccurrences(sourceClause) { it.entity }
        .distinctBy { it.anchor.label to it.range }
        .sortedBy { it.range.first }
    val expected = positiveNegationSourceEntities(sourceClause).map { entity ->
        val ordinal = sourceEntities
            .filter { it.anchor.label == entity.anchor.label }
            .indexOf(entity)
        "${entity.anchor.label}#$ordinal"
    }
    if (CANONICAL_NEGATION_ANCHOR.sources.any(sourceClause::contains) && expected.isEmpty()) {
        return listOf("子句$clauseNumber:正向否定缺少作用实体")
    }
    if (expected.isEmpty()) return emptyList()
    val relationText = translatedClause.maskProtectedPromptStructuresPreservingEntities(sourceClause)
    val operators = NEGATIVE_CONDITIONING_OPERATOR.findAll(relationText).toList()
    val entities = canonicalEnglishEntityOccurrences(relationText)
    val actual = operators.mapNotNull { operator ->
        entities.firstOrNull { it.range.first > operator.range.last }?.let { entity ->
            val ordinal = entities
                .filter { it.anchor.label == entity.anchor.label }
                .indexOf(entity)
            "${entity.anchor.label}#$ordinal"
        }
    }
    return if (actual == expected && operators.size == expected.size) {
        emptyList()
    } else {
        listOf(
            "子句$clauseNumber:正向否定作用域:" +
                "${expected.joinToString("+")}->${actual.joinToString("+")}"
        )
    }
}

private val CONTROLLED_NEGATIVE_CONDITIONING_PREFIX = Regex(
    "^(?:do\\s+not\\s+include|do\\s+not|don't\\s+include|don't|without|avoid|exclude|not|no)" +
        "\\b(?:[\\s,:;\\-]+|$)",
    RegexOption.IGNORE_CASE
)

private val NEGATIVE_CONDITIONING_OPERATOR = Regex(
    "(?<![a-z0-9_])(?:do\\s+not(?:\\s+include)?|don't(?:\\s+include)?|" +
        "no|not|without|avoid|exclude)(?![a-z0-9_])",
    RegexOption.IGNORE_CASE
)

private fun containsNegativeConditioningOperator(value: String): Boolean =
    NEGATIVE_CONDITIONING_OPERATOR.containsMatchIn(value)
