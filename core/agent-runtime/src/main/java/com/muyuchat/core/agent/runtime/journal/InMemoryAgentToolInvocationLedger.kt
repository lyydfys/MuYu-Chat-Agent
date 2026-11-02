package com.muyuchat.core.agent.runtime.journal

import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentStep
import com.muyuchat.core.agent.contract.AgentStepKind
import com.muyuchat.core.agent.contract.AgentStepStatus
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.contract.ToolInvocation
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import kotlinx.coroutines.sync.withLock

/**
 * In-memory tool-invocation ledger sharing the owning [InMemoryAgentJournal]'s state and mutex.
 *
 * It is the deterministic JVM implementation of the same contract the Room adapter implements:
 * the PROPOSED row and its step are written together, freeze CASes to VALIDATED, the execution gate
 * is one RUNNING transition, and a terminal value can never be overwritten.
 */
internal class InMemoryAgentToolInvocationLedger(
    private val journal: InMemoryAgentJournal
) : AgentToolInvocationLedger {

    private val mutex get() = journal.mutex
    private val runs get() = journal.runs
    private val steps get() = journal.steps
    private val invocations get() = journal.invocations
    private val frozenInvocations get() = journal.frozenInvocations


    override suspend fun recordProposed(proposal: AgentToolProposal): AgentToolInvocationRecord =
        mutex.withLock {
            val run = runs[proposal.runId]
                ?: return@withLock AgentToolInvocationRecord.Rejected(
                    AgentErrorCodes.COMMAND_NOT_FOUND,
                    "Run '${proposal.runId}' not found."
                )
            if (run.taskId != proposal.taskId) {
                return@withLock AgentToolInvocationRecord.Rejected(
                    AgentErrorCodes.COMMAND_INVALID_STATE,
                    "Run '${proposal.runId}' does not belong to task '${proposal.taskId}'."
                )
            }
            if (run.state.isTerminal) {
                return@withLock AgentToolInvocationRecord.Rejected(
                    AgentErrorCodes.COMMAND_INVALID_STATE,
                    "Run '${proposal.runId}' is terminal."
                )
            }
            findByProposalIdentity(proposal)?.let { existing ->
                return@withLock AgentToolInvocationRecord.Recorded(existing)
            }
            val stepAtSameIndex = steps[run.runId]?.values?.firstOrNull { it.index == proposal.stepIndex }
            if (stepAtSameIndex != null && stepAtSameIndex.stepId != proposal.stepId) {
                return@withLock AgentToolInvocationRecord.Rejected(
                    AgentErrorCodes.COMMAND_INVALID_STATE,
                    "Step index ${proposal.stepIndex} already belongs to another step."
                )
            }
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
                resourceFingerprint = PENDING_RESOURCE_FINGERPRINT,
                status = ToolInvocationStatus.PROPOSED,
                idempotencyKey = proposal.idempotencyKey,
                resultRef = null,
                createdAtMillis = proposal.proposedAtMillis,
                updatedAtMillis = proposal.proposedAtMillis
            )
            steps.getOrPut(run.runId) { LinkedHashMap() }[proposal.stepId] = AgentStep(
                stepId = proposal.stepId,
                runId = proposal.runId,
                index = proposal.stepIndex,
                kind = AgentStepKind.TOOL,
                status = AgentStepStatus.PENDING,
                modelAttemptId = null,
                invocationId = proposal.invocationId,
                startedAtMillis = proposal.proposedAtMillis,
                endedAtMillis = null
            )
            invocations.getOrPut(run.runId) { LinkedHashMap() }[invocation.invocationId] = invocation
            AgentToolInvocationRecord.Recorded(invocation)
        }

    override suspend fun recordFrozen(frozen: AgentFrozenInvocation): AgentToolInvocationRecord =
        mutex.withLock {
            val command = frozen.command
            val run = runs[command.runId]
                ?: return@withLock AgentToolInvocationRecord.Rejected(
                    AgentErrorCodes.COMMAND_NOT_FOUND,
                    "Run '${command.runId}' not found."
                )
            val existing = invocations[command.runId]?.get(command.invocationId)
                ?: return@withLock AgentToolInvocationRecord.Rejected(
                    AgentErrorCodes.COMMAND_NOT_FOUND,
                    "Invocation '${command.invocationId}' was not proposed."
                )
            if (!existing.matchesFrozenIdentity(command)) {
                return@withLock AgentToolInvocationRecord.Rejected(
                    AgentErrorCodes.INTERNAL_INVARIANT,
                    "The frozen command does not match the proposed invocation."
                )
            }
            if (existing.status == ToolInvocationStatus.VALIDATED) {
                val recorded = frozenInvocations[command.invocationId]
                return@withLock if (recorded == frozen) {
                    AgentToolInvocationRecord.Recorded(existing)
                } else {
                    AgentToolInvocationRecord.Rejected(
                        AgentErrorCodes.INTERNAL_INVARIANT,
                        "The invocation was already frozen with different material."
                    )
                }
            }
            if (existing.status != ToolInvocationStatus.PROPOSED) {
                return@withLock AgentToolInvocationRecord.Rejected(
                    AgentErrorCodes.COMMAND_INVALID_STATE,
                    "Invocation '${command.invocationId}' cannot be frozen from status ${existing.status}."
                )
            }
            if (command.runEpoch != run.epoch) {
                return@withLock AgentToolInvocationRecord.Rejected(
                    AgentErrorCodes.INTERNAL_INVARIANT,
                    "The frozen run epoch does not match the run."
                )
            }
            if (command.policyVersion != run.policy.policyVersion) {
                return@withLock AgentToolInvocationRecord.Rejected(
                    AgentErrorCodes.INTERNAL_INVARIANT,
                    "The frozen policy version does not match the run."
                )
            }
            val validated = existing.copy(
                argumentsHash = command.argumentsHash,
                resourceFingerprint = command.resourceFingerprint,
                frozenArgumentsRef = "memory:frozen:${command.invocationId}",
                status = ToolInvocationStatus.VALIDATED,
                updatedAtMillis = frozen.recordedAtMillis
            )
            invocations.getValue(run.runId)[command.invocationId] = validated
            frozenInvocations[command.invocationId] = frozen
            AgentToolInvocationRecord.Recorded(validated)
        }

    override suspend fun markWaitingApproval(
        invocationId: String,
        atMillis: Long
    ): AgentToolInvocationUpdate = mutex.withLock {
        transitionInvocation(invocationId, atMillis, setOf(ToolInvocationStatus.VALIDATED)) {
            it.copy(status = ToolInvocationStatus.WAITING_APPROVAL)
        } ?: AgentToolInvocationUpdate.Rejected(
            AgentErrorCodes.COMMAND_INVALID_STATE,
            "Invocation '$invocationId' cannot wait for approval from its current status."
        )
    }

    override suspend fun markApproved(
        invocationId: String,
        atMillis: Long
    ): AgentToolInvocationUpdate = mutex.withLock {
        transitionInvocation(
            invocationId,
            atMillis,
            setOf(ToolInvocationStatus.VALIDATED, ToolInvocationStatus.WAITING_APPROVAL)
        ) { it.copy(status = ToolInvocationStatus.APPROVED) }
            ?: findInvocationLocked(invocationId)?.let { current ->
                // Idempotent for an already-approved row; any other status is a real refusal.
                if (current.status == ToolInvocationStatus.APPROVED) {
                    AgentToolInvocationUpdate.Updated
                } else {
                    AgentToolInvocationUpdate.Rejected(
                        AgentErrorCodes.COMMAND_INVALID_STATE,
                        "Invocation '$invocationId' cannot be approved from ${current.status}."
                    )
                }
            }
            ?: AgentToolInvocationUpdate.Rejected(
                AgentErrorCodes.COMMAND_NOT_FOUND,
                "Invocation '$invocationId' not found."
            )
    }

    override suspend fun markRunning(
        invocationId: String,
        atMillis: Long
    ): AgentToolInvocationUpdate = mutex.withLock {
        transitionInvocation(invocationId, atMillis, allowed = null) { invocation ->
            if (!invocation.status.canStartExecution()) return@transitionInvocation null
            invocation.copy(status = ToolInvocationStatus.RUNNING)
        } ?: AgentToolInvocationUpdate.Rejected(
            AgentErrorCodes.COMMAND_INVALID_STATE,
            "Invocation '$invocationId' is not executable from its current status."
        )
    }

    override suspend fun markTerminal(
        invocationId: String,
        status: ToolInvocationStatus,
        possibleSideEffects: Boolean,
        resultRef: String?,
        atMillis: Long
    ): AgentToolInvocationUpdate = mutex.withLock {
        require(status.isTerminal) { "markTerminal requires a terminal status" }
        val invocation = findInvocationLocked(invocationId)
            ?: return@withLock AgentToolInvocationUpdate.Rejected(
                AgentErrorCodes.COMMAND_NOT_FOUND,
                "Invocation '$invocationId' not found."
            )
        if (invocation.status.isTerminal) {
            return@withLock if (invocation.status == status) {
                AgentToolInvocationUpdate.Updated
            } else {
                AgentToolInvocationUpdate.Rejected(
                    AgentErrorCodes.COMMAND_INVALID_STATE,
                    "Invocation '$invocationId' is already ${invocation.status}."
                )
            }
        }
        val updated = invocation.copy(
            status = status,
            resultRef = resultRef,
            updatedAtMillis = atMillis
        )
        invocations.getValue(invocation.runId)[invocationId] = updated
        updateStepStatusLocked(invocation.runId, invocation.stepId, stepStatusOf(status), atMillis)
        AgentToolInvocationUpdate.Updated
    }

    override suspend fun readInvocation(invocationId: String): ToolInvocation? =
        mutex.withLock { findInvocationLocked(invocationId) }

    override suspend fun readFrozenInvocation(invocationId: String): AgentFrozenInvocation? =
        mutex.withLock { frozenInvocations[invocationId] }

    private fun findByProposalIdentity(proposal: AgentToolProposal): ToolInvocation? =
        invocations.values.asSequence()
            .flatMap { it.values.asSequence() }
            .firstOrNull {
                it.idempotencyKey == proposal.idempotencyKey ||
                    (it.stepId == proposal.stepId && it.callOrdinal == proposal.callOrdinal)
            }

    private fun findInvocationLocked(invocationId: String): ToolInvocation? =
        invocations.values.asSequence()
            .flatMap { it.values.asSequence() }
            .firstOrNull { it.invocationId == invocationId }

    private fun transitionInvocation(
        invocationId: String,
        atMillis: Long,
        allowed: Set<ToolInvocationStatus>?,
        update: (ToolInvocation) -> ToolInvocation?
    ): AgentToolInvocationUpdate? {
        val invocation = findInvocationLocked(invocationId) ?: return null
        if (allowed != null && invocation.status !in allowed) return null
        val next = update(invocation) ?: return null
        invocations.getValue(invocation.runId)[invocationId] = next.copy(updatedAtMillis = atMillis)
        updateStepStatusLocked(
            invocation.runId,
            invocation.stepId,
            stepStatusOf(next.status),
            atMillis
        )
        return AgentToolInvocationUpdate.Updated
    }

    private fun updateStepStatusLocked(
        runId: String,
        stepId: String,
        status: AgentStepStatus,
        atMillis: Long
    ) {
        val list = steps[runId] ?: return
        val current = list[stepId] ?: return
        list[stepId] = current.copy(
            status = status,
            endedAtMillis = when (status) {
                AgentStepStatus.SUCCEEDED,
                AgentStepStatus.FAILED,
                AgentStepStatus.CANCELLED,
                AgentStepStatus.NOT_EXECUTED,
                AgentStepStatus.UNKNOWN -> atMillis
                else -> current.endedAtMillis
            }
        )
    }

    private fun stepStatusOf(status: ToolInvocationStatus): AgentStepStatus = when (status) {
        ToolInvocationStatus.WAITING_APPROVAL, ToolInvocationStatus.APPROVED -> AgentStepStatus.WAITING
        ToolInvocationStatus.RUNNING -> AgentStepStatus.RUNNING
        ToolInvocationStatus.SUCCEEDED -> AgentStepStatus.SUCCEEDED
        ToolInvocationStatus.FAILED -> AgentStepStatus.FAILED
        ToolInvocationStatus.CANCELLED -> AgentStepStatus.CANCELLED
        ToolInvocationStatus.DENIED,
        ToolInvocationStatus.EXPIRED,
        ToolInvocationStatus.NOT_EXECUTED -> AgentStepStatus.NOT_EXECUTED
        ToolInvocationStatus.UNKNOWN -> AgentStepStatus.UNKNOWN
        ToolInvocationStatus.PROPOSED, ToolInvocationStatus.VALIDATED -> AgentStepStatus.PENDING
    }

    private fun ToolInvocation.matchesFrozenIdentity(command: FrozenToolCommand): Boolean =
        invocationId == command.invocationId &&
            runId == command.runId &&
            stepId == command.stepId &&
            callOrdinal == command.callOrdinal &&
            toolKey == command.toolKey &&
            toolVersion == command.toolVersion &&
            schemaHash == command.schemaHash
}
