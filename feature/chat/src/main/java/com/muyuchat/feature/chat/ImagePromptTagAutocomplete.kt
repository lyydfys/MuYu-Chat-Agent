package com.muyuchat.feature.chat

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.ArrayDeque
import java.util.Collections
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

internal class ImagePromptTagRecord(
    val tag: String,
    val category: Int,
    val postCount: Long,
    aliases: List<String> = emptyList()
) {
    private val aliasSnapshot = immutableTagList(aliases)

    val aliases: List<String>
        get() = aliasSnapshot

    init {
        require(isSafeCanonicalImagePromptTag(tag)) {
            "Canonical tags must be bounded ASCII English prompt values."
        }
        require(category in MIN_TAG_CATEGORY..MAX_TAG_CATEGORY) { "Tag category is out of range." }
        require(postCount >= 0L) { "Tag popularity cannot be negative." }
        require(aliasSnapshot.size <= MAX_TAG_ALIASES) { "Tag alias count exceeds the limit." }
        require(aliasSnapshot.all(::isSafeTagValue)) { "Tag aliases must be bounded printable values." }
    }

    override fun equals(other: Any?): Boolean =
        other is ImagePromptTagRecord &&
            tag == other.tag &&
            category == other.category &&
            postCount == other.postCount &&
            aliasSnapshot == other.aliasSnapshot

    override fun hashCode(): Int {
        var result = tag.hashCode()
        result = 31 * result + category
        result = 31 * result + postCount.hashCode()
        result = 31 * result + aliasSnapshot.hashCode()
        return result
    }
}

internal data class ImagePromptTagTranslationRow(
    val tag: String,
    val translation: String
) {
    init {
        require(isSafeCanonicalImagePromptTag(tag)) { "Translation tag key is invalid." }
        require(isSafeTranslationValue(translation)) { "Translation text is invalid." }
    }
}

/** Strict, size-limited CSV parsing. Callers remain responsible for line iteration and storage. */
internal object ImagePromptTagCsv {
    const val MAX_UTF8_LINE_BYTES: Int = 16_384
    const val MAX_TRANSLATION_COLUMNS: Int = 8

    fun parseTagLine(line: String): ImagePromptTagRecord? {
        if (line.length > MAX_UTF8_LINE_BYTES || hasUnpairedSurrogate(line)) return null
        return parseTagUtf8Line(line.toByteArray(Charsets.UTF_8))
    }

    fun parseTagUtf8Line(bytes: ByteArray): ImagePromptTagRecord? {
        val line = decodeUtf8Line(bytes) ?: return null
        val cells = parseBoundedCsvCells(line, expectedColumns = 3..4) ?: return null
        return try {
            val tag = cells[0].trim().removePrefix("\uFEFF")
            if (!isSafeCanonicalImagePromptTag(tag)) return null
            val category = cells[1].trim().toIntOrNull() ?: return null
            val postCount = cells[2].trim().toLongOrNull()?.takeIf { it >= 0L } ?: return null
            val aliasCells = cells.getOrNull(3)
                ?.split(',', limit = MAX_TAG_ALIASES + 1)
                .orEmpty()
            if (aliasCells.size > MAX_TAG_ALIASES) return null
            val aliasesByKey = LinkedHashMap<String, String>(aliasCells.size)
            for (rawAlias in aliasCells) {
                val alias = rawAlias.trim()
                if (alias.isEmpty()) continue
                if (!isSafeTagValue(alias)) return null
                val key = normalizeImagePromptTag(alias)
                if (key.isEmpty()) return null
                aliasesByKey.putIfAbsent(key, alias)
            }
            ImagePromptTagRecord(
                tag = tag,
                category = category,
                postCount = postCount,
                aliases = aliasesByKey.values.toList()
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parseTranslationLine(line: String): ImagePromptTagTranslationRow? {
        if (line.length > MAX_UTF8_LINE_BYTES || hasUnpairedSurrogate(line)) return null
        return parseTranslationUtf8Line(line.toByteArray(Charsets.UTF_8))
    }

    fun parseTranslationUtf8Line(bytes: ByteArray): ImagePromptTagTranslationRow? {
        val line = decodeUtf8Line(bytes) ?: return null
        val cells = parseBoundedCsvCells(
            line,
            expectedColumns = 2..MAX_TRANSLATION_COLUMNS
        ) ?: return null
        return try {
            val translation = cells.asSequence()
                .drop(1)
                .map(String::trim)
                .firstOrNull { value ->
                    value.isNotEmpty() && value.toDoubleOrNull() == null
                } ?: return null
            ImagePromptTagTranslationRow(
                tag = cells[0].trim().removePrefix("\uFEFF"),
                translation = translation
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeUtf8Line(bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > MAX_UTF8_LINE_BYTES) return null
        val decoded = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            return null
        }
        return decoded.takeIf {
            decoded.none { it == '\r' || it == '\n' || it == '\u0000' }
        }
    }

    private fun parseBoundedCsvCells(line: String, expectedColumns: IntRange): List<String>? {
        if (line.isEmpty()) return null
        val cells = ArrayList<String>(expectedColumns.last)
        var cursor = 0
        while (true) {
            if (cells.size >= expectedColumns.last + 1) return null
            val field = StringBuilder()
            if (cursor < line.length && line[cursor] == '"') {
                cursor++
                var closed = false
                while (cursor < line.length) {
                    val current = line[cursor]
                    if (current != '"') {
                        field.append(current)
                        cursor++
                    } else if (cursor + 1 < line.length && line[cursor + 1] == '"') {
                        field.append('"')
                        cursor += 2
                    } else {
                        closed = true
                        cursor++
                        break
                    }
                }
                if (!closed) return null
                while (cursor < line.length && (line[cursor] == ' ' || line[cursor] == '\t')) cursor++
                if (cursor < line.length && line[cursor] != ',') return null
            } else {
                while (cursor < line.length && line[cursor] != ',') {
                    if (line[cursor] == '"') return null
                    field.append(line[cursor])
                    cursor++
                }
            }
            cells += field.toString()
            if (cursor >= line.length) break
            cursor++
            if (cursor == line.length) {
                cells += ""
                break
            }
        }
        return cells.takeIf { it.size in expectedColumns }
    }
}

internal fun normalizeImagePromptTag(value: String): String {
    if (value.isEmpty()) return ""
    val lower = value.lowercase(Locale.ROOT)
    val normalized = StringBuilder(lower.length)
    var separatorPending = false
    for (char in lower) {
        if (char == '_' || char == '-' || char.isWhitespace()) {
            separatorPending = normalized.isNotEmpty()
        } else {
            if (separatorPending) normalized.append('_')
            normalized.append(char)
            separatorPending = false
        }
    }
    return normalized.toString()
}

internal enum class ImagePromptTagMatchKind {
    TAG_PREFIX,
    ALIAS_PREFIX,
    TRANSLATION_PREFIX,
    FUZZY_TAG,
    FUZZY_ALIAS,
    FUZZY_TRANSLATION
}

internal data class ImagePromptTagSuggestion(
    val replacementTag: String,
    val category: Int,
    val postCount: Long,
    val matchKind: ImagePromptTagMatchKind,
    val matchedText: String,
    val translation: String?,
    val textualInversionId: String? = null,
    val textualInversionSelected: Boolean = false,
) {
    init {
        require(!textualInversionSelected || textualInversionId != null) {
            "A selected textual-inversion suggestion must identify its artifact."
        }
    }
}

/**
 * Immutable autocomplete index. Querying uses binary prefix ranges, bounded fuzzy length buckets,
 * and a fixed-capacity top-K; only index construction sorts complete term lists.
 */
internal class ImagePromptTagAutocomplete private constructor(
    private val entries: List<IndexedTag>,
    private val tagTerms: List<SearchTerm>,
    private val aliasTerms: List<SearchTerm>,
    private val translationTerms: List<SearchTerm>,
    private val tagFuzzyTermsByLength: Array<List<SearchTerm>>,
    private val translationFuzzyTermsByLength: Array<List<SearchTerm>>
) {
    fun suggest(query: String, limit: Int = DEFAULT_RESULT_LIMIT): List<ImagePromptTagSuggestion> {
        require(limit in 1..MAX_RESULT_LIMIT) { "Suggestion limit is out of range." }
        if (query.length > MAX_TAG_TEXT_CHARS || hasUnpairedSurrogate(query)) return emptyList()
        val normalizedQuery = normalizeImagePromptTag(query)
        val normalizedTranslationQuery = normalizeImagePromptTranslation(query)
        if (normalizedQuery.isEmpty() && normalizedTranslationQuery.isEmpty()) return emptyList()

        val hits = ArrayList<RankedHit>(limit)
        if (normalizedQuery.isNotEmpty()) {
            collectPrefixHits(tagTerms, normalizedQuery, limit, hits)
            collectPrefixHits(aliasTerms, normalizedQuery, limit, hits)
        }
        if (normalizedTranslationQuery.isNotEmpty()) {
            collectPrefixHits(translationTerms, normalizedTranslationQuery, limit, hits)
        }
        if (hits.size < limit) {
            collectFuzzyHits(
                normalizedTagQuery = normalizedQuery,
                normalizedTranslationQuery = normalizedTranslationQuery,
                limit = limit,
                hits = hits
            )
        }
        hits.sortWith(::compareHits)
        return hits.map { hit ->
            val indexed = entries[hit.entryIndex]
            ImagePromptTagSuggestion(
                replacementTag = indexed.record.tag,
                category = indexed.record.category,
                postCount = indexed.record.postCount,
                matchKind = hit.kind,
                matchedText = hit.term.visibleText,
                translation = indexed.translation
            )
        }
    }

    private fun collectPrefixHits(
        terms: List<SearchTerm>,
        query: String,
        limit: Int,
        hits: MutableList<RankedHit>
    ) {
        var index = lowerBound(terms, query)
        while (index < terms.size) {
            val term = terms[index]
            if (!term.normalized.startsWith(query)) break
            offerBoundedHit(
                hits = hits,
                limit = limit,
                entryIndex = term.entryIndex,
                term = term,
                kind = term.kind,
                quality = if (term.normalized == query) {
                    MATCH_QUALITY_EXACT
                } else {
                    MATCH_QUALITY_PREFIX
                }
            )
            index++
        }
    }

    private fun collectFuzzyHits(
        normalizedTagQuery: String,
        normalizedTranslationQuery: String,
        limit: Int,
        hits: MutableList<RankedHit>
    ) {
        val tagQuery = normalizedTagQuery.takeIf(::isFuzzyQueryLengthAllowed)
        val translationQuery = normalizedTranslationQuery.takeIf(::isFuzzyQueryLengthAllowed)
        if (tagQuery == null && translationQuery == null) return

        var eligibleTermCount = 0L
        if (tagQuery != null) {
            eligibleTermCount += countFuzzyTerms(tagFuzzyTermsByLength, tagQuery.length)
        }
        if (translationQuery != null) {
            eligibleTermCount += countFuzzyTerms(
                translationFuzzyTermsByLength,
                translationQuery.length
            )
        }
        if (!isImagePromptTagFuzzyScanAllowed(eligibleTermCount)) return

        if (tagQuery != null) {
            collectFuzzyBucketHits(
                buckets = tagFuzzyTermsByLength,
                query = tagQuery,
                limit = limit,
                hits = hits
            )
        }
        if (translationQuery != null) {
            collectFuzzyBucketHits(
                buckets = translationFuzzyTermsByLength,
                query = translationQuery,
                limit = limit,
                hits = hits
            )
        }
    }

    private fun collectFuzzyBucketHits(
        buckets: Array<List<SearchTerm>>,
        query: String,
        limit: Int,
        hits: MutableList<RankedHit>
    ) {
        val queryFingerprint = imagePromptTagFuzzyFingerprint(query)
        for (length in fuzzyCandidateLengthRange(query.length)) {
            for (term in buckets[length]) {
                val quality = fuzzyMatchQuality(
                    query = query,
                    queryFingerprint = queryFingerprint,
                    term = term
                ) ?: continue
                offerBoundedHit(
                    hits = hits,
                    limit = limit,
                    entryIndex = term.entryIndex,
                    term = term,
                    kind = term.kind.toFuzzyKind(),
                    quality = quality
                )
            }
        }
    }

    private fun countFuzzyTerms(
        buckets: Array<List<SearchTerm>>,
        queryLength: Int
    ): Long {
        var count = 0L
        for (length in fuzzyCandidateLengthRange(queryLength)) {
            count += buckets[length].size.toLong()
            if (count > MAX_FUZZY_SCANNED_TERMS) break
        }
        return count
    }

    private fun isFuzzyQueryLengthAllowed(query: String): Boolean =
        query.length in MIN_FUZZY_QUERY_CHARS..MAX_FUZZY_QUERY_CHARS

    private fun fuzzyCandidateLengthRange(queryLength: Int): IntRange =
        (queryLength - MAX_FUZZY_EDITS).coerceAtLeast(1)..
            (queryLength + MAX_FUZZY_SUBSEQUENCE_SKIPS).coerceAtMost(MAX_FUZZY_TERM_CHARS)

    private fun fuzzyMatchQuality(
        query: String,
        queryFingerprint: Long,
        term: SearchTerm
    ): Int? {
        val candidate = term.normalized
        if (
            abs(candidate.length - query.length) <= MAX_FUZZY_EDITS &&
            java.lang.Long.bitCount(queryFingerprint xor term.fuzzyFingerprint) <=
            MAX_FUZZY_FINGERPRINT_DELTA &&
            isSingleImagePromptTagEdit(query, candidate)
        ) {
            return MATCH_QUALITY_SINGLE_EDIT
        }

        val skipped = candidate.length - query.length
        if (
            skipped in 1..MAX_FUZZY_SUBSEQUENCE_SKIPS &&
            (queryFingerprint and term.fuzzyFingerprint) == queryFingerprint &&
            isBoundedImagePromptTagSubsequence(query, candidate)
        ) {
            return MATCH_QUALITY_SUBSEQUENCE_BASE + skipped
        }
        return null
    }

    private fun lowerBound(terms: List<SearchTerm>, query: String): Int {
        var low = 0
        var high = terms.size
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (terms[middle].normalized < query) low = middle + 1 else high = middle
        }
        return low
    }

    private fun offerBoundedHit(
        hits: MutableList<RankedHit>,
        limit: Int,
        entryIndex: Int,
        term: SearchTerm,
        kind: ImagePromptTagMatchKind,
        quality: Int
    ) {
        val existingIndex = hits.indexOfFirst { it.entryIndex == entryIndex }
        if (existingIndex >= 0) {
            val existing = hits[existingIndex]
            if (compareCandidate(entryIndex, term, kind, quality, existing) < 0) {
                existing.entryIndex = entryIndex
                existing.term = term
                existing.kind = kind
                existing.quality = quality
            }
            return
        }
        if (hits.size < limit) {
            hits += RankedHit(entryIndex, term, kind, quality)
            return
        }
        var worstIndex = 0
        for (candidateIndex in 1 until hits.size) {
            if (compareHits(hits[candidateIndex], hits[worstIndex]) > 0) worstIndex = candidateIndex
        }
        if (compareCandidate(entryIndex, term, kind, quality, hits[worstIndex]) < 0) {
            val replaced = hits[worstIndex]
            replaced.entryIndex = entryIndex
            replaced.term = term
            replaced.kind = kind
            replaced.quality = quality
        }
    }

    private fun compareCandidate(
        entryIndex: Int,
        term: SearchTerm,
        kind: ImagePromptTagMatchKind,
        quality: Int,
        other: RankedHit
    ): Int = compareRanking(
        leftEntry = entries[entryIndex],
        leftKind = kind,
        leftQuality = quality,
        rightEntry = entries[other.entryIndex],
        rightKind = other.kind,
        rightQuality = other.quality
    )

    private fun compareHits(left: RankedHit, right: RankedHit): Int = compareRanking(
        leftEntry = entries[left.entryIndex],
        leftKind = left.kind,
        leftQuality = left.quality,
        rightEntry = entries[right.entryIndex],
        rightKind = right.kind,
        rightQuality = right.quality
    )

    private fun compareRanking(
        leftEntry: IndexedTag,
        leftKind: ImagePromptTagMatchKind,
        leftQuality: Int,
        rightEntry: IndexedTag,
        rightKind: ImagePromptTagMatchKind,
        rightQuality: Int
    ): Int {
        val quality = leftQuality.compareTo(rightQuality)
        if (quality != 0) return quality
        val popularity = rightEntry.record.postCount.compareTo(leftEntry.record.postCount)
        if (popularity != 0) return popularity
        val source = matchKindRank(leftKind).compareTo(matchKindRank(rightKind))
        if (source != 0) return source
        val normalizedName = leftEntry.normalizedTag.compareTo(rightEntry.normalizedTag)
        if (normalizedName != 0) return normalizedName
        val originalName = leftEntry.record.tag.compareTo(rightEntry.record.tag)
        if (originalName != 0) return originalName
        return leftEntry.ordinal.compareTo(rightEntry.ordinal)
    }

    companion object {
        const val DEFAULT_RESULT_LIMIT: Int = 12
        const val MAX_RESULT_LIMIT: Int = 50
        const val MAX_DICTIONARY_ENTRIES: Int = 200_000
        const val MAX_INDEX_TERMS: Int = 1_000_000
        const val MIN_FUZZY_QUERY_CHARS: Int = 3
        const val MAX_FUZZY_QUERY_CHARS: Int = 64
        const val MAX_FUZZY_TERM_CHARS: Int = 72
        const val MAX_FUZZY_EDITS: Int = 1
        const val MAX_FUZZY_SUBSEQUENCE_SKIPS: Int = 8
        const val MAX_FUZZY_SCANNED_TERMS: Long = 250_000L

        private fun newMutableFuzzyBuckets(): Array<ArrayList<SearchTerm>?> =
            arrayOfNulls(MAX_FUZZY_TERM_CHARS + 1)

        private fun addFuzzyTerm(
            buckets: Array<ArrayList<SearchTerm>?>,
            term: SearchTerm
        ) {
            val length = term.normalized.length
            if (length !in 1..MAX_FUZZY_TERM_CHARS) return
            val bucket = buckets[length] ?: ArrayList<SearchTerm>().also {
                buckets[length] = it
            }
            bucket += term
        }

        private fun freezeFuzzyBuckets(
            buckets: Array<ArrayList<SearchTerm>?>
        ): Array<List<SearchTerm>> = Array(buckets.size) { index ->
            buckets[index]?.let(Collections::unmodifiableList) ?: emptyList()
        }

        fun create(
            records: List<ImagePromptTagRecord>,
            translations: Map<String, String> = emptyMap(),
            checkCancelled: () -> Unit = {}
        ): ImagePromptTagAutocomplete {
            checkCancelled()
            require(records.size <= MAX_DICTIONARY_ENTRIES) { "Tag dictionary is too large." }
            require(translations.size <= MAX_DICTIONARY_ENTRIES) { "Translation dictionary is too large." }

            val normalizedTranslations = deterministicTranslationMap(translations, checkCancelled)
            val indexedEntries = ArrayList<IndexedTag>(records.size)
            val uniqueTags = HashSet<String>(records.size.coerceAtLeast(16))
            records.forEachIndexed { ordinal, record ->
                if (ordinal % CANCELLATION_CHECK_INTERVAL == 0) checkCancelled()
                require(isSafeCanonicalImagePromptTag(record.tag)) {
                    "Tag dictionary contains a non-canonical replacement tag."
                }
                val normalizedTag = normalizeImagePromptTag(record.tag)
                require(normalizedTag.isNotEmpty() && uniqueTags.add(normalizedTag)) {
                    "Tag dictionary contains an empty or duplicate normalized tag."
                }
                indexedEntries += IndexedTag(
                    record = record,
                    normalizedTag = normalizedTag,
                    translation = normalizedTranslations[normalizedTag],
                    ordinal = ordinal
                )
            }

            val tagTerms = ArrayList<SearchTerm>(records.size)
            val aliasTerms = ArrayList<SearchTerm>()
            val translationTerms = ArrayList<SearchTerm>()
            val tagFuzzyBuckets = newMutableFuzzyBuckets()
            val translationFuzzyBuckets = newMutableFuzzyBuckets()
            var termCount = 0L
            for ((entryIndex, indexed) in indexedEntries.withIndex()) {
                if (entryIndex % CANCELLATION_CHECK_INTERVAL == 0) checkCancelled()
                val tagTerm = SearchTerm(
                    normalized = indexed.normalizedTag,
                    entryIndex = entryIndex,
                    kind = ImagePromptTagMatchKind.TAG_PREFIX,
                    visibleText = indexed.record.tag,
                    fuzzyFingerprint = imagePromptTagFuzzyFingerprint(indexed.normalizedTag)
                )
                tagTerms += tagTerm
                addFuzzyTerm(tagFuzzyBuckets, tagTerm)
                termCount++
                for (alias in indexed.record.aliases) {
                    val normalizedAlias = normalizeImagePromptTag(alias)
                    if (normalizedAlias.isEmpty()) continue
                    val aliasTerm = SearchTerm(
                        normalized = normalizedAlias,
                        entryIndex = entryIndex,
                        kind = ImagePromptTagMatchKind.ALIAS_PREFIX,
                        visibleText = alias,
                        fuzzyFingerprint = imagePromptTagFuzzyFingerprint(normalizedAlias)
                    )
                    aliasTerms += aliasTerm
                    addFuzzyTerm(tagFuzzyBuckets, aliasTerm)
                    termCount++
                }
                indexed.translation?.let { translation ->
                    val normalizedTranslation = normalizeImagePromptTranslation(translation)
                    if (normalizedTranslation.isNotEmpty()) {
                        val translationTerm = SearchTerm(
                            normalized = normalizedTranslation,
                            entryIndex = entryIndex,
                            kind = ImagePromptTagMatchKind.TRANSLATION_PREFIX,
                            visibleText = translation,
                            fuzzyFingerprint = imagePromptTagFuzzyFingerprint(normalizedTranslation)
                        )
                        translationTerms += translationTerm
                        addFuzzyTerm(translationFuzzyBuckets, translationTerm)
                        termCount++
                    }
                }
                require(termCount <= MAX_INDEX_TERMS) { "Autocomplete index term limit exceeded." }
            }
            val termComparator = compareBy<SearchTerm>(SearchTerm::normalized)
                .thenBy { matchKindRank(it.kind) }
                .thenBy(SearchTerm::entryIndex)
            checkCancelled()
            tagTerms.sortWith(termComparator)
            checkCancelled()
            aliasTerms.sortWith(termComparator)
            checkCancelled()
            translationTerms.sortWith(termComparator)
            checkCancelled()
            return ImagePromptTagAutocomplete(
                entries = immutableTagList(indexedEntries),
                tagTerms = immutableTagList(tagTerms),
                aliasTerms = immutableTagList(aliasTerms),
                translationTerms = immutableTagList(translationTerms),
                tagFuzzyTermsByLength = freezeFuzzyBuckets(tagFuzzyBuckets),
                translationFuzzyTermsByLength = freezeFuzzyBuckets(translationFuzzyBuckets)
            )
        }

        private fun deterministicTranslationMap(
            translations: Map<String, String>,
            checkCancelled: () -> Unit
        ): Map<String, String> {
            data class Choice(val rawKey: String, val value: String)

            val choices = HashMap<String, Choice>(translations.size.coerceAtLeast(16))
            for ((index, entry) in translations.entries.withIndex()) {
                if (index % CANCELLATION_CHECK_INTERVAL == 0) checkCancelled()
                val rawKey = entry.key
                val rawValue = entry.value
                require(
                    isSafeCanonicalImagePromptTag(rawKey) && isSafeTranslationValue(rawValue)
                ) {
                    "Translation dictionary contains an invalid entry."
                }
                val normalizedKey = normalizeImagePromptTag(rawKey)
                val value = rawValue.trim()
                val candidate = Choice(rawKey, value)
                val current = choices[normalizedKey]
                if (
                    current == null ||
                    candidate.rawKey < current.rawKey ||
                    (candidate.rawKey == current.rawKey && candidate.value < current.value)
                ) {
                    choices[normalizedKey] = candidate
                }
            }
            return choices.mapValuesTo(HashMap(choices.size.coerceAtLeast(16))) { it.value.value }
        }
    }

    private data class IndexedTag(
        val record: ImagePromptTagRecord,
        val normalizedTag: String,
        val translation: String?,
        val ordinal: Int
    )

    private data class SearchTerm(
        val normalized: String,
        val entryIndex: Int,
        val kind: ImagePromptTagMatchKind,
        val visibleText: String,
        val fuzzyFingerprint: Long
    )

    private class RankedHit(
        var entryIndex: Int,
        var term: SearchTerm,
        var kind: ImagePromptTagMatchKind,
        var quality: Int
    )
}

internal data class ImagePromptActiveToken(
    val query: String,
    val contentStart: Int,
    val segmentEnd: Int
)

internal data class ImagePromptTextEdit(
    val text: String,
    val cursor: Int
)

internal fun findImagePromptActiveToken(text: String, cursor: Int): ImagePromptActiveToken? {
    if (text.length > MAX_PROMPT_TEXT_CHARS || cursor !in 1..text.length) return null
    val rawStart = text.lastIndexOf(',', startIndex = cursor - 1).let { if (it < 0) 0 else it + 1 }
    val segmentEnd = text.indexOf(',', startIndex = cursor).let { if (it < 0) text.length else it }
    var contentStart = rawStart
    while (contentStart < cursor && text[contentStart].isWhitespace()) contentStart++
    var queryEnd = cursor
    while (queryEnd > contentStart && text[queryEnd - 1].isWhitespace()) queryEnd--
    if (queryEnd <= contentStart) return null
    return ImagePromptActiveToken(
        query = text.substring(contentStart, queryEnd),
        contentStart = contentStart,
        segmentEnd = segmentEnd
    )
}

internal fun applyImagePromptTagSuggestion(
    text: String,
    cursor: Int,
    suggestion: ImagePromptTagSuggestion
): ImagePromptTextEdit? {
    val active = findImagePromptActiveToken(text, cursor) ?: return null
    if (suggestion.textualInversionId == null &&
        !isSafeCanonicalImagePromptTag(suggestion.replacementTag)
    ) {
        return null
    }
    if (suggestion.textualInversionId != null && !isSafeTagValue(suggestion.replacementTag)) {
        return null
    }
    val literal = if (suggestion.textualInversionId != null) {
        suggestion.replacementTag
    } else {
        renderImagePromptTagLiteral(suggestion.replacementTag)
    }
    val prefix = text.substring(0, active.contentStart)
    val suffixAfterDelimiter = if (active.segmentEnd < text.length) {
        text.substring(active.segmentEnd + 1).trimStart()
    } else {
        ""
    }
    val updated = buildString(prefix.length + literal.length + suffixAfterDelimiter.length + 2) {
        append(prefix)
        append(literal)
        append(", ")
        append(suffixAfterDelimiter)
    }
    if (updated.length > MAX_PROMPT_TEXT_CHARS) return null
    return ImagePromptTextEdit(updated, prefix.length + literal.length + 2)
}

internal fun adjustImagePromptActiveTagWeight(
    text: String,
    cursor: Int,
    delta: Double
): ImagePromptTextEdit? {
    if (!delta.isFinite() || abs(delta) > MAX_WEIGHT_DELTA) return null
    val segment = findNonEmptyPromptSegment(text, cursor) ?: return null
    val weighted = decodePromptWeight(segment.content) ?: return null
    val adjusted = roundToSingleDecimal(weighted.weight + delta)
        .coerceIn(MIN_PROMPT_WEIGHT, MAX_PROMPT_WEIGHT)
    val replacement = if (adjusted == 1.0) {
        weighted.body
    } else {
        "(${weighted.body}:${String.format(Locale.ROOT, "%.1f", adjusted)})"
    }
    val updated = text.replaceRange(segment.contentStart, segment.contentEnd, replacement)
    if (updated.length > MAX_PROMPT_TEXT_CHARS) return null
    return ImagePromptTextEdit(updated, segment.contentStart + replacement.length)
}

internal fun clearImagePromptActiveTag(text: String, cursor: Int): ImagePromptTextEdit? {
    val segment = findNonEmptyPromptSegment(text, cursor) ?: return null
    val commaBefore = text.lastIndexOf(',', startIndex = segment.contentStart - 1)
    val commaAfter = text.indexOf(',', startIndex = segment.contentEnd)
    return when {
        commaBefore >= 0 -> {
            val prefix = text.substring(0, commaBefore).trimEnd()
            val suffix = if (commaAfter >= 0) text.substring(commaAfter) else ""
            ImagePromptTextEdit(prefix + suffix, prefix.length)
        }

        commaAfter >= 0 -> {
            val remainder = text.substring(commaAfter + 1).trimStart()
            ImagePromptTextEdit(remainder, 0)
        }

        else -> ImagePromptTextEdit("", 0)
    }
}

internal fun renderImagePromptTagLiteral(tag: String): String {
    require(isSafeCanonicalImagePromptTag(tag)) {
        "Canonical tag is invalid for prompt insertion."
    }
    val spaced = StringBuilder(tag.length)
    var index = 0
    while (index < tag.length) {
        if (tag[index] == '\\' && index + 1 < tag.length && tag[index + 1] == '_') {
            spaced.append('_')
            index += 2
        } else {
            spaced.append(if (tag[index] == '_') ' ' else tag[index])
            index++
        }
    }

    val escaped = StringBuilder(spaced.length + 4)
    var precedingBackslashes = 0
    for (char in spaced) {
        if ((char == '(' || char == ')') && precedingBackslashes % 2 == 0) escaped.append('\\')
        escaped.append(char)
        precedingBackslashes = if (char == '\\') precedingBackslashes + 1 else 0
    }
    return escaped.toString()
}

private data class PromptSegment(
    val contentStart: Int,
    val contentEnd: Int,
    val content: String
)

private data class DecodedPromptWeight(
    val body: String,
    val weight: Double
)

private fun findNonEmptyPromptSegment(text: String, cursor: Int): PromptSegment? {
    if (text.length > MAX_PROMPT_TEXT_CHARS || cursor !in 0..text.length) return null
    val rawStart = if (cursor == 0) {
        0
    } else {
        text.lastIndexOf(',', startIndex = cursor - 1).let { if (it < 0) 0 else it + 1 }
    }
    val rawEnd = text.indexOf(',', startIndex = cursor).let { if (it < 0) text.length else it }
    var start = rawStart
    while (start < rawEnd && text[start].isWhitespace()) start++
    var end = rawEnd
    while (end > start && text[end - 1].isWhitespace()) end--
    if (start >= end) return null
    return PromptSegment(start, end, text.substring(start, end))
}

private fun decodePromptWeight(content: String): DecodedPromptWeight? {
    var body = content.trim()
    if (body.isEmpty()) return null
    var weight = 1.0
    repeat(MAX_WEIGHT_WRAPPERS) {
        if (isWholePromptWrapper(body, '(', ')')) {
            val inner = body.substring(1, body.length - 1).trim()
            // A colon only denotes an explicit weight when it belongs to the
            // current wrapper level. Nested prompt groups (for example
            // `((tag:1.2))`) must be unwrapped before their inner colon is
            // considered. Escaped punctuation remains literal prompt text.
            val colon = findTopLevelWeightSeparator(inner)
            if (colon > 0 && colon < inner.lastIndex) {
                val explicit = inner.substring(colon + 1).trim().toDoubleOrNull()
                val explicitBody = inner.substring(0, colon).trim()
                if (
                    explicit != null && explicit.isFinite() &&
                    explicit in MIN_ACCEPTED_EXISTING_WEIGHT..MAX_ACCEPTED_EXISTING_WEIGHT &&
                    explicitBody.isNotEmpty()
                ) {
                    val wrapperDelta = weight - 1.0
                    return DecodedPromptWeight(
                        explicitBody,
                        roundToSingleDecimal(explicit + wrapperDelta)
                    )
                }
            }
            body = inner
            weight += 0.1
        } else if (isWholePromptWrapper(body, '[', ']')) {
            body = body.substring(1, body.length - 1).trim()
            weight -= 0.1
        } else {
            return DecodedPromptWeight(body, roundToSingleDecimal(weight))
        }
        if (body.isEmpty()) return null
    }
    return null
}

private fun findTopLevelWeightSeparator(value: String): Int {
    val stack = ArrayDeque<Char>()
    var candidate = -1
    var index = 0
    while (index < value.length) {
        when (val character = value[index]) {
            '\\' -> {
                if (index + 1 < value.length) index++
            }
            '(' -> stack.addLast(')')
            '[' -> stack.addLast(']')
            ')', ']' -> {
                if (stack.lastOrNull() == character) stack.removeLast()
                else return -1
            }
            ':' -> if (stack.isEmpty()) candidate = index
        }
        index++
    }
    return if (stack.isEmpty()) candidate else -1
}

private fun isWholePromptWrapper(value: String, open: Char, close: Char): Boolean {
    if (value.length < 2 || value.first() != open || value.last() != close) return false
    var depth = 0
    var index = 0
    while (index < value.length) {
        when (value[index]) {
            '\\' -> index++
            open -> depth++
            close -> {
                depth--
                if (depth < 0 || (depth == 0 && index != value.lastIndex)) return false
            }
        }
        index++
    }
    return depth == 0
}

private fun matchKindRank(kind: ImagePromptTagMatchKind): Int = when (kind) {
    ImagePromptTagMatchKind.TAG_PREFIX,
    ImagePromptTagMatchKind.FUZZY_TAG -> 0
    ImagePromptTagMatchKind.ALIAS_PREFIX,
    ImagePromptTagMatchKind.FUZZY_ALIAS -> 1
    ImagePromptTagMatchKind.TRANSLATION_PREFIX,
    ImagePromptTagMatchKind.FUZZY_TRANSLATION -> 2
}

private fun ImagePromptTagMatchKind.toFuzzyKind(): ImagePromptTagMatchKind = when (this) {
    ImagePromptTagMatchKind.TAG_PREFIX -> ImagePromptTagMatchKind.FUZZY_TAG
    ImagePromptTagMatchKind.ALIAS_PREFIX -> ImagePromptTagMatchKind.FUZZY_ALIAS
    ImagePromptTagMatchKind.TRANSLATION_PREFIX -> ImagePromptTagMatchKind.FUZZY_TRANSLATION
    ImagePromptTagMatchKind.FUZZY_TAG,
    ImagePromptTagMatchKind.FUZZY_ALIAS,
    ImagePromptTagMatchKind.FUZZY_TRANSLATION -> this
}

internal fun isImagePromptTagFuzzyScanAllowed(termCount: Long): Boolean =
    termCount in 0L..ImagePromptTagAutocomplete.MAX_FUZZY_SCANNED_TERMS

private fun imagePromptTagFuzzyFingerprint(value: String): Long {
    var fingerprint = 0L
    for (char in value) {
        val bit = when (char) {
            in 'a'..'z' -> char - 'a'
            in '0'..'9' -> 26 + (char - '0')
            '_' -> 36
            else -> 37 + (((char.code * FUZZY_UNICODE_HASH_MULTIPLIER) ushr 1) % 26)
        }
        fingerprint = fingerprint or (1L shl bit)
    }
    return fingerprint
}

private fun isSingleImagePromptTagEdit(left: String, right: String): Boolean {
    val lengthDelta = left.length - right.length
    if (abs(lengthDelta) > ImagePromptTagAutocomplete.MAX_FUZZY_EDITS) return false

    if (lengthDelta == 0) {
        var mismatch = 0
        while (mismatch < left.length && left[mismatch] == right[mismatch]) mismatch++
        if (mismatch == left.length) return false

        var cursor = mismatch + 1
        while (cursor < left.length && left[cursor] == right[cursor]) cursor++
        if (cursor == left.length) return true

        if (
            cursor == mismatch + 1 &&
            left[mismatch] == right[cursor] &&
            left[cursor] == right[mismatch]
        ) {
            cursor++
            while (cursor < left.length && left[cursor] == right[cursor]) cursor++
            return cursor == left.length
        }
        return false
    }

    val shorter = if (left.length < right.length) left else right
    val longer = if (left.length < right.length) right else left
    var cursor = 0
    while (cursor < shorter.length && shorter[cursor] == longer[cursor]) cursor++
    if (cursor == shorter.length) return true
    while (cursor < shorter.length && shorter[cursor] == longer[cursor + 1]) cursor++
    return cursor == shorter.length
}

private fun isBoundedImagePromptTagSubsequence(query: String, candidate: String): Boolean {
    if (query.isEmpty() || candidate.length <= query.length || query.first() != candidate.first()) {
        return false
    }
    var queryIndex = 1
    var lastMatchIndex = 0
    var candidateIndex = 1
    while (candidateIndex < candidate.length && queryIndex < query.length) {
        if (candidate[candidateIndex] == query[queryIndex]) {
            if (candidateIndex - lastMatchIndex - 1 > MAX_FUZZY_SUBSEQUENCE_GAP) return false
            lastMatchIndex = candidateIndex
            queryIndex++
        }
        candidateIndex++
    }
    return queryIndex == query.length
}

private fun normalizeImagePromptTranslation(value: String): String = buildString(value.length) {
    for (char in value.lowercase(Locale.ROOT)) {
        if (char != '_' && char != '-' && !char.isWhitespace()) append(char)
    }
}

private fun isSafeTagValue(value: String): Boolean =
    value.isNotBlank() &&
        value == value.trim() &&
        value.length <= MAX_TAG_TEXT_CHARS &&
        !hasUnpairedSurrogate(value) &&
        value.none { it == ',' || it == '\r' || it == '\n' || it == '\u0000' || it.isISOControl() }

/**
 * A tag selected from the dictionary becomes executable prompt text. Keep that one direction
 * intentionally narrower than aliases and translations: they are search text and may be Chinese,
 * while a canonical replacement must be an English-compatible ASCII tag.
 */
private fun isSafeCanonicalImagePromptTag(value: String): Boolean =
    isSafeTagValue(value) && value.all { character -> character.code in 0x20..0x7e }

private fun isSafeTranslationValue(value: String): Boolean =
    value.isNotBlank() &&
        value == value.trim() &&
        value.length <= MAX_TRANSLATION_TEXT_CHARS &&
        !hasUnpairedSurrogate(value) &&
        value.none { it == '\r' || it == '\n' || it == '\u0000' || it.isISOControl() }

private fun hasUnpairedSurrogate(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val char = value[index]
        when {
            char.isHighSurrogate() -> {
                if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return true
                index += 2
            }

            char.isLowSurrogate() -> return true
            else -> index++
        }
    }
    return false
}

private fun roundToSingleDecimal(value: Double): Double = round(value * 10.0) / 10.0

private fun <T> immutableTagList(values: Collection<T>): List<T> =
    if (values.isEmpty()) emptyList() else Collections.unmodifiableList(ArrayList(values))

private const val MIN_TAG_CATEGORY: Int = 0
private const val MAX_TAG_CATEGORY: Int = 255
private const val MAX_TAG_ALIASES: Int = 64
private const val MAX_TAG_TEXT_CHARS: Int = 256
private const val MAX_TRANSLATION_TEXT_CHARS: Int = 512
private const val MAX_PROMPT_TEXT_CHARS: Int = 65_536
private const val MIN_PROMPT_WEIGHT: Double = 0.1
private const val MAX_PROMPT_WEIGHT: Double = 2.0
private const val MIN_ACCEPTED_EXISTING_WEIGHT: Double = -100.0
private const val MAX_ACCEPTED_EXISTING_WEIGHT: Double = 100.0
private const val MAX_WEIGHT_DELTA: Double = 1.0
private const val MAX_WEIGHT_WRAPPERS: Int = 16
private const val CANCELLATION_CHECK_INTERVAL: Int = 1_024
private const val MATCH_QUALITY_EXACT: Int = 0
private const val MATCH_QUALITY_PREFIX: Int = 1
private const val MATCH_QUALITY_SINGLE_EDIT: Int = 2
private const val MATCH_QUALITY_SUBSEQUENCE_BASE: Int = 3
private const val MAX_FUZZY_FINGERPRINT_DELTA: Int = 2
private const val MAX_FUZZY_SUBSEQUENCE_GAP: Int = 4
private const val FUZZY_UNICODE_HASH_MULTIPLIER: Int = 0x45D9F3B
