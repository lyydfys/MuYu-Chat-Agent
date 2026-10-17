package com.muyuchat.mca

import java.util.Locale
import com.muyuchat.core.engine.isSafeAsciiDiffusionPromptCodePoint

internal fun canonicalEnglishEntityOccurrences(
    translated: String
): List<CanonicalEnglishEntityOccurrence> = translated.englishPromptTokens()
    .mapNotNull { token ->
        val anchors = CANONICAL_TRANSLATION_ANCHORS.filter { anchor ->
            anchor.entity && token.matchesSingleWordTarget(anchor)
        }
        anchors.singleOrNull()?.let { anchor ->
            CanonicalEnglishEntityOccurrence(anchor, token.range)
        }
    }

private fun translatedRelationBindingMatch(
    translated: String,
    relation: Regex,
    expectedBefore: CanonicalTranslationAnchor,
    expectedAfter: CanonicalTranslationAnchor,
    symmetric: Boolean,
    excludedMatches: Set<IntRange>
): IntRange? {
    val relationText = translated
    val entities = canonicalEnglishEntityOccurrences(relationText)
    return relation.findAll(relationText).firstOrNull { match ->
        if (match.range in excludedMatches) return@firstOrNull false
        val before = entities.lastOrNull { it.range.last < match.range.first }?.anchor
        val after = entities.firstOrNull { it.range.first > match.range.last }?.anchor
        (before == expectedBefore && after == expectedAfter) ||
            (symmetric && before == expectedAfter && after == expectedBefore)
    }?.range
}

private fun consumeSpatialAuxiliaryCopulaBefore(
    translated: String,
    operatorRange: IntRange,
    consumedTargetRelationMatches: MutableSet<IntRange>
) {
    val precedingEntity = canonicalEnglishEntityOccurrences(translated)
        .lastOrNull { entity -> entity.range.last < operatorRange.first }
        ?: return
    val gapStart = precedingEntity.range.last + 1
    if (gapStart >= operatorRange.first) return
    val gap = translated.substring(gapStart, operatorRange.first)
    val copulas = ENGLISH_COPULA_RELATION.findAll(gap).toList()
    if (copulas.size != 1) return
    val copula = copulas.single()
    if (gap.substring(0, copula.range.first).any { character -> !character.isWhitespace() }) return
    val bridge = gap.substring(copula.range.last + 1).trim().lowercase(Locale.ROOT)
    if (bridge.isNotEmpty() && bridge !in setOf("placed", "sitting", "located")) return
    consumedTargetRelationMatches +=
        (gapStart + copula.range.first)..(gapStart + copula.range.last)
}

private fun consumeInvertedSpatialAuxiliaryCopula(
    translated: String,
    relationMatchRange: IntRange,
    operatorRange: IntRange,
    expectedReference: CanonicalTranslationAnchor,
    expectedSubject: CanonicalTranslationAnchor,
    consumedTargetRelationMatches: MutableSet<IntRange>
) {
    val entities = canonicalEnglishEntityOccurrences(translated)
        .filter { entity ->
            entity.range.first >= relationMatchRange.first &&
                entity.range.last <= relationMatchRange.last
        }
    val reference = entities.firstOrNull { entity ->
        entity.range.first > operatorRange.last && entity.anchor == expectedReference
    } ?: return
    val subject = entities.firstOrNull { entity ->
        entity.range.first > reference.range.last && entity.anchor == expectedSubject
    } ?: return
    val gapStart = reference.range.last + 1
    if (gapStart >= subject.range.first) return
    val gap = translated.substring(gapStart, subject.range.first)
    val copulas = ENGLISH_COPULA_RELATION.findAll(gap).toList()
    if (copulas.size != 1) return
    val copula = copulas.single()
    val prefix = gap.substring(0, copula.range.first).trim().lowercase(Locale.ROOT)
    if (prefix.isNotEmpty() && prefix != "there") return
    val suffix = gap.substring(copula.range.last + 1)
    val suffixTokens = suffix.englishPromptTokens()
    val hasUnsupportedPrefixToken = suffixTokens.any { token ->
        token.value !in CONTROLLED_INVERTED_SPATIAL_SUBJECT_PREFIX_WORDS
    }
    if (hasUnsupportedPrefixToken) return
    val suffixResidue = suffix.toCharArray()
    suffixTokens.forEach { token ->
        token.range.forEach { index -> suffixResidue[index] = ' ' }
    }
    if (suffixResidue.any { character -> !character.isWhitespace() }) return
    consumedTargetRelationMatches +=
        (gapStart + copula.range.first)..(gapStart + copula.range.last)
}

private fun consumeSpatialTargetRelation(
    translated: String,
    subjectFirstMatch: MatchResult?,
    invertedMatch: MatchResult?,
    expectedReference: CanonicalTranslationAnchor,
    expectedSubject: CanonicalTranslationAnchor,
    consumedTargetRelationMatches: MutableSet<IntRange>
): Boolean {
    val subjectFirstOperator = subjectFirstMatch?.groups?.get(1)?.range
    val invertedOperator = invertedMatch?.groups?.get(1)?.range
    val operatorRange = subjectFirstOperator ?: invertedOperator ?: return false
    consumedTargetRelationMatches += operatorRange
    if (subjectFirstOperator != null) {
        consumeSpatialAuxiliaryCopulaBefore(
            translated = translated,
            operatorRange = subjectFirstOperator,
            consumedTargetRelationMatches = consumedTargetRelationMatches
        )
    } else if (invertedMatch != null) {
        consumeInvertedSpatialAuxiliaryCopula(
            translated = translated,
            relationMatchRange = invertedMatch.range,
            operatorRange = operatorRange,
            expectedReference = expectedReference,
            expectedSubject = expectedSubject,
            consumedTargetRelationMatches = consumedTargetRelationMatches
        )
    }
    return true
}

internal fun consumedSemanticRelationMarkerRanges(source: String): List<IntRange> = buildList {
    DIRECTIONAL_EXISTENCE_RELATION.findAll(source).forEach { match ->
        val direction = match.groups[1] ?: return@forEach
        val operator = match.groups[2] ?: return@forEach
        if (uniqueSourceEntityBetween(source, 0, match.range.first) != null &&
            uniqueSourceEntityBetween(source, match.range.last + 1, source.length) != null
        ) {
            add(direction.range)
            add(operator.range)
        }
    }
    PLACED_ON_RELATION.findAll(source).forEach { match ->
        val connector = match.groups[1] ?: return@forEach
        val locative = match.groups[2] ?: return@forEach
        if (uniqueSourceEntityBetween(source, 0, connector.range.first) != null &&
            uniqueSourceEntityBetween(
                source,
                connector.range.last + 1,
                locative.range.first
            ) != null
        ) {
            add(connector.range)
            add(locative.range)
        }
    }
    SUBJECT_RELATIVE_DIRECTION.findAll(source).forEach { match ->
        val connector = match.groups[1] ?: return@forEach
        val direction = match.groups[2] ?: return@forEach
        if (uniqueSourceEntityBetween(source, 0, connector.range.first) != null &&
            uniqueSourceEntityBetween(
                source,
                connector.range.last + 1,
                direction.range.first
            ) != null
        ) {
            add(connector.range)
            add(direction.range)
        }
    }
    SIMPLE_LOCATIVE_RELATION.findAll(source).forEach { match ->
        val connector = match.groups[1] ?: return@forEach
        val locative = match.groups[2] ?: return@forEach
        if (uniqueSourceEntityBetween(source, 0, connector.range.first) != null &&
            uniqueSourceEntityBetween(
                source,
                connector.range.last + 1,
                locative.range.first
            ) != null
        ) {
            add(connector.range)
            add(locative.range)
        }
    }
    adjacencySourceRelations(source).flatMapTo(this) { it.markerRanges }
    possessionSourceRelations(source).flatMapTo(this) { it.markerRanges }
    copulaSourceRelations(source).flatMapTo(this) { it.markerRanges }
    predicativeAttributeSourceRelations(source).mapTo(this) { it.markerRange }
}

private fun englishDirectionTargets(sourceDirection: String): Set<String> = when (sourceDirection) {
    "左侧", "左边" -> setOf("left")
    "右侧", "右边" -> setOf("right")
    "上方", "上面" -> setOf("above", "over", "on top")
    else -> setOf("below", "under", "beneath")
}

private fun englishDirectionRelationPattern(targets: Set<String>): String =
    targets.joinToString(prefix = "(?:", postfix = ")", separator = "|") { target ->
        when (target) {
            "left", "right" -> "(?:to\\s+the\\s+)?$target\\s+of"
            else -> target.split(' ').joinToString("\\s+") { Regex.escape(it) }
        }
    }

internal fun missingCanonicalRelationTuples(
    sourceClause: String,
    translatedClause: String,
    clauseNumber: Int,
    rejectUnexpectedRelations: Boolean
): List<String> = buildList {
    fun validateScope(sourceScope: String, translatedScope: String) {
        val scopedSource = sourceScope.maskProtectedPromptStructuresPreservingSourceEntities()
        val scopedTarget = translatedScope
            .maskAsciiLiteralFragmentsDeclaredBy(sourceScope)
            .maskProtectedPromptStructuresPreservingEntities(sourceScope)
        addAll(
            missingCanonicalRelationTuplesInScope(
                sourceClause = scopedSource,
                translatedClause = scopedTarget,
                clauseNumber = clauseNumber,
                rejectUnexpectedRelations = rejectUnexpectedRelations
            )
        )
    }

    validateScope(sourceClause, translatedClause)
    val sourceScopes = sourceClause.balancedPromptStructureFragments()
    val translatedScopes = translatedClause.balancedPromptStructureFragments()
    if (sourceScopes.size == translatedScopes.size) {
        sourceScopes.zip(translatedScopes).forEach { (sourceScope, translatedScope) ->
            if (!sourceScope.value.containsHanScript()) return@forEach
            val sourceInner = sourceScope.innerPromptStructureValueOrNull() ?: return@forEach
            val translatedInner = translatedScope.innerPromptStructureValueOrNull()
                ?: return@forEach
            validateScope(sourceInner, translatedInner)
        }
    }
}

private fun missingCanonicalRelationTuplesInScope(
    sourceClause: String,
    translatedClause: String,
    clauseNumber: Int,
    rejectUnexpectedRelations: Boolean
): List<String> = buildList {
    val relationText = translatedClause.maskProtectedPromptStructuresPreservingEntities(sourceClause)
    val consumedTargetRelationMatches = mutableSetOf<IntRange>()
    val binaryRelations = adjacencySourceRelations(sourceClause) +
        possessionSourceRelations(sourceClause) +
        copulaSourceRelations(sourceClause) +
        conjunctionSourceRelations(sourceClause)
    binaryRelations.forEach { relation ->
        val (targetRelation, symmetric, label) = when (relation.kind) {
            CanonicalSourceRelationKind.ADJACENCY ->
                Triple(ENGLISH_ADJACENCY_RELATION, true, "adjacent")
            CanonicalSourceRelationKind.POSSESSION ->
                Triple(ENGLISH_POSSESSION_RELATION, false, "has")
            CanonicalSourceRelationKind.COPULA ->
                Triple(ENGLISH_COPULA_RELATION, false, "is")
            CanonicalSourceRelationKind.CONJUNCTION ->
                Triple(ENGLISH_CONJUNCTION_RELATION, true, "and")
        }
        val targetMatch = translatedRelationBindingMatch(
            translated = relationText,
            relation = targetRelation,
            expectedBefore = relation.before,
            expectedAfter = relation.after,
            symmetric = symmetric,
            excludedMatches = consumedTargetRelationMatches
        )
        if (targetMatch == null) {
            add(
                "子句$clauseNumber:关系:${relation.before.label}-$label-${relation.after.label}"
            )
        } else {
            consumedTargetRelationMatches += targetMatch
            if (relation.kind == CanonicalSourceRelationKind.ADJACENCY) {
                consumeSpatialAuxiliaryCopulaBefore(
                    translated = relationText,
                    operatorRange = targetMatch,
                    consumedTargetRelationMatches = consumedTargetRelationMatches
                )
            }
        }
    }

    DIRECTIONAL_EXISTENCE_RELATION.findAll(sourceClause).forEach { match ->
        val direction = match.groupValues[1]
        val markerStart = match.range.first
        val markerEnd = match.range.last + 1
        val reference = sourceEntityBefore(sourceClause, markerStart) ?: return@forEach
        val subject = sourceEntityAfter(sourceClause, markerEnd) ?: return@forEach
        val englishDirections = englishDirectionTargets(direction)
        val subjectPattern = subject.englishTargetPattern()
        val referencePattern = reference.englishTargetPattern()
        val directionPattern = englishDirectionRelationPattern(englishDirections)
        val subjectFirst = Regex(
            "$subjectPattern.{0,120}?($directionPattern).{0,120}$referencePattern",
            RegexOption.IGNORE_CASE
        )
        val invertedGrammar = Regex(
            "($directionPattern).{0,120}$referencePattern.{0,120}$subjectPattern",
            RegexOption.IGNORE_CASE
        )
        val subjectFirstMatch = subjectFirst.find(relationText)
        val invertedMatch = invertedGrammar.find(relationText)
        if (!consumeSpatialTargetRelation(
                translated = relationText,
                subjectFirstMatch = subjectFirstMatch,
                invertedMatch = invertedMatch,
                expectedReference = reference,
                expectedSubject = subject,
                consumedTargetRelationMatches = consumedTargetRelationMatches
            )
        ) {
            add(
                "子句$clauseNumber:关系:${subject.label}-" +
                    "${englishDirections.sorted().joinToString("/")}-${reference.label}"
            )
        }
    }

    PLACED_ON_RELATION.findAll(sourceClause).forEach { match ->
        val connector = match.groups[1] ?: return@forEach
        val locative = match.groups[2] ?: return@forEach
        val subject = uniqueSourceEntityBetween(sourceClause, 0, connector.range.first)
            ?: return@forEach
        val reference = uniqueSourceEntityBetween(
            sourceClause,
            connector.range.last + 1,
            locative.range.first
        ) ?: return@forEach
        val subjectPattern = subject.englishTargetPattern()
        val referencePattern = reference.englishTargetPattern()
        val subjectFirst = Regex(
            "$subjectPattern.{0,120}?((?<![a-z])on(?![a-z])).{0,120}$referencePattern",
            RegexOption.IGNORE_CASE
        )
        val invertedGrammar = Regex(
            "((?<![a-z])on(?![a-z])).{0,120}$referencePattern.{0,120}$subjectPattern",
            RegexOption.IGNORE_CASE
        )
        val subjectFirstMatch = subjectFirst.find(relationText)
        val invertedMatch = invertedGrammar.find(relationText)
        if (!consumeSpatialTargetRelation(
                translated = relationText,
                subjectFirstMatch = subjectFirstMatch,
                invertedMatch = invertedMatch,
                expectedReference = reference,
                expectedSubject = subject,
                consumedTargetRelationMatches = consumedTargetRelationMatches
            )
        ) {
            add("子句$clauseNumber:关系:${subject.label}-on-${reference.label}")
        }
    }

    SUBJECT_RELATIVE_DIRECTION.findAll(sourceClause).forEach { match ->
        val connector = match.groups[1] ?: return@forEach
        val directionGroup = match.groups[2] ?: return@forEach
        val subject = uniqueSourceEntityBetween(sourceClause, 0, connector.range.first)
            ?: return@forEach
        val reference = uniqueSourceEntityBetween(
            sourceClause,
            connector.range.last + 1,
            directionGroup.range.first
        ) ?: return@forEach
        val englishDirections = englishDirectionTargets(directionGroup.value)
        val directionPattern = englishDirectionRelationPattern(englishDirections)
        val subjectPattern = subject.englishTargetPattern()
        val referencePattern = reference.englishTargetPattern()
        val subjectFirst = Regex(
            "$subjectPattern.{0,120}?($directionPattern).{0,120}$referencePattern",
            RegexOption.IGNORE_CASE
        )
        val invertedGrammar = Regex(
            "($directionPattern).{0,120}$referencePattern.{0,120}$subjectPattern",
            RegexOption.IGNORE_CASE
        )
        val subjectFirstMatch = subjectFirst.find(relationText)
        val invertedMatch = invertedGrammar.find(relationText)
        if (!consumeSpatialTargetRelation(
                translated = relationText,
                subjectFirstMatch = subjectFirstMatch,
                invertedMatch = invertedMatch,
                expectedReference = reference,
                expectedSubject = subject,
                consumedTargetRelationMatches = consumedTargetRelationMatches
            )
        ) {
            add(
                "子句$clauseNumber:关系:${subject.label}-" +
                    "${englishDirections.sorted().joinToString("/")}-${reference.label}"
            )
        }
    }

    SIMPLE_LOCATIVE_RELATION.findAll(sourceClause).forEach { match ->
        val connector = match.groups[1] ?: return@forEach
        val directionGroup = match.groups[2] ?: return@forEach
        val subject = uniqueSourceEntityBetween(sourceClause, 0, connector.range.first)
            ?: return@forEach
        val reference = uniqueSourceEntityBetween(
            sourceClause,
            connector.range.last + 1,
            directionGroup.range.first
        ) ?: return@forEach
        val targetWords = when (directionGroup.value) {
            "上" -> setOf("on")
            "下" -> setOf("under", "below")
            "里", "内" -> setOf("in", "inside")
            else -> setOf("outside")
        }
        val relationPattern = targetWords.joinToString("|") { Regex.escape(it) }
        val subjectPattern = subject.englishTargetPattern()
        val referencePattern = reference.englishTargetPattern()
        val subjectFirst = Regex(
            "$subjectPattern.{0,120}?((?<![a-z])(?:$relationPattern)(?![a-z])).{0,120}$referencePattern",
            RegexOption.IGNORE_CASE
        )
        val invertedGrammar = Regex(
            "((?<![a-z])(?:$relationPattern)(?![a-z])).{0,120}$referencePattern.{0,120}$subjectPattern",
            RegexOption.IGNORE_CASE
        )
        val subjectFirstMatch = subjectFirst.find(relationText)
        val invertedMatch = invertedGrammar.find(relationText)
        if (!consumeSpatialTargetRelation(
                translated = relationText,
                subjectFirstMatch = subjectFirstMatch,
                invertedMatch = invertedMatch,
                expectedReference = reference,
                expectedSubject = subject,
                consumedTargetRelationMatches = consumedTargetRelationMatches
            )
        ) {
            add("子句$clauseNumber:关系:${subject.label}-${directionGroup.value}-${reference.label}")
        }
    }

    val relationEntities = canonicalEnglishEntityOccurrences(relationText)
    if (rejectUnexpectedRelations) {
        CanonicalSourceRelationKind.entries.forEach { kind ->
            val (targetRelation, label) = when (kind) {
                CanonicalSourceRelationKind.ADJACENCY -> ENGLISH_ADJACENCY_RELATION to "adjacent"
                CanonicalSourceRelationKind.POSSESSION -> ENGLISH_POSSESSION_RELATION to "has"
                CanonicalSourceRelationKind.COPULA -> ENGLISH_COPULA_RELATION to "is"
                CanonicalSourceRelationKind.CONJUNCTION -> ENGLISH_CONJUNCTION_RELATION to "and"
            }
            val leftovers = targetRelation.findAll(relationText).count { match ->
                match.range !in consumedTargetRelationMatches &&
                    relationEntities.any { entity -> entity.range.last < match.range.first } &&
                    relationEntities.any { entity -> entity.range.first > match.range.last }
            }
            if (leftovers > 0) {
                add("子句$clauseNumber:新增关系:$label x$leftovers")
            }
        }
        val spatialLeftovers = ENGLISH_SIMPLE_LOCATIVE_RELATION.findAll(relationText).count { match ->
            consumedTargetRelationMatches.none { consumed ->
                consumed.first <= match.range.first && consumed.last >= match.range.last
            } && relationEntities.size >= 2
        }
        if (spatialLeftovers > 0) {
            add("子句$clauseNumber:新增关系:spatial x$spatialLeftovers")
        }
    }
}

internal fun controlledRelationTargetWords(source: String): Set<String> = buildSet {
    if (PLACED_ON_RELATION.containsMatchIn(source) ||
        SIMPLE_LOCATIVE_RELATION.findAll(source).any { it.groupValues[2] == "上" }
    ) addAll(setOf("on", "is", "are", "placed", "sitting", "located"))
    SIMPLE_LOCATIVE_RELATION.findAll(source).forEach { match ->
        when (match.groupValues[2]) {
            "下" -> addAll(setOf("under", "below"))
            "里", "内" -> addAll(setOf("in", "inside"))
            "外" -> add("outside")
        }
    }
    if (CANONICAL_TRANSLATION_ANCHORS.any { anchor ->
            anchor.label in setOf("左侧", "右侧") && anchor.sources.any(source::contains)
        }
    ) add("on")
    if (adjacencySourceRelations(source).isNotEmpty()) {
        addAll(setOf("next", "to", "beside", "near", "is", "are"))
    }
    if (possessionSourceRelations(source).isNotEmpty()) {
        addAll(setOf("with", "has", "have"))
    }
    if (copulaSourceRelations(source).isNotEmpty()) {
        addAll(setOf("is", "are"))
    }
    if (predicativeAttributeSourceRelations(source).isNotEmpty()) {
        addAll(setOf("is", "are"))
    }
    if (conjunctionSourceRelations(source).isNotEmpty()) {
        add("and")
    }
    if (DIRECTIONAL_EXISTENCE_RELATION.containsMatchIn(source) ||
        SUBJECT_RELATIVE_DIRECTION.containsMatchIn(source) ||
        SIMPLE_LOCATIVE_RELATION.containsMatchIn(source)
    ) {
        addAll(setOf("is", "are", "to", "of", "there", "its", "side"))
    }
}

private fun sourceEntityBefore(source: String, endExclusive: Int): CanonicalTranslationAnchor? =
    CANONICAL_TRANSLATION_ANCHORS.asSequence()
        .filter { it.entity }
        .flatMap { anchor ->
            anchor.sources.asSequence().mapNotNull { token ->
                val index = source.lastIndexOf(token, startIndex = (endExclusive - 1).coerceAtLeast(0))
                anchor.takeIf { index >= 0 && index + token.length <= endExclusive }?.let { index to it }
            }
        }
        .maxByOrNull { it.first }
        ?.second

private fun sourceEntityAfter(source: String, startIndex: Int): CanonicalTranslationAnchor? =
    CANONICAL_TRANSLATION_ANCHORS.asSequence()
        .filter { it.entity }
        .flatMap { anchor ->
            anchor.sources.asSequence().mapNotNull { token ->
                source.indexOf(token, startIndex = startIndex)
                    .takeIf { it >= 0 }
                    ?.let { it to anchor }
            }
        }
        .minByOrNull { it.first }
        ?.second

private fun CanonicalTranslationAnchor.englishTargetPattern(): String = targets
    .sortedByDescending(String::length)
    .joinToString(prefix = "(?:", postfix = ")", separator = "|") { target ->
        "(?<![a-z0-9_])${Regex.escape(target.trim())}(?![a-z0-9_])"
    }

internal fun unexpectedCanonicalTranslationAnchors(
    source: String,
    translated: String,
    negativeConditioning: Boolean = false
): List<String> {
    if (!source.containsHanScript()) return emptyList()
    val semanticSource = source.maskAsciiOnlyProtectedPromptStructures()
    val lower = translated.maskLiteralProtectedFragmentsDeclaredBy(source)
        .lowercase(Locale.ROOT)
    return CANONICAL_TRANSLATION_ANCHORS.mapNotNull { anchor ->
        if (negativeConditioning && anchor.negativeOperator) return@mapNotNull null
        if (anchor.sources.any(semanticSource::contains) ||
            anchor.targets.any { semanticSource.lowercase(Locale.ROOT).containsCanonicalTarget(it) }
        ) return@mapNotNull null
        "新增:${anchor.label}".takeIf {
            anchor.targets.any { lower.containsCanonicalTarget(it) }
        }
    }
}

internal fun String.containsCanonicalTarget(target: String): Boolean {
    val normalized = target.trim().lowercase(Locale.ROOT)
    if (normalized.isEmpty()) return false
    return Regex(
        "(?<![a-z0-9_.])${Regex.escape(normalized)}(?![a-z0-9_.])",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(this)
}

internal data class CanonicalTranslationAnchor(
    val label: String,
    val sources: Set<String>,
    val targets: Set<String>,
    val negativeOperator: Boolean = false,
    val entity: Boolean = false,
    val bindingKind: CanonicalAttributeBindingKind? = null
)

internal enum class CanonicalAttributeBindingKind {
    ADJACENT,
    QUANTITY,
    DIRECTION
}

internal val CANONICAL_NEGATION_ANCHOR = CanonicalTranslationAnchor(
    "否定",
    setOf("不要", "避免", "不能有", "不包含"),
    setOf("no", "not", "without", "avoid", "exclude", "do not", "don't"),
    negativeOperator = true
)

internal val CONTROLLED_APOSTROPHE_CONTRACTION = Regex(
    "(?<![a-z0-9_])don't(?:\\s+include)?(?![a-z0-9_])",
    RegexOption.IGNORE_CASE
)

private val PROMPT_CLAUSE_SEPARATOR = Regex("[，,；;。.!！？?\\n\\r]+")
internal val PROMPT_CLAUSE_SEPARATOR_CHARACTERS =
    setOf('，', ',', '；', ';', '。', '.', '!', '！', '？', '?', '\n', '\r')
internal val PROMPT_STRUCTURE_OPEN_TO_CLOSE: Map<Char, Char> = mapOf(
    '(' to ')',
    '[' to ']',
    '{' to '}',
    '<' to '>',
    '（' to '）',
    '［' to '］',
    '｛' to '｝',
    '＜' to '＞'
)
internal val PROMPT_STRUCTURE_CHARACTERS: Set<Char> =
    (PROMPT_STRUCTURE_OPEN_TO_CLOSE.keys + PROMPT_STRUCTURE_OPEN_TO_CLOSE.values).toSet()
internal val ENGLISH_PROMPT_TOKEN = Regex("[a-z][a-z0-9_+.-]*")
internal val ASCII_LITERAL_RUN = Regex("[\\x20-\\x7e]+")
internal val BARE_PROMPT_DIRECTIVE = Regex(
    "(?<![A-Za-z0-9_<>])(?:embedding|lora):[A-Za-z0-9_+./-]{1,120}" +
        "(?::[+-]?\\d+(?:\\.\\d+)?)?(?![A-Za-z0-9_])"
)
internal val CONTROLLED_POSITION_PUNCTUATION =
    setOf('|', '=', '@', '#', '%', '&', '*', '/', '\\', '"', '+', '_', '-')
internal val SUPPORTED_CHINESE_DIFFUSION_PROMPT_PUNCTUATION = setOf(
    '，'.code,
    '；'.code,
    '。'.code,
    '！'.code,
    '？'.code,
    '（'.code,
    '）'.code,
    '［'.code,
    '］'.code,
    '｛'.code,
    '｝'.code,
    '＜'.code,
    '＞'.code,
    '：'.code
)

internal fun String.isSafeAsciiDiffusionPrompt(): Boolean =
    codePoints().allMatch { codePoint -> isSafeAsciiDiffusionPromptCodePoint(codePoint) }

internal val CONTROLLED_ENGLISH_GRAMMAR_TOKENS = setOf(
    "a", "an", "the"
)
internal const val MAX_CONTROLLED_ENTITY_PHRASE_TOKENS = 12
internal val CANONICAL_ATTRIBUTE_TARGET_WORDS = setOf(
    "red", "blue", "green", "yellow", "black", "white",
    "ceramic", "porcelain", "wood", "wooden", "extra", "additional", "surplus"
)
private val CONTROLLED_INVERTED_SPATIAL_SUBJECT_PREFIX_WORDS =
    CONTROLLED_ENGLISH_GRAMMAR_TOKENS + CANONICAL_ATTRIBUTE_TARGET_WORDS +
        setOf("one", "single", "two", "three", "four")

private val DIRECTIONAL_EXISTENCE_RELATION =
    Regex("(左侧|左边|右侧|右边|上方|上面|下方|下面)(有|是)")
// These relations are evaluated after promptClauseSpans() has already split only on top-level
// separators. Their operands may therefore contain protected diffusion syntax such as
// `(blue dog:1.1)` or escaped punctuation, which must not be mistaken for another clause.
private const val CLAUSE_LOCAL_RELATION_BODY = "[\\s\\S]{0,80}?"
private val PLACED_ON_RELATION =
    Regex("(放在)$CLAUSE_LOCAL_RELATION_BODY(上面|上方|上)")
private val SUBJECT_RELATIVE_DIRECTION = Regex(
    "(在|位于)$CLAUSE_LOCAL_RELATION_BODY(左侧|左边|右侧|右边|上方|上面|下方|下面)"
)
private val SIMPLE_LOCATIVE_RELATION = Regex(
    "(在|位于)[\\s\\S]{1,80}?(上|下|里|内|外)(?:$|[，,；;。.!！？?\\n\\r])"
)

private val ENGLISH_ADJACENCY_RELATION =
    Regex("(?<![a-z0-9_])(?:next\\s+to|beside|near)(?![a-z0-9_])", RegexOption.IGNORE_CASE)
private val ENGLISH_POSSESSION_RELATION =
    Regex("(?<![a-z0-9_])(?:has|have|with)(?![a-z0-9_])", RegexOption.IGNORE_CASE)
private val ENGLISH_COPULA_RELATION =
    Regex("(?<![a-z0-9_])(?:is|are)(?![a-z0-9_])", RegexOption.IGNORE_CASE)
internal val ENGLISH_CONJUNCTION_RELATION =
    Regex("(?<![a-z0-9_])and(?![a-z0-9_])", RegexOption.IGNORE_CASE)
private val ENGLISH_SIMPLE_LOCATIVE_RELATION =
    Regex("(?<![a-z0-9_])(?:inside|outside|on|in)(?![a-z0-9_])", RegexOption.IGNORE_CASE)

internal val CANONICAL_TRANSLATION_ANCHORS = listOf(
    CanonicalTranslationAnchor("红色", setOf("红色", "红的", "红色的", "红"), setOf("red"), bindingKind = CanonicalAttributeBindingKind.ADJACENT),
    CanonicalTranslationAnchor("蓝色", setOf("蓝色", "蓝的", "蓝色的", "蓝"), setOf("blue"), bindingKind = CanonicalAttributeBindingKind.ADJACENT),
    CanonicalTranslationAnchor("绿色", setOf("绿色", "绿的", "绿色的", "绿"), setOf("green"), bindingKind = CanonicalAttributeBindingKind.ADJACENT),
    CanonicalTranslationAnchor("黄色", setOf("黄色", "黄的", "黄色的", "黄"), setOf("yellow"), bindingKind = CanonicalAttributeBindingKind.ADJACENT),
    CanonicalTranslationAnchor("黑色", setOf("黑色", "黑的", "黑色的", "黑"), setOf("black"), bindingKind = CanonicalAttributeBindingKind.ADJACENT),
    CanonicalTranslationAnchor("白色", setOf("白色", "白的", "白色的", "白"), setOf("white"), bindingKind = CanonicalAttributeBindingKind.ADJACENT),
    CanonicalTranslationAnchor("左侧", setOf("左侧", "左边"), setOf("left"), bindingKind = CanonicalAttributeBindingKind.DIRECTION),
    CanonicalTranslationAnchor("右侧", setOf("右侧", "右边"), setOf("right"), bindingKind = CanonicalAttributeBindingKind.DIRECTION),
    CanonicalTranslationAnchor("上方", setOf("上方", "上面"), setOf("above", "over", "on top"), bindingKind = CanonicalAttributeBindingKind.DIRECTION),
    CanonicalTranslationAnchor("下方", setOf("下方", "下面"), setOf("below", "under", "beneath"), bindingKind = CanonicalAttributeBindingKind.DIRECTION),
    CanonicalTranslationAnchor("两个", setOf("两个", "两只", "两颗", "两张", "二个"), setOf("two"), bindingKind = CanonicalAttributeBindingKind.QUANTITY),
    CanonicalTranslationAnchor("三个", setOf("三个", "三只", "三颗", "三张"), setOf("three"), bindingKind = CanonicalAttributeBindingKind.QUANTITY),
    CanonicalTranslationAnchor("四个", setOf("四个", "四只", "四颗", "四张"), setOf("four"), bindingKind = CanonicalAttributeBindingKind.QUANTITY),
    CanonicalTranslationAnchor("一个", setOf("一个", "一只", "一颗", "一张"), setOf("one", "single"), bindingKind = CanonicalAttributeBindingKind.QUANTITY),
    CanonicalTranslationAnchor(
        "猫",
        setOf("猫", "猫咪"),
        setOf("cat", "cats", "feline", "felines"),
        entity = true
    ),
    CanonicalTranslationAnchor(
        "狗",
        setOf("狗", "狗狗"),
        setOf("dog", "dogs", "canine", "canines"),
        entity = true
    ),
    CanonicalTranslationAnchor(
        "杯子",
        setOf("杯子", "水杯"),
        setOf("cup", "cups", "mug", "mugs"),
        entity = true
    ),
    CanonicalTranslationAnchor(
        "桌子",
        setOf("桌子", "桌面", "木桌"),
        setOf("table", "tables", "desk", "desks", "tabletop", "tabletops"),
        entity = true
    ),
    CanonicalTranslationAnchor("苹果", setOf("苹果"), setOf("apple", "apples"), entity = true),
    CanonicalTranslationAnchor(
        "人物",
        setOf("人物", "人像", "人类"),
        setOf("person", "people", "human", "humans", "character", "characters"),
        entity = true
    ),
    CanonicalTranslationAnchor(
        "文字",
        setOf("文字", "文本", "字样"),
        setOf("text", "texts", "word", "words", "lettering", "writing", "typography"),
        entity = true
    ),
    CanonicalTranslationAnchor("水果", setOf("水果"), setOf("fruit", "fruits"), entity = true),
    CanonicalTranslationAnchor("水印", setOf("水印"), setOf("watermark", "watermarks"), entity = true),
    CanonicalTranslationAnchor("模糊", setOf("模糊"), setOf("blur", "blurry", "blurred")),
    CanonicalTranslationAnchor("低质量", setOf("低质量", "低品质"), setOf("low quality", "poor quality")),
    CanonicalTranslationAnchor("陶瓷", setOf("陶瓷"), setOf("ceramic", "porcelain"), bindingKind = CanonicalAttributeBindingKind.ADJACENT),
    CanonicalTranslationAnchor("木质", setOf("木质", "木制", "木桌"), setOf("wood", "wooden"), bindingKind = CanonicalAttributeBindingKind.ADJACENT),
    CanonicalTranslationAnchor(
        "多余",
        setOf("多余"),
        setOf("extra", "additional", "surplus"),
        bindingKind = CanonicalAttributeBindingKind.ADJACENT
    ),
    CANONICAL_NEGATION_ANCHOR
)
