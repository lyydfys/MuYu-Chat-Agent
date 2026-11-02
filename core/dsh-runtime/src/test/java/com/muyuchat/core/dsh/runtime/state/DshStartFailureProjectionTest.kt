package com.muyuchat.core.dsh.runtime.state

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshRuntimeFailure
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DshStartFailureProjectionTest {

    private val requested = DshBindingId("b-0123456789abcdef")

    private fun ready(epoch: Long = 3L) = DshRuntimeSnapshot(
        phase = DshRuntimePhase.READY,
        epoch = epoch,
        origin = "http://127.0.0.1:4567"
    )

    private fun failed(code: DshFailureCode, retryable: Boolean) = DshRuntimeSnapshot(
        phase = DshRuntimePhase.FAILED,
        epoch = 3L,
        failure = DshRuntimeFailure(code = code, message = "typed", retryable = retryable)
    )

    private fun result(
        status: DshApplyStatus,
        snapshot: DshRuntimeSnapshot,
        applied: DshBindingId? = null,
        retained: DshBindingId? = null,
        detail: String? = null
    ) = DshApplyResult(
        status = status,
        requested = requested,
        applied = applied,
        retained = retained,
        epoch = snapshot.epoch,
        snapshot = snapshot,
        detail = detail
    )

    @Test
    fun aKilledHostKeepsItsOwnTypedFailureInsteadOfBecomingACorruptPayload() {
        // Device evidence: the host process was killed under memory pressure and the apply failed
        // with NODE_START_FAILED; the UI showed "payload is corrupt, reinstall".
        val snapshot = failed(DshFailureCode.NODE_START_FAILED, retryable = true)

        val projected = DshStartFailureProjection.resolve(
            result = result(DshApplyStatus.FAILED, snapshot, detail = "apply timed out"),
            closureRequested = false
        )

        assertSame(snapshot, projected)
        assertEquals(DshFailureCode.NODE_START_FAILED, projected.failure?.code)
        assertTrue(projected.failure!!.retryable)
    }

    @Test
    fun aFailedApplyWithoutAnOwnerFailureIsARetryableInternalFailureForTheInstalledPayload() {
        val projected = DshStartFailureProjection.resolve(
            result = result(DshApplyStatus.FAILED, ready(), detail = "host did not answer"),
            closureRequested = false,
            nowMillis = 9L
        )

        assertEquals(DshRuntimePhase.FAILED, projected.phase)
        assertEquals(DshFailureCode.INTERNAL, projected.failure?.code)
        assertTrue(projected.failure!!.retryable)
        assertEquals("host did not answer", projected.failure?.diagnostics)
        assertEquals(9L, projected.changedAtMillis)
    }

    @Test
    fun aRefusedClosureTreeIsTheOnlyPayloadIntegrityProjection() {
        val projected = DshStartFailureProjection.resolve(
            result = result(DshApplyStatus.FAILED, ready(), detail = "payload root escapes the closure store"),
            closureRequested = true
        )

        assertEquals(DshFailureCode.PAYLOAD_INTEGRITY, projected.failure?.code)
        assertFalse(projected.failure!!.retryable)
        assertEquals("payload root escapes the closure store", projected.failure?.diagnostics)
    }

    @Test
    fun anAppliedStartReturnsTheOwnerSnapshotUnchanged() {
        val snapshot = ready()
        val projected = DshStartFailureProjection.resolve(
            result = result(DshApplyStatus.APPLIED, snapshot, applied = requested),
            closureRequested = true
        )

        assertSame(snapshot, projected)
    }

    @Test
    fun aDeferredApplyIsNotRelabelledAsAFailure() {
        val snapshot = ready()
        val projected = DshStartFailureProjection.resolve(
            result = result(DshApplyStatus.DEFERRED_GATEWAY_UNAVAILABLE, snapshot),
            closureRequested = false
        )

        assertSame(snapshot, projected)
    }
}
