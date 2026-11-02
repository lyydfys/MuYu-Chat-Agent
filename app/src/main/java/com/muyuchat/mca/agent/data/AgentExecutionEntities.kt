package com.muyuchat.mca.agent.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `agent_model_attempts` — one model generation attempt per row.
 *
 * `backendRequestId` is unique and never reused. `(runId, stepIndex, ordinal)` is the always-present
 * attempt identity; `stepId` is a nullable denormalized link because the frozen `AgentModelAttempt`
 * contract carries `stepIndex` rather than `stepId`. The additional `(runId, stepId, ordinal)` unique
 * index pins the persisted relationship when the link is present. `stopRequestedAt` records the
 * scoped stop request time required by the recovery/stop-proof evidence.
 */
@Entity(
    tableName = "agent_model_attempts",
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
        Index(value = ["backendRequestId"], unique = true, name = "index_agent_model_attempts_backendRequestId"),
        Index(
            value = ["runId", "stepIndex", "ordinal"],
            unique = true,
            name = "index_agent_model_attempts_runId_stepIndex_ordinal"
        ),
        Index(
            value = ["runId", "stepId", "ordinal"],
            unique = true,
            name = "index_agent_model_attempts_runId_stepId_ordinal"
        ),
        Index(value = ["runId", "terminal"], name = "index_agent_model_attempts_runId_terminal")
    ]
)
data class AgentModelAttemptEntity(
    @PrimaryKey val attemptId: String,
    val runId: String,
    val stepId: String?,
    val stepIndex: Int,
    val ordinal: Int,
    val backendRequestId: String,
    val backendIdentityRef: String?,
    val bindingFingerprint: String,
    val terminal: String,
    val usageJson: String?,
    val stopRequestedAt: Long?,
    val startedAt: Long,
    val finishedAt: Long?,
    @ColumnInfo(defaultValue = "1") val schemaVersion: Int = 1
)

/** `agent_steps` — run-scoped step ledger. `(runId, stepIndex)` is unique. */
@Entity(
    tableName = "agent_steps",
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
        Index(value = ["runId", "stepIndex"], unique = true, name = "index_agent_steps_runId_stepIndex"),
        Index(value = ["runId", "status"], name = "index_agent_steps_runId_status"),
        Index(value = ["modelAttemptId"], name = "index_agent_steps_modelAttemptId"),
        Index(value = ["invocationId"], name = "index_agent_steps_invocationId")
    ]
)
data class AgentStepEntity(
    @PrimaryKey val stepId: String,
    val runId: String,
    val stepIndex: Int,
    val kind: String,
    val status: String,
    val modelAttemptId: String?,
    val invocationId: String?,
    val startedAt: Long,
    val endedAt: Long?,
    @ColumnInfo(defaultValue = "1") val schemaVersion: Int = 1
)

/**
 * `agent_invocations` — frozen tool invocation and its terminal result.
 *
 * `idempotencyKey` is unique. `(stepId, callOrdinal)` is unique. `runningStepKey` is non-null only
 * while the invocation is RUNNING; its unique index therefore enforces "at most one running tool
 * per step" in SQLite for both fresh and migrated databases (NULL values do not collide).
 */
@Entity(
    tableName = "agent_invocations",
    foreignKeys = [
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.NO_ACTION
        ),
        ForeignKey(
            entity = AgentStepEntity::class,
            parentColumns = ["stepId"],
            childColumns = ["stepId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index(value = ["stepId", "callOrdinal"], unique = true, name = "index_agent_invocations_stepId_callOrdinal"),
        Index(value = ["idempotencyKey"], unique = true, name = "index_agent_invocations_idempotencyKey"),
        Index(value = ["runningStepKey"], unique = true, name = "index_agent_invocations_runningStepKey"),
        Index(value = ["runId", "status"], name = "index_agent_invocations_runId_status"),
        Index(value = ["stepId"], name = "index_agent_invocations_stepId"),
        Index(value = ["runId"], name = "index_agent_invocations_runId")
    ]
)
data class AgentInvocationEntity(
    @PrimaryKey val invocationId: String,
    val runId: String,
    val stepId: String,
    val callOrdinal: Int,
    val toolKey: String,
    val toolVersion: String,
    val schemaHash: String,
    val frozenArgumentsRef: String?,
    val argumentsHash: String,
    val resourceFingerprint: String,
    val status: String,
    val idempotencyKey: String,
    val resultRef: String?,
    val possibleSideEffects: Int,
    val runningStepKey: String?,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "1") val schemaVersion: Int = 1
)

/**
 * `agent_approvals` — one-shot approval bound to the exact invocation/schema/resource/epoch.
 *
 * `pendingInvocationKey` equals [invocationId] only while [status] is PENDING and is NULL otherwise;
 * its unique index enforces "at most one pending approval per invocation" in SQL for both fresh and
 * migrated databases. `(invocationId)` is indexed for history.
 */
@Entity(
    tableName = "agent_approvals",
    foreignKeys = [
        ForeignKey(
            entity = AgentInvocationEntity::class,
            parentColumns = ["invocationId"],
            childColumns = ["invocationId"],
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
        Index(
            value = ["pendingInvocationKey"],
            unique = true,
            name = "index_agent_approvals_pendingInvocationKey"
        ),
        Index(value = ["invocationId"], name = "index_agent_approvals_invocationId"),
        Index(value = ["runId", "status"], name = "index_agent_approvals_runId_status"),
        Index(value = ["status", "expiresAt"], name = "index_agent_approvals_status_expiresAt"),
        Index(value = ["runId"], name = "index_agent_approvals_runId")
    ]
)
data class AgentApprovalEntity(
    @PrimaryKey val approvalId: String,
    val runId: String,
    val invocationId: String,
    val principal: String,
    val argumentsHash: String,
    val toolSchemaHash: String,
    val resourceFingerprint: String,
    val policyVersion: Long,
    val runEpoch: Long,
    val expiresAt: Long,
    val status: String,
    val decision: String?,
    val createdAt: Long,
    val consumedAt: Long?,
    val pendingInvocationKey: String?,
    @ColumnInfo(defaultValue = "1") val schemaVersion: Int = 1
)
