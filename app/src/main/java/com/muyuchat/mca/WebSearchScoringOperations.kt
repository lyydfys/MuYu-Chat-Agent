package com.muyuchat.mca

import com.muyuchat.core.engine.ChatSourceReference
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Calendar
import java.util.Locale

/**
 * Web-search scoring and source-trust evaluation (P7/S5), moved verbatim from WebSearchProvider.kt:
 * relevance selection/ranking, authority and freshness scoring, host trust classification and the
 * source-trust summary. Declarations shared across the cut were widened private->internal in place
 * in WebSearchProvider.kt (the four scoring data classes, yearRegex, the trusted/developer/community/
 * media host sets, freshnessSearchHints and officialSearchHints); five declarations used by the
 * retained file are internal here (selectRelevantForQuestion, detectedWebSearchYear, webSearchHost,
 * isTrustedWebSearchHost, WebSearchDocument.webSearchSourceTrustClass). Same-package resolution
 * keeps every caller and behavioral test call unchanged.
 */

// P7/S5: shared with WebSearchProvider.kt.
internal fun List<WebSearchDocument>.selectRelevantForQuestion(
    question: String,
    queries: List<String>,
    directUrls: List<String>,
    maxResults: Int
): WebSearchRelevanceSelection {
    if (isEmpty()) return WebSearchRelevanceSelection(documents = emptyList())
    val profile = searchTermProfile(question, queries)
    val directUrlKeys = directUrls.map { it.normalizedUrlKey() }.toSet()
    val ranked = map { document ->
        document.toRankedDocument(
            question = question,
            profile = profile,
            isDirectUrl = document.url.normalizedUrlKey() in directUrlKeys
        )
    }.sortedWith(
        compareByDescending<WebSearchRankedDocument> { it.isDirectUrl }
            .thenByDescending { it.score }
            .thenByDescending { it.authorityScore }
            .thenByDescending { it.freshnessScore }
            .thenByDescending { it.strongCoreMatchCount }
            .thenByDescending { it.coreMatchCount }
            .thenBy { it.document.title.length.coerceAtLeast(1) }
    )
    if (profile.coreTerms.isEmpty()) {
        return WebSearchRelevanceSelection(documents = ranked.map { it.document }.take(maxResults))
    }

    val hasCjkCoreTerm = profile.coreTerms.any { it.hasCjkCharacter() }
    val minCoreMatches = if (!hasCjkCoreTerm && profile.coreTerms.size >= 2) 2 else 1
    val minScore = if (minCoreMatches >= 2) 10 else 6
    val relevant = ranked.filter { rankedDocument ->
        rankedDocument.isDirectUrl ||
            (
                rankedDocument.coreMatchCount >= minCoreMatches &&
                    rankedDocument.score >= minScore &&
                    (minCoreMatches < 2 || rankedDocument.strongCoreMatchCount >= 1)
                )
    }
    val usesWeakSingleSourceFallback = relevant.isEmpty() && ranked.size == 1
    val selectedRanked = when {
        relevant.isNotEmpty() -> relevant
        usesWeakSingleSourceFallback -> ranked.take(1)
        else -> ranked.filter { it.score >= minScore }.take(1)
    }.take(maxResults)
    val selected = selectedRanked.map { it.document }
    val filteredCount = (size - selected.size).coerceAtLeast(0)
    val warnings = buildList {
        if (filteredCount > 0 && selected.isNotEmpty()) add("已过滤 $filteredCount 个低相关来源")
        if (usesWeakSingleSourceFallback) add("唯一来源相关性较弱，已保留供参考")
        if (
            profile.wantsFreshness &&
            selectedRanked.isNotEmpty() &&
            selectedRanked.none { it.freshnessScore > 0 } &&
            selectedRanked.any { rankedDocument ->
                rankedDocument.detectedYear?.let { year -> year < Calendar.getInstance().get(Calendar.YEAR) - 1 } == true
            }
        ) {
            add("未检索到近期强匹配来源，已保留较早资料")
        }
    }
    return WebSearchRelevanceSelection(
        documents = selected,
        warnings = warnings
    )
}

private fun WebSearchDocument.toRankedDocument(
    question: String,
    profile: SearchTermProfile,
    isDirectUrl: Boolean
): WebSearchRankedDocument {
    val title = this.title.cleanWebSearchText().lowercase(Locale.ROOT)
    val snippet = this.snippet.cleanWebSearchText().lowercase(Locale.ROOT)
    val content = this.content.cleanWebSearchText().lowercase(Locale.ROOT)
    val url = this.url.lowercase(Locale.ROOT)
    val host = this.url.webSearchHost()
    val cleanQuestion = question.cleanWebSearchText().lowercase(Locale.ROOT)
    var score = 0
    if (title.contains(cleanQuestion) && cleanQuestion.length >= 4) score += 24
    if (snippet.contains(cleanQuestion) && cleanQuestion.length >= 4) score += 12
    profile.terms.forEach { term ->
        val lower = term.lowercase(Locale.ROOT)
        if (title.containsSearchTerm(lower)) score += 8
        if (url.containsSearchTerm(lower)) score += 4
        if (snippet.containsSearchTerm(lower)) score += 3
        if (content.containsSearchTerm(lower)) score += 1
    }
    val coreMatchCount = profile.coreTerms.count { term ->
        val lower = term.lowercase(Locale.ROOT)
        title.containsSearchTerm(lower) ||
            url.containsSearchTerm(lower) ||
            snippet.containsSearchTerm(lower) ||
            content.containsSearchTerm(lower)
    }
    val strongCoreMatchCount = profile.coreTerms.count { term ->
        val lower = term.lowercase(Locale.ROOT)
        title.containsSearchTerm(lower) || url.containsSearchTerm(lower)
    }
    if (url.startsWith("https://", ignoreCase = true)) score += 2
    if (content.length > 500) score += 2
    val authorityScore = documentAuthorityScore(
        host = host,
        url = url,
        title = title,
        profile = profile
    )
    val freshness = documentFreshnessScore(
        title = title,
        snippet = snippet,
        content = content,
        url = url,
        wantsFreshness = profile.wantsFreshness
    )
    score += authorityScore + freshness.score
    if (isDirectUrl) score += 50
    return WebSearchRankedDocument(
        document = this,
        score = score,
        coreMatchCount = coreMatchCount,
        strongCoreMatchCount = strongCoreMatchCount,
        isDirectUrl = isDirectUrl,
        authorityScore = authorityScore,
        freshnessScore = freshness.score,
        detectedYear = freshness.year,
        host = host
    )
}

private fun searchTermProfile(question: String, queries: List<String>): SearchTermProfile {
    val searchText = (listOf(question) + queries).joinToString(" ")
    val lowerSearchText = searchText.lowercase(Locale.ROOT)
    val terms = (listOf(question) + queries)
        .flatMap { it.searchTerms() }
        .distinct()
        .take(20)
    val coreTerms = terms
        .map { it.trim() }
        .filter { it.length >= 2 }
        .filterNot { it.isGenericSearchTerm() }
        .distinctBy { it.lowercase(Locale.ROOT) }
        .take(8)
    return SearchTermProfile(
        terms = terms,
        coreTerms = coreTerms,
        wantsFreshness = freshnessSearchHints.any { lowerSearchText.contains(it.lowercase(Locale.ROOT)) },
        wantsOfficialSource = officialSearchHints.any { lowerSearchText.contains(it.lowercase(Locale.ROOT)) },
        wantsResearchSynthesis = researchSearchHints.any { lowerSearchText.contains(it.lowercase(Locale.ROOT)) }
    )
}

private fun documentAuthorityScore(
    host: String,
    url: String,
    title: String,
    profile: SearchTermProfile
): Int {
    if (host.isBlank()) return 0
    val normalizedHost = host.removePrefix("www.")
    val hostLabels = normalizedHost.split('.', '-').filter { it.isNotBlank() }
    val pathLooksLikeDocs = url.contains("/docs", ignoreCase = true) ||
        url.contains("/documentation", ignoreCase = true) ||
        url.contains("/guide", ignoreCase = true) ||
        url.contains("/developer", ignoreCase = true)
    val brandHostMatch = profile.coreTerms
        .map { it.lowercase(Locale.ROOT).filter { ch -> ch.isLetterOrDigit() } }
        .filter { it.length >= 3 }
        .any { term ->
            hostLabels.any { label -> label == term || label.startsWith(term) || term.startsWith(label) && label.length >= 4 }
        }
    var score = 0
    if (brandHostMatch) score += 14
    if (normalizedHost in trustedFirstPartyWebSearchHosts) score += 12
    if (trustedFirstPartyWebSearchHosts.any { normalizedHost.endsWith(".$it") }) score += 10
    if (normalizedHost in trustedDeveloperWebSearchHosts || trustedDeveloperWebSearchHosts.any { normalizedHost.endsWith(".$it") }) {
        score += 10
    }
    if (pathLooksLikeDocs) score += 6
    if (profile.wantsOfficialSource) {
        when {
            brandHostMatch -> score += 10
            pathLooksLikeDocs -> score += 5
            title.contains("official", ignoreCase = true) || title.contains("官方") -> score += 5
            else -> score -= 3
        }
    }
    return score.coerceIn(-8, 36)
}

private fun documentFreshnessScore(
    title: String,
    snippet: String,
    content: String,
    url: String,
    wantsFreshness: Boolean
): WebSearchFreshnessScore {
    val detectedYear = listOf(title, snippet, url, content.take(600))
        .asSequence()
        .flatMap { text -> yearRegex.findAll(text).mapNotNull { it.value.toIntOrNull() } }
        .filter { it in 2000..Calendar.getInstance().get(Calendar.YEAR) + 1 }
        .maxOrNull()
    if (detectedYear == null) return WebSearchFreshnessScore(score = 0, year = null)
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val age = currentYear - detectedYear
    val score = when {
        !wantsFreshness && age <= 1 -> 3
        !wantsFreshness -> 0
        age <= 0 -> 18
        age == 1 -> 12
        age == 2 -> 4
        age in 3..4 -> -5
        else -> -10
    }
    return WebSearchFreshnessScore(score = score, year = detectedYear)
}

// P7/S5: shared with WebSearchProvider.kt.
internal fun WebSearchDocument.detectedWebSearchYear(): Int? =
    listOf(title, snippet, url, content.take(600))
        .asSequence()
        .flatMap { text -> yearRegex.findAll(text).mapNotNull { it.value.toIntOrNull() } }
        .filter { it in 2000..Calendar.getInstance().get(Calendar.YEAR) + 1 }
        .maxOrNull()

// P7/S5: shared with WebSearchProvider.kt.
internal fun String.webSearchHost(): String =
    toHttpUrlOrNull()
        ?.host
        ?.lowercase(Locale.ROOT)
        ?.removePrefix("www.")
        .orEmpty()

// P7/S5: shared with WebSearchProvider.kt.
internal fun String.isTrustedWebSearchHost(): Boolean {
    if (isBlank()) return false
    val host = removePrefix("www.")
    return host in trustedFirstPartyWebSearchHosts ||
        trustedFirstPartyWebSearchHosts.any { host.endsWith(".$it") } ||
        host in trustedDeveloperWebSearchHosts ||
        trustedDeveloperWebSearchHosts.any { host.endsWith(".$it") }
}

internal fun ChatSourceReference.webSearchSourceTrustClass(): WebSearchSourceTrustClass =
    webSearchSourceTrustClass(url = url, provider = provider)

internal fun ChatSourceReference.webSearchHostLabel(): String =
    url.webSearchHost().ifBlank { url.removePrefix("https://").removePrefix("http://").substringBefore("/") }

// P7/S5: shared with WebSearchProvider.kt.
internal fun WebSearchDocument.webSearchSourceTrustClass(): WebSearchSourceTrustClass =
    webSearchSourceTrustClass(url = url, provider = provider)

private fun webSearchSourceTrustClass(url: String, provider: String): WebSearchSourceTrustClass {
    if (provider == "安全拦截") {
        return WebSearchSourceTrustClass("安全拦截", "受限地址未读取")
    }
    val host = url.webSearchHost()
    if (host.isBlank()) {
        return WebSearchSourceTrustClass("未知来源", "无法识别站点")
    }
    val normalizedHost = host.removePrefix("www.")
    fun hostMatches(candidates: Set<String>): Boolean =
        normalizedHost in candidates || candidates.any { normalizedHost.endsWith(".$it") }

    return when {
        normalizedHost == "github.com" || normalizedHost.endsWith(".github.com") ->
            WebSearchSourceTrustClass("代码仓库", "GitHub 或开发者仓库")
        normalizedHost == "huggingface.co" || normalizedHost == "modelscope.cn" ->
            WebSearchSourceTrustClass("模型社区", "模型托管与下载站点")
        normalizedHost == "arxiv.org" || normalizedHost.endsWith(".arxiv.org") ->
            WebSearchSourceTrustClass("学术论文", "论文或预印本来源")
        hostMatches(trustedFirstPartyWebSearchHosts) ->
            WebSearchSourceTrustClass("官方/一手", "品牌、平台或官方站点")
        hostMatches(trustedDeveloperWebSearchHosts) || url.contains("/docs", ignoreCase = true) || url.contains("/developer", ignoreCase = true) ->
            WebSearchSourceTrustClass("开发者文档", "文档、指南或开发者资料")
        hostMatches(communityWebSearchHosts) ->
            WebSearchSourceTrustClass("社区讨论", "论坛、问答或社交讨论")
        hostMatches(mediaWebSearchHosts) ->
            WebSearchSourceTrustClass("媒体报道", "新闻、评测或媒体文章")
        else ->
            WebSearchSourceTrustClass("普通网页", "未命中官方、开发者或社区分类")
    }
}

// P7/S2: shared with WebSearchDiagnosticConversion.kt.
internal fun List<WebSearchDocument>.toWebSearchSourceTrustSummary(): List<String> =
    filterNot { it.provider == "安全拦截" }
        .groupingBy { it.webSearchSourceTrustClass().label }
        .eachCount()
        .entries
        .sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { it.key }
        )
        .map { (label, count) -> "$label $count 个" }
