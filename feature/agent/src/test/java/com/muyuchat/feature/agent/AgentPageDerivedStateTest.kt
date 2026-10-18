package com.muyuchat.feature.agent

import com.muyuchat.core.benchmark.BenchmarkResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPageDerivedStateTest {

    @Test
    fun equalBenchmarkHistoryRowsReuseTheSamePresentation() {
        val cache = AgentPagePresentationCache(maxEntries = 8)
        val item = historyItem(timeText = "12:00:00", modelName = "model-a")

        val first = cache.benchmarkHistory(listOf(item)).single()
        val second = cache.benchmarkHistory(listOf(item.copy())).single()

        assertSame(first, second)
    }

    @Test
    fun changingOneHistoryRowOnlyInvalidatesThatRow() {
        val cache = AgentPagePresentationCache(maxEntries = 8)
        val firstItem = historyItem(timeText = "12:00:00", modelName = "first")
        val secondItem = historyItem(timeText = "12:01:00", modelName = "second")
        val thirdItem = historyItem(timeText = "12:02:00", modelName = "third")
        val firstRows = cache.benchmarkHistory(listOf(firstItem, secondItem, thirdItem))

        val changedSecond = secondItem.copy(decodeTps = 19.75, stable = false)
        val secondRows = cache.benchmarkHistory(listOf(firstItem, changedSecond, thirdItem))

        assertSame(firstRows[0], secondRows[0])
        assertNotSame(firstRows[1], secondRows[1])
        assertSame(firstRows[2], secondRows[2])
        assertTrue(secondRows[1].detailText.contains("异常"))
        assertTrue(secondRows[1].detailText.contains("%.2f".format(changedSecond.decodeTps)))
    }

    @Test
    fun changingOneTrialOnlyInvalidatesThatTrialAndItsDiagnosticsVersion() {
        val cache = AgentPagePresentationCache(maxEntries = 8)
        val benchmark = BenchmarkResult(bestThreadCount = 8, decodeTps = 12.0, ttftMs = 80)
        val firstTrial = TuningTrialItem(4, 8.0, 120, 32, true, false)
        val secondTrial = TuningTrialItem(8, 12.0, 80, 40, true, true)
        val thirdTrial = TuningTrialItem(6, 10.0, 90, 36, true, false)
        val firstKey = diagnosticsKey(benchmark, listOf(firstTrial, secondTrial, thirdTrial))
        val first = cache.diagnostics(firstKey)

        val changedSecondTrial = secondTrial.copy(decodeTps = 11.5, stable = false)
        val second = cache.diagnostics(
            firstKey.copy(trials = listOf(firstTrial, changedSecondTrial, thirdTrial))
        )

        assertSame(first.details.trialRows[0], second.details.trialRows[0])
        assertNotSame(first.details.trialRows[1], second.details.trialRows[1])
        assertSame(first.details.trialRows[2], second.details.trialRows[2])
        assertTrue(second.details.trialRows[1].detailText.contains("异常"))
    }

    @Test
    fun equalDiagnosticVersionReusesSummaryAndDetails() {
        val cache = AgentPagePresentationCache(maxEntries = 8)
        val key = diagnosticsKey(
            BenchmarkResult(bestThreadCount = 6, decodeTps = 9.5, ttftMs = 75),
            listOf(TuningTrialItem(6, 9.5, 75, 24, true, true))
        )

        val first = cache.diagnostics(key)
        val second = cache.diagnostics(key.copy(trials = key.trials.toList()))

        assertSame(first, second)
    }

    @Test
    fun benchmarkVersionChangeInvalidatesCachedDiagnostics() {
        val cache = AgentPagePresentationCache(maxEntries = 8)
        val key = diagnosticsKey(
            BenchmarkResult(bestThreadCount = 6, decodeTps = 9.5, ttftMs = 75),
            emptyList()
        )

        val first = cache.diagnostics(key)
        val changedBenchmark = key.benchmark.copy(decodeTps = 13.25, stable = false)
        val second = cache.diagnostics(key.copy(benchmark = changedBenchmark))

        assertNotSame(first, second)
        assertTrue(second.summary.labels.speedText.contains("%.2f".format(13.25)))
        assertEquals("需排查", second.summary.labels.stabilityText)
    }

    @Test
    fun historyFormattingKeepsUnstableAndNonFiniteDisplay() {
        val row = benchmarkHistoryRowPresentation(
            historyItem(timeText = "12:00:00", modelName = "broken").copy(
                decodeTps = Double.NaN,
                stable = false
            )
        )

        assertTrue(row.detailText.contains("NaN"))
        assertTrue(row.detailText.contains("异常"))
    }

    @Test
    fun cachedLabelsPreserveExistingCopyAndMeasuredValues() {
        val benchmark = BenchmarkResult(
            decodeTps = 12.5,
            ttftMs = 80,
            genTokens = 32,
            processRssKb = 2048,
            thermalDelta = 3,
            stable = true
        )
        val current = currentModelReportPresentation(
            AgentCurrentModelReportKey(benchmark = benchmark, autoTuningSummary = "summary")
        )

        assertEquals("速度 ${"%.2f".format(benchmark.decodeTps)} token/s", current.labels?.speedText)
        assertEquals("首 token 80ms", current.labels?.firstTokenText)
        assertEquals("TTFT 80ms", current.labels?.ttftText)
        assertEquals("稳定", current.labels?.stabilityText)
        assertEquals("summary", current.autoTuningSummary)

        val diagnostics = diagnosticsPresentation(diagnosticsKey(benchmark, emptyList()))
        assertEquals("已完成短基准测试。", diagnostics.summary.completionText)
        assertEquals("输出 32 token · 内存 2 MB · 温控变化 3", diagnostics.summary.resourceText)
    }
    @Test
    fun memoEvictsLeastRecentlyUsedEntriesAtItsFixedLimit() {
        var transforms = 0
        val memo = AgentPresentationMemo<Int, String>(maxEntries = 2) { key ->
            transforms += 1
            "value-$key"
        }

        assertEquals("value-1", memo.get(1))
        assertEquals("value-2", memo.get(2))
        assertEquals("value-1", memo.get(1))
        assertEquals("value-3", memo.get(3))
        assertEquals(2, memo.size)
        assertEquals(3, transforms)

        assertEquals("value-2", memo.get(2))
        assertEquals(4, transforms)
    }

    private fun historyItem(timeText: String, modelName: String): BenchmarkHistoryItem =
        BenchmarkHistoryItem(
            timeText = timeText,
            modelName = modelName,
            decodeTps = 12.5,
            ttftMs = 90,
            nCtx = 4096,
            nThreads = 8,
            stable = true
        )

    private fun diagnosticsKey(
        benchmark: BenchmarkResult,
        trials: List<TuningTrialItem>
    ): AgentDiagnosticsKey = AgentDiagnosticsKey(
        benchmark = benchmark,
        trials = trials,
        fallbackThreadCount = 4,
        recommendationReason = "reason",
        recommendationExplanation = "explanation"
    )
}