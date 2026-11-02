package com.muyuchat.core.agent.runtime.journal

import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.testing.AgentRuntimeFixtures
import com.muyuchat.core.agent.runtime.validation.AgentV1ToolSpecs
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator

/**
 * JVM fixtures for the dispatch-outbox contract and crash matrix. The journal doubles as the
 * authoritative invocation ledger, so these tests exercise the production in-memory adapter rather
 * than a bespoke fake.
 */
internal class AgentDispatchHarness {
    val journal = InMemoryAgentJournal()
    val store = InMemoryAgentDispatchOutboxStore()
    val descriptor: AgentToolDescriptor = AgentV1ToolSpecs.descriptor(
        AgentV1ToolSpecs.WORKSPACE_READ_TEXT,
        SupportedToolSchemaValidator()
    )

    var nowMillis: Long = 1_000L
    val clock: () -> Long = { nowMillis }

    lateinit var task: AgentTask
        private set
    lateinit var run: AgentRun
        private set

    suspend fun seedRun() {
        task = AgentRuntimeFixtures.task()
        journal.commit(CreateTaskTransition(commandId = "dispatch-task", task = task))
        run = AgentRuntimeFixtures.run(state = AgentRunState.RUNNING)
        journal.commit(
            CreateRunTransition(
                commandId = "dispatch-run",
                run = run,
                taskRevision = task.revision,
                taskDisplay = AgentDisplayStatus.RUNNING
            )
        )
    }

    /** Drives the real ledger to a durable APPROVED row and (by default) its dispatch outbox row. */
    suspend fun approveInvocation(
        invocationId: String = "invocation-1",
        stepId: String = "step-1",
        stepIndex: Int = 0,
        callOrdinal: Int = 0,
        enqueue: Boolean = true
    ): AgentDispatchOutboxEntry {
        seedFrozenToWaiting(invocationId, stepId, stepIndex, callOrdinal)
        check(journal.markApproved(invocationId, nowMillis) is AgentToolInvocationUpdate.Updated)
        val invocation = requireNotNull(journal.readInvocation(invocationId))
        check(invocation.status == ToolInvocationStatus.APPROVED)
        val entry = AgentDispatchOutboxEntry.approved(invocation, nowMillis)
        if (enqueue) {
            check(store.enqueue(entry) is AgentDispatchEnqueueResult.Recorded)
        }
        return entry
    }

    /**
     * A frozen invocation that never received an approval (or whose approval transaction rolled
     * back). The outbox row exists but must never cause a dispatch.
     */
    suspend fun uncommittedApprovalEntry(
        invocationId: String = "invocation-uncommitted",
        stepId: String = "step-uncommitted",
        stepIndex: Int = 1,
        callOrdinal: Int = 1,
        enqueue: Boolean = true
    ): AgentDispatchOutboxEntry {
        seedFrozenToWaiting(invocationId, stepId, stepIndex, callOrdinal)
        val invocation = requireNotNull(journal.readInvocation(invocationId))
        check(invocation.status == ToolInvocationStatus.WAITING_APPROVAL)
        val entry = AgentDispatchOutboxEntry(
            idempotencyKey = AgentDispatchOutboxEntry.keyFor(invocationId),
            invocationId = invocationId,
            runId = run.runId,
            stepId = stepId,
            callOrdinal = callOrdinal,
            toolKey = descriptor.toolKey,
            toolVersion = descriptor.version,
            approvedAtMillis = nowMillis,
            updatedAtMillis = nowMillis
        )
        if (enqueue) {
            check(store.enqueue(entry) is AgentDispatchEnqueueResult.Recorded)
        }
        return entry
    }

    private suspend fun seedFrozenToWaiting(
        invocationId: String,
        stepId: String,
        stepIndex: Int,
        callOrdinal: Int
    ) {
        val proposal = AgentToolProposal(
            invocationId = invocationId,
            runId = run.runId,
            taskId = task.taskId,
            stepId = stepId,
            stepIndex = stepIndex,
            callOrdinal = callOrdinal,
            toolKey = descriptor.toolKey,
            toolVersion = descriptor.version,
            schemaHash = descriptor.schemaHash,
            argumentsHash = "a".repeat(64),
            proposedAtMillis = nowMillis
        )
        check(journal.recordProposed(proposal) is AgentToolInvocationRecord.Recorded)
        check(
            journal.recordFrozen(
                AgentFrozenInvocation(
                    command = commandFor(invocationId, stepId, callOrdinal),
                    descriptor = descriptor,
                    scope = AgentRuntimeFixtures.scopeFor(task),
                    recordedAtMillis = nowMillis
                )
            ) is AgentToolInvocationRecord.Recorded
        )
        check(journal.markWaitingApproval(invocationId, nowMillis) is AgentToolInvocationUpdate.Updated)
    }

    /** A pending outbox row with no (or foreign) authorization; the dispatcher must never run it. */
    fun danglingEntry(
        invocationId: String = "invocation-missing",
        toolKey: String = descriptor.toolKey,
        approvedAtMillis: Long = nowMillis
    ): AgentDispatchOutboxEntry = AgentDispatchOutboxEntry(
        idempotencyKey = AgentDispatchOutboxEntry.keyFor(invocationId),
        invocationId = invocationId,
        runId = run.runId,
        stepId = "step-dangling",
        callOrdinal = 0,
        toolKey = toolKey,
        toolVersion = descriptor.version,
        approvedAtMillis = approvedAtMillis,
        updatedAtMillis = approvedAtMillis
    )

    fun commandFor(
        invocationId: String = "invocation-1",
        stepId: String = "step-1",
        callOrdinal: Int = 0
    ): FrozenToolCommand = FrozenToolCommand(
        invocationId = invocationId,
        runId = run.runId,
        stepId = stepId,
        callOrdinal = callOrdinal,
        toolKey = descriptor.toolKey,
        toolVersion = descriptor.version,
        schemaHash = descriptor.schemaHash,
        normalizedArgumentsJson = """{"limit":4096,"offset":0,"path":"inputs/brief.txt"}""",
        argumentsHash = "a".repeat(64),
        resourceFingerprint = "dispatch-resource",
        runEpoch = run.epoch,
        policyVersion = run.policy.policyVersion,
        expiresAtMillis = nowMillis + 1_000_000L
    )
}

internal enum class DispatchCrashPoint {
    NONE,
    BEFORE_EXECUTION,
    DURING_EXECUTION,
    BEFORE_RESULT_COMMIT,
    AFTER_RESULT_COMMIT
}

/** Test-only process-death signal: the dispatcher must let a JVM Error escape, like a real crash. */
internal class SimulatedProcessDeath : Error("simulated process death")

/**
 * Executor double that mirrors the production contract: the `markRunning` CAS is the single
 * execution gate, one terminal settlement follows, and [crash] kills the JVM between those steps.
 */
internal class RecordingDispatchExecutor(
    private val ledger: AgentToolInvocationLedger,
    private val crash: DispatchCrashPoint = DispatchCrashPoint.NONE
) : AgentDispatchExecutor {
    val effects: MutableList<String> = mutableListOf()
    var dispatchCalls: Int = 0
        private set
    var runningGateWins: Int = 0
        private set

    override suspend fun dispatch(entry: AgentDispatchOutboxEntry): AgentDispatchExecution {
        dispatchCalls += 1
        if (crash == DispatchCrashPoint.BEFORE_EXECUTION) throw SimulatedProcessDeath()
        val invocation = ledger.readInvocation(entry.invocationId)
            ?: return AgentDispatchExecution.Refused(
                AgentErrorCodes.INTERNAL_INVARIANT,
                "the invocation row is missing"
            )
        if (invocation.status != ToolInvocationStatus.APPROVED) {
            // A duplicate hand-off must lose the execution gate and produce no second side effect.
            return AgentDispatchExecution.Settled(invocation.status)
        }
        val gate = ledger.markRunning(entry.invocationId, entry.approvedAtMillis + dispatchCalls)
        if (gate !is AgentToolInvocationUpdate.Updated) {
            return AgentDispatchExecution.Settled(
                ledger.readInvocation(entry.invocationId)?.status ?: ToolInvocationStatus.UNKNOWN
            )
        }
        runningGateWins += 1
        if (crash == DispatchCrashPoint.DURING_EXECUTION) throw SimulatedProcessDeath()
        effects += entry.invocationId
        if (crash == DispatchCrashPoint.BEFORE_RESULT_COMMIT) throw SimulatedProcessDeath()
        ledger.markTerminal(
            invocationId = entry.invocationId,
            status = ToolInvocationStatus.SUCCEEDED,
            possibleSideEffects = false,
            resultRef = "result-1",
            atMillis = entry.approvedAtMillis + dispatchCalls + 1L
        )
        if (crash == DispatchCrashPoint.AFTER_RESULT_COMMIT) throw SimulatedProcessDeath()
        return AgentDispatchExecution.Settled(ToolInvocationStatus.SUCCEEDED)
    }
}

internal fun AgentDispatchHarness.dispatcher(
    executor: AgentDispatchExecutor,
    dispatcherId: String = "dispatcher-1",
    leaseMillis: Long = 1_000L,
    approvalGraceMillis: Long = 0L
): AgentDispatchOutboxDispatcher = AgentDispatchOutboxDispatcher(
    store = store,
    ledger = journal,
    executor = executor,
    dispatcherId = dispatcherId,
    clock = clock,
    leaseMillis = leaseMillis,
    approvalGraceMillis = approvalGraceMillis
)

internal fun AgentDispatchHarness.reconciler(
    approvalGraceMillis: Long = 0L
): AgentDispatchStartupReconciler = AgentDispatchStartupReconciler(
    store = store,
    ledger = journal,
    clock = clock,
    approvalGraceMillis = approvalGraceMillis
)
