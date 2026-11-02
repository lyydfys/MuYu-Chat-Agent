package com.muyuchat.mca.dsh

import com.muyuchat.api.dsh.gateway.DshGatewayLogger
import com.muyuchat.api.dsh.gateway.DshGatewayServer
import com.muyuchat.core.dsh.contract.DshGatewayEndpoint
import com.muyuchat.core.engine.ChatExecutionBody
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.runtime.RuntimeSupervisor
import com.muyuchat.mca.facade.ChatExecutionFacade
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end JVM proof of the production stop chain over a real loopback socket:
 *
 * `POST /v1/mca/calls/cancel` (or a peer EOF on the SSE socket) ->
 * `DshGatewayCallRegistry.cancel` -> `McaDshGatewayBackend.cancel` -> `DshCallSession` ->
 * `FacadeDshLocalChatPort.cancel` -> `ChatExecutionFacade.stopGenerationIfActive` ->
 * the exact engine `stopGenerationIfActive(requestId)`.
 *
 * The engine body is a fake (a real engine needs a device), but every object between the HTTP route
 * and that boundary is the production implementation. Both the explicit route and the peer-death
 * path are exercised; the peer-death case closes the SSE socket without any further write, so it
 * proves the gateway's dedicated EOF watcher (not a failed write) cancels the call.
 */
class DshGatewayStopEndToEndTest {

    @Test
    fun `explicit cancel route stops the exact engine request once`() {
        GatewayHarness().use { gateway ->
            val sse = gateway.openStream()
            try {
                sse.readUntil { it.contains("\"content\":\"partial\"") }
                assertTrue("no stop may be dispatched before a cancel", gateway.engine.dispatchedStops.isEmpty())

                val response = gateway.cancel(sse.callId)
                assertEquals(200, response.status)
                assertTrue(response.json().getBoolean("cancelled"))
                assertEquals(
                    "the cancel route must dispatch the exact engine stop for the call's request id",
                    listOf(sse.callId),
                    gateway.engine.dispatchedStops.toList()
                )
                assertFalse(
                    "the exact cancel must never use the global engine stop",
                    gateway.engine.globalStops.get() > 0
                )

                val events = sse.readUntilDone()
                assertEquals("[DONE]", events.last())
                assertEquals("exactly one terminal frame", 1, events.count { it == "[DONE]" })
                assertEquals(listOf("cancelled"), finishReasons(events))

                // A late cancel of the settled call is a no-op: 404 and no further stop dispatch.
                waitUntil("the settled call must release its registry entry") {
                    gateway.activeCalls() == 0
                }
                val late = gateway.cancel(sse.callId)
                assertEquals(404, late.status)
                assertEquals(listOf(sse.callId), gateway.engine.dispatchedStops.toList())
            } finally {
                sse.close()
            }
        }
    }

    @Test
    fun `a killed SSE peer stops the exact engine request without any failed write`() {
        GatewayHarness().use { gateway ->
            val sse = gateway.openStream()
            sse.readUntil { it.contains("\"content\":\"partial\"") }
            assertTrue(gateway.engine.dispatchedStops.isEmpty())

            // A host crash / kill -9: the peer socket simply disappears. The scripted engine flow
            // stays suspended afterwards, so no write can fail; only the EOF watcher can observe it.
            sse.abort()

            waitUntil("peer death must dispatch the engine stop") {
                gateway.engine.dispatchedStops.contains(sse.callId)
            }
            assertEquals(listOf(sse.callId), gateway.engine.dispatchedStops.toList())
            assertFalse(
                "a peer disconnect must never use the global engine stop",
                gateway.engine.globalStops.get() > 0
            )
        }
    }

    @Test
    fun `a stale cancel id never stops the newer engine request`() {
        GatewayHarness().use { gateway ->
            val first = gateway.openStream()
            try {
                first.readUntil { it.contains("\"content\":\"partial\"") }
                assertEquals(200, gateway.cancel(first.callId).status)
                first.readUntilDone()
                assertEquals(listOf(first.callId), gateway.engine.dispatchedStops.toList())
                // The first call is released before the successor starts, so the stale cancel below
                // can only be a genuine "not live here" 404.
                waitUntil("the first call must be released") { gateway.activeCalls() == 0 }

                val second = gateway.openStream()
                try {
                    second.readUntil { it.contains("\"content\":\"partial\"") }

                    val stale = gateway.cancel(first.callId)
                    assertEquals(404, stale.status)
                    assertEquals(
                        "a stale id must not reach the newer call's engine request",
                        listOf(first.callId),
                        gateway.engine.dispatchedStops.toList()
                    )

                    assertEquals(200, gateway.cancel(second.callId).status)
                    assertEquals(
                        listOf(first.callId, second.callId),
                        gateway.engine.dispatchedStops.toList()
                    )
                    second.readUntilDone()
                } finally {
                    second.close()
                }
            } finally {
                first.close()
            }
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

    private fun waitUntil(message: String, timeoutMillis: Long = 10_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10L)
        }
        assertTrue(message, condition())
    }

    /**
     * One real gateway generation over the production adapter, facade and call registry, with the
     * engine boundary replaced by [RecordingEngineBody].
     */
    private class GatewayHarness : AutoCloseable {
        val engine = RecordingEngineBody()
        private val server: DshGatewayServer
        private val endpoint: DshGatewayEndpoint

        init {
            val facade = ChatExecutionFacade(RuntimeSupervisor(), engine)
            val backend = McaDshGatewayBackend(
                localModels = DshLocalModelAccess { localModel() },
                localChat = FacadeDshLocalChatPort(facade),
                cloudModels = null,
                cloudChat = null
            )
            server = DshGatewayServer(
                backend = backend,
                runtimeEpoch = 7L,
                logger = DshGatewayLogger.None
            )
            endpoint = runBlocking { server.start() }
        }

        fun openStream(): DshTestSseClient =
            DshTestSseClient(endpoint, TEST_LOCAL_MODEL_NAME)

        fun cancel(callId: String): DshTestHttpResult =
            DshTestHttpResult.post(
                endpoint = endpoint,
                path = "/v1/mca/calls/cancel",
                body = JSONObject().put("call_id", callId).toString()
            )

        /** The same authenticated `/healthz` surface the app uses for in-flight observation. */
        fun activeCalls(): Int =
            DshTestHttpResult.get(endpoint, "/healthz").json().getInt("active_calls")

        override fun close() {
            runBlocking { server.stop() }
        }
    }

    private class DshTestSseClient(endpoint: DshGatewayEndpoint, modelId: String) : AutoCloseable {
        private val socket = Socket("127.0.0.1", URI(endpoint.baseUrl).port)
        private val reader = DshTestLineReader(BufferedInputStream(socket.getInputStream()))
        val callId: String

        init {
            socket.soTimeout = 15_000
            val body = JSONObject()
                .put("model", modelId)
                .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "hello")))
                .put("stream", true)
                .toString()
                .toByteArray(Charsets.UTF_8)
            val output = socket.getOutputStream()
            output.write(
                buildString {
                    append("POST /v1/chat/completions HTTP/1.1\r\n")
                    append("Host: 127.0.0.1:${URI(endpoint.baseUrl).port}\r\n")
                    append("Authorization: Bearer ${endpoint.bearerToken}\r\n")
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ${body.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }.toByteArray(Charsets.ISO_8859_1)
            )
            output.write(body)
            output.flush()

            val statusLine = reader.readLine() ?: error("The gateway closed before the SSE status line.")
            check(statusLine.contains(" 200 ")) { "Expected SSE 200, got: $statusLine" }
            val headers = reader.readHeaders()
            callId = headers["x-mca-call-id"] ?: error("The SSE response carries no X-MCA-Call-Id.")
        }

        fun readEvent(): String? {
            val data = StringBuilder()
            var sawData = false
            while (true) {
                val line = reader.readLine() ?: return if (sawData) data.toString() else null
                when {
                    line.isEmpty() -> if (sawData) return data.toString()
                    line.startsWith(":") -> Unit
                    line.startsWith("data:") -> {
                        sawData = true
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").removePrefix(" "))
                    }
                }
            }
        }

        fun readUntil(maxEvents: Int = 500, predicate: (String) -> Boolean): List<String> {
            val events = ArrayList<String>()
            while (events.size < maxEvents) {
                val event = readEvent() ?: break
                events += event
                if (predicate(event)) break
            }
            return events
        }

        fun readUntilDone(maxEvents: Int = 2_000): List<String> {
            val events = ArrayList<String>()
            while (events.size < maxEvents) {
                val event = readEvent() ?: break
                events += event
                if (event == "[DONE]") break
            }
            return events
        }

        /** A killed host: the peer socket disappears without a graceful shutdown. */
        fun abort() {
            runCatching { socket.close() }
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    private class DshTestHttpResult(
        val status: Int,
        private val body: String
    ) {
        fun json(): JSONObject = JSONObject(body)

        companion object {
            fun get(endpoint: DshGatewayEndpoint, path: String): DshTestHttpResult =
                request(endpoint, "GET", path, body = null)

            fun post(endpoint: DshGatewayEndpoint, path: String, body: String): DshTestHttpResult =
                request(endpoint, "POST", path, body)

            private fun request(
                endpoint: DshGatewayEndpoint,
                method: String,
                path: String,
                body: String?
            ): DshTestHttpResult {
                Socket("127.0.0.1", URI(endpoint.baseUrl).port).use { socket ->
                    socket.soTimeout = 15_000
                    val bytes = body?.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write(
                            buildString {
                                append("$method $path HTTP/1.1\r\n")
                                append("Host: 127.0.0.1:${URI(endpoint.baseUrl).port}\r\n")
                                append("Authorization: Bearer ${endpoint.bearerToken}\r\n")
                                if (bytes != null) append("Content-Type: application/json\r\n")
                                append("Content-Length: ${bytes?.size ?: 0}\r\n")
                                append("Connection: close\r\n\r\n")
                            }.toByteArray(Charsets.ISO_8859_1)
                        )
                        if (bytes != null) write(bytes)
                        flush()
                    }
                    val reader = DshTestLineReader(BufferedInputStream(socket.getInputStream()))
                    val statusLine = reader.readLine() ?: error("No HTTP status line.")
                    val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
                        ?: error("Malformed status line: $statusLine")
                    val headers = reader.readHeaders()
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    return DshTestHttpResult(status, String(reader.readBytes(contentLength), Charsets.UTF_8))
                }
            }
        }
    }

    private class DshTestLineReader(private val input: InputStream) {
        fun readLine(): String? {
            val buffer = ByteArrayOutputStream()
            var first = true
            while (true) {
                val value = input.read()
                if (value < 0) {
                    if (first) return null
                    break
                }
                first = false
                if (value == '\n'.code) break
                if (value != '\r'.code) buffer.write(value)
            }
            return String(buffer.toByteArray(), Charsets.ISO_8859_1)
        }

        fun readHeaders(): Map<String, String> {
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine() ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
            }
            return headers
        }

        fun readBytes(count: Int): ByteArray {
            val bytes = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = input.read(bytes, offset, count - offset)
                if (read < 0) break
                offset += read
            }
            return if (offset == count) bytes else bytes.copyOf(offset)
        }
    }

    private class RecordingEngineBody : ChatExecutionBody {
        val dispatchedStops = CopyOnWriteArrayList<String>()
        val globalStops = AtomicInteger()
        private val everStopped = ConcurrentHashMap<String, Boolean>()

        override fun stream(
            request: ChatRequest,
            context: LocalChatExecutionContext
        ): Flow<GenerateEvent> = flow {
            emit(GenerateEvent.Chunk(text = "partial", stats = RuntimeStats(loaded = true)))
            awaitCancellation()
        }

        override suspend fun stopGeneration() {
            globalStops.incrementAndGet()
        }

        override suspend fun stopGenerationIfActive(requestId: String): Boolean {
            if (everStopped.putIfAbsent(requestId, true) != null) return true
            dispatchedStops += requestId
            return true
        }
    }
}
