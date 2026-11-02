package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentModelResult
import com.muyuchat.core.agent.contract.AgentModelUsage
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.mca.CloudApiConfig
import com.muyuchat.mca.CloudApiFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCloudModelAdapterTest {

    private class FakeTransport : AgentCloudStreamTransport {
        var behavior: suspend (onVisibleDelta: (String) -> Unit) -> AgentCloudStreamTerminal =
            { _ -> AgentCloudStreamTerminal.Completed("hello", AgentModelUsage(1, 2, false)) }
        var callHandle: AgentCloudCallHandle? = null
        var cancelCalls = 0
        var streamCalls = 0
        var lastRequest: AgentCloudStreamRequest? = null

        override suspend fun stream(
            request: AgentCloudStreamRequest,
            onCall: (AgentCloudCallHandle) -> Unit,
            onVisibleDelta: (String) -> Unit
        ): AgentCloudStreamTerminal {
            streamCalls += 1
            lastRequest = request
            val handle = AgentCloudCallHandle { cancelCalls += 1 }
            callHandle = handle
            onCall(handle)
            return behavior(onVisibleDelta)
        }
    }

    private fun responsesConfig(): CloudApiConfig = CloudApiConfig(
        enabled = true,
        apiFormat = CloudApiFormat.OPENAI_RESPONSES,
        baseUrl = "https://example.test/v1",
        chatModel = "gpt-test",
        supportsTools = true
    )

    private fun adapter(
        transport: FakeTransport,
        config: CloudApiConfig = responsesConfig()
    ): AgentCloudModelAdapter = AgentCloudModelAdapter(
        configSource = { config },
        transport = transport
    )

    private fun binding(protocol: AgentProtocolMode = AgentProtocolMode.NATIVE) =
        modelBinding("cloud-fingerprint", modelId = "gpt-test", runtime = "cloud", protocol = protocol)

    private fun readToolDescriptor() = com.muyuchat.core.agent.contract.AgentToolDescriptor(
        toolKey = "workspace.read_text",
        version = "1",
        displayName = "读取文本",
        description = "Read a UTF-8 text file under inputs/.",
        schemaJson = "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}," +
            "\"required\":[\"path\"],\"additionalProperties\":false}",
        schemaHash = "a".repeat(64),
        sideEffect = com.muyuchat.core.agent.contract.AgentToolSideEffect.READ_ONLY,
        dataClass = com.muyuchat.core.agent.contract.AgentDataClass.PRIVATE
    )

    @Test
    fun completedTerminalProducesSuccess() = runBlocking {
        val transport = FakeTransport().apply {
            behavior = { onDelta ->
                onDelta("hello")
                AgentCloudStreamTerminal.Completed("hello", AgentModelUsage(5, 7, false))
            }
        }
        val result = adapter(transport).infer(agentRequest(binding = binding())) { }
        val success = result as AgentModelResult.Success
        assertEquals("hello", success.visibleText)
        assertEquals(7, success.usage?.outputTokens)
    }

    private fun compatibleConfig(): CloudApiConfig = CloudApiConfig(
        enabled = true,
        apiFormat = CloudApiFormat.OPENAI_COMPATIBLE,
        baseUrl = "https://example.test/v1",
        chatModel = "model-x",
        apiKey = "k",
        supportsTools = true
    )

    @Test
    fun compatibleRecordRunsThePromptedChatWire() = runBlocking {
        val responses = FakeTransport()
        val chat = FakeTransport().apply {
            behavior = {
                AgentCloudStreamTerminal.Completed(
                    """{"protocol":"mca.agent.v1","kind":"final","text":"done"}""",
                    AgentModelUsage(4, 5, false)
                )
            }
        }
        val port = AgentCloudModelAdapter(
            configSource = { compatibleConfig() },
            transport = responses,
            chatCompletionsTransport = chat
        )
        val phases = mutableListOf<String>()
        val result = port.infer(
            agentRequest(binding = binding(AgentProtocolMode.PROMPTED_V1))
        ) { progress ->
            (progress as? com.muyuchat.core.agent.contract.AgentModelProgress.Phase)?.let {
                phases += it.label
            }
        }

        val success = result as AgentModelResult.Success
        assertEquals("""{"protocol":"mca.agent.v1","kind":"final","text":"done"}""", success.visibleText)
        assertEquals("a compatible record never uses the Responses wire", 0, responses.streamCalls)
        assertEquals(1, chat.streamCalls)
        assertTrue("the prompted wire asks for one JSON object", chat.lastRequest!!.promptedJson)
        assertTrue("no provider tools are declared", chat.lastRequest!!.tools.isEmpty())
        assertNull("no Responses input items are sent", chat.lastRequest!!.nativeInput)
        assertEquals(listOf("CLOUD_CHAT_COMPLETIONS"), phases)
    }

    @Test
    fun compatibleRecordWithoutAChatWireIsRejectedTyped() = runBlocking {
        val transport = FakeTransport()
        val port = AgentCloudModelAdapter(
            configSource = { compatibleConfig() },
            transport = transport
        )
        val result = port.infer(agentRequest(binding = binding(AgentProtocolMode.PROMPTED_V1))) { }
        val failure = result as AgentModelResult.Failure
        assertEquals(AgentErrorCodes.MODEL_PROTOCOL_ERROR, failure.error.code)
        assertEquals("no wire, no call", 0, transport.streamCalls)
    }

    @Test
    fun nativeBindingOnTheChatWireIsRejectedBeforeAnyCall() = runBlocking {
        val chat = FakeTransport()
        val port = AgentCloudModelAdapter(
            configSource = { compatibleConfig() },
            transport = FakeTransport(),
            chatCompletionsTransport = chat
        )
        val result = port.infer(
            agentRequest(
                binding = binding(AgentProtocolMode.NATIVE),
                tools = listOf(readToolDescriptor())
            )
        ) { }
        val failure = result as AgentModelResult.Failure
        assertEquals(AgentErrorCodes.MODEL_PROTOCOL_ERROR, failure.error.code)
        assertEquals(0, chat.streamCalls)
    }

    @Test
    fun nativeToolCallTerminalProducesAToolCallTurn() = runBlocking {
        val transport = FakeTransport().apply {
            behavior = {
                AgentCloudStreamTerminal.Completed(
                    visibleText = "",
                    usage = AgentModelUsage(3, 4, false),
                    calls = listOf(
                        com.muyuchat.core.agent.contract.AgentTurnToolCall(
                            callId = "call-1",
                            name = "workspace.read_text",
                            argumentsJson = """{"path":"inputs/a"}"""
                        )
                    ),
                    terminalProof = "responses:completed"
                )
            }
        }

        val result = adapter(transport).infer(
            agentRequest(
                binding = binding(AgentProtocolMode.NATIVE),
                tools = listOf(readToolDescriptor())
            )
        ) { }

        val success = result as AgentModelResult.Success
        assertEquals(
            com.muyuchat.core.agent.contract.AgentTurnFinish.TOOL_CALL,
            success.turn?.finish
        )
        assertEquals(
            com.muyuchat.core.agent.contract.AgentTurnProtocol.NATIVE,
            success.turn?.protocol
        )
        assertEquals("call-1", success.turn?.calls?.single()?.callId)
        assertEquals("workspace.read_text", success.turn?.calls?.single()?.name)
        assertEquals("responses:completed", success.turn?.terminalProof)
        assertEquals(1, transport.lastRequest?.tools?.size)
    }

    @Test
    fun promptedBindingIgnoresProviderCallsAndStaysTextOnly() = runBlocking {
        val transport = FakeTransport().apply {
            behavior = {
                AgentCloudStreamTerminal.Completed(
                    visibleText = "hello",
                    usage = AgentModelUsage(3, 4, false),
                    calls = listOf(
                        com.muyuchat.core.agent.contract.AgentTurnToolCall(
                            callId = "call-1",
                            name = "workspace.read_text",
                            argumentsJson = """{"path":"inputs/a"}"""
                        )
                    )
                )
            }
        }

        val result = adapter(transport).infer(
            agentRequest(binding = binding(AgentProtocolMode.PROMPTED_V1))
        ) { }

        val success = result as AgentModelResult.Success
        assertEquals("hello", success.visibleText)
        assertEquals(null, success.turn)
    }

    @Test
    fun nativeMessagesReachTheTransportAsProviderInputItems() = runBlocking {
        val transport = FakeTransport()
        val request = agentRequest(
            binding = binding(AgentProtocolMode.NATIVE),
            messages = listOf(
                com.muyuchat.core.agent.contract.AgentModelMessage(
                    "m-sys",
                    com.muyuchat.core.agent.contract.AgentTranscriptRole.SYSTEM,
                    "sys"
                ),
                com.muyuchat.core.agent.contract.AgentModelMessage(
                    "m-call",
                    com.muyuchat.core.agent.contract.AgentTranscriptRole.ASSISTANT,
                    """{"path":"inputs/a"}""",
                    kind = com.muyuchat.core.agent.contract.AgentModelMessageKind.FUNCTION_CALL,
                    callId = "call_1",
                    toolName = "workspace.read_text"
                ),
                com.muyuchat.core.agent.contract.AgentModelMessage(
                    "m-out",
                    com.muyuchat.core.agent.contract.AgentTranscriptRole.USER,
                    """{"status":"SUCCEEDED"}""",
                    kind = com.muyuchat.core.agent.contract.AgentModelMessageKind.FUNCTION_OUTPUT,
                    callId = "call_1"
                )
            )
        )

        adapter(transport).infer(request) { }

        val items = transport.lastRequest!!.nativeInput!!
        assertEquals(2, items.length())
        assertEquals("function_call", items.getJSONObject(0).getString("type"))
        assertEquals("call_1", items.getJSONObject(0).getString("call_id"))
        assertEquals("function_call_output", items.getJSONObject(1).getString("type"))
        assertEquals("call_1", items.getJSONObject(1).getString("call_id"))
    }

    @Test
    fun eofBeforeTerminalIsModelTerminalUnverified() = runBlocking {
        val transport = FakeTransport().apply {
            behavior = { _ -> AgentCloudStreamTerminal.EofBeforeTerminal("partial") }
        }
        val result = adapter(transport).infer(agentRequest(binding = binding())) { }
        assertEquals("MODEL_TERMINAL_UNVERIFIED", result.failureCode())
    }

    @Test
    fun incompleteOrFailedTerminalNeverYieldsSuccess() = runBlocking {
        val transport = FakeTransport().apply {
            behavior = {
                AgentCloudStreamTerminal.Failed(
                    "MODEL_TERMINAL_UNVERIFIED",
                    "answer was incomplete"
                )
            }
        }
        val result = adapter(transport).infer(agentRequest(binding = binding())) { }
        assertEquals("MODEL_TERMINAL_UNVERIFIED", result.failureCode())
    }

    @Test
    fun nonResponsesCloudConfigIsRejected() = runBlocking {
        val transport = FakeTransport()
        val config = CloudApiConfig(
            enabled = true,
            apiFormat = CloudApiFormat.OPENAI_COMPATIBLE,
            baseUrl = "https://example.test/v1",
            chatModel = "legacy"
        )
        val result = adapter(transport, config).infer(agentRequest(binding = binding())) { }
        assertEquals("MODEL_PROTOCOL_ERROR", result.failureCode())
        assertEquals(0, transport.streamCalls)
    }

    @Test
    fun noneProtocolBindingIsRejectedBeforeConfigAccess() = runBlocking {
        val transport = FakeTransport()
        val result = adapter(transport).infer(
            agentRequest(binding = binding(AgentProtocolMode.NONE))
        ) { }
        assertEquals("MODEL_PROTOCOL_ERROR", result.failureCode())
        assertEquals(0, transport.streamCalls)
    }

    @Test
    fun stopCancelsOnlyTheExactCallAndJob() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val transport = FakeTransport().apply {
            behavior = { onDelta ->
                onDelta("partial")
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val cloudAdapter = adapter(transport)
        val request = agentRequest(binding = binding())
        val inference = async { cloudAdapter.infer(request) { } }
        withTimeout(3_000) { started.await() }
        assertNotNull(transport.callHandle)

        val dispatch = cloudAdapter.requestStop(request.attempt)
        assertTrue(dispatch.accepted)
        assertTrue(inference.await() is AgentModelResult.Cancelled)
        assertEquals(1, transport.cancelCalls)
    }

    @Test
    fun completedTerminalWithoutVisibleTextIsUnverified() = runBlocking {
        val transport = FakeTransport().apply {
            behavior = { _ -> AgentCloudStreamTerminal.Completed("", null) }
        }
        val result = adapter(transport).infer(agentRequest(binding = binding())) { }
        assertEquals("MODEL_TERMINAL_UNVERIFIED", result.failureCode())
    }

    @Test
    fun staleAttemptStopIsRejectedAndDoesNotCancelNewerCall() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val transport = FakeTransport().apply {
            behavior = { onDelta ->
                onDelta("partial")
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val cloudAdapter = adapter(transport)
        val request = agentRequest(binding = binding())
        val inference = async { cloudAdapter.infer(request) { } }
        withTimeout(3_000) { started.await() }

        val stale = cloudAdapter.requestStop(attempt(ordinal = 2))
        assertFalse(stale.accepted)
        assertEquals(0, transport.cancelCalls)

        cloudAdapter.requestStop(request.attempt)
        inference.await()
        assertEquals(1, transport.cancelCalls)
    }

    @Test
    fun staleAuthorityAfterCompletedTerminalIsCancelled() = runBlocking {
        val transport = FakeTransport().apply {
            behavior = { onDelta ->
                onDelta("hello")
                AgentCloudStreamTerminal.Completed("hello", null)
            }
        }
        var state = AgentAttemptAuthorityState.CURRENT
        val guarded = AgentCloudModelAdapter(
            configSource = { responsesConfig() },
            transport = transport,
            authority = { state }
        )
        state = AgentAttemptAuthorityState.SUPERSEDED
        val result = guarded.infer(agentRequest(binding = binding())) { }
        assertTrue(result is AgentModelResult.Cancelled)
    }
}
