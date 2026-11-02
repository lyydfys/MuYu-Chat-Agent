package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentModelMessage
import com.muyuchat.core.agent.contract.AgentModelMessageKind
import com.muyuchat.core.agent.contract.AgentModelResult
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.AgentTranscriptRole
import com.muyuchat.core.agent.contract.AgentTurnFinish
import com.muyuchat.core.agent.contract.AgentTurnProtocol
import com.muyuchat.mca.CloudApiConfig
import com.muyuchat.mca.CloudApiFormat
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Priority 1 / TOOL-02: the *whole* native tool round through the production adapter and the real
 * OkHttp transport, against an auditable controlled Responses server.
 *
 * Round 1: the request declares the frozen run catalog; the terminal event carries a streamed
 * `function_call`, which must become a NATIVE `TOOL_CALL` turn with the provider `call_id` intact.
 * Round 2: the transcript the loop writes (`function_call` + `function_call_output`) must reach the
 * wire as provider items with the same `call_id`, and only then may the turn complete.
 *
 * This is controlled-service evidence, not a real-provider pass: the delivery report keeps that
 * distinction and no current acceptance status is upgraded by this file.
 */
class AgentNativeRoundTripControlledServerTest {

    private data class WireReply(
        val chunks: List<String>,
        val truncated: Boolean = false,
        /** Keeps the response body open after the chunks so a caller can cancel mid-stream. */
        val holdOpenMillis: Long = 0L
    )

    private class LoopbackSseServer : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val replies = ArrayDeque<WireReply>()
        private val received = mutableListOf<String>()
        private val thread = Thread(::acceptLoop, "mca-native-roundtrip-server").apply {
            isDaemon = true
            start()
        }

        val port: Int get() = socket.localPort

        fun enqueue(reply: WireReply) = synchronized(this) { replies.addLast(reply) }

        fun bodies(): List<String> = synchronized(this) { received.toList() }

        private fun acceptLoop() {
            while (!socket.isClosed) {
                try {
                    socket.accept().use(::handle)
                } catch (_: Exception) {
                    return
                }
            }
        }

        private fun handle(client: Socket) {
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = readLine(input) ?: return
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val count = input.read(body, read, contentLength - read)
                if (count < 0) break
                read += count
            }
            check(requestLine.startsWith("POST /v1/responses")) { "unexpected request: $requestLine" }
            synchronized(this) { received += body.toString(Charsets.UTF_8) }
            val next = synchronized(this) {
                check(replies.isNotEmpty()) { "unexpected request (no queued reply)" }
                replies.removeFirst()
            }
            val written = next.chunks.sumOf { it.toByteArray(Charsets.UTF_8).size }
            val declared = written + if (next.truncated) 128 else 0
            val out = client.getOutputStream()
            out.write(
                (
                    "HTTP/1.1 200 MCA-Wire\r\n" +
                        "Content-Type: text/event-stream\r\n" +
                        "Connection: close\r\n" +
                        "Content-Length: $declared\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8)
            )
            out.flush()
            next.chunks.forEach { chunk ->
                out.write(chunk.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            if (next.holdOpenMillis > 0L) {
                Thread.sleep(next.holdOpenMillis)
            }
        }

        private fun readLine(input: BufferedInputStream): String? {
            val line = StringBuilder()
            while (true) {
                val value = input.read()
                if (value < 0) return if (line.isEmpty()) null else line.toString()
                if (value == '\n'.code) return line.toString().removeSuffix("\r")
                line.append(value.toChar())
            }
        }

        override fun close() {
            socket.close()
        }
    }

    private val tool = AgentToolDescriptor(
        toolKey = "workspace.read_text",
        version = "1",
        displayName = "读取文本",
        description = "Read a UTF-8 text file under inputs/.",
        schemaJson = "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}," +
            "\"required\":[\"path\"],\"additionalProperties\":false}",
        schemaHash = "a".repeat(64),
        sideEffect = AgentToolSideEffect.READ_ONLY,
        dataClass = AgentDataClass.PRIVATE
    )

    private lateinit var server: LoopbackSseServer
    private lateinit var client: OkHttpClient
    private lateinit var adapter: AgentCloudModelAdapter

    @Before
    fun startServer() {
        server = LoopbackSseServer()
        client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
        val config = CloudApiConfig(
            enabled = true,
            apiFormat = CloudApiFormat.OPENAI_RESPONSES,
            baseUrl = "http://127.0.0.1:${server.port}/v1",
            chatModel = "wire-model",
            apiKey = "test-key",
            supportsTools = true
        )
        adapter = AgentCloudModelAdapter(
            configSource = { config },
            transport = OpenAiResponsesBoundedTransport(client)
        )
    }

    @After
    fun stopServer() {
        server.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun binding() = modelBinding(
        identityHash = "cloud-fingerprint",
        modelId = "wire-model",
        runtime = "cloud",
        protocol = AgentProtocolMode.NATIVE
    )

    private fun sse(json: JSONObject) = "data: $json\n\n"

    private fun functionCallCompleted(callId: String, args: String): JSONObject = JSONObject()
        .put("type", "response.completed")
        .put(
            "response",
            JSONObject()
                .put("status", "completed")
                .put(
                    "output",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("type", "function_call")
                            .put("call_id", callId)
                            .put("name", "workspace_read_text")
                            .put("arguments", args)
                    )
                )
        )

    /** Terminal carrying any number of `function_call` items, in wire order. */
    private fun callsCompleted(vararg calls: Pair<String, String>): JSONObject {
        val output = org.json.JSONArray()
        calls.forEach { (callId, args) ->
            output.put(
                JSONObject()
                    .put("type", "function_call")
                    .put("call_id", callId)
                    .put("name", "workspace_read_text")
                    .put("arguments", args)
            )
        }
        return JSONObject()
            .put("type", "response.completed")
            .put(
                "response",
                JSONObject()
                    .put("status", "completed")
                    .put("output", output)
            )
    }

    private fun finalTextCompleted(text: String): JSONObject = JSONObject()
        .put("type", "response.completed")
        .put(
            "response",
            JSONObject()
                .put("status", "completed")
                .put(
                    "output",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("type", "message")
                            .put("role", "assistant")
                            .put(
                                "content",
                                org.json.JSONArray().put(
                                    JSONObject().put("type", "output_text").put("text", text)
                                )
                            )
                    )
                )
        )

    @Test
    fun theFullNativeToolRoundTripsWithTheProviderCallIdThroughTheAdapter() = runBlocking {
        val args = """{"path":"inputs/brief.txt"}"""
        server.enqueue(WireReply(chunks = listOf(sse(functionCallCompleted("call_1", args)))))

        val first = adapter.infer(
            agentRequest(
                binding = binding(),
                messages = listOf(AgentModelMessage("m-user", AgentTranscriptRole.USER, "整理简报")),
                tools = listOf(tool)
            )
        ) { }

        val firstSuccess = first as AgentModelResult.Success
        val toolTurn = requireNotNull(firstSuccess.turn) { "a native turn must carry the canonical turn" }
        assertEquals(AgentTurnProtocol.NATIVE, toolTurn.protocol)
        assertEquals(AgentTurnFinish.TOOL_CALL, toolTurn.finish)
        assertEquals("responses:completed", toolTurn.terminalProof)
        val call = toolTurn.calls.single()
        assertEquals("call_1", call.callId)
        assertEquals("workspace.read_text", call.name)
        assertEquals(args, call.argumentsJson)

        val firstBody = JSONObject(server.bodies().single())
        val declaredTools = firstBody.getJSONArray("tools")
        assertEquals(1, declaredTools.length())
        assertEquals("workspace_read_text", declaredTools.getJSONObject(0).getString("name"))
        assertEquals("auto", firstBody.getString("tool_choice"))

        // The exact transcript the loop writes after the tool settles: the assistant call item keeps
        // the provider id, and the result is a paired function_call_output with the same id.
        val transcript = listOf(
            AgentModelMessage("m-system", AgentTranscriptRole.SYSTEM, "instructions"),
            AgentModelMessage("m-user", AgentTranscriptRole.USER, "整理简报"),
            AgentModelMessage(
                "m-call",
                AgentTranscriptRole.ASSISTANT,
                args,
                kind = AgentModelMessageKind.FUNCTION_CALL,
                callId = "call_1",
                toolName = "workspace.read_text"
            ),
            AgentModelMessage(
                "m-result",
                AgentTranscriptRole.USER,
                """{"status":"SUCCEEDED","data":{"content":"简报内容"}}""",
                kind = AgentModelMessageKind.FUNCTION_OUTPUT,
                callId = "call_1"
            )
        )
        server.enqueue(WireReply(chunks = listOf(sse(finalTextCompleted("已完成")))))

        val second = adapter.infer(
            agentRequest(binding = binding(), messages = transcript, tools = listOf(tool))
        ) { }

        val secondSuccess = second as AgentModelResult.Success
        val finalTurn = requireNotNull(secondSuccess.turn)
        assertEquals(AgentTurnProtocol.NATIVE, finalTurn.protocol)
        assertEquals(AgentTurnFinish.COMPLETED, finalTurn.finish)
        assertEquals("已完成", secondSuccess.visibleText)

        val secondBody = JSONObject(server.bodies()[1])
        val input = secondBody.getJSONArray("input")
        val items = (0 until input.length()).map { input.getJSONObject(it) }
        val callItem = items.single { it.optString("type") == "function_call" }
        val outputItem = items.single { it.optString("type") == "function_call_output" }
        assertEquals("call_1", callItem.getString("call_id"))
        assertEquals("workspace.read_text", callItem.getString("name"))
        assertEquals("call_1", outputItem.getString("call_id"))
        assertTrue(outputItem.getString("output").contains("SUCCEEDED"))
        assertTrue(
            "SYSTEM is carried once as instructions, never duplicated into input",
            items.none { it.optString("role") == "system" }
        )
    }

    @Test
    fun aStreamThatEndsBeforeTheTerminalNeverBecomesAToolTurn() = runBlocking {
        val added = JSONObject()
            .put("type", "response.output_item.added")
            .put("output_index", 0)
            .put(
                "item",
                JSONObject()
                    .put("type", "function_call")
                    .put("call_id", "call_x")
                    .put("name", "workspace_read_text")
                    .put("arguments", "")
            )
        server.enqueue(WireReply(chunks = listOf(sse(added)), truncated = true))

        val result = adapter.infer(
            agentRequest(
                binding = binding(),
                messages = listOf(AgentModelMessage("m-user", AgentTranscriptRole.USER, "整理简报")),
                tools = listOf(tool)
            )
        ) { }

        val failure = result as? AgentModelResult.Failure
            ?: error("a stream without a terminal must not succeed: $result")
        assertEquals(AgentErrorCodes.MODEL_TERMINAL_UNVERIFIED, failure.error.code)
    }

    @Test
    fun aProviderFailedTerminalIsATypedFailureAndNeverAToolTurn() = runBlocking {
        val failed = JSONObject()
            .put("type", "response.failed")
            .put(
                "response",
                JSONObject()
                    .put("status", "failed")
                    .put(
                        "error",
                        JSONObject().put("code", "server_error").put("message", "upstream exploded")
                    )
            )
        server.enqueue(WireReply(chunks = listOf(sse(failed))))

        val result = adapter.infer(
            agentRequest(
                binding = binding(),
                messages = listOf(AgentModelMessage("m-user", AgentTranscriptRole.USER, "整理简报")),
                tools = listOf(tool)
            )
        ) { }

        val failure = result as? AgentModelResult.Failure
            ?: error("a failed provider terminal must not succeed: $result")
        // A provider-declared failure is a terminal error, never a partial success or a tool turn.
        assertEquals(AgentErrorCodes.MODEL_PROTOCOL_ERROR, failure.error.code)
    }

    @Test
    fun anErrorEventIsATypedProtocolFailure() = runBlocking {
        val error = JSONObject()
            .put("type", "error")
            .put("error", JSONObject().put("code", "invalid_request").put("message", "bad request"))
        server.enqueue(WireReply(chunks = listOf(sse(error))))

        val result = adapter.infer(
            agentRequest(
                binding = binding(),
                messages = listOf(AgentModelMessage("m-user", AgentTranscriptRole.USER, "整理简报")),
                tools = listOf(tool)
            )
        ) { }

        val failure = result as? AgentModelResult.Failure
            ?: error("an error event must not succeed: $result")
        assertEquals(AgentErrorCodes.MODEL_PROTOCOL_ERROR, failure.error.code)
    }

    @Test
    fun eventsAfterTheTerminalCannotAddCallsOrText() = runBlocking {
        val args = """{"path":"inputs/brief.txt"}"""
        server.enqueue(
            WireReply(
                chunks = listOf(
                    sse(functionCallCompleted("call_1", args)),
                    // Provider-invalid trailing traffic: a second terminal and a late text delta. The
                    // adapter stops at the first terminal, so neither may reach the turn.
                    sse(functionCallCompleted("call_2", """{"path":"inputs/other.txt"}""")),
                    sse(finalTextCompleted("迟到的文本"))
                )
            )
        )

        val result = adapter.infer(
            agentRequest(
                binding = binding(),
                messages = listOf(AgentModelMessage("m-user", AgentTranscriptRole.USER, "整理简报")),
                tools = listOf(tool)
            )
        ) { }

        val turn = requireNotNull((result as AgentModelResult.Success).turn) {
            "a native turn must carry the canonical turn"
        }
        assertEquals("exactly one call came from the first terminal", 1, turn.calls.size)
        assertEquals("call_1", turn.calls.single().callId)
        assertEquals(args, turn.calls.single().argumentsJson)
        assertEquals("a late terminal must not leak a second call", AgentTurnFinish.TOOL_CALL, turn.finish)
        assertEquals("late text must not enter the visible answer", "", turn.text)
        assertEquals("responses:completed", turn.terminalProof)
    }

    @Test
    fun cancellingTheModelTurnNeverProducesAToolTurn() = runBlocking {
        val added = JSONObject()
            .put("type", "response.output_item.added")
            .put("output_index", 0)
            .put(
                "item",
                JSONObject()
                    .put("type", "function_call")
                    .put("call_id", "call_cancel")
                    .put("name", "workspace_read_text")
                    .put("arguments", "")
            )
        server.enqueue(WireReply(chunks = listOf(sse(added)), truncated = true, holdOpenMillis = 4_000))

        var completed: AgentModelResult? = null
        val job = launch(Dispatchers.Default) {
            completed = adapter.infer(
                agentRequest(
                    binding = binding(),
                    messages = listOf(AgentModelMessage("m-user", AgentTranscriptRole.USER, "整理简报")),
                    tools = listOf(tool)
                )
            ) { }
        }
        delay(500)
        job.cancelAndJoin()

        assertTrue("the coroutine must be cancelled, not left running", job.isCancelled)
        // Cancellation is a typed result in this adapter, not an exception and never a tool turn.
        val cancelled = completed as? AgentModelResult.Cancelled
            ?: error("a cancelled turn must surface as Cancelled, got $completed")
        assertNull("cancellation carries no error", cancelled.error)

        // The release proof: the same client and adapter still complete a normal turn afterwards,
        // so the cancelled call released its connection and its attempt bookkeeping.
        server.enqueue(WireReply(chunks = listOf(sse(finalTextCompleted("取消后仍可用")))))
        val afterCancel = adapter.infer(
            agentRequest(
                binding = binding(),
                messages = listOf(AgentModelMessage("m-user", AgentTranscriptRole.USER, "整理简报")),
                tools = listOf(tool)
            )
        ) { }
        assertEquals("取消后仍可用", (afterCancel as AgentModelResult.Success).visibleText)
    }

    @Test
    fun aProviderThatGoesSilentFailsAtTheIdleTimeoutInsteadOfHoldingTheTurn() = runBlocking {
        // The production client is built with readTimeout(0); without an idle bound a provider that
        // sends headers and then nothing would hold the turn until the user cancels.
        val silentAdapter = AgentCloudModelAdapter(
            configSource = {
                CloudApiConfig(
                    enabled = true,
                    apiFormat = CloudApiFormat.OPENAI_RESPONSES,
                    baseUrl = "http://127.0.0.1:${server.port}/v1",
                    chatModel = "wire-model",
                    apiKey = "test-key",
                    supportsTools = true
                )
            },
            transport = OpenAiResponsesBoundedTransport(client, idleTimeoutMillis = 1_000L)
        )
        // Headers promise 128 body bytes, then the socket stays silent: the read blocks with no data.
        server.enqueue(WireReply(chunks = emptyList(), truncated = true, holdOpenMillis = 6_000))

        val startedAt = System.nanoTime()
        val result = silentAdapter.infer(
            agentRequest(
                binding = binding(),
                messages = listOf(AgentModelMessage("m-user", AgentTranscriptRole.USER, "整理简报")),
                tools = listOf(tool)
            )
        ) { }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L

        val failure = result as? AgentModelResult.Failure
            ?: error("a silent provider must not succeed or hang: $result")
        assertEquals(AgentErrorCodes.MODEL_TERMINAL_UNVERIFIED, failure.error.code)
        assertTrue(
            "the failure must name the idle window: ${failure.error.safeMessage}",
            failure.error.safeMessage.contains("1 秒")
        )
        assertTrue(
            "the idle timeout must fire before the client callTimeout (took ${elapsedMillis}ms)",
            elapsedMillis < 5_000L
        )

        // The release proof: the same client (its clone shares pool and dispatcher) still completes
        // a turn after the timed-out attempt. The default-timeout adapter is used so this turn is
        // not limited by the 1 s idle window that the test injects above.
        server.enqueue(WireReply(chunks = listOf(sse(finalTextCompleted("超时后仍可用")))))
        val afterTimeout = AgentCloudModelAdapter(
            configSource = {
                CloudApiConfig(
                    enabled = true,
                    apiFormat = CloudApiFormat.OPENAI_RESPONSES,
                    baseUrl = "http://127.0.0.1:${server.port}/v1",
                    chatModel = "wire-model",
                    apiKey = "test-key",
                    supportsTools = true
                )
            },
            transport = OpenAiResponsesBoundedTransport(client)
        ).infer(
            agentRequest(
                binding = binding(),
                messages = listOf(AgentModelMessage("m-user", AgentTranscriptRole.USER, "整理简报")),
                tools = listOf(tool)
            )
        ) { }
        assertEquals("超时后仍可用", (afterTimeout as AgentModelResult.Success).visibleText)
    }

    @Test
    fun aZeroArgumentCallInTheTerminalIsNotDropped() = runBlocking {
        // A no-parameter tool is a legitimate shape; dropping the item would end the turn with no
        // call at all, which looks like "the model answered" while the tool never ran.
        server.enqueue(WireReply(chunks = listOf(sse(callsCompleted("call_zero" to "")))))

        val result = adapter.infer(
            agentRequest(
                binding = binding(),
                messages = listOf(AgentModelMessage("m-user", AgentTranscriptRole.USER, "整理简报")),
                tools = listOf(tool)
            )
        ) { }

        val turn = requireNotNull(
            (result as? AgentModelResult.Success)?.turn
        ) { "expected a native tool turn, got $result" }
        assertEquals(AgentTurnFinish.TOOL_CALL, turn.finish)
        val call = turn.calls.single()
        assertEquals("call_zero", call.callId)
        assertEquals("workspace.read_text", call.name)
        assertEquals("blank arguments normalize to an empty object", "{}", call.argumentsJson)
    }

    @Test
    fun twoCallsInOneTerminalKeepBothIdsAndBothOutputsGoBack() = runBlocking {
        val argsA = """{"path":"inputs/a.txt"}"""
        val argsB = """{"path":"inputs/b.txt"}"""
        server.enqueue(
            WireReply(chunks = listOf(sse(callsCompleted("call_a" to argsA, "call_b" to argsB))))
        )

        val first = adapter.infer(
            agentRequest(
                binding = binding(),
                messages = listOf(AgentModelMessage("m-user", AgentTranscriptRole.USER, "整理简报")),
                tools = listOf(tool)
            )
        ) { }

        val firstTurn = requireNotNull((first as AgentModelResult.Success).turn)
        assertEquals(AgentTurnFinish.TOOL_CALL, firstTurn.finish)
        assertEquals(listOf("call_a", "call_b"), firstTurn.calls.map { it.callId })
        assertEquals(listOf(argsA, argsB), firstTurn.calls.map { it.argumentsJson })

        val transcript = listOf(
            AgentModelMessage("m-user", AgentTranscriptRole.USER, "整理简报"),
            AgentModelMessage(
                "m-call-a",
                AgentTranscriptRole.ASSISTANT,
                argsA,
                kind = AgentModelMessageKind.FUNCTION_CALL,
                callId = "call_a",
                toolName = "workspace.read_text"
            ),
            AgentModelMessage(
                "m-result-a",
                AgentTranscriptRole.USER,
                """{"status":"SUCCEEDED","data":{"content":"A"}}""",
                kind = AgentModelMessageKind.FUNCTION_OUTPUT,
                callId = "call_a"
            ),
            AgentModelMessage(
                "m-call-b",
                AgentTranscriptRole.ASSISTANT,
                argsB,
                kind = AgentModelMessageKind.FUNCTION_CALL,
                callId = "call_b",
                toolName = "workspace.read_text"
            ),
            AgentModelMessage(
                "m-result-b",
                AgentTranscriptRole.USER,
                """{"status":"SUCCEEDED","data":{"content":"B"}}""",
                kind = AgentModelMessageKind.FUNCTION_OUTPUT,
                callId = "call_b"
            )
        )
        server.enqueue(WireReply(chunks = listOf(sse(finalTextCompleted("都读完了")))))

        val second = adapter.infer(
            agentRequest(binding = binding(), messages = transcript, tools = listOf(tool))
        ) { }
        assertEquals("都读完了", (second as AgentModelResult.Success).visibleText)

        val input = JSONObject(server.bodies()[1]).getJSONArray("input")
        val items = (0 until input.length()).map { input.getJSONObject(it) }
        val outputs = items.filter { it.optString("type") == "function_call_output" }
        assertEquals(
            "both results must come back paired with their own provider id",
            listOf("call_a", "call_b"),
            outputs.map { it.getString("call_id") }
        )
        assertEquals(2, items.count { it.optString("type") == "function_call" })
    }
}
