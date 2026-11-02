package com.muyuchat.mca.dsh

import com.muyuchat.api.dsh.gateway.DshGatewayBackend
import com.muyuchat.core.dsh.contract.DshModelEvent
import com.muyuchat.core.dsh.contract.DshModelMessage
import com.muyuchat.core.dsh.contract.DshModelRequest
import com.muyuchat.core.dsh.contract.DshModelRole
import com.muyuchat.core.dsh.contract.DshModelToolCall
import com.muyuchat.core.dsh.contract.DshModelToolDefinition
import com.muyuchat.core.dsh.contract.DshToolChoice
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.ChatToolCall
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.mca.CloudApiConfig
import com.muyuchat.mca.CloudApiFormat
import com.muyuchat.mca.CloudModelKind
import com.muyuchat.mca.CloudModelRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

internal const val TEST_LOCAL_MODEL_ID = "test-model"
internal const val TEST_LOCAL_MODEL_NAME = "mca-local:test-model"
internal const val TEST_CALL_ID = "dsh:7:3"

internal fun localModel(
    modelId: String = TEST_LOCAL_MODEL_ID,
    runtimeValuesJson: String = LOCAL_RUNTIME_VALUES,
    contextWindow: Int? = 4096,
    maxOutputTokens: Int? = null,
    toolEnvelopeStyle: DshToolEnvelopeStyle = DshToolEnvelopeStyle.QWEN_JSON
): DshLocalModel = DshLocalModel(
    modelId = modelId,
    displayName = "Test Local Model",
    contextWindow = contextWindow,
    maxOutputTokens = maxOutputTokens,
    runtimeValuesJson = runtimeValuesJson,
    toolEnvelopeStyle = toolEnvelopeStyle
)

internal const val LOCAL_RUNTIME_VALUES: String =
    "{\"n_ctx\":4096,\"n_threads\":4,\"chat_template_mode\":\"auto\",\"use_jinja\":true}"

internal fun dshRequest(
    callId: String = TEST_CALL_ID,
    modelId: String = TEST_LOCAL_MODEL_NAME,
    messages: List<DshModelMessage> = listOf(userMessage("hello")),
    tools: List<DshModelToolDefinition> = emptyList(),
    toolChoice: DshToolChoice = DshToolChoice.AUTO,
    temperature: Double? = null,
    maxTokens: Int? = null
): DshModelRequest = DshModelRequest(
    callId = callId,
    modelId = modelId,
    messages = messages,
    tools = tools,
    toolChoice = toolChoice,
    temperature = temperature,
    maxTokens = maxTokens
)

internal fun systemMessage(text: String): DshModelMessage =
    DshModelMessage(role = DshModelRole.SYSTEM, content = text)

internal fun userMessage(text: String): DshModelMessage =
    DshModelMessage(role = DshModelRole.USER, content = text)

internal fun assistantMessage(
    text: String?,
    toolCalls: List<DshModelToolCall> = emptyList()
): DshModelMessage = DshModelMessage(
    role = DshModelRole.ASSISTANT,
    content = text,
    toolCalls = toolCalls
)

internal fun toolMessage(callId: String, output: String): DshModelMessage = DshModelMessage(
    role = DshModelRole.TOOL,
    content = output,
    toolCallId = callId
)

internal fun toolCall(
    id: String,
    name: String,
    argumentsJson: String = "{}"
): DshModelToolCall = DshModelToolCall(id = id, name = name, argumentsJson = argumentsJson)

internal fun toolDefinition(
    name: String,
    description: String = "Read a file.",
    parametersJson: String = "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}"
): DshModelToolDefinition = DshModelToolDefinition(
    name = name,
    description = description,
    parametersJson = parametersJson
)

internal fun cloudRecord(
    id: String = "cloud-record-1",
    format: CloudApiFormat = CloudApiFormat.OPENAI_COMPATIBLE,
    kind: CloudModelKind = CloudModelKind.CHAT,
    supportsTools: Boolean = false,
    displayName: String = "Cloud Chat",
    modelName: String = "cloud-model",
    baseUrl: String = "https://example.invalid/v1",
    apiKey: String = "test-key"
): CloudModelRecord = CloudModelRecord(
    id = id,
    kind = kind,
    apiFormat = format,
    providerName = format.label,
    displayName = displayName,
    baseUrl = baseUrl,
    apiKey = apiKey,
    modelName = modelName,
    supportsTools = supportsTools
)

internal fun chunkEvent(text: String): GenerateEvent =
    GenerateEvent.Chunk(text = text, stats = RuntimeStats(loaded = true))

internal fun doneEvent(
    promptTokens: Int = 5,
    completionTokens: Int = 2,
    toolCalls: List<ChatToolCall> = emptyList(),
    promptTokensEstimated: Boolean = false,
    completionTokensEstimated: Boolean = false
): GenerateEvent.Done = GenerateEvent.Done(
    stats = RuntimeStats(
        loaded = true,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        promptTokensEstimated = promptTokensEstimated,
        completionTokensEstimated = completionTokensEstimated
    ),
    toolCalls = toolCalls
)

internal fun errorEvent(code: String?, message: String = "engine failed"): GenerateEvent.Error =
    GenerateEvent.Error(message = message, stats = RuntimeStats(loaded = true), code = code)

internal fun eventsFlow(vararg events: GenerateEvent): Flow<GenerateEvent> = flow {
    events.forEach { emit(it) }
}

internal data class RecordedLocalCall(val requestId: String, val request: ChatRequest)

internal class FakeLocalChatPort : DshLocalChatPort {
    val requests = mutableListOf<RecordedLocalCall>()
    val cancelCalls = mutableListOf<String>()
    var flowFactory: (ChatRequest, String) -> Flow<GenerateEvent> =
        { _, _ -> eventsFlow(doneEvent()) }

    /**
     * Flows scripted for one request id, consulted before [flowFactory].
     *
     * Cancellation tests deliberately install a flow that suspends forever. [flowFactory] is asked
     * for every call, so leaving such a flow there would also serve the next, unrelated call and
     * make it look as if a stopped call had cancelled its successor. Scripting per request id
     * keeps the blocking flow scoped to the call that owns it.
     */
    val flowByRequestId = mutableMapOf<String, Flow<GenerateEvent>>()

    override fun stream(request: ChatRequest, requestId: String): Flow<GenerateEvent> {
        requests += RecordedLocalCall(requestId, request)
        return flowByRequestId[requestId] ?: flowFactory(request, requestId)
    }

    override suspend fun cancel(requestId: String): Boolean {
        cancelCalls += requestId
        return true
    }
}

internal data class RecordedCloudCall(val config: CloudApiConfig, val request: ChatRequest)

internal class FakeCloudChatPort : DshCloudChatPort {
    val requests = mutableListOf<RecordedCloudCall>()
    var flowFactory: (CloudApiConfig, ChatRequest) -> Flow<GenerateEvent> =
        { _, _ -> eventsFlow(doneEvent()) }

    override fun stream(config: CloudApiConfig, request: ChatRequest): Flow<GenerateEvent> {
        requests += RecordedCloudCall(config, request)
        return flowFactory(config, request)
    }
}

internal fun backend(
    loadedLocalModel: DshLocalModel? = localModel(),
    localPort: FakeLocalChatPort? = FakeLocalChatPort(),
    cloudRecords: List<CloudModelRecord> = emptyList(),
    cloudPort: FakeCloudChatPort? = FakeCloudChatPort(),
    limits: DshAdapterLimits = DshAdapterLimits.Default,
    registry: DshCallRegistry = DshCallRegistry()
): McaDshGatewayBackend = McaDshGatewayBackend(
    localModels = loadedLocalModel?.let { model -> DshLocalModelAccess { model } },
    localChat = localPort,
    cloudModels = if (cloudRecords.isEmpty()) null else DshCloudModelAccess { cloudRecords },
    cloudChat = cloudPort,
    limits = limits,
    registry = registry
)

internal fun collect(backend: DshGatewayBackend, request: DshModelRequest): List<DshModelEvent> =
    runBlocking { backend.stream(request).toList() }

internal fun envelope(vararg calls: Pair<String, String>): String = buildString {
    append(DshPromptedToolProtocol.START_MARKER)
    append('\n')
    append("{\"tool_calls\":[")
    calls.forEachIndexed { index, (name, arguments) ->
        if (index > 0) append(',')
        append("{\"name\":\"").append(name).append("\",\"arguments\":").append(arguments).append('}')
    }
    append("]}")
    append('\n')
    append(DshPromptedToolProtocol.END_MARKER)
}
