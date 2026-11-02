package com.muyuchat.mca.agent.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `agent_park_tokens` — one durable park/wake token per run.
 *
 * A run that parks in WAITING_APPROVAL / WAITING_USER writes this row before the owner releases its
 * admission slot, so the wake decision survives process death. The primary key is [runId]: a newer
 * park supersedes the previous token for the same run instead of accumulating stale ones.
 *
 * [wakeId] identifies the concrete park event; [AgentParkTokenDao.consume] refuses a token whose
 * wake id no longer matches, so a stale approval/question answer can never resume a later park.
 * [transcriptJson] is the bounded frozen transcript the owner needs to continue the loop; it is not
 * a UI projection and no chat/assistant query reads this table.
 */
@Entity(
    tableName = "agent_park_tokens",
    indices = [
        Index(value = ["wakeId"], unique = true, name = "index_agent_park_tokens_wakeId")
    ]
)
data class AgentParkTokenEntity(
    @PrimaryKey val runId: String,
    val epoch: Long,
    val checkpointRevision: Long,
    val wakeId: String,
    val kind: String,
    val approvalId: String?,
    val invocationId: String?,
    val questionId: String?,
    val transcriptJson: String,
    val createdAtMillis: Long
)
