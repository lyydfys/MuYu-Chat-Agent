package com.muyuchat.core.engine

import android.content.Context
import com.muyuchat.core.telemetry.MemorySnapshot
import com.muyuchat.core.nativebridge.NativeLlamaBridge
import com.muyuchat.core.telemetry.RuntimeMetrics
import com.muyuchat.core.telemetry.SocDetector
import com.muyuchat.core.telemetry.TelemetryLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlin.math.max

class McaInferenceService(
    context: Context,
    private val bridge: NativeLlamaBridge = NativeLlamaBridge(),
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutex = Mutex()
    private val telemetry = TelemetryLogger(context.applicationContext)
    private val socInfo = SocDetector.detect()
    private val appContext = context.applicationContext
    private val _stats = MutableStateFlow(RuntimeStats(backend = "cpu", loaded = false))

    val stats = _stats.asStateFlow()

    init {
        if (NativeLlamaBridge.isAvailable) {
            bridge.initBackends(appContext.applicationInfo.nativeLibraryDir)
        } else {
            _stats.value = _stats.value.copy(lastError = NativeLlamaBridge.loadError?.message)
        }
    }

    suspend fun loadModel(modelPath: String, params: LoadParams = LoadParams()): Result<RuntimeStats> = withContext(io) {
        runCatching {
            stopGeneration()
            val started = System.currentTimeMillis()
            val rc = bridge.loadModel(modelPath, params.toJson())
            if (rc != 0) {
                val nativeStats = nativeStatsJson()
                val nativeError = runCatching {
                    JSONObject(nativeStats).optString("lastError").takeIf { it.isNotBlank() }
                }.getOrNull()
                error(
                    buildString {
                        append("Native loadModel failed: ").append(rc)
                        if (!nativeError.isNullOrBlank()) append("；").append(nativeError.trim())
                    }
                )
            }
            val loadMs = System.currentTimeMillis() - started
            val memory = telemetry.memorySnapshotDetailed()
            val nativeStats = runCatching { JSONObject(nativeStatsJson()) }.getOrNull()
            val stats = RuntimeStats(
                loaded = true,
                modelPath = modelPath,
                backend = nativeStats?.optString("backend")?.takeIf { it.isNotBlank() } ?: "cpu",
                loadMs = loadMs,
                nThreads = nativeStats?.optInt("nThreads") ?: params.nThreads,
                nThreadsBatch = nativeStats?.optInt("nThreadsBatch") ?: params.nThreads,
                nBatch = nativeStats?.optInt("nBatch") ?: 0,
                nUbatch = nativeStats?.optInt("nUbatch") ?: 0,
                backendDevices = nativeStats?.optJSONArray("backendDevices")?.toString() ?: "[]"
            ).withMemory(memory)
            _stats.value = stats
            stats
        }
    }

    suspend fun unloadModel() = withContext(io) {
        stopGeneration()
        bridge.unloadModel()
        _stats.value = RuntimeStats(loaded = false, backend = "cpu")
    }

    fun streamChat(request: ChatRequest): Flow<GenerateEvent> = flow {
        mutex.withLock {
            val current = _stats.value
            if (!current.loaded) {
                val errorStats = current.copy(lastError = "No GGUF model is loaded.")
                emit(GenerateEvent.Error("请先在模型页加载一个 GGUF 模型。", errorStats))
                return@withLock
            }
            val memoryBeforeGenerate = telemetry.memorySnapshotDetailed()
            if (memoryBeforeGenerate.availMemKb in 1 until LOW_MEMORY_START_GUARD_KB) {
                val message = "当前可用内存过低（约 ${formatMb(memoryBeforeGenerate.availMemKb)}），已拦截本轮生成。请关闭后台应用、降低上下文或换更小模型。"
                val errorStats = current.copy(
                    lastError = message
                ).withMemory(memoryBeforeGenerate)
                _stats.value = errorStats
                emit(GenerateEvent.Error(message, errorStats))
                return@withLock
            }

            val protected = protectContext(request)
            if (protected.error != null) {
                val errorStats = current.copy(lastError = protected.error)
                _stats.value = errorStats
                emit(GenerateEvent.Error(protected.error, errorStats))
                return@withLock
            }
            val activeRequest = protected.request

            val started = System.currentTimeMillis()
            val beginRc = withContext(io) {
                bridge.beginCompletion(activeRequest.messagesJson(), activeRequest.params.toJson())
            }
            if (beginRc != 0) {
                val nativeError = runCatching {
                    JSONObject(nativeStatsJson()).optString("lastError").takeIf { it.isNotBlank() }
                }.getOrNull()
                val message = buildString {
                    append("Native beginCompletion failed: ").append(beginRc)
                    if (!nativeError.isNullOrBlank()) append("；").append(nativeError.trim())
                }
                val errorStats = _stats.value.copy(lastError = message)
                emit(GenerateEvent.Error(message, errorStats))
                return@withLock
            }

            var firstTokenAt = 0L
            var lastTokenAt = started
            var generatedChunks = 0
            var generatedTokens = 0
            var finalStats = _stats.value
            var latestMemory = memoryBeforeGenerate
            var lastStatsSampleAt = 0L
            var lastMemorySampleAt = started
            var cachedNativeStats: JSONObject? = null
            val reasoningFilter = ReasoningContentFilter()
            val reasoningLoopGuard = ReasoningLoopGuard()
            val hideReasoning = request.params.hideReasoning || request.params.reasoningMode == ReasoningMode.OFF
            var reasoningStartedAt = 0L
            var reasoningDurationMs = 0L

            try {
                while (true) {
                    val chunk = withContext(io) { bridge.generateNextChunk() } ?: break
                    if (chunk.isBlank()) continue
                    val now = System.currentTimeMillis()
                    if (firstTokenAt == 0L) firstTokenAt = now
                    lastTokenAt = now
                    generatedChunks += 1
                    generatedTokens += estimateTokens(chunk)
                    val shouldSampleStats = cachedNativeStats == null ||
                        now - lastStatsSampleAt >= STATS_SAMPLE_INTERVAL_MS
                    val nativeStats = if (shouldSampleStats) {
                        lastStatsSampleAt = now
                        runCatching { JSONObject(nativeStatsJson()) }.getOrNull()
                            ?.also { cachedNativeStats = it }
                    } else {
                        cachedNativeStats
                    }
                    val nativeCompletionTokens = if (shouldSampleStats) {
                        nativeStats?.optInt("completionTokens")?.takeIf { it > 0 }
                    } else {
                        null
                    }
                    val nativePromptTokens = if (shouldSampleStats) {
                        nativeStats?.optInt("promptTokens")?.takeIf { it > 0 }
                    } else {
                        null
                    }
                    if (nativeCompletionTokens != null) generatedTokens = nativeCompletionTokens
                    val ttft = firstTokenAt - started
                    val decodeMs = if (shouldSampleStats) {
                        nativeStats?.optLong("decodeMs")?.takeIf { it > 0L }
                    } else {
                        null
                    }
                        ?: max(1L, lastTokenAt - firstTokenAt)
                    val totalMs = max(1L, lastTokenAt - started)
                    if (now - lastMemorySampleAt >= MEMORY_SAMPLE_INTERVAL_MS) {
                        latestMemory = telemetry.memorySnapshotDetailed()
                        lastMemorySampleAt = now
                    }
                    finalStats = _stats.value.copy(
                        promptTokens = nativePromptTokens ?: estimatePromptTokens(activeRequest),
                        completionTokens = generatedTokens,
                        ttftMs = ttft,
                        prefillMs = if (shouldSampleStats) {
                            nativeStats?.optLong("prefillMs") ?: _stats.value.prefillMs
                        } else {
                            _stats.value.prefillMs
                        },
                        decodeMs = decodeMs,
                        decodeTps = if (shouldSampleStats) {
                            nativeStats?.optDouble("decodeTps")?.takeIf { it > 0.0 }
                        } else {
                            null
                        }
                            ?: (generatedTokens * 1000.0 / decodeMs),
                        e2eTps = generatedTokens * 1000.0 / totalMs,
                        nThreads = nativeStats?.optInt("nThreads") ?: _stats.value.nThreads,
                        nThreadsBatch = nativeStats?.optInt("nThreadsBatch") ?: _stats.value.nThreadsBatch,
                        nBatch = nativeStats?.optInt("nBatch") ?: _stats.value.nBatch,
                        nUbatch = nativeStats?.optInt("nUbatch") ?: _stats.value.nUbatch,
                        backendDevices = nativeStats?.optJSONArray("backendDevices")?.toString() ?: _stats.value.backendDevices,
                        lastError = null
                    ).withMemory(latestMemory)
                    if (shouldSampleStats) {
                        _stats.value = finalStats
                    }
                    if (latestMemory.availMemKb in 1 until LOW_MEMORY_RUNTIME_STOP_KB) {
                        val message = "生成过程中可用内存降到 ${formatMb(latestMemory.availMemKb)}，已停止生成以避免系统回收或崩溃。建议降低 n_ctx / n_predict 或关闭后台应用。"
                        bridge.requestStop()
                        val errorStats = finalStats.copy(lastError = message)
                        _stats.value = errorStats
                        writeLog(errorStats, activeRequest.params, error = message)
                        emit(GenerateEvent.Error(message, errorStats))
                        return@withLock
                    }
                    val filtered = reasoningFilter.filter(chunk)
                    var stopForReasoningLoop = false
                    if (filtered.reasoning.isNotBlank() && !hideReasoning) {
                        if (reasoningStartedAt == 0L) reasoningStartedAt = now
                        reasoningDurationMs = now - reasoningStartedAt
                        stopForReasoningLoop = reasoningLoopGuard.shouldStop(filtered.reasoning)
                        if (stopForReasoningLoop) {
                            bridge.requestStop()
                        }
                    }
                    if (filtered.visible.isNotBlank() || (filtered.reasoning.isNotBlank() && !hideReasoning)) {
                        emit(
                            GenerateEvent.Chunk(
                                text = filtered.visible,
                                stats = finalStats,
                                reasoning = if (hideReasoning) "" else filtered.reasoning,
                                reasoningDurationMs = reasoningDurationMs
                            )
                        )
                    } else if (filtered.reasoning.isNotBlank() && hideReasoning && generatedTokens % HIDDEN_REASONING_PROGRESS_STEP_TOKENS == 0) {
                        emit(
                            GenerateEvent.Chunk(
                                text = "",
                                stats = finalStats,
                                hiddenReasoning = true
                            )
                        )
                    }
                    if (stopForReasoningLoop) break
                }
                val finalNativeStats = runCatching { JSONObject(nativeStatsJson()) }.getOrNull()
                finalStats = mergeNativeStats(
                    base = finalStats,
                    nativeStats = finalNativeStats,
                    memory = telemetry.memorySnapshotDetailed(),
                    started = started,
                    lastTokenAt = lastTokenAt,
                    request = activeRequest
                )
                _stats.value = finalStats
                val remaining = reasoningFilter.finish()
                if (remaining.reasoning.isNotBlank() && !hideReasoning) {
                    val now = System.currentTimeMillis()
                    if (reasoningStartedAt == 0L) reasoningStartedAt = now
                    reasoningDurationMs = now - reasoningStartedAt
                }
                if (remaining.visible.isNotBlank() || (remaining.reasoning.isNotBlank() && !hideReasoning)) {
                    emit(
                        GenerateEvent.Chunk(
                            text = remaining.visible,
                            stats = finalStats,
                            reasoning = if (hideReasoning) "" else remaining.reasoning,
                            reasoningDurationMs = reasoningDurationMs
                        )
                    )
                }
                writeLog(finalStats, activeRequest.params, error = null)
                emit(GenerateEvent.Done(finalStats))
            } catch (t: Throwable) {
                bridge.requestStop()
                if (t is CancellationException) throw t
                val errorStats = _stats.value.copy(lastError = t.message)
                _stats.value = errorStats
                writeLog(errorStats, activeRequest.params, error = t.message)
                emit(GenerateEvent.Error(t.message ?: "Generation failed.", errorStats))
            }
        }
    }

    suspend fun stopGeneration() = withContext(io) {
        runCatching { bridge.requestStop() }
    }

    fun nativeStatsJson(): String = runCatching { bridge.getRuntimeStatsJson() }.getOrElse {
        JSONObject().put("error", it.message).toString()
    }

    fun recentLogs(limit: Int = 200): List<RuntimeMetrics> = telemetry.recent(limit)

    private fun writeLog(stats: RuntimeStats, params: GenerationParams, error: String?) {
        telemetry.append(
            RuntimeMetrics(
                model = stats.modelPath.orEmpty(),
                backend = stats.backend,
                soc = socInfo.family.name.lowercase(),
                promptTokens = stats.promptTokens,
                genTokens = stats.completionTokens,
                loadMs = stats.loadMs,
                ttftMs = stats.ttftMs,
                prefillMs = stats.prefillMs,
                decodeMs = stats.decodeMs,
                decodeTps = stats.decodeTps,
                e2eTps = stats.e2eTps,
                nativePssKb = stats.nativePssKb,
                processRssKb = stats.processRssKb,
                nativeHeapKb = stats.nativeHeapKb,
                nativeHeapSizeKb = stats.nativeHeapSizeKb,
                javaHeapKb = stats.javaHeapKb,
                availMemKb = stats.availMemKb,
                totalMemKb = stats.totalMemKb,
                advertisedMemKb = stats.advertisedMemKb,
                memoryThresholdKb = stats.memoryThresholdKb,
                isLowMemory = stats.isLowMemory,
                procMemAvailableKb = stats.procMemAvailableKb,
                procMemFreeKb = stats.procMemFreeKb,
                cachedKb = stats.cachedKb,
                reclaimableKb = stats.reclaimableKb,
                modelMemoryBudgetKb = stats.modelMemoryBudgetKb,
                params = params.toJson(),
                error = error
            )
        )
    }

    private fun estimateTokens(text: String): Int = max(1, text.length / 2)

    private fun estimatePromptTokens(request: ChatRequest): Int =
        request.messages.sumOf { estimateTokens(it.content) } +
            estimateTokens(request.params.systemPrompt) +
            REASONING_INSTRUCTION_ESTIMATE_TOKENS

    private fun promptReserveTokens(nCtx: Int): Int =
        (nCtx / 8).coerceIn(MIN_RESERVED_OUTPUT_TOKENS, MAX_PROMPT_RESERVE_TOKENS)

    private fun protectContext(request: ChatRequest): ContextProtection {
        val nCtx = request.params.nCtx.coerceAtLeast(MIN_CONTEXT_TOKENS)
        val reservedOutput = promptReserveTokens(nCtx)
        val promptBudget = nCtx - reservedOutput - CONTEXT_HEADROOM_TOKENS
        if (promptBudget < MIN_PROMPT_BUDGET_TOKENS) {
            return ContextProtection(
                request = request,
                error = "上下文预算过小：n_ctx=$nCtx。请提高 n_ctx，或缩短上传文件/历史对话。"
            )
        }

        val systemMessages = request.messages.filter { it.role == Role.SYSTEM }
        val turnMessages = request.messages.filterNot { it.role == Role.SYSTEM }
        val systemTokens = systemMessages.sumOf { estimateTokens(it.content) } +
            estimateTokens(request.params.systemPrompt) +
            REASONING_INSTRUCTION_ESTIMATE_TOKENS
        val latestTurn = turnMessages.lastOrNull()
        if (latestTurn != null && systemTokens + estimateTokens(latestTurn.content) > promptBudget) {
            return ContextProtection(
                request = request,
                error = "当前输入约 ${estimateTokens(latestTurn.content)} token，超过本机安全上下文预算。请缩短上传文件/问题，或在参数页提高 n_ctx。"
            )
        }

        val estimated = systemTokens + turnMessages.sumOf { estimateTokens(it.content) }
        if (estimated <= promptBudget) return ContextProtection(request)

        var used = systemTokens
        val keptReversed = mutableListOf<ChatMessage>()
        for (message in turnMessages.asReversed()) {
            val cost = estimateTokens(message.content)
            if (used + cost <= promptBudget) {
                keptReversed += message
                used += cost
            }
        }
        if (keptReversed.isEmpty() && turnMessages.isNotEmpty()) {
            return ContextProtection(
                request = request,
                error = "历史上下文过长，且最新消息无法放入当前 n_ctx=$nCtx。请新建对话或降低文件长度。"
            )
        }
        val trimmedMessages = systemMessages + keptReversed.asReversed()
        return ContextProtection(
            request = request.copy(messages = trimmedMessages),
            trimmedMessages = request.messages.size - trimmedMessages.size
        )
    }

    private fun formatMb(kb: Long): String = "%.0f MB".format(kb / 1024.0)

    private fun mergeNativeStats(
        base: RuntimeStats,
        nativeStats: JSONObject?,
        memory: MemorySnapshot,
        started: Long,
        lastTokenAt: Long,
        request: ChatRequest
    ): RuntimeStats {
        val completionTokens = nativeStats?.optInt("completionTokens")?.takeIf { it > 0 }
            ?: base.completionTokens
        val decodeMs = nativeStats?.optLong("decodeMs")?.takeIf { it > 0L }
            ?: base.decodeMs.takeIf { it > 0L }
            ?: max(1L, lastTokenAt - started)
        val totalMs = max(1L, lastTokenAt - started)
        return base.copy(
            backend = nativeStats?.optString("backend")?.takeIf { it.isNotBlank() } ?: base.backend,
            promptTokens = nativeStats?.optInt("promptTokens")?.takeIf { it > 0 }
                ?: base.promptTokens.takeIf { it > 0 }
                ?: estimatePromptTokens(request),
            completionTokens = completionTokens,
            prefillMs = nativeStats?.optLong("prefillMs") ?: base.prefillMs,
            decodeMs = decodeMs,
            decodeTps = nativeStats?.optDouble("decodeTps")?.takeIf { it > 0.0 }
                ?: (completionTokens * 1000.0 / decodeMs),
            e2eTps = completionTokens * 1000.0 / totalMs,
            nThreads = nativeStats?.optInt("nThreads") ?: base.nThreads,
            nThreadsBatch = nativeStats?.optInt("nThreadsBatch") ?: base.nThreadsBatch,
            nBatch = nativeStats?.optInt("nBatch") ?: base.nBatch,
            nUbatch = nativeStats?.optInt("nUbatch") ?: base.nUbatch,
            backendDevices = nativeStats?.optJSONArray("backendDevices")?.toString() ?: base.backendDevices,
            lastError = null
        ).withMemory(memory)
    }

    private fun RuntimeStats.withMemory(memory: MemorySnapshot): RuntimeStats = copy(
        nativePssKb = memory.processPssKb,
        processRssKb = memory.processRssKb,
        nativeHeapKb = memory.nativeHeapKb,
        nativeHeapSizeKb = memory.nativeHeapSizeKb,
        javaHeapKb = memory.javaHeapKb,
        availMemKb = memory.availMemKb,
        totalMemKb = memory.totalMemKb,
        advertisedMemKb = memory.advertisedMemKb,
        memoryThresholdKb = memory.memoryThresholdKb,
        isLowMemory = memory.isLowMemory,
        procMemAvailableKb = memory.procMemAvailableKb,
        procMemFreeKb = memory.procMemFreeKb,
        cachedKb = memory.cachedKb,
        reclaimableKb = memory.reclaimableKb,
        modelMemoryBudgetKb = memory.modelMemoryBudgetKb
    )

    private data class ContextProtection(
        val request: ChatRequest,
        val error: String? = null,
        val trimmedMessages: Int = 0
    )

    private companion object {
        private const val MIN_CONTEXT_TOKENS = 512
        private const val CONTEXT_HEADROOM_TOKENS = 96
        private const val MIN_RESERVED_OUTPUT_TOKENS = 64
        private const val MIN_PROMPT_BUDGET_TOKENS = 256
        private const val REASONING_INSTRUCTION_ESTIMATE_TOKENS = 96
        private const val MAX_PROMPT_RESERVE_TOKENS = 1024
        private const val LOW_MEMORY_START_GUARD_KB = 384L * 1024L
        private const val LOW_MEMORY_RUNTIME_STOP_KB = 256L * 1024L
        private const val HIDDEN_REASONING_PROGRESS_STEP_TOKENS = 16
        private const val STATS_SAMPLE_INTERVAL_MS = 250L
        private const val MEMORY_SAMPLE_INTERVAL_MS = 1000L
    }
}

