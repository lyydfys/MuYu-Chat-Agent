package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentJournal
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentQuestion
import com.muyuchat.core.agent.contract.AgentQuestionStatus
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.core.agent.contract.AgentTransition
import com.muyuchat.core.agent.contract.AnswerAgentQuestion
import com.muyuchat.core.agent.contract.AnswerQuestionTransition
import com.muyuchat.core.agent.contract.AppendEventsTransition
import com.muyuchat.core.agent.contract.AskQuestionTransition
import com.muyuchat.core.agent.contract.CancelAgentRun
import com.muyuchat.core.agent.contract.ConsumeApprovalTransition
import com.muyuchat.core.agent.contract.ContinueAgentTask
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.contract.PauseAgentRun
import com.muyuchat.core.agent.contract.ResolveAgentApproval
import com.muyuchat.core.agent.contract.StartAgentRun
import com.muyuchat.core.agent.contract.UpdateRunTransition
import com.muyuchat.core.agent.contract.UpsertApprovalTransition
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.journal.AgentApprovalReader
import com.muyuchat.core.agent.runtime.journal.AgentCommandLedger
import com.muyuchat.core.agent.runtime.journal.AgentCommandRecord
import com.muyuchat.core.agent.runtime.journal.AgentDispatchExecution
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxEntry
import com.muyuchat.core.agent.runtime.journal.AgentJournalRejectedException
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationLedger
import com.muyuchat.core.agent.runtime.journal.NoAgentToolInvocationLedger
import com.muyuchat.core.agent.runtime.journal.AgentQuestionReader
import com.muyuchat.core.agent.runtime.journal.AgentTaskReader
import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

class AgentRunOwner(
    internal val journal: AgentJournal,
    internal val ledger: AgentCommandLedger,
    internal val taskReader: AgentTaskReader,
    internal val questionReader: AgentQuestionReader,
    internal val approvalReader: AgentApprovalReader,
    /**
     * Durable tool-invocation ledger. The production composition injects the Room adapter; the
     * fail-closed default can never record or execute a tool call.
     */
    internal val toolLedger: AgentToolInvocationLedger = NoAgentToolInvocationLedger,
    /**
     * One-shot authorization seam. A resume without it is refused, never executed blindly.
     */
    internal val toolAuthorization: AgentToolAuthorizationPort = NoAgentToolAuthorization,
    internal val specProvider: AgentRunSpecProvider,
    internal val executor: AgentRunExecutor,
    internal val processor: AgentCommandProcessor,
    internal val clock: AgentMonotonicClock,
    internal val ids: AgentIdGenerator,
    internal val scope: CoroutineScope,
    internal val attemptRegistry: AgentActiveAttemptRegistry = AgentActiveAttemptRegistry(),
    /**
     * App-scope owner of the real native/network resource fence. Null keeps the historical
     * compatibility contract for owners that own no native resource; production Agent composition
     * must inject the source that consumes the Agent model lease settlement.
     */
    internal val stopSettlementSource: AgentRunStopSettlementSource? = null,
    /**
     * Durable boundary for approval resolution (N03): the approval consume, invocation state and
     * run/ledger transition must commit together. Production injects the shared Room runner.
     */
    internal val transaction: AgentRunTransaction = AgentRunTransaction.Direct,
    /**
     * Outbox hand-off registry (N03 production wiring). When a sink is present, an approved resume is
     * offered to the background dispatch loop instead of being launched inline; the loop calls
     * [dispatchApproved] to consume the exact in-memory authorization. Null keeps the legacy inline
     * launch for tests and harnesses that own no outbox.
     */
    internal val dispatchHandoff: AgentApprovedDispatchHandoff? = null,
    internal val dispatchSink: AgentApprovedDispatchSink? = null,
    /**
     * Durable park/wake tokens (S4). Production injects the Room-backed store; the no-op default
     * keeps harnesses that own no database on the historical in-memory park contract.
     */
    internal val parkTokens: AgentParkTokenStore = NoAgentParkTokenStore,
    internal val stopJoinTimeoutMillis: Long = DEFAULT_STOP_JOIN_TIMEOUT_MILLIS
) {
    init {
        require(stopJoinTimeoutMillis > 0L) { "stopJoinTimeoutMillis must be positive" }
    }
    internal val codec: StrictJsonCodec = StrictAgentJsonCodec()
    internal val waiting = ArrayDeque<String>()
    internal val suspended = HashMap<String, AgentLoopTranscript>()
    internal val pendingApprovals = HashMap<String, PendingApprovalExecution>()
    /**
     * Exact in-memory resume for a run that already committed `RUNNING` after a park (user answer or
     * approval decision) while the single admission slot is owned by another run (S2/WF-17).
     *
     * The resume is one-shot: [promote] consumes it, re-checks the durable epoch and `RUNNING` state,
     * and never executes when they no longer match. It is process memory only — durable park/wake
     * tokens are NOT implemented yet (S4).
     */
    internal val pendingResumes = HashMap<String, PendingOwnerResume>()
    /**
     * In-flight STOPPING settlement waiters. The serial command processor is the only caller that
     * inserts; the waiter coroutine removes itself. The lazy job is registered before it starts so
     * a repeated cancel cannot start a duplicate settlement.
     */
    internal val stopWaiters = HashMap<String, Job>()
    /** Last typed settlement received for a STOPPING run; diagnostics/tests, not a second writer. */
    internal val stopSettlements = java.util.concurrent.ConcurrentHashMap<String, AgentStopSettlementReceipt>()

    @Volatile
    internal var active: ActiveExecution? = null

    @Volatile
    internal var activeRunIdValue: String? = null

    suspend fun createTask(command: CreateAgentTask): AgentCommandResult =
        serial(AgentCommandPriority.NORMAL) { createTaskLocked(command) }

    suspend fun startRun(command: StartAgentRun): AgentCommandResult =
        serial(AgentCommandPriority.NORMAL) { startRunLocked(command) }

    suspend fun requestPause(command: PauseAgentRun): AgentCommandResult =
        serial(AgentCommandPriority.CONTROL) { pauseLocked(command) }

    suspend fun requestCancel(command: CancelAgentRun): AgentCommandResult =
        serial(AgentCommandPriority.CONTROL) { cancelLocked(command) }

    suspend fun continueTask(command: ContinueAgentTask): AgentCommandResult =
        serial(AgentCommandPriority.NORMAL) { continueLocked(command) }

    suspend fun answerQuestion(command: AnswerAgentQuestion): AgentCommandResult =
        serial(AgentCommandPriority.NORMAL) { answerLocked(command) }

    suspend fun resolveApproval(command: ResolveAgentApproval): AgentCommandResult =
        serial(AgentCommandPriority.NORMAL) { approvalLocked(command) }

    /**
     * Reports the final resource settlement for a STOPPING run. This is the late-proof entry point
     * for an app-scope executor that proves quiescence after the bounded first settlement query.
     *
     * The receipt is checked against the persisted run id + epoch and the STOPPING state, so a
     * late proof can neither settle another run nor revive a terminal one. Repeated receipts are
     * idempotent: the CANCELLED terminal is written at most once.
     */
    suspend fun reportStopSettlement(receipt: AgentStopSettlementReceipt): AgentCommandResult =
        serial(AgentCommandPriority.CONTROL) { applyStopSettlementReceipt(receipt) }

    /**
     * Consumes one outbox dispatch row by starting the run loop with the exact in-memory
     * authorization published by the approval command.
     *
     * The call is serialized with every other owner command and never consumes an approval, creates
     * an executor or reconstructs authority from storage. When no live authorization matches the row
     * it returns [AgentDispatchExecution.Refused] with zero side effects.
     */
    suspend fun dispatchApproved(entry: AgentDispatchOutboxEntry): AgentDispatchExecution {
        val outcome = processor.submit(AgentCommandPriority.CONTROL) { dispatchApprovedLocked(entry) }
        return when (outcome) {
            is AgentProcessorResult.Completed -> outcome.value
            is AgentProcessorResult.Rejected -> AgentDispatchExecution.Refused(
                outcome.error.code,
                outcome.error.safeMessage
            )
        }
    }

    /**
     * Validates one durable park wake without executing a resume.
     *
     * The token only keeps a WAITING_APPROVAL/WAITING_USER run resumable across process death; a
     * wake never reconstructs a one-shot authorization. Repeated wakes for the same parked run are
     * side-effect free, and a token whose epoch/revision no longer matches the run row is retired
     * with a typed stale result.
     */
    suspend fun wakeParked(runId: String, wakeId: String): AgentParkWakeResult {
        val outcome = processor.submit(AgentCommandPriority.CONTROL) {
            wakeParkedLocked(runId, wakeId)
        }
        return when (outcome) {
            is AgentProcessorResult.Completed -> outcome.value
            is AgentProcessorResult.Rejected -> AgentParkWakeResult.Stale(
                runId,
                outcome.error.code,
                outcome.error.safeMessage
            )
        }
    }

    /** Non-suspending stale-callback guard used by model adapters. */
    fun acceptAttemptCallback(callback: AgentAttemptResultCallback): AgentAttemptCallbackDecision {
        val current = active
            ?: return AgentAttemptCallbackDecision.Stale(
                AgentErrorCodes.RESOURCE_LEASE_LOST,
                "No run is active."
            )
        if (callback.identity.runId != current.runId) {
            return AgentAttemptCallbackDecision.Stale(
                AgentErrorCodes.RESOURCE_LEASE_LOST,
                "The callback belongs to a different run."
            )
        }
        if (callback.identity.epoch != current.epoch) {
            return AgentAttemptCallbackDecision.Stale(
                AgentErrorCodes.RESOURCE_LEASE_LOST,
                "The callback epoch is stale."
            )
        }
        return attemptRegistry.verify(callback.identity)
    }

    fun activeRunId(): String? = activeRunIdValue

    /**
     * Buffers text the user typed while [runId] is running; the loop reads it at the next step
     * boundary (Mika's "interject, read before the next step").
     *
     * Lives on the owner because it is the only object the UI/host holds for a live run: the queue
     * itself is process-wide ([AgentInterjectionQueues.global]) so the loop coroutine can reach the
     * same instance without widening every loop construction site.
     *
     * Refused (returns false, nothing queued) when [runId] is not the run currently in the single
     * admission slot: text typed for a queued or already-finished run would be delivered to the wrong
     * turn. A false return value from an accepted-but-evicting enqueue is propagated as-is.
     */
    fun enqueueInterjection(runId: String, text: String): Boolean {
        if (activeRunIdValue != runId) return false
        return AgentInterjectionQueues.global.enqueue(runId, text, clock.nowMillis())
    }

    /** Read-only view of the pending interjections for [runId]; never consumes them. */
    fun pendingInterjections(runId: String): List<String> =
        AgentInterjectionQueues.global.pending(runId)

    fun close() {
        synchronized(stopWaiters) {
            stopWaiters.values.forEach { waiter -> waiter.cancel() }
            stopWaiters.clear()
        }
        stopSettlements.clear()
        processor.close()
    }

    private suspend fun serial(
        priority: AgentCommandPriority,
        block: suspend () -> AgentCommandResult
    ): AgentCommandResult {
        val outcome = processor.submit(priority) { block() }
        return when (outcome) {
            is AgentProcessorResult.Completed -> outcome.value
            is AgentProcessorResult.Rejected -> AgentCommandResult.Rejected(outcome.error)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Commands (all invoked from the serial processor context).
    // ------------------------------------------------------------------------------------------

}

internal data class ActiveExecution(
        val runId: String,
        val epoch: Long,
        val job: Job?
    )

internal data class PendingApprovalExecution(
        val approvalId: String,
        val invocationId: String,
        val callHash: String,
        val toolKey: String
    )

/** Which park the queued resume resumes; used only to pick the fail-closed pause reason. */
internal enum class PendingResumeKind { ANSWER, APPROVAL }

/**
 * One exact, in-memory-only resume that is queued behind the single owner admission slot.
 *
 * [epoch] is captured from the durable `RUNNING` row committed by the answer/approval command; the
 * promotion path replays it against the current row so a resume can never start for another epoch.
 * [invocationId] is set only for an approved tool resume, whose never-executed invocation must be
 * settled `NOT_EXECUTED` if the resume is dropped.
 */
internal data class PendingOwnerResume(
        val runId: String,
        val epoch: Long,
        val resume: AgentLoopResume,
        val kind: PendingResumeKind,
        val invocationId: String? = null
    ) {
        init {
            require(epoch > 0L) { "pending resume epoch must be positive" }
        }

        /** Recoverable pause reason written when this resume can never be executed. */
        val failClosedReason: AgentPauseReason
            get() = if (kind == PendingResumeKind.APPROVAL) {
                AgentPauseReason.APPROVAL_STALE
            } else {
                AgentPauseReason.INTERRUPTED
            }
    }

internal const val AGENT_RUN_OWNER_CORRELATION_ID = "agent-run-owner"
