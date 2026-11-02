package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentCommitResult
import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.core.agent.runtime.run.AgentRunSpec
import com.muyuchat.mca.agent.memory.AgentMemoryError
import com.muyuchat.mca.agent.memory.AgentMemoryErrorCodes
import com.muyuchat.mca.agent.memory.AgentMemorySelection
import com.muyuchat.mca.agent.memory.AgentMemorySelectionOutcome
import com.muyuchat.mca.agent.memory.AgentMemorySelectionService
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Outcome of the idempotent task-creation command. */
sealed interface AgentTaskCreationOutcome {
    data class Created(val task: AgentTask, val idempotentReplay: Boolean) : AgentTaskCreationOutcome

    data class Rejected(val error: AgentError) : AgentTaskCreationOutcome
}

/**
 * Creates a task with its 1:1 private workspace, frozen creation context (model binding, policy,
 * budget) and the `ALLOW_FOR_TASK` workspace-read grant in ONE transaction, plus the command
 * idempotency row. When `CreateAgentTask.memorySpaceIds` / `AgentRunPolicy.memorySpaceIds` name
 * memory spaces, the selection is frozen through the shared `AgentMemorySelectionService` in that
 * same transaction, so an unknown/oversized/revoked selection rejects the command before a task is
 * reported created.
 *
 * This exists because the frozen `CreateTaskTransition` carries neither the model binding nor the
 * display title; `RoomAgentJournal.commit(CreateTaskTransition)` remains supported for runtime
 * compatibility, but production task creation should use this method so `StartAgentRun` never has to
 * re-read the chat page's current model selection.
 */
class AgentTaskRepository(
    private val daos: AgentJournalDaos,
    private val grantDao: AgentGrantDao,
    private val transactions: AgentTransactionRunner,
    private val clock: AgentClock = AgentClock.system(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    /**
     * The one memory selection owner. Task creation freezes the user's selection through this
     * service inside the same transaction that writes the task, so a task can never be reported
     * created without the selection the caller asked for (and a rejected selection writes nothing).
     */
    private val memorySelectionService: AgentMemorySelectionService
) {
    suspend fun createTask(command: CreateAgentTask): AgentTaskCreationOutcome {
        val goalBytes = command.goal.toByteArray(Charsets.UTF_8)
        if (goalBytes.size > MAX_GOAL_BYTES) {
            return AgentTaskCreationOutcome.Rejected(
                rejection(command.commandId, "goal exceeds $MAX_GOAL_BYTES bytes")
            )
        }
        if (command.displayTitle.length > MAX_DISPLAY_TITLE_CHARS) {
            return AgentTaskCreationOutcome.Rejected(
                rejection(command.commandId, "display title exceeds $MAX_DISPLAY_TITLE_CHARS characters")
            )
        }
        if (hasOnlyBlankMemoryIds(command.memorySpaceIds) ||
            hasOnlyBlankMemoryIds(command.policy.memorySpaceIds)
        ) {
            return AgentTaskCreationOutcome.Rejected(
                memoryRejection(
                    command.commandId,
                    AgentMemoryError(
                        AgentMemoryErrorCodes.SELECTION_EMPTY,
                        "Select at least one memory space for the task, or omit the selection."
                    )
                )
            )
        }
        val requestedMemorySpaceIds = effectiveMemorySpaceIds(command)
            ?: return AgentTaskCreationOutcome.Rejected(
                rejection(
                    command.commandId,
                    "CreateAgentTask.memorySpaceIds and AgentRunPolicy.memorySpaceIds disagree"
                )
            )
        val contentHash = AgentCommandFingerprint.createTaskFingerprint(command)
        return try {
            transactions.inTransaction {
                val existing = daos.command.find(command.commandId)
                when (AgentJournalRules.commandLookup(existing?.contentHash, contentHash)) {
                    AgentJournalRules.CommandLookup.CONFLICT -> throw AgentJournalException(
                        AgentDataErrorCodes.COMMAND_IDEMPOTENCY_CONFLICT,
                        "command ${command.commandId} was already applied with different content"
                    )
                    AgentJournalRules.CommandLookup.REPLAY -> {
                        val taskId = requireNotNull(existing).scopeId
                        val taskEntity = daos.task.find(taskId)
                            ?: throw AgentJournalException(
                                AgentDataErrorCodes.STORAGE_CORRUPTION,
                                "creation command ${command.commandId} has no task"
                            )
                        // A replay must be side-effect-free for the user but may repair a selection
                        // that was never written (for example an older/partial build). The service
                        // is idempotent for the identical set and rejects a different one.
                        val replaySelection = freezeMemorySelection(taskId, requestedMemorySpaceIds)
                        if (replaySelection is AgentMemorySelectionOutcome.Rejected) {
                            return@inTransaction AgentTaskCreationOutcome.Rejected(
                                memoryRejection(command.commandId, replaySelection.error)
                            )
                        }
                        AgentTaskCreationOutcome.Created(
                            task = AgentEntityMappers.toContract(taskEntity),
                            idempotentReplay = true
                        )
                    }
                    AgentJournalRules.CommandLookup.EXECUTE -> {
                        val now = clock.nowMillis()
                        val taskId = idGenerator()
                        val workspaceId = idGenerator()
                        AgentContract.requireId(taskId, "taskId")
                        AgentContract.requireId(workspaceId, "workspaceId")
                        // Freeze the memory scope before the task row is visible: a rejected or
                        // unknown selection aborts the transaction and nothing is reported created.
                        val memoryOutcome = freezeMemorySelection(taskId, requestedMemorySpaceIds)
                        if (memoryOutcome is AgentMemorySelectionOutcome.Rejected) {
                            return@inTransaction AgentTaskCreationOutcome.Rejected(
                                memoryRejection(command.commandId, memoryOutcome.error)
                            )
                        }
                        val frozenSelection: AgentMemorySelection? =
                            (memoryOutcome as? AgentMemorySelectionOutcome.Frozen)?.selection
                        val policy = command.policy.copy(
                            memorySpaceIds = frozenSelection?.spaceIds?.toSet().orEmpty()
                        )
                        val task = AgentTask(
                            taskId = taskId,
                            workspaceId = workspaceId,
                            goal = command.goal,
                            lifecycle = AgentTaskLifecycle.ACTIVE,
                            createdAtMillis = now,
                            updatedAtMillis = now,
                            displayStatus = AgentDisplayStatus.IDLE,
                            lastRunId = null,
                            revision = 0L
                        )
                        daos.workspace.insert(
                            AgentWorkspaceEntity(
                                workspaceId = workspaceId,
                                displayName = command.displayTitle,
                                storageRef = workspaceId,
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                        daos.task.insert(
                            AgentEntityMappers.toEntity(
                                task = task,
                                displayTitle = command.displayTitle,
                                modelBindingJson = AgentRecordJson.encodeBinding(command.modelBinding),
                                policyJson = AgentRecordJson.encodePolicy(policy),
                                budgetJson = AgentRecordJson.encodeBudget(command.budget)
                            )
                        )
                        grantDao.upsert(
                            AgentGrantEntity(
                                grantId = idGenerator(),
                                taskId = taskId,
                                runId = null,
                                scopeType = AgentGrantScope.WORKSPACE_READ.name,
                                scopeJson = workspaceGrantJson(workspaceId, taskId),
                                policyVersion = command.policy.policyVersion,
                                modelFingerprint = null,
                                grantedAt = now,
                                expiresAt = null,
                                revokedAt = null,
                                revokedReason = null
                            )
                        )
                        daos.command.insert(
                            AgentCommandEntity(
                                commandId = command.commandId,
                                scopeId = taskId,
                                commandType = "CREATE_TASK_COMMAND",
                                contentHash = contentHash,
                                resultRef = AgentRecordJson.encodeCommitResult(
                                    AgentCommitResult(appliedRevision = 0L, taskRevision = 0L)
                                ),
                                appliedRevision = 0L,
                                createdAt = now
                            )
                        )
                        AgentTaskCreationOutcome.Created(task = task, idempotentReplay = false)
                    }
                }
            }
        } catch (error: AgentJournalException) {
            AgentTaskCreationOutcome.Rejected(
                rejection(command.commandId, error.message ?: error.code, error.code)
            )
        }
    }

    /**
     * Frozen creation context for K03's `AgentRunSpecProvider`.
     *
     * Reads the task's persisted binding/policy/budget and falls back to the latest run's frozen
     * context for tasks created through the binding-less `CreateTaskTransition`. Returns null when no
     * frozen context exists; callers must not substitute the chat page's current model selection.
     */
    suspend fun readRunSpec(taskId: String): AgentRunSpec? {
        AgentContract.requireId(taskId, "taskId")
        return transactions.inTransaction {
            val task = daos.task.find(taskId) ?: return@inTransaction null
            AgentRunSpecCodec.decode(task, daos.run.findLatestForTask(taskId))
        }
    }

    private fun workspaceGrantJson(workspaceId: String, taskId: String): String = JSONObject()
        .put("workspaceId", workspaceId)
        .put("taskId", taskId)
        .put("pathPrefixes", JSONArray(listOf(AgentArtifactLayout.INPUTS, AgentArtifactLayout.ARTIFACTS)))
        .toString()

    /**
     * The selection the caller asked for. `CreateAgentTask.memorySpaceIds` is the primary input;
     * `AgentRunPolicy.memorySpaceIds` is accepted as the frozen mirror for callers that only build a
     * policy. Two non-empty values must agree, otherwise the command is rejected instead of
     * silently preferring one and persisting a selection nobody asked for.
     */
    private fun effectiveMemorySpaceIds(command: CreateAgentTask): Set<String>? {
        val fromCommand = canonicalMemorySpaceIds(command.memorySpaceIds)
        val fromPolicy = canonicalMemorySpaceIds(command.policy.memorySpaceIds)
        if (fromCommand.isNotEmpty() && fromPolicy.isNotEmpty() && fromCommand != fromPolicy) return null
        return fromCommand.ifEmpty { fromPolicy }
    }

    private fun canonicalMemorySpaceIds(spaceIds: Set<String>): Set<String> =
        spaceIds.map(String::trim).filter(String::isNotEmpty).toSortedSet()

    /** A non-empty set made only of blanks is malformed input, not "no memory was requested". */
    private fun hasOnlyBlankMemoryIds(spaceIds: Set<String>): Boolean =
        spaceIds.isNotEmpty() && canonicalMemorySpaceIds(spaceIds).isEmpty()

    /** Null means the task requested no memory; otherwise the service freezes or rejects. */
    private suspend fun freezeMemorySelection(
        taskId: String,
        requestedSpaceIds: Set<String>
    ): AgentMemorySelectionOutcome? =
        if (requestedSpaceIds.isEmpty()) null else memorySelectionService.freezeForTask(taskId, requestedSpaceIds)

    private fun memoryRejection(commandId: String, error: AgentMemoryError): AgentError {
        val (category, retryability) = when (error.code) {
            AgentMemoryErrorCodes.STORAGE_FAILED ->
                AgentErrorCategory.STORAGE to AgentRetryability.SAFE_RETRY
            AgentMemoryErrorCodes.SELECTION_REVOKED ->
                AgentErrorCategory.PERMISSION to AgentRetryability.AFTER_USER_ACTION
            else ->
                AgentErrorCategory.POLICY to AgentRetryability.AFTER_USER_ACTION
        }
        return AgentError(
            code = error.code,
            category = category,
            retryability = retryability,
            safeMessage = error.safeMessage,
            correlationId = commandId
        )
    }

    private fun rejection(
        commandId: String,
        message: String,
        code: String = AgentDataErrorCodes.INPUT_INVALID
    ): AgentError = AgentError(
        code = code,
        category = AgentErrorCategory.INTERNAL,
        retryability = AgentRetryability.NEVER,
        safeMessage = message,
        correlationId = commandId
    )

    companion object {
        const val MAX_GOAL_BYTES: Int = 8 * 1024
        const val MAX_DISPLAY_TITLE_CHARS: Int = 120
    }
}
