package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.contract.DshRuntimePhase

/**
 * Whether the console may treat the host as quiescent for maintenance writes.
 *
 * `FAILED` is quiescent in the same sense as `STOPPED`: the generation is not serving (it either
 * never started or exited), so no page can be using the home/workspace trees. Without this the
 * maintenance actions — including the recovery rescue that exists for an interrupted trial —
 * are refused on exactly the screen that needs them.
 */
internal object DshMaintenanceQuiescencePolicy {

    fun consoleHostQuiescent(phase: DshRuntimePhase): Boolean =
        phase == DshRuntimePhase.STOPPED || phase == DshRuntimePhase.FAILED
}
