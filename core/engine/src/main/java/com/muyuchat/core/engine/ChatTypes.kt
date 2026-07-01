package com.muyuchat.core.engine

import org.json.JSONArray
import org.json.JSONObject

enum class Role {
    SYSTEM,
    USER,
    ASSISTANT
}

enum class ReasoningMode(val label: String) {
    OFF("关闭"),
    STANDARD("标准"),
    ADVANCED("进阶")
}

data class ChatMessage(
    val role: Role,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val tokenCount: Int? = null,
    val reasoningContent: String = "",
    val reasoningDurationMs: Long = 0L,
    val imageAttachments: List<ChatImageAttachment> = emptyList()
)

data class ChatImageAttachment(
    val name: String = "",
    val uriString: String = "",
    val mimeType: String = "image/jpeg",
    val dataBase64: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L
) {
    val hasInlineData: Boolean
        get() = dataBase64.isNotBlank()

    fun dataUrl(): String =
        if (dataBase64.startsWith("data:", ignoreCase = true)) {
            dataBase64
        } else {
            "data:${mimeType.ifBlank { "image/jpeg" }};base64,$dataBase64"
        }

    fun plainBase64(): String =
        dataBase64.substringAfter("base64,", dataBase64)
}

data class LoadParams(
    val nCtx: Int = 8192,
    val nThreads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2) - 1,
    val mmap: Boolean = true,
    val mlock: Boolean = false,
    val visionProjectorPath: String? = null
) {
    fun toJson(): String = JSONObject()
        .put("n_ctx", nCtx)
        .put("n_threads", nThreads)
        .put("mmap", mmap)
        .put("mlock", mlock)
        .apply {
            if (!visionProjectorPath.isNullOrBlank()) {
                put("mmproj_path", visionProjectorPath)
            }
        }
        .toString()
}

data class GenerationParams(
    val nCtx: Int = 8192,
    val nPredict: Int = 8192,
    val nThreads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2) - 1,
    val temperature: Float = 0.6f,
    val topK: Int = 20,
    val topP: Float = 0.95f,
    val minP: Float = 0.0f,
    val repeatPenalty: Float = 1.08f,
    val presencePenalty: Float = 0.0f,
    val frequencyPenalty: Float = 0.2f,
    val seed: Int? = null,
    val systemPrompt: String = "你是霂榆Chat Agent（MCA），一名运行在本机的离线助手。默认使用中文回答，回答要清晰、直接、自然。用户要求代码时，必须使用 Markdown 代码块并标注语言，代码块内保留正确换行和缩进。",
    val stopWords: List<String> = emptyList(),
    val chatTemplateMode: String = "auto",
    val advancedJson: String = "{}",
    val reasoningMode: ReasoningMode = ReasoningMode.OFF,
    val hideReasoning: Boolean = false
) {
    fun effectiveNPredict(): Int {
        val reasoningFloor = when (reasoningMode) {
            ReasoningMode.OFF -> nPredict
            ReasoningMode.STANDARD -> 2048
            ReasoningMode.ADVANCED -> 8192
        }
        return nPredict
            .coerceAtLeast(reasoningFloor)
            .coerceAtLeast(1)
    }

    fun effectiveThinkingBudget(): Int = when (reasoningMode) {
        ReasoningMode.OFF -> 0
        ReasoningMode.STANDARD -> 192
        ReasoningMode.ADVANCED -> 1536
    }

    fun toJson(): String = JSONObject()
        .put("n_ctx", nCtx)
        .put("n_predict", effectiveNPredict())
        .put("n_threads", nThreads)
        .put("temperature", temperature)
        .put("top_k", topK)
        .put("top_p", topP)
        .put("min_p", minP)
        .put("repeat_penalty", repeatPenalty)
        .put("repetition_penalty", repeatPenalty)
        .put("presence_penalty", presencePenalty)
        .put("frequency_penalty", frequencyPenalty)
        .put("seed", seed)
        .put("system_prompt", systemPrompt)
        .put("stop_words", JSONArray(stopWords))
        .put("chat_template_mode", chatTemplateMode)
        .put("reasoning_mode", reasoningMode.name.lowercase())
        .put("enable_thinking", reasoningMode != ReasoningMode.OFF && !hideReasoning)
        .put("thinking_budget", effectiveThinkingBudget())
        .put("hide_reasoning", hideReasoning || reasoningMode == ReasoningMode.OFF)
        .put("advanced_json", runCatching { JSONObject(advancedJson.ifBlank { "{}" }) }.getOrElse { JSONObject() })
        .toString()
}

data class ChatRequest(
    val messages: List<ChatMessage>,
    val params: GenerationParams = GenerationParams()
) {
    fun messagesJson(multimodal: Boolean = false): String {
        val array = JSONArray()
        val effectiveMessages = withSystemPrompt(messages)
        effectiveMessages.forEach { message ->
            array.put(
                JSONObject()
                    .put("role", message.role.name.lowercase())
                    .put("content", message.toJsonContent(multimodal))
                    .put("created_at", message.createdAt)
            )
        }
        return array.toString()
    }

    private fun ChatMessage.toJsonContent(multimodal: Boolean): Any {
        if (!multimodal || imageAttachments.isEmpty()) return content
        val parts = JSONArray()
        if (content.isNotBlank()) {
            parts.put(JSONObject().put("type", "text").put("text", content))
        }
        imageAttachments
            .filter { it.hasInlineData || it.uriString.isNotBlank() }
            .forEach { attachment ->
                parts.put(
                    JSONObject()
                        .put("type", "image_url")
                        .put(
                            "image_url",
                            JSONObject()
                                .put(
                                    "url",
                                    if (attachment.hasInlineData) attachment.dataUrl() else attachment.uriString
                                )
                                .put("detail", "auto")
                        )
                )
            }
        if (parts.length() == 0) return content
        return parts
    }

    private fun withSystemPrompt(input: List<ChatMessage>): List<ChatMessage> {
        val prompt = params.effectiveSystemPrompt().trim()
        if (prompt.isBlank()) return input
        val firstSystem = input.indexOfFirst { it.role == Role.SYSTEM }
        if (firstSystem < 0) {
            return listOf(ChatMessage(Role.SYSTEM, prompt)) + input
        }
        return input.mapIndexed { index, message ->
            if (index == firstSystem) {
                message.copy(content = listOf(message.content, params.reasoningInstruction())
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n"))
            } else {
                message
            }
        }
    }

    private fun GenerationParams.effectiveSystemPrompt(): String =
        listOf(systemPrompt, reasoningInstruction())
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

    private fun GenerationParams.reasoningInstruction(): String = when (reasoningMode) {
        ReasoningMode.OFF -> "请直接回答，不展示思考过程。"
        ReasoningMode.STANDARD,
        ReasoningMode.ADVANCED -> ""
    }
}

data class RuntimeStats(
    val loaded: Boolean = false,
    val modelPath: String? = null,
    val backend: String = "cpu",
    val loadMs: Long = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val ttftMs: Long = 0,
    val prefillMs: Long = 0,
    val decodeMs: Long = 0,
    val decodeTps: Double = 0.0,
    val e2eTps: Double = 0.0,
    val nativePssKb: Long = 0,
    val processRssKb: Long = 0,
    val nativeHeapKb: Long = 0,
    val nativeHeapSizeKb: Long = 0,
    val javaHeapKb: Long = 0,
    val availMemKb: Long = 0,
    val totalMemKb: Long = 0,
    val advertisedMemKb: Long = 0,
    val memoryThresholdKb: Long = 0,
    val isLowMemory: Boolean = false,
    val procMemAvailableKb: Long = 0,
    val procMemFreeKb: Long = 0,
    val cachedKb: Long = 0,
    val reclaimableKb: Long = 0,
    val modelMemoryBudgetKb: Long = 0,
    val nThreads: Int = 0,
    val nThreadsBatch: Int = 0,
    val nBatch: Int = 0,
    val nUbatch: Int = 0,
    val backendDevices: String = "[]",
    val lastError: String? = null
)

sealed interface GenerateEvent {
    data class Chunk(
        val text: String,
        val stats: RuntimeStats,
        val reasoning: String = "",
        val reasoningDurationMs: Long = 0L,
        val hiddenReasoning: Boolean = false
    ) : GenerateEvent
    data class Done(val stats: RuntimeStats) : GenerateEvent
    data class Error(val message: String, val stats: RuntimeStats) : GenerateEvent
}

