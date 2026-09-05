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

enum class MultimodalContentEncoding {
    OPENAI_PARTS,
    MNN_IMAGE_TAGS_FIRST
}

data class ChatMessage(
    val role: Role,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val tokenCount: Int? = null,
    val reasoningContent: String = "",
    val reasoningDurationMs: Long = 0L,
    val imageAttachments: List<ChatImageAttachment> = emptyList(),
    val sourceReferences: List<ChatSourceReference> = emptyList(),
    val webSearchTrace: ChatWebSearchTrace? = null
)

data class ChatSourceReference(
    val title: String = "",
    val url: String = "",
    val snippet: String = "",
    val provider: String = "",
    val hostLabel: String = "",
    val trustLabel: String = "",
    val trustReason: String = ""
)

data class ChatWebSearchTrace(
    val query: String = "",
    val providerLabel: String = "",
    val triggerModeLabel: String = "",
    val running: Boolean = false,
    val stageLabel: String = "",
    val searchedQueries: List<String> = emptyList(),
    val directUrls: List<String> = emptyList(),
    val sourceCount: Int = 0,
    val elapsedMs: Long = 0L,
    val success: Boolean = false,
    val message: String = "",
    val healthScore: Int = 0,
    val healthLabel: String = "",
    val qualityScore: Int = 0,
    val qualityLabel: String = "",
    val researchConfidenceScore: Int = 0,
    val researchConfidenceLabel: String = "",
    val evidenceGroups: List<String> = emptyList(),
    val conflictWarnings: List<String> = emptyList(),
    val synthesisGuidance: List<String> = emptyList(),
    val triggerReasons: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val cacheStatus: String = "",
    val closedLoopChecks: List<String> = emptyList()
) {
    val hasContent: Boolean
        get() = query.isNotBlank() ||
            running ||
            stageLabel.isNotBlank() ||
            searchedQueries.isNotEmpty() ||
            directUrls.isNotEmpty() ||
            sourceCount > 0 ||
            message.isNotBlank() ||
            warnings.isNotEmpty()
}

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
    val visionProjectorPath: String? = null,
    val geniexComputeUnit: String? = null,
    val advancedJson: String = "{}"
) {
    companion object {
        fun fromJson(json: String, defaults: LoadParams = LoadParams()): LoadParams {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return defaults
            return LoadParams(
                nCtx = root.optInt("n_ctx", defaults.nCtx),
                nThreads = root.optInt("n_threads", defaults.nThreads),
                mmap = root.optBoolean("mmap", defaults.mmap),
                mlock = root.optBoolean("mlock", defaults.mlock),
                visionProjectorPath = root.optString("mmproj_path", defaults.visionProjectorPath.orEmpty())
                    .takeIf { it.isNotBlank() },
                geniexComputeUnit = root.optString(
                    "geniex_compute_unit",
                    root.optString("compute_unit", defaults.geniexComputeUnit.orEmpty())
                ).trim().takeIf { it.isNotBlank() },
                advancedJson = LlamaAdvancedParams.collectFromRoot(root, defaults.advancedJson)
            )
        }
    }

    fun advancedValidationErrors(): List<String> =
        LlamaAdvancedParams.parse(advancedJson).errorMessages

    fun toJson(): String {
        val advanced = LlamaAdvancedParams.parse(advancedJson)
        return JSONObject()
            .put("n_ctx", nCtx)
            .put("n_threads", nThreads)
            .put("mmap", mmap)
            .put("mlock", mlock)
            .apply {
                // Valid advanced canonical values override the LoadParams defaults.
                advanced.putCanonicalFields(this)
                put("advanced_json", advanced.advancedJsonValue())
            if (!visionProjectorPath.isNullOrBlank()) {
                put("mmproj_path", visionProjectorPath)
            }
            if (!geniexComputeUnit.isNullOrBlank()) {
                put("geniex_compute_unit", geniexComputeUnit)
            }
        }
        .toString()
    }
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
    companion object {
        fun fromJson(json: String, defaults: GenerationParams = GenerationParams()): GenerationParams {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return defaults
            return fromJson(root, defaults)
        }

        fun fromJson(root: JSONObject, defaults: GenerationParams = GenerationParams()): GenerationParams =
            GenerationParams(
                // LiteRT-LM profiles call the context-length constructor field
                // `max_num_tokens`; it is the same value MCA exposes as
                // `n_ctx`.  Keep this alias in the generation parser because
                // profile merging passes the canonical load-bound map here.
                nCtx = root.optInt(
                    "n_ctx",
                    root.optInt("max_num_tokens", root.optInt("maxNumTokens", defaults.nCtx))
                ),
                nPredict = root.optInt("n_predict", root.optInt("max_tokens", defaults.nPredict)).takeIf { it > 0 } ?: defaults.nPredict,
                nThreads = root.optInt("n_threads", defaults.nThreads),
                temperature = root.optDouble("temperature", defaults.temperature.toDouble()).toFloat(),
                topK = root.optInt("top_k", defaults.topK),
                topP = root.optDouble("top_p", defaults.topP.toDouble()).toFloat(),
                minP = root.optDouble("min_p", defaults.minP.toDouble()).toFloat(),
                repeatPenalty = root.optDouble(
                    "repeat_penalty",
                    root.optDouble("repetition_penalty", defaults.repeatPenalty.toDouble())
                ).toFloat(),
                presencePenalty = root.optDouble("presence_penalty", defaults.presencePenalty.toDouble()).toFloat(),
                frequencyPenalty = root.optDouble("frequency_penalty", defaults.frequencyPenalty.toDouble()).toFloat(),
                seed = if (root.has("seed") && !root.isNull("seed")) root.optInt("seed") else defaults.seed,
                systemPrompt = root.optString("system_prompt", defaults.systemPrompt),
                stopWords = root.optJSONArray("stop_words")?.toStringList()
                    ?: root.optJSONArray("stop")?.toStringList()
                    ?: defaults.stopWords,
                chatTemplateMode = root.optString("chat_template_mode", defaults.chatTemplateMode),
                advancedJson = LlamaAdvancedParams.collectFromRoot(root, defaults.advancedJson),
                reasoningMode = root.optReasoningMode(defaults.reasoningMode),
                hideReasoning = root.optBoolean("hide_reasoning", defaults.hideReasoning)
            )

        private fun JSONObject.optReasoningMode(default: ReasoningMode): ReasoningMode {
            val raw = optString("reasoning_mode", optString("thinking_mode", "")).trim()
            if (raw.isBlank()) {
                return if (has("enable_thinking")) {
                    if (optBoolean("enable_thinking", default != ReasoningMode.OFF)) ReasoningMode.STANDARD else ReasoningMode.OFF
                } else {
                    default
                }
            }
            return when (raw.lowercase()) {
                "off", "none", "disable", "disabled", "false", "关闭" -> ReasoningMode.OFF
                "advanced", "deep", "high", "进阶", "深度" -> ReasoningMode.ADVANCED
                "standard", "normal", "default", "true", "标准" -> ReasoningMode.STANDARD
                else -> ReasoningMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: default
            }
        }

        private fun JSONArray.toStringList(): List<String> = buildList {
            for (index in 0 until length()) {
                val value = optString(index)
                if (value.isNotBlank()) add(value)
            }
        }
    }

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

    fun advancedValidationErrors(): List<String> =
        LlamaAdvancedParams.parse(advancedJson).errorMessages

    fun toJson(): String {
        val advanced = LlamaAdvancedParams.parse(advancedJson)
        return JSONObject()
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
            .apply {
                advanced.putCanonicalFields(this)
                put("advanced_json", advanced.advancedJsonValue())
            }
            .toString()
    }
}

data class ChatRequest(
    val messages: List<ChatMessage>,
    val params: GenerationParams = GenerationParams(),
    /** Request-scoped authoritative context; never persisted into assistants or chat history. */
    val runtimeSystemContext: String = "",
    /**
     * Stable persona/system prefix for the role cache. Request-scoped
     * retrieval, web-search, and clock text are excluded from this field.
     * When [persistentSessionId] is present, the exact rendered conversation
     * state is additionally checkpointed under that session identity and is
     * accepted only after native token-prefix validation.
     */
    val persistentPrefixSystemPrompt: String? = null,
    /** Stable conversation identity for disk-backed full-session KV reuse. */
    val persistentSessionId: String? = null
) {
    /**
     * Returns only the stable configured persona prefix. Request-scoped system
     * context (world books, retrieval, clock text, and caller additions) is
     * deliberately excluded so it can never be persisted in a prefix state.
     */
    fun fixedSystemPromptForPrefixCache(): String {
        val stablePrompt = persistentPrefixSystemPrompt?.trim().orEmpty()
        if (stablePrompt.isBlank()) return ""
        return listOf(stablePrompt, params.reasoningInstruction())
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    fun messagesJson(
        multimodal: Boolean = false,
        contentEncoding: MultimodalContentEncoding = MultimodalContentEncoding.OPENAI_PARTS
    ): String {
        val array = JSONArray()
        val effectiveMessages = withSystemPrompt(messages)
        effectiveMessages.forEach { message ->
            array.put(
                JSONObject()
                    .put("role", message.role.name.lowercase())
                    .put("content", message.toJsonContent(multimodal, contentEncoding))
                    .put("created_at", message.createdAt)
            )
        }
        return array.toString()
    }

    private fun ChatMessage.toJsonContent(
        multimodal: Boolean,
        contentEncoding: MultimodalContentEncoding
    ): Any {
        if (!multimodal || imageAttachments.isEmpty()) return content
        val usableAttachments = imageAttachments.filter { it.hasInlineData || it.uriString.isNotBlank() }
        if (usableAttachments.isEmpty()) return content
        if (contentEncoding == MultimodalContentEncoding.MNN_IMAGE_TAGS_FIRST) {
            return buildString {
                usableAttachments.forEach { attachment ->
                    val source = if (attachment.hasInlineData) attachment.dataUrl() else attachment.uriString
                    append("<img>").append(source).append("</img>")
                }
                append(content)
            }
        }
        val parts = JSONArray()
        if (content.isNotBlank()) {
            parts.put(JSONObject().put("type", "text").put("text", content))
        }
        usableAttachments.forEach { attachment ->
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
        return parts
    }

    private fun withSystemPrompt(input: List<ChatMessage>): List<ChatMessage> {
        val systemParts = linkedSetOf<String>()
        fun addSystemPart(value: String) {
            value.trim().takeIf { it.isNotBlank() }?.let(systemParts::add)
        }

        val inputSystemMessages = input.filter { it.role == Role.SYSTEM }
        // An explicit system message from an API client is its character card
        // and therefore replaces the app's default persona, as it did before
        // role snapshots were introduced. Runtime context and the requested
        // reasoning instruction still apply to either form of system prompt.
        if (inputSystemMessages.isEmpty()) {
            addSystemPart(params.effectiveSystemPrompt())
        } else {
            inputSystemMessages.forEach { addSystemPart(it.content) }
            addSystemPart(params.reasoningInstruction())
        }
        addSystemPart(runtimeSystemContext)

        val nonSystemMessages = input.filter { it.role != Role.SYSTEM }
        if (systemParts.isEmpty()) return nonSystemMessages
        val systemCreatedAt = inputSystemMessages.firstOrNull()?.createdAt
            ?: System.currentTimeMillis()
        return listOf(
            ChatMessage(
                role = Role.SYSTEM,
                content = systemParts.joinToString("\n\n"),
                createdAt = systemCreatedAt
            )
        ) + nonSystemMessages
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
    /** Tokens actually evaluated by the native prefill pass per second. */
    val prefillTps: Double = 0.0,
    /** Tokens/s if the full logical prompt (including reused KV) were counted. */
    val effectivePromptTps: Double = 0.0,
    /** Number of prompt tokens that were actually evaluated this request. */
    val prefillTokens: Int = 0,
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
    val nCtx: Int = 0,
    val maxAllTokens: Int = 0,
    val maxNewTokens: Int = 0,
    val backendDevices: String = "[]",
    /** True only after native allocation and successful decode evidence agree. */
    val gpuOffloadActive: Boolean = false,
    /** Native read-back saw non-CPU model and context/compute allocations. */
    val gpuOffloadAllocationObserved: Boolean = false,
    /** At least one llama_decode completed after the allocation evidence. */
    val gpuOffloadExecutionObserved: Boolean = false,
    val gpuOffloadBytes: Long = 0,
    /** -1 means the backend did not expose an exact actual layer count. */
    val gpuOffloadLayers: Int = 0,
    val gpuOffloadLayersKnown: Boolean = false,
    /** `n_gpu_layers=auto` retried the current load using CPU. */
    val gpuAutoFallbackApplied: Boolean = false,
    val gpuAutoFallbackReason: String? = null,
    /** In-memory longest-common-prefix KV reuse for the current request. */
    val cacheReuseHit: Boolean = false,
    val cacheReusedTokens: Int = 0,
    val cacheReuseReason: String? = null,
    val cacheReuseHits: Long = 0,
    val cacheReuseMisses: Long = 0,
    /** Disk-backed fixed-system-prefix cache remains separately attributable. */
    val persistentPrefixCacheHit: Boolean = false,
    val persistentPrefixCacheTokens: Int = 0,
    val persistentPrefixCacheReason: String? = null,
    val lastError: String? = null
) {
    /** A single predicate shared by UI and API projections of runtime stats. */
    val hasVerifiedGpuExecution: Boolean
        get() = gpuOffloadActive &&
            gpuOffloadAllocationObserved &&
            gpuOffloadExecutionObserved
}

/** Execution boundaries reported by [GenerateEvent.Phase]. */
enum class GenerationPhase {
    LOAD,
    TOKENIZE,
    PREFILL,
    DECODE,
    PERSIST
}

/** Lifecycle of KV cache serialization/write reported by the native runtime. */
enum class PersistStage {
    IDLE,
    ENCODING,
    WRITING,
    DONE
}

/**
 * Byte-level serialization progress for the KV state file written while
 * beginCompletion runs. A missing [PersistProgress] means no write is in flight.
 */
data class PersistProgress(
    val stage: PersistStage,
    val writtenBytes: Long,
    val totalBytes: Long
) {
    val isActive: Boolean
        get() = stage == PersistStage.ENCODING || stage == PersistStage.WRITING
}

/**
 * Exact token progress reported by a native runtime.
 *
 * A missing [TokenProgress] means the phase is indeterminate. Callers must not
 * derive a percentage from estimates or configured token limits.
 */
data class TokenProgress(
    val completedTokens: Int,
    val totalTokens: Int
) {
    init {
        require(completedTokens >= 0) { "completedTokens must not be negative" }
        require(totalTokens > 0) { "totalTokens must be positive" }
        require(completedTokens <= totalTokens) { "completedTokens must not exceed totalTokens" }
    }
}

/** Original message position and deterministic retention decision for one prompt. */
data class PromptMessageRetention(
    val originalIndex: Int,
    val role: Role,
    val retained: Boolean
) {
    init {
        require(originalIndex >= 0) { "originalIndex must not be negative" }
    }
}

/** Deterministic prompt accounting exposed to the chat UI and diagnostics. */
data class PromptContextUsage(
    val retainedMessageCount: Int,
    val trimmedMessageCount: Int,
    val roleTokens: Int,
    val worldBookTokens: Int,
    val knowledgeTokens: Int,
    val totalEstimatedTokens: Long,
    val messageRetention: List<PromptMessageRetention> = emptyList(),
    val selectedWorldBookEntryIds: List<String> = emptyList(),
    val skippedWorldBookEntryIds: List<String> = emptyList(),
    val selectedKnowledgeChunkIds: List<String> = emptyList(),
    val skippedKnowledgeChunkIds: List<String> = emptyList()
) {
    init {
        require(retainedMessageCount >= 0) { "retainedMessageCount must not be negative" }
        require(trimmedMessageCount >= 0) { "trimmedMessageCount must not be negative" }
        require(roleTokens >= 0) { "roleTokens must not be negative" }
        require(worldBookTokens >= 0) { "worldBookTokens must not be negative" }
        require(knowledgeTokens >= 0) { "knowledgeTokens must not be negative" }
        require(totalEstimatedTokens >= 0L) { "totalEstimatedTokens must not be negative" }
        require(messageRetention.map { it.originalIndex }.distinct().size == messageRetention.size) {
            "messageRetention must contain each original index at most once"
        }
        if (messageRetention.isNotEmpty()) {
            require(retainedMessageCount == messageRetention.count { it.retained }) {
                "retainedMessageCount must match messageRetention"
            }
            require(trimmedMessageCount == messageRetention.count { !it.retained }) {
                "trimmedMessageCount must match messageRetention"
            }
        }
    }
}

sealed interface GenerateEvent {
    /**
     * A non-token lifecycle transition. In particular, prefill stays
     * indeterminate until native code supplies an exact token total.
     */
    data class Phase(
        val phase: GenerationPhase,
        val stats: RuntimeStats,
        val tokenProgress: TokenProgress? = null
    ) : GenerateEvent

    data class Chunk(
        val text: String,
        val stats: RuntimeStats,
        val reasoning: String = "",
        val reasoningDurationMs: Long = 0L,
        val hiddenReasoning: Boolean = false
    ) : GenerateEvent

    /** Byte-level KV serialization progress while native persists a state file. */
    data class Persist(val progress: PersistProgress) : GenerateEvent
    data class Done(val stats: RuntimeStats) : GenerateEvent
    data class Error(
        val message: String,
        val stats: RuntimeStats,
        val code: String? = null,
        val changedFields: Set<String> = emptySet(),
        val action: String? = null
    ) : GenerateEvent
}
