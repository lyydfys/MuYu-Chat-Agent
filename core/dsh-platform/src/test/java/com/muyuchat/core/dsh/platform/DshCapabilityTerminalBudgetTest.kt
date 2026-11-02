package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshCapabilityAuthority
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteHandler
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A terminal session polls `/terminal/read` every 40 ms, so the general anti-abuse budget would
 * reject it (device: `MCA terminal bridge read failed: HTTP 429`). The terminal family therefore
 * has its own bounded budget; every other path must still be limited by the general one.
 */
class DshCapabilityTerminalBudgetTest {

    private val authority = DshCapabilityAuthority()
    private val grant = authority.startGeneration(
        3L,
        setOf(DshCapability.ANDROID_SYSTEM, DshCapability.WORKSPACE_TERMINAL)
    )
    private val general = DshCapabilityThrottle(
        maxRequestsPerWindow = 2,
        windowMillis = 60_000L,
        maxConcurrent = 4,
        cooldownMillis = 0L
    )
    private val terminal = DshCapabilityThrottle(
        maxRequestsPerWindow = 100,
        windowMillis = 60_000L,
        maxConcurrent = 2,
        cooldownMillis = 0L
    )
    private val registry = DshCapabilityRouteRegistry.builder()
        .post("/terminal/read", DshCapability.WORKSPACE_TERMINAL, DshCapabilityRouteHandler {
            DshCapabilityRouteResponse.Json(200, "{\"ok\":true}")
        })
        .post("/notify", DshCapability.ANDROID_SYSTEM, DshCapabilityRouteHandler {
            DshCapabilityRouteResponse.Json(200, "{\"ok\":true}")
        })
        .build()
    private val core = DshCapabilityServerCore(
        authority = authority,
        registry = registry,
        ledger = DshCapabilityLedger(),
        throttle = general,
        budgetFor = { path -> if (path.startsWith("/terminal/")) terminal else general }
    )

    @Test
    fun terminalPollingDoesNotConsumeTheGeneralBudget() = runBlocking {
        repeat(5) {
            assertEquals(200, call("/terminal/read").statusCode)
        }
        // The five terminal reads never touched the two-per-window general budget.
        assertEquals(200, call("/notify").statusCode)
        assertEquals(200, call("/notify").statusCode)
        assertEquals(429, call("/notify").statusCode)
    }

    @Test
    fun theTerminalBudgetIsStillBounded() = runBlocking {
        repeat(100) { assertEquals(200, call("/terminal/read").statusCode) }

        val refused = call("/terminal/read")

        assertEquals(429, refused.statusCode)
    }

    private suspend fun call(path: String): DshCapabilityHttpResult {
        val headers = linkedMapOf("x-dsh-token" to grant.token.value)
        return core.handle(ParsedDshCapabilityRequest("POST", path, emptyMap(), "{}", headers))
    }
}
