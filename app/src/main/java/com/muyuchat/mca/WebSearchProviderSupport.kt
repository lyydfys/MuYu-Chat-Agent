package com.muyuchat.mca

import com.muyuchat.core.engine.ChatSourceReference
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Locale
import javax.net.ssl.SSLException

/**
 * Web-search provider support declarations (P7/S10), moved verbatim from WebSearchProvider.kt:
 * failure-message mapping, closed-loop check building, query limits, cache constants, host tables
 * and query builders. Declarations consumed by the retained class/execute path are internal here.
 * Same-package resolution keeps every caller and behavioral test call unchanged.
 */

// P7/S10: shared with WebSearchProvider.kt.
internal fun Throwable.friendlyWebSearchFailureMessage(): String {
    val detail = message.orEmpty().trim()
    val suffix = detail.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
    return when (this) {
        is UnknownHostException -> "设备无法解析搜索服务域名，请检查手机网络、DNS、VPN 或代理设置$suffix"
        is SocketTimeoutException -> "搜索请求超时，请检查网络稳定性或稍后重试$suffix"
        is ConnectException -> "无法连接搜索服务，请检查接口地址、网络连接或防火墙$suffix"
        is SSLException -> "搜索服务 HTTPS/TLS 连接失败，请检查证书、系统时间或代理设置$suffix"
        else -> detail.ifBlank { javaClass.simpleName }
    }
}

// P7/S10: shared with WebSearchProvider.kt.
internal fun WebSearchResult.toClosedLoopChecks(
    promptContext: String,
    sourceReferences: List<ChatSourceReference>
): List<String> =
    buildList {
        add("已生成检索计划：${searchedQueries.size.coerceAtLeast(directUrls.size)} 个检索目标")
        if (searchedQueries.isNotEmpty()) {
            add("已执行搜索 Provider：$providerLabel")
        }
        if (directUrls.isNotEmpty()) {
            add("已处理网页直读：${directUrls.size} 个 URL")
        }
        add(if (documents.isNotEmpty()) "已获得可用来源：${documents.size} 个" else "未获得可用来源")
        add(if (promptContext.isNotBlank()) "已生成模型联网上下文" else "未生成模型联网上下文")
        add(if (sourceReferences.isNotEmpty()) "已生成来源卡片数据：${sourceReferences.size} 张" else "未生成来源卡片数据")
        healthReport.let { health -> add("检索健康：${health.label}（${health.score}/100）") }
        qualityReport.let { quality -> add("资料质量：${quality.label}（${quality.score}/100）") }
        if (searchedQueries.size >= 4) {
            researchReport.let { research ->
                add("研究综合：${research.confidenceLabel}（${research.confidenceScore}/100）")
                if (research.evidenceGroups.isNotEmpty()) {
                    add("研究证据：${research.evidenceGroups.take(3).joinToString("；")}")
                }
                if (research.conflictWarnings.isNotEmpty()) {
                    add("研究不确定性：${research.conflictWarnings.take(2).joinToString("；")}")
                }
            }
        }
        cacheStatus.takeIf { it.isNotBlank() }?.let { add("缓存状态：$it") }
    }

// P7/S10: shared with WebSearchProvider.kt.
internal fun WebSearchPlan.providerQueryLimit(): Int =
    if (
        reason == "research" ||
        queries.size >= 4 ||
        triggerReasons.any { it.contains("研究") || it.contains("多源") }
    ) {
        MAX_WEB_SEARCH_QUERY_ATTEMPTS
    } else {
        3
    }

// P7/S5: shared with WebSearchScoringOperations.kt.
internal val yearRegex = Regex("""\b20\d{2}\b""")
// P7/S6: shared with WebSearchAnswerGuards.kt.
internal val webSearchCitationRegex = Regex("""(?:\[(\d{1,2})]|［(\d{1,2})］)""")
// P7/S10: shared with WebSearchProvider.kt.
internal const val DEFAULT_WEB_SEARCH_CACHE_TTL_MS = 2 * 60 * 1000L
// P7/S10: shared with WebSearchProvider.kt.
internal const val MAX_WEB_SEARCH_CACHE_ENTRIES = 24
// P7/S1: widened private->internal because both this file and WebSearchPreflightChecks.kt
// (the extracted preflight owner) enforce the same backup-provider cap.
internal const val MAX_WEB_SEARCH_BACKUP_PROVIDERS = 3
// P7/S10: shared with WebSearchProvider.kt.
internal const val MAX_WEB_SEARCH_QUERY_ATTEMPTS = 5
// P7/S10: shared with WebSearchProvider.kt.
internal const val WEB_SEARCH_CACHE_HIT = "命中本机短缓存"
// P7/S10: shared with WebSearchProvider.kt.
internal const val WEB_SEARCH_CACHE_STORED = "实时检索，已写入本机短缓存"
// P7/S10: shared with WebSearchProvider.kt.
internal const val WEB_SEARCH_CACHE_NOT_USED = "未使用缓存"
// P7/S6: shared with WebSearchAnswerGuards.kt.
internal val webSearchGroundingReplacementRules = listOf(
    Regex("""联网检索资料较少，以下主要基于我的知识库整理（截至[^）]*）：""") to
        "本轮联网检索来源有限，以下基于已检索到的资料整理；未覆盖的信息会标注不确定性：",
    Regex("""以下主要基于我的知识库整理（截至[^）]*）：""") to
        "以下基于本轮联网检索资料整理；未覆盖的信息会标注不确定性：",
    Regex("""根据我的知识库（截至[^）]*），?""") to
        "根据本轮联网检索资料，",
    Regex("""以下基于我的知识库更新至\*{0,2}[^，。；：\n]*\*{0,2}[，。；：]?""") to
        "以下基于本轮联网检索资料；未覆盖的信息会标注不确定性：",
    Regex("""我的知识库更新至\*{0,2}[^，。；：\n]*\*{0,2}""") to
        "本轮联网检索资料",
    Regex("""我的知识库（截至[^）]*）""") to
        "本轮联网检索资料",
    Regex("""我的知识库""") to
        "本轮联网检索资料",
    Regex("""基于知识库""") to
        "基于本轮联网检索资料",
    Regex("""知识库""") to
        "本轮联网检索资料",
    Regex("""我的训练知识""") to
        "本轮联网检索资料",
    Regex("""训练数据""") to
        "本轮联网检索资料",
    Regex("""knowledge base""", RegexOption.IGNORE_CASE) to
        "retrieved web sources",
    Regex("""training data""", RegexOption.IGNORE_CASE) to
        "retrieved web sources",
    Regex("""由于我无法真正执行联网检索[^。！？]*[。！？]""") to
        "本轮 MCA 已完成联网检索。",
    Regex("""我无法联网[^。！？]*[。！？]""") to
        "本轮 MCA 已完成联网检索。",
    Regex("""无法访问实时信息""") to
        "本轮检索资料有限"
)
// P7/S5: shared with WebSearchScoringOperations.kt.
internal val trustedFirstPartyWebSearchHosts = setOf(
    "openai.com",
    "anthropic.com",
    "google.com",
    "android.com",
    "developer.android.com",
    "ai.google.dev",
    "cloud.google.com",
    "microsoft.com",
    "apple.com",
    "nvidia.com",
    "qualcomm.com",
    "alibabacloud.com",
    "aliyun.com",
    "dashscope.aliyuncs.com",
    "qwenlm.github.io",
    "zhipuai.cn",
    "moonshot.cn",
    "modelscope.cn",
    "huggingface.co",
    "github.com",
    "arxiv.org"
)
// P7/S5: shared with WebSearchScoringOperations.kt.
internal val trustedDeveloperWebSearchHosts = setOf(
    "docs.github.com",
    "learn.microsoft.com",
    "developer.android.com",
    "ai.google.dev",
    "cloud.google.com",
    "platform.openai.com",
    "docs.anthropic.com",
    "help.openai.com",
    "modelscope.cn",
    "huggingface.co",
    "github.com",
    "arxiv.org"
)
// P7/S5: shared with WebSearchScoringOperations.kt.
internal val communityWebSearchHosts = setOf(
    "reddit.com",
    "stackoverflow.com",
    "stackexchange.com",
    "zhihu.com",
    "juejin.cn",
    "csdn.net",
    "v2ex.com",
    "x.com",
    "twitter.com",
    "tieba.baidu.com"
)
// P7/S5: shared with WebSearchScoringOperations.kt.
internal val mediaWebSearchHosts = setOf(
    "theverge.com",
    "arstechnica.com",
    "wired.com",
    "techcrunch.com",
    "venturebeat.com",
    "36kr.com",
    "ithome.com",
    "sspai.com",
    "jiqizhixin.com",
    "quantamagazine.org"
)

// P7/S4: shared with WebSearchPlanBuilding.kt.
internal fun String.smartSearchQueries(researchMode: WebSearchResearchMode = WebSearchResearchMode.AUTO): List<String> {
    val base = cleanSearchQuery()
    if (base.isBlank()) return emptyList()
    val year = Calendar.getInstance().get(Calendar.YEAR)
    val queries = mutableListOf(base)
    val lower = base.lowercase(Locale.ROOT)
    val researchHintMatched = researchSearchHints.any { lower.contains(it.lowercase(Locale.ROOT)) } ||
        (
            comparisonSearchHints.any { lower.contains(it.lowercase(Locale.ROOT)) } &&
                (freshnessSearchHints.any { lower.contains(it.lowercase(Locale.ROOT)) } ||
                    officialSearchHints.any { lower.contains(it.lowercase(Locale.ROOT)) })
            )
    val wantsResearch = when (researchMode) {
        WebSearchResearchMode.AUTO -> researchHintMatched
        WebSearchResearchMode.OFF -> false
        WebSearchResearchMode.DEEP -> true
    }
    if (freshnessSearchHints.any { lower.contains(it.lowercase(Locale.ROOT)) }) {
        queries += "$base $year"
    }
    if (officialSearchHints.any { lower.contains(it.lowercase(Locale.ROOT)) }) {
        queries += "$base 官方 文档"
    }
    if (comparisonSearchHints.any { lower.contains(it.lowercase(Locale.ROOT)) }) {
        queries += "$base 评测 对比"
    }
    if (wantsResearch) {
        val core = listOf(base).fallbackSearchQueries().firstOrNull().orEmpty().ifBlank { base }
        queries += "$core 官方 文档 docs"
        queries += "$core 评测 对比 benchmark review"
        queries += "$core 限制 问题 缺点 limitations issues"
        queries += "$core GitHub ModelScope community"
    }
    return queries
        .map { it.cleanSearchQuery() }
        .filter { it.isNotBlank() }
        .distinct()
        .take(if (wantsResearch) MAX_WEB_SEARCH_QUERY_ATTEMPTS else 3)
}

// P7/S10: shared with WebSearchProvider.kt.
internal fun List<String>.fallbackSearchQueries(): List<String> {
    val terms = flatMap { it.searchTerms() }
        .map { it.trim() }
        .filter { it.length >= 2 }
        .filterNot { it.isGenericSearchTerm() }
        .distinctBy { it.lowercase(Locale.ROOT) }
    val compactCore = terms
        .take(6)
        .joinToString(" ")
        .cleanSearchQuery()
    val namedCore = terms
        .filter { term ->
            term.any { it.isDigit() } ||
                term.any { it.isUpperCase() } ||
                knownSearchNameTerms.any { known -> known.equals(term, ignoreCase = true) }
        }
        .take(5)
        .joinToString(" ")
        .cleanSearchQuery()
    return listOf(namedCore, compactCore)
        .map { it.restoreKnownSearchNames().normalizeWhitespace().take(160).trim() }
        .filter { it.length >= 2 }
        .distinct()
}

// P7/S5: shared with WebSearchScoringOperations.kt.
internal fun String.searchTerms(): List<String> {
    val normalized = normalizeWhitespace()
    val asciiTerms = normalized
        .split(Regex("""[^\p{L}\p{N}._+-]+"""))
        .map { it.trim() }
        .filter { it.length >= 2 }
    val cjkTerms = Regex("""[\u4e00-\u9fff]{2,}""")
        .findAll(normalized)
        .flatMap { match ->
            val text = match.value
            buildList {
                add(text)
                if (text.length >= 4) {
                    text.windowed(2, 1).take(8).forEach(::add)
                }
            }
        }
        .toList()
    return (asciiTerms + cjkTerms)
        .map { it.trim() }
        .filter { it.length >= 2 }
        .distinct()
}

// P7/S11: moved from the WebSearchProvider class body (class-only callers).
internal fun String.hasCustomJsonPlaceholder(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains("{query}") ||
            lower.contains("{q}") ||
            lower.contains("{max_results}") ||
            lower.contains("{maxresults}") ||
            lower.contains("{limit}")
    }

internal fun String.replaceCustomJsonPlaceholders(query: String, maxResults: String): String {
        if (!hasCustomJsonPlaceholder()) return this
        val encodedQuery = query.urlEncodeForTemplate()
        val encodedMaxResults = maxResults.urlEncodeForTemplate()
        return replace("{query}", encodedQuery, ignoreCase = true)
            .replace("{q}", encodedQuery, ignoreCase = true)
            .replace("{max_results}", encodedMaxResults, ignoreCase = true)
            .replace("{maxResults}", encodedMaxResults, ignoreCase = true)
            .replace("{limit}", encodedMaxResults, ignoreCase = true)
    }

internal fun String.urlEncodeForTemplate(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")
