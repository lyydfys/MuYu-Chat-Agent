package com.muyuchat.mca

import android.util.Base64
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.deduplicateVisionAttachments
import java.security.MessageDigest
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cloud chat request support (P7/S21), moved verbatim from CloudChatProvider.kt: the
 * config/endpoint/key-header helpers and the OpenAI-compatible + Anthropic payload builders.
 * Pure request construction only; transports and stores stay with their owners.
 */

internal fun CloudApiConfig.normalizedForImageRequest(preserveProviderName: Boolean = false): CloudApiConfig {
    val cleanBaseUrl = baseUrl.trim().trimEnd('/')
    val cleanImageModel = imageModel.trim()
    val cleanEndpointPath = imageEndpointPath.trim().trim('/')
    val inferredImageFormat = when {
        cleanBaseUrl.isDashScopeBaseUrl() -> CloudImageApiFormat.DASHSCOPE_IMAGE
        imageApiFormat == CloudImageApiFormat.DASHSCOPE_IMAGE -> CloudImageApiFormat.DASHSCOPE_IMAGE
        else -> imageApiFormat
    }
    val normalizedEndpointPath = when {
        inferredImageFormat == CloudImageApiFormat.DASHSCOPE_IMAGE &&
            (cleanEndpointPath.isBlank() || cleanEndpointPath.isOpenAiImagesEndpointPath()) ->
            CloudImageApiFormat.DASHSCOPE_IMAGE.defaultEndpointPath
        cleanEndpointPath.isBlank() -> inferredImageFormat.defaultEndpointPath
        else -> cleanEndpointPath
    }
    val imageProtocolLabels = (CloudImageApiFormat.entries.map { it.label } + CloudApiFormat.entries.map { it.label }).toSet()
    return copy(
        providerName = providerName.trim().ifBlank { inferredImageFormat.label }.let { current ->
            if (!preserveProviderName && current in imageProtocolLabels && current != inferredImageFormat.label) inferredImageFormat.label else current
        },
        baseUrl = cleanBaseUrl,
        imageApiFormat = inferredImageFormat,
        imageModel = cleanImageModel,
        imageEndpointPath = normalizedEndpointPath,
        imageSize = imageSize.trim().ifBlank { "1024x1024" }
    )
}

private fun String.isDashScopeBaseUrl(): Boolean =
    contains("dashscope.aliyuncs.com", ignoreCase = true) ||
            contains("dashscope-intl.aliyuncs.com", ignoreCase = true)

private fun String.isMiMoBaseUrl(): Boolean =
    contains("xiaomimimo.com", ignoreCase = true) ||
            contains("mimo.mi.com", ignoreCase = true)

private fun String.isOpenAiImagesEndpointPath(): Boolean =
    trim('/').endsWith(CloudImageApiFormat.OPENAI_IMAGES.defaultEndpointPath, ignoreCase = true)

internal fun Request.Builder.addCloudApiKeyHeaders(config: CloudApiConfig): Request.Builder {
    if (config.apiKey.isNotBlank()) {
        addHeader("Authorization", "Bearer ${config.apiKey}")
        if (config.baseUrl.isMiMoBaseUrl()) {
            addHeader("api-key", config.apiKey)
        }
    }
    return this
}

internal fun guessCloudVisionSupport(modelName: String, baseUrl: String): Boolean {
    val text = "$modelName $baseUrl".lowercase()
    val visionHints = listOf(
        "vision",
        "multimodal",
        "multi-modal",
        "vl",
        "mimo",
        "gpt-4o",
        "qwen-vl",
        "qvq",
        "claude-3",
        "claude-sonnet",
        "claude-opus",
        "claude-haiku"
    )
    return visionHints.any { it in text }
}

internal fun coalesceCloudChatMessagesByRole(messages: List<ChatMessage>): List<ChatMessage> {
    val result = mutableListOf<ChatMessage>()
    messages.forEach { message ->
        val last = result.lastOrNull()
        if (last != null && last.role == message.role) {
            result[result.lastIndex] = last.copy(
                content = listOf(last.content, message.content)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n"),
                imageAttachments = (last.imageAttachments + message.imageAttachments)
                    .deduplicateVisionAttachments()
            )
        } else {
            result.add(message)
        }
    }
    return result
}

internal fun buildOpenAiChatJson(config: CloudApiConfig, request: ChatRequest): JSONObject {
    val params = request.params
    return JSONObject()
        .put("model", config.chatModel.trim())
        .put("messages", JSONArray(request.messagesJson(multimodal = true)))
        .put("stream", config.streaming)
        .also { root -> if (config.streaming) root.put("stream_options", JSONObject().put("include_usage", true)) }
        .put("temperature", params.temperature.toDouble())
        .put("top_p", params.topP.toDouble())
        .put("presence_penalty", params.presencePenalty.toDouble())
        .put("frequency_penalty", params.frequencyPenalty.toDouble())
        .put("max_tokens", params.effectiveNPredict().coerceIn(1, 32768))
        .also { root ->
            if (params.stopWords.isNotEmpty()) {
                root.put("stop", JSONArray(params.stopWords))
            }
            applyOpenAiCompatibleReasoning(root, config, params)
            applyOpenAiPromptCacheKey(root, config, request)
        }
}

internal fun buildAnthropicChatJson(config: CloudApiConfig, request: ChatRequest): JSONObject {
    val split = splitCloudSystemMessages(request)
    val params = request.params
    return JSONObject()
        .put("model", config.chatModel.trim())
        .put("stream", true)
        .put("max_tokens", params.effectiveNPredict().coerceIn(1, 8192))
        .put("temperature", params.temperature.toDouble())
        .put("top_p", params.topP.toDouble())
        .also { root ->
            // Anthropic only caches a prefix that carries an explicit breakpoint. The stable
            // system prefix (role card plus world-book anchors) is the only part worth marking:
            // message blocks stay unmarked so the request never exceeds the four-point limit.
            if (split.system.isNotBlank()) {
                root.put("system", anthropicCachedSystemJson(split.system))
            }
        }
        .put("messages", split.messages.toAnthropicMessagesJson())
        .also { root ->
            if (params.reasoningMode != ReasoningMode.OFF) {
                root.put(
                    "thinking",
                    JSONObject()
                        .put("type", "enabled")
                        .put("budget_tokens", params.effectiveThinkingBudget().coerceAtLeast(1024))
                )
            }
        }
}

/** System prompt as one cached text block; an empty system must omit the field entirely. */
private fun anthropicCachedSystemJson(system: String): JSONArray =
    JSONArray().put(
        JSONObject()
            .put("type", "text")
            .put("text", system)
            .put("cache_control", JSONObject().put("type", "ephemeral"))
    )

/**
 * OpenAI prompt caching is keyed by the official endpoint only. Strict compatible gateways reject
 * unknown top-level fields with a 400, so every other Base URL keeps the plain payload.
 */
private fun applyOpenAiPromptCacheKey(root: JSONObject, config: CloudApiConfig, request: ChatRequest) {
    if (!config.baseUrl.contains("openai.com", ignoreCase = true)) return
    val sessionId = request.persistentSessionId?.trim()?.takeIf { it.isNotBlank() } ?: return
    root.put("prompt_cache_key", openAiPromptCacheKeyForSession(sessionId))
}

/**
 * Opaque routing key: sha256 of the session identity, truncated to 32 hex chars. The raw
 * conversation identity never leaves the app.
 */
internal fun openAiPromptCacheKeyForSession(sessionId: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(sessionId.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        .take(32)

private fun applyOpenAiCompatibleReasoning(
    root: JSONObject,
    config: CloudApiConfig,
    params: GenerationParams
) {
    val thinkingEnabled = params.reasoningMode != ReasoningMode.OFF
    if (!config.baseUrl.contains("openai.com", ignoreCase = true)) {
        root.put("enable_thinking", thinkingEnabled)
        root.put("thinking_budget", params.effectiveThinkingBudget())
        root.put(
            "chat_template_kwargs",
            JSONObject().put("enable_thinking", thinkingEnabled)
        )
    }
}

private fun splitCloudSystemMessages(request: ChatRequest): CloudSplitMessages {
    val system = StringBuilder()
    val messages = mutableListOf<ChatMessage>()
    for (message in request.messagesWithSystemPrompt()) {
        if (message.role == Role.SYSTEM) {
            if (system.isNotBlank()) system.append("\n\n")
            system.append(message.content)
        } else {
            messages.add(message)
        }
    }
    if (messages.isEmpty() || messages.first().role != Role.USER) {
        messages.add(0, ChatMessage(Role.USER, "Continue."))
    }
    return CloudSplitMessages(system.toString(), coalesceCloudChatMessagesByRole(messages))
}

private fun List<ChatMessage>.toAnthropicMessagesJson(): JSONArray {
    val array = JSONArray()
    forEach { message ->
        array.put(
            JSONObject()
                .put("role", if (message.role == Role.ASSISTANT) "assistant" else "user")
                .put("content", message.toAnthropicContentJson())
        )
    }
    return array
}

private fun ChatMessage.toAnthropicContentJson(): Any {
    if (imageAttachments.isEmpty()) return content
    val parts = JSONArray()
    imageAttachments
        .deduplicateVisionAttachments()
        .filter { it.hasInlineData }
        .forEach { attachment ->
            parts.put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", attachment.mimeType.ifBlank { "image/jpeg" })
                            .put("data", attachment.plainBase64())
                    )
            )
        }
    if (content.isNotBlank()) {
        parts.put(JSONObject().put("type", "text").put("text", content))
    }
    return if (parts.length() == 0) content else parts
}

private data class CloudSplitMessages(
    val system: String,
    val messages: List<ChatMessage>
)
