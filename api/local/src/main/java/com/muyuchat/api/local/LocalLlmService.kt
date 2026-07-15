package com.muyuchat.api.local

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.LocalChatExecutionContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID

class LocalLlmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val binder = object : ILocalLlmService.Stub() {
        override fun getLoadedModelJson(): String = LocalApiRuntime.loadedModelJsonProvider()

        override fun getParamsJson(): String = LocalApiRuntime.paramsJsonProvider()

        override fun getDeviceProfileJson(): String = LocalApiRuntime.deviceProfileJsonProvider()

        override fun getAgentRecommendationJson(requestJson: String): String =
            LocalApiRuntime.agentRecommendationJsonProvider(requestJson)

        override fun getMetricsJson(): String = LocalApiRuntime.metricsJson()

        override fun startChat(sessionId: String, requestJson: String, callback: ITokenCallback) {
            val parsedResult = runCatching {
                OpenAiApiCompat.parseChatRequestChecked(
                    requestJson,
                    LocalApiRuntime.generationParamsProvider()
                )
            }.getOrElse { error ->
                callback.onError(
                    sessionId,
                    OpenAiApiCompat.errorJson(
                        "preflight_failed",
                        error.message ?: "Local API request preflight failed."
                    ).toString()
                )
                return
            }
            val request = when (
                val parsed = parsedResult
            ) {
                is OpenAiChatParseResult.Success -> parsed.request
                is OpenAiChatParseResult.Rejected -> {
                    callback.onError(
                        sessionId,
                        OpenAiApiCompat.errorJson(
                            parsed.rejection.code,
                            parsed.rejection.message,
                            parsed.rejection.detailsJson
                        ).toString()
                    )
                    return
                }
            }
            val preflightResult = runCatching {
                LocalApiRuntime.preflight(
                    LocalApiPreflightRequest(
                        route = "binder/startChat",
                        streaming = true,
                        requestedModel = OpenAiApiCompat.requestedModel(requestJson),
                        chatRequest = request
                    )
                )
            }.getOrElse { error ->
                callback.onError(
                    sessionId,
                    OpenAiApiCompat.errorJson(
                        "coordinator_preflight_failed",
                        error.message ?: "The runtime coordinator could not complete preflight."
                    ).toString()
                )
                return
            }
            when (val preflight = preflightResult) {
                LocalApiPreflightResult.Ready -> Unit
                is LocalApiPreflightResult.Rejected -> {
                    callback.onError(
                        sessionId,
                        OpenAiApiCompat.errorJson(
                            preflight.code,
                            preflight.message,
                            preflight.detailsJson
                        ).toString()
                    )
                    return
                }
            }
            val requestId = "binder-${UUID.randomUUID().toString().replace("-", "")}"
            val sequenceBefore = LocalApiRuntime.generationSequence()
            val stream = runCatching {
                LocalApiRuntime.streamChat(
                    request,
                    LocalChatExecutionContext(requestId = requestId)
                )
            }.getOrElse { error ->
                callback.onError(sessionId, error.message ?: "MCA engine could not start generation.")
                return
            }
            if (stream == null) {
                callback.onError(sessionId, "MCA engine is not attached.")
                return
            }
            scope.launch {
                var generationSequence: Long? = null
                runCatching {
                    stream.collect { event ->
                        generationSequence = generationSequence
                            ?: LocalApiRuntime.generationSequence()?.takeIf { current ->
                                sequenceBefore?.let { current > it } ?: (current > 0L)
                            }
                        generationSequence?.let {
                            LocalApiRuntime.recordGenerationSequence(requestId, it)
                        }
                        when (event) {
                            is GenerateEvent.Chunk -> callback.onChunk(sessionId, event.text)
                            is GenerateEvent.Done -> callback.onDone(sessionId)
                            is GenerateEvent.Error -> callback.onError(sessionId, event.message)
                        }
                    }
                }.onFailure { callback.onError(sessionId, it.message ?: "AIDL chat failed.") }
            }
        }

        override fun runBenchmark(requestJson: String, callback: ITokenCallback) {
            scope.launch {
                runCatching {
                    callback.onChunk("benchmark", LocalApiRuntime.benchmarkJsonProvider(requestJson))
                    callback.onDone("benchmark")
                }.onFailure { callback.onError("benchmark", it.message ?: "Benchmark failed.") }
            }
        }

        override fun stop(sessionId: String) {
            scope.launch { LocalApiRuntime.stopGeneration() }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

