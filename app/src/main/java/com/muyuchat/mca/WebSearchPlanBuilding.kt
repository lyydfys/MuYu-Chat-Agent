package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role

/**
 * Web-search plan building (P7/S4), moved verbatim from WebSearchProvider.kt: the public plan
 * builders and the provider-run gate. Pure read-only helpers it needs were widened private->internal
 * in place (hasExplicitSearchIntent, cleanSearchQuery, smartSearchQueries, containsAnySearchHint,
 * researchSearchHints, buildWebSearchTriggerReasons); same-package resolution keeps every caller and
 * behavioral test call unchanged.
 */

fun buildSearchQueryFromMessages(messages: List<ChatMessage>): String =
    buildWebSearchPlanFromMessages(messages).userQuestion

fun buildWebSearchPlanFromMessages(
    messages: List<ChatMessage>,
    researchMode: WebSearchResearchMode = WebSearchResearchMode.AUTO
): WebSearchPlan =
    buildWebSearchPlan(
        rawInput = messages.lastOrNull { it.role == Role.USER }
            ?.content
            .orEmpty(),
        researchMode = researchMode
    )

fun buildWebSearchPlan(
    rawInput: String,
    researchMode: WebSearchResearchMode = WebSearchResearchMode.AUTO
): WebSearchPlan {
    val cleanInput = rawInput
        .replace(Regex("""【上传文件：[^】]+】"""), " ")
        .replace(Regex("""【上传图片：[^】]+】"""), " ")
        .lineSequence()
        .filterNot { it.trim().startsWith("content://") || it.trim().startsWith("file://") }
        .joinToString(" ")
        .normalizeWhitespace()
    val explicitSearchRequested = cleanInput.hasExplicitSearchIntent()
    val directUrls = cleanInput.extractHttpUrls().take(3)
    val question = cleanInput
        .replace(urlRegex, " ")
        .cleanSearchQuery()
    val queries = question.smartSearchQueries(researchMode)
    val usesResearchPlan = question.isNotBlank() &&
        researchMode != WebSearchResearchMode.OFF &&
        (
            researchMode == WebSearchResearchMode.DEEP ||
                question.containsAnySearchHint(researchSearchHints) ||
                queries.size >= 4
            )
    val triggerReasons = buildWebSearchTriggerReasons(
        question = question,
        directUrls = directUrls,
        explicitSearchRequested = explicitSearchRequested,
        queries = queries
    ).let { reasons ->
        when {
            researchMode == WebSearchResearchMode.DEEP && question.isNotBlank() ->
                (reasons + "研究模式为深度研究，已启用多源综合").distinct()
            usesResearchPlan ->
                (reasons + "包含研究、评测、方案或多源综合线索").distinct()
            researchMode == WebSearchResearchMode.OFF && question.containsAnySearchHint(researchSearchHints) ->
                (reasons + "研究模式为普通检索，已限制为轻量查询").distinct()
            else -> reasons
        }
    }
    return WebSearchPlan(
        userQuestion = question,
        queries = queries,
        directUrls = directUrls,
        reason = when {
            directUrls.isNotEmpty() && queries.isNotEmpty() -> "url+query"
            directUrls.isNotEmpty() -> "url"
            usesResearchPlan -> "research"
            queries.size > 1 -> "expanded"
            else -> "single"
        },
        explicitSearchRequested = explicitSearchRequested,
        triggerReasons = triggerReasons
    )
}

// P7/S2: shared with WebSearchDiagnosticConversion.kt.
internal fun WebSearchPlan.shouldRunSearchProvider(config: WebSearchConfig): Boolean {
    if (!config.configured) return false
    if (directUrls.isEmpty()) return true
    return explicitSearchRequested || queries.size >= 4
}
