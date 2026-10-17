package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.RuntimeMonotonicClock
import com.muyuchat.core.engine.SystemRuntimeMonotonicClock

internal suspend fun executeWebSearchForChatTurn(
    messages: List<ChatMessage>,
    config: WebSearchConfig,
    oneShotEnabled: Boolean,
    assistantWebSearchEnabled: Boolean,
    turnMode: WebSearchTurnMode = if (oneShotEnabled) WebSearchTurnMode.ON else WebSearchTurnMode.FOLLOW,
    allowPublicCheckSourceForProtocolTest: Boolean = false,
    search: suspend (WebSearchPlan, WebSearchConfig) -> WebSearchResult,
    beforeSearch: suspend (WebSearchPlan, List<String>) -> Unit = { _, _ -> },
    clock: RuntimeMonotonicClock = SystemRuntimeMonotonicClock
): WebSearchTurnOutcome {
    val plan = buildWebSearchPlanFromMessages(messages, config.researchMode)
    val searchConfig = if (allowPublicCheckSourceForProtocolTest) {
        config
    } else {
        config.withoutPublicCheckSearchSources()
    }
    val forceSearchForTurn = oneShotEnabled || turnMode == WebSearchTurnMode.ON
    val canRunPublicProtocolCheck = searchConfig.enabled &&
        allowPublicCheckSourceForProtocolTest &&
        searchConfig.isPublicCheckSource
    val canRunKeywordSearch = searchConfig.enabled &&
        (searchConfig.realSearchConfigured || canRunPublicProtocolCheck)
    val canReadDirectUrlsForTurn = config.canReadDirectUrls && plan.directUrls.isNotEmpty()
    val shouldSearch = when {
        forceSearchForTurn -> true
        turnMode == WebSearchTurnMode.OFF -> false
        else -> assistantWebSearchEnabled ||
            ((canRunKeywordSearch || canReadDirectUrlsForTurn) &&
                plan.shouldUseWebSearchAutomatically(config.triggerMode))
    }
    val triggerReasons = plan.webSearchDecisionReasons(
        config = searchConfig,
        oneShotEnabled = forceSearchForTurn,
        assistantWebSearchEnabled = assistantWebSearchEnabled
    )
    if (!shouldSearch) {
        return WebSearchTurnOutcome(
            plan = plan,
            requested = false,
            searched = false,
            success = false
        )
    }
    val query = plan.displayQuery
    if (!canRunKeywordSearch && config.isPublicCheckSource && !canReadDirectUrlsForTurn) {
        return WebSearchTurnOutcome(
            plan = plan,
            requested = true,
            searched = false,
            success = false,
            webSearchStatusMessage = WEB_SEARCH_PUBLIC_CHECK_WARNING,
            statusMessage = WEB_SEARCH_PUBLIC_CHECK_WARNING
        )
    }
    if (!canRunKeywordSearch && !canReadDirectUrlsForTurn) {
        return WebSearchTurnOutcome(
            plan = plan,
            requested = true,
            searched = false,
            success = false,
            webSearchStatusMessage = if (plan.directUrls.isNotEmpty()) {
                "联网检索未启用，本轮将直接回答"
            } else {
                "联网检索未配置，本轮将直接回答"
            },
            statusMessage = if (plan.directUrls.isNotEmpty()) {
                "联网检索未启用，本轮将直接回答"
            } else {
                "联网检索未配置，本轮将直接回答"
            }
        )
    }
    if (query.isBlank()) {
        return WebSearchTurnOutcome(
            plan = plan,
            requested = true,
            searched = false,
            success = false,
            webSearchStatusMessage = "没有可用于搜索的问题，本轮将直接回答",
            statusMessage = "没有可用于搜索的问题"
        )
    }

    beforeSearch(plan, triggerReasons)
    val started = clock.nowMs()
    return runCatching { search(plan, searchConfig) }
        .fold(
            onSuccess = { result ->
                if (result.documents.isNotEmpty()) {
                    val promptContext = result.toPromptContext()
                    val sourceReferences = result.sourceReferences
                    val closedLoopChecks = result.toClosedLoopChecks(
                        promptContext = promptContext,
                        sourceReferences = sourceReferences
                    )
                    val message = buildString {
                        append("已检索 ${result.documents.size} 个来源")
                        if (result.searchedQueries.size > 1) append(" · ${result.searchedQueries.size} 组检索词")
                        if (result.directUrls.isNotEmpty()) append(" · ${result.directUrls.size} 个网页")
                        append(" · ${result.providerLabel}")
                    }
                    WebSearchTurnOutcome(
                        plan = plan,
                        requested = true,
                        searched = true,
                        success = true,
                        promptContext = promptContext,
                        sourceReferences = sourceReferences,
                        diagnostic = result.toDiagnosticRecord(
                            config = searchConfig,
                            success = true,
                            message = message,
                            triggerReasons = triggerReasons,
                            closedLoopChecks = closedLoopChecks
                        ),
                        webSearchStatusMessage = message,
                        statusMessage = "已注入联网来源"
                    )
                } else {
                    val message = "没有找到可靠来源，本轮将直接回答"
                    val closedLoopChecks = result.toClosedLoopChecks(
                        promptContext = "",
                        sourceReferences = emptyList()
                    )
                    WebSearchTurnOutcome(
                        plan = plan,
                        requested = true,
                        searched = true,
                        success = false,
                        diagnostic = result.toDiagnosticRecord(
                            config = searchConfig,
                            success = false,
                            message = message,
                            triggerReasons = triggerReasons,
                            closedLoopChecks = closedLoopChecks
                        ),
                        webSearchStatusMessage = message,
                        statusMessage = "联网检索无结果"
                    )
                }
            },
            onFailure = { error ->
                val message = "联网检索失败：${error.friendlyWebSearchFailureMessage()}"
                WebSearchTurnOutcome(
                    plan = plan,
                    requested = true,
                    searched = true,
                    success = false,
                    diagnostic = plan.toFailedDiagnosticRecord(
                        config = searchConfig,
                        elapsedMs = (clock.nowMs() - started).coerceAtLeast(0L),
                        message = message,
                        closedLoopChecks = listOf(
                            "已生成检索计划",
                            "搜索请求失败，未生成可用来源",
                            "未生成模型联网上下文",
                            "未生成来源卡片数据"
                        )
                    ),
                    webSearchStatusMessage = message,
                    statusMessage = "联网检索失败，本轮将直接回答"
                )
            }
        )
}
