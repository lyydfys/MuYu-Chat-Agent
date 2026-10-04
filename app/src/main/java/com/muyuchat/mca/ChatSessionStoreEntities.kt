package com.muyuchat.mca

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.muyuchat.core.engine.Role

/**
 * Room table contracts for chat history: the @Entity data classes and their columns.
 *
 * Moved out of `ChatSessionStore.kt` verbatim. Table names, column names, @ColumnInfo defaults,
 * primary keys, indices and foreign keys are byte-identical to the original (the string-literal
 * multiset of this block is unchanged), and no @Database version or migration moved with it.
 */
@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val pinned: Boolean,
    val manualTitle: Boolean,
    val updatedAt: Long,
    val projectId: String? = null,
    val assistantId: String? = null,
    val assistantSnapshotJson: String? = null,
    val modelMode: String? = null,
    val modelId: String? = null,
    val appearanceJson: String? = null,
    val contextSummariesJson: String? = null,
    @ColumnInfo(defaultValue = "0") val mixedAssistantHistory: Boolean = false
)

@Entity(
    tableName = "image_assets",
    indices = [Index("createdAt"), Index("chatSessionId"), Index("projectId"), Index("favorite")]
)
data class ImageAssetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val uriString: String,
    val source: String,
    val prompt: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    @ColumnInfo(defaultValue = "")
    val generationMetadataJson: String,
    @ColumnInfo(defaultValue = "0")
    val favorite: Boolean = false,
    val chatSessionId: String?,
    val projectId: String? = null
)

@Entity(
    tableName = "file_assets",
    indices = [Index("createdAt"), Index("chatSessionId"), Index("projectId")]
)
data class FileAssetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mimeType: String,
    val text: String,
    val preview: String,
    val truncated: Boolean,
    val source: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val chatSessionId: String?,
    val projectId: String? = null
)

@Entity(
    tableName = "memories",
    indices = [Index("assistantId"), Index("scope"), Index("createdAt")]
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val assistantId: String,
    val scope: String,
    val content: String,
    val source: String,
    val createdAt: Long
)

@Entity(
    tableName = "assistant_memory_turns",
    indices = [Index(value = ["assistantId", "isSummarized", "createdAt"])]
)
data class AssistantMemoryTurnEntity(
    @PrimaryKey val id: String,
    val assistantId: String,
    val sessionId: String,
    val userText: String,
    val assistantText: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val isSummarized: Boolean = false
)

@Entity(
    tableName = "assistants",
    indices = [Index("updatedAt")]
)
data class AssistantEntity(
    @PrimaryKey val id: String,
    val name: String,
    val avatar: String,
    @ColumnInfo(defaultValue = "")
    val tag: String,
    val systemPrompt: String,
    val defaultModelMode: String,
    val defaultModelId: String?,
    val paramsJson: String,
    val characterCardJson: String?,
    val memoryEnabled: Boolean,
    @ColumnInfo(defaultValue = "12") val memorySummaryInterval: Int =
        AssistantRecord.DEFAULT_MEMORY_SUMMARY_INTERVAL,
    val webSearchEnabled: Boolean,
    val fileContextEnabled: Boolean,
    val appearanceJson: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "knowledge_bases",
    indices = [Index("updatedAt")]
)
data class KnowledgeBaseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val embeddingModelFingerprint: String?,
    val indexState: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "knowledge_documents",
    indices = [Index("knowledgeBaseId"), Index("contentHash")],
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeBaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["knowledgeBaseId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class KnowledgeDocumentEntity(
    @PrimaryKey val id: String,
    val knowledgeBaseId: String,
    val title: String,
    val source: String,
    val contentHash: String,
    val contentLength: Int,
    val chunkCount: Int,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "knowledge_chunks",
    indices = [Index("knowledgeBaseId"), Index("documentId")],
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeBaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["knowledgeBaseId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = KnowledgeDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class KnowledgeChunkEntity(
    @PrimaryKey val id: String,
    val knowledgeBaseId: String,
    val documentId: String,
    val position: Int,
    val content: String,
    val contentHash: String,
    val estimatedTokens: Int
)

@Entity(
    tableName = "chat_knowledge_base_bindings",
    primaryKeys = ["chatSessionId", "knowledgeBaseId"],
    indices = [Index("knowledgeBaseId")],
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatSessionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = KnowledgeBaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["knowledgeBaseId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ChatKnowledgeBaseBindingEntity(
    val chatSessionId: String,
    val knowledgeBaseId: String
)

@Entity(
    tableName = "chat_messages",
    primaryKeys = ["sessionId", "position"],
    indices = [Index("sessionId")]
)
data class ChatMessageEntity(
    val sessionId: String,
    val position: Int,
    val role: String,
    val content: String,
    val createdAt: Long,
    val tokenCount: Int?,
    @ColumnInfo(defaultValue = "")
    val reasoningContent: String = "",
    @ColumnInfo(defaultValue = "0")
    val reasoningDurationMs: Long = 0L,
    @ColumnInfo(defaultValue = "[]")
    val imageAttachmentsJson: String = "[]",
    @ColumnInfo(defaultValue = "[]")
    val sourceReferencesJson: String = "[]",
    @ColumnInfo(defaultValue = "{}")
    val webSearchTraceJson: String = "{}",
    val generationMetricsJson: String? = null,
    val generatedImageJson: String? = null,
    @ColumnInfo(defaultValue = "0")
    val pinned: Boolean = false,
    val contextAssemblyTraceJson: String? = null,
    @ColumnInfo(defaultValue = "''")
    val messageId: String = ""
)
