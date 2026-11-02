package com.muyuchat.mca.agent.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `agent_events` — append-only audit/timeline rows committed with the state change they describe.
 *
 * `(runId, sequence)` is unique; paging always uses `sequence > cursor ORDER BY sequence LIMIT n`.
 * Payloads are references only (`redactedPayloadRef`); the row itself stays bounded.
 */
@Entity(
    tableName = "agent_events",
    foreignKeys = [
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index(value = ["runId", "sequence"], unique = true, name = "index_agent_events_runId_sequence"),
        Index(value = ["commandId"], name = "index_agent_events_commandId"),
        Index(value = ["createdAt"], name = "index_agent_events_createdAt")
    ]
)
data class AgentEventEntity(
    @PrimaryKey val eventId: String,
    val runId: String,
    val sequence: Long,
    val type: String,
    val version: Int,
    val createdAt: Long,
    val commandId: String?,
    val causationId: String?,
    val redactedPayloadRef: String?,
    val summary: String?,
    @ColumnInfo(defaultValue = "1") val schemaVersion: Int = 1
)

/**
 * `agent_commands` — durable command idempotency log.
 *
 * [contentHash] is the stable SHA-256 of the transition content (excluding commandId and
 * expectedRevision). [resultRef] is the bounded JSON encoding of the committed [AgentCommitResult];
 * it is self-contained so a replay after process restart never depends on another table. The row is
 * inserted in the same transaction as the business change.
 */
@Entity(
    tableName = "agent_commands",
    indices = [
        Index(value = ["scopeId", "createdAt"], name = "index_agent_commands_scopeId_createdAt"),
        Index(value = ["contentHash"], name = "index_agent_commands_contentHash")
    ]
)
data class AgentCommandEntity(
    @PrimaryKey val commandId: String,
    val scopeId: String,
    val commandType: String,
    val contentHash: String,
    val resultRef: String,
    val appliedRevision: Long,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "1") val schemaVersion: Int = 1
)

/**
 * `agent_extensions` — installed Skill/MCP metadata only.
 *
 * Credentials never live here (architecture 10.1); [manifestRef] is a bounded payload reference.
 */
@Entity(
    tableName = "agent_extensions",
    indices = [
        Index(value = ["kind", "enabled"], name = "index_agent_extensions_kind_enabled"),
        Index(value = ["sourceHash"], name = "index_agent_extensions_sourceHash"),
        Index(value = ["updatedAt"], name = "index_agent_extensions_updatedAt")
    ]
)
data class AgentExtensionEntity(
    @PrimaryKey val extensionId: String,
    val kind: String,
    val version: String,
    val source: String,
    val sourceHash: String,
    val enabled: Int,
    val manifestRef: String?,
    val installedAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "1") val schemaVersion: Int = 1
)

/**
 * `agent_grants` — local user grants only. Export excludes this table (architecture 10.6).
 *
 * No API key or credential may be serialized into [scopeJson] or [modelFingerprint].
 */
@Entity(
    tableName = "agent_grants",
    foreignKeys = [
        ForeignKey(
            entity = AgentTaskEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.NO_ACTION
        ),
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index(value = ["taskId", "scopeType"], name = "index_agent_grants_taskId_scopeType"),
        Index(value = ["runId"], name = "index_agent_grants_runId"),
        Index(value = ["expiresAt"], name = "index_agent_grants_expiresAt")
    ]
)
data class AgentGrantEntity(
    @PrimaryKey val grantId: String,
    val taskId: String,
    val runId: String?,
    val scopeType: String,
    val scopeJson: String,
    val policyVersion: Long,
    val modelFingerprint: String?,
    val grantedAt: Long,
    val expiresAt: Long?,
    val revokedAt: Long?,
    val revokedReason: String?,
    @ColumnInfo(defaultValue = "1") val schemaVersion: Int = 1
)

/**
 * `agent_command_results` — runtime command-level idempotency, deliberately separate from
 * `agent_commands`.
 *
 * The K03 owner fingerprints the command DTO (goal/model/policy...), while [AgentCommandEntity]
 * fingerprints the frozen transition it produces. Sharing one table would turn a legitimate
 * transition retry into a false `IDEMPOTENCY_CONFLICT`, so commands keep their own result row:
 * `commandId` PK, the owner-computed content hash, the applied revision and optional
 * taskRevision/runId for replay responses.
 */
@Entity(
    tableName = "agent_command_results",
    indices = [
        Index(value = ["contentHash"], name = "index_agent_command_results_contentHash"),
        Index(value = ["createdAt"], name = "index_agent_command_results_createdAt")
    ]
)
data class AgentCommandResultEntity(
    @PrimaryKey val commandId: String,
    val contentHash: String,
    val appliedRevision: Long,
    val taskRevision: Long?,
    val runId: String?,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "1") val schemaVersion: Int = 1
)
