package com.muyuchat.mca.dsh

import android.util.Log
import com.muyuchat.api.dsh.gateway.DshGatewayBackend
import com.muyuchat.core.dsh.contract.DshFinishReason
import com.muyuchat.core.dsh.contract.DshGatewayModel
import com.muyuchat.core.dsh.contract.DshModelEvent
import com.muyuchat.core.dsh.contract.DshModelRequest
import com.muyuchat.core.dsh.contract.DshToolChoice
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.ChatToolCall
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.contract.ErrorCategory
import com.muyuchat.mca.CloudApiConfig
import com.muyuchat.mca.CloudModelKind
import com.muyuchat.mca.CloudModelRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * MCA inference backend for the internal DSH model gateway (R02).
 *
 * Local models run through the one production funnel (`ChatExecutionFacade.streamChat` ->
 * `ChatExecutionOwner` -> `McaInferenceService`) with a deterministic request id derived from the
 * gateway call identity. Cancellation is exact: the injected local chat port marks exactly that
 * request on the supervisor *and* dispatches the exact engine stop, never a global stop. The
 * gateway awaits that dispatch under its own bound (it does not await native settle evidence).
 * Cloud models run through the existing `OpenAiCompatibleChatProvider`; the existing
 * `CloudApiStore` remains the only credential source.
 *
 * Tool semantics are explicit, never faked:
 *  - the local engine has no native tool/template path, so local models always use
 *    [DshPromptedToolProtocol] and the catalog says `nativeToolCalling = false`;
 *  - the OpenAI Responses cloud transport returns real `GenerateEvent.Done.toolCalls`, so it uses
 *    native function calls; every other cloud format uses the same labelled prompted protocol;
 *  - tools are never executed here. DSH owns execution and approval.
 *
 * Each collected flow is one call: it registers its own [DshCallSession], emits one terminal event
 * on every path that settles normally, and unregisters in `finally`. Structural cancellation of
 * the collector is the gateway's own stop signal, so a call cancelled that way emits no second
 * terminal; its session stays bound to that call id only. A late `cancel(callId)` after the call is
 * gone is a no-op, and a call id can never be served twice, so a stale terminal/cancel can never
 * reach a newer call.
 */
internal class McaDshGatewayBackend(
    private val localModels: DshLocalModelAccess?,
    /** All downloaded local models; DSH freezes its provider list per host generation. */
    private val installedLocalModels: () -> List<DshLocalModel> = { emptyList() },
    private val localChat: DshLocalChatPort?,
    private val cloudModels: DshCloudModelAccess?,
    private val cloudChat: DshCloudChatPort?,
    private val limits: DshAdapterLimits = DshAdapterLimits.Default,
    private val registry: DshCallRegistry = DshCallRegistry()
) : DshGatewayBackend {

    /**
     * Live, uncached model catalog: every call re-reads the single engine owner and the existing
     * cloud store, so a model loaded/unloaded after this backend was created is reflected on the
     * gateway's `/v1/models` immediately.
     *
     * This is deliberately NOT a DSH-side refresh: the official `llm-pi-ai` provider freezes its
     * model list from the host's `cordis.patch.yml` snapshot when a host generation starts, and
     * its discovery API only proposes candidates to a settings draft (it is not a catalog
     * refresh). The host therefore has to reseed its overlay / restart the host generation to make
     * a newly loaded model selectable in DSH. See the [DshModelCatalog] lifecycle note; the
     * adapter must never restart or kill a busy host to compensate. Returns an empty list rather
     * than failing when nothing is serveable.
     */
    override suspend fun catalog(): List<DshGatewayModel> {
        val local = localModels?.let { access -> runCatching { access.activeModel() }.getOrNull() }
        val cloud = cloudModels?.let { access -> runCatching { access.chatModels() }.getOrDefault(emptyList()) }
            ?: emptyList()
        val installed = runCatching { installedLocalModels() }.getOrDefault(emptyList())
        return DshModelCatalog.build(local, cloud, installed)
    }

    override fun stream(request: DshModelRequest): Flow<DshModelEvent> = flow {
        validateRequest(request)?.let { rejected ->
            emit(rejected)
            return@flow
        }
        val session = DshCallSession(DshCallIdentity.of(request.callId))
        if (!registry.begin(session)) {
            emit(
                failure(
                    DshAdapterErrorCodes.DUPLICATE_CALL_ID,
                    "This DSH call id was already served; every model call must use a new id.",
                    retryable = false
                )
            )
            return@flow
        }
        try {
            when (val target = resolveTarget(request)) {
                is Target.Rejected -> emit(target.event)
                is Target.Local -> runLocal(session, target.model, request) { event -> emit(event) }
                is Target.Cloud -> runCloud(session, target.record, target.config, request) { event ->
                    emit(event)
                }
            }
        } finally {
            registry.end(session)
        }
    }

    /** Exact-call cancellation; a no-op for unknown or already terminal calls. */
    override suspend fun cancel(callId: String) {
        cancelCall(callId)
    }

    /** Boolean form for tests and diagnostics; the gateway port itself is fire-and-forget. */
    internal suspend fun cancelCall(callId: String): Boolean = registry.cancel(callId)

    // --- target resolution -------------------------------------------------------------------

    private sealed interface Target {
        data class Local(val model: DshLocalModel) : Target
        data class Cloud(val record: CloudModelRecord, val config: CloudApiConfig) : Target
        data class Rejected(val event: DshModelEvent.Failed) : Target
    }

    private suspend fun resolveTarget(request: DshModelRequest): Target {
        val modelId = request.modelId.trim()
        return when {
            modelId.startsWith(DshModelCatalog.LOCAL_ID_PREFIX) -> {
                val model = localModels?.let { access ->
                    runCatching { access.activeModel() }.getOrNull()
                } ?: return Target.Rejected(
                    failure(
                        DshAdapterErrorCodes.MODEL_NOT_AVAILABLE,
                        "No local MCA model is loaded. Load a model in MCA, then retry.",
                        // Retrying cannot load a model: DSH must show the cause immediately instead
                        // of burning its 5 retries (device r35: 1 s retries for >10 minutes).
                        retryable = false
                    )
                )
                if (DshModelCatalog.localModelId(model.modelId) != modelId) {
                    return Target.Rejected(
                        failure(
                            DshAdapterErrorCodes.MODEL_NOT_AVAILABLE,
                            "The requested local model is not the one currently loaded. Reload it in MCA, then retry.",
                            // A stale binding cannot become current by retrying.
                            retryable = false
                        )
                    )
                }
                if (localChat == null) {
                    return Target.Rejected(
                        failure(
                            DshAdapterErrorCodes.MODEL_NOT_AVAILABLE,
                            "Local model execution is not wired in this build.",
                            retryable = false
                        )
                    )
                }
                Target.Local(model)
            }

            modelId.startsWith(DshModelCatalog.CLOUD_ID_PREFIX) -> {
                val recordId = modelId.removePrefix(DshModelCatalog.CLOUD_ID_PREFIX)
                val records = cloudModels?.let { access ->
                    runCatching { access.chatModels() }.getOrDefault(emptyList())
                } ?: emptyList()
                val record = records.firstOrNull {
                    it.id == recordId && it.kind == CloudModelKind.CHAT && it.configured
                } ?: return Target.Rejected(
                    failure(
                        DshAdapterErrorCodes.MODEL_NOT_AVAILABLE,
                        "The requested cloud model is not configured or no longer available.",
                        retryable = true
                    )
                )
                if (cloudChat == null) {
                    return Target.Rejected(
                        failure(
                            DshAdapterErrorCodes.MODEL_NOT_AVAILABLE,
                            "Cloud model execution is not wired in this build.",
                            retryable = false
                        )
                    )
                }
                Target.Cloud(record, record.toChatConfig())
            }

            else -> Target.Rejected(
                failure(
                    DshAdapterErrorCodes.MODEL_NOT_FOUND,
                    "Unknown MCA model id; use a model id from /v1/models.",
                    retryable = false
                )
            )
        }
    }

    // --- local path --------------------------------------------------------------------------

    private suspend fun runLocal(
        session: DshCallSession,
        model: DshLocalModel,
        request: DshModelRequest,
        emit: suspend (DshModelEvent) -> Unit
    ) {
        val port = localChat ?: run {
            emit(notWiredFailure("Local model execution is not wired in this build."))
            return
        }
        val mapped = try {
            DshTranscriptMapper.mapLocal(request, model, limits.forLocalPrompted())
        } catch (mapping: DshMappingException) {
            emit(failure(mapping.code, mapping.safeMessage, mapping.retryable))
            return
        }
        // Installed before dispatch: a cancel that arrived while mapping is applied immediately.
        session.attachCancelHook { port.cancel(session.requestId) }
        if (session.isCancelled()) {
            emit(completed(DshFinishReason.CANCELLED))
            return
        }
        val mappedMessages = mapped.chatRequest.messages
        Log.i(
            "McaDshLocal",
            "dispatch request=${session.requestId} model=${model.modelId} " +
                "messages=${mappedMessages.size} systemChars=" +
                (mappedMessages.firstOrNull { it.role.name == "SYSTEM" }?.content?.length ?: 0) +
                " transcriptChars=${mappedMessages.sumOf { it.content.length }} " +
                "nPredict=${mapped.chatRequest.params.nPredict} " +
                "maxTokens=${request.maxTokens ?: "-"} offered=${mapped.offeredToolNames.size} " +
                "tools=${request.tools.size} toolChoice=${request.toolChoice}"
        )

        val gate = if (mapped.usesPromptedToolProtocol) {
            DshPromptedToolStreamGate(mapped.offeredToolNames, request.callId, limits)
        } else {
            null
        }
        var done: GenerateEvent.Done? = null
        var engineError: GenerateEvent.Error? = null
        val streamedVisible = StringBuilder()
        val admitted = collectLocal(port, mapped.chatRequest, session.requestId) { event ->
            when (event) {
                is GenerateEvent.Chunk -> {
                    if (event.reasoning.isNotEmpty()) {
                        emit(DshModelEvent.ReasoningDelta(event.reasoning))
                    }
                    if (event.text.isNotEmpty()) {
                        if (gate == null) {
                            emit(DshModelEvent.TextDelta(event.text))
                        } else {
                            val visible = gate.feed(event.text)
                            if (visible.isNotEmpty()) {
                                streamedVisible.append(visible)
                                emit(DshModelEvent.TextDelta(visible))
                            }
                        }
                    }
                }

                is GenerateEvent.Done -> done = event
                is GenerateEvent.Error -> engineError = event
                is GenerateEvent.Phase, is GenerateEvent.Persist -> Unit
            }
        }
        if (!admitted) {
            emit(
                failure(
                    DshAdapterErrorCodes.RESOURCE_BUSY,
                    "The local model is busy with another request; retry when it is free.",
                    retryable = true
                )
            )
            return
        }

        val error = engineError
        if (error != null) {
            flushHeldText(gate, emit)
            if (isCancellation(error) || session.isCancelled()) {
                emit(completed(DshFinishReason.CANCELLED))
            } else {
                emit(mapLocalError(error))
            }
            return
        }
        val doneEvent = done
        if (doneEvent == null) {
            flushHeldText(gate, emit)
            emit(
                failure(
                    DshAdapterErrorCodes.BACKEND_STREAM_CLOSED,
                    "The local engine stream closed without a terminal event.",
                    retryable = true
                )
            )
            return
        }
        if (session.isCancelled()) {
            emit(completed(DshFinishReason.CANCELLED, doneEvent.stats))
            return
        }
        if (gate == null) {
            emit(completed(DshFinishReason.STOP, doneEvent.stats))
        } else {
            val parsed = gate.finish()
            val partial = when {
                parsed is DshPromptedParse.Malformed -> parsed.rawEnvelope
                parsed is DshPromptedParse.NoCalls && isProtocolEcho(streamedVisible) ->
                    streamedVisible.toString()
                else -> null
            }
            val repaired = if (partial != null) {
                Log.i(
                    "McaDshLocal",
                    "repair trigger=${if (parsed is DshPromptedParse.Malformed) "malformed" else "echo"} " +
                        "firstPromptTokens=${doneEvent.stats.promptTokens} " +
                        "firstCompletionTokens=${doneEvent.stats.completionTokens}"
                )
                repairPromptedEnvelope(session, mapped, partial, port, emit)
            } else {
                null
            }
            val firstWasEcho = parsed is DshPromptedParse.NoCalls && partial != null
            if (repaired != null) {
                if (firstWasEcho && repaired.parse is DshPromptedParse.NoCalls) {
                    emitProtocolEchoFailure(emit)
                } else {
                    emitPromptedTerminal(repaired.parse, request.toolChoice, repaired.stats, emit)
                }
            } else if (firstWasEcho) {
                emitProtocolEchoFailure(emit)
            } else {
                emitPromptedTerminal(parsed, request.toolChoice, doneEvent.stats, emit)
            }
        }
    }

    /** A repeated protocol guide is a malfunction even after the one allowed repair. */
    private suspend fun emitProtocolEchoFailure(
        emit: suspend (DshModelEvent) -> Unit
    ) {
        emit(
            failure(
                DshAdapterErrorCodes.PROMPTED_TOOL_MALFORMED,
                "The local model repeated the tool protocol guide instead of calling a tool.",
                retryable = true
            )
        )
    }

    /** Bounded single-line excerpt for device diagnosis; never used for control flow. */
    private fun boundedLogExcerpt(raw: String, max: Int = 240): String {
        val escaped = raw.take(max)
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return if (raw.length > max) "$escaped…" else escaped
    }

    /** A protocol guide repeated as prose is a malfunction, not an answer; it is never accepted. */
    private fun isProtocolEcho(text: CharSequence): Boolean {
        val value = text.toString()
        return value.contains("Call rules:") ||
            value.contains("The example arguments are valid JSON") ||
            value.contains("Tool protocol reminder") ||
            value.contains("never use a command word")
    }

    private data class LocalRepair(val parse: DshPromptedParse, val stats: RuntimeStats?)

    /**
     * One bounded continuation for a broken first attempt (truncated envelope or protocol guide
     * echoed as prose). A single strict "finish the block" turn is cheaper than failing the whole
     * agent step, and a second failure falls back to the same typed result. This never runs more
     * than once and never accepts a different protocol shape.
     */
    private suspend fun repairPromptedEnvelope(
        session: DshCallSession,
        mapped: DshMappedRequest,
        partial: String,
        port: DshLocalChatPort,
        emit: suspend (DshModelEvent) -> Unit
    ): LocalRepair? {
        if (mapped.offeredToolNames.isEmpty() || session.isCancelled()) return null
        val repairId = session.requestId + ":repair"
        val missingCloseTag =
            partial.contains("<tool_call") && !partial.contains("</tool_call>")
        val repairInstruction = if (missingCloseTag) {
            REPAIR_MISSING_CLOSE_INSTRUCTION
        } else {
            REPAIR_INSTRUCTION
        }
        Log.i(
            "McaDshLocal",
            "repair request=${session.requestId} cause=${if (missingCloseTag) "missing_close_tag" else "generic"}"
        )
        val messages = mapped.chatRequest.messages + listOf(
            ChatMessage(
                role = Role.ASSISTANT,
                content = partial.take(2_000)
                    .ifBlank { DshPromptedToolProtocol.XML_START },
                createdAt = 0L,
                id = "dsh-repair-assistant"
            ),
            ChatMessage(
                role = Role.USER,
                content = repairInstruction,
                createdAt = 0L,
                id = "dsh-repair-user"
            )
        )
        val gate = DshPromptedToolStreamGate(mapped.offeredToolNames, repairId, limits)
        session.attachCancelHook { port.cancel(repairId) }
        var done: GenerateEvent.Done? = null
        var error: GenerateEvent.Error? = null
        val admitted = collectLocal(port, mapped.chatRequest.copy(messages = messages), repairId) { event ->
            when (event) {
                is GenerateEvent.Chunk -> {
                    if (event.reasoning.isNotEmpty()) {
                        emit(DshModelEvent.ReasoningDelta(event.reasoning))
                    }
                    if (event.text.isNotEmpty()) {
                        val visible = gate.feed(event.text)
                        if (visible.isNotEmpty()) emit(DshModelEvent.TextDelta(visible))
                    }
                }
                is GenerateEvent.Done -> done = event
                is GenerateEvent.Error -> error = event
                is GenerateEvent.Phase, is GenerateEvent.Persist -> Unit
            }
        }
        val terminal = done
        Log.i(
            "McaDshLocal",
            "repair request=${session.requestId} admitted=$admitted error=${error != null} " +
                "done=${terminal != null}"
        )
        if (!admitted || error != null || terminal == null) return null
        return LocalRepair(gate.finish(), terminal.stats)
    }

    /**
     * Collects the engine flow and enforces the admission window.
     *
     * Returns false when no first event arrived inside [DshAdapterLimits.admissionTimeoutMillis]:
     * the adapter cancels exactly its own request (never the global stop) and reports retryable
     * resource-busy instead of waiting behind another channel forever. The engine's own
     * synchronous admission rejection is converted into an error event so the terminal contract
     * still holds.
     */
    private suspend fun collectLocal(
        port: DshLocalChatPort,
        request: ChatRequest,
        requestId: String,
        onEvent: suspend (GenerateEvent) -> Unit
    ): Boolean = coroutineScope {
        val channel = Channel<GenerateEvent>(capacity = 64)
        val pump = launch {
            try {
                port.stream(request, requestId).collect { event -> channel.send(event) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                runCatching {
                    channel.send(
                        GenerateEvent.Error(
                            message = "Local engine stream failed: " +
                                (failure.message ?: failure::class.java.simpleName),
                            stats = RuntimeStats(),
                            code = DshAdapterErrorCodes.MODEL_GENERATION_FAILED
                        )
                    )
                }
            } finally {
                channel.close()
            }
        }
        var normal = false
        try {
            var first = true
            while (true) {
                val received = if (first) {
                    withTimeoutOrNull(limits.admissionTimeoutMillis) { channel.receiveCatching() }
                } else {
                    channel.receiveCatching()
                }
                if (received == null) {
                    runCatching { port.cancel(requestId) }
                    return@coroutineScope false
                }
                val event = received.getOrNull() ?: break
                first = false
                onEvent(event)
            }
            normal = true
            true
        } finally {
            if (!normal) runCatching { port.cancel(requestId) }
            pump.cancel()
        }
    }

    // --- cloud path --------------------------------------------------------------------------

    private suspend fun runCloud(
        session: DshCallSession,
        record: CloudModelRecord,
        config: CloudApiConfig,
        request: DshModelRequest,
        emit: suspend (DshModelEvent) -> Unit
    ) {
        val port = cloudChat ?: run {
            emit(notWiredFailure("Cloud model execution is not wired in this build."))
            return
        }
        val native = DshModelCatalog.supportsNativeCloudTools(record) &&
            request.tools.isNotEmpty() &&
            request.toolChoice != DshToolChoice.NONE
        val mapped = try {
            if (native) {
                DshTranscriptMapper.mapCloudNativeResponses(request, limits)
            } else {
                DshTranscriptMapper.mapCloudPrompted(request, limits.forCloudPrompted())
            }
        } catch (mapping: DshMappingException) {
            emit(failure(mapping.code, mapping.safeMessage, mapping.retryable))
            return
        }
        if (session.isCancelled()) {
            emit(completed(DshFinishReason.CANCELLED))
            return
        }
        val gate = if (mapped.usesPromptedToolProtocol) {
            DshPromptedToolStreamGate(mapped.offeredToolNames, request.callId, limits)
        } else {
            null
        }
        var done: GenerateEvent.Done? = null
        var engineError: GenerateEvent.Error? = null
        var thrown: Throwable? = null
        try {
            port.stream(config, mapped.chatRequest).collect { event ->
                when (event) {
                    is GenerateEvent.Chunk -> {
                        if (event.reasoning.isNotEmpty()) {
                            emit(DshModelEvent.ReasoningDelta(event.reasoning))
                        }
                        if (event.text.isNotEmpty()) {
                            if (gate == null) {
                                emit(DshModelEvent.TextDelta(event.text))
                            } else {
                                val visible = gate.feed(event.text)
                                if (visible.isNotEmpty()) emit(DshModelEvent.TextDelta(visible))
                            }
                        }
                    }

                    is GenerateEvent.Done -> done = event
                    is GenerateEvent.Error -> engineError = event
                    is GenerateEvent.Phase, is GenerateEvent.Persist -> Unit
                }
            }
        } catch (cancelled: CancellationException) {
            // Structural cancellation is the gateway's own signal; it needs no second terminal.
            throw cancelled
        } catch (failure: Throwable) {
            thrown = failure
        }
        val failure = thrown
        if (failure != null) {
            flushHeldText(gate, emit)
            emit(
                failure(
                    DshAdapterErrorCodes.CLOUD_MODEL_ERROR,
                    "Cloud model request failed: " + (failure.message ?: failure::class.java.simpleName),
                    retryable = true
                )
            )
            return
        }
        val error = engineError
        if (error != null) {
            flushHeldText(gate, emit)
            emit(mapCloudError(error.message))
            return
        }
        val doneEvent = done
        if (doneEvent == null) {
            flushHeldText(gate, emit)
            emit(
                failure(
                    DshAdapterErrorCodes.BACKEND_STREAM_CLOSED,
                    "The cloud transport closed without a terminal event.",
                    retryable = true
                )
            )
            return
        }
        if (session.isCancelled()) {
            emit(completed(DshFinishReason.CANCELLED, doneEvent.stats))
            return
        }
        if (native && doneEvent.toolCalls.isNotEmpty()) {
            val invalid = validateNativeToolCalls(doneEvent.toolCalls, request)
            if (invalid != null) {
                emit(invalid)
                return
            }
            doneEvent.toolCalls.forEachIndexed { index, call ->
                emit(
                    DshModelEvent.ToolCallDelta(
                        index = index,
                        id = call.id,
                        name = call.name,
                        argumentsFragment = call.argumentsJson
                    )
                )
            }
            emit(completed(DshFinishReason.TOOL_CALLS, doneEvent.stats))
            return
        }
        if (gate == null) {
            if (request.toolChoice == DshToolChoice.REQUIRED) {
                emit(
                    failure(
                        DshAdapterErrorCodes.TOOL_REQUIRED_MISSING,
                        "The request required a tool call, but the provider returned none.",
                        retryable = true
                    )
                )
            } else {
                emit(completed(DshFinishReason.STOP, doneEvent.stats))
            }
        } else {
            emitPromptedTerminal(gate.finish(), request.toolChoice, doneEvent.stats, emit)
        }
    }

    // --- terminal helpers --------------------------------------------------------------------

    private suspend fun emitPromptedTerminal(
        parsed: DshPromptedParse,
        toolChoice: DshToolChoice,
        stats: RuntimeStats?,
        emit: suspend (DshModelEvent) -> Unit
    ) {
        val shape = when (parsed) {
            is DshPromptedParse.NoCalls -> "noCalls textLen=${parsed.text.length}"
            is DshPromptedParse.Calls -> "calls=${parsed.calls.size}"
            is DshPromptedParse.Malformed ->
                "malformed envelopeLen=${parsed.rawEnvelope.length} " +
                    "raw=${boundedLogExcerpt(parsed.rawEnvelope)}"
            is DshPromptedParse.UnknownTools -> "unknownTools=${parsed.names.size}"
            is DshPromptedParse.TooManyCalls -> "tooManyCalls=${parsed.count}"
            is DshPromptedParse.ArgumentsTooLarge -> "argumentsTooLarge=${parsed.names.size}"
            is DshPromptedParse.ParallelCallsForbidden -> "parallelForbidden=${parsed.count}"
        }
        Log.i(
            "McaDshLocal",
            "terminal shape=$shape promptTokens=${stats?.promptTokens ?: "-"} " +
                "completionTokens=${stats?.completionTokens ?: "-"} " +
                "promptEstimated=${stats?.promptTokensEstimated ?: "-"}"
        )
        when (parsed) {
            is DshPromptedParse.Calls -> {
                parsed.calls.forEachIndexed { index, call ->
                    emit(
                        DshModelEvent.ToolCallDelta(
                            index = index,
                            id = call.id,
                            name = call.name,
                            argumentsFragment = call.argumentsJson
                        )
                    )
                }
                emit(completed(DshFinishReason.TOOL_CALLS, stats))
            }

            is DshPromptedParse.UnknownTools -> emit(
                failure(
                    DshAdapterErrorCodes.PROMPTED_TOOL_UNKNOWN_NAME,
                    "The model requested tools that were not offered: " +
                        parsed.names.joinToString(", ").take(200),
                    retryable = true
                )
            )

            // A malformed / incomplete envelope is a semantic failure: the withheld envelope text
            // is never surfaced as assistant prose and no half tool call is executed.
            is DshPromptedParse.Malformed -> emit(
                failure(
                    DshAdapterErrorCodes.PROMPTED_TOOL_MALFORMED,
                    "The model produced a malformed or incomplete tool-call envelope.",
                    retryable = true
                )
            )

            is DshPromptedParse.TooManyCalls -> emit(
                failure(
                    DshAdapterErrorCodes.TOOL_CALL_INVALID,
                    "The model requested ${parsed.count} tool calls; the adapter accepts at most " +
                        "${limits.maxPromptedToolCalls}.",
                    retryable = true
                )
            )

            is DshPromptedParse.ArgumentsTooLarge -> emit(
                failure(
                    DshAdapterErrorCodes.TOOL_ARGUMENTS_TOO_LARGE,
                    "Tool-call arguments exceed the adapter's per-call limit: " +
                        parsed.names.joinToString(", ").take(200),
                    retryable = false
                )
            )

            is DshPromptedParse.ParallelCallsForbidden -> emit(
                failure(
                    DshAdapterErrorCodes.PROMPTED_TOOL_PARALLEL_FORBIDDEN,
                    "The model requested ${parsed.count} parallel tool calls, but parallel tool " +
                        "calling is disabled for this request.",
                    retryable = true
                )
            )

            is DshPromptedParse.NoCalls -> {
                if (toolChoice == DshToolChoice.REQUIRED) {
                    // Required choice without a call is a semantic failure, not a successful text
                    // answer. The held tail is dropped; nothing executable is produced.
                    emit(
                        failure(
                            DshAdapterErrorCodes.TOOL_REQUIRED_MISSING,
                            "The request required a tool call, but the model answered without one.",
                            retryable = true
                        )
                    )
                } else {
                    if (parsed.text.isNotEmpty()) emit(DshModelEvent.TextDelta(parsed.text))
                    emit(completed(DshFinishReason.STOP, stats))
                }
            }
        }
    }

    /**
     * Rejects native provider tool calls that cannot be executed faithfully before any delta is
     * emitted: count, blank/duplicate ids, non-offered names, arguments that are not one complete
     * JSON object, and arguments over the explicit per-call limit. `tool_choice=REQUIRED` with no
     * calls is handled by [emitPromptedTerminal] for the prompted path; native `Done` without calls
     * is a normal text completion and is not fabricated into a tool call.
     */
    private fun validateNativeToolCalls(
        calls: List<ChatToolCall>,
        request: DshModelRequest
    ): DshModelEvent.Failed? {
        if (calls.size > limits.maxToolCallsPerResponse) {
            return failure(
                DshAdapterErrorCodes.TOOL_CALL_INVALID,
                "The provider returned ${calls.size} tool calls; the adapter accepts at most " +
                    "${limits.maxToolCallsPerResponse}.",
                retryable = false
            )
        }
        val offered = request.tools.mapTo(HashSet()) { it.name }
        val ids = HashSet<String>()
        calls.forEachIndexed { index, call ->
            val id = call.id.trim()
            if (id.isEmpty()) {
                return failure(
                    DshAdapterErrorCodes.TOOL_CALL_INVALID,
                    "The provider returned a tool call without an id (index $index).",
                    retryable = false
                )
            }
            if (!ids.add(id)) {
                return failure(
                    DshAdapterErrorCodes.TOOL_CALL_INVALID,
                    "The provider returned a duplicate tool call id: ${id.take(64)}.",
                    retryable = false
                )
            }
            val name = call.name.trim()
            if (name.isEmpty() || name !in offered) {
                return failure(
                    DshAdapterErrorCodes.TOOL_CALL_INVALID,
                    "The provider returned a tool call for a name that was not offered: ${name.take(64)}.",
                    retryable = false
                )
            }
            val arguments = call.argumentsJson.trim().ifBlank { "{}" }
            if (arguments.length > limits.maxToolCallArgumentsChars) {
                return failure(
                    DshAdapterErrorCodes.TOOL_ARGUMENTS_TOO_LARGE,
                    "A native tool call's arguments exceed the adapter's per-call argument limit.",
                    retryable = false
                )
            }
            runCatching { JSONObject(arguments) }.getOrNull() ?: return failure(
                DshAdapterErrorCodes.TOOL_CALL_INVALID,
                "A native tool call's arguments are not one complete JSON object.",
                retryable = false
            )
        }
        return null
    }

    private suspend fun flushHeldText(
        gate: DshPromptedToolStreamGate?,
        emit: suspend (DshModelEvent) -> Unit
    ) {
        val held = gate?.flushForError().orEmpty()
        if (held.isNotEmpty()) emit(DshModelEvent.TextDelta(held))
    }

    /**
     * Usage honesty: the frozen [DshModelEvent.Completed] has only numeric token fields, so:
     *  - unknown (no stats) -> null;
     *  - known zero -> 0 (never swallowed by a `> 0` filter);
     *  - estimated values -> null, because the contract has no estimated flag and an estimate must
     *    not be presented as exact usage;
     *  - negative values -> null.
     * Preserving estimates needs the additive contract fields requested from the integrator.
     */
    private fun completed(finishReason: DshFinishReason, stats: RuntimeStats? = null): DshModelEvent.Completed =
        DshModelEvent.Completed(
            finishReason = finishReason,
            promptTokens = honestTokens(stats?.promptTokens, stats?.promptTokensEstimated),
            completionTokens = honestTokens(stats?.completionTokens, stats?.completionTokensEstimated)
        )

    private fun honestTokens(value: Int?, estimated: Boolean?): Int? =
        if (value == null || value < 0 || estimated == true) null else value

    private fun isCancellation(error: GenerateEvent.Error): Boolean {
        if (error.errorEnvelope?.category == ErrorCategory.Cancel) return true
        val code = error.code?.trim()?.uppercase() ?: return false
        return code in CANCELLATION_CODES
    }

    private fun mapLocalError(error: GenerateEvent.Error): DshModelEvent.Failed {
        val code = error.code?.trim()?.uppercase()
        return when (code) {
            "CONTEXT_LENGTH_EXCEEDED" -> failure(
                DshAdapterErrorCodes.CONTEXT_LENGTH_EXCEEDED,
                error.message.ifBlank { "The request does not fit the local model context." },
                retryable = false
            )

            "MODEL_NOT_LOADED" -> failure(
                DshAdapterErrorCodes.MODEL_NOT_AVAILABLE,
                error.message.ifBlank { "No local MCA model is loaded. Load a model in MCA, then retry." },
                // The engine does not load a model on demand. A retry loop here watched a real
                // device spin 1 s failures for over ten minutes without ever recovering.
                retryable = false
            )

            "MODEL_RELOAD_REQUIRED",
            "MODEL_RELOAD_REQUIRED_AUTHORIZED",
            "ACTIVE_PROFILE_DRIFT",
            "EXECUTION_OVERRIDE_FORBIDDEN",
            "MODEL_BEHAVIOR_OVERRIDE_FORBIDDEN",
            "MODEL_MISMATCH" -> failure(
                DshAdapterErrorCodes.MODEL_CONFIGURATION_CONFLICT,
                error.message.ifBlank { "The local model configuration rejected this request." },
                retryable = false
            )

            else -> failure(
                code?.lowercase()?.take(64) ?: DshAdapterErrorCodes.MODEL_GENERATION_FAILED,
                error.message.ifBlank { "The local model generation failed." },
                retryable = true
            )
        }
    }

    private fun mapCloudError(message: String): DshModelEvent.Failed {
        val text = message.trim().ifBlank { "The cloud model request failed." }
        if (text.contains("未配置")) {
            return failure(DshAdapterErrorCodes.CLOUD_NOT_CONFIGURED, text, retryable = false)
        }
        val status = HTTP_STATUS_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (status != null) {
            return failure(
                DshAdapterErrorCodes.CLOUD_HTTP_ERROR_PREFIX + status,
                text,
                retryable = status == 429 || status >= 500
            )
        }
        return failure(DshAdapterErrorCodes.CLOUD_MODEL_ERROR, text, retryable = true)
    }

    private fun notWiredFailure(message: String): DshModelEvent.Failed =
        failure(DshAdapterErrorCodes.MODEL_NOT_AVAILABLE, message, retryable = false)

    private fun failure(code: String, message: String, retryable: Boolean): DshModelEvent.Failed =
        DshModelEvent.Failed(
            code = code,
            message = message.take(limits.maxErrorMessageChars),
            retryable = retryable
        )

    // --- request validation ------------------------------------------------------------------

    private fun validateRequest(request: DshModelRequest): DshModelEvent.Failed? {
        if (request.callId.isBlank() || request.callId.length > MAX_CALL_ID_CHARS) {
            return failure(
                DshAdapterErrorCodes.INVALID_REQUEST,
                "The DSH call id is empty or too long.",
                retryable = false
            )
        }
        if (request.modelId.isBlank() || request.modelId.length > MAX_MODEL_ID_CHARS) {
            return failure(
                DshAdapterErrorCodes.INVALID_REQUEST,
                "The DSH request has no usable model id.",
                retryable = false
            )
        }
        if (request.messages.isEmpty()) {
            return failure(
                DshAdapterErrorCodes.INVALID_REQUEST,
                "The DSH request has no messages.",
                retryable = false
            )
        }
        val temperature = request.temperature
        if (temperature != null && (!temperature.isFinite() || temperature < 0.0 || temperature > 2.0)) {
            return failure(
                DshAdapterErrorCodes.INVALID_REQUEST,
                "temperature must be a finite number between 0 and 2.",
                retryable = false
            )
        }
        val maxTokens = request.maxTokens
        if (maxTokens != null && maxTokens < 1) {
            return failure(
                DshAdapterErrorCodes.INVALID_REQUEST,
                "The max token budget must be a positive integer.",
                retryable = false
            )
        }
        if (request.toolChoice == DshToolChoice.REQUIRED && request.tools.isEmpty()) {
            return failure(
                DshAdapterErrorCodes.INVALID_REQUEST,
                "tool_choice=required needs at least one tool definition.",
                retryable = false
            )
        }
        return null
    }

    private companion object {
        const val MAX_CALL_ID_CHARS = 512
        const val MAX_MODEL_ID_CHARS = 512
        const val REPAIR_INSTRUCTION =
            "Your previous reply did not contain a usable tool call. Do not explain or repeat " +
                "any instructions. Reply now with exactly one complete <tool_call> block that " +
                "performs the next action the user asked for, and end with </tool_call>."
        const val REPAIR_MISSING_CLOSE_INSTRUCTION =
            "Your previous block was missing the closing </tool_call> tag. Do not explain or " +
                "repeat any instructions. Repeat the same tool call with a complete JSON " +
                "object, then write </tool_call> as the final text. No other text."

        val CANCELLATION_CODES = setOf(
            "REQUEST_CANCELLED",
            "CANCELLED",
            "CANCELED",
            "CNC_USER_CANCELLED",
            "CNC_ALREADY_CANCELLED"
        )

        val HTTP_STATUS_REGEX = Regex("接口错误\\s+(\\d{3})")
    }
}
