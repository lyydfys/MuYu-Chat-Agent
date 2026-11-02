package com.muyuchat.mca.agent.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `agent_workflow_checkpoints` — one durable graph frame per task-root key.
 *
 * [payloadJson] is the strict `WorkflowCheckpointJson` document; the row is the Room successor of
 * the file CAS store. [revision] is the same compare-and-set token: a writer may only replace a row
 * with a strictly newer revision. The primary key is the frame key (`wftask-<hash>`), so a successor
 * run of the same task loads the predecessor frame.
 */
@Entity(tableName = "agent_workflow_checkpoints")
data class AgentWorkflowCheckpointEntity(
    @PrimaryKey val frameKey: String,
    val revision: Long,
    val definitionId: String,
    val definitionVersion: Long,
    val payloadJson: String,
    val updatedAtMillis: Long
)
