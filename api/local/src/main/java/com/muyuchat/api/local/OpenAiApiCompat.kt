package com.muyuchat.api.local

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import org.json.JSONArray
import org.json.JSONObject

internal object OpenAiApiCompat {
    fun corsHeaders(): String = buildString {
        append("Access-Control-Allow-Origin: *\r\n")
        append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        append("Access-Control-Allow-Headers: Authorization, Content-Type, X-API-Key, x-api-key, Accept\r\n")
        append("Access-Control-Max-Age: 86400\r\n")
        append("Access-Control-Allow-Private-Network: true\r\n")
        append("Access-Control-Allow-Credentials: false\r\n")
    }

    fun errorJson(code: String, message: String): JSONObject =
        JSONObject()
            .put(
                "error",
                JSONObject()
                    .put("message", message)
                    .put("type", "mca_error")
                    .put("code", code)
            )

    fun isStreamingRequest(body: String): Boolean {
        val parsed = runCatching {
            val value = JSONObject(body).opt("stream")
            when (value) {
                is Boolean -> value
                is String -> value.equals("true", ignoreCase = true) || value == "1"
                is Number -> value.toInt() == 1
                else -> false
            }
        }.getOrDefault(false)
        return parsed || STREAM_TRUE_PATTERN.containsMatchIn(body)
    }

    fun parseChatRequest(body: String, baseParams: GenerationParams = GenerationParams()): ChatRequest {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return ChatRequest(listOf(ChatMessage(Role.USER, body)), params = baseParams)
        val messages = root.optJSONArray("messages")?.toMessages()?.normalizeForLocalTemplate()
            ?: listOf(ChatMessage(Role.USER, root.promptText().ifBlank { body }))
        return ChatRequest(messages = messages, params = root.toGenerationParams(baseParams))
    }

    private fun JSONObject.toGenerationParams(baseParams: GenerationParams): GenerationParams {
        val hasShowReasoning = has("show_reasoning") && !isNull("show_reasoning")
        val showReasoning = optBoolean("show_reasoning", baseParams.reasoningMode != ReasoningMode.OFF && !baseParams.hideReasoning)
        val defaults = if (hasShowReasoning) {
            baseParams.copy(
                reasoningMode = if (showReasoning) ReasoningMode.STANDARD else ReasoningMode.OFF,
                hideReasoning = !showReasoning
            )
        } else {
            baseParams
        }
        return GenerationParams(
            nCtx = optInt("n_ctx", defaults.nCtx),
            nPredict = requestedPredict(defaults.nPredict),
            nThreads = optInt("n_threads", defaults.nThreads),
            temperature = optDouble("temperature", defaults.temperature.toDouble()).toFloat(),
            topK = optInt("top_k", defaults.topK),
            topP = optDouble("top_p", defaults.topP.toDouble()).toFloat(),
            minP = optDouble("min_p", defaults.minP.toDouble()).toFloat(),
            repeatPenalty = optDouble("repeat_penalty", optDouble("repetition_penalty", defaults.repeatPenalty.toDouble())).toFloat(),
            presencePenalty = optDouble("presence_penalty", defaults.presencePenalty.toDouble()).toFloat(),
            frequencyPenalty = optDouble("frequency_penalty", defaults.frequencyPenalty.toDouble()).toFloat(),
            seed = if (has("seed") && !isNull("seed")) optInt("seed") else defaults.seed,
            systemPrompt = optString("system_prompt", defaults.systemPrompt),
            stopWords = optJSONArray("stop")?.toStringList()
                ?: optJSONArray("stop_words")?.toStringList()
                ?: defaults.stopWords,
            chatTemplateMode = optString("chat_template_mode", defaults.chatTemplateMode),
            advancedJson = optJSONObject("advanced_json")?.toString() ?: defaults.advancedJson,
            reasoningMode = optReasoningMode(defaults.reasoningMode),
            hideReasoning = if (hasShowReasoning) !showReasoning else optBoolean("hide_reasoning", defaults.hideReasoning)
        )
    }

    private fun JSONArray.toMessages(): List<ChatMessage> = buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val role = when (item.optString("role").lowercase()) {
                "system" -> Role.SYSTEM
                "assistant" -> Role.ASSISTANT
                else -> Role.USER
            }
            add(
                ChatMessage(
                    role = role,
                    content = item.chatContent(),
                    imageAttachments = item.chatImageAttachments()
                )
            )
        }
    }

    private fun List<ChatMessage>.normalizeForLocalTemplate(): List<ChatMessage> {
        val systemPrompt = filter { it.role == Role.SYSTEM }
            .map { it.content.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        val nonSystem = filterNot { it.role == Role.SYSTEM }
        val normalized = if (systemPrompt.isBlank()) {
            nonSystem
        } else {
            listOf(ChatMessage(Role.SYSTEM, systemPrompt)) + nonSystem
        }
        if (normalized.any { it.role == Role.USER }) return normalized
        return normalized + ChatMessage(Role.USER, "Continue.")
    }

    private fun JSONObject.optReasoningMode(default: ReasoningMode): ReasoningMode {
        val raw = optString("reasoning_mode", optString("thinking_mode", "")).trim()
        if (raw.isBlank()) return default
        return when (raw.lowercase()) {
            "off", "none", "disable", "disabled", "false", "关闭" -> ReasoningMode.OFF
            "advanced", "deep", "high", "进阶", "深度" -> ReasoningMode.ADVANCED
            "standard", "normal", "default", "标准" -> ReasoningMode.STANDARD
            else -> ReasoningMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: default
        }
    }

    private fun JSONObject.chatContent(): String {
        val value = opt("content")
        return when (value) {
            is String -> value
            is JSONArray -> buildString {
                for (index in 0 until value.length()) {
                    val part = value.optJSONObject(index) ?: continue
                    val type = part.optString("type")
                    if (type == "text" || type == "input_text" || type.isBlank()) {
                        append(part.optString("text"))
                    }
                }
            }
            is JSONObject -> value.optString("text")
            else -> optString("content")
        }
    }

    private fun JSONObject.chatImageAttachments(): List<ChatImageAttachment> {
        val value = opt("content") as? JSONArray ?: return emptyList()
        return buildList {
            for (index in 0 until value.length()) {
                val part = value.optJSONObject(index) ?: continue
                val type = part.optString("type")
                if (type != "image_url" && type != "input_image") continue
                val imageUrl = part.opt("image_url")
                val url = when (imageUrl) {
                    is JSONObject -> imageUrl.optString("url")
                    is String -> imageUrl
                    else -> part.optString("url")
                }.trim()
                if (url.isBlank()) continue
                add(
                    ChatImageAttachment(
                        name = "api-image-${index + 1}",
                        uriString = url,
                        mimeType = when {
                            url.endsWith(".png", ignoreCase = true) -> "image/png"
                            url.endsWith(".webp", ignoreCase = true) -> "image/webp"
                            else -> "image/jpeg"
                        },
                        dataBase64 = if (url.startsWith("data:", ignoreCase = true)) url else ""
                    )
                )
            }
        }
    }

    private fun JSONObject.promptText(): String {
        val value = opt("prompt") ?: opt("input")
        return when (value) {
            is String -> value
            is JSONArray -> buildString {
                for (index in 0 until value.length()) {
                    val item = value.opt(index)
                    when (item) {
                        is String -> append(item)
                        is JSONObject -> append(item.chatContent())
                    }
                    if (index < value.length() - 1) append('\n')
                }
            }
            is JSONObject -> value.chatContent()
            else -> optString("prompt", optString("input", ""))
        }
    }

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (index in 0 until length()) {
            val value = optString(index)
            if (value.isNotBlank()) add(value)
        }
    }

    private fun JSONObject.requestedPredict(defaultValue: Int): Int {
        val raw = when {
            has("n_predict") && !isNull("n_predict") -> optInt("n_predict", defaultValue)
            has("max_tokens") && !isNull("max_tokens") -> optInt("max_tokens", defaultValue)
            else -> defaultValue
        }
        return if (raw <= 0) defaultValue else raw.coerceAtMost(MAX_API_PREDICT_TOKENS)
    }

    private const val MAX_API_PREDICT_TOKENS = 65536
    private val STREAM_TRUE_PATTERN = Regex(""""stream"\s*:\s*(true|1|"true")""", RegexOption.IGNORE_CASE)
}
