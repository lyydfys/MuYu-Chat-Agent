package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot

/**
 * Which service-side snapshots the admission-refusal overlay publishes while a typed refusal is
 * showing.
 *
 * A refusal is produced by [DshModeAssembly]'s `refuseBeforeStart` before the service is asked to
 * start, so the service never sees that attempt. What it does still emit is the *previous*
 * generation's terminal state (`PROCESS_EXITED` after the host was stopped, or the initial
 * `STOPPED`). Publishing that would replace the actionable refusal with "the runtime exited", which
 * is exactly the confusing state the overlay exists to prevent. A real new attempt (`STARTING` or
 * `READY`) ends the refusal's lifetime.
 */
internal object DshAdmissionRefusalOverlayPolicy {

    fun clearsRefusal(incoming: DshRuntimePhase): Boolean =
        incoming == DshRuntimePhase.STARTING || incoming == DshRuntimePhase.READY

    fun suppresses(active: Boolean, incoming: DshRuntimeSnapshot): Boolean =
        active && (
            (incoming.phase == DshRuntimePhase.FAILED &&
                incoming.failure?.code == DshFailureCode.PROCESS_EXITED) ||
                incoming.phase == DshRuntimePhase.STOPPED
            )
}
