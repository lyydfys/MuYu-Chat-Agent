package com.muyuchat.api.local

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

internal sealed interface OpenAiChatParseResult {
    data class Success(val request: ChatRequest) : OpenAiChatParseResult

    data class Rejected(val rejection: OpenAiRequestRejection) : OpenAiChatParseResult
}

internal data class OpenAiRequestRejection(
    val code: String,
    val message: String,
    val detailsJson: String = "{}"
)

internal class OpenAiRequestRejectedException(
    val rejection: OpenAiRequestRejection
) : IllegalArgumentException(rejection.message)

internal object OpenAiApiCompat {
    fun corsHeaders(): String = buildString {
        append("Access-Control-Allow-Origin: *\r\n")
        append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        append("Access-Control-Allow-Headers: Authorization, Content-Type, X-API-Key, x-api-key, Accept, Idempotency-Key\r\n")
        append("Access-Control-Expose-Headers: Retry-After, X-Retry-After-Ms\r\n")
        append("Access-Control-Max-Age: 86400\r\n")
        append("Access-Control-Allow-Private-Network: true\r\n")
        append("Access-Control-Allow-Credentials: false\r\n")
    }

    fun errorJson(code: String, message: String, detailsJson: String = "{}"): JSONObject {
        val error = JSONObject()
            .put("message", message)
            .put("type", "mca_error")
            .put("code", code)
        runCatching { JSONObject(detailsJson) }
            .getOrNull()
            ?.takeIf { it.length() > 0 }
            ?.let { error.put("details", it) }
        return JSONObject().put("error", error)
    }

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
        return when (val result = parseChatRequestChecked(body, baseParams)) {
            is OpenAiChatParseResult.Success -> result.request
            is OpenAiChatParseResult.Rejected -> throw OpenAiRequestRejectedException(result.rejection)
        }
    }

    fun parseChatRequestChecked(
        body: String,
        baseParams: GenerationParams = GenerationParams()
    ): OpenAiChatParseResult {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return OpenAiChatParseResult.Success(
                ChatRequest(listOf(ChatMessage(Role.USER, body)), params = baseParams)
            )
        val restrictedFields = root.restrictedParameterPaths()
        if (restrictedFields.isNotEmpty()) {
            return OpenAiChatParseResult.Rejected(
                OpenAiRequestRejection(
                    code = "parameter_scope_conflict",
                    message = "Local API chat requests may only override generation parameters. " +
                        "Model load, execution, template, GPU/MoE/MTP, and native advanced fields must be applied through an authorized runtime profile.",
                    detailsJson = JSONObject()
                        .put("allowed_scope", "generation_only")
                        .put("restricted_fields", JSONArray(restrictedFields))
                        .toString()
                )
            )
        }
        val messages = root.optJSONArray("messages")?.toMessages()?.normalizeForLocalTemplate()
            ?: root.inputMessages()?.normalizeForLocalTemplate()
            ?: listOf(ChatMessage(Role.USER, root.promptText().ifBlank { body }))
        return OpenAiChatParseResult.Success(
            ChatRequest(messages = messages, params = root.toGenerationParams(baseParams))
        )
    }

    fun requestedModel(body: String): String? =
        runCatching { JSONObject(body).optString("model").trim().takeIf { it.isNotBlank() } }.getOrNull()

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
        return defaults.copy(
            nPredict = requestedPredict(defaults.nPredict),
            temperature = optDouble("temperature", defaults.temperature.toDouble()).toFloat(),
            topK = optInt("top_k", defaults.topK),
            topP = optDouble("top_p", defaults.topP.toDouble()).toFloat(),
            minP = optDouble("min_p", defaults.minP.toDouble()).toFloat(),
            repeatPenalty = optDouble("repeat_penalty", optDouble("repetition_penalty", defaults.repeatPenalty.toDouble())).toFloat(),
            presencePenalty = optDouble("presence_penalty", defaults.presencePenalty.toDouble()).toFloat(),
            frequencyPenalty = optDouble("frequency_penalty", defaults.frequencyPenalty.toDouble()).toFloat(),
            seed = if (has("seed") && !isNull("seed")) optInt("seed") else defaults.seed,
            systemPrompt = optString("system_prompt", defaults.systemPrompt).decodePercentEncodedText(),
            stopWords = requestedStopWords(defaults.stopWords),
            reasoningMode = optReasoningMode(defaults.reasoningMode),
            hideReasoning = if (hasShowReasoning) !showReasoning else optBoolean("hide_reasoning", defaults.hideReasoning)
        )
    }

    private fun JSONObject.requestedStopWords(defaults: List<String>): List<String> {
        val value = when {
            has("stop") && !isNull("stop") -> opt("stop")
            has("stop_words") && !isNull("stop_words") -> opt("stop_words")
            else -> return defaults
        }
        return when (value) {
            is JSONArray -> value.toStringList()
            is String -> listOf(value).filter(String::isNotBlank)
            else -> defaults
        }
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

    private fun JSONObject.inputMessages(): List<ChatMessage>? {
        val value = opt("input") ?: return null
        return when (value) {
            is JSONArray -> value.toInputMessages()
            is JSONObject -> listOf(value.toChatMessage(defaultRole = Role.USER))
            is String -> listOf(ChatMessage(Role.USER, value.decodePercentEncodedText()))
            else -> null
        }?.takeIf { it.isNotEmpty() }
    }

    private fun JSONArray.toInputMessages(): List<ChatMessage> {
        val messageObjects = buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.takeIf { it.has("role") }?.let(::add)
            }
        }
        if (messageObjects.isNotEmpty()) {
            return messageObjects.map { it.toChatMessage(defaultRole = Role.USER) }
        }
        val synthetic = JSONObject().put("content", this)
        return listOf(
            ChatMessage(
                role = Role.USER,
                content = inputPromptText(),
                imageAttachments = synthetic.chatImageAttachments()
            )
        )
    }

    private fun JSONObject.toChatMessage(defaultRole: Role): ChatMessage {
        val role = when (optString("role").lowercase()) {
            "system", "developer" -> Role.SYSTEM
            "assistant" -> Role.ASSISTANT
            "user" -> Role.USER
            else -> defaultRole
        }
        return ChatMessage(
            role = role,
            content = chatContent(),
            imageAttachments = chatImageAttachments()
        )
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
        if (raw.isBlank()) {
            return if (has("enable_thinking") && !isNull("enable_thinking")) {
                if (optBoolean("enable_thinking", default != ReasoningMode.OFF)) {
                    ReasoningMode.STANDARD
                } else {
                    ReasoningMode.OFF
                }
            } else {
                default
            }
        }
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
            is String -> value.decodePercentEncodedText()
            is JSONArray -> buildString {
                for (index in 0 until value.length()) {
                    when (val partValue = value.opt(index)) {
                        is String -> append(partValue.decodePercentEncodedText())
                        is JSONObject -> {
                            val type = partValue.optString("type")
                            if (type == "text" || type == "input_text" || type.isBlank()) {
                                append(partValue.optString("text", partValue.optString("content")).decodePercentEncodedText())
                            }
                        }
                    }
                }
            }
            is JSONObject -> value.optString("text").decodePercentEncodedText()
            else -> optString("content").decodePercentEncodedText()
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
                        mimeType = url.inferImageMimeType(),
                        dataBase64 = if (url.startsWith("data:", ignoreCase = true)) url else ""
                    )
                )
            }
        }
    }

    private fun JSONObject.promptText(): String {
        val value = opt("prompt") ?: opt("input")
        return when (value) {
            is String -> value.decodePercentEncodedText()
            is JSONArray -> buildString {
                for (index in 0 until value.length()) {
                    val item = value.opt(index)
                    when (item) {
                        is String -> append(item.decodePercentEncodedText())
                        is JSONObject -> append(item.chatContent())
                    }
                    if (index < value.length() - 1) append('\n')
                }
            }
            is JSONObject -> value.chatContent()
            else -> optString("prompt", optString("input", "")).decodePercentEncodedText()
        }
    }

    private fun JSONArray.inputPromptText(): String = buildList {
        for (index in 0 until length()) {
            when (val item = opt(index)) {
                is String -> item.decodePercentEncodedText()
                is JSONObject -> item.chatContent()
                else -> ""
            }.trim().takeIf { it.isNotBlank() }?.let(::add)
        }
    }.joinToString("\n")

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (index in 0 until length()) {
            val value = optString(index)
            if (value.isNotBlank()) add(value)
        }
    }

    private fun String.inferImageMimeType(): String {
        if (startsWith("data:", ignoreCase = true)) {
            substringAfter("data:", "")
                .substringBefore(';')
                .trim()
                .takeIf { it.startsWith("image/", ignoreCase = true) }
                ?.let { return it.lowercase() }
        }
        return when {
            endsWith(".png", ignoreCase = true) -> "image/png"
            endsWith(".webp", ignoreCase = true) -> "image/webp"
            endsWith(".gif", ignoreCase = true) -> "image/gif"
            endsWith(".bmp", ignoreCase = true) -> "image/bmp"
            else -> "image/jpeg"
        }
    }

    private fun JSONObject.requestedPredict(defaultValue: Int): Int {
        val raw = when {
            has("n_predict") && !isNull("n_predict") -> optInt("n_predict", defaultValue)
            has("max_tokens") && !isNull("max_tokens") -> optInt("max_tokens", defaultValue)
            has("max_completion_tokens") && !isNull("max_completion_tokens") ->
                optInt("max_completion_tokens", defaultValue)
            else -> defaultValue
        }
        return if (raw <= 0) defaultValue else raw.coerceAtMost(MAX_API_PREDICT_TOKENS)
    }

    private fun JSONObject.restrictedParameterPaths(prefix: String = ""): List<String> = buildList {
        val keys = keys()
        while (keys.hasNext()) {
            val rawKey = keys.next()
            val key = rawKey.trim().lowercase()
            val path = if (prefix.isBlank()) rawKey else "$prefix.$rawKey"
            when {
                key in RESTRICTED_PARAMETER_FIELDS || key.looksLikeNativeParameter() -> add(path)
                key in NATIVE_PARAMETER_CONTAINERS -> add(path)
                key == "extra_body" -> {
                    (opt(rawKey) as? JSONObject)?.restrictedParameterPaths(path)?.let(::addAll)
                }
            }
        }
    }.distinct().sorted()

    private fun String.looksLikeNativeParameter(): Boolean =
        this == "native" ||
            endsWith("_native") ||
            contains("_native_") ||
            NATIVE_PARAMETER_PREFIXES.any { prefix -> startsWith(prefix) }

    private fun String.decodePercentEncodedText(): String {
        if (isBlank() || startsWith("data:", ignoreCase = true) || !PERCENT_ESCAPE_PATTERN.containsMatchIn(this)) {
            return this
        }
        return runCatching {
            val decoded = StringBuilder(length)
            val bytes = ByteArrayOutputStream()
            fun flushBytes() {
                if (bytes.size() > 0) {
                    decoded.append(bytes.toByteArray().toString(Charsets.UTF_8))
                    bytes.reset()
                }
            }
            var index = 0
            while (index < length) {
                val ch = this[index]
                if (ch == '%' && index + 2 < length) {
                    val hi = this[index + 1].hexValue()
                    val lo = this[index + 2].hexValue()
                    if (hi >= 0 && lo >= 0) {
                        bytes.write((hi shl 4) or lo)
                        index += 3
                        continue
                    }
                }
                flushBytes()
                decoded.append(ch)
                index += 1
            }
            flushBytes()
            decoded.toString()
        }.getOrDefault(this)
    }

    private fun Char.hexValue(): Int = when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> -1
    }

    private const val MAX_API_PREDICT_TOKENS = 65536
    private val RESTRICTED_PARAMETER_FIELDS = setOf(
        "n_ctx",
        "context_length",
        "n_threads",
        "threads",
        "n_threads_batch",
        "n_batch",
        "n_ubatch",
        "mmap",
        "use_mmap",
        "mlock",
        "use_mlock",
        "cache_type_k",
        "cache_type_v",
        "cache_reuse",
        "flash_attn",
        "flash_attention",
        "n_gpu_layers",
        "gpu",
        "gpu_layers",
        "main_gpu",
        "tensor_split",
        "split_mode",
        "n_cpu_moe",
        "moe",
        "cpu_moe",
        "spec_type",
        "spec_draft_n_max",
        "spec_draft_n_min",
        "spec_draft_p_min",
        "n_parallel",
        "mtp",
        "speculative",
        "draft_model",
        "mmproj",
        "mmproj_path",
        "vision_projector_path",
        "chat_template",
        "chat_template_mode",
        "chat_template_path",
        "chat_template_content",
        "use_jinja",
        "template_policy_ref",
        "runtime",
        "backend",
        "device",
        "device_map",
        "compute_unit",
        "geniex_compute_unit",
        "perf"
    )
    private val NATIVE_PARAMETER_CONTAINERS = setOf(
        "advanced_json",
        "native_params",
        "runtime_params",
        "load_params",
        "execution_params",
        "llama_params",
        "mnn_params",
        "qairt_params",
        "model_execution_profile"
    )
    private val NATIVE_PARAMETER_PREFIXES = setOf(
        "native_",
        "llama_",
        "runtime_",
        "gpu_",
        "moe_",
        "mtp_",
        "spec_",
        "kv_",
        "cache_type_",
        "mnn_",
        "qnn_",
        "qairt_",
        "geniex_",
        "htp_"
    )
    private val PERCENT_ESCAPE_PATTERN = Regex("%[0-9a-fA-F]{2}")
    private val STREAM_TRUE_PATTERN = Regex(""""stream"\s*:\s*(true|1|"true")""", RegexOption.IGNORE_CASE)
}
