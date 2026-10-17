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

enum class ChatGeneratedImageStatus {
    QUEUED,
    AWAITING_APPROVAL,
    GENERATING,
    DONE,
    FAILED,
    CANCELLED,
    INTERRUPTED
}

enum class ChatGeneratedImageOrigin {
    USER_COMMAND,
    ASSISTANT_TOOL
}

/** A protocol-neutral function schema. Only adapters that support tools serialize it. */
data class ChatToolDefinition(
    val name: String,
    val description: String,
    val parametersJson: String
)

/** A complete, provider-decoded tool call. Partial streamed arguments are never executable. */
data class ChatToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

/** A completed tool invocation and its result, kept separate from ordinary chat prose. */
data class ChatToolExchange(
    val call: ChatToolCall,
    val output: String
)

/** Generated media is a chat timeline event, never a user-supplied vision attachment. */
enum class ChatImageToolContinuationStatus {
    NOT_STARTED,
    RUNNING,
    COMPLETED,
    FAILED
}

data class ChatGeneratedImageRequest(
    val id: String,
    val prompt: String,
    val status: ChatGeneratedImageStatus,
    val message: String = "",
    val imageAssetIds: List<String> = emptyList(),
    val origin: ChatGeneratedImageOrigin = ChatGeneratedImageOrigin.USER_COMMAND,
    val currentJobId: String? = null,
    val backendId: String? = null,
    val modelId: String? = null,
    val modelName: String? = null,
    /** Secret-free fingerprint used to reject retries after the selected image profile changes. */
    val modelFingerprint: String? = null,
    /** Secret-free, versioned image generation parameters used by retry. */
    val generationOptionsJson: String? = null,
    /** Original prompt, branch provenance and protected syntax at the send boundary. */
    val promptEnvelopeJson: String? = null,
    val toolCallId: String? = null,
    val toolArgumentsJson: String? = null,
    val toolOutputJson: String? = null,
    /** Chat-only continuation context; contains no provider credentials. */
    val toolChatModelId: String? = null,
    val toolParamsJson: String? = null,
    val toolRequestMessageCount: Int? = null,
    /** Hash of the bounded request prefix, used to reject continuation after history edits. */
    val toolRequestMessagesFingerprint: String? = null,
    val toolContinuationStatus: ChatImageToolContinuationStatus = ChatImageToolContinuationStatus.NOT_STARTED,
    /** Backward-compatible mirror for chat JSON written before continuation states were explicit. */
    val toolContinuationStarted: Boolean = false
) {
    init {
        require(id.isNotBlank()) { "Generated image request id must not be blank." }
        require(prompt.isNotBlank()) { "Generated image prompt must not be blank." }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("prompt", prompt)
        .put("status", status.name)
        .put("message", message)
        .put("imageAssetIds", JSONArray(imageAssetIds))
        .put("origin", origin.name)
        .apply {
            currentJobId?.let { put("currentJobId", it) }
            backendId?.let { put("backendId", it) }
            modelId?.let { put("modelId", it) }
            modelName?.let { put("modelName", it) }
            modelFingerprint?.let { put("modelFingerprint", it) }
            generationOptionsJson?.let { put("generationOptionsJson", it) }
            promptEnvelopeJson?.let { put("promptEnvelopeJson", it) }
            toolCallId?.let { put("toolCallId", it) }
            toolArgumentsJson?.let { put("toolArgumentsJson", it) }
            toolOutputJson?.let { put("toolOutputJson", it) }
            toolChatModelId?.let { put("toolChatModelId", it) }
            toolParamsJson?.let { put("toolParamsJson", it) }
            toolRequestMessageCount?.let { put("toolRequestMessageCount", it) }
            toolRequestMessagesFingerprint?.let { put("toolRequestMessagesFingerprint", it) }
            put("toolContinuationStatus", toolContinuationStatus.name)
            put(
                "toolContinuationStarted",
                toolContinuationStatus == ChatImageToolContinuationStatus.RUNNING ||
                    toolContinuationStatus == ChatImageToolContinuationStatus.COMPLETED
            )
        }

    companion object {
        fun fromJsonOrNull(json: JSONObject?): ChatGeneratedImageRequest? {
            if (json == null) return null
            return runCatching {
                ChatGeneratedImageRequest(
                    id = json.getString("id"),
                    prompt = json.getString("prompt"),
                    status = ChatGeneratedImageStatus.valueOf(json.getString("status")),
                    message = json.optString("message"),
                    imageAssetIds = json.optJSONArray("imageAssetIds")?.let { values ->
                        List(values.length()) { index -> values.optString(index) }
                            .filter(String::isNotBlank)
                    }.orEmpty(),
                    origin = runCatching {
                        ChatGeneratedImageOrigin.valueOf(json.optString("origin"))
                    }.getOrDefault(ChatGeneratedImageOrigin.USER_COMMAND),
                    currentJobId = json.optString("currentJobId").takeIf(String::isNotBlank),
                    backendId = json.optString("backendId").takeIf(String::isNotBlank),
                    modelId = json.optString("modelId").takeIf(String::isNotBlank),
                    modelName = json.optString("modelName").takeIf(String::isNotBlank),
                    modelFingerprint = json.optString("modelFingerprint").takeIf(String::isNotBlank),
                    generationOptionsJson = json.optString("generationOptionsJson").takeIf(String::isNotBlank),
                    promptEnvelopeJson = json.optString("promptEnvelopeJson").takeIf(String::isNotBlank),
                    toolCallId = json.optString("toolCallId").takeIf(String::isNotBlank),
                    toolArgumentsJson = json.optString("toolArgumentsJson").takeIf(String::isNotBlank),
                    toolOutputJson = json.optString("toolOutputJson").takeIf(String::isNotBlank),
                    toolChatModelId = json.optString("toolChatModelId").takeIf(String::isNotBlank),
                    toolParamsJson = json.optString("toolParamsJson").takeIf(String::isNotBlank),
                    toolRequestMessageCount = json.optInt("toolRequestMessageCount", -1)
                        .takeIf { it >= 0 },
                    toolRequestMessagesFingerprint = json.optString("toolRequestMessagesFingerprint")
                        .takeIf(String::isNotBlank),
                    toolContinuationStatus = runCatching {
                        ChatImageToolContinuationStatus.valueOf(json.optString("toolContinuationStatus"))
                    }.getOrElse {
                        if (json.optBoolean("toolContinuationStarted", false)) {
                            ChatImageToolContinuationStatus.RUNNING
                        } else {
                            ChatImageToolContinuationStatus.NOT_STARTED
                        }
                    },
                    toolContinuationStarted = when {
                        json.has("toolContinuationStatus") -> runCatching {
                            ChatImageToolContinuationStatus.valueOf(json.optString("toolContinuationStatus")) in
                                setOf(
                                    ChatImageToolContinuationStatus.RUNNING,
                                    ChatImageToolContinuationStatus.COMPLETED
                                )
                        }.getOrDefault(false)
                        else -> json.optBoolean("toolContinuationStarted", false)
                    }
                )
            }.getOrNull()
        }
    }
}
