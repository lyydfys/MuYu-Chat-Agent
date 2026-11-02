package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentRunState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Upper bound for waiting for the execution job to quiesce before the typed settlement source is
 * asked. It is deliberately shorter than an unbounded native call: a wedged model/network call is
 * reported as [AgentRunStopSettlement.StoppingUnknown], never as CANCELLED.
 */
internal const val DEFAULT_STOP_JOIN_TIMEOUT_MILLIS = 8_000L

/**
 * Starts exactly one STOPPING settlement waiter for [runId]. The command processor is the only
 * caller; the waiter registers itself before starting so repeated cancel commands cannot start a
 * second settlement or write the terminal twice.
 *
 * @param job execution job to cancel+join. Null or [executionReturned] means the execution already
 * returned and only the resource settlement is outstanding.
 */
internal fun AgentRunOwner.startStopSettlement(
    runId: String,
    runEpoch: Long,
    job: Job?,
    executionReturned: Boolean
) {
    val owner = this
    val joinTimeoutMillis = stopJoinTimeoutMillis
    val waiter = scope.launch(start = CoroutineStart.LAZY) {
        try {
            val joined = when {
                executionReturned || job == null -> true
                else -> withTimeoutOrNull(joinTimeoutMillis) {
                    job.cancelAndJoin()
                    true
                } ?: false
            }
            if (!joined) {
                owner.publishStopSettlement(
                    AgentStopSettlementReceipt(
                        runId = runId,
                        runEpoch = runEpoch,
                        settlement = AgentRunStopSettlement.StoppingUnknown(
                            "the execution job did not quiesce within ${joinTimeoutMillis}ms"
                        )
                    )
                )
                return@launch
            }
            owner.publishStopSettlement(owner.resolveStopSettlement(runId, runEpoch))
        } finally {
            synchronized(owner.stopWaiters) { owner.stopWaiters.remove(runId) }
        }
    }
    synchronized(stopWaiters) {
        if (stopWaiters.containsKey(runId)) {
            waiter.cancel()
            return
        }
        stopWaiters[runId] = waiter
    }
    waiter.start()
}

/** Asks the app-scope source once, with a bounded first answer; failures keep STOPPING. */
private suspend fun AgentRunOwner.resolveStopSettlement(
    runId: String,
    runEpoch: Long
): AgentStopSettlementReceipt {
    val source = stopSettlementSource
        ?: return AgentStopSettlementReceipt(
            runId = runId,
            runEpoch = runEpoch,
            settlement = AgentRunStopSettlement.Released(
                "legacy compatibility: the execution job joined and no settlement source is composed"
            )
        )
    return try {
        // The source contract is bounded, but the owner does not trust an unbounded implementation:
        // losing the answer keeps STOPPING instead of hanging the settlement waiter forever.
        val receipt = withTimeoutOrNull(stopJoinTimeoutMillis) {
            source.settlementFor(runId, runEpoch)
        } ?: return AgentStopSettlementReceipt(
            runId = runId,
            runEpoch = runEpoch,
            settlement = AgentRunStopSettlement.StoppingUnknown(
                "the stop settlement source did not answer within ${stopJoinTimeoutMillis}ms"
            )
        )
        if (receipt.runId != runId || receipt.runEpoch != runEpoch) {
            AgentStopSettlementReceipt(
                runId = runId,
                runEpoch = runEpoch,
                settlement = AgentRunStopSettlement.Failed(
                    "the stop settlement source returned a foreign run identity"
                )
            )
        } else {
            receipt
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        AgentStopSettlementReceipt(
            runId = runId,
            runEpoch = runEpoch,
            settlement = AgentRunStopSettlement.Failed(
                "the stop settlement source failed: " +
                    (failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName)
                        .take(200)
            )
        )
    }
}

/**
 * Publishes a settlement through the single owner processor. A closed owner leaves the run in
 * STOPPING on purpose; losing the proof must never release the fence.
 */
private suspend fun AgentRunOwner.publishStopSettlement(receipt: AgentStopSettlementReceipt) {
    try {
        processor.submit(AgentCommandPriority.CONTROL) {
            applyStopSettlementReceipt(receipt)
            AgentCommandResult.Accepted(0L, receipt.runId)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        // The owner is closed or the processor rejected the control command; STOPPING is retained.
    }
}

/**
 * Applies a typed settlement while the serial processor owns the run state. Only a [Released]
 * receipt for the exact run epoch + STOPPING state writes the single CANCELLED terminal.
 */
internal suspend fun AgentRunOwner.applyStopSettlementReceipt(
    receipt: AgentStopSettlementReceipt
): AgentCommandResult {
    val snapshot = journal.readRun(receipt.runId)
        ?: return rejected(AgentErrorCodes.COMMAND_NOT_FOUND, "Run '${receipt.runId}' not found.")
    val run = snapshot.run
    if (run.epoch != receipt.runEpoch) {
        return rejected(
            AgentErrorCodes.RESOURCE_LEASE_LOST,
            "Stop settlement belongs to run epoch ${receipt.runEpoch}; the current epoch is ${run.epoch}."
        )
    }
    val attempt = receipt.attempt
    if (attempt != null && attempt.runId != receipt.runId) {
        return rejected(
            AgentErrorCodes.RESOURCE_LEASE_LOST,
            "Stop settlement attempt belongs to run '${attempt.runId}', not '${receipt.runId}'."
        )
    }
    if (run.state == AgentRunState.CANCELLED) {
        // The terminal is written exactly once; late repeats are acknowledged but change nothing.
        stopSettlements.remove(receipt.runId)
        return AgentCommandResult.Accepted(run.revision, run.runId, idempotentReplay = true)
    }
    if (run.state != AgentRunState.STOPPING) {
        return rejected(
            AgentErrorCodes.COMMAND_INVALID_STATE,
            "Run '${receipt.runId}' is ${run.state.name}; stop settlement is only valid in STOPPING."
        )
    }
    stopSettlements[receipt.runId] = receipt
    return when (receipt.settlement) {
        is AgentRunStopSettlement.Released -> {
            settleCancel(receipt.runId)
            val settled = journal.readRun(receipt.runId)?.run
            AgentCommandResult.Accepted(settled?.revision ?: run.revision, receipt.runId)
        }

        is AgentRunStopSettlement.StoppingUnknown,
        is AgentRunStopSettlement.Failed ->
            // Keep STOPPING, the active slot and the resource fence. A later released receipt may
            // still settle this exact run once.
            AgentCommandResult.Accepted(run.revision, run.runId)
    }
}
