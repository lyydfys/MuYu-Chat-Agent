package com.muyuchat.core.engine

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
    val webSearchTrace: ChatWebSearchTrace? = null,
    val generationMetrics: ChatGenerationMetrics? = null,
    val generatedImageRequest: ChatGeneratedImageRequest? = null,
    val pinned: Boolean = false,
    /** Exact local retrieval snapshot used for this message; absent in legacy records. */
    val contextAssemblyTraceJson: String? = null,
    val id: String = java.util.UUID.randomUUID().toString()
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

    fun plainBase64(): String {
        if (!dataBase64.startsWith("data:", ignoreCase = true)) return dataBase64
        val marker = dataBase64.indexOf("base64,", ignoreCase = true)
        return if (marker >= 0) dataBase64.substring(marker + "base64,".length) else dataBase64
    }
}
