package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeMonotonicClock
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.SystemRuntimeMonotonicClock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI-compatible cloud chat transport (P7/S25), moved verbatim out of CloudChatProvider.kt:
 * chat SSE streaming, quick test, token usage and stats. Consumed by MainViewModel and the
 * cloud chat tests in the same package; configuration/store/image stay in their own files.
 */

class OpenAiCompatibleChatProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val clock: RuntimeMonotonicClock = SystemRuntimeMonotonicClock
) {
    private val quickClient = client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(16, TimeUnit.SECONDS)
        .build()

    fun streamChat(
        config: CloudApiConfig,
        request: ChatRequest
    ): Flow<GenerateEvent> = if (config.apiFormat == CloudApiFormat.OPENAI_RESPONSES) {
        streamOpenAiResponsesChat(client, config, request)
    } else flow {
        if (!config.configured) {
            emit(GenerateEvent.Error("云端模型未配置完整。请填写协议、Base URL、模型名和必要的 API Key。", cloudStats(config)))
            return@flow
        }
        val startedAt = clock.nowMs()
        var firstChunkAt: Long? = null
        var completionChars = 0
        val estimatedPromptTokens = estimateCloudPromptTokens(request)
        var usage = CloudTokenUsage()

        executeChatRequest(config, request) { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                emit(GenerateEvent.Error("云端接口错误 ${response.code}: ${parseErrorMessage(errorBody)}", cloudStats(config)))
                return@executeChatRequest
            }
            val body = response.body ?: run {
                emit(GenerateEvent.Error("云端接口没有返回内容", cloudStats(config)))
                return@executeChatRequest
            }
            var sawStreamData = false
            val nonStreamBody = StringBuilder()
            body.byteStream().bufferedReader().use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val rawLine = reader.readLine() ?: break
                    val line = rawLine.trim()
                    if (!line.startsWith("data:")) {
                        if (!sawStreamData && line.isNotBlank() && nonStreamBody.length < MAX_NON_STREAM_BODY_CHARS) {
                            nonStreamBody.append(line).append('\n')
                        }
                        continue
                    }
                    sawStreamData = true
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val chunk = parseStreamChunk(config.apiFormat, data) ?: continue
                    usage = usage.merge(chunk.usage)
                    chunk.error?.let { error ->
                        emit(GenerateEvent.Error(error, cloudStats(config)))
                        return@executeChatRequest
                    }
                    if (chunk.done) break
                    val visibleText = chunk.text
                    val reasoningText = if (request.params.reasoningMode == ReasoningMode.OFF) {
                        ""
                    } else {
                        chunk.reasoning
                    }
                    completionChars += visibleText.length + chunk.reasoning.length
                    if (visibleText.isEmpty() && reasoningText.isEmpty()) continue
                    if (firstChunkAt == null) firstChunkAt = clock.nowMs()
                    emit(
                        GenerateEvent.Chunk(
                            text = visibleText,
                            reasoning = reasoningText,
                            reasoningDurationMs = 0L,
                            stats = cloudStats(
                                config = config,
                                startedAt = startedAt,
                                firstChunkAt = firstChunkAt,
                                estimatedPromptTokens = estimatedPromptTokens,
                                completionChars = completionChars,
                                usage = usage
                            )
                        )
                    )
                }
            }
            if (!sawStreamData) {
                val fallback = parseNonStreamResponse(config.apiFormat, nonStreamBody.toString())
                if (fallback == null) {
                    emit(GenerateEvent.Error("云端接口没有返回可解析的 SSE 或 JSON 内容。请确认协议、模型名和 Base URL。", cloudStats(config)))
                    return@executeChatRequest
                }
                fallback.error?.let { error ->
                    emit(GenerateEvent.Error(error, cloudStats(config)))
                    return@executeChatRequest
                }
                usage = usage.merge(fallback.usage)
                val visibleText = fallback.text
                val reasoningText = if (request.params.reasoningMode == ReasoningMode.OFF) {
                    ""
                } else {
                    fallback.reasoning
                }
                completionChars += visibleText.length + fallback.reasoning.length
                if (visibleText.isNotEmpty() || reasoningText.isNotEmpty()) {
                    if (firstChunkAt == null) firstChunkAt = clock.nowMs()
                    emit(
                        GenerateEvent.Chunk(
                            text = visibleText,
                            reasoning = reasoningText,
                            reasoningDurationMs = 0L,
                            stats = cloudStats(
                                config = config,
                                startedAt = startedAt,
                                firstChunkAt = firstChunkAt,
                                estimatedPromptTokens = estimatedPromptTokens,
                                completionChars = completionChars,
                                usage = usage,
                                cachedInputTokens = usage.cachedInput,
                                streaming = false
                            )
                        )
                    )
                }
                val finishedAt = clock.nowMs()
                emit(
                    GenerateEvent.Done(
                        cloudStats(
                            config = config,
                            startedAt = startedAt,
                            firstChunkAt = firstChunkAt,
                            finishedAt = finishedAt,
                            estimatedPromptTokens = estimatedPromptTokens,
                            completionChars = completionChars,
                            usage = usage,
                            cachedInputTokens = usage.cachedInput,
                            streaming = false
                        )
                    )
                )
                return@executeChatRequest
            }
            val finishedAt = clock.nowMs()
            emit(
                GenerateEvent.Done(
                    cloudStats(
                        config = config,
                        startedAt = startedAt,
                        firstChunkAt = firstChunkAt,
                        finishedAt = finishedAt,
                        estimatedPromptTokens = estimatedPromptTokens,
                        completionChars = completionChars,
                        usage = usage,
                        cachedInputTokens = usage.cachedInput
                    )
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    suspend fun test(config: CloudApiConfig): Result<Unit> = runCatching {
        var failed: String? = null
        streamChat(
            config = config,
            request = ChatRequest(
                messages = listOf(ChatMessage(Role.USER, "ping")),
                params = GenerationParams(nPredict = 8, temperature = 0f, reasoningMode = ReasoningMode.OFF)
            )
        ).collect { event ->
            if (event is GenerateEvent.Error) failed = event.message
        }
        failed?.let { error(it) }
    }

    suspend fun quickTest(config: CloudApiConfig): Result<Unit> = if (config.apiFormat == CloudApiFormat.OPENAI_RESPONSES) {
        quickTestOpenAiResponses(quickClient, config)
    } else withContext(Dispatchers.IO) {
        runCatching {
            if (!config.configured) {
                error("云端模型未配置完整。请填写协议、Base URL、模型名和必要的 API Key。")
            }
            quickClient.newCall(buildQuickTestHttpRequest(config)).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("云端接口错误 ${response.code}: ${parseErrorMessage(body)}")
                }
                val parsedError = runCatching { JSONObject(body).jsonError() }.getOrNull()
                if (!parsedError.isNullOrBlank()) {
                    error(parsedError)
                }
            }
        }
    }

    /**
     * Fetches the provider's model-id list from the standard `/models` endpoint.
     *
     * Only the Base URL (and key when the provider needs one) is required — the model name is what
     * the caller is trying to discover. OpenAI-compatible and Responses gateways use
     * `GET {base}/models` with a Bearer key; Anthropic uses `GET {base}/v1/models` with its own
     * headers. The draft config comes straight from the editor form, before any save.
     */
    suspend fun listModels(config: CloudApiConfig): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = config.baseUrl.trim().trimEnd('/')
            if (baseUrl.isBlank()) {
                error("请先填写 Base URL，再获取模型列表。")
            }
            val request = if (config.apiFormat == CloudApiFormat.ANTHROPIC) {
                Request.Builder()
                    .url(anthropicModelsUrl(baseUrl))
                    .addHeader("x-api-key", config.apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("Accept", "application/json")
                    .get()
                    .build()
            } else {
                Request.Builder()
                    .url(openAiModelsUrl(baseUrl))
                    .addHeader("Accept", "application/json")
                    .addCloudApiKeyHeaders(config)
                    .get()
                    .build()
            }
            quickClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("云端接口错误 ${response.code}: ${parseErrorMessage(body)}")
                }
                parseModelsResponse(body)
            }
        }
    }

    private fun openAiModelsUrl(baseUrl: String): String =
        if (baseUrl.endsWith("/models")) baseUrl else "$baseUrl/models"

    private fun anthropicModelsUrl(baseUrl: String): String = when {
        baseUrl.endsWith("/v1/models") -> baseUrl
        baseUrl.endsWith("/models") -> baseUrl
        baseUrl.endsWith("/v1") -> "$baseUrl/models"
        baseUrl.contains("anthropic.com", ignoreCase = true) -> "$baseUrl/v1/models"
        else -> "$baseUrl/models"
    }

    /** Accepts `data[].id` (OpenAI/Anthropic) plus lenient `models[]` shapes used by gateways. */
    private fun parseModelsResponse(body: String): List<String> {
        if (body.isBlank()) error("接口没有返回模型列表。")
        val root = runCatching { JSONObject(body) }.getOrElse { error("接口返回的不是有效 JSON。") }
        val models = mutableListOf<String>()
        fun collect(array: JSONArray?) {
            if (array == null) return
            for (index in 0 until array.length()) {
                when (val item = array.opt(index)) {
                    is JSONObject -> item.optString("id")
                        .takeIf { it.isNotBlank() && it != "null" }
                        ?.let(models::add)
                    is String -> item.trim().takeIf { it.isNotBlank() }?.let(models::add)
                }
            }
        }
        collect(root.optJSONArray("data"))
        collect(root.optJSONArray("models"))
        val unique = models.distinct().take(MAX_LISTED_MODELS)
        if (unique.isEmpty()) {
            error("接口返回的模型列表为空，请手动填写模型名。")
        }
        return unique
    }

    private fun buildHttpRequest(config: CloudApiConfig, request: ChatRequest): Request =
        when (config.apiFormat) {
            CloudApiFormat.OPENAI_COMPATIBLE -> openAiRequest(config, request)
            CloudApiFormat.ANTHROPIC -> anthropicRequest(config, request)
            CloudApiFormat.OPENAI_RESPONSES -> responsesHttpRequest(config, request)
        }

    private fun buildQuickTestHttpRequest(config: CloudApiConfig): Request =
        when (config.apiFormat) {
            CloudApiFormat.OPENAI_COMPATIBLE -> quickOpenAiRequest(config)
            CloudApiFormat.ANTHROPIC -> quickAnthropicRequest(config)
            CloudApiFormat.OPENAI_RESPONSES -> responsesHttpRequest(config,
                ChatRequest(listOf(ChatMessage(Role.USER, "ping")), GenerationParams(nPredict = 16)), stream = false)
        }

    private fun chatEndpointUrl(baseUrl: String, path: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val endpointPath = path.trim().trim('/')
        return if (base.endsWith("/$endpointPath")) base else "$base/$endpointPath"
    }

    private fun anthropicMessagesUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.endsWith("/v1/messages") -> base
            base.contains("anthropic.com", ignoreCase = true) && base.endsWith("/messages") ->
                "${base.removeSuffix("/messages")}/v1/messages"
            base.endsWith("/messages") -> base
            base.endsWith("/v1") -> "$base/messages"
            base.contains("anthropic.com", ignoreCase = true) -> "$base/v1/messages"
            else -> "$base/messages"
        }
    }

    private suspend fun <T> executeChatRequest(
        config: CloudApiConfig,
        request: ChatRequest,
        read: suspend (Response) -> T
    ): T = withCancellableCloudCall(client.newCall(buildHttpRequest(config, request))) { response ->
        if (config.apiFormat == CloudApiFormat.OPENAI_COMPATIBLE && response.code in listOf(400, 422)) {
            val error = response.peekBody(65_536L).string().lowercase()
            // Older compatible gateways may reject the optional usage field. Retry only
            // an explicit validation rejection, before any assistant output was accepted.
            if (("stream_options" in error || "include_usage" in error) &&
                listOf("unsupported", "unknown", "unrecognized", "not allowed", "not permitted", "extra", "not support")
                    .any { it in error }
            ) {
                response.close()
                return@withCancellableCloudCall withCancellableCloudCall(
                    client.newCall(openAiRequest(config, request, includeUsage = false)),
                    read
                )
            }
        }
        read(response)
    }

    private fun openAiRequest(config: CloudApiConfig, request: ChatRequest, includeUsage: Boolean = true): Request {
        val builder = Request.Builder()
            .url(chatEndpointUrl(config.baseUrl, "chat/completions"))
            .addHeader("Accept", "text/event-stream")
            .addCloudApiKeyHeaders(config)
        return builder
            .post(buildOpenAiChatJson(config, request).apply {
                if (!includeUsage) remove("stream_options")
            }.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun anthropicRequest(config: CloudApiConfig, request: ChatRequest): Request =
        Request.Builder()
            .url(anthropicMessagesUrl(config.baseUrl))
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Accept", "text/event-stream")
            .post(buildAnthropicChatJson(config, request).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

    private fun quickOpenAiRequest(config: CloudApiConfig): Request {
        val body = JSONObject()
            .put("model", config.chatModel.trim())
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", "ping"))
            )
            .put("stream", false)
            .put("temperature", 0.0)
            .put("max_tokens", 1)
        val builder = Request.Builder()
            .url(chatEndpointUrl(config.baseUrl, "chat/completions"))
            .addHeader("Accept", "application/json")
            .addCloudApiKeyHeaders(config)
        return builder.post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
    }

    private fun quickAnthropicRequest(config: CloudApiConfig): Request =
        Request.Builder()
            .url(anthropicMessagesUrl(config.baseUrl))
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Accept", "application/json")
            .post(
                JSONObject()
                    .put("model", config.chatModel.trim())
                    .put("max_tokens", 1)
                    .put("temperature", 0.0)
                    .put(
                        "messages",
                        JSONArray().put(JSONObject().put("role", "user").put("content", "ping"))
                    )
                    .toString()
                    .toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()

    private fun parseStreamChunk(format: CloudApiFormat, data: String): CloudChunk? =
        when (format) {
            CloudApiFormat.OPENAI_COMPATIBLE -> parseOpenAiChunk(data)
            CloudApiFormat.ANTHROPIC -> parseAnthropicChunk(data)
            CloudApiFormat.OPENAI_RESPONSES -> error("Responses requires a request-scoped stream decoder")
        }

    private fun parseOpenAiChunk(data: String): CloudChunk? =
        runCatching {
            val root = JSONObject(data)
            root.jsonError()?.let { return CloudChunk(error = it) }
            val choice = root.optJSONArray("choices")?.optJSONObject(0)
            val delta = choice?.optJSONObject("delta") ?: choice?.optJSONObject("message") ?: JSONObject()
            CloudChunk(
                text = delta.cleanString("content"),
                reasoning = delta.cleanString("reasoning_content", "reasoning", "reasoning_text", "thinking", "thinking_content"),
                usage = CloudTokenUsage.parse(root.optJSONObject("usage"))
            )
        }.getOrNull()

    private fun parseAnthropicChunk(data: String): CloudChunk? =
        runCatching {
            val root = JSONObject(data)
            when (root.optString("type")) {
                "message_start" -> CloudChunk(usage = CloudTokenUsage.parse(
                    root.optJSONObject("message")?.optJSONObject("usage"), anthropic = true).copy(output = null))
                "message_delta" -> CloudChunk(usage = CloudTokenUsage.parse(root.optJSONObject("usage"), anthropic = true))
                "content_block_delta" -> {
                    val delta = root.optJSONObject("delta") ?: JSONObject()
                    when (delta.optString("type")) {
                        "text_delta" -> CloudChunk(text = delta.cleanString("text"))
                        "thinking_delta" -> CloudChunk(reasoning = delta.cleanString("thinking"))
                        else -> CloudChunk()
                    }
                }
                "message_stop" -> CloudChunk(done = true)
                "error" -> CloudChunk(error = root.optJSONObject("error")?.optString("message") ?: "Anthropic stream error")
                else -> CloudChunk()
            }
        }.getOrNull()

    private fun parseNonStreamResponse(format: CloudApiFormat, body: String): CloudChunk? {
        val cleanBody = body.trim()
        if (cleanBody.isBlank()) return null
        return when (format) {
            CloudApiFormat.OPENAI_COMPATIBLE -> parseOpenAiResponse(cleanBody)
            CloudApiFormat.ANTHROPIC -> parseAnthropicResponse(cleanBody)
            CloudApiFormat.OPENAI_RESPONSES -> error("Responses requires a request-scoped stream decoder")
        }
    }

    private fun parseOpenAiResponse(body: String): CloudChunk? =
        runCatching {
            val root = JSONObject(body)
            root.jsonError()?.let { return CloudChunk(error = it) }
            val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return null
            val message = choice.optJSONObject("message") ?: choice.optJSONObject("delta") ?: JSONObject()
            CloudChunk(
                text = message.cleanString("content").ifEmpty { choice.cleanString("text") },
                reasoning = message.cleanString("reasoning_content", "reasoning", "reasoning_text", "thinking", "thinking_content"),
                usage = CloudTokenUsage.parse(root.optJSONObject("usage"))
            )
        }.getOrNull()

    private fun parseAnthropicResponse(body: String): CloudChunk? =
        runCatching {
            val root = JSONObject(body)
            root.jsonError()?.let { return CloudChunk(error = it) }
            val usage = CloudTokenUsage.parse(root.optJSONObject("usage"), anthropic = true)
            val content = root.optJSONArray("content") ?: return CloudChunk(text = root.optString("content"), usage = usage)
            val text = StringBuilder()
            val reasoning = StringBuilder()
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                when (item.optString("type")) {
                    "text" -> text.append(item.cleanString("text"))
                    "thinking" -> reasoning.append(item.cleanString("thinking"))
                }
            }
            CloudChunk(text = text.toString(), reasoning = reasoning.toString(), usage = usage)
        }.getOrNull()

    private fun JSONObject.jsonError(): String? {
        val error = optJSONObject("error") ?: return null
        return error.cleanString("message")
            .takeIf { it.isNotBlank() }
            ?: error.cleanString("type").takeIf { it.isNotBlank() }
            ?: "云端接口返回错误"
    }

    private fun JSONObject.cleanString(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key ->
            if (!has(key) || isNull(key)) {
                null
            } else {
                opt(key)
                    ?.toString()
                    ?.takeIf { it.isNotEmpty() }
            }
        }.orEmpty()

    private fun parseErrorMessage(body: String): String {
        if (body.isBlank()) return "请求失败"
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return body.take(240)
        val error = json.optJSONObject("error")
        return error?.optString("message")?.takeIf { it.isNotBlank() }
            ?: json.optString("message", body.take(240))
    }

    private fun cloudStats(
        config: CloudApiConfig,
        startedAt: Long = clock.nowMs(),
        firstChunkAt: Long? = null,
        finishedAt: Long = clock.nowMs(),
        estimatedPromptTokens: Int = 0,
        completionChars: Int = 0,
        usage: CloudTokenUsage = CloudTokenUsage(),
        /** Provider-reported prompt-cache reuse; null means the provider reported no such field. */
        cachedInputTokens: Int? = null,
        streaming: Boolean = true
    ): RuntimeStats {
        val decodeMs = (finishedAt - (firstChunkAt ?: startedAt)).coerceAtLeast(0L)
        val completionTokens = usage.output ?: estimateCloudTokens(completionChars)
        val promptTokens = usage.input ?: estimatedPromptTokens
        val totalMs = (finishedAt - startedAt).coerceAtLeast(0L)
        val e2eTps = if (totalMs > 0L) completionTokens * 1000.0 / totalMs else 0.0
        val tps = if (streaming && decodeMs > 0L) completionTokens * 1000.0 / decodeMs else e2eTps
        val reusedTokens = (cachedInputTokens ?: 0).coerceAtLeast(0)
        return RuntimeStats(
            loaded = true,
            modelPath = "${config.apiFormat.label}/${config.chatModel}",
            backend = "cloud",
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            ttftMs = firstChunkAt?.let { (it - startedAt).coerceAtLeast(0L) } ?: 0L,
            decodeMs = decodeMs,
            decodeTps = tps,
            e2eTps = e2eTps,
            cacheReuseHit = reusedTokens > 0,
            cacheReusedTokens = reusedTokens,
            cacheReuseHits = if (reusedTokens > 0) 1L else 0L,
            cacheReuseMisses = if (cachedInputTokens != null && cachedInputTokens == 0) 1L else 0L,
            cacheReuseReason = if (cachedInputTokens != null) PROVIDER_PROMPT_CACHE_REASON else "unknown",
            promptTokensEstimated = usage.input == null,
            completionTokensEstimated = usage.output == null
        )
    }

    private data class CloudChunk(
        val text: String = "",
        val reasoning: String = "",
        val done: Boolean = false,
        val error: String? = null,
        val usage: CloudTokenUsage = CloudTokenUsage()
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_NON_STREAM_BODY_CHARS = 1_048_576
        private const val MAX_LISTED_MODELS = 256

        /** `cacheReuseReason` for provider-side prompt caching; matches the Responses decoder. */
        private const val PROVIDER_PROMPT_CACHE_REASON = "provider_prompt_cache"
    }
}
