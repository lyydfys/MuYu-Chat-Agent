package com.muyuchat.core.dsh.runtime.state

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshRuntimeFailure
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DshDisconnectFailurePolicyTest {

    @Test
    fun aReadySnapshotBecomesAProcessExitAtTheSameEpoch() {
        val ready = DshRuntimeSnapshot(
            phase = DshRuntimePhase.READY,
            epoch = 12,
            origin = "http://127.0.0.1:4567",
            changedAtMillis = 1
        )

        val resolved = DshDisconnectFailurePolicy.resolve(ready, "socket lost", nowMillis = 99)

        assertEquals(DshRuntimePhase.FAILED, resolved.phase)
        assertEquals(12L, resolved.epoch)
        assertNull(resolved.origin)
        assertEquals(DshFailureCode.PROCESS_EXITED, resolved.failure?.code)
        assertEquals("socket lost", resolved.failure?.message)
        assertTrue(resolved.failure!!.retryable)
        assertEquals(99L, resolved.changedAtMillis)
    }

    @Test
    fun aRefusedClosurePayloadKeepsItsOwnFailureInsteadOfBecomingACrash() {
        val refused = DshRuntimeSnapshot(
            phase = DshRuntimePhase.FAILED,
            epoch = 13,
            failure = DshRuntimeFailure(
                code = DshFailureCode.PAYLOAD_INTEGRITY,
                message = "the committed closure payload failed its integrity admission",
                retryable = false,
                diagnostics = "critical file sha256 mismatch"
            ),
            changedAtMillis = 5
        )

        val resolved = DshDisconnectFailurePolicy.resolve(refused, "process exited", nowMillis = 99)

        assertSame(refused, resolved)
        assertEquals(DshFailureCode.PAYLOAD_INTEGRITY, resolved.failure?.code)
        assertFalse(resolved.failure!!.retryable)
    }

    @Test
    fun aStartupRecoveryRefusalAlsoSurvivesTheDisconnect() {
        val refused = DshRuntimeSnapshot(
            phase = DshRuntimePhase.FAILED,
            failure = DshRuntimeFailure(
                code = DshFailureCode.STARTUP_RECOVERY_REQUIRED,
                message = "an interrupted trial still owns the host slot",
                retryable = true
            )
        )

        assertSame(refused, DshDisconnectFailurePolicy.resolve(refused, "binding died"))
    }

    @Test
    fun aStoppedSnapshotStillBecomesAProcessExit() {
        val stopped = DshRuntimeSnapshot(phase = DshRuntimePhase.STOPPED, epoch = 4)

        val resolved = DshDisconnectFailurePolicy.resolve(stopped, "binding died", nowMillis = 7)

        assertEquals(DshRuntimePhase.FAILED, resolved.phase)
        assertEquals(DshFailureCode.PROCESS_EXITED, resolved.failure?.code)
        assertEquals(4L, resolved.epoch)
    }

    @Test
    fun anExistingProcessExitKeepsTheFreshDisconnectMessage() {
        val previous = DshRuntimeSnapshot(
            phase = DshRuntimePhase.FAILED,
            epoch = 8,
            failure = DshRuntimeFailure(
                code = DshFailureCode.PROCESS_EXITED,
                message = "child exited",
                retryable = true
            )
        )

        val resolved = DshDisconnectFailurePolicy.resolve(previous, "binding died", nowMillis = 11)

        assertEquals("binding died", resolved.failure?.message)
        assertEquals(11L, resolved.changedAtMillis)
    }
}
