package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshMaintenanceAdmission
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceLeaseKind
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The terminal session seam maps the shared admission gate 1:1 and holds no state of its own. */
class DshCapabilityTerminalSessionAdmissionTest {

    @Test
    fun anAdmittedSessionGetsAOneShotRelease() {
        val gate = DshCapabilityMaintenanceAdmissionGate()
        val admission = DshCapabilityTerminalSessionAdmission(gate) { 7L }

        val release = admission.acquire("dsh", "term-1")

        assertNotNull(release)
        // While the session is live the exclusive barrier is refused.
        assertTrue(gate.tryAcquire(DshMaintenanceLeaseKind.MAINTENANCE, 7L) is DshMaintenanceAdmission.Refused)
        release!!.invoke()
        // Released exactly once; the barrier can now be acquired.
        assertTrue(gate.tryAcquire(DshMaintenanceLeaseKind.MAINTENANCE, 7L) is DshMaintenanceAdmission.Admitted)
        release.invoke()
    }

    @Test
    fun aHeldBarrierRefusesTheSession() {
        val gate = DshCapabilityMaintenanceAdmissionGate()
        val barrier = gate.tryAcquire(DshMaintenanceLeaseKind.MAINTENANCE, 7L)
        assertTrue(barrier is DshMaintenanceAdmission.Admitted)
        val admission = DshCapabilityTerminalSessionAdmission(gate) { 7L }

        assertNull(admission.acquire("dsh", "term-1"))
    }
}
