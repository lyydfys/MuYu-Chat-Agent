package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentModelUsage
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.mca.CloudApiConfig
import com.muyuchat.mca.addCloudApiKeyHeaders
import com.muyuchat.mca.agent.inference.BoundedSseReader.Companion.MAX_ATTEMPT_CHARS
import com.muyuchat.mca.agent.inference.BoundedSseReader.Companion.MAX_SSE_EVENT_CHARS
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Prompted wire for OpenAI-compatible `chat/completions` (typed adapter for OPENAI_COMPATIBLE).
 *
 * The Agent prompt already carries the whole transcript and tool catalog, so this wire sends no
 * tool declarations and no native call items: the assistant message is the prompted envelope that
 * [promptedToolText] and the prompted loop decode. Strict terminal rules match the Responses
 * adapter: success requires `[DONE]` or a chunk that states `finish_reason`; a truncated
 * (`length`) or filtered turn is a typed failure, and EOF before a terminal is never success. An
 * idle provider stream fails at the same bounded window as the Responses transport.
 */
internal class OpenAiChatCompletionsBoundedTransport(
    client: OkHttpClient,
    private val idleTimeoutMillis: Long = DEFAULT_CLOUD_STREAM_IDLE_TIMEOUT_MILLIS
) : AgentCloudStreamTransport {
    private val streamClient: OkHttpClient = client.withStreamIdleTimeout(idleTimeoutMillis)

    override suspend fun stream(
        request: AgentCloudStreamRequest,
        onCall: (AgentCloudCallHandle) -> Unit,
        onVisibleDelta: (String) -> Unit
    ): AgentCloudStreamTerminal = withContext(Dispatchers.IO) {
        val call = streamClient.newCall(
            agentChatCompletionsHttpRequest(
                config = request.config,
                chatRequest = request.chatRequest,
                promptedJson = request.promptedJson
            )
        )
        onCall(AgentCloudCallHandle { call.cancel() })
        try {
            call.execute().use { response ->
                val body = response.body
                    ?: return@withContext AgentCloudStreamTerminal.Failed(
                        AgentErrorCodes.MODEL_TERMINAL_UNVERIFIED,
                        "云端 chat/completions 接口没有返回内容。"
                    )
                if (!response.isSuccessful) {
                    val errorText = runCatching {
                        BoundedSseReader(
                            body.charStream().buffered(),
                            MAX_SSE_EVENT_CHARS,
                            MAX_ATTEMPT_CHARS
                        ).readAllBounded()
                    }.getOrDefault("")
                    return@withContext AgentCloudStreamTerminal.Failed(
                        AgentErrorCodes.MODEL_PROTOCOL_ERROR,
                        "云端 chat/completions 接口错误 ${response.code}: ${errorText.take(200)}",
                        AgentRetryability.AFTER_USER_ACTION
                    )
                }
                val reader = BoundedSseReader(
                    body.charStream().buffered(),
                    request.maxSseEventChars,
                    request.maxAttemptChars
                )
                val decoder = AgentChatCompletionsStreamDecoder()
                try {
                    while (!decoder.finished) {
                        currentCoroutineContext().ensureActive()
                        val event = reader.nextEvent() ?: break
                        decoder.accept(event)
                        decoder.drainVisible(onVisibleDelta)
                    }
                } catch (limit: AgentStreamLimitExceeded) {
                    return@withContext AgentCloudStreamTerminal.Failed(
                        AgentErrorCodes.MODEL_PROTOCOL_ERROR,
                        limit.message ?: "云端响应超过 Agent 接收上限。",
                        AgentRetryability.NEVER
                    )
                }
                decoder.terminal()
            }
        } catch (timeout: java.net.SocketTimeoutException) {
            if (call.isCanceled()) {
                AgentCloudStreamTerminal.Cancelled
            } else {
                AgentCloudStreamTerminal.Failed(
                    AgentErrorCodes.MODEL_TERMINAL_UNVERIFIED,
                    "云端 chat/completions 在 ${idleTimeoutMillis / 1000} 秒内没有发送任何数据，未取得终止事件。",
                    AgentRetryability.SAFE_RETRY
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            if (call.isCanceled()) AgentCloudStreamTerminal.Cancelled
            else AgentCloudStreamTerminal.EofBeforeTerminal("")
        }
    }
}

/**
 * Accumulates one chat/completions SSE stream into exactly one terminal.
 *
 * Only `delta.content` reaches the prompted decoder; reasoning channels are ignored by contract.
 * A chunk that cannot be parsed is skipped: the prompted envelope is the single authority on what
 * the turn means, and providers legitimately interleave keep-alive frames.
 */
internal class AgentChatCompletionsStreamDecoder {
    private val visible = StringBuilder()
    private var emittedChars = 0
    private var usage: AgentModelUsage? = null
    private var finishReason: String? = null
    private var failure: AgentCloudStreamTerminal.Failed? = null
    private var sawDone = false

    /** True once no further input can change the terminal. */
    val finished: Boolean
        get() = sawDone || failure != null

    fun accept(event: AgentSseEvent) {
        if (finished) return
        val data = event.data.trim()
        if (data.isEmpty()) return
        if (data == "[DONE]") {
            sawDone = true
            return
        }
        val root = runCatching { JSONObject(data) }.getOrNull() ?: return
        root.optJSONObject("error")?.let { error ->
            failure = AgentCloudStreamTerminal.Failed(
                AgentErrorCodes.MODEL_PROTOCOL_ERROR,
                (error.optString("message").ifBlank { "云端返回错误" }).take(200),
                AgentRetryability.SAFE_RETRY
            )
            return
        }
        usage = parseUsage(root.optJSONObject("usage")) ?: usage
        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
        val text = delta?.opt("content")
        if (text is String && text.isNotEmpty()) visible.append(text)
        choice.optString("finish_reason").takeIf { it.isNotBlank() }?.let { finishReason = it }
    }

    /** Hands each newly appended character to the sink exactly once. */
    fun drainVisible(onVisibleDelta: (String) -> Unit) {
        if (visible.length <= emittedChars) return
        val delta = visible.substring(emittedChars)
        emittedChars = visible.length
        if (delta.isNotEmpty()) onVisibleDelta(delta)
    }

    fun terminal(): AgentCloudStreamTerminal {
        failure?.let { return it }
        val text = visible.toString()
        return when {
            finishReason == "length" -> AgentCloudStreamTerminal.Failed(
                AgentErrorCodes.MODEL_TERMINAL_UNVERIFIED,
                "云端回答被长度上限截断，不授权后续动作。",
                AgentRetryability.SAFE_RETRY
            )

            finishReason == "content_filter" -> AgentCloudStreamTerminal.Failed(
                AgentErrorCodes.MODEL_TERMINAL_UNVERIFIED,
                "云端回答被内容策略中断。",
                AgentRetryability.AFTER_USER_ACTION
            )

            finishReason != null || sawDone -> AgentCloudStreamTerminal.Completed(text, usage)

            else -> AgentCloudStreamTerminal.EofBeforeTerminal(text)
        }
    }

    private fun parseUsage(raw: JSONObject?): AgentModelUsage? {
        if (raw == null) return null
        val input = raw.optInt("prompt_tokens", -1).takeIf { it >= 0 }
        val output = raw.optInt("completion_tokens", -1).takeIf { it >= 0 }
        if (input == null && output == null) return null
        return AgentModelUsage(
            inputTokens = input,
            outputTokens = output,
            estimated = input == null || output == null
        )
    }
}

/**
 * Exact chat/completions request for one Agent attempt.
 *
 * `stream` and `include_usage` are unconditional: this wire only exists for the streaming Agent
 * path, and usage must arrive on the same terminal. [promptedJson] asks for a JSON object because
 * the prompted envelope is exactly one root object.
 */
internal fun agentChatCompletionsHttpRequest(
    config: CloudApiConfig,
    chatRequest: ChatRequest,
    promptedJson: Boolean
): Request {
    val params = chatRequest.params
    val body = JSONObject()
        .put("model", config.chatModel.trim())
        .put("messages", org.json.JSONArray(chatRequest.messagesJson(multimodal = false)))
        .put("stream", true)
        .put("stream_options", JSONObject().put("include_usage", true))
        .put("temperature", params.temperature.toDouble())
        .put("top_p", params.topP.toDouble())
        .put("max_tokens", params.effectiveNPredict().coerceIn(1, 32768))
        .also { root ->
            if (promptedJson) root.put("response_format", JSONObject().put("type", "json_object"))
            if (params.stopWords.isNotEmpty()) {
                root.put("stop", org.json.JSONArray(params.stopWords))
            }
        }
    return Request.Builder()
        .url(agentChatCompletionsUrl(config.baseUrl))
        .addHeader("Accept", "text/event-stream")
        .addCloudApiKeyHeaders(config)
        .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
        .build()
}

internal fun agentChatCompletionsUrl(baseUrl: String): String {
    val base = baseUrl.trim().trimEnd('/')
    return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
