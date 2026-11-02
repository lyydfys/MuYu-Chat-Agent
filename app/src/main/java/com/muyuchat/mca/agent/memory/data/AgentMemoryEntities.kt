package com.muyuchat.mca.agent.memory.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room 29 memory tables (plan §7.1). Additive only.
 *
 * These four tables are the first-party persistence of the memory domain. They share `mca.db` with
 * chat/assistant/knowledge and the Agent tables, but no other owner reads or writes them: the chat
 * `memories` / `assistant_memory_turns` / `knowledge_*` rows are *not* reused as a memory write
 * target, and nothing here writes into them.
 *
 * Design rules:
 * - ids are generated stable strings, never paths or user text;
 * - `(spaceId, contentHash)` is unique, which is what makes ingest idempotent per source;
 * - `revoked` is the tombstone column retrieval filters on; rows are retained for audit;
 * - `schemaVersion` matches the convention of every other Agent table.
 */
@Entity(
    tableName = "agent_memory_spaces",
    indices = [
        Index(value = ["createdAt"], name = "index_agent_memory_spaces_createdAt")
    ]
)
data class AgentMemorySpaceEntity(
    @PrimaryKey val spaceId: String,
    val displayName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long,
    @ColumnInfo(defaultValue = "1") val schemaVersion: Int = 1
)

@Entity(
    tableName = "agent_memory_items",
    foreignKeys = [
        ForeignKey(
            entity = AgentMemorySpaceEntity::class,
            parentColumns = ["spaceId"],
            childColumns = ["spaceId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index(
            value = ["spaceId", "contentHash"],
            unique = true,
            name = "index_agent_memory_items_spaceId_contentHash"
        ),
        Index(
            value = ["spaceId", "revoked", "updatedAt"],
            name = "index_agent_memory_items_spaceId_revoked_updatedAt"
        ),
        Index(value = ["contentHash"], name = "index_agent_memory_items_contentHash")
    ]
)
data class AgentMemoryItemEntity(
    @PrimaryKey val memoryId: String,
    val spaceId: String,
    val title: String,
    val text: String,
    val contentHash: String,
    val sourceKind: String,
    val sourceRef: String?,
    val tokenCount: Int,
    val chunkCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long,
    @ColumnInfo(defaultValue = "0") val revoked: Int = 0,
    val revokedAt: Long?,
    @ColumnInfo(defaultValue = "1") val schemaVersion: Int = 1
)

@Entity(
    tableName = "agent_memory_chunks",
    foreignKeys = [
        ForeignKey(
            entity = AgentMemoryItemEntity::class,
            parentColumns = ["memoryId"],
            childColumns = ["memoryId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.NO_ACTION
        ),
        ForeignKey(
            entity = AgentMemorySpaceEntity::class,
            parentColumns = ["spaceId"],
            childColumns = ["spaceId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index(
            value = ["memoryId", "position"],
            unique = true,
            name = "index_agent_memory_chunks_memoryId_position"
        ),
        Index(
            value = ["spaceId", "memoryId", "position"],
            name = "index_agent_memory_chunks_spaceId_memoryId_position"
        ),
        Index(value = ["contentHash"], name = "index_agent_memory_chunks_contentHash")
    ]
)
data class AgentMemoryChunkEntity(
    @PrimaryKey val chunkId: String,
    val memoryId: String,
    val spaceId: String,
    val position: Int,
    val text: String,
    val contentHash: String,
    val estimatedTokens: Int,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "1") val schemaVersion: Int = 1
)

/**
 * Frozen per-task memory selection. Insert-only per `taskId`; only `revoked`/`revokedAt` may change.
 *
 * There is deliberately no foreign key to `agent_tasks`: a selection is addressed by `taskId` and an
 * orphan row is inert (grant resolution returns it, the executor then re-checks space liveness and
 * denies when the task is gone). This keeps task deletion from being blocked by memory rows.
 */
@Entity(
    tableName = "agent_memory_task_selections",
    indices = [
        Index(value = ["selectionHash"], name = "index_agent_memory_task_selections_selectionHash"),
        Index(value = ["revoked"], name = "index_agent_memory_task_selections_revoked")
    ]
)
data class AgentMemoryTaskSelectionEntity(
    @PrimaryKey val taskId: String,
    val spaceIdsJson: String,
    val selectionHash: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val revoked: Int = 0,
    val revokedAt: Long?,
    @ColumnInfo(defaultValue = "1") val schemaVersion: Int = 1
)
