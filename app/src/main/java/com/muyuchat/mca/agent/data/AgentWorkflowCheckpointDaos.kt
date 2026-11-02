package com.muyuchat.mca.agent.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Frame rows are read inside the store's transaction and replaced with `REPLACE` only after the
 * in-memory revision check, so the store keeps the file CAS semantics without a second writer.
 */
@Dao
interface AgentWorkflowCheckpointDao {
    @Query("SELECT * FROM agent_workflow_checkpoints WHERE frameKey = :frameKey")
    suspend fun find(frameKey: String): AgentWorkflowCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AgentWorkflowCheckpointEntity)

    @Query("DELETE FROM agent_workflow_checkpoints WHERE frameKey = :frameKey")
    suspend fun delete(frameKey: String): Int
}
