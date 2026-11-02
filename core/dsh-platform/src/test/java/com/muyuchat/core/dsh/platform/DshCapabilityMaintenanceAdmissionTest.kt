package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshMaintenanceAdmission
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceLeaseKind
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceRefusal
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshCapabilityMaintenanceAdmissionTest {

    private class FakeClock(var nowMillis: Long = 1_000L) {
        operator fun invoke(): Long = nowMillis
    }

    private fun gate(
        clock: FakeClock = FakeClock(),
        ttlMillis: Long = 10_000L,
        maxMutationLeases: Int = 4,
        maxReadLeases: Int = 4
    ): DshCapabilityMaintenanceAdmissionGate {
        var counter = 0
        return DshCapabilityMaintenanceAdmissionGate(
            leaseTtlMillis = ttlMillis,
            maxMutationLeases = maxMutationLeases,
            maxReadLeases = maxReadLeases,
            leaseIdFactory = { "lease-0000000${counter++}" },
            clock = clock::invoke
        )
    }

    private fun admitted(admission: DshMaintenanceAdmission): com.muyuchat.core.dsh.contract.capability.DshMaintenanceLease {
        assertTrue("expected admission, got $admission", admission is DshMaintenanceAdmission.Admitted)
        return (admission as DshMaintenanceAdmission.Admitted).lease
    }

    @Test
    fun barrierIsAdmittedWhenNothingElseIsActive() {
        val clock = FakeClock()
        val lease = admitted(gate(clock).tryAcquire(DshMaintenanceLeaseKind.MAINTENANCE, 7L))

        assertEquals(DshMaintenanceLeaseKind.MAINTENANCE, lease.kind)
        assertEquals(7L, lease.generation)
        assertEquals(11_000L, lease.deadlineAtMillis)
    }

    @Test
    fun secondBarrierIsRefusedWhileOneIsActive() {
        val clock = FakeClock()
        val gate = gate(clock)
        admitted(gate.tryAcquire(DshMaintenanceLeaseKind.MAINTENANCE, 7L))

        val refused = gate.tryAcquire(DshMaintenanceLeaseKind.MAINTENANCE, 7L) as DshMaintenanceAdmission.Refused

        assertEquals(DshMaintenanceRefusal.MAINTENANCE_ACTIVE, refused.reason)
    }

    @Test
    fun mutatorIsRefusedWhileTheBarrierIsActive() {
        val clock = FakeClock()
        val gate = gate(clock)
        admitted(gate.tryAcquire(DshMaintenanceLeaseKind.MAINTENANCE, 7L))

        val refused = gate.tryAcquire(DshMaintenanceLeaseKind.MUTATION, 7L) as DshMaintenanceAdmission.Refused

        assertEquals(DshMaintenanceRefusal.MAINTENANCE_ACTIVE, refused.reason)
    }

    @Test
    fun readerIsRefusedWhileTheBarrierIsActive() {
        val clock = FakeClock()
        val gate = gate(clock)
        admitted(gate.tryAcquire(DshMaintenanceLeaseKind.MAINTENANCE, 7L))

        val refused = gate.tryAcquire(DshMaintenanceLeaseKind.READ, 7L) as DshMaintenanceAdmission.Refused

        assertEquals(DshMaintenanceRefusal.MAINTENANCE_ACTIVE, refused.reason)
    }

    @Test
    fun barrierIsRefusedWhileAMutatorHoldsALease() {
        val clock = FakeClock()
        val gate = gate(clock)
        admitted(gate.tryAcquire(DshMaintenanceLeaseKind.MUTATION, 7L))

        val refused = gate.tryAcquire(DshMaintenanceLeaseKind.MAINTENANCE, 7L) as DshMaintenanceAdmission.Refused

        assertEquals(DshMaintenanceRefusal.MUTATORS_ACTIVE, refused.reason)
    }

    @Test
    fun barrierIsRefusedWhileAReaderHoldsALease() {
        val clock = FakeClock()
        val gate = gate(clock)
        admitted(gate.tryAcquire(DshMaintenanceLeaseKind.READ, 7L))

        val refused = gate.tryAcquire(DshMaintenanceLeaseKind.MAINTENANCE, 7L) as DshMaintenanceAdmission.Refused

        assertEquals(DshMaintenanceRefusal.READERS_ACTIVE, refused.reason)
    }

    @Test
    fun releasingTheLastMutatorAdmitsTheBarrier() {
        val clock = FakeClock()
        val gate = gate(clock)
        val mutator = admitted(gate.tryAcquire(DshMaintenanceLeaseKind.MUTATION, 7L))

        val released = gate.release(mutator.leaseId, 7L)

        assertTrue(released is DshMaintenanceRelease.Released)
        admitted(gate.tryAcquire(DshMaintenanceLeaseKind.MAINTENANCE, 7L))
    }

    @Test
    fun releaseIsIdempotentWithinTheReleaseWindow() {
        val clock = FakeClock()
        val gate = gate(clock)
        val mutator = admitted(gate.tryAcquire(DshMaintenanceLeaseKind.MUTATION, 7L))

        assertTrue(gate.release(mutator.leaseId, 7L) is DshMaintenanceRelease.Released)
        assertEquals(
            DshMaintenanceRelease.AlreadyReleased(mutator.leaseId),
            gate.release(mutator.leaseId, 7L)
        )
    }

    @Test
    fun releasingAnUnknownLeaseIsTyped() {
        val gate = gate()

        assertEquals(
            DshMaintenanceRelease.UnknownLease("lease-unknown-1"),
            gate.release("lease-unknown-1", 7L)
        )
    }

    @Test
    fun aStaleGenerationCannotReleaseANewerLease() {
        val clock = FakeClock()
        val gate = gate(clock)
        val mutator = admitted(gate.tryAcquire(DshMaintenanceLeaseKind.MUTATION, 7L))

        val mismatch = gate.release(mutator.leaseId, 8L)

        assertTrue(mismatch is DshMaintenanceRelease.GenerationMismatch)
        assertEquals(1, gate.snapshot(clock.nowMillis).mutationLeaseCount)
        assertTrue(gate.release(mutator.leaseId, 7L) is DshMaintenanceRelease.Released)
    }

    @Test
    fun expiredLeaseIsReclaimedBeforeTheNextDecision() {
        val clock = FakeClock()
        val gate = gate(clock, ttlMillis = 1_000L)
        admitted(gate.tryAcquire(DshMaintenanceLeaseKind.MUTATION, 7L))

        clock.nowMillis += 1_001L

        val snapshot = gate.snapshot(clock.nowMillis)
        assertEquals(0, snapshot.mutationLeaseCount)
        assertTrue(snapshot.barrierAcquirable)
        admitted(gate.tryAcquire(DshMaintenanceLeaseKind.MAINTENANCE, 7L))
    }

    @Test
    fun mutationLeaseCapacityIsEnforced() {
        val clock = FakeClock()
        val gate = gate(clock, maxMutationLeases = 2)
        admitted(gate.tryAcquire(DshMaintenanceLeaseKind.MUTATION, 7L))
        admitted(gate.tryAcquire(DshMaintenanceLeaseKind.MUTATION, 7L))

        val refused = gate.tryAcquire(DshMaintenanceLeaseKind.MUTATION, 7L) as DshMaintenanceAdmission.Refused

        assertEquals(DshMaintenanceRefusal.CAPACITY_EXCEEDED, refused.reason)
    }

    @Test
    fun snapshotProjectsLiveLeaseCounts() {
        val clock = FakeClock()
        val gate = gate(clock)
        admitted(gate.tryAcquire(DshMaintenanceLeaseKind.MUTATION, 7L))
        admitted(gate.tryAcquire(DshMaintenanceLeaseKind.MUTATION, 7L))
        admitted(gate.tryAcquire(DshMaintenanceLeaseKind.READ, 7L))

        val snapshot = gate.snapshot(clock.nowMillis)

        assertFalse(snapshot.barrierActive)
        assertEquals(2, snapshot.mutationLeaseCount)
        assertEquals(1, snapshot.readLeaseCount)
        assertFalse(snapshot.barrierAcquirable)
    }
}
