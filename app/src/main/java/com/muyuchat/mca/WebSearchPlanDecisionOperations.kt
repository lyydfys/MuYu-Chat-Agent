package com.muyuchat.mca

import java.util.Locale

/**
 * Web-search plan decision operations (P7/S9), moved verbatim from WebSearchProvider.kt: query
 * cleanup helpers, trigger-reason construction and the plan decision predicates. Only
 * webSearchDecisionReasons had to change visibility (private->internal; the retained execute
 * path calls it); the rest keep their original visibility. Same-package resolution keeps every
 * caller and behavioral test call unchanged.
 */

// P7/S5: shared with WebSearchScoringOperations.kt.
internal fun String.containsSearchTerm(term: String): Boolean {
    if (term.isBlank()) return false
    if (!term.isAsciiAlphaNumericTerm() || term.length > 2) {
        return contains(term, ignoreCase = true)
    }
    return Regex("""(?<![a-z0-9])${Regex.escape(term)}(?![a-z0-9])""", RegexOption.IGNORE_CASE)
        .containsMatchIn(this)
}

private fun String.isAsciiAlphaNumericTerm(): Boolean =
    all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

// P7/S5: shared with WebSearchScoringOperations.kt.
internal fun String.hasCjkCharacter(): Boolean =
    any { it in '\u4e00'..'\u9fff' }

// P7/S5: shared with WebSearchScoringOperations.kt.
internal fun String.isGenericSearchTerm(): Boolean =
    lowercase(Locale.ROOT).trim() in genericSearchTerms

// P7/S4: shared with WebSearchPlanBuilding.kt.
internal fun String.cleanSearchQuery(): String =
    normalizeWhitespace()
        .removeChineseSearchCommandPrefix()
        .removeSearchPrefixIgnoreCase("search for")
        .removeSearchPrefixIgnoreCase("search")
        .removeSearchPrefixIgnoreCase("web search")
        .removeSearchPrefixIgnoreCase("look up")
        .removeSearchPrefixIgnoreCase("find online")
        .removeSearchPrefixIgnoreCase("please search")
        .expandCompactSearchWords()
        .restoreKnownSearchNames()
        .removeSearchAnswerInstructionSuffix()
        .normalizeWhitespace()
        .take(220)
        .trim()

private fun String.removeChineseSearchCommandPrefix(): String {
    val prefixes = listOf(
        "麻烦帮我搜索一下",
        "麻烦帮我搜索",
        "麻烦搜索一下",
        "麻烦搜索",
        "请帮我搜索一下",
        "请帮我搜索",
        "帮我搜索一下",
        "帮我搜索",
        "请搜索一下",
        "请搜索",
        "搜索一下",
        "搜索",
        "请帮我查一下",
        "帮我查一下",
        "请查一下",
        "查一下",
        "帮我查",
        "请帮我找一下",
        "帮我找一下",
        "请找一下",
        "找一下",
        "请帮我检索一下",
        "帮我检索一下",
        "请检索一下",
        "检索一下",
        "查找一下",
        "查询一下",
        "上网查一下",
        "网上查一下",
        "联网查一下",
        "联网搜索一下",
        "联网检索一下",
        "上网查",
        "网上查",
        "联网查",
        "联网搜索",
        "联网检索",
        "检索",
        "查找",
        "查询"
    )
    val trimmed = trimStart()
    val prefix = prefixes.firstOrNull { trimmed.startsWith(it, ignoreCase = true) } ?: return this
    return trimmed.drop(prefix.length).trimStart(' ', ':', '：', '-', '，', ',', '。', '.', '、')
}

private fun String.removeSearchPrefixIgnoreCase(prefix: String): String =
    if (startsWith(prefix, ignoreCase = true)) {
        drop(prefix.length).trimStart(' ', ':', '：', '-', '，', ',', '。', '.')
    } else {
        this
    }

private fun String.removeSearchAnswerInstructionSuffix(): String {
    var result = trim()
    val suffixPatterns = listOf(
        Regex("""[，,、；;。.\s]*(并)?(给我)?(附上|列出|标注)?(来源|引用|参考来源)$"""),
        Regex("""[，,、；;。.\s]*(并)?(给我)?(带上|附上|列出)(来源|引用|参考来源)$"""),
        Regex("""[，,、；;。.\s]*(要求|要)?(有|带)?(来源|引用|参考来源)$"""),
        Regex("""[，,、；;。.\s]*(用)?中文(回答|总结|说明)?$"""),
        Regex("""[，,、；;。.\s]*(回答|总结|说明|解释)(一下)?$"""),
        Regex("""[，,、；;。.\s]*(给我看看|告诉我)$""")
    )
    var changed: Boolean
    do {
        changed = false
        suffixPatterns.forEach { pattern ->
            val next = pattern.replace(result, "").trim()
            if (next != result && next.length >= 2) {
                result = next
                changed = true
            }
        }
    } while (changed)
    return result
}

fun WebSearchPlan.shouldUseWebSearchAutomatically(mode: WebSearchTriggerMode): Boolean =
    when (mode) {
        WebSearchTriggerMode.MANUAL -> false
        WebSearchTriggerMode.ALWAYS -> displayQuery.isNotBlank()
        WebSearchTriggerMode.SMART -> {
            if (userQuestion.isDeviceClockOnlyQuestion() && !explicitSearchRequested && directUrls.isEmpty()) {
                false
            } else {
                directUrls.isNotEmpty() ||
                    explicitSearchRequested ||
                    queries.size > 1 ||
                    userQuestion.hasSmartSearchHint()
            }
        }
    }

// P7/S4: shared with WebSearchPlanBuilding.kt.
internal fun buildWebSearchTriggerReasons(
    question: String,
    directUrls: List<String>,
    explicitSearchRequested: Boolean,
    queries: List<String>
): List<String> =
    buildList {
        if (directUrls.isNotEmpty()) add("包含 ${directUrls.size} 个网页链接")
        if (explicitSearchRequested) add("用户明确要求搜索/联网")
        if (question.isDeviceClockOnlyQuestion()) {
            add("仅询问设备当前日期、时间或时区，无需联网")
        } else if (question.containsAnySearchHint(freshnessSearchHints)) {
            add("包含实时或最新信息词")
        }
        if (question.containsAnySearchHint(officialSearchHints)) add("包含官网、文档、接口或下载类线索")
        if (question.containsAnySearchHint(comparisonSearchHints)) add("包含对比、推荐或选择类线索")
        if (question.containsAnySearchHint(factualSearchHints)) add("包含事实状态类查询线索")
        if (queries.size > 1) add("已扩展为 ${queries.size} 组检索词")
        if (isEmpty() && question.isNotBlank()) add("普通问题，未命中智能联网线索")
    }

// P7/S9: shared with WebSearchProvider.kt.
internal fun WebSearchPlan.webSearchDecisionReasons(
    config: WebSearchConfig,
    oneShotEnabled: Boolean,
    assistantWebSearchEnabled: Boolean
): List<String> =
    buildList {
        if (oneShotEnabled) add("本轮手动开启联网")
        if (assistantWebSearchEnabled) add("当前助手默认开启联网")
        when (config.triggerMode) {
            WebSearchTriggerMode.ALWAYS -> add("触发方式为始终联网")
            WebSearchTriggerMode.SMART -> add("触发方式为智能判断")
            WebSearchTriggerMode.MANUAL -> if (!oneShotEnabled && !assistantWebSearchEnabled) add("触发方式为手动")
        }
        addAll(triggerReasons)
        if (config.configured) {
            add("搜索服务已配置：${config.providerLabel}")
        } else if (config.canReadDirectUrls && directUrls.isNotEmpty()) {
            add("未配置搜索服务，仅读取网页链接")
        }
    }.distinct()

// P7/S4: shared with WebSearchPlanBuilding.kt.
internal fun String.hasExplicitSearchIntent(): Boolean {
    val text = lowercase(Locale.ROOT)
    return explicitSearchHints.any { text.contains(it.lowercase(Locale.ROOT)) }
}

private fun String.hasSmartSearchHint(): Boolean {
    if (isDeviceClockOnlyQuestion()) return false
    val text = lowercase(Locale.ROOT)
    return (freshnessSearchHints + officialSearchHints + comparisonSearchHints + researchSearchHints + factualSearchHints)
        .any { text.contains(it.lowercase(Locale.ROOT)) }
}

internal fun String.isDeviceClockOnlyQuestion(): Boolean {
    val normalized = lowercase(Locale.ROOT)
        .replace(Regex("[\\s，,。.!！?？；;：:、]+"), "")
        .trim()
    if (normalized.isBlank()) return false
    if (DEVICE_CLOCK_EXTERNAL_FACT_HINTS.any(normalized::contains)) return false
    return DEVICE_CLOCK_ONLY_PATTERNS.any { pattern -> pattern.matches(normalized) }
}

// P7/S4: shared with WebSearchPlanBuilding.kt.
internal fun String.containsAnySearchHint(hints: List<String>): Boolean {
    val text = lowercase(Locale.ROOT)
    return hints.any { text.contains(it.lowercase(Locale.ROOT)) }
}
