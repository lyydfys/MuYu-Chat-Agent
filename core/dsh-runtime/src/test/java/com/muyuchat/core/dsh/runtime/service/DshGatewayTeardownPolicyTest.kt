package com.muyuchat.core.dsh.runtime.service

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshRuntimeFailure
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D01 stop-fact rule: the gateway is only released on a confirmed STOPPED snapshot.
 *
 * These assertions pin the fail-closed behaviour the composition relies on; they do not prove a
 * real Binder/Node stop, which stays NOT_RUN without a device.
 */
class DshGatewayTeardownPolicyTest {

    private fun snapshot(phase: DshRuntimePhase, failure: DshRuntimeFailure? = null) =
        DshRuntimeSnapshot(phase = phase, epoch = 3L, failure = failure, changedAtMillis = 100L)

    @Test
    fun `confirmed stop releases the gateway`() {
        assertTrue(DshGatewayTeardownPolicy.shouldStopAfterStop(snapshot(DshRuntimePhase.STOPPED)))
    }

    @Test
    fun `unknown stop keeps the gateway so a live host is not cut off`() {
        val unknown = snapshot(
            DshRuntimePhase.FAILED,
            DshRuntimeFailure(DshFailureCode.STOP_TIMEOUT, "stop outcome unknown", retryable = true)
        )
        assertFalse(DshGatewayTeardownPolicy.shouldStopAfterStop(unknown))
        assertFalse(DshGatewayTeardownPolicy.shouldStopAfterStop(snapshot(DshRuntimePhase.STOPPING)))
        assertFalse(DshGatewayTeardownPolicy.shouldStopAfterStop(snapshot(DshRuntimePhase.READY)))
        assertFalse(DshGatewayTeardownPolicy.shouldStopAfterStop(snapshot(DshRuntimePhase.STARTING)))
    }

    @Test
    fun `failed start attempt releases the half-created generation`() {
        val failed = snapshot(
            DshRuntimePhase.FAILED,
            DshRuntimeFailure(DshFailureCode.NODE_START_FAILED, "node exited", retryable = true)
        )
        assertTrue(DshGatewayTeardownPolicy.shouldStopAfterStartAttempt(failed))
        assertTrue(DshGatewayTeardownPolicy.shouldStopAfterStartAttempt(snapshot(DshRuntimePhase.STOPPED)))
    }

    @Test
    fun `a ready or in-flight start keeps the gateway`() {
        assertFalse(DshGatewayTeardownPolicy.shouldStopAfterStartAttempt(snapshot(DshRuntimePhase.READY)))
        assertFalse(DshGatewayTeardownPolicy.shouldStopAfterStartAttempt(snapshot(DshRuntimePhase.STARTING)))
        assertFalse(DshGatewayTeardownPolicy.shouldStopAfterStartAttempt(snapshot(DshRuntimePhase.STOPPING)))
    }

    @Test
    fun `a new generation rotates credentials unless the host is ready or starting`() {
        assertTrue(DshGatewayTeardownPolicy.shouldRotateBeforeStart(snapshot(DshRuntimePhase.STOPPED)))
        assertTrue(DshGatewayTeardownPolicy.shouldRotateBeforeStart(snapshot(DshRuntimePhase.STOPPING)))
        assertTrue(
            DshGatewayTeardownPolicy.shouldRotateBeforeStart(
                snapshot(
                    DshRuntimePhase.FAILED,
                    DshRuntimeFailure(DshFailureCode.STOP_TIMEOUT, "tree did not settle", retryable = true)
                )
            )
        )
        assertFalse(DshGatewayTeardownPolicy.shouldRotateBeforeStart(snapshot(DshRuntimePhase.READY)))
        assertFalse(DshGatewayTeardownPolicy.shouldRotateBeforeStart(snapshot(DshRuntimePhase.STARTING)))
    }

    @Test
    fun `an unproven stop keeps the gateway even after a failed apply`() {
        // A binding reload can fail after it stopped the previous generation and could not prove
        // the tree gone. A surviving host may still be calling the gateway generation, so the
        // failure reply must not authorise closing it (same rule as an unknown stop).
        val fenced = snapshot(
            DshRuntimePhase.FAILED,
            DshRuntimeFailure(DshFailureCode.STOP_TIMEOUT, "tree did not settle", retryable = false)
        )
        assertFalse(DshGatewayTeardownPolicy.shouldStopAfterStartAttempt(fenced))
        // Every other failure still releases the half-created generation.
        assertTrue(
            DshGatewayTeardownPolicy.shouldStopAfterStartAttempt(
                snapshot(
                    DshRuntimePhase.FAILED,
                    DshRuntimeFailure(DshFailureCode.GATEWAY_UNAVAILABLE, "seed refused", retryable = true)
                )
            )
        )
    }
}
