package com.muyuchat.core.dsh.runtime.state

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshRuntimeStateMachineTest {

    private var now = 1_000L
    private val machine = DshRuntimeStateMachine { now++ }

    @Test
    fun recreatedMachineContinuesFromPersistedEpoch() {
        val resumed = DshRuntimeStateMachine(initialEpoch = 6L, clock = { now++ })
        assertEquals(7L, resumed.beginStart()!!.epoch)
        val fresh = DshRuntimeStateMachine(initialEpoch = 0L, clock = { now++ })
        assertEquals(1L, fresh.beginStart()!!.epoch)
    }

    @Test
    fun lifecycleAllocatesOneEpochPerGeneration() {
        assertEquals(DshRuntimePhase.STOPPED, machine.snapshot().phase)
        val starting = machine.beginStart()
        assertNotNull(starting)
        assertEquals(1L, starting!!.epoch)
        assertEquals(DshRuntimePhase.STARTING, starting.phase)
        val ready = machine.markReady("http://127.0.0.1:1234")
        assertEquals(1L, ready.epoch)
        assertEquals(DshRuntimePhase.READY, ready.phase)
        assertTrue(ready.isReady)
        assertNull(machine.beginStart()) // no second process while READY
        machine.beginStop()
        assertEquals(DshRuntimePhase.STOPPING, machine.snapshot().phase)
        val stopped = machine.markStopped()
        assertEquals(DshRuntimePhase.STOPPED, stopped.phase)
        assertEquals(1L, stopped.epoch)
        assertEquals(2L, machine.beginStart()!!.epoch)
    }

    @Test
    fun failurePublishesConcreteCodeAndAllowsRestartWithNewEpoch() {
        machine.beginStart()
        val failed = machine.fail(DshFailureCode.PAYLOAD_MISSING, "payload absent", retryable = false)
        assertEquals(DshRuntimePhase.FAILED, failed.phase)
        assertEquals(DshFailureCode.PAYLOAD_MISSING, failed.failure?.code)
        assertFalse(failed.failure!!.retryable)
        assertNull(failed.origin)
        assertTrue(machine.canStart())
        assertEquals(2L, machine.beginStart()!!.epoch)
    }

    @Test
    fun stopTimeoutRaisesFenceAndBlocksNewStartUntilConfirmedGone() {
        machine.beginStart()
        machine.markReady("http://127.0.0.1:1234")
        machine.beginStop()
        val fenced = machine.markStopTimeout("process tree survived SIGKILL")
        assertEquals(DshFailureCode.STOP_TIMEOUT, fenced.failure?.code)
        assertFalse(fenced.failure!!.retryable)
        assertTrue(machine.isStopFenceRaised())
        assertNull(machine.beginStart())
        val cleared = machine.confirmExitedAfterFence()
        assertEquals(DshRuntimePhase.STOPPED, cleared.phase)
        assertFalse(machine.isStopFenceRaised())
        assertEquals(2L, machine.beginStart()!!.epoch)
    }

    @Test
    fun failureAfterStopIsNoOp() {
        machine.beginStart()
        machine.beginStop()
        val snapshot = machine.fail(DshFailureCode.INTERNAL, "late failure")
        assertEquals(DshRuntimePhase.STOPPING, snapshot.phase)
        assertNull(snapshot.failure)
    }

    @Test
    fun reloadReplacesAReadyGenerationWithANewEpochWithoutPublishingStopped() {
        machine.beginStart()
        machine.markReady("http://127.0.0.1:1234")
        // The plain start path still refuses a second process while READY.
        assertNull(machine.beginStart())
        val reload = machine.beginReload()
        assertNotNull(reload)
        assertEquals(2L, reload!!.epoch)
        assertEquals(DshRuntimePhase.STARTING, reload.phase)
        assertNull(reload.origin)
        assertNull(reload.failure)
        val ready = machine.markReady("http://127.0.0.1:1235")
        assertEquals(DshRuntimePhase.READY, ready.phase)
        assertEquals(2L, ready.epoch)
        assertEquals("http://127.0.0.1:1235", ready.origin)
    }

    @Test
    fun reloadCannotBypassAStopFence() {
        machine.beginStart()
        machine.markReady("http://127.0.0.1:1234")
        machine.beginStop()
        machine.markStopTimeout("tree survived SIGKILL")
        assertNull(machine.beginReload())
        assertTrue(machine.isStopFenceRaised())
        machine.confirmExitedAfterFence()
        assertEquals(DshRuntimePhase.STOPPED, machine.snapshot().phase)
    }
}
