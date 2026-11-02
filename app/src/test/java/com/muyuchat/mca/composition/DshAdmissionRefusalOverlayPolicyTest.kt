package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshRuntimeFailure
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshAdmissionRefusalOverlayPolicyTest {

    @Test
    fun `an active refusal suppresses the previous generation terminal states`() {
        assertTrue(
            DshAdmissionRefusalOverlayPolicy.suppresses(
                active = true,
                incoming = DshRuntimeSnapshot(
                    phase = DshRuntimePhase.FAILED,
                    failure = DshRuntimeFailure(
                        code = DshFailureCode.PROCESS_EXITED,
                        message = "the host process exited"
                    )
                )
            )
        )
        assertTrue(
            DshAdmissionRefusalOverlayPolicy.suppresses(
                active = true,
                incoming = DshRuntimeSnapshot(phase = DshRuntimePhase.STOPPED)
            )
        )
    }

    @Test
    fun `an active refusal still publishes every other state`() {
        val others = listOf(
            DshRuntimeSnapshot(phase = DshRuntimePhase.STARTING),
            DshRuntimeSnapshot(phase = DshRuntimePhase.READY, origin = "http://127.0.0.1:1"),
            DshRuntimeSnapshot(
                phase = DshRuntimePhase.FAILED,
                failure = DshRuntimeFailure(code = DshFailureCode.STARTUP_RECOVERY_REQUIRED, message = "x")
            )
        )
        for (snapshot in others) {
            assertFalse(
                "must publish ${snapshot.phase}",
                DshAdmissionRefusalOverlayPolicy.suppresses(active = true, incoming = snapshot)
            )
        }
    }

    @Test
    fun `without an active refusal nothing is suppressed`() {
        val terminal = listOf(
            DshRuntimeSnapshot(phase = DshRuntimePhase.STOPPED),
            DshRuntimeSnapshot(
                phase = DshRuntimePhase.FAILED,
                failure = DshRuntimeFailure(code = DshFailureCode.PROCESS_EXITED, message = "x")
            )
        )
        for (snapshot in terminal) {
            assertFalse(DshAdmissionRefusalOverlayPolicy.suppresses(active = false, incoming = snapshot))
        }
    }

    @Test
    fun `only a real new attempt clears the refusal`() {
        assertTrue(DshAdmissionRefusalOverlayPolicy.clearsRefusal(DshRuntimePhase.STARTING))
        assertTrue(DshAdmissionRefusalOverlayPolicy.clearsRefusal(DshRuntimePhase.READY))
        val rest = DshRuntimePhase.entries.filter {
            it != DshRuntimePhase.STARTING && it != DshRuntimePhase.READY
        }
        for (phase in rest) {
            assertFalse("must keep refusal for $phase", DshAdmissionRefusalOverlayPolicy.clearsRefusal(phase))
        }
    }
}
