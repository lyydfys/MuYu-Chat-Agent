package com.muyuchat.api.dsh.gateway

import com.muyuchat.core.dsh.contract.DshGatewayModel
import com.muyuchat.core.dsh.contract.DshModelEvent
import com.muyuchat.core.dsh.contract.DshModelRequest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** Minimal scriptable backend used by the loopback integration tests. */
class FakeDshGatewayBackend(
    initialModels: List<DshGatewayModel> = listOf(fakeGatewayModel())
) : DshGatewayBackend {

    @Volatile
    var models: List<DshGatewayModel> = initialModels

    @Volatile
    var catalogFailure: Throwable? = null

    @Volatile
    var streamFactory: (DshModelRequest) -> Flow<DshModelEvent> = { flowOf() }

    val requests = CopyOnWriteArrayList<DshModelRequest>()
    val cancelCalls = CopyOnWriteArrayList<String>()

    /**
     * Optional extra work performed inside a cancel dispatch. A test can block here to prove the
     * gateway's cancel-dispatch window is bounded, or complete a gate that lets a scripted flow
     * settle while the cancel is being dispatched.
     */
    @Volatile
    var onCancel: (suspend () -> Unit)? = null

    override suspend fun catalog(): List<DshGatewayModel> {
        catalogFailure?.let { throw it }
        return models
    }

    override fun stream(request: DshModelRequest): Flow<DshModelEvent> = flow {
        requests += request
        emitAll(streamFactory(request))
    }

    override suspend fun cancel(callId: String) {
        cancelCalls += callId
        onCancel?.invoke()
    }

    fun singleRequest(): DshModelRequest = requests.single()
}

internal fun fakeGatewayModel(
    id: String = "fake-model",
    displayName: String = "Fake Model",
    contextWindow: Int? = 8_192,
    maxOutputTokens: Int? = 1_024,
    supportsTools: Boolean = true,
    nativeToolCalling: Boolean = true,
    toolProtocolLabel: String? = null
): DshGatewayModel = DshGatewayModel(
    id = id,
    displayName = displayName,
    contextWindow = contextWindow,
    maxOutputTokens = maxOutputTokens,
    supportsTools = supportsTools,
    nativeToolCalling = nativeToolCalling,
    toolProtocolLabel = toolProtocolLabel
)

internal fun flowOf(vararg events: DshModelEvent): Flow<DshModelEvent> = flow {
    events.forEach { emit(it) }
}

/** Emits [initial] and then never completes; used to observe cancellation and concurrency. */
internal fun neverEndingFlow(vararg initial: DshModelEvent): Flow<DshModelEvent> = flow {
    initial.forEach { emit(it) }
    awaitCancellation()
}

internal fun waitUntil(timeoutMillis: Long = 5_000L, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(10L)
    }
    check(condition()) { "Condition was not met within $timeoutMillis ms." }
}

internal fun chatRequestBody(
    model: String = "fake-model",
    messagesJson: String = """[{"role":"user","content":"hello"}]""",
    stream: Boolean = false,
    includeUsage: Boolean? = null,
    extraFieldsJson: String = ""
): String {
    val root = JSONObject()
        .put("model", model)
        .put("messages", JSONArray(messagesJson))
        .put("stream", stream)
    if (includeUsage != null) {
        root.put("stream_options", JSONObject().put("include_usage", includeUsage))
    }
    if (extraFieldsJson.isNotBlank()) {
        val extra = JSONObject("{" + extraFieldsJson.trim().removePrefix(",") + "}")
        val keys = extra.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            root.put(key, extra.get(key))
        }
    }
    return root.toString()
}

internal fun standardChatBody(
    stream: Boolean,
    includeUsage: Boolean? = if (stream) true else null,
    messagesJson: String = """[{"role":"user","content":"hello"}]""",
    extraFieldsJson: String = ""
): String = chatRequestBody(
    messagesJson = messagesJson,
    stream = stream,
    includeUsage = includeUsage,
    extraFieldsJson = extraFieldsJson
)
