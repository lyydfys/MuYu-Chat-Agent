package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshCapabilityAuthority
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceAdmission
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceLeaseKind
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capability bridge and the maintenance owner share one admission gate: a tool call runs under
 * a MUTATION lease, so an exclusive maintenance barrier can never overlap a live call, and a call
 * is refused (typed 503) while the barrier is held.
 */
class DshCapabilityMaintenanceLeaseTest {

    private class Harness(
        private val handlerDelayMillis: Long = 0L
    ) {
        val authority = DshCapabilityAuthority()
        val grant = authority.startGeneration(7L, setOf(DshCapability.ANDROID_SYSTEM))
        val calls = AtomicInteger()
        val gate = DshCapabilityMaintenanceAdmissionGate()

        private val registry = DshCapabilityRouteRegistry.builder()
            .post(
                "/notify",
                DshCapability.ANDROID_SYSTEM
            ) { _: DshCapabilityRouteRequest ->
                calls.incrementAndGet()
                if (handlerDelayMillis > 0L) delay(handlerDelayMillis)
                DshCapabilityRouteResponse.Json(200, "{\"ok\":true}")
            }
            .build()

        val core = DshCapabilityServerCore(
            authority = authority,
            registry = registry,
            ledger = DshCapabilityLedger(),
            throttle = DshCapabilityThrottle(
                maxRequestsPerWindow = 100,
                windowMillis = 60_000L,
                maxConcurrent = 8,
                cooldownMillis = 0L
            ),
            requestTimeoutMillis = 5_000L,
            clock = { 1_000L },
            maintenance = gate
        )

        fun request(): ParsedDshCapabilityRequest {
            val headers = LinkedHashMap<String, String>()
            headers["x-dsh-token"] = grant.token.value
            return ParsedDshCapabilityRequest("POST", "/notify", emptyMap(), "{}", headers)
        }
    }

    @Test
    fun aToolCallReleasesItsMutableLeaseAfterItFinishes() = runBlocking {
        val harness = Harness()

        val result = harness.core.handle(harness.request())

        assertEquals(200, result.statusCode)
        assertEquals(1, harness.calls.get())
        // The lease is gone, so the exclusive barrier can be acquired immediately afterwards.
        val barrier = harness.gate.tryAcquire(DshMaintenanceLeaseKind.MAINTENANCE, 7L)
        assertTrue(barrier is DshMaintenanceAdmission.Admitted)
    }

    @Test
    fun aHeldMaintenanceBarrierRefusesToolCallsWithATypedUnavailable() = runBlocking {
        val harness = Harness()
        val barrier = harness.gate.tryAcquire(DshMaintenanceLeaseKind.MAINTENANCE, 7L)
        assertTrue(barrier is DshMaintenanceAdmission.Admitted)

        val refused = harness.core.handle(harness.request())

        assertEquals(503, refused.statusCode)
        assertTrue(refused.bodyJson.contains("CAPABILITY_UNAVAILABLE"))
        assertEquals(0, harness.calls.get())

        val admitted = barrier as DshMaintenanceAdmission.Admitted
        harness.gate.release(admitted.lease.leaseId, admitted.lease.generation)
        assertEquals(200, harness.core.handle(harness.request()).statusCode)
        assertEquals(1, harness.calls.get())
    }

    @Test
    fun anInFlightToolCallBlocksTheExclusiveBarrier() = runBlocking {
        val harness = Harness(handlerDelayMillis = 300L)
        val call = launch(Dispatchers.Default) { harness.core.handle(harness.request()) }
        // Wait until the handler has started and is holding its lease.
        while (harness.calls.get() == 0) delay(10)

        val barrier = harness.gate.tryAcquire(DshMaintenanceLeaseKind.MAINTENANCE, 7L)

        assertTrue(barrier is DshMaintenanceAdmission.Refused)
        call.join()
    }
}
