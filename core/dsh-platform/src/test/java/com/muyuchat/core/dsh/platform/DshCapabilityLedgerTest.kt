package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshCapabilityLedgerCompletion
import com.muyuchat.core.dsh.contract.capability.DshCapabilityLedgerDecision
import com.muyuchat.core.dsh.contract.capability.DshCapabilityOperationState
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRequestId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshCapabilityLedgerTest {

    private val fingerprint = "a".repeat(64)
    private val otherFingerprint = "b".repeat(64)
    private val path = "/notify"

    @Test
    fun newIdentityIsAdmittedAndVisibleAsPending() {
        val ledger = DshCapabilityLedger()

        val decision = ledger.begin(requestId(1), path, fingerprint, generation = 7L, nowMillis = 1_000L)

        assertEquals(DshCapabilityLedgerDecision.New, decision)
        val stats = ledger.stats()
        assertEquals(1, stats.entries)
        assertEquals(1, stats.pending)
        assertEquals(0, stats.unknown)
        val record = requireNotNull(ledger.lookup(requestId(1)))
        assertEquals(DshCapabilityOperationState.RECEIVED, record.state)
        assertEquals(7L, record.generation)
    }

    @Test
    fun completedIdentityReplaysStoredReceipt() {
        val ledger = DshCapabilityLedger()
        val id = requestId(1)
        ledger.begin(id, path, fingerprint, 7L, 1_000L)
        assertEquals(
            DshCapabilityLedgerCompletion.COMPLETED,
            ledger.complete(id, fingerprint, DshCapabilityOperationState.OBSERVED, 200, "{\"ok\":true}", 1_010L)
        )

        val replay = ledger.begin(id, path, fingerprint, 7L, 2_000L)

        assertTrue(replay is DshCapabilityLedgerDecision.Replay)
        replay as DshCapabilityLedgerDecision.Replay
        assertEquals(DshCapabilityOperationState.OBSERVED, replay.state)
        assertEquals(200, replay.statusCode)
        assertEquals("{\"ok\":true}", replay.bodyJson)
    }

    @Test
    fun pendingDuplicateIsInProgress() {
        val ledger = DshCapabilityLedger()
        ledger.begin(requestId(1), path, fingerprint, 7L, 1_000L)

        assertEquals(
            DshCapabilityLedgerDecision.InProgress,
            ledger.begin(requestId(1), path, fingerprint, 7L, 1_001L)
        )
    }

    @Test
    fun unknownOutcomeIsNeverReExecuted() {
        val ledger = DshCapabilityLedger()
        val id = requestId(1)
        ledger.begin(id, path, fingerprint, 7L, 1_000L)
        assertEquals(
            DshCapabilityLedgerCompletion.COMPLETED,
            ledger.markUnknown(id, fingerprint, 1_050L)
        )

        assertEquals(
            DshCapabilityLedgerDecision.OutcomeUnknown,
            ledger.begin(id, path, fingerprint, 8L, 2_000L)
        )
        val record = requireNotNull(ledger.lookup(id))
        assertEquals(DshCapabilityOperationState.UNKNOWN, record.state)
        assertNull(record.statusCode)
    }

    @Test
    fun reusedIdentityWithDifferentPayloadIsRejected() {
        val ledger = DshCapabilityLedger()
        ledger.begin(requestId(1), path, fingerprint, 7L, 1_000L)

        assertEquals(
            DshCapabilityLedgerDecision.FingerprintMismatch,
            ledger.begin(requestId(1), path, otherFingerprint, 7L, 1_001L)
        )
    }

    @Test
    fun settlingAnUnknownIdentityReportsMissing() {
        val ledger = DshCapabilityLedger()

        assertEquals(
            DshCapabilityLedgerCompletion.MISSING,
            ledger.complete(requestId(9), fingerprint, DshCapabilityOperationState.OBSERVED, 200, "{}", 1L)
        )
        assertEquals(
            DshCapabilityLedgerCompletion.MISSING,
            ledger.markUnknown(requestId(9), fingerprint, 1L)
        )
    }

    @Test
    fun settlingWithDifferentFingerprintIsRejected() {
        val ledger = DshCapabilityLedger()
        ledger.begin(requestId(1), path, fingerprint, 7L, 1_000L)

        assertEquals(
            DshCapabilityLedgerCompletion.FINGERPRINT_MISMATCH,
            ledger.complete(requestId(1), otherFingerprint, DshCapabilityOperationState.OBSERVED, 200, "{}", 1_010L)
        )
        assertEquals(
            DshCapabilityLedgerCompletion.FINGERPRINT_MISMATCH,
            ledger.markUnknown(requestId(1), otherFingerprint, 1_010L)
        )
    }

    @Test
    fun secondSettlementCannotOverwriteTheFirstReceipt() {
        val ledger = DshCapabilityLedger()
        val id = requestId(1)
        ledger.begin(id, path, fingerprint, 7L, 1_000L)
        ledger.complete(id, fingerprint, DshCapabilityOperationState.OBSERVED, 200, "first", 1_010L)

        assertEquals(
            DshCapabilityLedgerCompletion.ALREADY_SETTLED,
            ledger.complete(id, fingerprint, DshCapabilityOperationState.FAILED, 500, "second", 1_020L)
        )
        assertEquals(
            DshCapabilityLedgerCompletion.ALREADY_SETTLED,
            ledger.markUnknown(id, fingerprint, 1_030L)
        )
        val replay = ledger.begin(id, path, fingerprint, 7L, 2_000L) as DshCapabilityLedgerDecision.Replay
        assertEquals("first", replay.bodyJson)
    }

    @Test
    fun aTimeoutCannotOverwriteATerminalReceipt() {
        val ledger = DshCapabilityLedger()
        val id = requestId(1)
        ledger.begin(id, path, fingerprint, 7L, 1_000L)
        ledger.complete(id, fingerprint, DshCapabilityOperationState.OBSERVED, 200, "receipt", 1_010L)

        assertEquals(
            DshCapabilityLedgerCompletion.ALREADY_SETTLED,
            ledger.markUnknown(id, fingerprint, 1_020L)
        )

        val replay = ledger.begin(id, path, fingerprint, 7L, 3_000L) as DshCapabilityLedgerDecision.Replay
        assertEquals("receipt", replay.bodyJson)
    }

    @Test
    fun settledReceiptAgesIntoATombstoneThatRefusesReplay() {
        val ledger = DshCapabilityLedger(capacity = 1, tombstoneCapacity = 2)
        ledger.begin(requestId(1), path, fingerprint, 7L, 1_000L)
        ledger.complete(requestId(1), fingerprint, DshCapabilityOperationState.OBSERVED, 200, "old", 1_010L)

        assertEquals(
            DshCapabilityLedgerDecision.New,
            ledger.begin(requestId(2), path, fingerprint, 7L, 2_000L)
        )
        assertEquals(
            DshCapabilityLedgerDecision.Expired,
            ledger.begin(requestId(1), path, fingerprint, 7L, 2_001L)
        )
        assertNull(ledger.lookup(requestId(1)))
        assertEquals(1, ledger.stats().tombstones)
    }

    @Test
    fun pendingIdentityIsNeverEvictedForCapacity() {
        val ledger = DshCapabilityLedger(capacity = 1, tombstoneCapacity = 1)
        ledger.begin(requestId(1), path, fingerprint, 7L, 1_000L)

        assertEquals(
            DshCapabilityLedgerDecision.CapacityExceeded,
            ledger.begin(requestId(2), path, fingerprint, 7L, 1_001L)
        )
    }

    @Test
    fun unknownIdentityIsNeverEvictedForCapacity() {
        val ledger = DshCapabilityLedger(capacity = 1, tombstoneCapacity = 1)
        ledger.begin(requestId(1), path, fingerprint, 7L, 1_000L)
        ledger.markUnknown(requestId(1), fingerprint, 1_010L)

        assertEquals(
            DshCapabilityLedgerDecision.CapacityExceeded,
            ledger.begin(requestId(2), path, fingerprint, 7L, 1_001L)
        )
    }

    @Test
    fun tombstoneBudgetExhaustionRefusesNewIdentity() {
        val ledger = DshCapabilityLedger(capacity = 1, tombstoneCapacity = 1)
        ledger.begin(requestId(1), path, fingerprint, 7L, 1_000L)
        ledger.complete(requestId(1), fingerprint, DshCapabilityOperationState.OBSERVED, 200, "a", 1_010L)
        ledger.begin(requestId(2), path, fingerprint, 7L, 2_000L)
        ledger.complete(requestId(2), fingerprint, DshCapabilityOperationState.OBSERVED, 200, "b", 2_010L)

        assertEquals(
            DshCapabilityLedgerDecision.CapacityExceeded,
            ledger.begin(requestId(3), path, fingerprint, 7L, 3_000L)
        )
        assertEquals(1, ledger.stats().tombstones)
    }

    @Test
    fun oversizedReceiptIsNotRetainedAndCannotBeReplayed() {
        val ledger = DshCapabilityLedger(maxRetainedBodyChars = 4)
        val id = requestId(1)
        ledger.begin(id, path, fingerprint, 7L, 1_000L)
        ledger.complete(id, fingerprint, DshCapabilityOperationState.OBSERVED, 200, "too-large", 1_010L)

        assertEquals(
            DshCapabilityLedgerDecision.Expired,
            ledger.begin(id, path, fingerprint, 7L, 2_000L)
        )
    }

    @Test
    fun statsSeparatePendingTerminalAndUnknown() {
        val ledger = DshCapabilityLedger()
        ledger.begin(requestId(1), path, fingerprint, 7L, 1_000L)
        ledger.begin(requestId(2), path, fingerprint, 7L, 1_000L)
        ledger.complete(requestId(2), fingerprint, DshCapabilityOperationState.OBSERVED, 200, "r", 1_010L)
        ledger.begin(requestId(3), path, fingerprint, 7L, 1_000L)
        ledger.markUnknown(requestId(3), fingerprint, 1_010L)

        val stats = ledger.stats()

        assertEquals(3, stats.entries)
        assertEquals(1, stats.pending)
        assertEquals(1, stats.unknown)
        assertEquals(256, stats.capacity)
    }

    private fun requestId(number: Int): DshCapabilityRequestId =
        DshCapabilityRequestId("request-id-${number.toString().padStart(8, '0')}")
}
