package com.muyuchat.mca

import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGenerationMetrics
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.deduplicateVisionAttachments
import org.json.JSONArray
import org.json.JSONObject

/**
 * Room row <-> domain record mapping and persisted-size accounting for chat history.
 *
 * Moved out of `ChatSessionStore.kt` verbatim: `ChatHistoryPersistenceLimits`,
 * `ChatHistoryPersistenceBounds`, the serialized-column size constants and helpers, and every
 * record<->entity mapper. JSON/column names, defaults, null handling, ordering and id/foreign-key
 * relations are unchanged.
 *
 * Only visibility moved: the mappers the DAO and the store still call are `internal` now (each has a
 * single declaration); the size helpers stay private to this file. The JSON-column helpers that
 * `ChatMessage.toEntity` / `ChatMessageEntity.toChatMessage` call live in
 * `ChatMessageJsonFields.kt` under responsibility-prefixed names.
 */
/** Hard caps for persisted chat history, independent of the live conversation buffer. */
internal data class ChatHistoryPersistenceLimits(
    val maxSessions: Int = 100,
    val maxMessages: Int = 2_000,
    val maxSerializedBytes: Long = 16L * 1024L * 1024L
) {
    init {
        require(maxSessions > 0) { "maxSessions must be positive." }
        require(maxMessages > 0) { "maxMessages must be positive." }
        require(maxSerializedBytes > 0L) { "maxSerializedBytes must be positive." }
    }
}

/**
 * Bounds the Room representation before a write. Inline image bytes are not
 * counted because [ChatMessage.toEntity] deliberately never persists them.
 */
/**
 * A persisted session may disappear only when the caller explicitly deleted it. Missing rows
 * from a UI snapshot are not enough: a transient load failure produces a partial projection,
 * and pruning against it would erase readable history.
 */
internal fun sessionsEligibleForDeletion(
    persistedIds: Set<String>,
    requestedIds: Set<String>,
    explicitlyDeletedSessionIds: Set<String>
): Set<String> = explicitlyDeletedSessionIds
    .asSequence()
    .filter { it in persistedIds && it !in requestedIds }
    .toSet()

internal object ChatHistoryPersistenceBounds {    fun bound(
        records: List<ChatSessionRecord>,
        limits: ChatHistoryPersistenceLimits = ChatHistoryPersistenceLimits()
    ): List<ChatSessionRecord> {
        val prioritized = records
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<ChatSessionRecord>> { it.value.pinned }
                    .thenByDescending { it.value.updatedAt }
                    .thenBy { it.index }
            )
            .asSequence()
            .distinctBy { it.value.id }
            .take(limits.maxSessions)
            .map { it.value }
            .toList()

        var usedBytes = 0L
        var usedMessages = 0
        val retained = mutableListOf<ChatSessionRecord>()
        for (record in prioritized) {
            val sessionBytes = record.toEntity().serializedByteCount()
            if (!fitsWithin(usedBytes, sessionBytes, limits.maxSerializedBytes)) continue

            var recordBytes = sessionBytes
            val tailMessages = mutableListOf<ChatMessage>()
            for (index in record.messages.indices.reversed()) {
                if (usedMessages + tailMessages.size >= limits.maxMessages) break
                val message = record.messages[index]
                val messageBytes = message.toEntity(record.id, index).serializedByteCount()
                if (
                    !fitsWithin(
                        usedBytes.saturatingAdd(recordBytes),
                        messageBytes,
                        limits.maxSerializedBytes
                    )
                ) {
                    // Keeping an older message while dropping the newest one
                    // makes the persisted transcript misleading. Stop at its tail.
                    break
                }
                tailMessages += message
                recordBytes = recordBytes.saturatingAdd(messageBytes)
            }

            retained += record.copy(messages = tailMessages.asReversed())
            usedBytes = usedBytes.saturatingAdd(recordBytes)
            usedMessages += tailMessages.size
        }
        return retained
    }

    internal fun serializedByteCount(records: List<ChatSessionRecord>): Long =
        records.fold(0L) { total, record ->
            val sessionBytes = record.toEntity().serializedByteCount()
            val messageBytes = record.messages.foldIndexed(0L) { index, messagesTotal, message ->
                messagesTotal.saturatingAdd(message.toEntity(record.id, index).serializedByteCount())
            }
            total.saturatingAdd(sessionBytes).saturatingAdd(messageBytes)
        }

    private fun fitsWithin(current: Long, next: Long, limit: Long): Boolean =
        current <= limit && next <= limit - current
}

private const val MAX_MEMORY_RECORD_CONTENT_CHARS = 12_000
private const val MAX_MEMORY_ID_CHARS = 128

private const val SERIALIZED_ROW_OVERHEAD_BYTES = 16L
private const val SERIALIZED_STRING_LENGTH_BYTES = 4L
private const val SERIALIZED_NULL_FIELD_BYTES = 1L

/**
 * Counts persisted column payloads without allocating a UTF-8 byte array.
 * The fixed overhead keeps the limit conservative with respect to Room row
 * metadata while avoiding image attachment inline data, which is not stored.
 */
private fun ChatSessionEntity.serializedByteCount(): Long =
    SERIALIZED_ROW_OVERHEAD_BYTES
        .saturatingAdd(id.serializedFieldByteCount())
        .saturatingAdd(title.serializedFieldByteCount())
        .saturatingAdd(1L) // pinned
        .saturatingAdd(1L) // manualTitle
        .saturatingAdd(8L) // updatedAt
        .saturatingAdd(projectId.nullableSerializedFieldByteCount())
        .saturatingAdd(assistantId.nullableSerializedFieldByteCount())
        .saturatingAdd(assistantSnapshotJson.nullableSerializedFieldByteCount())
        .saturatingAdd(modelMode.nullableSerializedFieldByteCount())
        .saturatingAdd(modelId.nullableSerializedFieldByteCount())
        .saturatingAdd(appearanceJson.nullableSerializedFieldByteCount())
        .saturatingAdd(contextSummariesJson.nullableSerializedFieldByteCount())
        .saturatingAdd(1L) // mixedAssistantHistory

private fun ChatMessageEntity.serializedByteCount(): Long =
    SERIALIZED_ROW_OVERHEAD_BYTES
        .saturatingAdd(sessionId.serializedFieldByteCount())
        .saturatingAdd(4L) // position
        .saturatingAdd(role.serializedFieldByteCount())
        .saturatingAdd(messageId.serializedFieldByteCount())
        .saturatingAdd(content.serializedFieldByteCount())
        .saturatingAdd(8L) // createdAt
        .saturatingAdd(if (tokenCount == null) SERIALIZED_NULL_FIELD_BYTES else 5L)
        .saturatingAdd(reasoningContent.serializedFieldByteCount())
        .saturatingAdd(8L) // reasoningDurationMs
        .saturatingAdd(imageAttachmentsJson.serializedFieldByteCount())
        .saturatingAdd(sourceReferencesJson.serializedFieldByteCount())
        .saturatingAdd(webSearchTraceJson.serializedFieldByteCount())
        .saturatingAdd(generationMetricsJson.nullableSerializedFieldByteCount())
        .saturatingAdd(generatedImageJson.nullableSerializedFieldByteCount())
        .saturatingAdd(1L)
        .saturatingAdd(contextAssemblyTraceJson.nullableSerializedFieldByteCount())

private fun String.serializedFieldByteCount(): Long =
    SERIALIZED_STRING_LENGTH_BYTES.saturatingAdd(serializedUtf8ByteCount())

private fun String?.nullableSerializedFieldByteCount(): Long =
    if (this == null) SERIALIZED_NULL_FIELD_BYTES else serializedFieldByteCount()

private fun String.serializedUtf8ByteCount(): Long {
    var total = 0L
    var index = 0
    while (index < length) {
        val character = this[index]
        val bytes = when {
            character <= '\u007f' -> 1L
            character <= '\u07ff' -> 2L
            Character.isHighSurrogate(character) &&
                index + 1 < length && Character.isLowSurrogate(this[index + 1]) -> {
                index += 1
                4L
            }
            else -> 3L
        }
        total = total.saturatingAdd(bytes)
        index += 1
    }
    return total
}

private fun Long.saturatingAdd(value: Long): Long = when {
    this < 0L || value < 0L -> Long.MAX_VALUE
    this > Long.MAX_VALUE - value -> Long.MAX_VALUE
    else -> this + value
}

internal fun ChatSessionRecord.toEntity(): ChatSessionEntity =
    ChatSessionEntity(
        id = id,
        title = title,
        pinned = pinned,
        manualTitle = manualTitle,
        updatedAt = updatedAt,
        projectId = projectId,
        assistantId = assistantId,
        // Oversized JSON is offloaded to an app-private file so one big character card can never
        // push a `chat_sessions` row past the 2 MB CursorWindow and break every session read.
        assistantSnapshotJson = SessionJsonOffload.offload(id, assistantSnapshot?.toJsonString()),
        modelMode = modelMode,
        modelId = modelId,
        appearanceJson = SessionJsonOffload.offload(id, appearanceOverride?.toJsonString()),
        contextSummariesJson = SessionJsonOffload.offload(id, contextSummariesToJson(contextSummaries)),
        mixedAssistantHistory = mixedAssistantHistory
    )

internal fun ImageAssetRecord.toEntity(): ImageAssetEntity =
    ImageAssetEntity(
        id = id,
        name = name,
        uriString = uriString,
        source = source,
        prompt = prompt,
        createdAt = createdAt,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        generationMetadataJson = generationMetadataJson,
        favorite = favorite,
        chatSessionId = chatSessionId,
        projectId = projectId
    )

internal fun FileAssetRecord.toEntity(): FileAssetEntity =
    FileAssetEntity(
        id = id,
        name = name,
        mimeType = mimeType,
        text = text,
        preview = preview,
        truncated = truncated,
        source = source,
        createdAt = createdAt,
        sizeBytes = sizeBytes,
        chatSessionId = chatSessionId,
        projectId = projectId
    )

internal fun MemoryRecord.toEntity(): MemoryEntity =
    MemoryEntity(
        id = id,
        assistantId = assistantId,
        scope = scope,
        content = content,
        source = source,
        createdAt = createdAt
    )

internal fun MemoryRecord.boundedForPersistence(): MemoryRecord? {
    if (id.isBlank() || id.length > MAX_MEMORY_ID_CHARS ||
        assistantId.isBlank() || assistantId.length > MAX_MEMORY_ID_CHARS ||
        scope.isBlank() || scope.length > 64 || source.isBlank() || source.length > 32 ||
        content.isBlank() || createdAt < 0L
    ) return null
    return copy(content = content.trim().take(MAX_MEMORY_RECORD_CONTENT_CHARS))
}

internal fun AssistantMemoryTurnRecord.boundedForPersistence(): AssistantMemoryTurnRecord? {
    if (id.isBlank() || id.length > MAX_MEMORY_ID_CHARS ||
        assistantId.isBlank() || assistantId.length > MAX_MEMORY_ID_CHARS ||
        sessionId.isBlank() || sessionId.length > MAX_MEMORY_ID_CHARS ||
        (userText.isBlank() && assistantText.isBlank()) || createdAt < 0L
    ) return null
    return copy(
        userText = userText.trim().take(AssistantMemoryTurnRecord.MAX_TEXT_CHARS),
        assistantText = assistantText.trim().take(AssistantMemoryTurnRecord.MAX_TEXT_CHARS)
    )
}

internal fun AssistantMemoryTurnRecord.toEntity(): AssistantMemoryTurnEntity =
    AssistantMemoryTurnEntity(
        id = id,
        assistantId = assistantId,
        sessionId = sessionId,
        userText = userText,
        assistantText = assistantText,
        createdAt = createdAt
    )

internal fun AssistantRecord.toEntity(): AssistantEntity =
    AssistantEntity(
        id = id,
        name = name,
        avatar = avatar,
        tag = tag,
        systemPrompt = systemPrompt,
        defaultModelMode = defaultModelMode,
        defaultModelId = defaultModelId,
        paramsJson = paramsJson,
        characterCardJson = characterCardJson,
        memoryEnabled = memoryEnabled,
        memorySummaryInterval = memorySummaryInterval.coerceIn(
            AssistantRecord.MIN_MEMORY_SUMMARY_INTERVAL,
            AssistantRecord.MAX_MEMORY_SUMMARY_INTERVAL
        ),
        webSearchEnabled = webSearchEnabled,
        fileContextEnabled = fileContextEnabled,
        appearanceJson = appearance.takeUnless { it.isDefault }?.toJsonString(),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

internal fun ChatMessage.toEntity(sessionId: String, position: Int): ChatMessageEntity =
    ChatMessageEntity(
        messageId = id,
        sessionId = sessionId,
        position = position,
        role = role.name,
        content = content,
        createdAt = createdAt,
        tokenCount = tokenCount,
        reasoningContent = reasoningContent,
        reasoningDurationMs = reasoningDurationMs,
        imageAttachmentsJson = imageAttachments
            .deduplicateVisionAttachments()
            .chatMessageAttachmentJson(includeInlineData = false),
        sourceReferencesJson = sourceReferences.chatMessageSourceReferenceJson(),
        webSearchTraceJson = webSearchTrace.chatMessageWebSearchTraceJson(),
        generationMetricsJson = generationMetrics?.toJson()?.toString(),
        generatedImageJson = generatedImageRequest?.toJson()?.toString(),
        pinned = pinned,
        contextAssemblyTraceJson = contextAssemblyTraceJson
    )

internal fun ChatMessageEntity.toChatMessage(): ChatMessage =
    ChatMessage(
        id = messageId.ifBlank { "$sessionId:$position:$createdAt" },
        role = runCatching { Role.valueOf(role) }.getOrDefault(Role.USER),
        content = content,
        createdAt = createdAt,
        tokenCount = tokenCount,
        reasoningContent = reasoningContent,
        reasoningDurationMs = reasoningDurationMs,
        imageAttachments = runCatching {
            JSONArray(imageAttachmentsJson)
                .chatMessageImageAttachments()
                .deduplicateVisionAttachments()
        }.getOrDefault(emptyList()),
        sourceReferences = runCatching { JSONArray(sourceReferencesJson).chatMessageSourceReferences() }.getOrDefault(emptyList()),
        webSearchTrace = runCatching { JSONObject(webSearchTraceJson).chatMessageWebSearchTrace() }.getOrNull(),
        generationMetrics = runCatching {
            generationMetricsJson?.let { ChatGenerationMetrics.fromJson(JSONObject(it)) }
        }.getOrNull(),
        generatedImageRequest = runCatching {
            generatedImageJson?.let { ChatGeneratedImageRequest.fromJsonOrNull(JSONObject(it)) }
        }.getOrNull(),
        pinned = pinned,
        contextAssemblyTraceJson = contextAssemblyTraceJson
    )

internal fun ImageAssetEntity.toImageAssetRecord(): ImageAssetRecord =
    ImageAssetRecord(
        id = id,
        name = name,
        uriString = uriString,
        source = source,
        prompt = prompt,
        createdAt = createdAt,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        generationMetadataJson = generationMetadataJson,
        favorite = favorite,
        chatSessionId = chatSessionId,
        projectId = projectId
    )

internal fun FileAssetEntity.toFileAssetRecord(): FileAssetRecord =
    FileAssetRecord(
        id = id,
        name = name,
        mimeType = mimeType,
        text = text,
        preview = preview,
        truncated = truncated,
        source = source,
        createdAt = createdAt,
        sizeBytes = sizeBytes,
        chatSessionId = chatSessionId,
        projectId = projectId
    )

internal fun MemoryEntity.toMemoryRecord(): MemoryRecord =
    MemoryRecord(
        id = id,
        assistantId = assistantId,
        scope = scope,
        content = content,
        source = source,
        createdAt = createdAt
    )

internal fun AssistantMemoryTurnEntity.toRecord(): AssistantMemoryTurnRecord =
    AssistantMemoryTurnRecord(
        id = id,
        assistantId = assistantId,
        sessionId = sessionId,
        userText = userText,
        assistantText = assistantText,
        createdAt = createdAt
    )

internal fun AssistantEntity.toAssistantRecord(): AssistantRecord =
    AssistantRecord(
        id = id,
        name = name,
        avatar = avatar,
        tag = tag,
        systemPrompt = systemPrompt,
        defaultModelMode = defaultModelMode,
        defaultModelId = defaultModelId,
        paramsJson = paramsJson,
        characterCardJson = characterCardJson,
        memoryEnabled = memoryEnabled,
        memorySummaryInterval = memorySummaryInterval.coerceIn(
            AssistantRecord.MIN_MEMORY_SUMMARY_INTERVAL,
            AssistantRecord.MAX_MEMORY_SUMMARY_INTERVAL
        ),
        webSearchEnabled = webSearchEnabled,
        fileContextEnabled = fileContextEnabled,
        appearance = ChatAppearance.fromJsonOrNull(appearanceJson) ?: ChatAppearance(),
        createdAt = createdAt,
        updatedAt = updatedAt
    )
