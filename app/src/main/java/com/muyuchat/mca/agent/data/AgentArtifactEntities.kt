package com.muyuchat.mca.agent.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `agent_artifacts` — logical artifact versions and the separate physical storage reference.
 *
 * * `relativePath` is the normalized task-relative logical path (`artifacts/...`).
 * * `version` is the per-path commit ordinal; `(taskId, relativePath, version)` is unique.
 * * `storageRef` points at the immutable physical version (named by artifactId/commitId) and is
 *   never overwritten in place.
 * * `latestKey` equals `relativePath` only for the latest committed version and is NULL otherwise;
 *   the unique `(taskId, latestKey)` index keeps exactly one latest logical mapping.
 * * `isLatest` is the queryable mirror of `latestKey` used by read paths.
 */
@Entity(
    tableName = "agent_artifacts",
    foreignKeys = [
        ForeignKey(
            entity = AgentTaskEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.NO_ACTION
        ),
        ForeignKey(
            entity = AgentInvocationEntity::class,
            parentColumns = ["invocationId"],
            childColumns = ["invocationId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index(
            value = ["taskId", "relativePath", "version"],
            unique = true,
            name = "index_agent_artifacts_taskId_relativePath_version"
        ),
        Index(value = ["taskId", "latestKey"], unique = true, name = "index_agent_artifacts_taskId_latestKey"),
        Index(value = ["taskId", "relativePath"], name = "index_agent_artifacts_taskId_relativePath"),
        Index(value = ["taskId", "commitState"], name = "index_agent_artifacts_taskId_commitState"),
        Index(value = ["taskId", "createdAt"], name = "index_agent_artifacts_taskId_createdAt"),
        Index(value = ["invocationId"], name = "index_agent_artifacts_invocationId")
    ]
)
data class AgentArtifactEntity(
    @PrimaryKey val artifactId: String,
    val taskId: String,
    val invocationId: String?,
    val relativePath: String,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String,
    val commitState: String,
    val storageRef: String,
    val version: Long,
    val isLatest: Int,
    val latestKey: String?,
    val commitId: String?,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "1") val schemaVersion: Int = 1
)

/**
 * `agent_artifact_commits` — durable cross-resource commit journal (architecture 10.3).
 *
 * Phase order: PREPARING → STAGED → RENAMED → COMMITTED, with FAILED/UNKNOWN as honest terminal
 * states. Recovery reads THIS row and the file hashes; it never re-executes a tool.
 */
@Entity(
    tableName = "agent_artifact_commits",
    foreignKeys = [
        ForeignKey(
            entity = AgentArtifactEntity::class,
            parentColumns = ["artifactId"],
            childColumns = ["artifactId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.NO_ACTION
        ),
        ForeignKey(
            entity = AgentInvocationEntity::class,
            parentColumns = ["invocationId"],
            childColumns = ["invocationId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index(value = ["artifactId"], name = "index_agent_artifact_commits_artifactId"),
        Index(value = ["invocationId"], name = "index_agent_artifact_commits_invocationId"),
        Index(value = ["phase", "updatedAt"], name = "index_agent_artifact_commits_phase_updatedAt"),
        Index(value = ["artifactId", "updatedAt"], name = "index_agent_artifact_commits_artifactId_updatedAt")
    ]
)
data class AgentArtifactCommitEntity(
    @PrimaryKey val commitId: String,
    val invocationId: String,
    val artifactId: String,
    val stagingRef: String,
    val targetLogicalPath: String,
    val expectedOldHash: String?,
    val newHash: String,
    val newSizeBytes: Long,
    val phase: String,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "1") val schemaVersion: Int = 1
)
