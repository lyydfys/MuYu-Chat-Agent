package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshCapabilityLedgerCompletion
import com.muyuchat.core.dsh.contract.capability.DshCapabilityLedgerDecision
import com.muyuchat.core.dsh.contract.capability.DshCapabilityLedgerRecord
import com.muyuchat.core.dsh.contract.capability.DshCapabilityLedgerStats
import com.muyuchat.core.dsh.contract.capability.DshCapabilityOperationState
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRequestId

/**
 * In-memory, bounded idempotency ledger for the App-local capability server.
 *
 * Identity is `(requestId, payloadFingerprint)`: the official DSH invocation is mapped to a stable
 * request id by the managed transport, and the fingerprint binds that id to the exact method, path,
 * query and body. A duplicate with the same parameters replays the stored receipt; a duplicate with
 * different parameters is rejected; a duplicate while the original is still pending is
 * `REQUEST_IN_PROGRESS`; and a request whose outcome was never observed stays `UNKNOWN` and is
 * never re-executed.
 *
 * The ledger deliberately survives generation rotation (the owner keeps one instance and feeds it
 * to every server generation) so a reconnect cannot repeat a side effect under the same id. It
 * stores no token, no user content and no raw payload, only the SHA-256 fingerprint and the
 * handler's JSON receipt.
 *
 * Eviction is fail-closed. Pending and unknown identities are never evicted. A settled receipt that
 * ages out leaves a bounded tombstone so the id is refused as [DshCapabilityLedgerDecision.Expired]
 * instead of being executed a second time; if even the tombstone budget is exhausted, new
 * identities are refused with [DshCapabilityLedgerDecision.CapacityExceeded].
 */
class DshCapabilityLedger(
    private val capacity: Int = 256,
    private val tombstoneCapacity: Int = 2_048,
    private val maxRetainedBodyChars: Int = 65_536
) {
    init {
        require(capacity >= 1) { "ledger capacity must be positive" }
        require(tombstoneCapacity >= capacity) { "tombstone capacity must cover the entry capacity" }
        require(maxRetainedBodyChars >= 0) { "retained body budget must be non-negative" }
    }

    private class Entry(
        val requestId: String,
        val path: String,
        val fingerprint: String,
        val generation: Long,
        val recordedAtMillis: Long,
        var state: DshCapabilityOperationState,
        var settledAtMillis: Long?,
        var statusCode: Int?,
        var bodyJson: String?
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private val tombstones = LinkedHashMap<String, String>()

    /**
     * Reserves [requestId] for one call. [nowMillis] is supplied by the owner's clock so tests are
     * deterministic; the ledger itself never reads time.
     */
    fun begin(
        requestId: DshCapabilityRequestId,
        path: String,
        payloadFingerprint: String,
        generation: Long,
        nowMillis: Long
    ): DshCapabilityLedgerDecision = synchronized(lock) {
        val key = requestId.value
        entries[key]?.let { entry ->
            return@synchronized when {
                entry.fingerprint != payloadFingerprint ->
                    DshCapabilityLedgerDecision.FingerprintMismatch

                entry.state == DshCapabilityOperationState.UNKNOWN ->
                    DshCapabilityLedgerDecision.OutcomeUnknown

                !entry.state.isTerminal ->
                    DshCapabilityLedgerDecision.InProgress

                entry.statusCode == null || entry.bodyJson == null ->
                    DshCapabilityLedgerDecision.Expired

                else -> DshCapabilityLedgerDecision.Replay(
                    state = entry.state,
                    statusCode = entry.statusCode as Int,
                    bodyJson = entry.bodyJson as String
                )
            }
        }
        tombstones[key]?.let { fingerprint ->
            return@synchronized if (fingerprint == payloadFingerprint) {
                DshCapabilityLedgerDecision.Expired
            } else {
                DshCapabilityLedgerDecision.FingerprintMismatch
            }
        }
        if (entries.size >= capacity) {
            val evictable = entries.values.firstOrNull { it.state.isTerminal }
                ?: return@synchronized DshCapabilityLedgerDecision.CapacityExceeded
            if (tombstones.size >= tombstoneCapacity) {
                return@synchronized DshCapabilityLedgerDecision.CapacityExceeded
            }
            entries.remove(evictable.requestId)
            tombstones[evictable.requestId] = evictable.fingerprint
        }
        entries[key] = Entry(
            requestId = key,
            path = path,
            fingerprint = payloadFingerprint,
            generation = generation,
            recordedAtMillis = nowMillis,
            state = DshCapabilityOperationState.RECEIVED,
            settledAtMillis = null,
            statusCode = null,
            bodyJson = null
        )
        DshCapabilityLedgerDecision.New
    }

    /**
     * Settles a pending identity with a replayable receipt. [state] must be terminal
     * (`OBSERVED`, `FAILED`, `REJECTED` or `CANCELLED`); use [markUnknown] when the action may
     * have run but no outcome was observed.
     */
    fun complete(
        requestId: DshCapabilityRequestId,
        payloadFingerprint: String,
        state: DshCapabilityOperationState,
        statusCode: Int,
        bodyJson: String,
        nowMillis: Long
    ): DshCapabilityLedgerCompletion {
        require(state.mayReplayReceipt) { "complete() requires a replayable terminal state" }
        require(statusCode in 100..599) { "receipt status code is out of range" }
        return synchronized(lock) {
            val entry = entries[requestId.value]
                ?: return@synchronized DshCapabilityLedgerCompletion.MISSING
            if (entry.fingerprint != payloadFingerprint) {
                return@synchronized DshCapabilityLedgerCompletion.FINGERPRINT_MISMATCH
            }
            if (entry.state.isTerminal || entry.state == DshCapabilityOperationState.UNKNOWN) {
                return@synchronized DshCapabilityLedgerCompletion.ALREADY_SETTLED
            }
            entry.state = state
            entry.settledAtMillis = nowMillis
            entry.statusCode = statusCode
            entry.bodyJson = if (bodyJson.length <= maxRetainedBodyChars) bodyJson else null
            DshCapabilityLedgerCompletion.COMPLETED
        }
    }

    /**
     * Marks a dispatched identity whose outcome was never observed. It can never be re-executed.
     * Calling this after a terminal settlement is a harmless no-op
     * ([DshCapabilityLedgerCompletion.ALREADY_SETTLED]) so a late timeout cannot overwrite a receipt.
     */
    fun markUnknown(
        requestId: DshCapabilityRequestId,
        payloadFingerprint: String,
        nowMillis: Long
    ): DshCapabilityLedgerCompletion = synchronized(lock) {
        val entry = entries[requestId.value]
            ?: return@synchronized DshCapabilityLedgerCompletion.MISSING
        if (entry.fingerprint != payloadFingerprint) {
            return@synchronized DshCapabilityLedgerCompletion.FINGERPRINT_MISMATCH
        }
        if (entry.state.isTerminal || entry.state == DshCapabilityOperationState.UNKNOWN) {
            return@synchronized DshCapabilityLedgerCompletion.ALREADY_SETTLED
        }
        entry.state = DshCapabilityOperationState.UNKNOWN
        entry.settledAtMillis = nowMillis
        entry.statusCode = null
        entry.bodyJson = null
        DshCapabilityLedgerCompletion.COMPLETED
    }

    /** Read-only projection of one record, or null when the id was never seen (or aged out). */
    fun lookup(requestId: DshCapabilityRequestId): DshCapabilityLedgerRecord? = synchronized(lock) {
        entries[requestId.value]?.let { entry ->
            DshCapabilityLedgerRecord(
                requestId = requestId,
                generation = entry.generation,
                path = entry.path,
                payloadFingerprint = entry.fingerprint,
                state = entry.state,
                recordedAtMillis = entry.recordedAtMillis,
                settledAtMillis = entry.settledAtMillis,
                statusCode = entry.statusCode,
                bodyJson = entry.bodyJson
            )
        }
    }

    fun stats(): DshCapabilityLedgerStats = synchronized(lock) {
        DshCapabilityLedgerStats(
            entries = entries.size,
            pending = entries.values.count { it.state.isPending },
            unknown = entries.values.count { it.state == DshCapabilityOperationState.UNKNOWN },
            capacity = capacity,
            tombstones = tombstones.size
        )
    }
}
