package com.muyuchat.api.dsh.gateway

import com.muyuchat.core.dsh.contract.DshFinishReason
import com.muyuchat.core.dsh.contract.DshModelEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshGatewayCancellationIntegrationTest : DshGatewayServerTestBase() {

    @Test
    fun clientDisconnectCancelsExactlyTheDisconnectedCall() {
        backend.streamFactory = { neverEndingFlow(DshModelEvent.TextDelta("hello")) }
        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        val firstEvents = sse.readUntil { it.contains("\"content\":\"hello\"") }
        assertTrue(firstEvents.any { it.contains("\"content\":\"hello\"") })
        val callId = backend.singleRequest().callId
        sse.closeSocket()

        waitUntil { backend.cancelCalls.contains(callId) }
        waitUntil { activeCallCount() == 0 }
        assertEquals(listOf(callId), backend.cancelCalls.toList())

        // The gateway and the fake backend remain usable for a later independent call.
        backend.streamFactory = {
            flowOf(DshModelEvent.Completed(DshFinishReason.STOP, promptTokens = 1, completionTokens = 1))
        }
        val next = chat(stream = false)
        assertEquals(200, next.status)
        assertEquals(
            "stop",
            next.json().getJSONArray("choices").getJSONObject(0).getString("finish_reason")
        )
    }

    @Test
    fun explicitCancellationCancelsOnlyThatCallAndEndsItAsCancelled() {
        backend.streamFactory = { neverEndingFlow(DshModelEvent.TextDelta("hello")) }
        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            val before = sse.readUntil { it.contains("\"content\":\"hello\"") }
            val callId = backend.singleRequest().callId

            val cancelResponse = cancelCall(callId)
            assertEquals(200, cancelResponse.status)
            assertTrue(cancelResponse.json().getBoolean("cancelled"))
            assertEquals(callId, cancelResponse.json().getString("call_id"))

            val events = before + sse.readUntilDone()
            assertEquals("[DONE]", events.last())
            val finishReasons = events.dropLast(1).map { JSONObject(it) }.mapNotNull { frame ->
                val choices = frame.optJSONArray("choices") ?: return@mapNotNull null
                if (choices.length() == 0) return@mapNotNull null
                val choice = choices.getJSONObject(0)
                if (choice.isNull("finish_reason")) null else choice.getString("finish_reason")
            }
            assertEquals(listOf("cancelled"), finishReasons)
            assertEquals(listOf(callId), backend.cancelCalls.toList())
            assertFalse(events.any { it.contains("\"finish_reason\":\"stop\"") })

            waitUntil { activeCallCount() == 0 }
            val again = cancelCall(callId)
            assertEquals(404, again.status)
            assertEquals("call_not_found", again.json().getJSONObject("error").getString("code"))
        } finally {
            sse.close()
        }
    }

    @Test
    fun cancellingAHeldToolEnvelopeProducesNoExecutableToolCall() {
        backend.streamFactory = {
            neverEndingFlow(DshModelEvent.ToolCallDelta(0, "call_a", null, "{\"path\":"))
        }
        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            waitUntil { backend.requests.isNotEmpty() }
            val callId = backend.singleRequest().callId
            val before = sse.readUntil { it.contains("\"role\":\"assistant\"") }
            assertEquals(200, cancelCall(callId).status)
            val events = before + sse.readUntilDone()
            assertEquals("[DONE]", events.last())
            val frames = events.dropLast(1).map { JSONObject(it) }
            val finishReasons = frames.mapNotNull { frame ->
                val choices = frame.optJSONArray("choices") ?: return@mapNotNull null
                if (choices.length() == 0) return@mapNotNull null
                val choice = choices.getJSONObject(0)
                if (choice.isNull("finish_reason")) null else choice.getString("finish_reason")
            }
            assertEquals(listOf("cancelled"), finishReasons)
            assertFalse(events.any { it.contains("\"tool_calls\"") })
            assertFalse(events.any { it.contains("\"arguments\"") })
            assertEquals(listOf(callId), backend.cancelCalls.toList())
        } finally {
            sse.close()
        }
    }

    @Test
    fun explicitCancellationOfABufferedCallReturnsTheCancelledError() {
        backend.streamFactory = { neverEndingFlow(DshModelEvent.TextDelta("hello")) }
        var response: DshTestHttpResponse? = null
        val worker = kotlin.concurrent.thread(name = "buffered-cancel-test") {
            response = chat(stream = false)
        }
        try {
            waitUntil { backend.requests.isNotEmpty() }
            val callId = backend.singleRequest().callId
            assertEquals(200, cancelCall(callId).status)
            worker.join(5_000L)
            assertFalse(worker.isAlive)
            val cancelled = response
            checkNotNull(cancelled) { "The buffered call did not finish after cancellation." }
            assertEquals(499, cancelled.status)
            assertEquals("cancelled", cancelled.json().getJSONObject("error").getString("code"))
            assertEquals(listOf(callId), backend.cancelCalls.toList())
            waitUntil { activeCallCount() == 0 }
        } finally {
            worker.join(5_000L)
        }
    }

    @Test
    fun unknownCancellationIdIsRejectedWithoutTouchingAnything() {
        backend.streamFactory = {
            flowOf(DshModelEvent.Completed(DshFinishReason.STOP, promptTokens = 1, completionTokens = 1))
        }
        val response = cancelCall("dsh:7:999")
        assertEquals(404, response.status)
        assertTrue(backend.cancelCalls.isEmpty())

        val malformed = DshTestHttp.request(
            endpoint, "POST", "/v1/mca/calls/cancel", body = "{}"
        )
        assertEquals(400, malformed.status)
    }

    @Test
    fun serverStopCancelsTheActiveCallAndWithdrawsTheEndpoint() {
        backend.streamFactory = { neverEndingFlow(DshModelEvent.TextDelta("hello")) }
        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        sse.readUntil { it.contains("\"content\":\"hello\"") }
        val callId = backend.singleRequest().callId

        runBlocking { server.stop() }
        waitUntil { backend.cancelCalls.contains(callId) }
        assertNull(server.currentEndpoint())
        assertFalse(server.isRunning)
        runCatching { sse.readEvent() }
        sse.close()
        assertEquals(listOf(callId), backend.cancelCalls.toList())
    }
}

class DshGatewayIsolationIntegrationTest : DshGatewayServerTestBase() {

    override fun limits(): DshGatewayLimits = DshGatewayLimits.Default.copy(maxConcurrentCalls = 2)

    @Test
    fun aDisconnectCancelsOnlyItsOwnCallNotTheOtherInFlightCall() {
        backend.streamFactory = { neverEndingFlow(DshModelEvent.TextDelta("hello")) }
        val first = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        val second = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            first.readUntil { it.contains("\"content\":\"hello\"") }
            second.readUntil { it.contains("\"content\":\"hello\"") }
            waitUntil { backend.requests.size == 2 }
            val firstId = backend.requests[0].callId
            val secondId = backend.requests[1].callId
            assertNotEquals(firstId, secondId)

            first.closeSocket()
            waitUntil { backend.cancelCalls.contains(firstId) }
            assertFalse(backend.cancelCalls.contains(secondId))
            waitUntil { activeCallCount() == 1 }

            val cancelResponse = cancelCall(secondId)
            assertEquals(200, cancelResponse.status)
            val events = second.readUntilDone()
            assertEquals("[DONE]", events.last())
            val finishReasons = events.dropLast(1).map { JSONObject(it) }.mapNotNull { frame ->
                val choices = frame.optJSONArray("choices") ?: return@mapNotNull null
                if (choices.length() == 0) return@mapNotNull null
                val choice = choices.getJSONObject(0)
                if (choice.isNull("finish_reason")) null else choice.getString("finish_reason")
            }
            assertEquals(listOf("cancelled"), finishReasons)
            waitUntil { backend.cancelCalls.size == 2 }
            assertEquals(setOf(firstId, secondId), backend.cancelCalls.toSet())
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun callsUseIndependentMonotonicIdentities() {
        backend.streamFactory = {
            flowOf(DshModelEvent.Completed(DshFinishReason.STOP, promptTokens = 1, completionTokens = 1))
        }
        assertEquals(200, chat(stream = false).status)
        assertEquals(200, chat(stream = false).status)
        val first = backend.requests[0].callId
        val second = backend.requests[1].callId
        assertEquals("dsh:7:1", first)
        assertEquals("dsh:7:2", second)
        assertNotEquals(first, second)
    }
}

class DshGatewayConcurrencyIntegrationTest : DshGatewayServerTestBase() {

    override fun limits(): DshGatewayLimits = DshGatewayLimits.Default.copy(maxConcurrentCalls = 1)

    @Test
    fun aSecondConcurrentCallIsRejectedBeforeTheBackendIsTouched() {
        backend.streamFactory = { neverEndingFlow(DshModelEvent.TextDelta("hello")) }
        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            sse.readUntil { it.contains("\"content\":\"hello\"") }
            waitUntil { activeCallCount() == 1 }

            val second = chat(stream = false)
            assertEquals(429, second.status)
            assertEquals(
                "too_many_concurrent_calls",
                second.json().getJSONObject("error").getString("code")
            )
            assertEquals("1", second.headers["retry-after"])
            assertEquals(1, backend.requests.size)

            val callId = backend.requests.single().callId
            assertEquals(200, cancelCall(callId).status)
            sse.readUntilDone()
            waitUntil { activeCallCount() == 0 }

            backend.streamFactory = {
                flowOf(DshModelEvent.Completed(DshFinishReason.STOP, promptTokens = 1, completionTokens = 1))
            }
            assertEquals(200, chat(stream = false).status)
        } finally {
            sse.close()
        }
    }
}

/**
 * The cancel path must not merely wake the local collector: it must dispatch the adapter's exact
 * native stop (for a local model `facade.stopGenerationIfActive(requestId)` -> `runner.requestStop()`)
 * before the cancel is acknowledged, it must stay bounded when that dispatch never returns, and it
 * must keep exactly one terminal outcome for the call.
 */
class DshGatewayCancelDispatchIntegrationTest : DshGatewayServerTestBase() {

    override fun limits(): DshGatewayLimits =
        DshGatewayLimits.Default.copy(cancelDispatchTimeoutMillis = 250L)

    @Test
    fun explicitCancelAwaitsTheAdapterStopDispatch() {
        val stopDispatched = java.util.concurrent.atomic.AtomicBoolean(false)
        backend.streamFactory = { neverEndingFlow(DshModelEvent.TextDelta("hello")) }
        backend.onCancel = { stopDispatched.set(true) }
        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            sse.readUntil { it.contains("\"content\":\"hello\"") }
            val callId = backend.singleRequest().callId

            val response = cancelCall(callId)
            assertEquals(200, response.status)
            assertTrue(
                "the cancel route must answer only after the adapter stop was dispatched",
                stopDispatched.get()
            )
            assertEquals(listOf(callId), backend.cancelCalls.toList())

            val events = sse.readUntilDone()
            assertEquals("[DONE]", events.last())
            assertEquals(1, events.count { it == "[DONE]" })
            assertEquals(listOf("cancelled"), finishReasons(events))
        } finally {
            sse.close()
        }
    }

    @Test
    fun aWedgedAdapterStopCannotBlockTheCancelResponse() {
        backend.streamFactory = { neverEndingFlow(DshModelEvent.TextDelta("hello")) }
        // A broken adapter that never returns from its stop dispatch.
        backend.onCancel = { awaitCancellation() }
        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            sse.readUntil { it.contains("\"content\":\"hello\"") }
            val callId = backend.singleRequest().callId

            val startedNanos = System.nanoTime()
            val response = cancelCall(callId)
            val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L
            assertEquals(200, response.status)
            assertTrue(
                "the cancel response must stay bounded by cancelDispatchTimeoutMillis, " +
                    "took ${elapsedMillis}ms",
                elapsedMillis < 5_000L
            )

            val events = sse.readUntilDone()
            assertEquals("[DONE]", events.last())
            assertEquals(listOf("cancelled"), finishReasons(events))
            waitUntil { activeCallCount() == 0 }
        } finally {
            sse.close()
        }
    }

    @Test
    fun aBackendThatSettlesDuringTheCancelStillProducesOneTerminal() {
        val gate = CompletableDeferred<Unit>()
        backend.streamFactory = {
            flow {
                emit(DshModelEvent.TextDelta("hello"))
                gate.await()
                // The engine's own terminal after its stop was observed. It must never be written
                // as a second terminal frame on the same call.
                emit(
                    DshModelEvent.Completed(
                        DshFinishReason.CANCELLED,
                        promptTokens = 1,
                        completionTokens = 2
                    )
                )
            }
        }
        backend.onCancel = { gate.complete(Unit) }
        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            sse.readUntil { it.contains("\"content\":\"hello\"") }
            val callId = backend.singleRequest().callId

            assertEquals(200, cancelCall(callId).status)

            val events = sse.readUntilDone()
            assertEquals("exactly one [DONE] frame", 1, events.count { it == "[DONE]" })
            assertEquals(listOf("cancelled"), finishReasons(events))
            waitUntil { activeCallCount() == 0 }
        } finally {
            sse.close()
        }
    }

    private fun finishReasons(events: List<String>): List<String> =
        events.dropLast(1).mapNotNull { event ->
            val frame = runCatching { JSONObject(event) }.getOrNull() ?: return@mapNotNull null
            val choices = frame.optJSONArray("choices") ?: return@mapNotNull null
            if (choices.length() == 0) return@mapNotNull null
            val choice = choices.getJSONObject(0)
            if (choice.isNull("finish_reason")) null else choice.getString("finish_reason")
        }
}

/**
 * Peer death or an explicit cancel must settle the gateway call within a bounded time even when
 * the backend collector is blocked inside a non-cancellable native call.
 *
 * This is the 2026-10-26 device failure: after the stop click the host closed its gateway sockets,
 * but `/healthz active_calls` stayed 1 for ~145 s because the handler awaited a collector blocked
 * in the worker's `beginCompletion` Binder call. The collector below blocks in [Thread.sleep], which
 * - like that Binder call - does not observe coroutine cancellation. The gateway must still write
 * the cancelled terminal, release the call and let `/healthz` fall to 0 within the drain bound.
 */
class DshGatewayBoundedSettlementIntegrationTest : DshGatewayServerTestBase() {

    private val drainMillis = 2_000L

    override fun limits(): DshGatewayLimits = DshGatewayLimits.Default.copy(
        cancelDispatchTimeoutMillis = 500L,
        collectorDrainTimeoutMillis = drainMillis
    )

    private fun blockedCollectorFlow(): Flow<DshModelEvent> = flow {
        emit(DshModelEvent.TextDelta("hello"))
        // Models a worker Binder prefill that ignores coroutine cancellation for minutes.
        Thread.sleep(10_000L)
        emit(DshModelEvent.Completed(DshFinishReason.STOP, promptTokens = 1, completionTokens = 1))
    }

    @Test
    fun aPeerDisconnectReleasesTheCallWhileTheCollectorIsStillBlocked() {
        backend.streamFactory = { blockedCollectorFlow() }
        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            sse.readUntil { it.contains("\"content\":\"hello\"") }
            val callId = backend.singleRequest().callId

            sse.closeSocket()

            waitUntil { backend.cancelCalls.contains(callId) }
            assertEquals(
                "the peer disconnect must dispatch the adapter stop for the exact call id",
                listOf(callId),
                backend.cancelCalls.toList()
            )
            // The collector is still blocked in its 10 s sleep; the registry must not wait for it.
            waitUntil { activeCallCount() == 0 }
            assertTrue(
                "the bounded drain must be visible in diagnostics",
                logs.any { it.contains("did not unwind within") }
            )
        } finally {
            sse.close()
        }
    }

    @Test
    fun anExplicitCancelDuringABlockedCollectStillSettlesTheCall() {
        backend.streamFactory = { blockedCollectorFlow() }
        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            sse.readUntil { it.contains("\"content\":\"hello\"") }
            val callId = backend.singleRequest().callId

            val startedNanos = System.nanoTime()
            val response = cancelCall(callId)
            assertEquals(200, response.status)
            assertTrue(response.json().getBoolean("cancelled"))
            assertEquals(listOf(callId), backend.cancelCalls.toList())

            val events = sse.readUntilDone()
            assertEquals("[DONE]", events.last())
            assertEquals("exactly one terminal frame", 1, events.count { it == "[DONE]" })
            assertEquals(listOf("cancelled"), finishReasons(events))
            waitUntil { activeCallCount() == 0 }
            val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L
            assertTrue(
                "the call must settle within the bounded drain, took ${elapsedMillis}ms",
                elapsedMillis < 8_000L
            )
        } finally {
            sse.close()
        }
    }

    @Test
    fun anExplicitCancelAfterTheDisconnectIsAcknowledgedWhileTheCollectorUnwinds() {
        backend.streamFactory = { blockedCollectorFlow() }
        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            sse.readUntil { it.contains("\"content\":\"hello\"") }
            val callId = backend.singleRequest().callId

            // The host closes the SSE connection (first cancel transition) and the collector stays
            // blocked, so the call is still live while already cancelling.
            sse.closeSocket()
            waitUntil { backend.cancelCalls.contains(callId) }

            val response = cancelCall(callId)
            assertEquals(
                "a live, already-cancelling call must be acknowledged, not reported as not found",
                200,
                response.status
            )
            assertTrue(response.json().getBoolean("cancelled"))
            assertEquals("the exact stop is dispatched at most once", listOf(callId), backend.cancelCalls.toList())
            waitUntil { activeCallCount() == 0 }
        } finally {
            sse.close()
        }
    }

    private fun finishReasons(events: List<String>): List<String> =
        events.dropLast(1).mapNotNull { event ->
            val frame = runCatching { JSONObject(event) }.getOrNull() ?: return@mapNotNull null
            val choices = frame.optJSONArray("choices") ?: return@mapNotNull null
            if (choices.length() == 0) return@mapNotNull null
            val choice = choices.getJSONObject(0)
            if (choice.isNull("finish_reason")) null else choice.getString("finish_reason")
        }
}
