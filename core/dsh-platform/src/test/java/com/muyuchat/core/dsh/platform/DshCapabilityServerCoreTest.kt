package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshCapabilityAuthority
import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import com.muyuchat.core.dsh.contract.capability.DshCapabilityOperationState
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRequestId
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteHandler
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshCapabilityServerCoreTest {

    private class Harness(
        capabilities: Set<DshCapability> = setOf(
            DshCapability.ANDROID_SYSTEM,
            DshCapability.PRIVILEGED_SHELL
        ),
        val ledger: DshCapabilityLedger = DshCapabilityLedger(),
        val throttle: DshCapabilityThrottle = DshCapabilityThrottle(
            maxRequestsPerWindow = 100,
            windowMillis = 60_000L,
            maxConcurrent = 8,
            cooldownMillis = 0L
        ),
        requestTimeoutMillis: Long = 5_000L,
        var nowMillis: Long = 1_000L
    ) {
        val authority = DshCapabilityAuthority()
        val grant = authority.startGeneration(7L, capabilities)
        val calls = AtomicInteger()
        var handler: suspend (DshCapabilityRouteRequest) -> DshCapabilityRouteResponse =
            { DshCapabilityRouteResponse.Json(200, "{\"ok\":true}") }

        val registry: DshCapabilityRouteRegistry = DshCapabilityRouteRegistry.builder()
            .post(
                "/notify",
                DshCapability.ANDROID_SYSTEM,
                DshCapabilityRouteHandler { request ->
                    calls.incrementAndGet()
                    handler(request)
                }
            )
            .get(
                "/usage",
                DshCapability.ANDROID_SYSTEM,
                DshCapabilityRouteHandler { request ->
                    calls.incrementAndGet()
                    handler(request)
                }
            )
            .post(
                "/shell",
                DshCapability.PRIVILEGED_SHELL,
                DshCapabilityRouteHandler { request ->
                    calls.incrementAndGet()
                    handler(request)
                }
            )
            .build()

        val core = DshCapabilityServerCore(
            authority = authority,
            registry = registry,
            ledger = ledger,
            throttle = throttle,
            requestTimeoutMillis = requestTimeoutMillis,
            clock = { nowMillis }
        )

        fun request(
            path: String = "/notify",
            method: String = "POST",
            token: String? = grant.token.value,
            generation: String? = null,
            requestId: String? = null,
            query: Map<String, String> = emptyMap(),
            body: String = ""
        ): ParsedDshCapabilityRequest {
            val headers = LinkedHashMap<String, String>()
            if (token != null) headers["x-dsh-token"] = token
            if (generation != null) headers["x-dsh-generation"] = generation
            if (requestId != null) headers["x-dsh-request-id"] = requestId
            return ParsedDshCapabilityRequest(method, path, query, body, headers)
        }
    }

    @Test
    fun authorizedRequestReachesTheHandler() = runBlocking {
        val harness = Harness()

        val result = harness.core.handle(harness.request(body = "{}"))

        assertEquals(200, result.statusCode)
        assertEquals("{\"ok\":true}", result.bodyJson)
        assertEquals(1, harness.calls.get())
    }

    @Test
    fun theOfficialShellBodyTokenIsAcceptedWithoutAHeader() = runBlocking {
        val harness = Harness()
        val body = "{\"command\":\"id -u\",\"timeout_ms\":1000,\"token\":\"${harness.grant.token.value}\"}"

        val result = harness.core.handle(
            harness.request(path = "/shell", token = null, body = body)
        )

        assertEquals(200, result.statusCode)
        assertEquals(1, harness.calls.get())
    }

    @Test
    fun aMalformedOrWrongShellBodyTokenIsRejected() = runBlocking {
        val harness = Harness()
        val malformed = "{\"command\":\"id -u\",\"token\":\"not-a-token\"}"
        val wrong = "{\"command\":\"id -u\",\"token\":\"dshc_${"B".repeat(43)}\"}"

        assertEquals(401, harness.core.handle(harness.request(path = "/shell", token = null, body = malformed)).statusCode)
        assertEquals(401, harness.core.handle(harness.request(path = "/shell", token = null, body = wrong)).statusCode)
        assertEquals(0, harness.calls.get())
    }

    @Test
    fun aBodyTokenCannotAuthenticateAnyOtherRoute() = runBlocking {
        val harness = Harness()
        val body = "{\"token\":\"${harness.grant.token.value}\"}"

        val result = harness.core.handle(harness.request(path = "/notify", token = null, body = body))

        assertEquals(401, result.statusCode)
        assertEquals(0, harness.calls.get())
    }

    @Test
    fun missingTokenIsRejectedBeforeAnyHandler() = runBlocking {
        val harness = Harness()

        val result = harness.core.handle(harness.request(token = null, body = "{}"))

        assertEquals(401, result.statusCode)
        assertTrue(result.bodyJson.contains("\"ok\":false"))
        assertTrue(result.bodyJson.contains("UNAUTHORIZED"))
        assertEquals(0, harness.calls.get())
    }

    @Test
    fun wrongTokenOfTheSameLengthIsRejected() = runBlocking {
        val harness = Harness()
        val wrong = "dshc_" + "B".repeat(43)

        val result = harness.core.handle(harness.request(token = wrong, body = "{}"))

        assertEquals(401, result.statusCode)
        assertTrue(result.bodyJson.contains("UNAUTHORIZED"))
        assertFalse(result.bodyJson.contains(wrong))
        assertEquals(0, harness.calls.get())
    }

    @Test
    fun staleDeclaredGenerationIsRejected() = runBlocking {
        val harness = Harness()

        val result = harness.core.handle(harness.request(generation = "6", body = "{}"))

        assertEquals(409, result.statusCode)
        assertTrue(result.bodyJson.contains("STALE_GENERATION"))
        assertEquals(0, harness.calls.get())
    }

    @Test
    fun malformedGenerationHeaderIsRejected() = runBlocking {
        val harness = Harness()

        val result = harness.core.handle(harness.request(generation = "not-a-number", body = "{}"))

        assertEquals(400, result.statusCode)
        assertTrue(result.bodyJson.contains("INVALID_REQUEST"))
    }

    @Test
    fun unusedCapabilityIsForbidden() = runBlocking {
        val harness = Harness(capabilities = setOf(DshCapability.NOTIFICATION))

        val result = harness.core.handle(harness.request(body = "{}"))

        assertEquals(403, result.statusCode)
        assertTrue(result.bodyJson.contains("CAPABILITY_UNAVAILABLE"))
        assertEquals(0, harness.calls.get())
    }

    @Test
    fun unknownPathIsNotFound() = runBlocking {
        val harness = Harness()

        val result = harness.core.handle(harness.request(path = "/clipboard", body = "{}"))

        assertEquals(404, result.statusCode)
        assertTrue(result.bodyJson.contains("\"status\":404"))
    }

    @Test
    fun wrongMethodIsRejectedWithAnAllowHeader() = runBlocking {
        val harness = Harness()

        val result = harness.core.handle(harness.request(method = "GET", body = ""))

        assertEquals(405, result.statusCode)
        assertEquals("POST", result.extraHeaders["Allow"])
        assertTrue(result.bodyJson.contains("INVALID_REQUEST"))
    }

    @Test
    fun duplicateTerminalRequestReplaysTheStoredReceipt() = runBlocking {
        val harness = Harness()
        harness.handler = { DshCapabilityRouteResponse.Json(201, "{\"receipt\":1}") }
        val requestId = requestId(1).value

        val first = harness.core.handle(harness.request(requestId = requestId, body = "{}"))
        val second = harness.core.handle(harness.request(requestId = requestId, body = "{}"))

        assertEquals(201, first.statusCode)
        assertEquals(201, second.statusCode)
        assertEquals("{\"receipt\":1}", second.bodyJson)
        assertEquals(1, harness.calls.get())
    }

    @Test
    fun duplicateWithDifferentPayloadIsRejected() = runBlocking {
        val harness = Harness()
        val requestId = requestId(1).value

        harness.core.handle(harness.request(requestId = requestId, body = "{\"a\":1}"))
        val second = harness.core.handle(harness.request(requestId = requestId, body = "{\"a\":2}"))

        assertEquals(409, second.statusCode)
        assertTrue(second.bodyJson.contains("INVALID_REQUEST"))
        assertEquals(1, harness.calls.get())
    }

    @Test
    fun duplicateWhilePendingIsRequestInProgress() = runBlocking {
        val harness = Harness()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        harness.handler = {
            entered.complete(Unit)
            release.await()
            DshCapabilityRouteResponse.Json(200, "{\"ok\":true}")
        }
        val requestId = requestId(1).value

        val first = async { harness.core.handle(harness.request(requestId = requestId, body = "{}")) }
        entered.await()
        val second = harness.core.handle(harness.request(requestId = requestId, body = "{}"))

        assertEquals(409, second.statusCode)
        assertTrue(second.bodyJson.contains("REQUEST_IN_PROGRESS"))
        release.complete(Unit)
        assertEquals(200, first.await().statusCode)
        assertEquals(1, harness.calls.get())
    }

    @Test
    fun unknownOutcomeIsNeverReExecuted() = runBlocking {
        val harness = Harness()
        val id = requestId(1)
        val fingerprint = capabilityPayloadFingerprint(
            DshCapabilityHttpMethod.POST,
            "/notify",
            emptyMap(),
            "{}"
        )
        harness.ledger.begin(id, "/notify", fingerprint, 7L, 1_000L)
        harness.ledger.markUnknown(id, fingerprint, 1_010L)

        val result = harness.core.handle(harness.request(requestId = id.value, body = "{}"))

        assertEquals(409, result.statusCode)
        assertTrue(result.bodyJson.contains("OUTCOME_UNKNOWN"))
        assertEquals(0, harness.calls.get())
    }

    @Test
    fun expiredReceiptIsNotReExecuted() = runBlocking {
        val harness = Harness(ledger = DshCapabilityLedger(maxRetainedBodyChars = 0))
        val requestId = requestId(1).value

        val first = harness.core.handle(harness.request(requestId = requestId, body = "{}"))
        val second = harness.core.handle(harness.request(requestId = requestId, body = "{}"))

        assertEquals(200, first.statusCode)
        assertEquals(409, second.statusCode)
        assertTrue(second.bodyJson.contains("OUTCOME_UNKNOWN"))
        assertEquals(1, harness.calls.get())
    }

    @Test
    fun rateLimitRejectsTheSecondRequest() = runBlocking {
        val throttle = DshCapabilityThrottle(
            maxRequestsPerWindow = 1,
            windowMillis = 60_000L,
            maxConcurrent = 8,
            cooldownMillis = 0L,
            clock = { 1_000L }
        )
        val harness = Harness(throttle = throttle)

        val first = harness.core.handle(harness.request(body = "{}"))
        val second = harness.core.handle(harness.request(body = "{}"))

        assertEquals(200, first.statusCode)
        assertEquals(429, second.statusCode)
        assertTrue(second.bodyJson.contains("RATE_LIMITED"))
        assertTrue(second.bodyJson.contains("retryAfterMs"))
        assertEquals(1, harness.calls.get())
    }

    @Test
    fun concurrencyLimitRejectsWhileOneCallIsInFlight() = runBlocking {
        val throttle = DshCapabilityThrottle(
            maxRequestsPerWindow = 100,
            windowMillis = 60_000L,
            maxConcurrent = 1,
            cooldownMillis = 0L
        )
        val harness = Harness(throttle = throttle)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        harness.handler = {
            entered.complete(Unit)
            release.await()
            DshCapabilityRouteResponse.Json(200, "{\"ok\":true}")
        }

        val first = async { harness.core.handle(harness.request(body = "{}")) }
        entered.await()
        val second = harness.core.handle(harness.request(body = "{}"))

        assertEquals(429, second.statusCode)
        release.complete(Unit)
        assertEquals(200, first.await().statusCode)
    }

    @Test
    fun handlerTimeoutSettlesUnknownAndBlocksRetry() = runBlocking {
        val harness = Harness(requestTimeoutMillis = 50L)
        harness.handler = {
            delay(500L)
            DshCapabilityRouteResponse.Json(200, "{\"ok\":true}")
        }
        val requestId = requestId(1).value

        val first = harness.core.handle(harness.request(requestId = requestId, body = "{}"))
        val second = harness.core.handle(harness.request(requestId = requestId, body = "{}"))

        assertEquals(504, first.statusCode)
        assertTrue(first.bodyJson.contains("TIMEOUT"))
        assertEquals(409, second.statusCode)
        assertTrue(second.bodyJson.contains("OUTCOME_UNKNOWN"))
        assertEquals(
            DshCapabilityOperationState.UNKNOWN,
            harness.ledger.lookup(requestId(1))?.state
        )
        assertEquals(1, harness.calls.get())
    }

    @Test
    fun handlerExceptionSettlesUnknownAndReturnsInternalError() = runBlocking {
        val harness = Harness()
        harness.handler = { throw IllegalStateException("boom") }
        val requestId = requestId(1).value

        val result = harness.core.handle(harness.request(requestId = requestId, body = "{}"))

        assertEquals(500, result.statusCode)
        assertTrue(result.bodyJson.contains("INTERNAL"))
        assertFalse(result.bodyJson.contains("boom"))
        assertEquals(
            DshCapabilityOperationState.UNKNOWN,
            harness.ledger.lookup(requestId(1))?.state
        )
    }

    @Test
    fun cancellationSettlesUnknownBeforePropagating() = runBlocking {
        val harness = Harness()
        val entered = CompletableDeferred<Unit>()
        harness.handler = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val requestId = requestId(1).value

        val job = launch { harness.core.handle(harness.request(requestId = requestId, body = "{}")) }
        entered.await()
        job.cancelAndJoin()

        assertEquals(
            DshCapabilityOperationState.UNKNOWN,
            harness.ledger.lookup(requestId(1))?.state
        )
    }

    @Test
    fun unroutedHandlerSettlesRejectedAndReplays404() = runBlocking {
        val harness = Harness()
        harness.handler = { DshCapabilityRouteResponse.Unrouted }
        val requestId = requestId(1).value

        val first = harness.core.handle(harness.request(requestId = requestId, body = "{}"))
        val second = harness.core.handle(harness.request(requestId = requestId, body = "{}"))

        assertEquals(404, first.statusCode)
        assertEquals(404, second.statusCode)
        assertEquals(first.bodyJson, second.bodyJson)
        assertEquals(
            DshCapabilityOperationState.REJECTED,
            harness.ledger.lookup(requestId(1))?.state
        )
        assertEquals(1, harness.calls.get())
    }

    @Test
    fun revokedAuthorityRejectsEveryRequest() = runBlocking {
        val harness = Harness()
        harness.authority.revoke()

        val result = harness.core.handle(harness.request(body = "{}"))

        assertEquals(409, result.statusCode)
        assertTrue(result.bodyJson.contains("STALE_GENERATION"))
        assertEquals(0, harness.calls.get())
    }

    @Test
    fun requestIdInTheQueryStringAlsoReplays() = runBlocking {
        val harness = Harness()
        val query = mapOf("requestId" to requestId(1).value)

        harness.core.handle(harness.request(query = query, body = "{}"))
        val replay = harness.core.handle(harness.request(query = query, body = "{}"))

        assertEquals(200, replay.statusCode)
        assertEquals(1, harness.calls.get())
    }

    @Test
    fun malformedRequestIdIsRejected() = runBlocking {
        val harness = Harness()

        val result = harness.core.handle(harness.request(requestId = "short", body = "{}"))

        assertEquals(400, result.statusCode)
        assertTrue(result.bodyJson.contains("INVALID_REQUEST"))
        assertEquals(0, harness.calls.get())
    }

    @Test
    fun requestWithoutAnIdExecutesEveryTime() = runBlocking {
        val harness = Harness()

        harness.core.handle(harness.request(body = "{}"))
        harness.core.handle(harness.request(body = "{}"))

        assertEquals(2, harness.calls.get())
    }

    @Test
    fun errorEnvelopeNeverCarriesThePresentedToken() = runBlocking {
        val harness = Harness()
        val wrong = "dshc_" + "Z".repeat(43)

        val result = harness.core.handle(harness.request(token = wrong, body = "{}"))

        assertFalse(result.bodyJson.contains(wrong))
        assertFalse(result.bodyJson.contains("Z".repeat(43)))
        assertTrue(result.bodyJson.contains("\"error\""))
    }

    @Test
    fun ledgerCapacityRefusalIsReportedAsRateLimited() = runBlocking {
        val harness = Harness(ledger = DshCapabilityLedger(capacity = 1, tombstoneCapacity = 1))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        harness.handler = {
            entered.complete(Unit)
            release.await()
            DshCapabilityRouteResponse.Json(200, "{\"ok\":true}")
        }

        val first = async {
            harness.core.handle(harness.request(requestId = requestId(1).value, body = "{}"))
        }
        entered.await()
        val second = harness.core.handle(harness.request(requestId = requestId(2).value, body = "{}"))

        assertEquals(429, second.statusCode)
        assertTrue(second.bodyJson.contains("RATE_LIMITED"))
        release.complete(Unit)
        assertEquals(200, first.await().statusCode)
        assertNotNull(harness.ledger.lookup(requestId(1)))
    }

    private fun requestId(number: Int): DshCapabilityRequestId =
        DshCapabilityRequestId("request-id-${number.toString().padStart(8, '0')}")
}
