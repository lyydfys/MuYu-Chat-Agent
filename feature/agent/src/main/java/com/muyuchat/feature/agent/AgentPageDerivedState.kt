package com.muyuchat.feature.agent

import com.muyuchat.core.benchmark.BenchmarkResult

internal const val AGENT_PAGE_PRESENTATION_CACHE_LIMIT = 64

/**
 * Small access-order memo for page-private presentation work. The UI owns one instance per
 * composed AgentScreen; values are small presentation objects, so a fixed count is sufficient.
 */
internal class AgentPresentationMemo<K : Any, V : Any>(
    private val maxEntries: Int,
    private val transform: (K) -> V
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val entries = LinkedHashMap<K, V>(maxEntries, 0.75f, true)

    val size: Int
        get() = entries.size

    fun get(key: K): V {
        entries[key]?.let { return it }
        val value = transform(key)
        entries[key] = value
        if (entries.size > maxEntries) {
            val eldest = entries.entries.iterator()
            if (eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            }
        }
        return value
    }

    fun clear() {
        entries.clear()
    }
}

internal data class BenchmarkHistoryRowPresentation(
    val titleText: String,
    val detailText: String
)

internal data class AgentDecisionRowPresentation(
    val titleText: String,
    val detailText: String
)

internal data class TuningTrialRowPresentation(
    val headerText: String,
    val speedText: String,
    val detailText: String,
    val selected: Boolean
)

internal data class AgentBenchmarkLabels(
    val speedText: String,
    val ttftText: String,
    val firstTokenText: String,
    val stabilityText: String
)

internal data class AgentCurrentModelReportPresentation(
    val labels: AgentBenchmarkLabels?,
    val autoTuningSummary: String?
)

internal data class AgentDebugSummaryPresentation(
    val completionText: String,
    val labels: AgentBenchmarkLabels,
    val resourceText: String,
    val errorText: String?
)

internal data class AgentDebugDetailsPresentation(
    val collapsedText: String,
    val trialRows: List<TuningTrialRowPresentation>,
    val shortBenchmarkText: String,
    val explanationText: String?
)

internal data class AgentDiagnosticsPresentation(
    val summary: AgentDebugSummaryPresentation,
    val details: AgentDebugDetailsPresentation
)

internal data class AgentInfoResultPresentation(
    val speedText: String,
    val planText: String,
    val reasonText: String
)

internal data class AgentPagePresentations(
    val benchmarkHistoryRows: List<BenchmarkHistoryRowPresentation> = emptyList(),
    val decisionRows: List<AgentDecisionRowPresentation> = emptyList(),
    val currentModelReport: AgentCurrentModelReportPresentation? = null,
    val diagnostics: AgentDiagnosticsPresentation? = null
)

internal data class AgentCurrentModelReportKey(
    val benchmark: BenchmarkResult?,
    val autoTuningSummary: String?
)

internal data class AgentDiagnosticsKey(
    val benchmark: BenchmarkResult,
    val trials: List<TuningTrialItem>,
    val fallbackThreadCount: Int,
    val recommendationReason: String?,
    val recommendationExplanation: String?
)

internal data class AgentInfoResultKey(
    val benchmark: BenchmarkResult,
    val nCtx: Int,
    val nPredict: Int,
    val nThreads: Int,
    val recommendationReason: String,
    val explanation: String
)

internal fun benchmarkHistoryRowPresentation(item: BenchmarkHistoryItem): BenchmarkHistoryRowPresentation =
    BenchmarkHistoryRowPresentation(
        titleText = "${item.timeText} · ${shortName(item.modelName)}",
        detailText = "速度 ${"%.2f".format(item.decodeTps)} token/s · 首 token ${item.ttftMs}ms · " +
            "上下文 ${item.nCtx} · 线程 ${item.nThreads} · ${if (item.stable) "稳定" else "异常"}"
    )

internal fun agentDecisionRowPresentation(item: AgentDecisionItem): AgentDecisionRowPresentation =
    AgentDecisionRowPresentation(
        titleText = "${item.timeText} · ${item.title}",
        detailText = item.detail
    )

internal fun tuningTrialRowPresentation(item: TuningTrialItem): TuningTrialRowPresentation =
    TuningTrialRowPresentation(
        headerText = "threads=${item.threads}${if (item.selected) " · 最佳" else ""}",
        speedText = "${"%.2f".format(item.decodeTps)} token/s",
        detailText = "TTFT ${item.ttftMs}ms · 输出 ${item.genTokens} · ${if (item.stable) "稳定" else "异常"}",
        selected = item.selected
    )

private fun benchmarkLabels(benchmark: BenchmarkResult): AgentBenchmarkLabels =
    AgentBenchmarkLabels(
        speedText = "速度 ${"%.2f".format(benchmark.decodeTps)} token/s",
        ttftText = "TTFT ${benchmark.ttftMs}ms",
        firstTokenText = "首 token ${benchmark.ttftMs}ms",
        stabilityText = if (benchmark.stable) "稳定" else "需排查"
    )

internal fun currentModelReportPresentation(
    key: AgentCurrentModelReportKey
): AgentCurrentModelReportPresentation =
    AgentCurrentModelReportPresentation(
        labels = key.benchmark?.let(::benchmarkLabels),
        autoTuningSummary = key.autoTuningSummary
    )

internal fun diagnosticsPresentation(
    key: AgentDiagnosticsKey,
    trialPresentation: (TuningTrialItem) -> TuningTrialRowPresentation = ::tuningTrialRowPresentation
): AgentDiagnosticsPresentation {
    val labels = benchmarkLabels(key.benchmark)
    val bestThread = key.benchmark.bestThreadCount.takeIf { it > 0 } ?: key.fallbackThreadCount
    val rssKb = key.benchmark.processRssKb.takeIf { it > 0L } ?: key.benchmark.nativePssKb
    val reason = key.recommendationReason
    return AgentDiagnosticsPresentation(
        summary = AgentDebugSummaryPresentation(
            completionText = if (key.trials.isNotEmpty()) {
                "已完成 ${key.trials.size} 轮线程测试，最佳 $bestThread 线程。"
            } else {
                "已完成短基准测试。"
            },
            labels = labels,
            resourceText = "输出 ${key.benchmark.genTokens} token · 内存 ${formatAgentBytes(rssKb * 1024)} · " +
                "温控变化 ${key.benchmark.thermalDelta}",
            errorText = key.benchmark.error?.let { "问题：$it" }
        ),
        details = AgentDebugDetailsPresentation(
            collapsedText = reason ?: "展开后查看线程扫描和调参解释。",
            trialRows = key.trials.map(trialPresentation),
            shortBenchmarkText = "速度 ${"%.2f".format(key.benchmark.decodeTps)} token/s · " +
                "TTFT ${key.benchmark.ttftMs}ms · decode ${key.benchmark.decodeMs}ms",
            explanationText = key.recommendationExplanation?.let { explanation ->
                reason.orEmpty().ifBlank { explanation }
            }
        )
    )
}

internal fun agentInfoResultPresentation(key: AgentInfoResultKey): AgentInfoResultPresentation =
    AgentInfoResultPresentation(
        speedText = "本次实测速度 ${"%.2f".format(key.benchmark.decodeTps)} token/s，TTFT ${key.benchmark.ttftMs}ms。",
        planText = "推荐线程 ${key.nThreads}，上下文 ${key.nCtx}，回复长度 ${key.nPredict}。",
        reasonText = key.recommendationReason.ifBlank { key.explanation }
    )

internal fun formatAgentBytes(bytes: Long): String {
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    val mb = bytes / 1024.0 / 1024.0
    return if (gb >= 1.0) "%.2f GB".format(gb) else "%.0f MB".format(mb)
}

internal class AgentPagePresentationCache(
    maxEntries: Int = AGENT_PAGE_PRESENTATION_CACHE_LIMIT
) {
    private val benchmarkHistoryRows = AgentPresentationMemo(maxEntries, ::benchmarkHistoryRowPresentation)
    private val decisionRows = AgentPresentationMemo(maxEntries, ::agentDecisionRowPresentation)
    private val tuningTrialRows = AgentPresentationMemo(maxEntries, ::tuningTrialRowPresentation)
    private val currentModelReports = AgentPresentationMemo(2, ::currentModelReportPresentation)
    private val diagnostics = AgentPresentationMemo<AgentDiagnosticsKey, AgentDiagnosticsPresentation>(2) { key ->
        diagnosticsPresentation(key) { item -> tuningTrialRows.get(item) }
    }
    private val infoResults = AgentPresentationMemo(2, ::agentInfoResultPresentation)

    fun benchmarkHistory(items: List<BenchmarkHistoryItem>): List<BenchmarkHistoryRowPresentation> =
        items.map(benchmarkHistoryRows::get)

    fun decisions(items: List<AgentDecisionItem>): List<AgentDecisionRowPresentation> =
        items.map(decisionRows::get)

    fun currentModelReport(key: AgentCurrentModelReportKey): AgentCurrentModelReportPresentation =
        currentModelReports.get(key)

    fun diagnostics(key: AgentDiagnosticsKey): AgentDiagnosticsPresentation = diagnostics.get(key)

    fun infoResult(key: AgentInfoResultKey): AgentInfoResultPresentation = infoResults.get(key)
}

internal class AgentPagePresentationHolder(
    private val cache: AgentPagePresentationCache = AgentPagePresentationCache()
) {
    var current: AgentPagePresentations = AgentPagePresentations()
        private set

    var currentInfoResult: AgentInfoResultPresentation? = null
        private set

    fun refresh(
        benchmarkHistory: List<BenchmarkHistoryItem>,
        decisions: List<AgentDecisionItem>,
        currentModelReport: AgentCurrentModelReportKey,
        diagnostics: AgentDiagnosticsKey?
    ): AgentPagePresentations {
        current = AgentPagePresentations(
            benchmarkHistoryRows = cache.benchmarkHistory(benchmarkHistory),
            decisionRows = cache.decisions(decisions),
            currentModelReport = cache.currentModelReport(currentModelReport),
            diagnostics = diagnostics?.let(cache::diagnostics)
        )
        return current
    }

    fun refreshInfoResult(key: AgentInfoResultKey?): AgentInfoResultPresentation? {
        currentInfoResult = key?.let(cache::infoResult)
        return currentInfoResult
    }
}