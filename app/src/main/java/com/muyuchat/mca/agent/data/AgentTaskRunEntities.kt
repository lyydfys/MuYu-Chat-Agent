package com.muyuchat.mca.agent.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `agent_workspaces` — one private workspace per task in V1 (architecture 10.1 / 10.2).
 *
 * [storageRef] is the *logical* root name (`<workspaceId>`), never an absolute path. Enum columns
 * store stable names; all timestamps are epoch milliseconds.
 */
@Entity(
    tableName = "agent_workspaces",
    indices = [
        Index(value = ["createdAt"], name = "index_agent_workspaces_createdAt")
    ]
)
data class AgentWorkspaceEntity(
    @PrimaryKey val workspaceId: String,
    val displayName: String,
    val storageRef: String,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "1") val schemaVersion: Int = 1
)

/**
 * `agent_tasks` — task lifecycle and the transactional latest-run display summary.
 *
 * [lifecycle] is the independent ACTIVE/IMPORTED_ARCHIVED/TOMBSTONED column required by 10.1; it is
 * never folded into [displayStatus]. [modelBindingJson]/[policyJson]/[budgetJson] hold the frozen
 * creation context (no credentials) so a later `StartAgentRun` cannot silently read the chat page's
 * current model selection. They are nullable only because the frozen `CreateTaskTransition` contract
 * carries no binding; the preferred `AgentTaskRepository.createTask(CreateAgentTask)` path always
 * fills them.
 */
@Entity(
    tableName = "agent_tasks",
    foreignKeys = [
        ForeignKey(
            entity = AgentWorkspaceEntity::class,
            parentColumns = ["workspaceId"],
            childColumns = ["workspaceId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index(value = ["workspaceId"], unique = true, name = "index_agent_tasks_workspaceId"),
        Index(value = ["updatedAt"], name = "index_agent_tasks_updatedAt"),
        Index(value = ["lifecycle", "updatedAt"], name = "index_agent_tasks_lifecycle_updatedAt")
    ]
)
data class AgentTaskEntity(
    @PrimaryKey val taskId: String,
    val workspaceId: String,
    val goal: String,
    val displayTitle: String,
    val lifecycle: String,
    val lastRunId: String?,
    val displayStatus: String,
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val modelBindingJson: String?,
    val policyJson: String?,
    val budgetJson: String?,
    @ColumnInfo(defaultValue = "1") val schemaVersion: Int = 1
)

/**
 * `agent_runs` — the only authoritative execution state.
 *
 * `(taskId, runOrdinal)` is unique; `lastSequence` and `revision` move only under the journal's
 * transaction. `successorRunId` is the durable link written by `ContinueAgentTask`/supersede.
 */
@Entity(
    tableName = "agent_runs",
    foreignKeys = [
        ForeignKey(
            entity = AgentTaskEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index(
            value = ["taskId", "runOrdinal"],
            unique = true,
            name = "index_agent_runs_taskId_runOrdinal"
        ),
        Index(value = ["taskId", "updatedAt"], name = "index_agent_runs_taskId_updatedAt"),
        Index(value = ["state"], name = "index_agent_runs_state")
    ]
)
data class AgentRunEntity(
    @PrimaryKey val runId: String,
    val taskId: String,
    val runOrdinal: Long,
    val state: String,
    val reason: String,
    val epoch: Long,
    val modelBindingJson: String,
    val policyJson: String,
    val budgetJson: String,
    val spentBudgetJson: String,
    val lastSequence: Long,
    val revision: Long,
    val successorRunId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "1") val schemaVersion: Int = 1
)

/**
 * `agent_questions` — durable model questions.
 *
 * The architecture table list omits this table, but `AskQuestionTransition`,
 * `AnswerQuestionTransition` and `AgentRunSnapshot.activeQuestion` cannot be reconstructed from
 * events without violating "relational facts are authoritative". One row per question; an OPEN row
 * is answerable exactly once.
 */
@Entity(
    tableName = "agent_questions",
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
        Index(value = ["runId", "status"], name = "index_agent_questions_runId_status"),
        Index(value = ["runId", "stepIndex"], name = "index_agent_questions_runId_stepIndex")
    ]
)
data class AgentQuestionEntity(
    @PrimaryKey val questionId: String,
    val runId: String,
    val stepIndex: Int,
    val text: String,
    val optionsJson: String,
    val status: String,
    val askedAt: Long,
    val answeredAt: Long?,
    val answer: String?,
    @ColumnInfo(defaultValue = "1") val schemaVersion: Int = 1
)
