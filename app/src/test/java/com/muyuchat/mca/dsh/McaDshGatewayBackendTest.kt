package com.muyuchat.mca.dsh

import com.muyuchat.core.dsh.contract.DshFinishReason
import com.muyuchat.core.dsh.contract.DshModelEvent
import com.muyuchat.core.engine.ChatToolCall
import com.muyuchat.mca.CloudApiFormat
import com.muyuchat.mca.CloudModelKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end adapter behaviour on fakes: terminals, exact cancellation, prompted vs native tools,
 * catalog honesty, busy admission and late-event isolation. No engine, network or Android API is
 * constructed here.
 */
class McaDshGatewayBackendTest {

    private fun terminals(events: List<DshModelEvent>): List<DshModelEvent> =
        events.filter { it is DshModelEvent.Completed || it is DshModelEvent.Failed }

    @Test
    fun `catalog exposes only the loaded local model and configured cloud chat models`() = runBlocking {
        val backend = backend(
            loadedLocalModel = localModel(),
            cloudRecords = listOf(
                cloudRecord(id = "chat"),
                cloudRecord(id = "image", kind = CloudModelKind.IMAGE)
            )
        )
        val catalog = backend.catalog()
        assertEquals(listOf("mca-local:test-model", "mca-cloud:chat"), catalog.map { it.id })
    }

    @Test
    fun `catalog is empty when nothing can be served`() = runBlocking {
        val backend = backend(loadedLocalModel = null, localPort = null, cloudPort = null)
        assertTrue(backend.catalog().isEmpty())
        assertTrue(backend.stream(dshRequest()).toList().single() is DshModelEvent.Failed)
    }

    @Test
    fun `local answer streams text and ends with stop`() {
        val port = FakeLocalChatPort()
        port.flowFactory = { _, _ -> eventsFlow(chunkEvent("Hel"), chunkEvent("lo"), doneEvent()) }
        val backend = backend(localPort = port)

        val events = collect(backend, dshRequest())

        assertEquals(
            listOf(DshModelEvent.TextDelta("Hel"), DshModelEvent.TextDelta("lo")),
            events.dropLast(1)
        )
        val completed = events.last() as DshModelEvent.Completed
        assertEquals(DshFinishReason.STOP, completed.finishReason)
        assertEquals(5, completed.promptTokens)
        assertEquals(2, completed.completionTokens)
        assertEquals(1, terminals(events).size)

        assertEquals("dsh:7:3", port.requests.single().requestId)
        val request = port.requests.single().request
        assertEquals(4096, request.params.nCtx)
        assertTrue(request.tools.isEmpty())
        assertEquals("hello", request.messages.single().content)
    }

    @Test
    fun `local model answer with prompted envelope becomes tool call deltas`() {
        val port = FakeLocalChatPort()
        val raw = envelope("read" to "{\"path\":\"a.txt\"}")
        port.flowFactory = { _, _ ->
            eventsFlow(
                chunkEvent("Checking.\n"),
                chunkEvent(raw.substring(0, raw.length / 2)),
                chunkEvent(raw.substring(raw.length / 2)),
                doneEvent()
            )
        }
        val backend = backend(localPort = port)
        val events = collect(
            backend,
            dshRequest(
                messages = listOf(systemMessage("DSH PROMPT"), userMessage("hello")),
                tools = listOf(toolDefinition("read"))
            )
        )

        assertEquals(DshModelEvent.TextDelta("Checking.\n"), events.first())
        val toolCall = events[1] as DshModelEvent.ToolCallDelta
        assertEquals(0, toolCall.index)
        assertEquals("read", toolCall.name)
        assertEquals("{\"path\":\"a.txt\"}", toolCall.argumentsFragment)
        assertTrue((events.last() as DshModelEvent.Completed).finishReason == DshFinishReason.TOOL_CALLS)
        assertEquals(1, terminals(events).size)

        // The local engine never receives native tool fields; the prompt layer is the only path.
        assertTrue(port.requests.single().request.tools.isEmpty())
        assertTrue(port.requests.single().request.toolExchanges.isEmpty())
        val system = port.requests.single().request.messages.first().content
        assertTrue(system.contains("<tool_call>"))
        assertTrue(system.contains("\"name\":\"read\""))
        assertFalse(system.contains(DshPromptedToolProtocol.START_MARKER))
    }

    @Test
    fun `unknown prompted tool name fails instead of executing nothing silently`() {
        val port = FakeLocalChatPort()
        port.flowFactory = { _, _ ->
            eventsFlow(chunkEvent(envelope("write" to "{}")), doneEvent())
        }
        val backend = backend(localPort = port)
        val events = collect(
            backend,
            dshRequest(tools = listOf(toolDefinition("read")))
        )
        val failed = events.single() as DshModelEvent.Failed
        assertEquals(DshAdapterErrorCodes.PROMPTED_TOOL_UNKNOWN_NAME, failed.code)
        assertTrue(failed.retryable)
    }

    @Test
    fun `local engine cancellation maps to completed cancelled`() {
        val port = FakeLocalChatPort()
        port.flowFactory = { _, _ ->
            eventsFlow(chunkEvent("partial"), errorEvent("REQUEST_CANCELLED", "cancelled"))
        }
        val events = collect(backend(localPort = port), dshRequest())
        assertEquals(DshModelEvent.TextDelta("partial"), events.first())
        assertEquals(DshFinishReason.CANCELLED, (events.last() as DshModelEvent.Completed).finishReason)
        assertEquals(1, terminals(events).size)
    }

    @Test
    fun `cancelled call flushes held prompted text before the cancelled terminal`() {
        val port = FakeLocalChatPort()
        val held = DshPromptedToolProtocol.START_MARKER + "\n{\"tool_calls\":["
        port.flowFactory = { _, _ -> eventsFlow(chunkEvent(held), errorEvent("REQUEST_CANCELLED")) }
        val events = collect(backend(localPort = port), dshRequest(tools = listOf(toolDefinition("read"))))
        assertEquals(DshModelEvent.TextDelta(held), events.first())
        assertEquals(DshFinishReason.CANCELLED, (events.last() as DshModelEvent.Completed).finishReason)
    }

    @Test
    fun `local context overflow and generation errors map to distinct failures`() {
        val contextPort = FakeLocalChatPort()
        contextPort.flowFactory = { _, _ ->
            eventsFlow(errorEvent("context_length_exceeded", "too long for n_ctx"))
        }
        val contextEvents = collect(backend(localPort = contextPort), dshRequest())
        val contextFailure = contextEvents.single() as DshModelEvent.Failed
        assertEquals(DshAdapterErrorCodes.CONTEXT_LENGTH_EXCEEDED, contextFailure.code)
        assertFalse(contextFailure.retryable)

        val generationPort = FakeLocalChatPort()
        generationPort.flowFactory = { _, _ -> eventsFlow(errorEvent("native_boom", "boom")) }
        val generationEvents = collect(backend(localPort = generationPort), dshRequest())
        val generationFailure = generationEvents.single() as DshModelEvent.Failed
        assertEquals("native_boom", generationFailure.code)
        assertTrue(generationFailure.retryable)
    }

    @Test
    fun `an unloaded local model fails fast instead of retrying`() {
        val port = FakeLocalChatPort()
        port.flowFactory = { _, _ ->
            eventsFlow(errorEvent("MODEL_NOT_LOADED", ""))
        }
        val failed = collect(backend(localPort = port), dshRequest()).single() as DshModelEvent.Failed
        assertEquals(DshAdapterErrorCodes.MODEL_NOT_AVAILABLE, failed.code)
        assertFalse("retrying cannot load a model", failed.retryable)
        assertTrue(failed.message.contains("Load a model"))
    }

    @Test
    fun `local stream closed without terminal is a retryable failure`() {
        val port = FakeLocalChatPort()
        port.flowFactory = { _, _ -> eventsFlow(chunkEvent("x")) }
        val events = collect(backend(localPort = port), dshRequest())
        assertEquals(DshModelEvent.TextDelta("x"), events.first())
        val failed = events.last() as DshModelEvent.Failed
        assertEquals(DshAdapterErrorCodes.BACKEND_STREAM_CLOSED, failed.code)
        assertTrue(failed.retryable)
    }

    @Test
    fun `a thrown local flow still ends with exactly one failed terminal`() {
        val port = FakeLocalChatPort()
        port.flowFactory = { _, _ -> flow { throw IllegalStateException("runtime is shut down") } }
        val events = collect(backend(localPort = port), dshRequest())
        val failed = events.single() as DshModelEvent.Failed
        assertEquals(DshAdapterErrorCodes.MODEL_GENERATION_FAILED, failed.code)
        assertTrue(failed.message.contains("runtime is shut down"))
    }

    @Test
    fun `duplicate call id is rejected without dispatching again`() {
        val port = FakeLocalChatPort()
        val backend = backend(localPort = port)
        collect(backend, dshRequest())
        assertEquals(1, port.requests.size)

        val replay = collect(backend, dshRequest())
        val failed = replay.single() as DshModelEvent.Failed
        assertEquals(DshAdapterErrorCodes.DUPLICATE_CALL_ID, failed.code)
        assertFalse(failed.retryable)
        assertEquals(1, port.requests.size)
    }

    @Test
    fun `cancel targets exactly the live request id and only once`() = runBlocking {
        val port = FakeLocalChatPort()
        val started = CompletableDeferred<Unit>()
        port.flowFactory = { _, _ ->
            flow {
                emit(chunkEvent("x"))
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val backend = backend(localPort = port)
        val job = launch(Dispatchers.Default) {
            runCatching { backend.stream(dshRequest()).toList() }
        }
        assertTrue("stream must start", withTimeoutOrNull(10_000L) { started.await() } != null)

        assertTrue(backend.cancelCall(TEST_CALL_ID))
        assertEquals(listOf("dsh:7:3"), port.cancelCalls)
        assertFalse(backend.cancelCall(TEST_CALL_ID))
        assertEquals(listOf("dsh:7:3"), port.cancelCalls)
        job.cancel()
        assertTrue("cancelled call must finish", withTimeoutOrNull(10_000L) { job.join() } != null)
    }

    @Test
    fun `late cancel after a finished call is a no-op`() = runBlocking {
        val port = FakeLocalChatPort()
        val backend = backend(localPort = port)
        collect(backend, dshRequest(callId = "dsh:1:1"))
        assertFalse(backend.cancelCall("dsh:1:1"))

        val nextEvents = collect(backend, dshRequest(callId = "dsh:1:2"))
        assertEquals(DshFinishReason.STOP, (nextEvents.last() as DshModelEvent.Completed).finishReason)
        assertTrue(port.cancelCalls.isEmpty())
    }

    @Test
    fun `gateway generation stop structurally cancels its call without a second terminal`() = runBlocking {
        val port = FakeLocalChatPort()
        val started = CompletableDeferred<Unit>()
        // The blocking flow is scoped to the call being stopped; the successor gets its own flow.
        port.flowByRequestId["dsh:6:1"] = flow {
            emit(chunkEvent("partial"))
            awaitCancellation()
        }
        port.flowByRequestId["dsh:7:1"] = eventsFlow(chunkEvent("next"), doneEvent())
        val registry = DshCallRegistry()
        val backend = backend(localPort = port, registry = registry)

        val stoppedCallEvents = mutableListOf<DshModelEvent>()
        val job = launch(Dispatchers.Default) {
            backend.stream(dshRequest(callId = "dsh:6:1")).collect { event ->
                stoppedCallEvents += event
                // Signal after the first delta reached the collector, so cancelling cannot erase it.
                started.complete(Unit)
            }
        }
        assertTrue("stream must start", withTimeoutOrNull(10_000L) { started.await() } != null)

        // Gateway stop: exact out-of-band cancel first, then structural cancellation of the flow.
        assertTrue(backend.cancelCall("dsh:6:1"))
        job.cancel()
        assertTrue("cancelled call must finish", withTimeoutOrNull(10_000L) { job.join() } != null)

        // Structural cancellation is the gateway's own signal. The adapter must not invent a
        // second terminal for a collector that is already gone.
        assertEquals(listOf(DshModelEvent.TextDelta("partial")), stoppedCallEvents)
        assertTrue(terminals(stoppedCallEvents).isEmpty())
        assertEquals(setOf("dsh:6:1"), port.cancelCalls.toSet())
        assertFalse(backend.cancelCall("dsh:6:1"))
        assertEquals(0, registry.liveCount())

        // The next call is independent: it must complete instead of inheriting a stopped session,
        // a shared stop flag or the suspended fake flow of the cancelled call.
        val nextEvents = withTimeoutOrNull(10_000L) {
            backend.stream(dshRequest(callId = "dsh:7:1")).toList()
        } ?: error("the successor call did not finish; the cancelled call leaked into it")
        assertEquals(2, nextEvents.size)
        assertEquals(DshModelEvent.TextDelta("next"), nextEvents.first())
        assertEquals(DshFinishReason.STOP, (nextEvents.last() as DshModelEvent.Completed).finishReason)
        assertEquals(1, terminals(nextEvents).size)
        assertEquals(setOf("dsh:6:1"), port.cancelCalls.toSet())
        assertFalse(backend.cancelCall("dsh:7:1"))
        assertEquals(listOf("dsh:6:1", "dsh:7:1"), port.requests.map { it.requestId })
    }

    @Test
    fun `structural cancellation alone stops exactly its own engine request`() = runBlocking {
        val port = FakeLocalChatPort()
        port.flowByRequestId["dsh:8:1"] = eventsFlow(chunkEvent("partial"), doneEvent())
        val backend = backend(localPort = port)

        val delivered = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            backend.stream(dshRequest(callId = "dsh:8:1")).collect {
                delivered.complete(Unit)
                // Suspend inside the collector so cancellation lands while the call is live.
                awaitCancellation()
            }
        }
        assertTrue("stream must start", withTimeoutOrNull(10_000L) { delivered.await() } != null)

        // No out-of-band gateway signal: only the structural cancellation can stop the engine.
        job.cancel()
        assertTrue("cancelled call must finish", withTimeoutOrNull(10_000L) { job.join() } != null)
        assertEquals(listOf("dsh:8:1"), port.cancelCalls)
        assertFalse(backend.cancelCall("dsh:8:1"))
    }

    @Test
    fun `waiting behind another engine request becomes a retryable resource-busy failure`() = runBlocking {
        val port = FakeLocalChatPort()
        port.flowFactory = { _, _ -> flow { awaitCancellation() } }
        val backend = backend(
            localPort = port,
            limits = DshAdapterLimits.Default.copy(admissionTimeoutMillis = 1_000L)
        )
        val events = backend.stream(dshRequest()).toList()
        val failed = events.single() as DshModelEvent.Failed
        assertEquals(DshAdapterErrorCodes.RESOURCE_BUSY, failed.code)
        assertTrue(failed.retryable)
        assertTrue(port.cancelCalls.contains("dsh:7:3"))
    }

    @Test
    fun `a terminal from an older call never appears in a newer call`() {
        val port = FakeLocalChatPort()
        var call = 0
        port.flowFactory = { _, _ ->
            call += 1
            if (call == 1) {
                eventsFlow(chunkEvent("A"), errorEvent(null, "first failed"))
            } else {
                eventsFlow(chunkEvent("B"), doneEvent())
            }
        }
        val backend = backend(localPort = port)
        val first = collect(backend, dshRequest(callId = "dsh:2:1"))
        val second = collect(backend, dshRequest(callId = "dsh:2:2"))

        assertTrue(first.last() is DshModelEvent.Failed)
        assertEquals(listOf(DshModelEvent.TextDelta("B")), second.dropLast(1))
        assertEquals(DshFinishReason.STOP, (second.last() as DshModelEvent.Completed).finishReason)
    }

    @Test
    fun `unknown model id and changed local model are rejected`() {
        val unknown = collect(
            backend(localPort = FakeLocalChatPort()),
            dshRequest(modelId = "nope")
        ).single() as DshModelEvent.Failed
        assertEquals(DshAdapterErrorCodes.MODEL_NOT_FOUND, unknown.code)

        val changed = collect(
            backend(localPort = FakeLocalChatPort()),
            dshRequest(modelId = "mca-local:other")
        ).single() as DshModelEvent.Failed
        assertEquals(DshAdapterErrorCodes.MODEL_NOT_AVAILABLE, changed.code)
        // A stale binding cannot become current by retrying; DSH must surface it instead of looping.
        assertFalse(changed.retryable)
    }

    @Test
    fun `no loaded local model means no local call and no local catalog entry`() = runBlocking {
        val backend = backend(loadedLocalModel = null, localPort = FakeLocalChatPort())
        assertTrue(backend.catalog().isEmpty())
        val failed = backend.stream(dshRequest()).toList().single() as DshModelEvent.Failed
        assertEquals(DshAdapterErrorCodes.MODEL_NOT_AVAILABLE, failed.code)
    }

    @Test
    fun `cloud prompted transport is used when the format has no native tools`() {
        val port = FakeCloudChatPort()
        val raw = envelope("read" to "{\"cmd\":\"ls\"}")
        port.flowFactory = { _, _ ->
            eventsFlow(chunkEvent("run\n"), chunkEvent(raw), doneEvent())
        }
        val records = listOf(
            cloudRecord(id = "c1", format = CloudApiFormat.ANTHROPIC, supportsTools = true)
        )
        val backend = backend(loadedLocalModel = null, localPort = null, cloudRecords = records, cloudPort = port)
        val events = collect(
            backend,
            dshRequest(
                modelId = "mca-cloud:c1",
                tools = listOf(toolDefinition("read"))
            )
        )

        assertEquals(DshModelEvent.TextDelta("run\n"), events.first())
        val toolCall = events[1] as DshModelEvent.ToolCallDelta
        assertEquals("read", toolCall.name)
        assertEquals(DshFinishReason.TOOL_CALLS, (events.last() as DshModelEvent.Completed).finishReason)
        assertTrue(port.requests.single().request.tools.isEmpty())
        assertTrue(
            port.requests.single().request.messages.first().content
                .contains(DshPromptedToolProtocol.START_MARKER)
        )
    }

    @Test
    fun `cloud Responses native tool calls map to tool-call deltas`() {
        val port = FakeCloudChatPort()
        port.flowFactory = { _, _ ->
            eventsFlow(
                chunkEvent("ok"),
                doneEvent(toolCalls = listOf(ChatToolCall("call_1", "read", "{\"path\":\"a\"}")))
            )
        }
        val records = listOf(
            cloudRecord(id = "native", format = CloudApiFormat.OPENAI_RESPONSES, supportsTools = true)
        )
        val backend = backend(loadedLocalModel = null, localPort = null, cloudRecords = records, cloudPort = port)
        val events = collect(
            backend,
            dshRequest(
                modelId = "mca-cloud:native",
                messages = listOf(
                    userMessage("read a"),
                    assistantMessage(null, listOf(toolCall("call_1", "read"))),
                    toolMessage("call_1", "previous body")
                ),
                tools = listOf(toolDefinition("read"))
            )
        )

        assertEquals(DshModelEvent.TextDelta("ok"), events.first())
        val toolCall = events[1] as DshModelEvent.ToolCallDelta
        assertEquals("call_1", toolCall.id)
        assertEquals("read", toolCall.name)
        assertEquals(DshFinishReason.TOOL_CALLS, (events.last() as DshModelEvent.Completed).finishReason)
        assertEquals(1, port.requests.single().request.tools.size)
        assertEquals(1, port.requests.single().request.toolExchanges.size)
    }

    @Test
    fun `cloud HTTP errors map to stable retryable-aware codes`() {
        val unauthorized = FakeCloudChatPort()
        unauthorized.flowFactory = { _, _ ->
            eventsFlow(errorEvent(null, "云端接口错误 401: invalid key"))
        }
        val records = listOf(cloudRecord(id = "c1", format = CloudApiFormat.ANTHROPIC))
        val unauthorizedFailure = collect(
            backend(loadedLocalModel = null, localPort = null, cloudRecords = records, cloudPort = unauthorized),
            dshRequest(modelId = "mca-cloud:c1")
        ).single() as DshModelEvent.Failed
        assertEquals("dsh_cloud_http_401", unauthorizedFailure.code)
        assertFalse(unauthorizedFailure.retryable)

        val unavailable = FakeCloudChatPort()
        unavailable.flowFactory = { _, _ ->
            eventsFlow(errorEvent(null, "云端 Responses 接口错误 503: busy"))
        }
        val unavailableFailure = collect(
            backend(loadedLocalModel = null, localPort = null, cloudRecords = records, cloudPort = unavailable),
            dshRequest(modelId = "mca-cloud:c1")
        ).single() as DshModelEvent.Failed
        assertEquals("dsh_cloud_http_503", unavailableFailure.code)
        assertTrue(unavailableFailure.retryable)
    }

    @Test
    fun `unconfigured or unknown cloud model ids are not served`() {
        val backend = backend(
            loadedLocalModel = null,
            localPort = null,
            cloudRecords = listOf(cloudRecord(id = "broken", baseUrl = "", apiKey = "")),
            cloudPort = FakeCloudChatPort()
        )
        val notConfigured = collect(backend, dshRequest(callId = "dsh:9:1", modelId = "mca-cloud:broken"))
            .single() as DshModelEvent.Failed
        assertEquals(DshAdapterErrorCodes.MODEL_NOT_AVAILABLE, notConfigured.code)

        val unknown = collect(backend, dshRequest(callId = "dsh:9:2", modelId = "mca-cloud:missing"))
            .single() as DshModelEvent.Failed
        assertEquals(DshAdapterErrorCodes.MODEL_NOT_AVAILABLE, unknown.code)
    }

    @Test
    fun `invalid gateway requests fail before any dispatch`() {
        val port = FakeLocalChatPort()
        val backend = backend(localPort = port)

        val cases = listOf(
            dshRequest(callId = " "),
            dshRequest(messages = emptyList()),
            dshRequest(temperature = 3.0),
            dshRequest(maxTokens = 0),
            dshRequest(toolChoice = com.muyuchat.core.dsh.contract.DshToolChoice.REQUIRED)
        )
        cases.forEach { request ->
            val failed = collect(backend, request).single() as DshModelEvent.Failed
            assertEquals(DshAdapterErrorCodes.INVALID_REQUEST, failed.code)
        }
        assertTrue(port.requests.isEmpty())
    }

    @Test
    fun `malformed prompted envelope is a semantic failure without invented calls`() {
        val port = FakeLocalChatPort()
        port.flowFactory = { _, _ ->
            eventsFlow(
                chunkEvent(DshPromptedToolProtocol.START_MARKER + "\nnot json\n" +
                    DshPromptedToolProtocol.END_MARKER),
                doneEvent()
            )
        }
        val events = collect(
            backend(localPort = port),
            dshRequest(tools = listOf(toolDefinition("read")))
        )
        val failed = events.single() as DshModelEvent.Failed
        assertEquals(DshAdapterErrorCodes.PROMPTED_TOOL_MALFORMED, failed.code)
        assertTrue(failed.retryable)
    }

    @Test
    fun `a truncated local envelope is repaired once and then executed`() {
        val port = FakeLocalChatPort()
        var calls = 0
        port.flowFactory = { _, _ ->
            calls += 1
            if (calls == 1) {
                eventsFlow(chunkEvent("<tool_call>"), doneEvent())
            } else {
                eventsFlow(
                    chunkEvent("<tool_call>{\"name\":\"read\",\"arguments\":{\"path\":\"a.txt\"}}</tool_call>"),
                    doneEvent()
                )
            }
        }
        val events = collect(
            backend(localPort = port),
            dshRequest(tools = listOf(toolDefinition("read")))
        )
        val call = events.filterIsInstance<DshModelEvent.ToolCallDelta>().single()
        assertEquals("read", call.name)
        assertEquals(2, port.requests.size)
        assertTrue(
            port.requests[1].request.messages.last().content.contains("closing </tool_call>")
        )
    }

    @Test
    fun `a malformed block with a closing tag uses the generic repair reason`() {
        val port = FakeLocalChatPort()
        var calls = 0
        port.flowFactory = { _, _ ->
            calls += 1
            if (calls == 1) {
                eventsFlow(
                    chunkEvent("<tool_call>{\"name\":\"read\",\"arguments\":{}</tool_call>"),
                    doneEvent()
                )
            } else {
                eventsFlow(
                    chunkEvent("<tool_call>{\"name\":\"read\",\"arguments\":{\"path\":\"a.txt\"}}</tool_call>"),
                    doneEvent()
                )
            }
        }
        val events = collect(
            backend(localPort = port),
            dshRequest(tools = listOf(toolDefinition("read")))
        )
        assertEquals("read", events.filterIsInstance<DshModelEvent.ToolCallDelta>().single().name)
        assertEquals(2, port.requests.size)
        assertTrue(
            port.requests[1].request.messages.last().content.contains("did not contain a usable tool call")
        )
    }

    @Test
    fun `a protocol guide echo is repaired once and then executed`() {
        val port = FakeLocalChatPort()
        var calls = 0
        port.flowFactory = { _, _ ->
            calls += 1
            if (calls == 1) {
                eventsFlow(
                    chunkEvent("Call rules: the name must be copied from the offered list."),
                    doneEvent()
                )
            } else {
                eventsFlow(chunkEvent(envelope("read" to "{\"path\":\"a.txt\"}")), doneEvent())
            }
        }
        val events = collect(
            backend(localPort = port),
            dshRequest(tools = listOf(toolDefinition("read")))
        )
        val call = events.filterIsInstance<DshModelEvent.ToolCallDelta>().single()
        assertEquals("read", call.name)
        assertEquals(2, port.requests.size)
    }

    @Test
    fun `an exhausted echo repair is a typed failure, never a text answer`() {
        val port = FakeLocalChatPort()
        port.flowFactory = { _, _ ->
            eventsFlow(
                chunkEvent("Call rules: do not describe the command in prose."),
                doneEvent()
            )
        }
        val events = collect(
            backend(localPort = port),
            dshRequest(tools = listOf(toolDefinition("read")))
        )
        val terminal = events.last() as DshModelEvent.Failed
        assertEquals(DshAdapterErrorCodes.PROMPTED_TOOL_MALFORMED, terminal.code)
        assertTrue(terminal.retryable)
        assertEquals(2, port.requests.size)
        assertTrue(events.none { it is DshModelEvent.Completed })
    }

    @Test
    fun `repair runs at most once even when the second envelope is malformed`() {
        val port = FakeLocalChatPort()
        var calls = 0
        port.flowFactory = { _, _ ->
            calls += 1
            if (calls == 1) {
                eventsFlow(chunkEvent("<tool_call>"), doneEvent())
            } else {
                eventsFlow(chunkEvent("<tool_call>{\"name\":\"read\","), doneEvent())
            }
        }
        val events = collect(
            backend(localPort = port),
            dshRequest(tools = listOf(toolDefinition("read")))
        )
        assertEquals(
            DshAdapterErrorCodes.PROMPTED_TOOL_MALFORMED,
            (events.last() as DshModelEvent.Failed).code
        )
        assertEquals(2, port.requests.size)
    }

    @Test
    fun `a hermes-style gguf executes its function-parameter envelope end to end`() {
        val port = FakeLocalChatPort()
        port.flowFactory = { _, _ ->
            eventsFlow(
                chunkEvent(
                    "<tool_call>\n<function=read>\n" +
                        "<parameter=path>a.txt</parameter>\n</function>\n</tool_call>"
                ),
                doneEvent()
            )
        }
        val events = collect(
            backend(
                loadedLocalModel = localModel(toolEnvelopeStyle = DshToolEnvelopeStyle.XML),
                localPort = port
            ),
            dshRequest(tools = listOf(toolDefinition("read")))
        )
        val call = events.filterIsInstance<DshModelEvent.ToolCallDelta>().single()
        assertEquals("read", call.name)
        assertTrue(call.argumentsFragment.orEmpty().contains("\"path\":\"a.txt\""))
        assertTrue(
            (events.last() as DshModelEvent.Completed).finishReason == DshFinishReason.TOOL_CALLS
        )
        val system = port.requests.single().request.messages.first().content
        assertTrue(system.contains("<function=read>"))
        assertFalse(system.contains("{\"name\":\"read\",\"arguments\":"))
    }

    @Test
    fun `required tool choice without a call fails and with a call succeeds`() {
        val noCallPort = FakeLocalChatPort()
        noCallPort.flowFactory = { _, _ -> eventsFlow(chunkEvent("plain answer"), doneEvent()) }
        val noCall = collect(
            backend(localPort = noCallPort),
            dshRequest(
                tools = listOf(toolDefinition("read")),
                toolChoice = com.muyuchat.core.dsh.contract.DshToolChoice.REQUIRED
            )
        )
        assertEquals(
            DshAdapterErrorCodes.TOOL_REQUIRED_MISSING,
            (noCall.last() as DshModelEvent.Failed).code
        )

        val callPort = FakeLocalChatPort()
        callPort.flowFactory = { _, _ ->
            eventsFlow(chunkEvent(envelope("read" to "{\"path\":\"a\"}")), doneEvent())
        }
        val called = collect(
            backend(localPort = callPort),
            dshRequest(
                tools = listOf(toolDefinition("read")),
                toolChoice = com.muyuchat.core.dsh.contract.DshToolChoice.REQUIRED
            )
        )
        assertEquals(DshFinishReason.TOOL_CALLS, (called.last() as DshModelEvent.Completed).finishReason)
        assertEquals("read", (called.first { it is DshModelEvent.ToolCallDelta } as DshModelEvent.ToolCallDelta).name)
    }

    @Test
    fun `usage keeps known zero and drops estimates instead of faking exact numbers`() {
        val zeroPort = FakeLocalChatPort()
        zeroPort.flowFactory = { _, _ ->
            eventsFlow(doneEvent(promptTokens = 0, completionTokens = 0))
        }
        val zero = collect(backend(localPort = zeroPort), dshRequest()).last() as DshModelEvent.Completed
        assertEquals(0, zero.promptTokens)
        assertEquals(0, zero.completionTokens)

        val estimatedPort = FakeLocalChatPort()
        estimatedPort.flowFactory = { _, _ ->
            eventsFlow(
                doneEvent(
                    promptTokens = 5,
                    completionTokens = 2,
                    promptTokensEstimated = true,
                    completionTokensEstimated = true
                )
            )
        }
        val estimated = collect(backend(localPort = estimatedPort), dshRequest())
            .last() as DshModelEvent.Completed
        assertEquals(null, estimated.promptTokens)
        assertEquals(null, estimated.completionTokens)
    }

    @Test
    fun `native required tool choice without a call is a semantic failure`() {
        val port = FakeCloudChatPort()
        port.flowFactory = { _, _ -> eventsFlow(chunkEvent("plain answer"), doneEvent()) }
        val records = listOf(
            cloudRecord(id = "native", format = CloudApiFormat.OPENAI_RESPONSES, supportsTools = true)
        )
        val events = collect(
            backend(loadedLocalModel = null, localPort = null, cloudRecords = records, cloudPort = port),
            dshRequest(
                modelId = "mca-cloud:native",
                tools = listOf(toolDefinition("read")),
                toolChoice = com.muyuchat.core.dsh.contract.DshToolChoice.REQUIRED
            )
        )
        assertEquals(
            DshAdapterErrorCodes.TOOL_REQUIRED_MISSING,
            (events.last() as DshModelEvent.Failed).code
        )
    }

    @Test
    fun `invalid native tool calls are rejected before any tool delta is emitted`() {
        val records = listOf(
            cloudRecord(id = "native", format = CloudApiFormat.OPENAI_RESPONSES, supportsTools = true)
        )
        fun runNative(
            calls: List<ChatToolCall>,
            limits: DshAdapterLimits = DshAdapterLimits.Default
        ): List<DshModelEvent> {
            val port = FakeCloudChatPort()
            port.flowFactory = { _, _ ->
                eventsFlow(
                    chunkEvent("ok"),
                    doneEvent(toolCalls = calls)
                )
            }
            return collect(
                backend(
                    loadedLocalModel = null,
                    localPort = null,
                    cloudRecords = records,
                    cloudPort = port,
                    limits = limits
                ),
                dshRequest(
                    modelId = "mca-cloud:native",
                    tools = listOf(toolDefinition("read"))
                )
            )
        }

        val duplicateIds = runNative(
            listOf(
                ChatToolCall("call_1", "read", "{}"),
                ChatToolCall("call_1", "read", "{}")
            )
        )
        assertEquals(
            DshAdapterErrorCodes.TOOL_CALL_INVALID,
            (duplicateIds.last() as DshModelEvent.Failed).code
        )
        assertTrue(duplicateIds.none { it is DshModelEvent.ToolCallDelta })

        val unknownName = runNative(listOf(ChatToolCall("call_1", "bash", "{}")))
        assertEquals(
            DshAdapterErrorCodes.TOOL_CALL_INVALID,
            (unknownName.last() as DshModelEvent.Failed).code
        )

        val malformedArguments = runNative(listOf(ChatToolCall("call_1", "read", "not-json")))
        assertEquals(
            DshAdapterErrorCodes.TOOL_CALL_INVALID,
            (malformedArguments.last() as DshModelEvent.Failed).code
        )

        val oversizedArguments = runNative(
            listOf(ChatToolCall("call_1", "read", "{\"path\":\"${"x".repeat(200)}\"}")),
            limits = DshAdapterLimits.Default.copy(maxToolCallArgumentsChars = 64)
        )
        assertEquals(
            DshAdapterErrorCodes.TOOL_ARGUMENTS_TOO_LARGE,
            (oversizedArguments.last() as DshModelEvent.Failed).code
        )
        assertTrue(oversizedArguments.none { it is DshModelEvent.ToolCallDelta })
    }

    @Test
    fun `cloud stream closing without a terminal is an explicit EOF failure`() {
        val port = FakeCloudChatPort()
        port.flowFactory = { _, _ -> eventsFlow(chunkEvent("partial")) }
        val records = listOf(cloudRecord(id = "c1", format = CloudApiFormat.ANTHROPIC))
        val events = collect(
            backend(loadedLocalModel = null, localPort = null, cloudRecords = records, cloudPort = port),
            dshRequest(modelId = "mca-cloud:c1")
        )
        assertEquals(DshModelEvent.TextDelta("partial"), events.first())
        assertEquals(
            DshAdapterErrorCodes.BACKEND_STREAM_CLOSED,
            (events.last() as DshModelEvent.Failed).code
        )
    }
}
