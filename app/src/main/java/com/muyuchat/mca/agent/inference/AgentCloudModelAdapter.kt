package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentModelAttemptRef
import com.muyuchat.core.agent.contract.AgentModelPort
import com.muyuchat.core.agent.contract.AgentModelProgress
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentModelRequest
import com.muyuchat.core.agent.contract.AgentModelResult
import com.muyuchat.core.agent.contract.AgentModelUsage
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.agent.contract.AgentStopDispatch
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.mca.CloudApiConfig
import com.muyuchat.mca.CloudApiFormat
import com.muyuchat.mca.CloudApiStore
import com.muyuchat.mca.ResponsesDecoder
import com.muyuchat.mca.responsesHttpRequest
import java.io.Reader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * The only production cloud wire format the Agent accepts (K04).
 *
 * Truthfulness note (N05e): the Responses transport declares the frozen run catalog, reconstructs
 * terminal `function_call` items (provider `call_id` preserved) and sends the paired
 * `function_call_output` on the next turn, so a NATIVE binding really speaks native tools. The
 * chat/completions wire still carries only the prompted envelope, and the capability projection
 * (`AgentNativeToolCapabilities`) is the single authority both the picker and the binding use.
 */
internal fun interface AgentCloudConfigSource {
    fun load(): CloudApiConfig?
}

internal class CloudApiStoreConfigSource(
    private val store: CloudApiStore
) : AgentCloudConfigSource {
    override fun load(): CloudApiConfig? = store.load().takeIf {
        it.configured &&
            (it.apiFormat == CloudApiFormat.OPENAI_RESPONSES ||
                it.apiFormat == CloudApiFormat.OPENAI_COMPATIBLE)
    }
}

internal data class AgentCloudStreamRequest(
    val config: CloudApiConfig,
    val chatRequest: ChatRequest,
    val tools: List<com.muyuchat.core.agent.contract.AgentToolDescriptor> = emptyList(),
    /** Provider input items when the transcript carries native calls/results (N05c). */
    val nativeInput: org.json.JSONArray? = null,
    /** True when the turn expects exactly one prompted JSON object in the visible text. */
    val promptedJson: Boolean = false,
    val maxSseEventChars: Int = MAX_SSE_EVENT_CHARS,
    val maxAttemptChars: Int = MAX_ATTEMPT_CHARS
)

/** Exact per-attempt cancellation handle. Calling it never touches the shared dispatcher. */
internal fun interface AgentCloudCallHandle {
    fun cancel()
}

internal sealed interface AgentCloudStreamTerminal {
    data class Completed(
        val visibleText: String,
        val usage: AgentModelUsage?,
        val calls: List<com.muyuchat.core.agent.contract.AgentTurnToolCall> = emptyList(),
        val terminalProof: String? = null
    ) : AgentCloudStreamTerminal
    data class Failed(
        val code: String,
        val safeMessage: String,
        val retryability: AgentRetryability = AgentRetryability.SAFE_RETRY
    ) : AgentCloudStreamTerminal

    data class EofBeforeTerminal(val visibleText: String) : AgentCloudStreamTerminal
    data object Cancelled : AgentCloudStreamTerminal
}

internal interface AgentCloudStreamTransport {
    suspend fun stream(
        request: AgentCloudStreamRequest,
        onCall: (AgentCloudCallHandle) -> Unit,
        onVisibleDelta: (String) -> Unit
    ): AgentCloudStreamTerminal
}

/**
 * Responses cloud adapter (strict terminal mode; prompted compatibility today, native tools N05c).
 *
 * Strict terminal rules (architecture 5.2.2): success requires `response.completed` with status
 * `completed`, no error and no incomplete reason; EOF before that terminal maps to
 * MODEL_TERMINAL_UNVERIFIED. Every attempt gets its own child Job and its own Call handle; stop
 * cancels only that Call/Job and never `dispatcher.cancelAll()`.
 */
internal class AgentCloudModelAdapter(
    private val configSource: AgentCloudConfigSource,
    private val transport: AgentCloudStreamTransport,
    private val authority: AgentAttemptAuthority = AgentAttemptAuthority.AlwaysCurrent,
    /**
     * Optional prompted wire for `OPENAI_COMPATIBLE` records.
     *
     * A compatible record carries no native tool round, so it runs the prompted envelope over
     * `chat/completions`; when this is null the format stays a typed rejection instead of silently
     * downgrading to a non-streaming or tool-less call.
     */
    private val chatCompletionsTransport: AgentCloudStreamTransport? = null,
    /**
     * Optional request-scoped selection injected by the production routing assembly (P05).
     *
     * When present it is authoritative for every request: a null resolution fails
     * `MODEL_NOT_LOADED` instead of falling back to the store's current chat selection, so a frozen
     * cloud binding can never borrow another record's base URL or credential. When absent this
     * adapter keeps its historical behavior: exactly [configSource] (the current store config).
     */
    private val requestConfig: AgentCloudRequestConfigResolver? = null
) : AgentModelPort {

    private val activeLock = Any()
    private val activeAttempts = linkedMapOf<String, ActiveCloudAttempt>()

    override suspend fun infer(
        request: AgentModelRequest,
        progress: AgentModelProgressSink
    ): AgentModelResult {
        val attempt = request.attempt
        if (request.binding.protocolMode == AgentProtocolMode.NONE) {
            return AgentInferenceErrors.failure(
                code = AgentErrorCodes.MODEL_PROTOCOL_ERROR,
                category = AgentErrorCategory.MODEL,
                safeMessage = "该云模型绑定未启用 Agent 可用协议。",
                attempt = attempt,
                retryability = AgentRetryability.NEVER
            )
        }
        if (authority.currentState(attempt) != AgentAttemptAuthorityState.CURRENT) {
            return AgentModelResult.Cancelled(AgentInferenceErrors.staleAttempt(attempt))
        }
        val config = resolveConfig(request) ?: return AgentInferenceErrors.failure(
            code = AgentErrorCodes.MODEL_NOT_LOADED,
            category = AgentErrorCategory.MODEL,
            safeMessage = "云端 Responses 模型未配置完整。",
            attempt = attempt,
            retryability = AgentRetryability.AFTER_USER_ACTION
        )
        val activeTransport = when {
            config.apiFormat == CloudApiFormat.OPENAI_RESPONSES -> transport
            config.apiFormat == CloudApiFormat.OPENAI_COMPATIBLE ->
                chatCompletionsTransport ?: return AgentInferenceErrors.failure(
                    code = AgentErrorCodes.MODEL_PROTOCOL_ERROR,
                    category = AgentErrorCategory.MODEL,
                    safeMessage = "该 chat/completions 记录没有可用的 Agent 流式线缆。",
                    attempt = attempt,
                    retryability = AgentRetryability.NEVER
                )
            else -> return AgentInferenceErrors.failure(
                code = AgentErrorCodes.MODEL_PROTOCOL_ERROR,
                category = AgentErrorCategory.MODEL,
                safeMessage = "Agent 严格终端模式当前只支持 Responses 与 chat/completions 协议。",
                attempt = attempt,
                retryability = AgentRetryability.NEVER
            )
        }
        val nativeOnPromptedWire = request.binding.protocolMode == AgentProtocolMode.NATIVE &&
            config.apiFormat != CloudApiFormat.OPENAI_RESPONSES
        if (nativeOnPromptedWire) {
            return AgentInferenceErrors.failure(
                code = AgentErrorCodes.MODEL_PROTOCOL_ERROR,
                category = AgentErrorCategory.MODEL,
                safeMessage = "chat/completions 线缆不声明原生工具，NATIVE 绑定被拒绝。",
                attempt = attempt,
                retryability = AgentRetryability.NEVER
            )
        }
        val sampling = when (val parsed = parseAgentSampling(request.samplingJson, request.maxOutputTokens)) {
            is AgentSamplingParse.Accepted -> parsed.params
            is AgentSamplingParse.Rejected -> return AgentInferenceErrors.failure(
                code = AgentErrorCodes.MODEL_BINDING_CHANGED,
                category = AgentErrorCategory.MODEL,
                safeMessage = "采样参数包含不允许的加载绑定字段：${parsed.field}",
                attempt = attempt
            )
        }
        val chatRequest = mapAgentRequestToChatRequest(
            request = request,
            sampling = sampling,
            responseFormatJson = if (request.binding.protocolMode == AgentProtocolMode.PROMPTED_V1) {
                "{\"type\":\"json_object\"}"
            } else {
                null
            }
        )
        if (request.tools.isNotEmpty()) {
            AgentResponsesToolDeclarations.validate(request.tools)?.let { message ->
                return AgentInferenceErrors.failure(
                    code = AgentErrorCodes.MODEL_BINDING_CHANGED,
                    category = AgentErrorCategory.MODEL,
                    safeMessage = message,
                    attempt = attempt,
                    retryability = AgentRetryability.NEVER
                )
            }
        }
        val active = ActiveCloudAttempt(attempt, agentRequestId(attempt))
        synchronized(activeLock) { activeAttempts[attempt.attemptId] = active }
        return try {
            coroutineScope {
                val promptedWire = config.apiFormat != CloudApiFormat.OPENAI_RESPONSES
                val streamJob = async {
                    activeTransport.stream(
                        request = AgentCloudStreamRequest(
                            config = config,
                            chatRequest = chatRequest,
                            // The prompted wire declares no provider-side tools; the catalog stays
                            // in the prompt so the envelope remains the only protocol.
                            tools = if (promptedWire) emptyList() else request.tools,
                            nativeInput = if (promptedWire) null else agentResponsesInputJson(request.messages),
                            promptedJson = request.binding.protocolMode == AgentProtocolMode.PROMPTED_V1
                        ),
                        onCall = { handle -> active.call.set(handle) },
                        onVisibleDelta = { delta ->
                            if (delta.isNotEmpty()) safeProgress(progress, AgentModelProgress.TextDelta(delta))
                        }
                    )
                }
                active.job = streamJob
                safeProgress(
                    progress,
                    AgentModelProgress.Phase(if (promptedWire) "CLOUD_CHAT_COMPLETIONS" else "CLOUD_RESPONSES")
                )
                val terminal = streamJob.await()
                classify(terminal, attempt, request.binding.protocolMode)
            }
        } catch (cancelled: CancellationException) {
            AgentModelResult.Cancelled(null)
        } catch (failure: Throwable) {
            AgentInferenceErrors.failure(
                code = AgentErrorCodes.MODEL_TERMINAL_UNVERIFIED,
                category = AgentErrorCategory.MODEL,
                safeMessage = "云端 Responses 请求失败：" + (failure.message ?: "未知错误").take(160),
                attempt = attempt,
                retryability = AgentRetryability.SAFE_RETRY
            )
        } finally {
            synchronized(activeLock) {
                if (activeAttempts[attempt.attemptId] === active) activeAttempts.remove(attempt.attemptId)
            }
        }
    }

    override suspend fun requestStop(attempt: AgentModelAttemptRef): AgentStopDispatch {
        val active = synchronized(activeLock) {
            activeAttempts[attempt.attemptId]?.takeIf { it.attempt == attempt }
        } ?: return AgentStopDispatch(false, "no live cloud Agent attempt")
        if (!active.stopRequested.compareAndSet(false, true)) {
            return AgentStopDispatch(true, "stop already requested for this attempt")
        }
        val handle = active.call.getAndSet(null)
        handle?.cancel()
        active.job?.cancel()
        return AgentStopDispatch(true, "exact cloud Call/Job cancelled")
    }

    private fun classify(
        terminal: AgentCloudStreamTerminal,
        attempt: AgentModelAttemptRef,
        protocolMode: AgentProtocolMode
    ): AgentModelResult = when (terminal) {
        is AgentCloudStreamTerminal.Completed -> when {
            authority.currentState(attempt) != AgentAttemptAuthorityState.CURRENT ->
                AgentModelResult.Cancelled(AgentInferenceErrors.staleAttempt(attempt))

            protocolMode == AgentProtocolMode.NATIVE && terminal.calls.isNotEmpty() ->
                // N05c receive side: the provider returned complete function calls. A blank
                // visible text is expected for a native tool turn.
                AgentModelResult.Success(
                    visibleText = terminal.visibleText,
                    usage = terminal.usage,
                    turn = com.muyuchat.core.agent.contract.AgentModelTurn(
                        protocol = com.muyuchat.core.agent.contract.AgentTurnProtocol.NATIVE,
                        finish = com.muyuchat.core.agent.contract.AgentTurnFinish.TOOL_CALL,
                        text = terminal.visibleText,
                        calls = terminal.calls,
                        usage = terminal.usage,
                        terminalProof = terminal.terminalProof
                    )
                )

            protocolMode == AgentProtocolMode.NATIVE && terminal.visibleText.isNotBlank() ->
                AgentModelResult.Success(
                    visibleText = terminal.visibleText,
                    usage = terminal.usage,
                    turn = com.muyuchat.core.agent.contract.AgentModelTurn(
                        protocol = com.muyuchat.core.agent.contract.AgentTurnProtocol.NATIVE,
                        finish = com.muyuchat.core.agent.contract.AgentTurnFinish.COMPLETED,
                        text = terminal.visibleText,
                        usage = terminal.usage,
                        terminalProof = terminal.terminalProof
                    )
                )

            terminal.visibleText.isBlank() -> AgentInferenceErrors.failure(
                code = AgentErrorCodes.MODEL_TERMINAL_UNVERIFIED,
                category = AgentErrorCategory.MODEL,
                safeMessage = "Responses 返回完成终态但没有可见正文。",
                attempt = attempt,
                retryability = AgentRetryability.SAFE_RETRY
            )

            else -> AgentModelResult.Success(
                promptedToolText(terminal.visibleText),
                terminal.usage
            )
        }

        is AgentCloudStreamTerminal.EofBeforeTerminal -> AgentInferenceErrors.failure(
            code = AgentErrorCodes.MODEL_TERMINAL_UNVERIFIED,
            category = AgentErrorCategory.MODEL,
            safeMessage = "云端响应在合法终态前结束，EOF 不被当作成功。",
            attempt = attempt,
            retryability = AgentRetryability.SAFE_RETRY
        )

        is AgentCloudStreamTerminal.Failed -> AgentInferenceErrors.failure(
            code = terminal.code,
            category = AgentErrorCategory.MODEL,
            safeMessage = terminal.safeMessage.take(200),
            attempt = attempt,
            retryability = terminal.retryability
        )

        AgentCloudStreamTerminal.Cancelled -> AgentModelResult.Cancelled(null)
    }

    /**
     * Request-scoped record config wins. A null [requestConfig] keeps the legacy default exactly:
     * [configSource] (the current store config) and its existing filtering.
     *
     * Resolution reads SharedPreferences and may unwrap Keystore-encrypted credentials, so it is
     * kept off the owner scope's main dispatcher; the adapter's network wait already lives on IO.
     */
    private suspend fun resolveConfig(request: AgentModelRequest): CloudApiConfig? {
        val resolver = requestConfig
        return withContext(Dispatchers.IO) {
            if (resolver == null) configSource.load() else resolver.resolve(request.binding)
        }
    }

    private fun safeProgress(sink: AgentModelProgressSink, progress: AgentModelProgress) {
        runCatching { sink.onProgress(progress) }
    }

    private class ActiveCloudAttempt(
        val attempt: AgentModelAttemptRef,
        val requestId: String
    ) {
        val stopRequested = java.util.concurrent.atomic.AtomicBoolean(false)
        val call = java.util.concurrent.atomic.AtomicReference<AgentCloudCallHandle?>(null)

        @Volatile
        var job: Job? = null
    }
}

/** Idle (no-byte) limit for a cloud stream; `0` disables it and restores the legacy wait. */
internal const val DEFAULT_CLOUD_STREAM_IDLE_TIMEOUT_MILLIS: Long = 180_000L

/**
 * Bounds the gap between two bytes of a streaming call.
 *
 * The shared production client deliberately sets `readTimeout(0)` so a long answer is never cut;
 * that also means a provider which accepts the request and then goes silent would hold the turn
 * open until the user cancels. A per-call clone keeps the shared client untouched.
 */
internal fun OkHttpClient.withStreamIdleTimeout(idleTimeoutMillis: Long): OkHttpClient =
    if (idleTimeoutMillis <= 0L) {
        this
    } else {
        newBuilder().readTimeout(idleTimeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS).build()
    }

/**
 * Production transport for the OpenAI Responses wire format.
 *
 * Reuses the repository's request builder (`responsesHttpRequest`) and strict protocol decoder
 * (`ResponsesDecoder`), but owns the bounded SSE reader so Agent V1 enforces its own limits:
 * ≤64 KiB per SSE message, ≤1 MiB total response text per attempt, and an idle timeout between
 * bytes ([DEFAULT_CLOUD_STREAM_IDLE_TIMEOUT_MILLIS]) so a silent provider is a typed failure.
 */
internal class OpenAiResponsesBoundedTransport(
    client: OkHttpClient,
    private val clock: AgentInferenceClock = SystemAgentInferenceClock,
    private val idleTimeoutMillis: Long = DEFAULT_CLOUD_STREAM_IDLE_TIMEOUT_MILLIS
) : AgentCloudStreamTransport {
    private val streamClient: OkHttpClient = client.withStreamIdleTimeout(idleTimeoutMillis)

    override suspend fun stream(
        request: AgentCloudStreamRequest,
        onCall: (AgentCloudCallHandle) -> Unit,
        onVisibleDelta: (String) -> Unit
    ): AgentCloudStreamTerminal = withContext(Dispatchers.IO) {
        val call = streamClient.newCall(
            agentResponsesHttpRequest(
                config = request.config,
                chatRequest = request.chatRequest,
                tools = request.tools,
                inputItems = request.nativeInput
            )
        )
        onCall(AgentCloudCallHandle { call.cancel() })
        try {
            call.execute().use { response ->
                val body = response.body
                    ?: return@withContext AgentCloudStreamTerminal.Failed(
                        AgentErrorCodes.MODEL_TERMINAL_UNVERIFIED,
                        "云端 Responses 接口没有返回内容。"
                    )
                if (!response.isSuccessful) {
                    val errorText = runCatching {
                        BoundedSseReader(body.charStream().buffered(), MAX_SSE_EVENT_CHARS, MAX_ATTEMPT_CHARS)
                            .readAllBounded()
                    }.getOrDefault("")
                    return@withContext AgentCloudStreamTerminal.Failed(
                        AgentErrorCodes.MODEL_PROTOCOL_ERROR,
                        "云端 Responses 接口错误 ${response.code}: ${errorText.take(200)}",
                        AgentRetryability.AFTER_USER_ACTION
                    )
                }
                val decoder = ResponsesDecoder()
                val reader = BoundedSseReader(
                    body.charStream().buffered(),
                    request.maxSseEventChars,
                    request.maxAttemptChars
                )
                val visible = StringBuilder()
                val declarations = if (request.tools.isEmpty()) {
                    null
                } else {
                    AgentResponsesToolDeclarations.declarations(request.tools)
                }
                var completedEventData: String? = null
                try {
                    while (!decoder.terminal) {
                        currentCoroutineContext().ensureActive()
                        val event = reader.nextEvent() ?: break
                        if (event.data.isBlank()) continue
                        if (AgentResponsesTurnProbe.isCompletedEvent(event.data)) {
                            completedEventData = event.data
                        }
                        val deltas = decoder.event(event.data, event.eventName)
                        for (delta in deltas) {
                            if (delta.text.isNotEmpty()) {
                                visible.append(delta.text)
                                onVisibleDelta(delta.text)
                            }
                        }
                        if (decoder.error != null) {
                            return@withContext AgentCloudStreamTerminal.Failed(
                                AgentErrorCodes.MODEL_PROTOCOL_ERROR,
                                decoder.error.orEmpty().take(200),
                                AgentRetryability.SAFE_RETRY
                            )
                        }
                    }
                } catch (limit: AgentStreamLimitExceeded) {
                    return@withContext AgentCloudStreamTerminal.Failed(
                        AgentErrorCodes.MODEL_PROTOCOL_ERROR,
                        limit.message ?: "云端响应超过 Agent 接收上限。",
                        AgentRetryability.NEVER
                    )
                }
                when {
                    decoder.error != null -> AgentCloudStreamTerminal.Failed(
                        AgentErrorCodes.MODEL_PROTOCOL_ERROR,
                        decoder.error.orEmpty().take(200)
                    )

                    !decoder.terminal -> AgentCloudStreamTerminal.EofBeforeTerminal(visible.toString())

                    decoder.incompleteReason != null -> AgentCloudStreamTerminal.Failed(
                        AgentErrorCodes.MODEL_TERMINAL_UNVERIFIED,
                        "云端回答未完成（${decoder.incompleteReason}），不授权后续动作。",
                        AgentRetryability.SAFE_RETRY
                    )

                    else -> AgentCloudStreamTerminal.Completed(
                        visible.toString(),
                        AgentModelUsage(
                            inputTokens = decoder.inputTokens,
                            outputTokens = decoder.outputTokens,
                            estimated = decoder.inputTokens == null || decoder.outputTokens == null
                        ),
                        calls = completedEventData?.let { data ->
                            AgentResponsesTurnProbe.calls(
                                data,
                                declarations?.wireNameToToolKey ?: emptyMap()
                            )
                        }.orEmpty(),
                        terminalProof = completedEventData?.let(AgentResponsesTurnProbe::terminalProof)
                    )
                }
            }
        } catch (timeout: java.net.SocketTimeoutException) {
            if (call.isCanceled()) {
                AgentCloudStreamTerminal.Cancelled
            } else {
                // The idle window elapsed with no byte at all: that is a typed, retryable failure,
                // never a silent EOF and never a success.
                AgentCloudStreamTerminal.Failed(
                    AgentErrorCodes.MODEL_TERMINAL_UNVERIFIED,
                    "云端 Responses 在 ${idleTimeoutMillis / 1000} 秒内没有发送任何数据，未取得终止事件。",
                    AgentRetryability.SAFE_RETRY
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: java.io.IOException) {
            if (call.isCanceled()) AgentCloudStreamTerminal.Cancelled
            else AgentCloudStreamTerminal.EofBeforeTerminal("")
        }
    }
}

internal class AgentStreamLimitExceeded(message: String) : Exception(message)

internal data class AgentSseEvent(val eventName: String, val data: String)

/** Minimal SSE reader that enforces per-message and per-attempt character bounds before use. */
internal class BoundedSseReader(
    private val reader: Reader,
    private val maxEventChars: Int,
    private val maxAttemptChars: Int
) {
    private var totalChars = 0

    fun nextEvent(): AgentSseEvent? {
        val data = StringBuilder()
        var eventName = ""
        var sawAny = false
        while (true) {
            val line = readLine()
            if (line == null) {
                return if (sawAny) AgentSseEvent(eventName, data.toString()) else null
            }
            sawAny = true
            when {
                line.isEmpty() -> return AgentSseEvent(eventName, data.toString())
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> eventName = line.substringAfter(':').trim()
                line == "data" || line.startsWith("data:") -> {
                    val chunk = if (line == "data") "" else line.substringAfter(':').removePrefix(" ")
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(chunk)
                    if (data.length > maxEventChars) {
                        throw AgentStreamLimitExceeded("SSE 消息超过 ${maxEventChars / 1024} KiB 上限。")
                    }
                }

                line.startsWith("id:") || line.startsWith("retry:") -> Unit
                else -> Unit
            }
        }
    }

    fun readAllBounded(): String {
        val out = StringBuilder()
        while (true) {
            val line = readLine() ?: return out.toString()
            out.append(line).append('\n')
        }
    }

    private fun readLine(): String? {
        val line = StringBuilder()
        while (true) {
            val value = reader.read()
            if (value < 0) return if (line.isEmpty()) null else line.toString().removeSuffix("\r")
            totalChars += 1
            if (totalChars > maxAttemptChars) {
                throw AgentStreamLimitExceeded(
                    "云端响应超过单次尝试 ${maxAttemptChars / 1024} KiB 上限。"
                )
            }
            if (value == '\n'.code) return line.toString().removeSuffix("\r")
            line.append(value.toChar())
            if (line.length > maxEventChars) {
                throw AgentStreamLimitExceeded("SSE 行超过 ${maxEventChars / 1024} KiB 上限。")
            }
        }
    }

    companion object {
        const val MAX_SSE_EVENT_CHARS = 64 * 1024
        const val MAX_ATTEMPT_CHARS = 1024 * 1024
    }
}

private const val MAX_SSE_EVENT_CHARS = 64 * 1024
private const val MAX_ATTEMPT_CHARS = 1024 * 1024

/**
 * Some OpenAI-compatible providers return a valid tool envelope followed by a second envelope
 * (usually a guessed final answer) in the same message. The prompted protocol requires exactly
 * one root, so without this the whole turn would be rejected and the tool never runs.
 *
 * Narrow by design: only a message that *starts* with a complete tool envelope is trimmed; a
 * final/ask_user first object is never altered.
 */
internal fun promptedToolText(visibleText: String): String {
    val text = visibleText.trimStart()
    if (!text.startsWith("{")) return visibleText
    val first = firstCompleteJsonObject(text) ?: return visibleText
    val parsed = runCatching { org.json.JSONObject(first) }.getOrNull() ?: return visibleText
    val isTool = parsed.optString("protocol") == "mca.agent.v1" &&
        parsed.optString("kind") == "tool" &&
        parsed.optJSONObject("call") != null
    return if (isTool) first else visibleText
}

/** Returns the substring of the first balanced JSON object, or null when braces are unbalanced. */
internal fun firstCompleteJsonObject(text: String): String? {
    if (!text.startsWith("{")) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (index in text.indices) {
        val ch = text[index]
        if (inString) {
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> inString = false
            }
        } else {
            when (ch) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(0, index + 1)
                }
            }
        }
    }
    return null
}
