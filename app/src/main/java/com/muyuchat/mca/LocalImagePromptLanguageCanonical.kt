package com.muyuchat.mca

import java.util.Locale

internal fun missingProtectedPromptFragments(
    source: String,
    translated: String,
    enforceAsciiLiteralEntityNeighborhoods: Boolean
): List<String> {
    if (!source.containsHanScript()) return emptyList()
    val missing = mutableListOf<String>()
    if (source.promptStructureEscapeSignature() != translated.promptStructureEscapeSignature()) {
        missing += "保护结构或转义位置"
    }
    if (source.promptSeparatorSignature(topLevelOnly = true) !=
        translated.promptSeparatorSignature(topLevelOnly = true)
    ) {
        missing += "子句分隔符类型或顺序"
    }
    if (source.promptSeparatorSignature(topLevelOnly = false) !=
        translated.promptSeparatorSignature(topLevelOnly = false)
    ) {
        missing += "保护结构内分隔符类型或顺序"
    }
    fun requireMultiset(
        label: String,
        regex: Regex,
        ignoreCase: Boolean = false,
        rejectUnexpectedValues: Boolean = false
    ) {
        val expected = regex.findAll(source)
            .map { match -> if (ignoreCase) match.value.lowercase(Locale.ROOT) else match.value }
            .groupingBy { it }
            .eachCount()
        val actual = regex.findAll(translated)
            .map { match -> if (ignoreCase) match.value.lowercase(Locale.ROOT) else match.value }
            .groupingBy { it }
            .eachCount()
        expected.forEach { (value, count) ->
            if ((actual[value] ?: 0) != count) missing += "$label:$value"
        }
        if (rejectUnexpectedValues) {
            (actual.keys - expected.keys).forEach { value ->
                missing += "$label:新增:$value"
            }
        }
    }
    requireMultiset(
        "数字",
        Regex("(?<![A-Za-z0-9_])[+-]?\\d+(?:\\.\\d+)?"),
        rejectUnexpectedValues = true
    )
    requireMultiset(
        "控制标点",
        Regex("[|=@#%&*/\\\\\"+_-]"),
        rejectUnexpectedValues = true
    )
    val expectedColonCount = source.count { it == ':' || it == '：' }
    if (translated.count { it == ':' } != expectedColonCount) {
        missing += "控制标点::"
    }
    val sourceNonContractionApostrophes = source.count { it == '\'' } -
        CONTROLLED_APOSTROPHE_CONTRACTION.findAll(source).count()
    val translatedNonContractionApostrophes = translated.count { it == '\'' } -
        CONTROLLED_APOSTROPHE_CONTRACTION.findAll(translated).count()
    if (sourceNonContractionApostrophes != translatedNonContractionApostrophes) {
        missing += "控制标点:'"
    }
    val sourceClauses = source.promptClauses()
    val translatedClauses = translated.promptClauses()
    if (sourceClauses.size == translatedClauses.size) {
        sourceClauses.zip(translatedClauses).forEachIndexed { index, (sourceClause, translatedClause) ->
            if (!sourceClause.containsHanScript() && sourceClause != translatedClause) {
                missing += "子句${index + 1}:纯ASCII片段未逐字保留"
            } else if (!translatedClause.containsAsciiLiteralRunsInOrder(
                    sourceClause.asciiLiteralRuns()
                )
            ) {
                missing += "子句${index + 1}:ASCII片段顺序或大小写"
            }
            if (enforceAsciiLiteralEntityNeighborhoods) {
                missing += missingAsciiLiteralEntityNeighborhoods(
                    sourceClause = sourceClause,
                    translatedClause = translatedClause,
                    clauseNumber = index + 1
                )
            }
            val expected = sourceClause.protectedPromptFragments()
            val actual = translatedClause.protectedPromptFragments()
            if (expected.size != actual.size) {
                missing += "子句${index + 1}:保护片段数量"
                return@forEachIndexed
            }
            expected.zip(actual).forEach { (expectedFragment, actualFragment) ->
                if (expectedFragment.value.containsHanScript()) {
                    if (actualFragment.value.containsHanScript() ||
                        expectedFragment.value.promptLiteralPunctuationSignature() !=
                        actualFragment.value.promptLiteralPunctuationSignature()
                    ) {
                        missing += "子句${index + 1}:中文保护片段结构:${expectedFragment.value.take(32)}"
                    }
                    val expectedBindings = CANONICAL_TRANSLATION_ANCHORS
                        .associate { anchor ->
                            anchor.label to canonicalSourceCount(expectedFragment.value, anchor)
                        }
                        .filterValues { it > 0 }
                    val actualBindings = CANONICAL_TRANSLATION_ANCHORS
                        .associate { anchor ->
                            anchor.label to canonicalTargetCount(actualFragment.value, anchor)
                        }
                        .filterValues { it > 0 }
                    if (expectedBindings != actualBindings) {
                        missing += "子句${index + 1}:中文保护片段语义:${expectedFragment.value.take(32)}"
                    }
                    return@forEach
                }
                if (expectedFragment.value != actualFragment.value) {
                    missing += "子句${index + 1}:ASCII保护片段:${expectedFragment.value.take(32)}"
                    return@forEach
                }
                val expectedBinding = sourceClause.protectedFragmentEntityBinding(
                    expectedFragment,
                    sourceEntityOccurrencesBetween(sourceClause, 0, sourceClause.length)
                        .map { occurrence -> PromptEntityOccurrence(occurrence.anchor.label, occurrence.range) }
                )
                val actualBinding = translatedClause.protectedFragmentEntityBinding(
                    actualFragment,
                    canonicalEnglishEntityOccurrences(
                        translatedClause.maskProtectedPromptStructuresPreservingEntities(sourceClause)
                    )
                        .map { occurrence -> PromptEntityOccurrence(occurrence.anchor.label, occurrence.range) }
                )
                if (expectedBinding != actualBinding) {
                    missing += "子句${index + 1}:ASCII保护片段实体绑定:${expectedFragment.value.take(32)}"
                }
            }

            val expectedScopes = sourceClause.balancedPromptStructureFragments()
            val actualScopes = translatedClause.balancedPromptStructureFragments()
            val sourceScopeEntities = sourceEntityOccurrencesBetween(
                sourceClause,
                0,
                sourceClause.length
            ).map { occurrence ->
                PromptEntityOccurrence(occurrence.anchor.label, occurrence.range)
            }
            val targetScopeEntities = canonicalEnglishEntityOccurrences(translatedClause)
                .map { occurrence ->
                    PromptEntityOccurrence(occurrence.anchor.label, occurrence.range)
                }
            if (expectedScopes.size != actualScopes.size) {
                missing += "子句${index + 1}:嵌套保护片段数量"
            } else {
                expectedScopes.zip(actualScopes).forEachIndexed { scopeIndex, (expectedScope, actualScope) ->
                    if (!expectedScope.value.containsHanScript()) return@forEachIndexed
                    val expectedBindings = CANONICAL_TRANSLATION_ANCHORS.associate { anchor ->
                        anchor.label to canonicalSourceCount(expectedScope.value, anchor)
                    }.filterValues { count -> count > 0 }
                    val actualBindings = CANONICAL_TRANSLATION_ANCHORS.associate { anchor ->
                        anchor.label to canonicalTargetCount(actualScope.value, anchor)
                    }.filterValues { count -> count > 0 }
                    if (expectedBindings != actualBindings) {
                        missing += "子句${index + 1}:嵌套保护语义${scopeIndex + 1}"
                    }
                    val expectedEntityIdentities = sourceScopeEntities
                        .filter { entity -> expectedScope.range.contains(entity.range) }
                        .map { entity -> entity.identityIn(sourceScopeEntities) }
                    val actualEntityIdentities = targetScopeEntities
                        .filter { entity -> actualScope.range.contains(entity.range) }
                        .map { entity -> entity.identityIn(targetScopeEntities) }
                    if (expectedEntityIdentities != actualEntityIdentities) {
                        missing += "子句${index + 1}:嵌套保护实体${scopeIndex + 1}"
                    }
                    if (expectedScope.value.controlledPunctuationEntityNeighborhoods(
                            sourceLanguage = true
                        ) != actualScope.value.controlledPunctuationEntityNeighborhoods(
                            sourceLanguage = false
                        )
                    ) {
                        missing += "子句${index + 1}:嵌套控制标点位置${scopeIndex + 1}"
                    }
                }
            }
        }
    }
    return missing
}

internal data class PromptEntityOccurrence(
    val label: String,
    val range: IntRange
)

internal fun String.controlledPunctuationEntityNeighborhoods(
    sourceLanguage: Boolean
): List<String> {
    val entities = if (sourceLanguage) {
        canonicalSourceOccurrences(this) { anchor -> anchor.entity }
            .distinctBy { occurrence -> occurrence.anchor.label to occurrence.range }
            .map { occurrence -> PromptEntityOccurrence(occurrence.anchor.label, occurrence.range) }
    } else {
        canonicalEnglishEntityOccurrences(this)
            .map { occurrence -> PromptEntityOccurrence(occurrence.anchor.label, occurrence.range) }
    }.sortedBy { occurrence -> occurrence.range.first }
    val ordinals = mutableMapOf<Char, Int>()
    return buildList {
        this@controlledPunctuationEntityNeighborhoods.forEachIndexed { index, character ->
            if (character !in CONTROLLED_POSITION_PUNCTUATION) return@forEachIndexed
            if (character in setOf('+', '-') &&
                this@controlledPunctuationEntityNeighborhoods.getOrNull(index + 1)?.isDigit() == true
            ) return@forEachIndexed
            val ordinal = ordinals.getOrDefault(character, 0)
            ordinals[character] = ordinal + 1
            val occurrence = PromptProtectedFragment(character.toString(), index..index)
            add("$character#$ordinal:${promptFragmentEntityNeighborhood(occurrence, entities)}")
        }
    }
}

internal fun PromptEntityOccurrence.identityIn(
    entities: List<PromptEntityOccurrence>
): String {
    val ordinal = entities.filter { it.label == label }
        .sortedBy { it.range.first }
        .indexOf(this)
    return "$label#$ordinal"
}

internal fun promptFragmentEntityNeighborhood(
    fragment: PromptProtectedFragment,
    entities: List<PromptEntityOccurrence>
): String {
    val before = entities.lastOrNull { it.range.last < fragment.range.first }
        ?.identityIn(entities)
        ?: "none"
    val overlapping = entities.singleOrNull { it.range.overlaps(fragment.range) }
        ?.identityIn(entities)
        ?: "none"
    val after = entities.firstOrNull { it.range.first > fragment.range.last }
        ?.identityIn(entities)
        ?: "none"
    return "$before|$overlapping|$after"
}

internal fun missingAsciiLiteralEntityNeighborhoods(
    sourceClause: String,
    translatedClause: String,
    clauseNumber: Int
): List<String> {
    if (!sourceClause.containsHanScript()) return emptyList()
    val expected = sourceClause.asciiLiteralFragmentsOutsideProtectedStructures()
    if (expected.isEmpty()) return emptyList()
    val sourceEntities = sourceEntityOccurrencesBetween(sourceClause, 0, sourceClause.length)
        .map { PromptEntityOccurrence(it.anchor.label, it.range) }
    val translatedEntities = canonicalEnglishEntityOccurrences(
        translatedClause.maskLiteralProtectedFragmentsDeclaredBy(sourceClause)
    )
        .map { PromptEntityOccurrence(it.anchor.label, it.range) }
    var cursor = 0
    return buildList {
        expected.forEach { fragment ->
            val expectedNeighborhood = promptFragmentEntityNeighborhood(fragment, sourceEntities)
            var candidateIndex = translatedClause.indexOf(fragment.value, startIndex = cursor)
            var accepted: PromptProtectedFragment? = null
            while (candidateIndex >= 0) {
                val candidate = PromptProtectedFragment(
                    value = fragment.value,
                    range = candidateIndex until (candidateIndex + fragment.value.length)
                )
                if (promptFragmentEntityNeighborhood(candidate, translatedEntities) ==
                    expectedNeighborhood
                ) {
                    accepted = candidate
                    break
                }
                candidateIndex = translatedClause.indexOf(
                    fragment.value,
                    startIndex = candidateIndex + 1
                )
            }
            if (accepted == null) {
                add("子句$clauseNumber:ASCII片段实体位置:${fragment.value.take(32)}")
            } else {
                cursor = accepted.range.last + 1
            }
        }
    }
}

internal fun String.maskAsciiLiteralFragmentsDeclaredBy(source: String): String {
    val expected = source.asciiLiteralFragmentsOutsideProtectedStructures()
    if (expected.isEmpty()) return this
    val sourceEntities = sourceEntityOccurrencesBetween(source, 0, source.length)
        .map { occurrence -> PromptEntityOccurrence(occurrence.anchor.label, occurrence.range) }
    val translatedEntities = canonicalEnglishEntityOccurrences(this)
        .map { occurrence -> PromptEntityOccurrence(occurrence.anchor.label, occurrence.range) }
    val masked = toCharArray()
    var cursor = 0
    expected.forEach { fragment ->
        val expectedNeighborhood = promptFragmentEntityNeighborhood(fragment, sourceEntities)
        var candidateIndex = indexOf(fragment.value, startIndex = cursor)
        var accepted: PromptProtectedFragment? = null
        while (candidateIndex >= 0) {
            val candidate = PromptProtectedFragment(
                value = fragment.value,
                range = candidateIndex until (candidateIndex + fragment.value.length)
            )
            if (promptFragmentEntityNeighborhood(candidate, translatedEntities) ==
                expectedNeighborhood
            ) {
                accepted = candidate
                break
            }
            candidateIndex = indexOf(fragment.value, startIndex = candidateIndex + 1)
        }
        if (accepted != null) {
            accepted.range.forEach { index -> masked[index] = ' ' }
            val declaredEntityCounts = sourceEntities
                .filter { entity -> entity.range.overlaps(fragment.range) }
                .groupingBy { entity -> entity.label }
                .eachCount()
                .toMutableMap()
            translatedEntities
                .asSequence()
                .filter { entity -> entity.range.overlaps(accepted.range) }
                .sortedBy { entity -> entity.range.first }
                .forEach { entity ->
                    val remaining = declaredEntityCounts[entity.label] ?: 0
                    if (remaining > 0) {
                        entity.range
                            .filter { index -> index in accepted.range }
                            .forEach { index -> masked[index] = this[index] }
                        declaredEntityCounts[entity.label] = remaining - 1
                    }
                }
            cursor = accepted.range.last + 1
        }
    }
    return String(masked)
}

internal fun String.protectedFragmentEntityBinding(
    fragment: PromptProtectedFragment,
    entities: List<PromptEntityOccurrence>
): String {
    if (entities.isEmpty()) return "none"
    val minimumDistance = entities.minOf { entity -> rangeDistance(fragment.range, entity.range) }
    val nearest = entities.filter { entity ->
        rangeDistance(fragment.range, entity.range) == minimumDistance
    }
    if (nearest.size != 1) return "ambiguous"
    val entity = nearest.single()
    val ordinal = entities.filter { it.label == entity.label }
        .sortedBy { it.range.first }
        .indexOf(entity)
    val relativePosition = when {
        fragment.range.last < entity.range.first -> "before"
        entity.range.last < fragment.range.first -> "after"
        else -> "overlap"
    }
    return "${entity.label}#$ordinal:$relativePosition"
}

internal fun rangeDistance(first: IntRange, second: IntRange): Int = when {
    first.last < second.first -> second.first - first.last - 1
    second.last < first.first -> first.first - second.last - 1
    else -> 0
}

internal fun String.asciiLiteralRuns(): List<String> = ASCII_LITERAL_RUN.findAll(this)
    .map { it.value.trim() }
    .filter { it.isNotEmpty() }
    .toList()

internal fun String.containsAsciiLiteralRunsInOrder(expected: List<String>): Boolean {
    var cursor = 0
    return expected.all { literal ->
        val index = indexOf(literal, startIndex = cursor)
        if (index < 0) return@all false
        cursor = index + literal.length
        true
    }
}

internal fun String.promptLiteralPunctuationSignature(): String = filter { character ->
    !character.isWhitespace() && !character.isLetter()
}.map { character ->
    when (character) {
        '（' -> '('
        '）' -> ')'
        '［' -> '['
        '］' -> ']'
        '｛' -> '{'
        '｝' -> '}'
        '＜' -> '<'
        '＞' -> '>'
        '：' -> ':'
        '，' -> ','
        '；' -> ';'
        '。' -> '.'
        '！' -> '!'
        '？' -> '?'
        else -> character
    }
}.joinToString("")

