package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import java.util.UUID
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal suspend fun TuningProbeWorkerService.runFixedCanary(
        engine: McaInferenceService,
        purpose: String,
        prompt: String,
        params: GenerationParams,
        timeoutMs: Long
    ): FixedCanaryRun {
        val requestId = "tuning-$purpose-${UUID.randomUUID()}"
        val before = JSONObject(engine.nativeStatsJson())
        val beforeMemory = readMemoryPoint()
        val output = StringBuilder()
        var stats = RuntimeStats()
        var errorMessage: String? = null
        withTimeout(timeoutMs) {
            engine.streamChat(
                ChatRequest(
                    messages = listOf(ChatMessage(Role.USER, prompt)),
                    params = params
                ),
                LocalChatExecutionContext(requestId = requestId)
            ).collect { event ->
                when (event) {
                    is GenerateEvent.Phase -> stats = event.stats
                    is GenerateEvent.Persist -> Unit
                    is GenerateEvent.Chunk -> {
                        output.append(event.text)
                        stats = event.stats
                    }
                    is GenerateEvent.Done -> stats = event.stats
                    is GenerateEvent.Error -> {
                        errorMessage = LocalDiagnosticRedactor.sanitize(event.message, listOf(prompt))
                        stats = event.stats
                    }
                }
            }
        }
        val nativeStatsJson = engine.nativeStatsJson()
        val after = JSONObject(nativeStatsJson)
        val afterMemory = readMemoryPoint(stats)
        val safeNativeError = LocalDiagnosticRedactor.sanitize(stats.lastError, listOf(prompt))
        return FixedCanaryRun(
            purpose = purpose,
            requestId = requestId,
            sequenceBefore = before.optLong("generationSequence", -1L),
            sequenceAfter = after.optLong("generationSequence", -1L),
            output = output.toString(),
            promptFingerprint = LocalDiagnosticRedactor.promptFingerprint(prompt),
            error = errorMessage ?: safeNativeError.takeIf(String::isNotBlank),
            stats = stats,
            nativeStatsJson = nativeStatsJson,
            beforeMemory = beforeMemory,
            afterMemory = afterMemory
        )
    }

internal data class FixedCanaryRun(
    val purpose: String,
    val requestId: String,
    val sequenceBefore: Long,
    val sequenceAfter: Long,
    val output: String,
    val promptFingerprint: String,
    val error: String?,
    val stats: RuntimeStats,
    val nativeStatsJson: String,
    val beforeMemory: ProbeMemoryPoint,
    val afterMemory: ProbeMemoryPoint
)
