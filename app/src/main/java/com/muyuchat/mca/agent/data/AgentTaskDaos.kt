package com.muyuchat.mca.agent.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Task/workspace queries. Every list query takes a `LIMIT`; cursor paging is expressed in SQL so a
 * caller can never load an unbounded task history. Observing a query never starts work.
 */
@Dao
interface AgentTaskDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: AgentTaskEntity)

    @Query("SELECT * FROM agent_tasks WHERE taskId = :taskId")
    suspend fun find(taskId: String): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE workspaceId = :workspaceId LIMIT 1")
    suspend fun findByWorkspace(workspaceId: String): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE taskId = :taskId")
    fun observe(taskId: String): Flow<AgentTaskEntity?>

    @Query(
        "UPDATE agent_tasks SET displayStatus = :displayStatus, lastRunId = :lastRunId, " +
            "revision = :revision, updatedAt = :updatedAt " +
            "WHERE taskId = :taskId AND revision = :expectedRevision"
    )
    suspend fun updateSummaryCas(
        taskId: String,
        displayStatus: String,
        lastRunId: String?,
        revision: Long,
        updatedAt: Long,
        expectedRevision: Long
    ): Int

    @Query(
        "SELECT * FROM agent_tasks WHERE (:includeArchived = 1 OR lifecycle = 'ACTIVE') " +
            "AND (:cursorUpdatedAt IS NULL OR updatedAt < :cursorUpdatedAt " +
            "OR (updatedAt = :cursorUpdatedAt AND taskId < :cursorTaskId)) " +
            "ORDER BY updatedAt DESC, taskId DESC LIMIT :limit"
    )
    fun observePage(
        includeArchived: Boolean,
        cursorUpdatedAt: Long?,
        cursorTaskId: String?,
        limit: Int
    ): Flow<List<AgentTaskEntity>>

    @Query(
        "SELECT * FROM agent_tasks WHERE (:includeArchived = 1 OR lifecycle = 'ACTIVE') " +
            "ORDER BY updatedAt DESC, taskId DESC LIMIT :limit"
    )
    suspend fun page(
        includeArchived: Boolean,
        limit: Int
    ): List<AgentTaskEntity>

    @Query(
        "SELECT * FROM agent_tasks WHERE (:includeArchived = 1 OR lifecycle = 'ACTIVE') " +
            "AND (updatedAt < :cursorUpdatedAt " +
            "OR (updatedAt = :cursorUpdatedAt AND taskId < :cursorTaskId)) " +
            "ORDER BY updatedAt DESC, taskId DESC LIMIT :limit"
    )
    suspend fun pageAfter(
        includeArchived: Boolean,
        cursorUpdatedAt: Long,
        cursorTaskId: String,
        limit: Int
    ): List<AgentTaskEntity>

    @Query("SELECT COUNT(*) FROM agent_tasks WHERE lifecycle = 'ACTIVE'")
    suspend fun countActive(): Int

    /**
     * CAS transition to TOMBSTONED (K14). The caller only invokes this after every active run has
     * stopped; the lifecycle guard makes a double tombstone a no-op for idempotent retries.
     */
    @Query(
        "UPDATE agent_tasks SET lifecycle = 'TOMBSTONED', updatedAt = :updatedAt, revision = :newRevision " +
            "WHERE taskId = :taskId AND revision = :expectedRevision AND lifecycle != 'TOMBSTONED'"
    )
    suspend fun markTombstonedCas(
        taskId: String,
        expectedRevision: Long,
        newRevision: Long,
        updatedAt: Long
    ): Int
}

/** Workspace rows are created once per task root; reads are by primary key only. */
@Dao
interface AgentWorkspaceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(workspace: AgentWorkspaceEntity)

    @Query("SELECT * FROM agent_workspaces WHERE workspaceId = :workspaceId")
    suspend fun find(workspaceId: String): AgentWorkspaceEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(workspace: AgentWorkspaceEntity): Long

    @Query("UPDATE agent_workspaces SET updatedAt = :updatedAt WHERE workspaceId = :workspaceId")
    suspend fun touch(workspaceId: String, updatedAt: Long): Int
}
