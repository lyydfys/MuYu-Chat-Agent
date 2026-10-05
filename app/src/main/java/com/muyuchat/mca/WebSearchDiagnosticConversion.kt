package com.muyuchat.mca

import com.muyuchat.core.engine.ChatWebSearchTrace

/**
 * Web-search diagnostic conversion (P7/S2), moved verbatim from WebSearchProvider.kt: the pure
 * converters between search results/plans and diagnostic records/traces, plus the failure-health
 * reason mapper. Shared helpers were widened private->internal in place
 * (WEB_SEARCH_DIRECT_READ_LABEL, shouldRunSearchProvider, toWebSearchSourceTrustSummary,
 * toWebSearchHealthSignal); nothing else was touched, so every caller and behavioral test call is
 * unchanged.
 */

internal fun WebSearchResult.toDiagnosticRecord(
    config: WebSearchConfig,
    success: Boolean,
    message: String,
    triggerReasons: List<String> = emptyList(),
    closedLoopChecks: List<String> = emptyList()
): WebSearchDiagnosticRecord =
    qualityReport.let { quality ->
        val health = healthReport
        val research = researchReport
        WebSearchDiagnosticRecord(
            providerLabel = providerLabel,
            triggerModeLabel = config.triggerMode.label,
            query = query,
            searchedQueries = searchedQueries,
            directUrls = directUrls,
            sourceCount = documents.size,
            elapsedMs = elapsedMs,
            success = success,
            message = message,
            topSources = sourceReferences.take(3),
            healthScore = health.score,
            healthLabel = health.label,
            healthReasons = health.reasons,
            qualityScore = quality.score,
            qualityLabel = quality.label,
            qualityReasons = quality.reasons,
            sourceTrustSummary = documents.toWebSearchSourceTrustSummary(),
            researchConfidenceScore = research.confidenceScore,
            researchConfidenceLabel = research.confidenceLabel,
            researchEvidenceGroups = research.evidenceGroups,
            researchConflictWarnings = research.conflictWarnings,
            researchSynthesisGuidance = research.synthesisGuidance,
            triggerReasons = triggerReasons,
            warnings = warnings,
            cacheStatus = cacheStatus
                .ifBlank { if (searchedQueries.isNotEmpty()) "实时检索" else "" },
            closedLoopChecks = closedLoopChecks
        )
    }

internal fun WebSearchDiagnosticRecord.toChatWebSearchTrace(): ChatWebSearchTrace =
    ChatWebSearchTrace(
        query = query,
        providerLabel = providerLabel,
        triggerModeLabel = triggerModeLabel,
        running = false,
        searchedQueries = searchedQueries,
        directUrls = directUrls,
        sourceCount = sourceCount,
        elapsedMs = elapsedMs,
        success = success,
        message = message,
        healthScore = healthScore,
        healthLabel = healthLabel,
        qualityScore = qualityScore,
        qualityLabel = qualityLabel,
        researchConfidenceScore = researchConfidenceScore,
        researchConfidenceLabel = researchConfidenceLabel,
        evidenceGroups = researchEvidenceGroups,
        conflictWarnings = researchConflictWarnings,
        synthesisGuidance = researchSynthesisGuidance,
        triggerReasons = triggerReasons,
        warnings = warnings,
        cacheStatus = cacheStatus,
        closedLoopChecks = closedLoopChecks
    )

internal fun WebSearchPlan.toPendingChatWebSearchTrace(
    config: WebSearchConfig,
    triggerReasons: List<String> = this.triggerReasons
): ChatWebSearchTrace {
    val shouldRunSearchProvider = shouldRunSearchProvider(config)
    val plannedQueries = if (shouldRunSearchProvider) queries else emptyList()
    val plannedTargets = (plannedQueries + directUrls).distinct()
    val targetSummary = buildString {
        if (plannedQueries.isNotEmpty()) append("${plannedQueries.size} 组检索词")
        if (directUrls.isNotEmpty()) {
            if (isNotBlank()) append(" · ")
            append("${directUrls.size} 个网页")
        }
    }.ifBlank { "1 个检索目标" }
    return ChatWebSearchTrace(
        query = displayQuery,
        providerLabel = when {
            shouldRunSearchProvider && directUrls.isNotEmpty() -> "$WEB_SEARCH_DIRECT_READ_LABEL + ${config.providerLabel}"
            shouldRunSearchProvider -> config.providerLabel
            else -> WEB_SEARCH_DIRECT_READ_LABEL
        },
        triggerModeLabel = config.triggerMode.label,
        running = true,
        stageLabel = "检索中",
        searchedQueries = if (shouldRunSearchProvider) {
            queries.ifEmpty { listOf(displayQuery).filter { it.isNotBlank() } }
        } else {
            emptyList()
        },
        directUrls = directUrls,
        success = false,
        message = "正在联网检索：$targetSummary",
        triggerReasons = triggerReasons.ifEmpty { this.triggerReasons },
        closedLoopChecks = buildList {
            add("已生成检索计划：$targetSummary")
            if (config.fetchPageContent) add("将读取可用网页摘要并过滤网页指令")
            if (plannedTargets.isNotEmpty()) add("待执行目标：${plannedTargets.take(4).joinToString("；")}")
        }
    )
}

internal fun WebSearchPlan.toFailedDiagnosticRecord(
    config: WebSearchConfig,
    elapsedMs: Long,
    message: String,
    closedLoopChecks: List<String> = emptyList()
): WebSearchDiagnosticRecord =
    WebSearchDiagnosticRecord(
        providerLabel = config.providerLabel,
        triggerModeLabel = config.triggerMode.label,
        query = displayQuery,
        searchedQueries = queries.ifEmpty { listOf(displayQuery).filter { it.isNotBlank() } },
        directUrls = directUrls,
        elapsedMs = elapsedMs,
        success = false,
        message = message,
        healthScore = 0,
        healthLabel = "失败",
        healthReasons = message.toWebSearchFailureHealthReasons(),
        triggerReasons = triggerReasons,
        closedLoopChecks = closedLoopChecks
    )

private fun String.toWebSearchFailureHealthReasons(): List<String> =
    buildList {
        add("搜索链路未完成")
        toWebSearchHealthSignal()?.let { add(it.label) }
        if (contains("配置", ignoreCase = true)) add("搜索服务配置不完整")
        if (contains("搜索问题为空", ignoreCase = true)) add("搜索问题为空")
    }.distinct()
