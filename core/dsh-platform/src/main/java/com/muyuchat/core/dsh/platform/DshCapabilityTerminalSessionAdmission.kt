package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshMaintenanceAdmission
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceAdmissionGate
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceLeaseKind
import com.muyuchat.core.dsh.contract.capability.DshTerminalSessionAdmission
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Adapts the one shared maintenance admission gate to the terminal session seam.
 *
 * A PTY session holds a MUTATION lease for its whole lifetime, so an exclusive maintenance
 * barrier cannot start while a shell is open, and a new shell is refused while the barrier holds
 * the gate. The owner keeps no copy of the gate or its state: [acquire] returns a one-shot release
 * that the terminal host invokes exactly once when the session ends.
 */
class DshCapabilityTerminalSessionAdmission(
    private val gate: DshMaintenanceAdmissionGate,
    private val generation: () -> Long
) : DshTerminalSessionAdmission {

    override fun acquire(ownerKey: String, sessionId: String): (() -> Unit)? {
        val admission = gate.tryAcquire(
            DshMaintenanceLeaseKind.MUTATION,
            generation().coerceAtLeast(0L)
        )
        val lease = (admission as? DshMaintenanceAdmission.Admitted)?.lease ?: return null
        val released = AtomicBoolean(false)
        return {
            if (released.compareAndSet(false, true)) {
                gate.release(lease.leaseId, lease.generation)
            }
        }
    }
}
