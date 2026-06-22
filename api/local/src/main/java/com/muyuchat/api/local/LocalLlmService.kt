package com.muyuchat.api.local

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class LocalLlmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val binder = object : ILocalLlmService.Stub() {
        override fun getLoadedModelJson(): String = LocalApiRuntime.loadedModelJsonProvider()

        override fun getParamsJson(): String = LocalApiRuntime.paramsJsonProvider()

        override fun getDeviceProfileJson(): String = LocalApiRuntime.deviceProfileJsonProvider()

        override fun getAgentRecommendationJson(requestJson: String): String =
            LocalApiRuntime.agentRecommendationJsonProvider(requestJson)

        override fun getMetricsJson(): String = LocalApiRuntime.engine?.nativeStatsJson() ?: "{}"

        override fun startChat(sessionId: String, requestJson: String, callback: ITokenCallback) {
            val engine = LocalApiRuntime.engine
            if (engine == null) {
                callback.onError(sessionId, "MCA engine is not attached.")
                return
            }
            scope.launch {
                runCatching {
                    engine.streamChat(parseChatRequest(requestJson)).collect { event ->
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
            scope.launch { LocalApiRuntime.engine?.stopGeneration() }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun parseChatRequest(json: String): ChatRequest {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return ChatRequest(listOf(ChatMessage(Role.USER, json)))
        val messages = root.optJSONArray("messages")?.toMessages()
            ?: listOf(ChatMessage(Role.USER, root.optString("prompt", json)))
        return ChatRequest(messages = messages, params = GenerationParams())
    }

    private fun JSONArray.toMessages(): List<ChatMessage> = buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val role = when (item.optString("role").lowercase()) {
                "system" -> Role.SYSTEM
                "assistant" -> Role.ASSISTANT
                else -> Role.USER
            }
            add(ChatMessage(role = role, content = item.optString("content")))
        }
    }
}

