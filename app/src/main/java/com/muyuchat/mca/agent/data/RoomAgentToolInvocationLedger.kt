package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.contract.ToolInvocation
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.journal.AgentFrozenInvocation
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationLedger
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationRecord
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationUpdate
import com.muyuchat.core.agent.runtime.journal.AgentToolProposal
import com.muyuchat.core.agent.runtime.journal.canStartExecution
import com.muyuchat.core.agent.runtime.journal.isPreExecution

/**
 * Room-backed tool-invocation ledger.
 *
 * Every mutation is one Room transaction. `recordProposed` writes the TOOL step and the `PROPOSED`
 * invocation together, idempotent on `(runId, stepId, callOrdinal)`. `recordFrozen` stores the
 * content-addressed frozen material first (outside the transaction, so no file IO runs while the DB
 * transaction is held) and then CASes `PROPOSED -> VALIDATED`. `markRunning` is the single
 * execution gate; `markTerminal` never overwrites a different terminal value.
 */
class RoomAgentToolInvocationLedger(
    private val taskDao: AgentTaskDao,
    private val runDao: AgentRunDao,
    private val stepDao: AgentStepDao,
    private val invocationDao: AgentInvocationDao,
    private val payloadStore: AgentPayloadStore,
    private val transactions: AgentTransactionRunner,
    private val clock: AgentClock = AgentClock.system()
) : AgentToolInvocationLedger {

    override suspend fun recordProposed(proposal: AgentToolProposal): AgentToolInvocationRecord =
        transactions.inTransaction {
            val run = runDao.find(proposal.runId)
                ?: return@inTransaction AgentToolInvocationRecord.Rejected(
                    AgentErrorCodes.COMMAND_NOT_FOUND,
                    "Run '${proposal.runId}' not found."
                )
            if (run.taskId != proposal.taskId) {
                return@inTransaction AgentToolInvocationRecord.Rejected(
                    AgentErrorCodes.COMMAND_INVALID_STATE,
                    "The run does not belong to the requested task."
                )
            }
            if (AgentEnumCodec.decodeRunState(run.state).isTerminal) {
                return@inTransaction AgentToolInvocationRecord.Rejected(
                    AgentErrorCodes.COMMAND_INVALID_STATE,
                    "The run is terminal."
                )
            }
            invocationDao.findByStepAndOrdinal(proposal.stepId, proposal.callOrdinal)?.let { existing ->
                return@inTransaction replayOrReject(existing, proposal)
            }
            invocationDao.findByIdempotencyKey(proposal.idempotencyKey)?.let { existing ->
                return@inTransaction replayOrReject(existing, proposal)
            }
            invocationDao.find(proposal.invocationId)?.let { existing ->
                return@inTransaction if (existing.stepId == proposal.stepId &&
                    existing.callOrdinal == proposal.callOrdinal
                ) {
                    AgentToolInvocationRecord.Recorded(AgentEntityMappers.toContract(existing))
                } else {
                    AgentToolInvocationRecord.Rejected(
                        AgentErrorCodes.COMMAND_INVALID_STATE,
                        "The invocation id is already used by another proposal."
                    )
                }
            }
            val sameIndex = stepDao.findByRunIndex(run.runId, proposal.stepIndex)
            if (sameIndex != null && sameIndex.stepId != proposal.stepId) {
                return@inTransaction AgentToolInvocationRecord.Rejected(
                    AgentErrorCodes.COMMAND_INVALID_STATE,
                    "The step index is already used by another step."
                )
            }
            if (stepDao.find(proposal.stepId) != null) {
                return@inTransaction AgentToolInvocationRecord.Rejected(
                    AgentErrorCodes.COMMAND_INVALID_STATE,
                    "The step already exists without a matching invocation."
                )
            }
            val now = clock.nowMillis()
            stepDao.upsert(
                AgentStepEntity(
                    stepId = proposal.stepId,
                    runId = run.runId,
                    stepIndex = proposal.stepIndex,
                    kind = com.muyuchat.core.agent.contract.AgentStepKind.TOOL.name,
                    status = com.muyuchat.core.agent.contract.AgentStepStatus.PENDING.name,
                    modelAttemptId = null,
                    invocationId = proposal.invocationId,
                    startedAt = now,
                    endedAt = null
                )
            )
            val invocation = ToolInvocation(
                invocationId = proposal.invocationId,
                runId = proposal.runId,
                stepId = proposal.stepId,
                callOrdinal = proposal.callOrdinal,
                toolKey = proposal.toolKey,
                toolVersion = proposal.toolVersion,
                schemaHash = proposal.schemaHash,
                frozenArgumentsRef = null,
                argumentsHash = proposal.argumentsHash,
                resourceFingerprint = com.muyuchat.core.agent.runtime.journal.PENDING_RESOURCE_FINGERPRINT,
                status = ToolInvocationStatus.PROPOSED,
                idempotencyKey = proposal.idempotencyKey,
                resultRef = null,
                createdAtMillis = now,
                updatedAtMillis = now
            )
            invocationDao.upsert(AgentEntityMappers.toEntity(invocation))
            AgentToolInvocationRecord.Recorded(invocation)
        }

    override suspend fun recordFrozen(frozen: AgentFrozenInvocation): AgentToolInvocationRecord {
        val command = frozen.command
        val existing = invocationDao.find(command.invocationId)
            ?: return AgentToolInvocationRecord.Rejected(
                AgentErrorCodes.COMMAND_NOT_FOUND,
                "Invocation '${command.invocationId}' was not proposed."
            )
        val run = runDao.find(existing.runId)
            ?: return AgentToolInvocationRecord.Rejected(
                AgentErrorCodes.COMMAND_NOT_FOUND,
                "The invocation run was not found."
            )
        val task = taskDao.find(run.taskId)
            ?: return AgentToolInvocationRecord.Rejected(
                AgentErrorCodes.COMMAND_NOT_FOUND,
                "The invocation task was not found."
            )
        if (!matchesProposed(existing, command)) {
            return AgentToolInvocationRecord.Rejected(
                AgentErrorCodes.INTERNAL_INVARIANT,
                "The frozen command does not match the proposed invocation."
            )
        }
        val policyVersion = AgentRecordJson.decodePolicy(run.policyJson).policyVersion
        if (run.epoch != command.runEpoch || policyVersion != command.policyVersion) {
            return AgentToolInvocationRecord.Rejected(
                AgentErrorCodes.INTERNAL_INVARIANT,
                "The frozen run epoch or policy version does not match the run."
            )
        }
        val encoded = AgentFrozenInvocationCodec.encode(frozen)
        if (encoded !is AgentFrozenInvocationCodec.Encoded.Success) {
            val rejected = encoded as AgentFrozenInvocationCodec.Encoded.Rejected
            return AgentToolInvocationRecord.Rejected(rejected.errorCode, rejected.safeMessage)
        }
        val frozenRef = try {
            payloadStore.write(
                workspaceId = task.workspaceId,
                taskId = task.taskId,
                kind = FROZEN_COMMAND_PAYLOAD_KIND,
                bytes = encoded.bytes
            ).ref
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return AgentToolInvocationRecord.Rejected(
                (failure as? AgentStorageException)?.code ?: AgentErrorCodes.STORAGE_NO_SPACE,
                "The frozen tool command could not be stored."
            )
        }
        return transactions.inTransaction {
            val current = invocationDao.find(command.invocationId)
                ?: return@inTransaction AgentToolInvocationRecord.Rejected(
                    AgentErrorCodes.COMMAND_NOT_FOUND,
                    "The invocation disappeared while freezing."
                )
            if (!matchesProposed(current, command)) {
                return@inTransaction AgentToolInvocationRecord.Rejected(
                    AgentErrorCodes.INTERNAL_INVARIANT,
                    "The frozen command does not match the proposed invocation."
                )
            }
            if (current.status == ToolInvocationStatus.VALIDATED.name) {
                return@inTransaction if (current.argumentsHash == command.argumentsHash &&
                    current.resourceFingerprint == command.resourceFingerprint &&
                    current.frozenArgumentsRef == frozenRef
                ) {
                    AgentToolInvocationRecord.Recorded(AgentEntityMappers.toContract(current))
                } else {
                    AgentToolInvocationRecord.Rejected(
                        AgentErrorCodes.INTERNAL_INVARIANT,
                        "The invocation was already frozen with different material."
                    )
                }
            }
            if (current.status != ToolInvocationStatus.PROPOSED.name) {
                return@inTransaction AgentToolInvocationRecord.Rejected(
                    AgentErrorCodes.COMMAND_INVALID_STATE,
                    "The invocation cannot be frozen from status ${current.status}."
                )
            }
            val rows = invocationDao.markValidatedCas(
                invocationId = command.invocationId,
                argumentsHash = command.argumentsHash,
                resourceFingerprint = command.resourceFingerprint,
                frozenArgumentsRef = frozenRef,
                updatedAt = clock.nowMillis()
            )
            if (rows != 1) {
                return@inTransaction AgentToolInvocationRecord.Rejected(
                    AgentErrorCodes.COMMAND_INVALID_STATE,
                    "The frozen command CAS lost."
                )
            }
            AgentToolInvocationRecord.Recorded(
                AgentEntityMappers.toContract(
                    requireNotNull(invocationDao.find(command.invocationId))
                )
            )
        }
    }

    override suspend fun markWaitingApproval(
        invocationId: String,
        atMillis: Long
    ): AgentToolInvocationUpdate = transactions.inTransaction {
        val current = invocationDao.find(invocationId)
            ?: return@inTransaction missing(invocationId)
        val status = AgentEnumCodec.decodeInvocationStatus(current.status)
        if (status == ToolInvocationStatus.WAITING_APPROVAL) {
            return@inTransaction AgentToolInvocationUpdate.Updated
        }
        if (status != ToolInvocationStatus.VALIDATED) {
            return@inTransaction AgentToolInvocationUpdate.Rejected(
                AgentErrorCodes.COMMAND_INVALID_STATE,
                "Invocation '$invocationId' cannot wait for approval from status $status."
            )
        }
        val rows = invocationDao.markWaitingApprovalCas(invocationId, atMillis)
        if (rows != 1) {
            return@inTransaction AgentToolInvocationUpdate.Rejected(
                AgentErrorCodes.COMMAND_INVALID_STATE,
                "The waiting-approval CAS lost."
            )
        }
        updateStepStatus(current, com.muyuchat.core.agent.contract.AgentStepStatus.WAITING, atMillis)
        AgentToolInvocationUpdate.Updated
    }

    override suspend fun markApproved(
        invocationId: String,
        atMillis: Long
    ): AgentToolInvocationUpdate = transactions.inTransaction {
        val current = invocationDao.find(invocationId)
            ?: return@inTransaction missing(invocationId)
        val status = AgentEnumCodec.decodeInvocationStatus(current.status)
        if (status == ToolInvocationStatus.APPROVED) {
            return@inTransaction AgentToolInvocationUpdate.Updated
        }
        if (status != ToolInvocationStatus.VALIDATED &&
            status != ToolInvocationStatus.WAITING_APPROVAL
        ) {
            return@inTransaction AgentToolInvocationUpdate.Rejected(
                AgentErrorCodes.COMMAND_INVALID_STATE,
                "Invocation '$invocationId' cannot be approved from status $status."
            )
        }
        val rows = invocationDao.markApprovedCas(invocationId, atMillis)
        if (rows != 1) {
            return@inTransaction AgentToolInvocationUpdate.Rejected(
                AgentErrorCodes.COMMAND_INVALID_STATE,
                "The approval-intent CAS lost."
            )
        }
        // Authorized but not started: the step stays WAITING until markRunning succeeds.
        updateStepStatus(current, com.muyuchat.core.agent.contract.AgentStepStatus.WAITING, atMillis)
        AgentToolInvocationUpdate.Updated
    }

    override suspend fun markRunning(
        invocationId: String,
        atMillis: Long
    ): AgentToolInvocationUpdate = transactions.inTransaction {
        val current = invocationDao.find(invocationId)
            ?: return@inTransaction missing(invocationId)
        val status = AgentEnumCodec.decodeInvocationStatus(current.status)
        if (!status.canStartExecution()) {
            return@inTransaction AgentToolInvocationUpdate.Rejected(
                AgentErrorCodes.COMMAND_INVALID_STATE,
                "Invocation '$invocationId' is not executable from status $status."
            )
        }
        val rows = invocationDao.markRunningCas(invocationId, current.stepId, atMillis)
        if (rows != 1) {
            return@inTransaction AgentToolInvocationUpdate.Rejected(
                AgentErrorCodes.COMMAND_INVALID_STATE,
                "The execution gate CAS lost."
            )
        }
        updateStepStatus(
            current,
            com.muyuchat.core.agent.contract.AgentStepStatus.RUNNING,
            atMillis
        )
        AgentToolInvocationUpdate.Updated
    }

    override suspend fun markTerminal(
        invocationId: String,
        status: ToolInvocationStatus,
        possibleSideEffects: Boolean,
        resultRef: String?,
        atMillis: Long
    ): AgentToolInvocationUpdate = transactions.inTransaction {
        require(status.isTerminal) { "markTerminal requires a terminal status" }
        val current = invocationDao.find(invocationId)
            ?: return@inTransaction missing(invocationId)
        val currentStatus = AgentEnumCodec.decodeInvocationStatus(current.status)
        if (currentStatus.isTerminal) {
            return@inTransaction if (currentStatus == status) {
                AgentToolInvocationUpdate.Updated
            } else {
                AgentToolInvocationUpdate.Rejected(
                    AgentErrorCodes.COMMAND_INVALID_STATE,
                    "Invocation '$invocationId' is already ${currentStatus.name}."
                )
            }
        }
        if (!currentStatus.isPreExecution() && currentStatus != ToolInvocationStatus.RUNNING) {
            return@inTransaction AgentToolInvocationUpdate.Rejected(
                AgentErrorCodes.COMMAND_INVALID_STATE,
                "Invocation '$invocationId' cannot settle from status ${currentStatus.name}."
            )
        }
        val rows = invocationDao.markTerminalCas(
            invocationId = invocationId,
            status = status.name,
            resultRef = resultRef,
            possibleSideEffects = if (possibleSideEffects) 1 else 0,
            updatedAt = atMillis
        )
        if (rows != 1) {
            return@inTransaction AgentToolInvocationUpdate.Rejected(
                AgentErrorCodes.COMMAND_INVALID_STATE,
                "The terminal CAS lost."
            )
        }
        updateStepStatus(current, stepStatusFor(status), atMillis)
        AgentToolInvocationUpdate.Updated
    }

    override suspend fun readInvocation(invocationId: String): ToolInvocation? =
        invocationDao.find(invocationId)?.let(AgentEntityMappers::toContract)

    override suspend fun readFrozenInvocation(invocationId: String): AgentFrozenInvocation? {
        val entity = invocationDao.find(invocationId) ?: return null
        val ref = entity.frozenArgumentsRef ?: return null
        val run = runDao.find(entity.runId) ?: return null
        val task = taskDao.find(run.taskId) ?: return null
        val bytes = try {
            payloadStore.readNullable(task.workspaceId, task.taskId, ref)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return null
        } ?: return null
        val frozen = AgentFrozenInvocationCodec.decode(bytes) ?: return null
        if (!matchesProposed(entity, frozen.command)) return null
        if (entity.argumentsHash != frozen.command.argumentsHash) return null
        if (entity.resourceFingerprint != frozen.command.resourceFingerprint) return null
        return frozen
    }

    private fun replayOrReject(
        existing: AgentInvocationEntity,
        proposal: AgentToolProposal
    ): AgentToolInvocationRecord {
        if (existing.toolKey != proposal.toolKey ||
            existing.runId != proposal.runId ||
            (existing.stepId != proposal.stepId && existing.idempotencyKey != proposal.idempotencyKey)
        ) {
            return AgentToolInvocationRecord.Rejected(
                AgentErrorCodes.COMMAND_INVALID_STATE,
                "The proposal identity was already recorded for a different tool call."
            )
        }
        return AgentToolInvocationRecord.Recorded(AgentEntityMappers.toContract(existing))
    }

    private fun matchesProposed(entity: AgentInvocationEntity, command: FrozenToolCommand): Boolean =
        entity.invocationId == command.invocationId &&
            entity.runId == command.runId &&
            entity.stepId == command.stepId &&
            entity.callOrdinal == command.callOrdinal &&
            entity.toolKey == command.toolKey &&
            entity.toolVersion == command.toolVersion &&
            entity.schemaHash == command.schemaHash

    private suspend fun updateStepStatus(
        invocation: AgentInvocationEntity,
        status: com.muyuchat.core.agent.contract.AgentStepStatus,
        atMillis: Long
    ) {
        val step = stepDao.find(invocation.stepId) ?: return
        stepDao.upsert(
            step.copy(
                status = status.name,
                endedAt = if (status == com.muyuchat.core.agent.contract.AgentStepStatus.SUCCEEDED ||
                    status == com.muyuchat.core.agent.contract.AgentStepStatus.FAILED ||
                    status == com.muyuchat.core.agent.contract.AgentStepStatus.CANCELLED ||
                    status == com.muyuchat.core.agent.contract.AgentStepStatus.NOT_EXECUTED ||
                    status == com.muyuchat.core.agent.contract.AgentStepStatus.UNKNOWN
                ) {
                    atMillis
                } else {
                    step.endedAt
                }
            )
        )
    }

    private fun stepStatusFor(
        status: ToolInvocationStatus
    ): com.muyuchat.core.agent.contract.AgentStepStatus = when (status) {
        ToolInvocationStatus.SUCCEEDED -> com.muyuchat.core.agent.contract.AgentStepStatus.SUCCEEDED
        ToolInvocationStatus.FAILED -> com.muyuchat.core.agent.contract.AgentStepStatus.FAILED
        ToolInvocationStatus.CANCELLED -> com.muyuchat.core.agent.contract.AgentStepStatus.CANCELLED
        ToolInvocationStatus.DENIED,
        ToolInvocationStatus.EXPIRED,
        ToolInvocationStatus.NOT_EXECUTED ->
            com.muyuchat.core.agent.contract.AgentStepStatus.NOT_EXECUTED
        ToolInvocationStatus.UNKNOWN -> com.muyuchat.core.agent.contract.AgentStepStatus.UNKNOWN
        else -> com.muyuchat.core.agent.contract.AgentStepStatus.UNKNOWN
    }

    private fun missing(invocationId: String): AgentToolInvocationUpdate =
        AgentToolInvocationUpdate.Rejected(
            AgentErrorCodes.COMMAND_NOT_FOUND,
            "Invocation '$invocationId' not found."
        )

    private companion object {
        /** Payload kind for the frozen command/descriptor/scope envelope. */
        const val FROZEN_COMMAND_PAYLOAD_KIND: String = "frozen_command"
    }
}
