package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentModelMessage
import com.muyuchat.core.agent.contract.AgentModelMessageKind
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.AgentTranscriptRole
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import com.muyuchat.mca.CloudApiConfig
import com.muyuchat.mca.CloudApiFormat
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Controlled NATIVE wire server (N05c): a real loopback socket server, the real OkHttp transport and
 * the real SSE decoder. No provider, no device and no chat projection participate.
 *
 * Covers request bytes (path/auth/tools/input), streamed function-call argument reconstruction,
 * two-call batch pairing, second-round function_call_output pairing, non-2xx errors and a connection
 * that ends before `response.completed`.
 */
class AgentResponsesLocalWireServerTest {

    private data class WireReply(
        val status: Int = 200,
        val chunks: List<String>,
        /** Declare more bytes than are written, then close, to simulate a truncated stream. */
        val truncated: Boolean = false
    )

    private data class Recorded(
        val method: String,
        val path: String,
        val authorization: String?,
        val body: String
    )

    /**
     * Minimal HTTP/1.1 server on loopback. It deliberately uses only java.net/java.io so the Android
     * unit-test boot classpath (which does not expose com.sun.net.httpserver) can compile it.
     */
    private class LoopbackSseServer : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val replies = ArrayDeque<WireReply>()
        private val received = mutableListOf<Recorded>()
        private val thread = Thread(::acceptLoop, "mca-wire-server").apply {
            isDaemon = true
            start()
        }

        val port: Int get() = socket.localPort

        fun enqueue(reply: WireReply) = synchronized(this) { replies.addLast(reply) }

        fun requests(): List<Recorded> = synchronized(this) { received.toList() }

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
            val requestLine = readAsciiLine(input) ?: return
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readAsciiLine(input) ?: break
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
            val parts = requestLine.split(' ')
            val method = parts.getOrElse(0) { "" }
            val path = parts.getOrElse(1) { "" }
            val next = synchronized(this) {
                check(replies.isNotEmpty()) { "unexpected request" }
                replies.removeFirst()
            }
            synchronized(this) {
                received += Recorded(
                    method = method,
                    path = path,
                    authorization = headers["authorization"],
                    body = body.toString(Charsets.UTF_8).take(read)
                )
            }
            val written = next.chunks.sumOf { it.toByteArray(Charsets.UTF_8).size }
            val declared = written + if (next.truncated) 128 else 0
            val out = client.getOutputStream()
            out.write(
                (
                    "HTTP/1.1 ${next.status} MCA-Wire\r\n" +
                        "Content-Type: text/event-stream\r\n" +
                        "Connection: close\r\n" +
                        "Content-Length: $declared\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8)
            )
            out.flush()
            next.chunks.forEach { chunk ->
                out.write(chunk.toByteArray(Charsets.UTF_8))
                out.flush()
                Thread.sleep(1)
            }
            out.flush()
        }

        private fun readAsciiLine(input: BufferedInputStream): String? {
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

    private val readTool = AgentToolDescriptor(
        toolKey = "workspace.list_files",
        version = "1",
        displayName = "列目录",
        description = "List files under a workspace directory.",
        schemaJson = "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}," +
            "\"required\":[\"path\"],\"additionalProperties\":false}",
        schemaHash = "b".repeat(64),
        sideEffect = AgentToolSideEffect.READ_ONLY,
        dataClass = AgentDataClass.PRIVATE
    )

    private val chatRequest = ChatRequest(
        messages = listOf(ChatMessage(Role.USER, "整理简报")),
        params = GenerationParams()
    )

    private lateinit var server: LoopbackSseServer
    private lateinit var client: OkHttpClient
    private lateinit var transport: OpenAiResponsesBoundedTransport

    @Before
    fun startServer() {
        server = LoopbackSseServer()
        client = OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
        transport = OpenAiResponsesBoundedTransport(client)
    }

    @After
    fun stopServer() {
        server.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun config(): CloudApiConfig = CloudApiConfig(
        enabled = true,
        apiFormat = CloudApiFormat.OPENAI_RESPONSES,
        baseUrl = "http://127.0.0.1:${server.port}/v1",
        chatModel = "wire-model",
        apiKey = "test-key",
        supportsTools = true
    )

    private fun request(nativeInput: org.json.JSONArray? = null) = AgentCloudStreamRequest(
        config = config(),
        chatRequest = chatRequest,
        tools = listOf(tool, readTool),
        nativeInput = nativeInput
    )

    private fun sse(data: String, event: String? = null): String =
        (if (event == null) "" else "event: $event\n") + "data: $data\n\n"

    @Test
    fun streamedCallIsReconstructedAndTheOutputRoundKeepsTheProviderCallId() = runBlocking {
        val args = """{"path":"inputs/brief.txt"}"""
        val added = JSONObject()
            .put("type", "response.output_item.added")
            .put("output_index", 0)
            .put(
                "item",
                JSONObject()
                    .put("type", "function_call")
                    .put("call_id", "call_1")
                    .put("name", "workspace_read_text")
                    .put("arguments", "")
            )
        val delta1 = JSONObject()
            .put("type", "response.function_call_arguments.delta")
            .put("output_index", 0)
            .put("item_id", "fc_1")
            .put("delta", """{"path":"inputs/""")
        val delta2 = JSONObject()
            .put("type", "response.function_call_arguments.delta")
            .put("output_index", 0)
            .put("item_id", "fc_1")
            .put("delta", """brief.txt"}""")
        val done = JSONObject()
            .put("type", "response.output_item.done")
            .put("output_index", 0)
            .put(
                "item",
                JSONObject()
                    .put("type", "function_call")
                    .put("call_id", "call_1")
                    .put("name", "workspace_read_text")
                    .put("arguments", args)
            )
        val completed = JSONObject()
            .put("type", "response.completed")
            .put(
                "response",
                JSONObject()
                    .put("status", "completed")
                    .put("output", org.json.JSONArray().put(done.getJSONObject("item")))
            )
        // Split one SSE frame across two writes to prove the reader is byte-stream correct.
        val stream = sse(added.toString(), "response.output_item.added") +
            sse(delta1.toString(), "response.function_call_arguments.delta") +
            sse(delta2.toString(), "response.function_call_arguments.delta") +
            sse(done.toString(), "response.output_item.done") +
            sse(completed.toString(), "response.completed")
        val splitAt = stream.indexOf("data: {\"type\":\"response.function_call_arguments.delta\"") + 17
        server.enqueue(
            WireReply(chunks = listOf(stream.substring(0, splitAt), stream.substring(splitAt)))
        )

        val first = transport.stream(request(), onCall = { }, onVisibleDelta = { })
        val completedTerminal = first as AgentCloudStreamTerminal.Completed
        assertEquals("responses:completed", completedTerminal.terminalProof)
        assertEquals(1, completedTerminal.calls.size)
        val call = completedTerminal.calls.single()
        assertEquals("call_1", call.callId)
        assertEquals("workspace.read_text", call.name)
        assertEquals(args, call.argumentsJson)

        val firstRequest = server.requests().single()
        val sent = JSONObject(firstRequest.body)
        assertEquals("POST", firstRequest.method)
        assertEquals("/v1/responses", firstRequest.path)
        assertEquals("Bearer test-key", firstRequest.authorization)
        assertEquals("auto", sent.getString("tool_choice"))
        val tools = sent.getJSONArray("tools")
        assertEquals(2, tools.length())
        val wireNames = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }.sorted()
        assertEquals(listOf("workspace_list_files", "workspace_read_text"), wireNames)
        assertEquals("object", tools.getJSONObject(0).getJSONObject("parameters").getString("type"))

        val nativeInput = agentResponsesInputJson(
            listOf(
                AgentModelMessage("m1", AgentTranscriptRole.SYSTEM, "instructions"),
                AgentModelMessage("m2", AgentTranscriptRole.USER, "整理简报"),
                AgentModelMessage(
                    "m3",
                    AgentTranscriptRole.ASSISTANT,
                    args,
                    kind = AgentModelMessageKind.FUNCTION_CALL,
                    callId = "call_1",
                    toolName = "workspace.read_text"
                ),
                AgentModelMessage(
                    "m4",
                    AgentTranscriptRole.USER,
                    """{"status":"SUCCEEDED","data":{"content":"hello"}}""",
                    kind = AgentModelMessageKind.FUNCTION_OUTPUT,
                    callId = "call_1"
                )
            )
        )
        val finalText = JSONObject()
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
                                        JSONObject().put("type", "output_text").put("text", "完成")
                                    )
                                )
                        )
                    )
            )
        server.enqueue(WireReply(chunks = listOf(sse(finalText.toString(), "response.completed"))))

        val second = transport.stream(request(nativeInput), onCall = { }, onVisibleDelta = { })
        assertEquals("完成", (second as AgentCloudStreamTerminal.Completed).visibleText)
        assertEquals(2, server.requests().size)
        val secondInput = JSONObject(server.requests()[1].body).getJSONArray("input")
        val outputItem = (0 until secondInput.length())
            .map { secondInput.getJSONObject(it) }
            .first { it.optString("type") == "function_call_output" }
        assertEquals("call_1", outputItem.getString("call_id"))
    }

    @Test
    fun twoCallsInOneTerminalKeepDistinctProviderIdsAndArguments() = runBlocking {
        fun call(id: String, name: String, args: String) = JSONObject()
            .put("type", "function_call")
            .put("call_id", id)
            .put("name", name)
            .put("arguments", args)
        val completed = JSONObject()
            .put("type", "response.completed")
            .put(
                "response",
                JSONObject()
                    .put("status", "completed")
                    .put(
                        "output",
                        org.json.JSONArray()
                            .put(call("call_a", "workspace_read_text", """{"path":"a.txt"}"""))
                            .put(call("call_b", "workspace_list_files", """{"path":"."}"""))
                    )
            )
        server.enqueue(WireReply(chunks = listOf(sse(completed.toString(), "response.completed"))))

        val terminal = transport.stream(request(), onCall = { }, onVisibleDelta = { })
            as AgentCloudStreamTerminal.Completed

        assertEquals(2, terminal.calls.size)
        assertEquals(listOf("call_a", "call_b"), terminal.calls.map { it.callId })
        assertEquals(
            listOf("workspace.read_text", "workspace.list_files"),
            terminal.calls.map { it.name }
        )
        assertEquals(
            listOf("""{"path":"a.txt"}""", """{"path":"."}"""),
            terminal.calls.map { it.argumentsJson }
        )
    }

    @Test
    fun aConnectionThatEndsBeforeTheTerminalNeverBecomesSuccess() = runBlocking {
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
        server.enqueue(
            WireReply(
                chunks = listOf(sse(added.toString(), "response.output_item.added")),
                truncated = true
            )
        )

        val terminal = transport.stream(request(), onCall = { }, onVisibleDelta = { })

        assertTrue(
            "a stream without response.completed must not be Completed: $terminal",
            terminal !is AgentCloudStreamTerminal.Completed
        )
        val eofBeforeTerminal = terminal is AgentCloudStreamTerminal.EofBeforeTerminal
        val unverifiedFailure = terminal is AgentCloudStreamTerminal.Failed &&
            terminal.code == com.muyuchat.core.agent.contract.AgentErrorCodes.MODEL_TERMINAL_UNVERIFIED
        assertTrue(
            "expected EOF-before-terminal, got $terminal",
            eofBeforeTerminal || unverifiedFailure
        )
    }

    @Test
    fun aNon2xxStatusIsATypedProtocolFailureBeforeAnyToolRuns() = runBlocking {
        server.enqueue(
            WireReply(
                status = 500,
                chunks = listOf("""{"error":{"message":"upstream exploded"}}""")
            )
        )

        val terminal = transport.stream(request(), onCall = { }, onVisibleDelta = { })

        val failed = terminal as AgentCloudStreamTerminal.Failed
        assertEquals(com.muyuchat.core.agent.contract.AgentErrorCodes.MODEL_PROTOCOL_ERROR, failed.code)
        assertTrue(failed.safeMessage.contains("500"))
        assertEquals(1, server.requests().size)
    }
}
