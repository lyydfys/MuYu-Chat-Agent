package com.muyuchat.mca.agent.memory

import kotlinx.coroutines.CancellationException

/**
 * Freezes and resolves a task's memory selection (plan §7.2).
 *
 * A task's selection is written exactly once, at task creation, from the ids the user chose. After
 * that the persisted row is the authority: a repeated freeze with the same ids is an idempotent
 * replay, a different set is rejected, and revocation is terminal. The executor resolves its grant
 * from this service, so widening the selection from the model side is impossible by construction.
 *
 * The service only declares *read scope*; it grants no write capability. Content writes go through
 * [AgentMemoryStore] and its explicit [AgentMemoryWriteApproval].
 */
class AgentMemorySelectionService(
    private val persistence: AgentMemoryPersistence,
    private val clock: () -> Long = System::currentTimeMillis
) : AgentMemoryReadGrantSource {

    suspend fun freezeForTask(
        taskId: String,
        requestedSpaceIds: Collection<String>
    ): AgentMemorySelectionOutcome {
        if (taskId.isBlank()) {
            return rejected(AgentMemoryErrorCodes.STORAGE_FAILED, "A memory selection needs a task id.")
        }
        val cleaned = requestedSpaceIds.map(String::trim).filter(String::isNotEmpty).distinct()
        if (cleaned.isEmpty()) {
            return rejected(AgentMemoryErrorCodes.SELECTION_EMPTY, "Select at least one memory space for the task.")
        }
        if (cleaned.size > AgentMemoryLimits.MAX_SELECTED_SPACES) {
            return rejected(
                AgentMemoryErrorCodes.SELECTION_TOO_LARGE,
                "A task can select at most ${AgentMemoryLimits.MAX_SELECTED_SPACES} memory spaces."
            )
        }
        if (cleaned.any { !AgentMemorySelection.isValidSpaceId(it) }) {
            return rejected(AgentMemoryErrorCodes.SPACE_NOT_FOUND, "The task names an invalid memory space id.")
        }
        val selection = AgentMemorySelection.of(cleaned)

        val stored = try {
            persistence.findSelection(taskId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return rejected(AgentMemoryErrorCodes.STORAGE_FAILED, "The memory selection could not be read.")
        }
        if (stored != null) {
            if (stored.revoked) {
                return rejected(
                    AgentMemoryErrorCodes.SELECTION_REVOKED,
                    "This task's memory selection was revoked; start a new task to select memory."
                )
            }
            val frozen = AgentMemorySelectionCodec.decode(stored)
                ?: return rejected(
                    AgentMemoryErrorCodes.STORAGE_FAILED,
                    "The stored memory selection is not readable; start a new task."
                )
            return if (frozen.spaceIds == selection.spaceIds) {
                AgentMemorySelectionOutcome.Frozen(frozen)
            } else {
                rejected(
                    AgentMemoryErrorCodes.SELECTION_ALREADY_FROZEN,
                    "This task's memory selection is already frozen and cannot be changed."
                )
            }
        }

        val live = try {
            persistence.existingSpaceIds(selection.spaceIds)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return rejected(AgentMemoryErrorCodes.STORAGE_FAILED, "The memory spaces could not be read.")
        }
        if (live.size != selection.spaceIds.size) {
            return rejected(
                AgentMemoryErrorCodes.SPACE_NOT_FOUND,
                "A selected memory space no longer exists; reload the space list."
            )
        }

        val now = clock()
        val row = AgentMemoryStoredSelection(
            taskId = taskId,
            spaceIdsJson = AgentMemorySelectionCodec.encode(selection),
            selectionHash = selection.hash(),
            createdAt = now,
            revoked = false,
            revokedAt = null
        )
        return try {
            persistence.insertSelection(row)
            AgentMemorySelectionOutcome.Frozen(selection)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A concurrent creation may have won the unique key; replay it only when identical.
            val raced = runCatching { persistence.findSelection(taskId) }.getOrNull()
            val frozen = raced?.let(AgentMemorySelectionCodec::decode)
            if (frozen != null && frozen.spaceIds == selection.spaceIds) {
                AgentMemorySelectionOutcome.Frozen(frozen)
            } else {
                rejected(AgentMemoryErrorCodes.STORAGE_FAILED, "The memory selection could not be stored.")
            }
        }
    }

    override suspend fun grantForTask(taskId: String): AgentMemoryReadGrant? {
        val stored = try {
            persistence.findSelection(taskId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        } ?: return null
        val frozen = AgentMemorySelectionCodec.decode(stored) ?: return null
        return AgentMemoryReadGrant(
            taskId = stored.taskId,
            spaceIds = frozen.spaceIds,
            revoked = stored.revoked
        )
    }

    /** Terminal revocation of one task's selection; the frozen row is kept for audit. */
    suspend fun revokeForTask(taskId: String): Boolean =
        try {
            persistence.revokeSelection(taskId, clock()) > 0
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    private fun rejected(code: String, message: String): AgentMemorySelectionOutcome =
        AgentMemorySelectionOutcome.Rejected(AgentMemoryError(code, message))
}
