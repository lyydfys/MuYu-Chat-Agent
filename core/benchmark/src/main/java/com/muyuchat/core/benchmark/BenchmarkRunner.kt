package com.muyuchat.core.benchmark

import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.core.tuning.TuningPlan
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

data class BenchmarkResult(
    val time: Long = System.currentTimeMillis(),
    val loadMs: Long = 0,
    val ttftMs: Long = 0,
    val promptTokens: Int = 0,
    val prefillMs: Long = 0,
    val prefillTps: Double = 0.0,
    val genTokens: Int = 0,
    val decodeMs: Long = 0,
    val decodeTps: Double = 0.0,
    val e2eTps: Double = 0.0,
    val cacheReuseHit: Boolean = false,
    val cacheReusedTokens: Int = 0,
    val cacheReuseReason: String? = null,
    val nativePssKb: Long = 0,
    val processRssKb: Long = 0,
    val nativeHeapKb: Long = 0,
    val javaHeapKb: Long = 0,
    val availMemKb: Long = 0,
    val totalMemKb: Long = 0,
    val modelMemoryBudgetKb: Long = 0,
    val isLowMemory: Boolean = false,
    val bestThreadCount: Int = 0,
    val threadResultsJson: String = "[]",
    val thermalDelta: Int = 0,
    val stable: Boolean = true,
    val error: String? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("time", time)
        .put("loadMs", loadMs)
        .put("ttftMs", ttftMs)
        .put("promptTokens", promptTokens)
        .put("prefillMs", prefillMs)
        .put("prefillTps", prefillTps)
        .put("genTokens", genTokens)
        .put("decodeMs", decodeMs)
        .put("decodeTps", decodeTps)
        .put("e2eTps", e2eTps)
        .put("cacheReuseHit", cacheReuseHit)
        .put("cacheReusedTokens", cacheReusedTokens)
        .put("cacheReuseReason", cacheReuseReason)
        .put("nativePssKb", nativePssKb)
        .put("processRssKb", processRssKb)
        .put("nativeHeapKb", nativeHeapKb)
        .put("javaHeapKb", javaHeapKb)
        .put("availMemKb", availMemKb)
        .put("totalMemKb", totalMemKb)
        .put("modelMemoryBudgetKb", modelMemoryBudgetKb)
        .put("isLowMemory", isLowMemory)
        .put("bestThreadCount", bestThreadCount)
        .put("threadResults", JSONArray(threadResultsJson))
        .put("thermalDelta", thermalDelta)
        .put("stable", stable)
        .put("error", error)
}

enum class BenchmarkSweepMode {
    QUICK,
    DEEP,
    POWER_SAVE
}

data class BenchmarkSweepConfig(
    val mode: BenchmarkSweepMode = BenchmarkSweepMode.QUICK,
    val repeatsPerCandidate: Int = 1,
    val maxPredictTokens: Int = 48,
    val refineRadius: Int = 1,
    val maxThreadCandidate: Int = 12
) {
    companion object {
        fun quick(): BenchmarkSweepConfig = BenchmarkSweepConfig(
            mode = BenchmarkSweepMode.QUICK,
            repeatsPerCandidate = 1,
            maxPredictTokens = 48,
            refineRadius = 1
        )

        fun deep(): BenchmarkSweepConfig = BenchmarkSweepConfig(
            mode = BenchmarkSweepMode.DEEP,
            repeatsPerCandidate = 3,
            maxPredictTokens = 96,
            refineRadius = 2
        )

        fun powerSave(): BenchmarkSweepConfig = BenchmarkSweepConfig(
            mode = BenchmarkSweepMode.POWER_SAVE,
            repeatsPerCandidate = 2,
            maxPredictTokens = 48,
            refineRadius = 0,
            maxThreadCandidate = 6
        )
    }
}

class BenchmarkRunner(
    private val engine: McaInferenceService,
    private val deviceProfileReader: DeviceProfileReader
) {
    suspend fun runThreadSweep(
        plan: TuningPlan? = null,
        candidates: List<Int> = defaultThreadCandidates(plan),
        config: BenchmarkSweepConfig = BenchmarkSweepConfig.quick()
    ): BenchmarkResult {
        val uniqueCandidates = candidates.filter { it in 1..config.maxThreadCandidate }.distinct()
        if (uniqueCandidates.isEmpty()) return runShortBenchmark(plan)

        val firstPass = benchmarkThreadCandidates(plan, uniqueCandidates, config)
        val firstBest = pickBestThreadResult(firstPass, config)
        val refinedCandidates = if (config.refineRadius > 0) {
            (-config.refineRadius..config.refineRadius).map { firstBest.first + it }
        } else {
            emptyList()
        }
            .filter { it in 1..config.maxThreadCandidate }
            .distinct()
            .filterNot { candidate -> firstPass.any { it.first == candidate } }
        val results = firstPass + benchmarkThreadCandidates(plan, refinedCandidates, config)
        val best = pickBestThreadResult(results, config)
        val threadResults = JSONArray()
        results.sortedBy { it.first }.forEach { (threads, result) ->
            threadResults.put(
                JSONObject()
                    .put("threads", threads)
                    .put("mode", config.mode.name)
                    .put("repeats", config.repeatsPerCandidate)
                    .put("decodeTps", result.decodeTps)
                    .put("prefillTps", result.prefillTps)
                    .put("ttftMs", result.ttftMs)
                    .put("decodeMs", result.decodeMs)
                    .put("genTokens", result.genTokens)
                    .put("score", scoreThreadResult(threads, result, config))
                    .put("stable", result.stable)
                    .put("error", result.error)
            )
        }
        return best.second.copy(
            bestThreadCount = best.first,
            threadResultsJson = threadResults.toString()
        )
    }

    private suspend fun benchmarkThreadCandidates(
        plan: TuningPlan?,
        candidates: List<Int>,
        config: BenchmarkSweepConfig
    ): List<Pair<Int, BenchmarkResult>> {
        return candidates.map { threads ->
            val candidatePlan = (plan ?: TuningPlan(
                nCtx = 4096,
                nPredict = config.maxPredictTokens,
                nThreads = threads,
                temperature = 0.6f,
                topK = 20,
                topP = 0.95f,
                minP = 0.0f,
                repeatPenalty = 1.0f,
                presencePenalty = 0.0f
            )).copy(
                nThreads = threads,
                nPredict = config.maxPredictTokens,
                temperature = 0.6f,
                topK = 20,
                topP = 0.95f,
                minP = 0.0f,
                repeatPenalty = 1.0f,
                presencePenalty = 0.0f
            )
            threads to runRepeatedBenchmark(candidatePlan, config)
        }
    }

    private suspend fun runRepeatedBenchmark(plan: TuningPlan, config: BenchmarkSweepConfig): BenchmarkResult {
        val runs = List(config.repeatsPerCandidate.coerceAtLeast(1)) {
            runShortBenchmark(plan, config.maxPredictTokens)
        }
        return aggregateRuns(plan.nThreads, runs, config)
    }

    private fun aggregateRuns(threads: Int, runs: List<BenchmarkResult>, config: BenchmarkSweepConfig): BenchmarkResult {
        val successful = runs.filter { it.error == null && it.decodeTps > 0.0 && it.stable }
        if (successful.isEmpty()) return runs.lastOrNull() ?: BenchmarkResult(bestThreadCount = threads, stable = false, error = "Benchmark did not run.")
        val sorted = successful.sortedBy { it.decodeTps }
        val median = sorted[sorted.size / 2]
        val avgTps = successful.map { it.decodeTps }.average()
        val avgTtft = successful.map { it.ttftMs }.average().toLong()
        val avgDecodeMs = successful.map { it.decodeMs }.average().toLong()
        val avgGenTokens = successful.map { it.genTokens }.average().toInt()
        val avgE2e = successful.map { it.e2eTps }.average()
        val thermalDelta = successful.maxOf { it.thermalDelta }
        val stable = successful.size == runs.size && thermalDelta <= if (config.mode == BenchmarkSweepMode.POWER_SAVE) 0 else 1
        val topRuns = successful.sortedByDescending { it.decodeTps }
        val deepTps = topRuns
            .take(if (topRuns.size >= 3) 2 else 1)
            .map { it.decodeTps }
            .average()
        return median.copy(
            ttftMs = avgTtft,
            genTokens = avgGenTokens.coerceAtLeast(median.genTokens),
            decodeMs = avgDecodeMs.coerceAtLeast(1),
            decodeTps = when (config.mode) {
                BenchmarkSweepMode.QUICK -> median.decodeTps
                BenchmarkSweepMode.DEEP -> deepTps
                BenchmarkSweepMode.POWER_SAVE -> avgTps
            },
            e2eTps = avgE2e,
            prefillMs = successful.map { it.prefillMs }.average().toLong(),
            prefillTps = successful.map { it.prefillTps }.average(),
            bestThreadCount = threads,
            thermalDelta = thermalDelta,
            stable = stable
        )
    }

    private fun pickBestThreadResult(
        results: List<Pair<Int, BenchmarkResult>>,
        config: BenchmarkSweepConfig = BenchmarkSweepConfig.quick()
    ): Pair<Int, BenchmarkResult> {
        val usable = results.filter { it.second.error == null && it.second.decodeTps > 0.0 }
        return usable.maxByOrNull { (threads, result) -> scoreThreadResult(threads, result, config) } ?: results.last()
    }

    private fun scoreThreadResult(
        threads: Int,
        result: BenchmarkResult,
        config: BenchmarkSweepConfig
    ): Double {
        if (result.error != null || result.decodeTps <= 0.0) return Double.NEGATIVE_INFINITY
        val ttftPenalty = result.ttftMs.coerceAtMost(5000L) / 5000.0
        val thermalPenalty = result.thermalDelta * when (config.mode) {
            BenchmarkSweepMode.QUICK -> 0.3
            BenchmarkSweepMode.DEEP -> 1.0
            BenchmarkSweepMode.POWER_SAVE -> 2.0
        }
        val stabilityPenalty = if (result.stable) 0.0 else when (config.mode) {
            BenchmarkSweepMode.QUICK -> 0.5
            BenchmarkSweepMode.DEEP -> 1.5
            BenchmarkSweepMode.POWER_SAVE -> 8.0
        }
        val threadPenalty = when (config.mode) {
            BenchmarkSweepMode.QUICK -> 0.0
            BenchmarkSweepMode.DEEP -> max(0, threads - 8) * 0.15
            BenchmarkSweepMode.POWER_SAVE -> threads.toDouble().pow(1.15) * 0.45
        }
        return result.decodeTps - ttftPenalty - thermalPenalty - threadPenalty - stabilityPenalty
    }

    suspend fun runShortBenchmark(plan: TuningPlan? = null, maxPredictTokens: Int = 48): BenchmarkResult {
        val params = plan?.toGenerationParams()?.copy(
            nPredict = plan.nPredict.coerceAtMost(maxPredictTokens),
            temperature = 0.6f,
            topK = 20,
            topP = 0.95f,
            minP = 0.0f,
            repeatPenalty = 1.0f,
            presencePenalty = 0.0f,
            reasoningMode = ReasoningMode.OFF,
            hideReasoning = true
        ) ?: GenerationParams(
            nPredict = maxPredictTokens,
            temperature = 0.6f,
            topK = 20,
            topP = 0.95f,
            minP = 0.0f,
            repeatPenalty = 1.0f,
            presencePenalty = 0.0f,
            systemPrompt = "You are MCA benchmark runner. Answer briefly.",
            reasoningMode = ReasoningMode.OFF,
            hideReasoning = true
        )
        return runBenchmark(params)
    }

    suspend fun runCurrentParamsBenchmark(params: GenerationParams, maxPredictTokens: Int = 48): BenchmarkResult {
        val benchmarkParams = params.copy(
            nPredict = params.nPredict.coerceIn(1, maxPredictTokens),
            systemPrompt = "You are MCA benchmark runner. Answer briefly."
        )
        return runBenchmark(benchmarkParams)
    }

    private suspend fun runBenchmark(params: GenerationParams): BenchmarkResult {
        val before = deviceProfileReader.read()
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(Role.SYSTEM, "You are MCA benchmark runner. Answer briefly."),
                ChatMessage(Role.USER, "用中文用一句话说明手机端本地大模型部署的一个优势。")
            ),
            params = params
        )
        var result = BenchmarkResult(error = "Benchmark did not complete.")
        engine.streamChat(request).collect { event ->
            when (event) {
                is GenerateEvent.Phase -> Unit
                is GenerateEvent.Chunk -> {
                    val stats = event.stats
                    result = BenchmarkResult(
                        loadMs = stats.loadMs,
                        ttftMs = stats.ttftMs,
                        promptTokens = stats.promptTokens,
                        prefillMs = stats.prefillMs,
                        prefillTps = stats.prefillTps,
                        genTokens = stats.completionTokens,
                        decodeMs = stats.decodeMs,
                        decodeTps = stats.decodeTps,
                        e2eTps = stats.e2eTps,
                        cacheReuseHit = stats.cacheReuseHit,
                        cacheReusedTokens = stats.cacheReusedTokens,
                        cacheReuseReason = stats.cacheReuseReason,
                        nativePssKb = stats.nativePssKb,
                        processRssKb = stats.processRssKb,
                        nativeHeapKb = stats.nativeHeapKb,
                        javaHeapKb = stats.javaHeapKb,
                        availMemKb = stats.availMemKb,
                        totalMemKb = stats.totalMemKb,
                        modelMemoryBudgetKb = stats.modelMemoryBudgetKb,
                        isLowMemory = stats.isLowMemory,
                        bestThreadCount = params.nThreads,
                        error = null
                    )
                }
                is GenerateEvent.Done -> {
                    val after = deviceProfileReader.read()
                    val stats = event.stats
                    result = BenchmarkResult(
                        loadMs = stats.loadMs,
                        ttftMs = stats.ttftMs,
                        promptTokens = stats.promptTokens,
                        prefillMs = stats.prefillMs,
                        prefillTps = stats.prefillTps,
                        genTokens = stats.completionTokens,
                        decodeMs = stats.decodeMs,
                        decodeTps = stats.decodeTps,
                        e2eTps = stats.e2eTps,
                        cacheReuseHit = stats.cacheReuseHit,
                        cacheReusedTokens = stats.cacheReusedTokens,
                        cacheReuseReason = stats.cacheReuseReason,
                        nativePssKb = stats.nativePssKb,
                        processRssKb = stats.processRssKb,
                        nativeHeapKb = stats.nativeHeapKb,
                        javaHeapKb = stats.javaHeapKb,
                        availMemKb = stats.availMemKb,
                        totalMemKb = stats.totalMemKb,
                        modelMemoryBudgetKb = stats.modelMemoryBudgetKb,
                        isLowMemory = stats.isLowMemory,
                        bestThreadCount = params.nThreads,
                        thermalDelta = abs(after.thermalStatus.ordinal - before.thermalStatus.ordinal),
                        stable = stats.lastError == null,
                        error = null
                    )
                }
                is GenerateEvent.Error -> {
                    result = BenchmarkResult(
                        nativePssKb = event.stats.nativePssKb,
                        processRssKb = event.stats.processRssKb,
                        nativeHeapKb = event.stats.nativeHeapKb,
                        javaHeapKb = event.stats.javaHeapKb,
                        availMemKb = event.stats.availMemKb,
                        totalMemKb = event.stats.totalMemKb,
                        modelMemoryBudgetKb = event.stats.modelMemoryBudgetKb,
                        isLowMemory = event.stats.isLowMemory,
                        bestThreadCount = params.nThreads,
                        stable = false,
                        error = event.message
                    )
                }
            }
        }
        return result
    }

    private fun defaultThreadCandidates(plan: TuningPlan?): List<Int> {
        val current = plan?.nThreads ?: Runtime.getRuntime().availableProcessors().coerceAtLeast(2) - 1
        return listOf(1, 2, 3, 4, current - 1, current, current + 1, 6, 8, 10)
            .filter { it in 1..12 }
            .distinct()
    }
}
