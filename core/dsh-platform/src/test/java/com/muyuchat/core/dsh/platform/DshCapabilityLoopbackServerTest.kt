package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshCapabilityAuthority
import com.muyuchat.core.dsh.contract.capability.DshCapabilityOperationState
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRequestId
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteHandler
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshCapabilityLoopbackServerTest {

    private class Harness(
        withGeneration: Boolean = true,
        requestTimeoutMillis: Long = 5_000L,
        drainTimeoutMillis: Long = 2_000L
    ) {
        val authority = DshCapabilityAuthority()
        val grant = if (withGeneration) {
            authority.startGeneration(7L, setOf(DshCapability.ANDROID_SYSTEM))
        } else {
            null
        }
        val calls = AtomicInteger()
        val logs = mutableListOf<String>()
        val ledger = DshCapabilityLedger()
        var handler: suspend (DshCapabilityRouteRequest) -> DshCapabilityRouteResponse =
            { DshCapabilityRouteResponse.Json(200, "{\"ok\":true}") }

        private val registry: DshCapabilityRouteRegistry = DshCapabilityRouteRegistry.builder()
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
            .build()

        val server = DshCapabilityLoopbackServer(
            authority = authority,
            registry = registry,
            ledger = ledger,
            throttle = DshCapabilityThrottle(
                maxRequestsPerWindow = 100,
                windowMillis = 60_000L,
                maxConcurrent = 8,
                cooldownMillis = 0L
            ),
            options = DshCapabilityServerOptions(
                clientReadTimeoutMillis = 2_000,
                drainTimeoutMillis = drainTimeoutMillis
            ),
            requestTimeoutMillis = requestTimeoutMillis,
            logSink = { message -> logs.add(message) }
        )
    }

    @Test
    fun startPublishesALoopbackOnlyBinding() = runBlocking {
        val harness = Harness()
        try {
            val binding = harness.server.start()

            assertTrue(binding.port in 1..65_535)
            assertEquals("http://127.0.0.1:${binding.port}", binding.baseUrl)
            assertEquals(7L, binding.generation)
            assertEquals(harness.grant?.token?.value, binding.token.value)
            assertEquals(binding, harness.server.binding())
        } finally {
            harness.server.stop()
        }
    }

    @Test
    fun servesAnAuthorizedRequestOverLoopback() = runBlocking {
        val harness = Harness()
        try {
            val binding = harness.server.start()

            val response = post(binding.port, harness.grant!!.token.value, "{}")

            assertEquals(200, statusOf(response))
            assertEquals("{\"ok\":true}", bodyOf(response))
            assertEquals(1, harness.calls.get())
        } finally {
            harness.server.stop()
        }
    }

    @Test
    fun wrongTokenIsRejectedOverLoopback() = runBlocking {
        val harness = Harness()
        try {
            val binding = harness.server.start()

            val response = post(binding.port, "dshc_" + "Q".repeat(43), "{}")

            assertEquals(401, statusOf(response))
            assertTrue(bodyOf(response).contains("UNAUTHORIZED"))
            assertEquals(0, harness.calls.get())
        } finally {
            harness.server.stop()
        }
    }

    @Test
    fun wrongMethodOverLoopbackReturns405() = runBlocking {
        val harness = Harness()
        try {
            val binding = harness.server.start()

            val response = roundTrip(
                binding.port,
                "GET /notify HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    "X-DSH-Token: ${harness.grant!!.token.value}\r\nConnection: close\r\n\r\n"
            )

            assertEquals(405, statusOf(response))
            assertEquals(0, harness.calls.get())
        } finally {
            harness.server.stop()
        }
    }

    @Test
    fun malformedRequestOverLoopbackReturns400() = runBlocking {
        val harness = Harness()
        try {
            val binding = harness.server.start()

            val response = roundTrip(binding.port, "BOGUS\r\n\r\n")

            assertEquals(400, statusOf(response))
        } finally {
            harness.server.stop()
        }
    }

    @Test
    fun stopIsIdempotentAndClosesTheListener() = runBlocking {
        val harness = Harness()
        val binding = harness.server.start()

        assertTrue(harness.server.stop())
        assertFalse(harness.server.stop())
        assertEquals(null, harness.server.binding())
        val connectionFailed = runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(InetAddress.getByName("127.0.0.1"), binding.port),
                    500
                )
            }
        }.exceptionOrNull()
        assertTrue(connectionFailed is IOException || connectionFailed is ConnectException)
    }

    @Test
    fun startTwiceIsRejected() = runBlocking {
        val harness = Harness()
        try {
            harness.server.start()

            val thrown = runCatching { harness.server.start() }.exceptionOrNull()

            assertTrue(thrown is IllegalStateException)
        } finally {
            harness.server.stop()
        }
    }

    @Test
    fun startWithoutAnActiveGenerationIsRejected() = runBlocking {
        val harness = Harness(withGeneration = false)

        val thrown = runCatching { harness.server.start() }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals(null, harness.server.binding())
    }

    @Test
    fun logsNeverContainTheGenerationToken() = runBlocking {
        val harness = Harness()
        try {
            val binding = harness.server.start()
            post(binding.port, harness.grant!!.token.value, "{}")
        } finally {
            harness.server.stop()
        }

        val token = harness.grant!!.token.value
        assertFalse(harness.logs.isEmpty())
        assertTrue(harness.logs.none { it.contains(token) })
    }

    @Test
    fun handlerResultIsWrittenWithJsonContentType() = runBlocking {
        val harness = Harness()
        harness.handler = { DshCapabilityRouteResponse.Json(200, "{\"nodes\":[]}") }
        try {
            val binding = harness.server.start()

            val response = post(binding.port, harness.grant!!.token.value, "{}")

            assertTrue(response.contains("Content-Type: application/json; charset=utf-8"))
            assertEquals("{\"nodes\":[]}", bodyOf(response))
        } finally {
            harness.server.stop()
        }
    }

    @Test
    fun stopDrainsAnInFlightRequestBeforeCancelling() = runBlocking {
        val harness = Harness(drainTimeoutMillis = 2_000L)
        val entered = CompletableDeferred<Unit>()
        harness.handler = {
            entered.complete(Unit)
            delay(150L)
            DshCapabilityRouteResponse.Json(200, "{\"ok\":true}")
        }
        val binding = harness.server.start()
        val client = async(Dispatchers.IO) {
            post(binding.port, harness.grant!!.token.value, "{}")
        }
        entered.await()

        assertTrue(harness.server.stop())

        val response = client.await()
        assertEquals(200, statusOf(response))
        assertEquals(1, harness.calls.get())
    }

    @Test
    fun stopCancelsAHandlerBeyondTheDrainDeadlineWithUnknownOutcome() = runBlocking {
        val harness = Harness(drainTimeoutMillis = 50L)
        val entered = CompletableDeferred<Unit>()
        harness.handler = {
            entered.complete(Unit)
            delay(5_000L)
            DshCapabilityRouteResponse.Json(200, "{\"ok\":true}")
        }
        val binding = harness.server.start()
        val requestId = "request-id-00000077"
        val client = async(Dispatchers.IO) {
            runCatching { postWithId(binding.port, harness.grant!!.token.value, requestId, "{}") }
        }
        entered.await()

        assertTrue(harness.server.stop())

        client.await()
        assertEquals(
            DshCapabilityOperationState.UNKNOWN,
            harness.ledger.lookup(DshCapabilityRequestId(requestId))?.state
        )
    }

    @Test
    fun bindingProjectionIsRedacted() = runBlocking {
        val harness = Harness()
        try {
            val binding = harness.server.start()

            assertNotNull(binding.token)
            assertFalse(binding.toString().contains(binding.token.value))
        } finally {
            harness.server.stop()
        }
    }

    private fun post(port: Int, token: String, body: String): String =
        postWithId(port, token, requestId = null, body = body)

    private fun postWithId(port: Int, token: String, requestId: String?, body: String): String {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val requestIdHeader = requestId?.let { "X-DSH-Request-Id: $it\r\n" }.orEmpty()
        return roundTrip(
            port,
            "POST /notify HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                "X-DSH-Token: $token\r\n$requestIdHeader" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n$body"
        )
    }

    private fun roundTrip(port: Int, raw: String): String {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress(InetAddress.getByName("127.0.0.1"), port),
                2_000
            )
            socket.soTimeout = 2_000
            socket.getOutputStream().write(raw.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun statusOf(response: String): Int =
        response.substringBefore("\r\n").split(' ').getOrNull(1)?.toIntOrNull() ?: -1

    private fun bodyOf(response: String): String = response.substringAfter("\r\n\r\n", "")
}
