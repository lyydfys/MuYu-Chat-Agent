package com.muyuchat.feature.chat

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.ArrayDeque
import java.util.Collections
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

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

private const val CANCELLATION_CHECK_INTERVAL: Int = 1_024
private const val MATCH_QUALITY_EXACT: Int = 0
private const val MATCH_QUALITY_PREFIX: Int = 1
private const val MATCH_QUALITY_SINGLE_EDIT: Int = 2
private const val MATCH_QUALITY_SUBSEQUENCE_BASE: Int = 3
private const val MAX_FUZZY_FINGERPRINT_DELTA: Int = 2
private const val MAX_FUZZY_SUBSEQUENCE_GAP: Int = 4
private const val FUZZY_UNICODE_HASH_MULTIPLIER: Int = 0x45D9F3B
