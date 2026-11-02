package com.muyuchat.mca.agent.reliability

import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.ToolInvocation
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchExecutor
import com.muyuchat.core.agent.runtime.journal.AgentDispatchExecution
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxEntry
import com.muyuchat.core.agent.runtime.journal.AgentFrozenInvocation
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationLedger
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationRecord
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationUpdate
import com.muyuchat.core.agent.runtime.journal.AgentToolProposal
import kotlinx.coroutines.CompletableDeferred

/**
 * Minimal in-memory invocation ledger for the app-level outbox tests.
 *
 * It only implements the operations the dispatcher and the executor touch; the proposal/freeze
 * operations fail closed because these tests start from an already-approved invocation row.
 */
internal class TestDispatchLedger : AgentToolInvocationLedger {
    private val invocations = LinkedHashMap<String, ToolInvocation>()

    fun seedApproved(
        invocationId: String = "invocation-1",
        runId: String = "run-1",
        stepId: String = "step-1",
        callOrdinal: Int = 0,
        toolKey: String = "workspace.read_text",
        toolVersion: String = "1.0.0",
        atMillis: Long = 1_000L
    ): AgentDispatchOutboxEntry {
        val invocation = ToolInvocation(
            invocationId = invocationId,
            runId = runId,
            stepId = stepId,
            callOrdinal = callOrdinal,
            toolKey = toolKey,
            toolVersion = toolVersion,
            schemaHash = "schema-hash",
            frozenArgumentsRef = "memory:frozen:$invocationId",
            argumentsHash = "a".repeat(64),
            resourceFingerprint = "resource-fingerprint",
            status = ToolInvocationStatus.APPROVED,
            idempotencyKey = "inv-key-$invocationId",
            resultRef = null,
            createdAtMillis = atMillis,
            updatedAtMillis = atMillis
        )
        invocations[invocationId] = invocation
        return AgentDispatchOutboxEntry.approved(invocation, atMillis)
    }

    fun seedWaiting(
        invocationId: String = "invocation-waiting",
        stepId: String = "step-waiting",
        callOrdinal: Int = 0,
        atMillis: Long = 1_000L
    ): String {
        val invocation = ToolInvocation(
            invocationId = invocationId,
            runId = "run-1",
            stepId = stepId,
            callOrdinal = callOrdinal,
            toolKey = "workspace.read_text",
            toolVersion = "1.0.0",
            schemaHash = "schema-hash",
            frozenArgumentsRef = "memory:frozen:$invocationId",
            argumentsHash = "a".repeat(64),
            resourceFingerprint = "resource-fingerprint",
            status = ToolInvocationStatus.WAITING_APPROVAL,
            idempotencyKey = "inv-key-$invocationId",
            resultRef = null,
            createdAtMillis = atMillis,
            updatedAtMillis = atMillis
        )
        invocations[invocationId] = invocation
        return invocationId
    }

    fun statusOf(invocationId: String): ToolInvocationStatus? = invocations[invocationId]?.status

    override suspend fun readInvocation(invocationId: String): ToolInvocation? =
        invocations[invocationId]

    override suspend fun markRunning(
        invocationId: String,
        atMillis: Long
    ): AgentToolInvocationUpdate {
        val current = invocations[invocationId]
            ?: return AgentToolInvocationUpdate.Rejected(
                AgentErrorCodes.COMMAND_NOT_FOUND,
                "invocation not found"
            )
        if (current.status != ToolInvocationStatus.APPROVED) {
            return AgentToolInvocationUpdate.Rejected(
                AgentErrorCodes.COMMAND_INVALID_STATE,
                "the execution gate already lost"
            )
        }
        invocations[invocationId] = current.copy(
            status = ToolInvocationStatus.RUNNING,
            updatedAtMillis = atMillis
        )
        return AgentToolInvocationUpdate.Updated
    }

    override suspend fun markTerminal(
        invocationId: String,
        status: ToolInvocationStatus,
        possibleSideEffects: Boolean,
        resultRef: String?,
        atMillis: Long
    ): AgentToolInvocationUpdate {
        require(status.isTerminal) { "markTerminal requires a terminal status" }
        val current = invocations[invocationId]
            ?: return AgentToolInvocationUpdate.Rejected(
                AgentErrorCodes.COMMAND_NOT_FOUND,
                "invocation not found"
            )
        if (current.status.isTerminal) {
            return if (current.status == status) {
                AgentToolInvocationUpdate.Updated
            } else {
                AgentToolInvocationUpdate.Rejected(
                    AgentErrorCodes.COMMAND_INVALID_STATE,
                    "a different terminal value already exists"
                )
            }
        }
        invocations[invocationId] = current.copy(
            status = status,
            resultRef = resultRef,
            updatedAtMillis = atMillis
        )
        return AgentToolInvocationUpdate.Updated
    }

    override suspend fun recordProposed(proposal: AgentToolProposal): AgentToolInvocationRecord =
        AgentToolInvocationRecord.Rejected(
            AgentErrorCodes.INTERNAL_INVARIANT,
            "the fixture starts from an approved invocation"
        )

    override suspend fun recordFrozen(frozen: AgentFrozenInvocation): AgentToolInvocationRecord =
        AgentToolInvocationRecord.Rejected(
            AgentErrorCodes.INTERNAL_INVARIANT,
            "the fixture starts from an approved invocation"
        )

    override suspend fun markWaitingApproval(
        invocationId: String,
        atMillis: Long
    ): AgentToolInvocationUpdate = AgentToolInvocationUpdate.Rejected(
        AgentErrorCodes.INTERNAL_INVARIANT,
        "the fixture starts from an approved invocation"
    )

    override suspend fun markApproved(
        invocationId: String,
        atMillis: Long
    ): AgentToolInvocationUpdate {
        val current = invocations[invocationId]
            ?: return AgentToolInvocationUpdate.Rejected(
                AgentErrorCodes.COMMAND_NOT_FOUND,
                "invocation not found"
            )
        if (current.status == ToolInvocationStatus.APPROVED) {
            return AgentToolInvocationUpdate.Updated
        }
        if (current.status != ToolInvocationStatus.VALIDATED &&
            current.status != ToolInvocationStatus.WAITING_APPROVAL
        ) {
            return AgentToolInvocationUpdate.Rejected(
                AgentErrorCodes.COMMAND_INVALID_STATE,
                "the invocation cannot be approved from ${current.status}"
            )
        }
        invocations[invocationId] = current.copy(
            status = ToolInvocationStatus.APPROVED,
            updatedAtMillis = atMillis
        )
        return AgentToolInvocationUpdate.Updated
    }

    override suspend fun readFrozenInvocation(invocationId: String): AgentFrozenInvocation? = null
}

internal enum class TestCrashPoint {
    NONE,
    BEFORE_EXECUTION,
    DURING_EXECUTION,
    BEFORE_RESULT_COMMIT,
    AFTER_RESULT_COMMIT
}

/** Test-only abrupt-death signal; it is an Error so the dispatcher does not convert it to a state. */
internal class TestProcessDeath : Error("simulated process death")

/**
 * Executor double with the production gate order: `markRunning` first, external effect second,
 * terminal settlement third. [gate] holds a pass open so lifecycle tests can inspect single-flight.
 */
internal class TestDispatchExecutor(
    private val ledger: TestDispatchLedger,
    private val crash: TestCrashPoint = TestCrashPoint.NONE,
    private val gate: CompletableDeferred<Unit>? = null
) : AgentDispatchExecutor {
    val effects: MutableList<String> = mutableListOf()
    var dispatchCalls: Int = 0
        private set
    var runningGateWins: Int = 0
        private set

    override suspend fun dispatch(entry: AgentDispatchOutboxEntry): AgentDispatchExecution {
        dispatchCalls += 1
        if (crash == TestCrashPoint.BEFORE_EXECUTION) throw TestProcessDeath()
        gate?.await()
        if (ledger.statusOf(entry.invocationId) != ToolInvocationStatus.APPROVED) {
            return AgentDispatchExecution.Settled(
                ledger.statusOf(entry.invocationId) ?: ToolInvocationStatus.UNKNOWN
            )
        }
        val gateUpdate = ledger.markRunning(entry.invocationId, entry.approvedAtMillis + dispatchCalls)
        if (gateUpdate !is AgentToolInvocationUpdate.Updated) {
            return AgentDispatchExecution.Settled(
                ledger.statusOf(entry.invocationId) ?: ToolInvocationStatus.UNKNOWN
            )
        }
        runningGateWins += 1
        if (crash == TestCrashPoint.DURING_EXECUTION) throw TestProcessDeath()
        effects += entry.invocationId
        if (crash == TestCrashPoint.BEFORE_RESULT_COMMIT) throw TestProcessDeath()
        ledger.markTerminal(
            invocationId = entry.invocationId,
            status = ToolInvocationStatus.SUCCEEDED,
            possibleSideEffects = false,
            resultRef = "result-1",
            atMillis = entry.approvedAtMillis + dispatchCalls + 1L
        )
        if (crash == TestCrashPoint.AFTER_RESULT_COMMIT) throw TestProcessDeath()
        return AgentDispatchExecution.Settled(ToolInvocationStatus.SUCCEEDED)
    }
}
