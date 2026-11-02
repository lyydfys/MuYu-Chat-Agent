package com.muyuchat.core.dsh.runtime.service

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshStopSnapshotPolicyTest {

    private fun snapshot(
        phase: DshRuntimePhase,
        epoch: Long,
        changedAt: Long = 100L
    ) = DshRuntimeSnapshot(
        phase = phase,
        epoch = epoch,
        origin = if (phase == DshRuntimePhase.READY) "http://127.0.0.1:4321" else null,
        failure = null,
        changedAtMillis = changedAt
    )

    @Test
    fun missingReplyWithoutConfirmedDeathIsRetryableStopTimeout() {
        val current = snapshot(DshRuntimePhase.READY, epoch = 3L, changedAt = 100L)
        val resolved = DshStopSnapshotPolicy.resolve(
            current = current,
            reply = null,
            nowMillis = 200L
        )
        assertEquals(DshRuntimePhase.FAILED, resolved.phase)
        assertEquals(3L, resolved.epoch)
        assertEquals(DshFailureCode.STOP_TIMEOUT, resolved.failure?.code)
        assertTrue(resolved.failure?.retryable == true)
        assertNull(resolved.origin)
    }

    @Test
    fun missingReplyAfterBindingLossIsStillRetryableStopTimeout() {
        // Binder loss is not proof of STOPPED: Node is a child and can outlive the service.
        val current = snapshot(DshRuntimePhase.READY, epoch = 3L, changedAt = 100L)
        val resolved = DshStopSnapshotPolicy.resolve(
            current = current,
            reply = null,
            nowMillis = 200L
        )
        assertEquals(DshRuntimePhase.FAILED, resolved.phase)
        assertEquals(3L, resolved.epoch)
        assertEquals(DshFailureCode.STOP_TIMEOUT, resolved.failure?.code)
        assertTrue(resolved.failure?.retryable == true)
    }

    @Test
    fun staleReplyIsNotAppliedAndBecomesRetryableStopTimeout() {
        val current = snapshot(DshRuntimePhase.READY, epoch = 3L, changedAt = 300L)
        val stale = snapshot(DshRuntimePhase.STOPPED, epoch = 2L, changedAt = 999L)
        val resolved = DshStopSnapshotPolicy.resolve(
            current = current,
            reply = stale,
            nowMillis = 400L
        )
        assertEquals(DshRuntimePhase.FAILED, resolved.phase)
        assertEquals(3L, resolved.epoch)
        assertEquals(DshFailureCode.STOP_TIMEOUT, resolved.failure?.code)
        assertTrue(resolved.failure?.retryable == true)
    }

    @Test
    fun newerReplyIsAppliedVerbatim() {
        val current = snapshot(DshRuntimePhase.READY, epoch = 3L, changedAt = 300L)
        val reply = snapshot(DshRuntimePhase.STOPPED, epoch = 4L, changedAt = 100L)
        val resolved = DshStopSnapshotPolicy.resolve(
            current = current,
            reply = reply,
            nowMillis = 400L
        )
        assertEquals(reply, resolved)
    }

    @Test
    fun sameEpochOlderTimestampIsStale() {
        val current = snapshot(DshRuntimePhase.READY, epoch = 3L, changedAt = 300L)
        val reply = snapshot(DshRuntimePhase.STOPPED, epoch = 3L, changedAt = 200L)
        val resolved = DshStopSnapshotPolicy.resolve(
            current = current,
            reply = reply,
            nowMillis = 400L
        )
        assertEquals(DshRuntimePhase.FAILED, resolved.phase)
        assertEquals(DshFailureCode.STOP_TIMEOUT, resolved.failure?.code)
    }

    @Test
    fun sameEpochNewerTimestampIsApplied() {
        val current = snapshot(DshRuntimePhase.READY, epoch = 3L, changedAt = 100L)
        val reply = snapshot(DshRuntimePhase.STOPPED, epoch = 3L, changedAt = 200L)
        val resolved = DshStopSnapshotPolicy.resolve(
            current = current,
            reply = reply,
            nowMillis = 400L
        )
        assertEquals(DshRuntimePhase.STOPPED, resolved.phase)
        assertEquals(3L, resolved.epoch)
    }
}
