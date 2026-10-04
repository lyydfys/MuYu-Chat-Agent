package com.muyuchat.mca

import java.util.Locale

internal data class PromptClauseSpan(
    val value: String,
    val range: IntRange
)

internal class LocalImagePromptClauseLayoutV4 private constructor(
    private val source: String,
    val clauses: List<String>,
    private val clauseRanges: List<IntRange>
) {
    init {
        require(clauses.size == clauseRanges.size)
    }

    fun render(translatedClauses: List<String>): String {
        require(translatedClauses.size == clauses.size) {
            "Translated prompt clause count does not match the source layout."
        }
        if (clauseRanges.isEmpty()) return canonicalizePromptLayoutSliceV4(source)
        return buildString(source.length) {
            var cursor = 0
            clauseRanges.forEachIndexed { index, range ->
                append(canonicalizePromptLayoutSliceV4(source.substring(cursor, range.first)))
                append(translatedClauses[index])
                cursor = range.last + 1
            }
            append(canonicalizePromptLayoutSliceV4(source.substring(cursor)))
        }
    }

    companion object {
        fun parse(source: String): LocalImagePromptClauseLayoutV4 {
            val spans = source.promptClauseSpans()
            return LocalImagePromptClauseLayoutV4(
                source = source,
                clauses = spans.map(PromptClauseSpan::value),
                clauseRanges = spans.map(PromptClauseSpan::range)
            )
        }
    }
}

internal fun localImagePromptClauseLayoutV4(source: String): LocalImagePromptClauseLayoutV4 =
    LocalImagePromptClauseLayoutV4.parse(source)

internal fun canonicalizePromptLayoutSliceV4(value: String): String = buildString(value.length) {
    value.forEach { character ->
        append(
            when (character) {
                '，' -> ','
                '；' -> ';'
                '。' -> '.'
                '！' -> '!'
                '？' -> '?'
                else -> character
            }
        )
    }
}

internal enum class PromptSeparatorKind {
    COMMA,
    SEMICOLON,
    PERIOD,
    EXCLAMATION,
    QUESTION,
    LINE_FEED,
    CARRIAGE_RETURN
}

internal data class EnglishPromptToken(
    val value: String,
    val range: IntRange
)

internal fun String.promptClauses(): List<String> = promptClauseSpans().map(PromptClauseSpan::value)

internal fun String.promptClauseSpans(): List<PromptClauseSpan> {
    val spans = mutableListOf<PromptClauseSpan>()
    var clauseStart = 0
    val delimiterStack = mutableListOf<Char>()
    fun appendClause(endExclusive: Int) {
        var contentStart = clauseStart
        var contentEnd = endExclusive
        while (contentStart < contentEnd && this[contentStart].isWhitespace()) contentStart += 1
        while (contentEnd > contentStart && this[contentEnd - 1].isWhitespace()) contentEnd -= 1
        if (contentStart < contentEnd) {
            spans += PromptClauseSpan(
                value = substring(contentStart, contentEnd),
                range = contentStart until contentEnd
            )
        }
    }
    forEachIndexed { index, character ->
        val expectedClose = PROMPT_STRUCTURE_OPEN_TO_CLOSE[character]
        when {
            isEscapedAt(index) -> Unit
            expectedClose != null -> delimiterStack += expectedClose
            delimiterStack.lastOrNull() == character -> delimiterStack.removeAt(delimiterStack.lastIndex)
            delimiterStack.isEmpty() && character.isPromptClauseSeparatorAt(this, index) -> {
                appendClause(index)
                clauseStart = index + 1
            }
        }
    }
    appendClause(length)
    return spans
}

internal fun Char.isPromptClauseSeparatorAt(source: String, index: Int): Boolean {
    if (this !in PROMPT_CLAUSE_SEPARATOR_CHARACTERS) return false
    if (source.isEscapedAt(index)) return false
    if (this == '.' && index > 0 && index + 1 < source.length &&
        source[index - 1].isDigit() && source[index + 1].isDigit()
    ) return false
    return true
}

internal fun Char.promptSeparatorKindOrNull(): PromptSeparatorKind? = when (this) {
    ',', '，' -> PromptSeparatorKind.COMMA
    ';', '；' -> PromptSeparatorKind.SEMICOLON
    '.', '。' -> PromptSeparatorKind.PERIOD
    '!', '！' -> PromptSeparatorKind.EXCLAMATION
    '?', '？' -> PromptSeparatorKind.QUESTION
    '\n' -> PromptSeparatorKind.LINE_FEED
    '\r' -> PromptSeparatorKind.CARRIAGE_RETURN
    else -> null
}

/**
 * Keep the punctuation program stable while still allowing the translator to replace Chinese
 * punctuation with its ASCII counterpart. Decimal points and escaped punctuation are literals,
 * not clause boundaries. The all-depth signature also covers intentionally unclosed attention
 * syntax that Local Dream accepts as literal prompt text.
 */
internal fun String.promptSeparatorSignature(topLevelOnly: Boolean): List<PromptSeparatorKind> {
    val signature = mutableListOf<PromptSeparatorKind>()
    val delimiterStack = mutableListOf<Char>()
    forEachIndexed { index, character ->
        if (isEscapedAt(index)) return@forEachIndexed
        val expectedClose = PROMPT_STRUCTURE_OPEN_TO_CLOSE[character]
        when {
            expectedClose != null -> delimiterStack += expectedClose
            delimiterStack.lastOrNull() == character ->
                delimiterStack.removeAt(delimiterStack.lastIndex)
            !topLevelOnly || delimiterStack.isEmpty() -> {
                if (character.isPromptClauseSeparatorAt(this, index)) {
                    character.promptSeparatorKindOrNull()?.let(signature::add)
                }
            }
        }
    }
    return signature
}

internal fun String.promptStructureEscapeSignature(): List<String> = buildList {
    this@promptStructureEscapeSignature.forEachIndexed { index, character ->
        if (character in PROMPT_STRUCTURE_CHARACTERS) {
            val canonical = character.canonicalPromptStructureCharacter()
            add(if (isEscapedAt(index)) "escaped:$canonical" else "syntax:$canonical")
        }
    }
}

internal fun Char.canonicalPromptStructureCharacter(): Char = when (this) {
    '（' -> '('
    '）' -> ')'
    '［' -> '['
    '］' -> ']'
    '｛' -> '{'
    '｝' -> '}'
    '＜' -> '<'
    '＞' -> '>'
    else -> this
}

internal fun String.isEscapedAt(index: Int): Boolean {
    var backslashCount = 0
    var cursor = index - 1
    while (cursor >= 0 && this[cursor] == '\\') {
        backslashCount += 1
        cursor -= 1
    }
    return backslashCount % 2 == 1
}

internal fun String.englishPromptTokens(): List<EnglishPromptToken> =
    ENGLISH_PROMPT_TOKEN.findAll(lowercase(Locale.ROOT))
        .map { match -> EnglishPromptToken(match.value, match.range) }
        .toList()

internal fun EnglishPromptToken.matchesSingleWordTarget(
    anchor: CanonicalTranslationAnchor
): Boolean = anchor.targets.any { target ->
    target.trim().equals(value, ignoreCase = true) &&
        ENGLISH_PROMPT_TOKEN.findAll(target).count() == 1
}

internal fun List<EnglishPromptToken>.precedingQuantityIndexes(
    entityIndex: Int,
    accepted: Set<String>
): List<Int> {
    val normalizedAccepted = accepted.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
    return (maxOf(0, entityIndex - MAX_CONTROLLED_ENTITY_PHRASE_TOKENS) until entityIndex)
        .filter { quantityIndex ->
        this[quantityIndex].value in normalizedAccepted &&
            subList(quantityIndex + 1, entityIndex).all { token ->
                token.value in CANONICAL_ATTRIBUTE_TARGET_WORDS
            }
        }
}

internal data class PromptProtectedFragment(
    val value: String,
    val range: IntRange
)

internal fun String.asciiLiteralFragmentsOutsideProtectedStructures(): List<PromptProtectedFragment> {
    val protected = protectedPromptFragments()
    return ASCII_LITERAL_RUN.findAll(this).mapNotNull { match ->
        var start = match.range.first
        var end = match.range.last
        while (start <= end && this[start].isWhitespace()) start += 1
        while (end >= start && this[end].isWhitespace()) end -= 1
        if (start > end) return@mapNotNull null
        val range = start..end
        if (protected.any { it.range.overlaps(range) }) return@mapNotNull null
        PromptProtectedFragment(substring(start, end + 1), range)
    }.toList()
}

internal fun String.protectedPromptFragments(): List<PromptProtectedFragment> {
    val balanced = mutableListOf<PromptProtectedFragment>()
    val delimiterStack = mutableListOf<Char>()
    var outerStart = -1
    forEachIndexed { index, character ->
        val expectedClose = PROMPT_STRUCTURE_OPEN_TO_CLOSE[character]
        when {
            isEscapedAt(index) -> Unit
            expectedClose != null -> {
                if (delimiterStack.isEmpty()) outerStart = index
                delimiterStack += expectedClose
            }
            delimiterStack.lastOrNull() == character -> {
                delimiterStack.removeAt(delimiterStack.lastIndex)
                if (delimiterStack.isEmpty() && outerStart >= 0) {
                    balanced += PromptProtectedFragment(
                        value = substring(outerStart, index + 1),
                        range = outerStart..index
                    )
                    outerStart = -1
                }
            }
        }
    }
    val bareDirectives = BARE_PROMPT_DIRECTIVE.findAll(this)
        .filter { match -> balanced.none { fragment -> match.range.overlaps(fragment.range) } }
        .map { match -> PromptProtectedFragment(match.value, match.range) }
        .toList()
    return (balanced + bareDirectives).sortedBy { it.range.first }
}

internal data class OpenPromptStructure(
    val start: Int,
    val expectedClose: Char
)

/** Every balanced scope, including nested attention structures, in deterministic pre-order. */
internal fun String.balancedPromptStructureFragments(): List<PromptProtectedFragment> {
    val stack = mutableListOf<OpenPromptStructure>()
    val fragments = mutableListOf<PromptProtectedFragment>()
    forEachIndexed { index, character ->
        if (isEscapedAt(index)) return@forEachIndexed
        val expectedClose = PROMPT_STRUCTURE_OPEN_TO_CLOSE[character]
        when {
            expectedClose != null -> stack += OpenPromptStructure(index, expectedClose)
            stack.lastOrNull()?.expectedClose == character -> {
                val open = stack.removeAt(stack.lastIndex)
                fragments += PromptProtectedFragment(
                    value = substring(open.start, index + 1),
                    range = open.start..index
                )
            }
        }
    }
    return fragments.sortedWith(
        compareBy<PromptProtectedFragment> { it.range.first }
            .thenByDescending { it.range.last }
    )
}

internal fun PromptProtectedFragment.innerPromptStructureValueOrNull(): String? {
    if (value.length < 2) return null
    val expectedClose = PROMPT_STRUCTURE_OPEN_TO_CLOSE[value.first()] ?: return null
    if (value.last() != expectedClose) return null
    return value.substring(1, value.lastIndex)
}

internal fun String.hasBalancedPromptStructures(): Boolean {
    val delimiterStack = mutableListOf<Char>()
    forEachIndexed { index, character ->
        if (isEscapedAt(index)) return@forEachIndexed
        val expectedClose = PROMPT_STRUCTURE_OPEN_TO_CLOSE[character]
        when {
            expectedClose != null -> delimiterStack += expectedClose
            character in PROMPT_STRUCTURE_OPEN_TO_CLOSE.values -> {
                if (delimiterStack.lastOrNull() != character) return false
                delimiterStack.removeAt(delimiterStack.lastIndex)
            }
        }
    }
    return delimiterStack.isEmpty()
}

internal fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last

internal fun String.isInsideProtectedPromptStructure(range: IntRange): Boolean =
    protectedPromptFragments().any { it.range.overlaps(range) }

internal fun String.maskProtectedPromptStructures(): String {
    val masked = toCharArray()
    protectedPromptFragments().forEach { fragment ->
        fragment.range.forEach { index -> if (index in masked.indices) masked[index] = ' ' }
    }
    return String(masked)
}

internal fun String.maskAsciiOnlyProtectedPromptStructures(): String {
    val masked = toCharArray()
    protectedPromptFragments().filterNot { it.value.containsHanScript() }.forEach { fragment ->
        fragment.range.forEach { index -> if (index in masked.indices) masked[index] = ' ' }
    }
    return String(masked)
}

internal fun String.maskLiteralProtectedFragmentsDeclaredBy(source: String): String {
    val sourceFragments = source.protectedPromptFragments()
    val targetFragments = protectedPromptFragments()
    if (sourceFragments.size != targetFragments.size) return this
    val masked = toCharArray()
    sourceFragments.zip(targetFragments).forEach { (sourceFragment, targetFragment) ->
        if (!sourceFragment.value.containsHanScript()) {
            targetFragment.range.forEach { index -> if (index in masked.indices) masked[index] = ' ' }
        }
    }
    return String(masked)
}

/**
 * Relation operators inside attention syntax must not satisfy an outer spatial relation, but an
 * entity wrapped by attention is still the same relation endpoint. Positive negation is also an
 * executable operator when it is intentionally weighted. Restore only those canonical tokens at
 * their original offsets after masking the protected fragment.
 */
internal fun String.maskProtectedPromptStructuresPreservingEntities(
    source: String? = null
): String {
    val masked = maskProtectedPromptStructures().toCharArray()
    val lower = lowercase(Locale.ROOT)
    val sourceFragments = source?.protectedPromptFragments()
    protectedPromptFragments().forEachIndexed { fragmentIndex, fragment ->
        if (sourceFragments != null &&
            sourceFragments.getOrNull(fragmentIndex)?.value?.containsHanScript() != true
        ) return@forEachIndexed
        CANONICAL_TRANSLATION_ANCHORS.asSequence()
            .filter { it.entity || it.negativeOperator }
            .flatMap { anchor -> anchor.targets.asSequence() }
            .forEach { target ->
                val pattern = Regex(
                    "(?<![a-z0-9_.])${Regex.escape(target.lowercase(Locale.ROOT))}" +
                        "(?![a-z0-9_.])",
                    RegexOption.IGNORE_CASE
                )
                pattern.findAll(lower, fragment.range.first).forEach { match ->
                    if (!fragment.range.contains(match.range)) return@forEach
                    match.range.forEach { index -> masked[index] = this[index] }
                }
            }
        val sourceFragment = sourceFragments?.getOrNull(fragmentIndex)
        if (sourceFragment != null &&
            conjunctionSourceRelations(sourceFragment.value).isNotEmpty()
        ) {
            ENGLISH_CONJUNCTION_RELATION.findAll(lower, fragment.range.first).forEach { match ->
                if (fragment.range.contains(match.range)) {
                    match.range.forEach { index -> masked[index] = this[index] }
                }
            }
        }
    }
    return String(masked)
}

internal fun String.maskProtectedPromptStructuresPreservingSourceEntities(): String {
    val masked = maskProtectedPromptStructures().toCharArray()
    val entities = canonicalSourceOccurrences(this) { anchor -> anchor.entity }
        .distinctBy { occurrence -> occurrence.anchor.label to occurrence.range }
    // The target-side outer scope restores conjunctions so repeated entity bindings remain
    // countable. Mirror only those markers here; spatial operators stay inside the nested scope
    // and are validated when that nested scope is visited below.
    val relationMarkers =
        conjunctionSourceRelations(this@maskProtectedPromptStructuresPreservingSourceEntities)
            .flatMap { relation -> relation.markerRanges }
            .distinct()
    protectedPromptFragments().forEach { fragment ->
        entities.filter { occurrence -> fragment.range.contains(occurrence.range) }
            .forEach { occurrence ->
                occurrence.range.forEach { index -> masked[index] = this[index] }
            }
        relationMarkers.filter { range ->
            range.first >= fragment.range.first && range.last <= fragment.range.last
        }.forEach { range ->
            range.forEach { index -> masked[index] = this[index] }
        }
    }
    return String(masked)
}
