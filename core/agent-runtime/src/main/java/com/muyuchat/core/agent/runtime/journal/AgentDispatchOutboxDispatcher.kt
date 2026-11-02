package com.muyuchat.core.agent.runtime.journal

import com.muyuchat.core.agent.contract.ToolInvocation
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import kotlinx.coroutines.CancellationException

/**
 * Narrow execution seam used by the dispatch outbox.
 *
 * The implementation owns the real `RUNNING` CAS and the terminal settlement; the dispatcher only
 * records *that it handed one approved invocation to that single executor*. A crash between the
 * hand-off and the receipt therefore stays `DISPATCHING` and is fenced, never resent.
 */
fun interface AgentDispatchExecutor {
    suspend fun dispatch(entry: AgentDispatchOutboxEntry): AgentDispatchExecution
}

/**
 * Receipt reported by one executor hand-off.
 *
 * An implementation must return [Refused] only when it provably performed no side effect and left
 * the invocation ledger unchanged; anything else that is not a durable terminal must be [Unknown].
 */
sealed interface AgentDispatchExecution {
    /** The executor observed a durable terminal invocation row (success, failure or NOT_EXECUTED). */
    data class Settled(val invocationStatus: ToolInvocationStatus) : AgentDispatchExecution

    /** Refused before execution; the invocation row was not advanced and no side effect occurred. */
    data class Refused(val errorCode: String, val safeMessage: String) : AgentDispatchExecution

    /** The hand-off happened but no durable terminal fact exists; may have caused side effects. */
    data class Unknown(val errorCode: String, val safeMessage: String) : AgentDispatchExecution

    /**
     * The entry was handed to the single in-process execution owner. The receipt is asynchronous:
     * the invocation ledger stays authoritative and the terminal fact settles the outbox
     * (`AgentApprovalOutboxLedger`) or, after a crash, the startup reconciler. The dispatcher must
     * leave the row `DISPATCHING` and must not treat this as a completed execution.
     */
    data object HandedOff : AgentDispatchExecution
}

/** Counters for one bounded dispatch pass; every count is a real store transition, not an attempt. */
data class AgentDispatchPassReport(
    val examined: Int,
    val dispatched: Int,
    val settled: Int,
    val needsReview: Int,
    val expired: Int,
    val failed: Int,
    val deferred: Int,
    val skipped: Int,
    /** Hand-offs accepted by the in-process owner whose receipt will arrive asynchronously. */
    val handedOff: Int = 0
) {
    val mutated: Int
        get() = dispatched + settled + needsReview + expired + failed
}

/**
 * Single, bounded dispatch pass over the durable outbox (N03/N19).
 *
 * For every active row the authoritative [AgentToolInvocationLedger] decides:
 * - terminal invocation -> `SETTLED` (the executor is never called again); an `UNKNOWN` terminal is
 *   fenced as `NEEDS_REVIEW`, never reported as success;
 * - `RUNNING` -> `NEEDS_REVIEW` (the process may have died mid-execution);
 * - not yet `APPROVED` -> deferred inside the approval grace window, then `EXPIRED`
 *   (an outbox row never authorises an invocation that was not committed);
 * - `APPROVED` -> claim a lease, record `DISPATCHING`, hand to the executor once, then settle.
 *
 * A `DISPATCHING` row is never touched by the periodic pass: while a receipt can still arrive it is
 * in flight, and after a process death the startup reconciler
 * ([AgentDispatchStartupReconciler]) owns the fence. This keeps a long-running tool from being
 * mistaken for a lost dispatch after its lease elapsed.
 *
 * The dispatcher is stateless between passes, so a new instance replays the same decisions from
 * durable state.
 */
class AgentDispatchOutboxDispatcher(
    private val store: AgentDispatchOutboxStore,
    private val ledger: AgentToolInvocationLedger,
    private val executor: AgentDispatchExecutor,
    private val dispatcherId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val leaseMillis: Long = DEFAULT_LEASE_MILLIS,
    private val approvalGraceMillis: Long = DEFAULT_APPROVAL_GRACE_MILLIS
) {
    init {
        require(dispatcherId.isNotBlank()) { "dispatcherId must not be blank" }
        require(leaseMillis > 0L) { "leaseMillis must be positive" }
        require(approvalGraceMillis >= 0L) { "approvalGraceMillis must not be negative" }
    }

    suspend fun dispatchOnce(maxEntries: Int = DEFAULT_MAX_ENTRIES): AgentDispatchPassReport {
        require(maxEntries > 0) { "maxEntries must be positive" }
        val now = clock()
        val active = store.listActive(maxEntries)
        val tally = Tally()
        for (entry in active) {
            if (entry.status.isTerminal) continue
            tally.examined += 1
            when (entry.status) {
                AgentDispatchOutboxStatus.PENDING -> inspectThenDispatch(entry, now, tally)

                AgentDispatchOutboxStatus.CLAIMED -> {
                    if (entry.leaseIsActiveAt(now)) {
                        tally.skipped += 1
                    } else {
                        // The previous dispatcher died before recording a dispatch intent: no
                        // executor was handed this entry, so a fresh lease may dispatch it once.
                        inspectThenDispatch(entry, now, tally)
                    }
                }

                AgentDispatchOutboxStatus.DISPATCHING -> {
                    // In-flight hand-off or an unresolved receipt. The startup reconciler fences it;
                    // a periodic pass must not race a long-running tool execution.
                    tally.skipped += 1
                }

                else -> tally.skipped += 1
            }
        }
        return tally.toReport()
    }

    private suspend fun inspectThenDispatch(
        entry: AgentDispatchOutboxEntry,
        nowMillis: Long,
        tally: Tally
    ) {
        val invocation = readInvocation(entry)
        when (classify(invocation, entry, nowMillis)) {
            Verdict.Missing -> settleAndCount(
                entry.idempotencyKey,
                AgentDispatchOutboxStatus.NEEDS_REVIEW,
                null,
                "invocation_missing",
                tally
            )

            Verdict.IdentityMismatch -> settleAndCount(
                entry.idempotencyKey,
                AgentDispatchOutboxStatus.FAILED,
                null,
                "entry_identity_mismatch",
                tally
            )

            Verdict.Terminal -> settleTerminal(entry.idempotencyKey, invocation, tally)

            Verdict.Running -> settleAndCount(
                entry.idempotencyKey,
                AgentDispatchOutboxStatus.NEEDS_REVIEW,
                null,
                "invocation_running",
                tally
            )

            Verdict.Defer -> tally.deferred += 1

            Verdict.Expired -> settleAndCount(
                entry.idempotencyKey,
                AgentDispatchOutboxStatus.EXPIRED,
                null,
                "approval_not_committed",
                tally
            )

            Verdict.Approved -> claimAndDispatch(entry, nowMillis, tally)
        }
    }

    private suspend fun claimAndDispatch(
        entry: AgentDispatchOutboxEntry,
        nowMillis: Long,
        tally: Tally
    ) {
        val claimed = when (val result = store.claim(entry.idempotencyKey, dispatcherId, nowMillis, leaseMillis)) {
            is AgentDispatchClaimResult.Granted -> result.entry
            AgentDispatchClaimResult.NotAvailable,
            AgentDispatchClaimResult.NotFound -> {
                tally.skipped += 1
                return
            }
        }
        // Revalidate after winning the lease: the approval may have expired, been cancelled or
        // already been settled by the owner between listing and claiming.
        val invocation = readInvocation(claimed)
        when (classify(invocation, claimed, nowMillis)) {
            Verdict.Approved -> Unit
            Verdict.Defer -> {
                releaseAndDefer(claimed, nowMillis, tally)
                return
            }
            Verdict.Missing -> {
                settleAndCount(
                    claimed.idempotencyKey,
                    AgentDispatchOutboxStatus.NEEDS_REVIEW,
                    null,
                    "invocation_missing",
                    tally
                )
                return
            }
            Verdict.IdentityMismatch -> {
                settleAndCount(
                    claimed.idempotencyKey,
                    AgentDispatchOutboxStatus.FAILED,
                    null,
                    "entry_identity_mismatch",
                    tally
                )
                return
            }
            Verdict.Terminal -> {
                settleTerminal(claimed.idempotencyKey, invocation, tally)
                return
            }
            Verdict.Running -> {
                settleAndCount(
                    claimed.idempotencyKey,
                    AgentDispatchOutboxStatus.NEEDS_REVIEW,
                    null,
                    "invocation_running",
                    tally
                )
                return
            }
            Verdict.Expired -> {
                settleAndCount(
                    claimed.idempotencyKey,
                    AgentDispatchOutboxStatus.EXPIRED,
                    null,
                    "approval_not_committed",
                    tally
                )
                return
            }
        }
        val dispatching = when (
            val result = store.markDispatching(claimed.idempotencyKey, claimed.revision, dispatcherId, nowMillis)
        ) {
            is AgentDispatchUpdateResult.Updated -> result.entry
            is AgentDispatchUpdateResult.Rejected -> {
                tally.skipped += 1
                return
            }
        }
        tally.dispatched += 1
        val execution = try {
            executor.dispatch(dispatching)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AgentDispatchExecution.Unknown(
                "dispatch_failed",
                "The executor failed before it confirmed a durable result."
            )
        }
        settleAfterExecution(dispatching, execution, tally)
    }

    private suspend fun settleAfterExecution(
        entry: AgentDispatchOutboxEntry,
        execution: AgentDispatchExecution,
        tally: Tally
    ) {
        if (execution is AgentDispatchExecution.HandedOff) {
            // The existing run owner now drives the tool. Its durable terminal fact (or the startup
            // reconciler after a crash) closes this row; a pass must not guess a receipt.
            tally.handedOff += 1
            return
        }
        val invocation = readInvocation(entry)
        if (invocation == null) {
            settleAndCount(
                entry.idempotencyKey,
                AgentDispatchOutboxStatus.NEEDS_REVIEW,
                null,
                "invocation_missing",
                tally
            )
            return
        }
        if (invocation.status.isTerminal) {
            settleTerminal(entry.idempotencyKey, invocation, tally)
            return
        }
        if (invocation.status == ToolInvocationStatus.RUNNING) {
            settleAndCount(
                entry.idempotencyKey,
                AgentDispatchOutboxStatus.NEEDS_REVIEW,
                null,
                "execution_not_settled",
                tally
            )
            return
        }
        when (execution) {
            is AgentDispatchExecution.Refused -> settleAndCount(
                entry.idempotencyKey,
                AgentDispatchOutboxStatus.FAILED,
                null,
                execution.errorCode,
                tally
            )

            is AgentDispatchExecution.Unknown -> settleAndCount(
                entry.idempotencyKey,
                AgentDispatchOutboxStatus.NEEDS_REVIEW,
                null,
                execution.errorCode,
                tally
            )

            is AgentDispatchExecution.Settled -> settleAndCount(
                entry.idempotencyKey,
                AgentDispatchOutboxStatus.NEEDS_REVIEW,
                null,
                "executor_settlement_not_durable",
                tally
            )

            AgentDispatchExecution.HandedOff -> Unit
        }
    }

    private enum class Verdict { Approved, Terminal, Running, Missing, IdentityMismatch, Defer, Expired }

    private suspend fun classify(
        invocation: ToolInvocation?,
        entry: AgentDispatchOutboxEntry,
        nowMillis: Long
    ): Verdict {
        if (invocation == null) return Verdict.Missing
        if (invocation.invocationId != entry.invocationId ||
            invocation.runId != entry.runId ||
            invocation.stepId != entry.stepId ||
            invocation.callOrdinal != entry.callOrdinal ||
            invocation.toolKey != entry.toolKey ||
            invocation.toolVersion != entry.toolVersion
        ) {
            return Verdict.IdentityMismatch
        }
        return when {
            invocation.status.isTerminal -> Verdict.Terminal
            invocation.status == ToolInvocationStatus.RUNNING -> Verdict.Running
            invocation.status == ToolInvocationStatus.APPROVED -> Verdict.Approved
            nowMillis - entry.approvedAtMillis <= approvalGraceMillis -> Verdict.Defer
            else -> Verdict.Expired
        }
    }

    /** A terminal ledger fact settles the row; an UNKNOWN terminal is fenced, never a success. */
    private suspend fun settleTerminal(
        idempotencyKey: String,
        invocation: ToolInvocation?,
        tally: Tally
    ) {
        val status = invocation?.status ?: return
        if (status == ToolInvocationStatus.UNKNOWN) {
            settleAndCount(
                idempotencyKey,
                AgentDispatchOutboxStatus.NEEDS_REVIEW,
                null,
                "invocation_unknown",
                tally
            )
        } else {
            settleAndCount(
                idempotencyKey,
                AgentDispatchOutboxStatus.SETTLED,
                status,
                null,
                tally
            )
        }
    }

    private suspend fun readInvocation(entry: AgentDispatchOutboxEntry): ToolInvocation? =
        ledger.readInvocation(entry.invocationId)

    private suspend fun releaseAndDefer(
        entry: AgentDispatchOutboxEntry,
        nowMillis: Long,
        tally: Tally
    ) {
        val released = store.release(entry.idempotencyKey, entry.revision, dispatcherId, nowMillis)
        if (released is AgentDispatchUpdateResult.Updated) {
            tally.deferred += 1
        } else {
            tally.skipped += 1
        }
    }

    private suspend fun settleAndCount(
        idempotencyKey: String,
        status: AgentDispatchOutboxStatus,
        invocationStatus: ToolInvocationStatus?,
        errorCode: String?,
        tally: Tally
    ) {
        val current = store.read(idempotencyKey) ?: return
        if (current.status.isTerminal) return
        val result = store.settle(
            idempotencyKey = idempotencyKey,
            expectedRevision = current.revision,
            status = status,
            invocationStatus = invocationStatus,
            errorCode = errorCode,
            nowMillis = clock()
        )
        if (result !is AgentDispatchUpdateResult.Updated) return
        when (status) {
            AgentDispatchOutboxStatus.SETTLED -> tally.settled += 1
            AgentDispatchOutboxStatus.EXPIRED -> tally.expired += 1
            AgentDispatchOutboxStatus.FAILED -> tally.failed += 1
            AgentDispatchOutboxStatus.NEEDS_REVIEW -> tally.needsReview += 1
            else -> Unit
        }
    }

    private class Tally {
        var examined: Int = 0
        var dispatched: Int = 0
        var handedOff: Int = 0
        var settled: Int = 0
        var needsReview: Int = 0
        var expired: Int = 0
        var failed: Int = 0
        var deferred: Int = 0
        var skipped: Int = 0

        fun toReport(): AgentDispatchPassReport = AgentDispatchPassReport(
            examined = examined,
            dispatched = dispatched,
            settled = settled,
            needsReview = needsReview,
            expired = expired,
            failed = failed,
            deferred = deferred,
            skipped = skipped,
            handedOff = handedOff
        )
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 16
        const val DEFAULT_LEASE_MILLIS: Long = 60_000L

        /**
         * A dispatch row is written inside the approval transaction; another process may observe it
         * a moment before the invocation row is visible. Within this window a non-approved
         * invocation is deferred instead of being expired, so a slow commit is never mistaken for
         * a missing authorization. Nothing is dispatched either way.
         */
        const val DEFAULT_APPROVAL_GRACE_MILLIS: Long = 30_000L
    }
}
