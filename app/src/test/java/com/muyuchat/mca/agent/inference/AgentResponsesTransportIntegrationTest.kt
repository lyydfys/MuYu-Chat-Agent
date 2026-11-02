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
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Controlled Responses wire (N05c): the real transport builds the request bytes and parses the SSE
 * terminal event, without any external provider. Two rounds: `function_call` in, then
 * `function_call_output` out.
 */
class AgentResponsesTransportIntegrationTest {

    private val config = CloudApiConfig(
        enabled = true,
        apiFormat = CloudApiFormat.OPENAI_RESPONSES,
        baseUrl = "https://example.test/v1",
        chatModel = "gpt-test",
        apiKey = "test-key",
        supportsTools = true
    )
    private val chatRequest = ChatRequest(
        messages = listOf(ChatMessage(Role.USER, "整理简报")),
        params = GenerationParams()
    )
    private val readTool = AgentToolDescriptor(
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

    private val bodies = ArrayDeque<String>()
    private val sentBodies = mutableListOf<String>()
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val body = Buffer().also { request -> chain.request().body!!.writeTo(request) }.readUtf8()
        sentBodies += body
        val next = bodies.removeFirst()
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(next.toResponseBody("text/event-stream".toMediaType()))
            .build()
    }.build()
    private val transport = OpenAiResponsesBoundedTransport(client)

    private fun sse(json: String) = "data: $json\n\n"

    private fun request(nativeInput: org.json.JSONArray? = null) = AgentCloudStreamRequest(
        config = config,
        chatRequest = chatRequest,
        tools = listOf(readTool),
        nativeInput = nativeInput
    )

    @Test
    fun firstRoundDeclaresToolsAndReconstructsTheProviderCall() = runBlocking {
        val completed = JSONObject()
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
                                .put("call_id", "call_1")
                                .put("name", "workspace_read_text")
                                .put("arguments", """{"path":"inputs/brief.txt"}""")
                        )
                    )
            )
        bodies += sse(completed.toString())

        val terminal = transport.stream(request(), onCall = { }, onVisibleDelta = { })

        val completedTerminal = terminal as AgentCloudStreamTerminal.Completed
        assertEquals(1, completedTerminal.calls.size)
        assertEquals("call_1", completedTerminal.calls.single().callId)
        assertEquals("workspace.read_text", completedTerminal.calls.single().name)
        assertEquals("responses:completed", completedTerminal.terminalProof)

        val sent = JSONObject(sentBodies.single())
        val tools = sent.getJSONArray("tools")
        assertEquals("workspace_read_text", tools.getJSONObject(0).getString("name"))
        assertEquals("auto", sent.getString("tool_choice"))
    }

    @Test
    fun aFailedOrIncompleteTerminalIsNeverSuccess() = runBlocking {
        bodies += sse(
            """{"type":"response.failed","response":{"error":{"message":"provider exploded"}}}"""
        )
        val failed = transport.stream(request(), onCall = { }, onVisibleDelta = { })
        assertTrue("failed must not be success: $failed", failed is AgentCloudStreamTerminal.Failed)

        bodies += sse(
            """{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}}"""
        )
        val incomplete = transport.stream(request(), onCall = { }, onVisibleDelta = { })
        assertTrue(
            "incomplete must not be success: $incomplete",
            incomplete is AgentCloudStreamTerminal.Failed
        )
    }

    @Test
    fun aTerminalThatOmitsAStreamedCallFailsClosed() = runBlocking {
        val done = JSONObject()
            .put("type", "response.output_item.done")
            .put("output_index", 0)
            .put(
                "item",
                JSONObject()
                    .put("type", "function_call")
                    .put("call_id", "call_7")
                    .put("name", "workspace_read_text")
                    .put("arguments", """{"path":"inputs/a.txt"}""")
            )
        val completed = JSONObject()
            .put("type", "response.completed")
            .put("response", JSONObject().put("status", "completed").put("output", org.json.JSONArray()))
        bodies += sse(done.toString()) + sse(completed.toString())

        val terminal = transport.stream(request(), onCall = { }, onVisibleDelta = { })

        assertTrue(
            "an inconsistent terminal must fail closed: $terminal",
            terminal is AgentCloudStreamTerminal.Failed
        )
    }

    @Test
    fun secondRoundCarriesThePairedFunctionCallOutput() = runBlocking {
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
                                .put("content", org.json.JSONArray().put(
                                    JSONObject().put("type", "output_text").put("text", "完成")
                                ))
                        )
                    )
            )
        bodies += sse(finalText.toString())

        val messages = listOf(
            AgentModelMessage("agent-msg-0", AgentTranscriptRole.SYSTEM, "instructions"),
            AgentModelMessage("agent-msg-1", AgentTranscriptRole.USER, "整理简报"),
            AgentModelMessage(
                "agent-msg-2",
                AgentTranscriptRole.ASSISTANT,
                """{"path":"inputs/brief.txt"}""",
                kind = AgentModelMessageKind.FUNCTION_CALL,
                callId = "call_1",
                toolName = "workspace.read_text"
            ),
            AgentModelMessage(
                "agent-msg-3",
                AgentTranscriptRole.USER,
                """{"status":"SUCCEEDED","data":{"content":"hello"}}""",
                kind = AgentModelMessageKind.FUNCTION_OUTPUT,
                callId = "call_1"
            )
        )

        val terminal = transport.stream(
            request(nativeInput = agentResponsesInputJson(messages)),
            onCall = { },
            onVisibleDelta = { }
        )

        val completed = terminal as AgentCloudStreamTerminal.Completed
        assertEquals("完成", completed.visibleText)
        assertTrue(completed.calls.isEmpty())

        val sent = JSONObject(sentBodies.single())
        val input = sent.getJSONArray("input")
        assertEquals(3, input.length())
        assertEquals("function_call", input.getJSONObject(1).getString("type"))
        val output = input.getJSONObject(2)
        assertEquals("function_call_output", output.getString("type"))
        assertEquals("call_1", output.getString("call_id"))
        assertTrue(output.getString("output").contains("SUCCEEDED"))
    }
}
