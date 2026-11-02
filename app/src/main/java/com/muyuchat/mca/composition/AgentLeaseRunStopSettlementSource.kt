package com.muyuchat.mca.composition

import com.muyuchat.core.agent.contract.AgentModelAttemptRef
import com.muyuchat.core.agent.runtime.run.AgentRunStopSettlement
import com.muyuchat.core.agent.runtime.run.AgentRunStopSettlementSource
import com.muyuchat.core.agent.runtime.run.AgentStopSettlementReceipt
import com.muyuchat.mca.agent.inference.AgentLeaseRelease
import com.muyuchat.mca.agent.inference.AgentLeaseSettlementListener
import com.muyuchat.mca.agent.inference.AgentLeaseSnapshot
import com.muyuchat.mca.agent.inference.AgentLeaseState
import com.muyuchat.mca.agent.inference.AgentModelLeaseRetention
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * App-scope stop settlement for one Agent run, derived from the real
 * [AgentModelLeaseRetention] accounting (C01 / P01 / P02).
 *
 * The answer is deliberately conservative:
 *  * no retained lease for the run, or every retained lease `RELEASED` -> [AgentRunStopSettlement.Released]
 *    ("there is no resource fence to hold");
 *  * any lease still `ACQUIRED`/`DISPATCHED`/`STOPPING`/`FENCE_HELD` -> [AgentRunStopSettlement.StoppingUnknown],
 *    so the owner keeps STOPPING and the lease/active slot stay retained;
 *  * a retention read failure -> [AgentRunStopSettlement.Failed], which also keeps STOPPING.
 *
 * It never waits for the engine and never unlocks a lease: the adapter is the only releaser. When a
 * later engine proof releases a lease that this source answered as unknown, [onLeaseSettled] reports
 * the typed `Released` receipt through the injected app-scope reporter
 * (`AgentRunOwner.reportStopSettlement`), so the owner can write the single CANCELLED terminal.
 */
internal class AgentLeaseRunStopSettlementSource(
    private val retention: AgentModelLeaseRetention,
    private val scope: CoroutineScope,
    /**
     * Journal lookup used when a stop-driven release happens after the owner's bounded settlement
     * query (for example when joining the execution job timed out). It returns the epoch only when
     * that exact run is still STOPPING, so a late receipt is bound to a real pending stop and a
     * settled/terminal run is never touched again.
     */
    private val lookupStoppingRunEpoch: suspend (runId: String) -> Long?,
    private val reportLateSettlement: suspend (AgentStopSettlementReceipt) -> Unit
) : AgentRunStopSettlementSource, AgentLeaseSettlementListener {

    private data class PendingAttempt(val runId: String, val runEpoch: Long)

    /** Attempt id -> run identity that asked about it while its lease was still held. */
    private val pendingAttempts = ConcurrentHashMap<String, PendingAttempt>()

    override suspend fun settlementFor(
        runId: String,
        runEpoch: Long
    ): AgentStopSettlementReceipt {
        val snapshots = readSnapshots(runId)
            ?: return failedReceipt(runId, runEpoch, "Agent model lease retention could not be read")
        if (snapshots.all { it.state == AgentLeaseState.RELEASED }) {
            pruneReleased(runId, snapshots)
            return releasedReceipt(runId, runEpoch, snapshots)
        }
        recordPending(runId, runEpoch, snapshots)
        // A release notification can race the first snapshot. Re-reading after the pending identity
        // is registered closes that window: the second read either proves every lease RELEASED or a
        // later onLeaseSettled call reports the exact attempt that was released.
        val rechecked = readSnapshots(runId)
            ?: return failedReceipt(runId, runEpoch, "Agent model lease retention could not be re-read")
        if (rechecked.all { it.state == AgentLeaseState.RELEASED }) {
            pruneReleased(runId, rechecked)
            return releasedReceipt(runId, runEpoch, rechecked)
        }
        recordPending(runId, runEpoch, rechecked)
        return AgentStopSettlementReceipt(
            runId = runId,
            runEpoch = runEpoch,
            settlement = AgentRunStopSettlement.StoppingUnknown(notReleasedDetail(rechecked))
        )
    }

    override fun onLeaseSettled(
        attempt: AgentModelAttemptRef,
        release: AgentLeaseRelease,
        stoppedExecution: Boolean
    ) {
        val released = releasedSnapshot(release) ?: return
        val pending = pendingAttempts[attempt.attemptId]
        if (pending != null && pending.runId != attempt.runId) {
            // The attempt id was accounted for another run: never report a foreign identity, and
            // never drop the legitimate pending identity.
            return
        }
        if (pending != null) {
            pendingAttempts.remove(attempt.attemptId, pending)
        }
        // A lease released by an ordinary successful attempt is not a stop settlement. Only a
        // stop-driven terminal (or a run whose settlement query retained this attempt) may report.
        if (pending == null && !stoppedExecution) return
        scope.launch {
            val runEpoch = if (pending != null) {
                pending.runEpoch
            } else {
                val lookedUp = try {
                    lookupStoppingRunEpoch(attempt.runId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                lookedUp ?: return@launch
            }
            val receipt = AgentStopSettlementReceipt(
                runId = attempt.runId,
                runEpoch = runEpoch,
                settlement = AgentRunStopSettlement.Released(
                    "Agent model lease for attempt '${attempt.attemptId}' was released after the " +
                        "engine proved the exact request quiet " +
                        "(releasedAtMs=${released.timeline.releasedAtMillis})"
                ),
                attempt = attempt
            )
            if (anotherLeaseForTheRunIsStillHeld(released)) {
                // A newer execution of the same run still holds a lease. Its own proof, not this
                // older release, must settle the stop.
                return@launch
            }
            try {
                reportLateSettlement(receipt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // The owner is closed or rejected the receipt: the run stays STOPPING on purpose.
                // Never retry forever and never unlock the lease from here.
            }
        }
    }

    /**
     * True when another attempt of the same run still holds a lease. The check fails closed: if the
     * account cannot be read, a late release is not reported as the run's settlement.
     */
    private fun anotherLeaseForTheRunIsStillHeld(released: AgentLeaseSnapshot): Boolean = try {
        retention.snapshots().any { snapshot ->
            snapshot.attempt.runId == released.attempt.runId &&
                snapshot.attempt.attemptId != released.attempt.attemptId &&
                snapshot.state != AgentLeaseState.RELEASED
        }
    } catch (_: Throwable) {
        true
    }

    private fun readSnapshots(runId: String): List<AgentLeaseSnapshot>? = try {
        retention.snapshots().filter { snapshot -> snapshot.attempt.runId == runId }
    } catch (_: Throwable) {
        null
    }

    private fun recordPending(
        runId: String,
        runEpoch: Long,
        snapshots: List<AgentLeaseSnapshot>
    ) {
        snapshots.forEach { snapshot ->
            if (snapshot.state == AgentLeaseState.RELEASED) return@forEach
            pendingAttempts[snapshot.attempt.attemptId] = PendingAttempt(runId, runEpoch)
        }
    }

    private fun pruneReleased(runId: String, snapshots: List<AgentLeaseSnapshot>) {
        snapshots.forEach { snapshot ->
            if (snapshot.state != AgentLeaseState.RELEASED) return@forEach
            val pending = pendingAttempts[snapshot.attempt.attemptId] ?: return@forEach
            if (pending.runId != runId) return@forEach
            pendingAttempts.remove(snapshot.attempt.attemptId, pending)
        }
    }

    private fun releasedSnapshot(release: AgentLeaseRelease): AgentLeaseSnapshot? = when (release) {
        is AgentLeaseRelease.Released -> release.snapshot
        is AgentLeaseRelease.AlreadyReleased ->
            release.snapshot?.takeIf { it.state == AgentLeaseState.RELEASED }
        is AgentLeaseRelease.Blocked -> null
        AgentLeaseRelease.UnknownAttempt -> null
    }

    private fun releasedReceipt(
        runId: String,
        runEpoch: Long,
        snapshots: List<AgentLeaseSnapshot>
    ): AgentStopSettlementReceipt = AgentStopSettlementReceipt(
        runId = runId,
        runEpoch = runEpoch,
        settlement = AgentRunStopSettlement.Released(
            if (snapshots.isEmpty()) {
                "no retained Agent model lease exists for run '$runId'"
            } else {
                "all ${snapshots.size} retained Agent model lease(s) are RELEASED for run '$runId'"
            }
        )
    )

    private fun failedReceipt(
        runId: String,
        runEpoch: Long,
        detail: String
    ): AgentStopSettlementReceipt = AgentStopSettlementReceipt(
        runId = runId,
        runEpoch = runEpoch,
        settlement = AgentRunStopSettlement.Failed(detail)
    )

    private fun notReleasedDetail(snapshots: List<AgentLeaseSnapshot>): String =
        "Agent model lease(s) are not proven released: " + snapshots.joinToString(", ") { snapshot ->
            val lastError = snapshot.lastError
                ?.takeIf { it.isNotBlank() }
                ?.let { " (lastError=" + it.take(120) + ")" }
                .orEmpty()
            snapshot.attempt.attemptId + "=" + snapshot.state.name + lastError
        }
}
