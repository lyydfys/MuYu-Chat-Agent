package com.muyuchat.mca

import java.util.Locale

internal fun canonicalSourceCount(
    source: String,
    anchor: CanonicalTranslationAnchor
): Int = countNonOverlappingCanonicalMatches(
    source = source.maskAsciiOnlyProtectedPromptStructures(),
    patterns = anchor.sources.map { Regex(Regex.escape(it)) } +
        anchor.targets.map { target ->
            Regex(
                "(?<![A-Za-z0-9_.])${Regex.escape(target.trim())}(?![A-Za-z0-9_.])",
                RegexOption.IGNORE_CASE
            )
        }
)

internal fun canonicalTargetCount(
    translated: String,
    anchor: CanonicalTranslationAnchor
): Int = countNonOverlappingCanonicalMatches(
    source = translated.lowercase(Locale.ROOT),
    patterns = anchor.targets.map { target ->
        Regex(
            "(?<![a-z0-9_.])${Regex.escape(target.trim().lowercase(Locale.ROOT))}(?![a-z0-9_.])",
            RegexOption.IGNORE_CASE
        )
    }
)

private fun countNonOverlappingCanonicalMatches(
    source: String,
    patterns: List<Regex>
): Int {
    val occupied = BooleanArray(source.length)
    var count = 0
    patterns.sortedByDescending { it.pattern.length }.forEach { pattern ->
        pattern.findAll(source).forEach { match ->
            if (match.range.none { occupied[it] }) {
                match.range.forEach { occupied[it] = true }
                count += 1
            }
        }
    }
    return count
}

internal fun unverifiedChinesePromptFragments(source: String): List<String> {
    if (!source.containsHanScript()) return emptyList()
    val consumed = BooleanArray(source.length)
    fun consume(range: IntRange) {
        range.forEach { index -> if (index in consumed.indices) consumed[index] = true }
    }
    val knownTokens = CANONICAL_TRANSLATION_ANCHORS
        .flatMap { it.sources }
        .distinct()
        .sortedByDescending(String::length)
    knownTokens.forEach { token ->
        Regex(Regex.escape(token)).findAll(source).forEach { match -> consume(match.range) }
    }
    source.promptClauseSpans().forEach { clause ->
        val offset = clause.range.first
        fun consumeLocal(range: IntRange) = consume(
            (offset + range.first)..(offset + range.last)
        )
        consumedSemanticRelationMarkerRanges(clause.value).forEach(::consumeLocal)
        conjunctionSourceRelations(clause.value)
            .flatMap(CanonicalSourceBinaryRelation::markerRanges)
            .forEach(::consumeLocal)
        consumedAttributiveParticleRanges(clause.value).forEach(::consumeLocal)
    }
    val remainder = buildString(source.length) {
        source.forEachIndexed { index, character ->
            append(if (consumed[index]) ' ' else character)
        }
    }
    val unknown = buildString {
        remainder.codePoints().forEach { codePoint ->
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                appendCodePoint(codePoint)
            }
        }
    }
    return if (unknown.isEmpty()) emptyList() else listOf("未覆盖中文:${unknown.take(24)}")
}

internal fun unexpectedControlledEnglishTokens(source: String, translated: String): List<String> {
    if (!source.containsHanScript()) return emptyList()
    val sourceClauses = source.promptClauses()
    val translatedClauses = translated.promptClauses()
    if (sourceClauses.size != translatedClauses.size) return emptyList()
    return sourceClauses.zip(translatedClauses).flatMapIndexed { clauseIndex, (sourceClause, translatedClause) ->
        unexpectedControlledEnglishTokensInClause(
            sourceClause = sourceClause,
            translatedClause = translatedClause,
            clauseNumber = clauseIndex + 1
        )
    }.distinct()
}

internal fun unexpectedControlledEnglishTokensInClause(
    sourceClause: String,
    translatedClause: String,
    clauseNumber: Int
): List<String> {
    if (!sourceClause.containsHanScript()) return emptyList()
    val allowed = buildSet {
        addAll(CONTROLLED_ENGLISH_GRAMMAR_TOKENS)
        addAll(controlledRelationTargetWords(sourceClause))
        CANONICAL_TRANSLATION_ANCHORS.forEach { anchor ->
            if (anchor.sources.any(sourceClause::contains)) {
                anchor.targets.forEach { target ->
                    ENGLISH_PROMPT_TOKEN.findAll(target.lowercase(Locale.ROOT))
                        .mapTo(this) { it.value }
                }
            }
        }
        ENGLISH_PROMPT_TOKEN.findAll(sourceClause.lowercase(Locale.ROOT)).mapTo(this) { it.value }
    }
    return ENGLISH_PROMPT_TOKEN.findAll(translatedClause.lowercase(Locale.ROOT))
        .map { it.value }
        .filterNot(allowed::contains)
        .distinct()
        .map { "子句$clauseNumber:未授权英文:$it" }
        .toList()
}

internal fun missingCanonicalAttributeBindings(
    sourceClause: String,
    translatedClause: String,
    clauseNumber: Int
): List<String> {
    val entityOccurrences = canonicalSourceOccurrences(sourceClause) { it.entity }
        .distinctBy { it.anchor.label to it.range }
    if (entityOccurrences.isEmpty()) return emptyList()
    val predicativeRelations = predicativeAttributeSourceRelations(sourceClause)
    val sourceProtectedFragments = sourceClause.protectedPromptFragments()
    val translatedProtectedFragments = translatedClause.protectedPromptFragments()
    val translatedSemanticText = translatedClause.maskProtectedPromptStructures()
    val translatedEntityOccurrences = canonicalEnglishEntityOccurrences(translatedSemanticText)
    val semanticRelationMarkerRanges = consumedSemanticRelationMarkerRanges(sourceClause)
    return buildList {
        val orderedBindings = mutableListOf<Triple<CanonicalSourceOccurrence, CanonicalSourceOccurrence, Int>>()
        canonicalSourceOccurrences(sourceClause) { it.bindingKind != null }
            .distinctBy { it.anchor.label to it.range }
            .forEach { attributeOccurrence ->
            if (semanticRelationMarkerRanges.any { marker -> marker.contains(attributeOccurrence.range) }) {
                return@forEach
            }
            val candidateEntities = when (attributeOccurrence.anchor.bindingKind) {
                CanonicalAttributeBindingKind.QUANTITY -> entityOccurrences.filter { entity ->
                    entity.range.first > attributeOccurrence.range.last
                }
                else -> entityOccurrences
            }
            if (candidateEntities.isEmpty()) {
                add("子句$clauseNumber:属性未绑定:${attributeOccurrence.anchor.label}")
                return@forEach
            }
            val nearestDistance = candidateEntities.minOf { entity ->
                sourceOccurrenceDistance(attributeOccurrence, entity)
            }
            val nearestEntities = candidateEntities.filter { entity ->
                sourceOccurrenceDistance(attributeOccurrence, entity) == nearestDistance
            }
            if (nearestEntities.size != 1) {
                add("子句$clauseNumber:属性歧义:${attributeOccurrence.anchor.label}")
                return@forEach
            }
            val entityOccurrence = nearestEntities.single()
            val predicative = predicativeRelations.any { relation ->
                relation.entity.range == entityOccurrence.range &&
                    relation.attribute.range == attributeOccurrence.range
            }
            val mixedProtectedIndex = sourceProtectedFragments.indexOfFirst { fragment ->
                fragment.value.containsHanScript() &&
                    fragment.range.contains(attributeOccurrence.range) &&
                    fragment.range.contains(entityOccurrence.range)
            }
            val targetAttributeIndex = if (predicative) {
                englishPredicativeAttributeBindingIndex(
                    translatedClause = translatedClause,
                    attribute = attributeOccurrence.anchor,
                    entity = entityOccurrence.anchor
                )
            } else {
                val protectedTarget = translatedProtectedFragments.getOrNull(mixedProtectedIndex)
                if (protectedTarget != null) {
                    val protectedSource = sourceProtectedFragments[mixedProtectedIndex]
                    val sourceOrdinal = entityOccurrences
                        .filter { occurrence ->
                            protectedSource.range.contains(occurrence.range) &&
                                occurrence.anchor.label == entityOccurrence.anchor.label
                        }
                        .sortedBy { it.range.first }
                        .indexOf(entityOccurrence)
                    val targetTokens = protectedTarget.value.englishPromptTokens()
                    val targetEntity = canonicalEnglishEntityOccurrences(protectedTarget.value)
                        .filter { occurrence ->
                            occurrence.anchor.label == entityOccurrence.anchor.label
                        }
                        .sortedBy { it.range.first }
                        .getOrNull(sourceOrdinal)
                    targetEntity?.let { occurrence ->
                        englishConceptBindingIndex(
                            translatedClause = protectedTarget.value,
                            first = attributeOccurrence.anchor,
                            targetEntity = occurrence,
                            attributePrecedesEntity =
                                attributeOccurrence.range.first <= entityOccurrence.range.first
                        )?.let { tokenIndex ->
                            protectedTarget.range.first + targetTokens[tokenIndex].range.first
                        }
                    }
                } else {
                    val sourceOrdinal = entityOccurrences
                        .filter { it.anchor.label == entityOccurrence.anchor.label }
                        .sortedBy { it.range.first }
                        .indexOf(entityOccurrence)
                    val targetEntity = translatedEntityOccurrences
                        .filter { it.anchor.label == entityOccurrence.anchor.label }
                        .sortedBy { it.range.first }
                        .getOrNull(sourceOrdinal)
                    targetEntity?.let { occurrence ->
                        englishConceptBindingIndex(
                            translatedClause = translatedSemanticText,
                            first = attributeOccurrence.anchor,
                            targetEntity = occurrence,
                            attributePrecedesEntity =
                                attributeOccurrence.range.first <= entityOccurrence.range.first
                        )
                    }
                }
            }
            if (targetAttributeIndex == null) {
                add(
                    "子句$clauseNumber:属性:" +
                        "${attributeOccurrence.anchor.label}-${entityOccurrence.anchor.label}"
                )
            } else if (!predicative &&
                attributeOccurrence.anchor.bindingKind != CanonicalAttributeBindingKind.DIRECTION
            ) {
                orderedBindings += Triple(
                    attributeOccurrence,
                    entityOccurrence,
                    targetAttributeIndex
                )
            }
        }
        orderedBindings.groupBy { it.second.range }.values.forEach { entityBindings ->
            val sourceOrdered = entityBindings.sortedBy { it.first.range.first }
            val translatedIndexes = sourceOrdered.map { it.third }
            if (translatedIndexes.zipWithNext().any { (first, second) -> first >= second }) {
                val entity = sourceOrdered.first().second.anchor.label
                add("子句$clauseNumber:多属性顺序:$entity")
            }
        }
    }.distinct()
}

internal data class CanonicalSourceOccurrence(
    val anchor: CanonicalTranslationAnchor,
    val range: IntRange
)

internal fun canonicalSourceOccurrences(
    source: String,
    include: (CanonicalTranslationAnchor) -> Boolean
): List<CanonicalSourceOccurrence> {
    val semanticAsciiSource = source.maskAsciiOnlyProtectedPromptStructures()
    return CANONICAL_TRANSLATION_ANCHORS
        .filter(include)
        .flatMap { anchor ->
            val occupied = BooleanArray(source.length)
            buildList {
                anchor.sources.sortedByDescending(String::length).forEach { token ->
                    Regex(Regex.escape(token)).findAll(source).forEach { match ->
                        if (match.range.none { occupied[it] }) {
                            match.range.forEach { occupied[it] = true }
                            add(CanonicalSourceOccurrence(anchor, match.range))
                        }
                    }
                }
                anchor.targets.sortedByDescending(String::length).forEach { target ->
                    Regex(
                        "(?<![A-Za-z0-9_.])${Regex.escape(target.trim())}(?![A-Za-z0-9_.])",
                        RegexOption.IGNORE_CASE
                    ).findAll(semanticAsciiSource).forEach { match ->
                        if (match.range.none { occupied[it] }) {
                            match.range.forEach { occupied[it] = true }
                            add(CanonicalSourceOccurrence(anchor, match.range))
                        }
                    }
                }
            }
        }.sortedBy { it.range.first }
}

internal fun sourceOccurrenceDistance(
    first: CanonicalSourceOccurrence,
    second: CanonicalSourceOccurrence
): Int = when {
    first.range.last < second.range.first -> second.range.first - first.range.last - 1
    second.range.last < first.range.first -> first.range.first - second.range.last - 1
    else -> 0
}

internal fun IntRange.contains(other: IntRange): Boolean = first <= other.first && last >= other.last

private fun englishConceptsNear(
    translatedClause: String,
    first: CanonicalTranslationAnchor,
    second: CanonicalTranslationAnchor,
    attributePrecedesEntity: Boolean,
    maskProtectedStructures: Boolean = true
): Boolean {
    val semanticText = if (maskProtectedStructures) {
        translatedClause.maskProtectedPromptStructures()
    } else {
        translatedClause
    }
    val tokens = ENGLISH_PROMPT_TOKEN.findAll(
        semanticText.lowercase(Locale.ROOT)
    )
        .map { it.value }
        .toList()
    val firstIndexes = tokens.indices.filter { index ->
        first.targets.any { target ->
            ENGLISH_PROMPT_TOKEN.findAll(target.lowercase(Locale.ROOT)).any { it.value == tokens[index] }
        }
    }
    val secondIndexes = tokens.indices.filter { index ->
        second.targets.any { target ->
            ENGLISH_PROMPT_TOKEN.findAll(target.lowercase(Locale.ROOT)).any { it.value == tokens[index] }
        }
    }
    return firstIndexes.any { firstIndex ->
        secondIndexes.any { secondIndex ->
            when (first.bindingKind) {
                CanonicalAttributeBindingKind.ADJACENT ->
                    if (attributePrecedesEntity) {
                        firstIndex < secondIndex &&
                            tokens.subList(firstIndex + 1, secondIndex)
                                .all(CANONICAL_ATTRIBUTE_TARGET_WORDS::contains)
                    } else {
                        secondIndex < firstIndex &&
                            tokens.subList(secondIndex + 1, firstIndex)
                                .all(CANONICAL_ATTRIBUTE_TARGET_WORDS::contains)
                    }
                CanonicalAttributeBindingKind.QUANTITY ->
                    firstIndex < secondIndex &&
                        secondIndex - firstIndex <= MAX_CONTROLLED_ENTITY_PHRASE_TOKENS &&
                        tokens.subList(firstIndex + 1, secondIndex)
                            .all(CANONICAL_ATTRIBUTE_TARGET_WORDS::contains) &&
                        quantityMatchesEntityNumber(first, tokens[secondIndex])
                CanonicalAttributeBindingKind.DIRECTION ->
                    kotlin.math.abs(firstIndex - secondIndex) <= 4 &&
                        tokens.subList(
                            minOf(firstIndex, secondIndex) + 1,
                            maxOf(firstIndex, secondIndex)
                        ).all(CANONICAL_DIRECTION_LINK_WORDS::contains)
                null -> false
            }
        }
    }
}

private fun englishConceptBindingIndex(
    translatedClause: String,
    first: CanonicalTranslationAnchor,
    targetEntity: CanonicalEnglishEntityOccurrence,
    attributePrecedesEntity: Boolean
): Int? {
    val tokens = translatedClause.englishPromptTokens()
    val entityIndex = tokens.indexOfFirst { token ->
        token.range == targetEntity.range && token.matchesSingleWordTarget(targetEntity.anchor)
    }
    if (entityIndex < 0) return null
    val candidates = tokens.indices.filter { firstIndex ->
        if (!tokens[firstIndex].matchesSingleWordTarget(first)) return@filter false
        when (first.bindingKind) {
            CanonicalAttributeBindingKind.ADJACENT ->
                if (attributePrecedesEntity) {
                    firstIndex < entityIndex &&
                        tokens.subList(firstIndex + 1, entityIndex)
                            .all { it.value in CANONICAL_ATTRIBUTE_TARGET_WORDS }
                } else {
                    entityIndex < firstIndex &&
                        tokens.subList(entityIndex + 1, firstIndex)
                            .all { it.value in CANONICAL_ATTRIBUTE_TARGET_WORDS }
                }
            CanonicalAttributeBindingKind.QUANTITY ->
                firstIndex < entityIndex &&
                    entityIndex - firstIndex <= MAX_CONTROLLED_ENTITY_PHRASE_TOKENS &&
                    tokens.subList(firstIndex + 1, entityIndex)
                        .all { it.value in CANONICAL_ATTRIBUTE_TARGET_WORDS } &&
                    quantityMatchesEntityNumber(first, tokens[entityIndex].value)
            CanonicalAttributeBindingKind.DIRECTION ->
                kotlin.math.abs(firstIndex - entityIndex) <= 4 &&
                    tokens.subList(
                        minOf(firstIndex, entityIndex) + 1,
                        maxOf(firstIndex, entityIndex)
                    ).all { it.value in CANONICAL_DIRECTION_LINK_WORDS }
            null -> false
        }
    }
    val minimumDistance = candidates.minOfOrNull { kotlin.math.abs(it - entityIndex) } ?: return null
    return candidates.singleOrNull { kotlin.math.abs(it - entityIndex) == minimumDistance }
}

private fun quantityMatchesEntityNumber(
    quantity: CanonicalTranslationAnchor,
    entityWord: String
): Boolean = if (quantity.label == "一个") {
    entityWord in CANONICAL_SINGULAR_ENTITY_TARGET_WORDS
} else {
    entityWord in CANONICAL_PLURAL_ENTITY_TARGET_WORDS
}

private fun englishPredicativeAttributeBindingIndex(
    translatedClause: String,
    attribute: CanonicalTranslationAnchor,
    entity: CanonicalTranslationAnchor
): Int? {
    val tokens = translatedClause.maskProtectedPromptStructures().englishPromptTokens()
    val attributeIndexes = tokens.indices.filter { tokens[it].matchesSingleWordTarget(attribute) }
    val entityIndexes = tokens.indices.filter { tokens[it].matchesSingleWordTarget(entity) }
    val candidates = attributeIndexes.filter { attributeIndex ->
        entityIndexes.any { entityIndex ->
            attributeIndex + 1 == entityIndex ||
                (entityIndex + 2 == attributeIndex &&
                    tokens[entityIndex + 1].value in setOf("is", "are"))
        }
    }
    return candidates.singleOrNull()
}

internal enum class CanonicalSourceRelationKind {
    ADJACENCY,
    POSSESSION,
    COPULA,
    CONJUNCTION
}

internal data class CanonicalSourceBinaryRelation(
    val before: CanonicalTranslationAnchor,
    val after: CanonicalTranslationAnchor,
    val kind: CanonicalSourceRelationKind,
    val markerRanges: List<IntRange>
)

internal data class CanonicalEnglishEntityOccurrence(
    val anchor: CanonicalTranslationAnchor,
    val range: IntRange
)

internal data class CanonicalPredicativeAttributeRelation(
    val entity: CanonicalSourceOccurrence,
    val attribute: CanonicalSourceOccurrence,
    val markerRange: IntRange
)

internal fun sourceEntityOccurrencesBetween(
    source: String,
    startInclusive: Int,
    endExclusive: Int
): List<CanonicalSourceOccurrence> {
    if (startInclusive >= endExclusive) return emptyList()
    return canonicalSourceOccurrences(source) { it.entity }
        .filter { occurrence ->
            occurrence.range.first >= startInclusive && occurrence.range.last < endExclusive
        }
        .distinctBy { it.anchor.label to it.range }
        .sortedBy { it.range.first }
}

internal fun uniqueSourceEntityBetween(
    source: String,
    startInclusive: Int,
    endExclusive: Int
): CanonicalTranslationAnchor? = sourceEntityOccurrencesBetween(
    source = source,
    startInclusive = startInclusive,
    endExclusive = endExclusive
).singleOrNull()?.anchor

internal fun adjacencySourceRelations(source: String): List<CanonicalSourceBinaryRelation> =
    buildList {
        ADJACENCY_EXISTENCE_RELATION.findAll(source).forEach { match ->
            val adjacency = match.groups[1] ?: return@forEach
            val operator = match.groups[2] ?: return@forEach
            val reference = uniqueSourceEntityBetween(source, 0, adjacency.range.first)
                ?: return@forEach
            val subject = uniqueSourceEntityBetween(source, operator.range.last + 1, source.length)
                ?: return@forEach
            add(
                CanonicalSourceBinaryRelation(
                    before = reference,
                    after = subject,
                    kind = CanonicalSourceRelationKind.ADJACENCY,
                    markerRanges = listOf(adjacency.range, operator.range)
                )
            )
        }
        ADJACENCY_PLACEMENT_RELATION.findAll(source).forEach { match ->
            val connector = match.groups[1] ?: return@forEach
            val adjacency = match.groups[2] ?: return@forEach
            val subject = uniqueSourceEntityBetween(source, 0, connector.range.first)
                ?: return@forEach
            val reference = uniqueSourceEntityBetween(
                source,
                connector.range.last + 1,
                adjacency.range.first
            ) ?: return@forEach
            add(
                CanonicalSourceBinaryRelation(
                    before = subject,
                    after = reference,
                    kind = CanonicalSourceRelationKind.ADJACENCY,
                    markerRanges = listOf(connector.range, adjacency.range)
                )
            )
        }
    }

private fun String.hasSpatialExistencePrefix(index: Int): Boolean {
    val prefix = substring(0, index)
    return SPATIAL_EXISTENCE_PREFIXES.any(prefix::endsWith)
}

internal fun possessionSourceRelations(source: String): List<CanonicalSourceBinaryRelation> =
    GENERIC_HAS_RELATION.findAll(source).mapNotNull { match ->
        if (source.hasSpatialExistencePrefix(match.range.first)) return@mapNotNull null
        val owner = uniqueSourceEntityBetween(source, 0, match.range.first)
            ?: return@mapNotNull null
        val possessed = uniqueSourceEntityBetween(source, match.range.last + 1, source.length)
            ?: return@mapNotNull null
        CanonicalSourceBinaryRelation(
            before = owner,
            after = possessed,
            kind = CanonicalSourceRelationKind.POSSESSION,
            markerRanges = listOf(match.range)
        )
    }.toList()

internal fun copulaSourceRelations(source: String): List<CanonicalSourceBinaryRelation> =
    GENERIC_COPULA_RELATION.findAll(source).mapNotNull { match ->
        if (source.hasSpatialExistencePrefix(match.range.first)) return@mapNotNull null
        val subject = uniqueSourceEntityBetween(source, 0, match.range.first)
            ?: return@mapNotNull null
        val complement = uniqueSourceEntityBetween(source, match.range.last + 1, source.length)
            ?: return@mapNotNull null
        CanonicalSourceBinaryRelation(
            before = subject,
            after = complement,
            kind = CanonicalSourceRelationKind.COPULA,
            markerRanges = listOf(match.range)
        )
    }.toList()

internal fun conjunctionSourceRelations(source: String): List<CanonicalSourceBinaryRelation> {
    val entities = canonicalSourceOccurrences(source) { it.entity }
        .distinctBy { it.anchor.label to it.range }
        .sortedBy { it.range.first }
    return CHINESE_CONJUNCTION.findAll(source).mapNotNull { match ->
        val before = entities.lastOrNull { it.range.last < match.range.first }?.anchor
            ?: return@mapNotNull null
        val after = entities.firstOrNull { it.range.first > match.range.last }?.anchor
            ?: return@mapNotNull null
        CanonicalSourceBinaryRelation(
            before = before,
            after = after,
            kind = CanonicalSourceRelationKind.CONJUNCTION,
            markerRanges = listOf(match.range)
        )
    }.toList()
}

internal fun predicativeAttributeSourceRelations(
    source: String
): List<CanonicalPredicativeAttributeRelation> = GENERIC_COPULA_RELATION.findAll(source)
    .mapNotNull { match ->
        if (source.hasSpatialExistencePrefix(match.range.first)) return@mapNotNull null
        val entity = sourceEntityOccurrencesBetween(source, 0, match.range.first).singleOrNull()
            ?: return@mapNotNull null
        val attributes = canonicalSourceOccurrences(source) {
            it.bindingKind == CanonicalAttributeBindingKind.ADJACENT
        }.filter { occurrence -> occurrence.range.first > match.range.last }
        val attribute = attributes.singleOrNull() ?: return@mapNotNull null
        if (sourceEntityOccurrencesBetween(source, match.range.last + 1, source.length).isNotEmpty()) {
            return@mapNotNull null
        }
        if (source.substring(entity.range.last + 1, match.range.first).any { !it.isWhitespace() } ||
            source.substring(match.range.last + 1, attribute.range.first).any { !it.isWhitespace() }
        ) return@mapNotNull null
        CanonicalPredicativeAttributeRelation(entity, attribute, match.range)
    }
    .toList()

private fun consumedAttributiveParticleRanges(source: String): List<IntRange> =
    CHINESE_ATTRIBUTIVE_PARTICLE.findAll(source).mapNotNull { match ->
        val attribute = canonicalSourceOccurrences(source) {
            it.bindingKind == CanonicalAttributeBindingKind.ADJACENT
        }.filter { occurrence ->
            occurrence.range.last < match.range.first &&
                source.substring(occurrence.range.last + 1, match.range.first).all(Char::isWhitespace)
        }.maxByOrNull { it.range.last } ?: return@mapNotNull null
        val entity = sourceEntityOccurrencesBetween(source, match.range.last + 1, source.length)
            .filter { occurrence ->
                source.substring(match.range.last + 1, occurrence.range.first).all(Char::isWhitespace)
            }
            .minByOrNull { it.range.first } ?: return@mapNotNull null
        match.range.takeIf { attribute.range.last < it.first && entity.range.first > it.last }
    }.toList()

private val CANONICAL_SINGULAR_ENTITY_TARGET_WORDS = setOf(
    "cat", "feline", "dog", "canine", "cup", "mug", "table", "desk", "tabletop",
    "apple", "person", "human", "character", "text", "word", "lettering", "writing",
    "typography", "fruit", "watermark"
)

private val CANONICAL_PLURAL_ENTITY_TARGET_WORDS = setOf(
    "cats", "felines", "dogs", "canines", "cups", "mugs", "tables", "desks", "tabletops",
    "apples", "people", "humans", "characters", "texts", "words", "fruits", "watermarks"
)

private val CANONICAL_DIRECTION_LINK_WORDS =
    CANONICAL_ATTRIBUTE_TARGET_WORDS + setOf("to", "the", "of", "on", "its")

private val ADJACENCY_EXISTENCE_RELATION = Regex("(旁边)(有|是)")

private val ADJACENCY_PLACEMENT_RELATION = Regex(
    "(坐落在|放置于|位于|放在|在)[\\s\\S]{1,80}?(旁边)"
)

private val GENERIC_HAS_RELATION = Regex("有")

private val GENERIC_COPULA_RELATION = Regex("是|为")

private val CHINESE_CONJUNCTION = Regex("以及|和|与|及")

private val CHINESE_ATTRIBUTIVE_PARTICLE = Regex("的")

private val SPATIAL_EXISTENCE_PREFIXES = setOf(
    "左侧", "左边", "右侧", "右边", "上方", "上面", "下方", "下面", "旁边",
    "上", "下", "里", "内", "外"
)
