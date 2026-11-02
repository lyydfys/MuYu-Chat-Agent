package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentCommitResult
import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentEventPage
import com.muyuchat.core.agent.contract.AgentJournal
import com.muyuchat.core.agent.contract.AgentRunSnapshot
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTransition

/**
 * Room implementation of the frozen [AgentJournal] contract.
 *
 * Guarantees:
 *  * every [commit] runs in exactly one Room transaction: revision CAS, relational rows, event
 *    sequence, task display summary, artifact/approval/invocation updates and the command-log row
 *    either all commit or all roll back;
 *  * idempotency is checked by commandId **before** any revision CAS; the same content replays the
 *    original result even after the revision advanced, different content raises
 *    `COMMAND_IDEMPOTENCY_CONFLICT`, and a new command with a stale revision raises
 *    `COMMAND_REVISION_CONFLICT`;
 *  * event sequences are validated as exactly contiguous after `run.lastSequence` and committed in
 *    the same transaction that advances it;
 *  * [listNonTerminalRuns] explicitly includes STOPPING/PAUSING/INTERRUPTED/PAUSED/NEEDS_REVIEW and
 *    excludes runs whose task lifecycle is IMPORTED_ARCHIVED or TOMBSTONED.
 *
 * The class is deliberately thin: transition semantics live in the internal, SQL-shaped applier and
 * the pure rules in [AgentJournalRules].
 */
class RoomAgentJournal(
    private val daos: AgentJournalDaos,
    private val transactions: AgentTransactionRunner,
    private val clock: AgentClock = AgentClock.system()
) : AgentJournal {
    private val applier = AgentTransitionApplier(
        taskDao = daos.task,
        workspaceDao = daos.workspace,
        runDao = daos.run,
        stepDao = daos.step,
        attemptDao = daos.attempt,
        invocationDao = daos.invocation,
        approvalDao = daos.approval,
        eventDao = daos.event,
        artifactDao = daos.artifact,
        commitDao = daos.artifactCommit,
        questionDao = daos.question,
        clock = clock
    )

    override suspend fun readRun(runId: String): AgentRunSnapshot? {
        AgentContract.requireId(runId, "runId")
        return transactions.inTransaction {
            val runEntity = daos.run.find(runId) ?: return@inTransaction null
            val taskEntity = daos.task.find(runEntity.taskId) ?: return@inTransaction null
            AgentRunSnapshot(
                run = AgentEntityMappers.toContract(runEntity),
                task = AgentEntityMappers.toContract(taskEntity),
                activeQuestion = daos.question.findOpenForRun(runId)?.let(AgentEntityMappers::toContract),
                pendingApproval = daos.approval.findPendingForRun(runId)?.let(AgentEntityMappers::toContract),
                steps = daos.step.listByRun(runId, AgentDataLimits.MAX_STEP_PAGE_LIMIT)
                    .map(AgentEntityMappers::toContract),
                artifacts = daos.artifact.listByRun(runId, AgentDataLimits.MAX_ARTIFACT_PAGE_LIMIT)
                    .map { AgentEntityMappers.toStoredArtifact(it).artifact }
            )
        }
    }

    override suspend fun listNonTerminalRuns(limit: Int): List<AgentRunSnapshot> {
        requireBounded(limit, AgentDataLimits.MAX_CHILD_PAGE_LIMIT, "listNonTerminalRuns")
        val states = AgentRunState.entries.filterNot { it.isTerminal }.map { it.name }
        return transactions.inTransaction {
            daos.run.listNonTerminalActive(states, limit).mapNotNull { runEntity ->
                val taskEntity = daos.task.find(runEntity.taskId) ?: return@mapNotNull null
                AgentRunSnapshot(
                    run = AgentEntityMappers.toContract(runEntity),
                    task = AgentEntityMappers.toContract(taskEntity)
                )
            }
        }
    }

    override suspend fun readEvents(runId: String, afterSequence: Long, limit: Int): AgentEventPage {
        AgentContract.requireId(runId, "runId")
        require(afterSequence >= 0L) { "afterSequence must not be negative" }
        requireBounded(limit, AgentDataLimits.MAX_EVENT_PAGE_LIMIT, "readEvents")
        val events = daos.event.page(runId, afterSequence, limit)
            .map(AgentEntityMappers::toContract)
        return AgentEventPage(
            events = events,
            nextSequence = events.lastOrNull()?.sequence
        )
    }

    override suspend fun commit(command: AgentTransition): AgentCommitResult {
        AgentContract.requireId(command.commandId, "commandId")
        val fingerprint = AgentCommandFingerprint.fingerprint(command)
        return translatingJournalErrors {
            transactions.inTransaction {
                val existing = daos.command.find(command.commandId)
                when (AgentJournalRules.commandLookup(existing?.contentHash, fingerprint)) {
                    AgentJournalRules.CommandLookup.CONFLICT -> throw AgentJournalException(
                        AgentDataErrorCodes.COMMAND_IDEMPOTENCY_CONFLICT,
                        "command ${command.commandId} was already applied with different content"
                    )
                    AgentJournalRules.CommandLookup.REPLAY -> {
                        val stored = requireNotNull(existing)
                        AgentRecordJson.decodeCommitResult(stored.resultRef).copy(idempotentReplay = true)
                    }
                    AgentJournalRules.CommandLookup.EXECUTE -> {
                        val result = applier.apply(command)
                        val encoded = AgentRecordJson.encodeCommitResult(result)
                        if (encoded.toByteArray(Charsets.UTF_8).size > AgentDataLimits.MAX_COMMAND_RESULT_BYTES) {
                            throw AgentJournalException(
                                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                                "command result exceeds the persisted bound"
                            )
                        }
                        daos.command.insert(
                            AgentCommandEntity(
                                commandId = command.commandId,
                                scopeId = AgentCommandFingerprint.scopeId(command),
                                commandType = AgentCommandFingerprint.commandType(command),
                                contentHash = fingerprint,
                                resultRef = encoded,
                                appliedRevision = result.appliedRevision,
                                createdAt = clock.nowMillis()
                            )
                        )
                        result
                    }
                }
            }
        }
    }

    private fun requireBounded(limit: Int, max: Int, operation: String) {
        require(limit in 1..max) { "$operation limit must be in 1..$max, was $limit" }
    }
}
