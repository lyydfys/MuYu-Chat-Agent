package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.contract.DshRuntimePhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshMaintenanceQuiescencePolicyTest {

    @Test
    fun stoppedAndFailedAreQuiescent() {
        assertTrue(DshMaintenanceQuiescencePolicy.consoleHostQuiescent(DshRuntimePhase.STOPPED))
        // A refused startup is exactly the screen whose recovery rescue must stay reachable.
        assertTrue(DshMaintenanceQuiescencePolicy.consoleHostQuiescent(DshRuntimePhase.FAILED))
    }

    @Test
    fun livePhasesAreNotQuiescent() {
        assertFalse(DshMaintenanceQuiescencePolicy.consoleHostQuiescent(DshRuntimePhase.STARTING))
        assertFalse(DshMaintenanceQuiescencePolicy.consoleHostQuiescent(DshRuntimePhase.READY))
        assertFalse(DshMaintenanceQuiescencePolicy.consoleHostQuiescent(DshRuntimePhase.STOPPING))
    }
}
