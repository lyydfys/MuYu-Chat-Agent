package com.muyuchat.mca

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Web-search report builders (P7/S7), moved verbatim from WebSearchProvider.kt: the research,
 * health, quality and diagnostic-health report builders. The four builders consumed by the
 * retained WebSearchResult property getters / record parsing are internal here; every other
 * declaration stays private. Same-package resolution keeps every caller and behavioral test
 * call unchanged.
 */

// P7/S7: shared with WebSearchProvider.kt.
internal fun WebSearchResult.toWebSearchResearchReport(): WebSearchResearchReport {
    val usableDocuments = documents.filterNot { it.provider == "安全拦截" }
    if (usableDocuments.isEmpty()) {
        return WebSearchResearchReport(
            confidenceScore = 0,
            confidenceLabel = "无资料",
            evidenceGroups = emptyList(),
            conflictWarnings = warnings.take(4),
            synthesisGuidance = listOf("没有获得可用网页资料时，不要给出确定性联网结论。")
        )
    }
    val hostCount = usableDocuments
        .map { it.url.webSearchHost() }
        .filter { it.isNotBlank() }
        .distinct()
        .size
    val trustedCount = usableDocuments.count { document ->
        document.url.webSearchHost().isTrustedWebSearchHost() ||
            document.url.contains("/docs", ignoreCase = true) ||
            document.url.contains("/developer", ignoreCase = true)
    }
    val richContentCount = usableDocuments.count { it.content.length >= 500 || it.snippet.length >= 180 }
    val years = usableDocuments.mapNotNull { it.detectedWebSearchYear() }.distinct().sorted()
    val providerLabels = usableDocuments.map { it.provider }.filter { it.isNotBlank() }.distinct()
    var score = 34
    score += (usableDocuments.size * 10).coerceAtMost(28)
    score += (hostCount * 9).coerceAtMost(24)
    score += (trustedCount * 8).coerceAtMost(24)
    score += (richContentCount * 5).coerceAtMost(15)
    if (searchedQueries.size >= 4) score += 6
    if (warnings.isNotEmpty()) score -= (warnings.size * 6).coerceAtMost(24)
    if (years.size >= 2 && years.last() - years.first() >= 3) score -= 8
    score = score.coerceIn(0, 100)
    val confidenceLabel = when {
        score >= 82 -> "高"
        score >= 64 -> "中高"
        score >= 46 -> "中"
        score >= 28 -> "较低"
        else -> "低"
    }
    val evidenceGroups = buildList {
        if (searchedQueries.size >= 4) add("多角度检索 ${searchedQueries.size} 组")
        addAll(usableDocuments.toWebSearchSourceTrustSummary().take(5))
        if (hostCount > 0) add("独立站点 $hostCount 个")
        if (providerLabels.isNotEmpty()) add("搜索服务 ${providerLabels.joinToString(" / ")}")
        if (years.isNotEmpty()) add("资料年份 ${years.first()}-${years.last()}")
    }.distinct().take(8)
    val conflictWarnings = buildList {
        if (usableDocuments.size < 2) add("来源数量不足，结论需要保守表述")
        if (hostCount < 2) add("独立站点不足，可能存在单站点偏差")
        if (trustedCount == 0) add("未命中官方、开发者文档或可信模型社区来源")
        if (years.size >= 2 && years.last() - years.first() >= 3) {
            add("来源年份跨度较大：${years.first()}-${years.last()}，新旧资料可能不一致")
        }
        if (warnings.isNotEmpty()) addAll(warnings.take(3).map { "检索警告：$it" })
        if (usableDocuments.hasMixedCapabilityAndLimitationSignals()) {
            add("来源中同时出现能力优势和限制问题，需要分开说明适用边界")
        }
    }.distinct().take(8)
    val synthesisGuidance = buildList {
        add("优先综合官方、开发者文档、模型社区和代码仓库来源")
        add("把结论按来源支持强弱分层，不要把单一来源说法写成全局事实")
        if (conflictWarnings.isNotEmpty()) add("遇到冲突或旧资料时，明确说明不确定性和资料时间范围")
        if (confidenceLabel == "低" || confidenceLabel == "较低") add("资料不足时给出下一步验证建议，而不是强行下结论")
    }.distinct()
    return WebSearchResearchReport(
        confidenceScore = score,
        confidenceLabel = confidenceLabel,
        evidenceGroups = evidenceGroups,
        conflictWarnings = conflictWarnings,
        synthesisGuidance = synthesisGuidance
    )
}

private fun List<WebSearchDocument>.hasMixedCapabilityAndLimitationSignals(): Boolean {
    val text = joinToString(" ") { "${it.title} ${it.snippet} ${it.content.take(800)}" }.lowercase(Locale.ROOT)
    val positive = listOf("support", "supports", "available", "works", "recommended", "优势", "支持", "可用", "推荐", "提升")
    val negative = listOf("limitation", "limitations", "issue", "issues", "not support", "slow", "risk", "限制", "问题", "缺点", "风险", "较慢")
    return positive.any { text.contains(it.lowercase(Locale.ROOT)) } &&
        negative.any { text.contains(it.lowercase(Locale.ROOT)) }
}

// P7/S7: shared with WebSearchProvider.kt.
internal fun WebSearchResult.toWebSearchHealthReport(): WebSearchHealthReport {
    val quality = qualityReport
    val warningSignals = warnings.mapNotNull { it.toWebSearchHealthSignal() }
    var score = 88
    val reasons = mutableListOf<String>()
    if (documents.isNotEmpty()) {
        reasons += "获得 ${documents.size} 个可用来源"
    } else {
        score -= 46
        reasons += "没有可用来源"
    }
    if (searchedQueries.isNotEmpty()) {
        reasons += "执行 ${searchedQueries.size} 组检索词"
    } else if (directUrls.isNotEmpty()) {
        reasons += "仅执行网页直读"
    }
    if (cacheStatus.contains("命中")) {
        score += 4
        reasons += "命中本机短缓存"
    } else if (cacheStatus.contains("未使用")) {
        reasons += "本轮未使用缓存"
    }
    if (elapsedMs > 0L) {
        when {
            elapsedMs <= 2_500L -> score += 3
            elapsedMs >= 15_000L -> {
                score -= 10
                reasons += "检索耗时较长"
            }
        }
    }
    when {
        quality.score >= 78 -> score += 5
        quality.score < 48 -> score -= 12
    }
    if (warningSignals.isEmpty()) {
        reasons += "未发现 Provider 或相关性警告"
    } else {
        warningSignals.distinctBy { it.label }.forEach { signal ->
            score -= signal.penalty
            reasons += signal.label
        }
    }
    score = score.coerceIn(0, 100)
    val label = when {
        score >= 82 -> "健康"
        score >= 58 -> "降级"
        score >= 28 -> "需检查"
        else -> "失败"
    }
    return WebSearchHealthReport(
        score = score,
        label = label,
        reasons = reasons.distinct().take(8)
    )
}

// P7/S2: shared with WebSearchDiagnosticConversion.kt.
internal data class WebSearchHealthSignal(
    val label: String,
    val penalty: Int
)

// P7/S2: shared with WebSearchDiagnosticConversion.kt.
internal fun String.toWebSearchHealthSignal(): WebSearchHealthSignal? {
    val text = lowercase(Locale.ROOT)
    return when {
        "鉴权失败" in this || "401" in text || "403" in text ->
            WebSearchHealthSignal("鉴权或权限需要检查", 38)
        "404" in text || "路径不存在" in this ->
            WebSearchHealthSignal("接口路径需要检查", 34)
        "429" in text || "限流" in this ->
            WebSearchHealthSignal("搜索服务限流", 30)
        "500" in text || "502" in text || "503" in text || "504" in text || "服务端异常" in this ->
            WebSearchHealthSignal("搜索服务端异常", 30)
        "unable to resolve host" in text || "unknown host" in text || "no address associated" in text ||
            "无法解析" in this || "dns" in text || "vpn" in text || "代理" in this ->
            WebSearchHealthSignal("设备网络或 DNS 需要检查", 38)
        "timeout" in text || "timed out" in text || "超时" in this ->
            WebSearchHealthSignal("搜索请求超时", 28)
        "failed to connect" in text || "connection refused" in text || "无法连接" in this ->
            WebSearchHealthSignal("搜索服务连接失败", 32)
        "全部失败" in this || "请求失败" in this ->
            WebSearchHealthSignal("搜索请求失败", 42)
        "低相关" in this || "相关性较弱" in this || "相关性过滤" in this ->
            WebSearchHealthSignal("来源相关性不足，已降级处理", 16)
        "空结果" in this || "无结果" in this ->
            WebSearchHealthSignal("搜索服务返回空结果", 18)
        "较早资料" in this ->
            WebSearchHealthSignal("未检索到足够近期资料", 12)
        "安全拦截" in this || "受限地址" in this ->
            WebSearchHealthSignal("网页直读被安全拦截", 24)
        else -> null
    }
}

// P7/S7: shared with WebSearchProvider.kt.
internal fun List<WebSearchDocument>.toWebSearchQualityReport(): WebSearchQualityReport {
    if (isEmpty()) {
        return WebSearchQualityReport(
            score = 0,
            label = "无资料",
            reasons = listOf("没有可用来源")
        )
    }
    val blockedCount = count { it.provider == "安全拦截" }
    val usableDocuments = filterNot { it.provider == "安全拦截" }
    if (usableDocuments.isEmpty()) {
        return WebSearchQualityReport(
            score = 8,
            label = "低",
            reasons = listOf("只有安全拦截记录", "没有可读取网页正文")
        )
    }
    val sourceCount = usableDocuments.size
    val hostCount = usableDocuments
        .mapNotNull { it.url.toHttpUrlOrNull()?.host?.removePrefix("www.") }
        .distinct()
        .size
    val contentChars = usableDocuments.sumOf { it.content.length + it.snippet.length }
    val contentRichCount = usableDocuments.count { it.content.length >= 500 || it.snippet.length >= 180 }
    val trustedHostCount = usableDocuments.count { it.url.webSearchHost().isTrustedWebSearchHost() }
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val recentSourceCount = usableDocuments.count { document ->
        document.detectedWebSearchYear()?.let { year -> year >= currentYear - 1 } == true
    }
    var score = 20
    score += (sourceCount * 12).coerceAtMost(30)
    score += (hostCount * 10).coerceAtMost(24)
    score += (contentChars / 260).coerceAtMost(28)
    score += (contentRichCount * 6).coerceAtMost(18)
    score += (trustedHostCount * 6).coerceAtMost(18)
    score += (recentSourceCount * 4).coerceAtMost(12)
    if (blockedCount > 0) score -= 12
    score = score.coerceIn(0, 100)
    val label = when {
        score >= 78 -> "高"
        score >= 48 -> "中"
        else -> "低"
    }
    val reasons = buildList {
        add("${sourceCount} 个可用来源")
        add("${hostCount} 个独立站点")
        add("约 ${contentChars.coerceAtMost(99_999)} 字符资料")
        if (contentRichCount > 0) add("${contentRichCount} 个正文较完整来源")
        if (trustedHostCount > 0) add("${trustedHostCount} 个可信一手/开发者来源")
        if (recentSourceCount > 0) add("${recentSourceCount} 个近年来源")
        if (blockedCount > 0) add("${blockedCount} 个安全拦截结果")
    }
    return WebSearchQualityReport(score = score, label = label, reasons = reasons)
}


// P7/S7: shared with WebSearchProvider.kt.
internal fun JSONObject.toWebSearchDiagnosticHealthReport(): WebSearchHealthReport {
    val explicitLabel = optString("healthLabel")
    val explicitReasons = optJSONArray("healthReasons")?.toStringList().orEmpty()
    if (explicitLabel.isNotBlank()) {
        return WebSearchHealthReport(
            score = optInt("healthScore", 0).coerceIn(0, 100),
            label = explicitLabel,
            reasons = explicitReasons
        )
    }
    val success = optBoolean("success", false)
    val sourceCount = optInt("sourceCount", 0)
    val qualityScore = optInt("qualityScore", 0)
    val warnings = optJSONArray("warnings")?.toStringList().orEmpty()
    val cacheStatus = optString("cacheStatus")
    val warningSignals = warnings.mapNotNull { it.toWebSearchHealthSignal() }
    var score = if (success) 72 else 22
    val reasons = mutableListOf<String>()
    if (sourceCount > 0) {
        score += (sourceCount * 6).coerceAtMost(16)
        reasons += "历史记录：获得 $sourceCount 个来源"
    } else {
        score -= 18
        reasons += "历史记录：没有可用来源"
    }
    when {
        qualityScore >= 78 -> score += 8
        qualityScore in 48..77 -> score += 2
        qualityScore in 1..47 -> score -= 10
    }
    if (cacheStatus.contains("命中")) {
        reasons += "历史记录：命中本机短缓存"
    }
    warningSignals.distinctBy { it.label }.forEach { signal ->
        score -= signal.penalty
        reasons += signal.label
    }
    if (warningSignals.isEmpty()) reasons += "历史记录：未发现 Provider 或相关性警告"
    score = score.coerceIn(0, 100)
    val label = when {
        score >= 82 -> "健康"
        score >= 58 -> "降级"
        score >= 28 -> "需检查"
        else -> "失败"
    }
    return WebSearchHealthReport(score = score, label = label, reasons = reasons.distinct().take(8))
}
