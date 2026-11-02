package com.muyuchat.mca.dsh

import com.muyuchat.core.dsh.contract.DshModelMessage
import com.muyuchat.core.dsh.contract.DshModelRequest
import com.muyuchat.core.dsh.contract.DshModelRole
import com.muyuchat.core.dsh.contract.DshModelToolCall
import com.muyuchat.core.dsh.contract.DshModelToolDefinition
import com.muyuchat.core.dsh.contract.DshToolChoice
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.ChatToolCall
import com.muyuchat.core.engine.ChatToolDefinition
import com.muyuchat.core.engine.ChatToolExchange
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import org.json.JSONObject

/** How the mapped call carries tool calls (or does not carry any). */
internal enum class DshToolMode {
    /** No tools offered or `tool_choice=none`: plain text generation. */
    NONE,

    /** Labelled prompt-compatibility envelope parsed from assistant text. */
    PROMPTED,

    /** Native OpenAI Responses function calls through `ChatRequest.tools`/`toolExchanges`. */
    NATIVE_RESPONSES
}

internal data class DshMappedRequest(
    val chatRequest: ChatRequest,
    val toolMode: DshToolMode,
    val offeredToolNames: List<String>
) {
    val usesPromptedToolProtocol: Boolean
        get() = toolMode == DshToolMode.PROMPTED
}

/** A mapping rejection that is reported as a terminal [com.muyuchat.core.dsh.contract.DshModelEvent.Failed]. */
internal class DshMappingException(
    val code: String,
    val safeMessage: String,
    val retryable: Boolean = false
) : Exception(safeMessage)

/**
 * Pure DSH-transcript -> MCA-[ChatRequest] mapping.
 *
 * Rules that keep the two prompt systems from contaminating each other:
 *  - exactly the DSH system/user/assistant/tool content is used; no MCA chat history, memory,
 *    search, role card, device clock or second persona is appended;
 *  - the local base parameters are derived from the exact active execution profile, so a call
 *    never demands a model reload;
 *  - `persistentSessionId`/`persistentRoleId`/KV-cache identities stay null: the frozen DSH model
 *    contract carries no session identity, so MCA must not reuse another channel's KV state;
 *  - tools are natively carried only on the OpenAI Responses path; the local engine and the other
 *    cloud formats use the labelled [DshPromptedToolProtocol];
 *  - system/user/assistant text is never silently truncated (oversized input fails honestly);
 *    tool results and historical tool arguments are bounded with an explicit truncation marker.
 */
internal object DshTranscriptMapper {

    private val TOOL_NAME_PATTERN = Regex("[A-Za-z0-9_.\\-]{1,128}")
    private const val EMPTY_OBJECT_SCHEMA = "{\"type\":\"object\",\"properties\":{}}"

    fun mapLocal(
        request: DshModelRequest,
        local: DshLocalModel,
        limits: DshAdapterLimits
    ): DshMappedRequest {
        validateToolDefinitions(request.tools, limits, requireNativeObjectSchema = false)
        validateToolHistory(request.messages, limits)
        val mapped = mapPrompted(
            request,
            parseBaseParameters(local.runtimeValuesJson),
            limits,
            local.toolEnvelopeStyle
        )
        // The catalog advertises this exact local budget. A caller that ignores it must not be
        // allowed to ask the engine for more output than the loaded context can hold.
        val capped = local.maxOutputTokens?.takeIf { it > 0 }?.let { ceiling ->
            val params = mapped.chatRequest.params
            if (params.nPredict <= ceiling) {
                mapped
            } else {
                mapped.copy(
                    chatRequest = mapped.chatRequest.copy(
                        params = params.copy(nPredict = ceiling)
                    )
                )
            }
        } ?: mapped
        return capped.withTrailingLocalReminder(local.toolEnvelopeStyle)
    }

    /**
     * Small local models lose the protocol when the guide sits far above several context
     * messages. Keep a short, exact block next to the generation point; cloud models keep the
     * larger catalog-only instruction.
     */
    private fun DshMappedRequest.withTrailingLocalReminder(
        style: DshToolEnvelopeStyle
    ): DshMappedRequest {
        if (toolMode != DshToolMode.PROMPTED || offeredToolNames.isEmpty()) return this
        val index = chatRequest.messages.indexOfLast { it.role == Role.USER }
        if (index < 0) return this
        val name = offeredToolNames.firstOrNull { it == "bash" } ?: offeredToolNames.first()
        val reminder = buildString {
            append("\n\nTool protocol reminder: do not describe a command in prose. To run it, ")
            append("end your reply with exactly this block and nothing after it. ")
            append("Use the user's command or arguments in place of pwd:\n")
            append(DshPromptedToolProtocol.XML_START).append('\n')
            when (style) {
                DshToolEnvelopeStyle.QWEN_JSON -> {
                    append("{\"name\":\"").append(name).append("\",\"arguments\":{")
                    if (name == "bash") append("\"command\":\"pwd\"") else append("\"value\":\"x\"")
                    append("}}\n</tool_call>")
                }
                DshToolEnvelopeStyle.XML -> {
                    append("<function=").append(name).append(">\n")
                    if (name == "bash") {
                        append("<parameter=command>pwd</parameter>\n")
                    } else {
                        append("<parameter=value>x</parameter>\n")
                    }
                    append("</function>\n</tool_call>")
                }
                DshToolEnvelopeStyle.JSON -> {
                    append("\n{\"tool_calls\":[{\"name\":\"").append(name)
                    append("\",\"arguments\":{")
                    if (name == "bash") append("\"command\":\"pwd\"") else append("\"value\":\"x\"")
                    append("}}]}\n")
                    append(DshPromptedToolProtocol.END_MARKER)
                }
            }
        }
        val updated = chatRequest.messages.toMutableList()
        updated[index] = updated[index].copy(content = updated[index].content + reminder)
        return copy(chatRequest = chatRequest.copy(messages = updated))
    }

    fun mapCloudPrompted(request: DshModelRequest, limits: DshAdapterLimits): DshMappedRequest {
        validateToolDefinitions(request.tools, limits, requireNativeObjectSchema = false)
        validateToolHistory(request.messages, limits)
        return mapPrompted(request, GenerationParams(), limits, DshToolEnvelopeStyle.JSON)
    }

    fun mapCloudNativeResponses(request: DshModelRequest, limits: DshAdapterLimits): DshMappedRequest {
        validateToolDefinitions(request.tools, limits, requireNativeObjectSchema = true)
        validateToolHistory(request.messages, limits)
        val split = splitTrailingToolRound(request.messages, limits)
        val messages = ArrayList<ChatMessage>(split.normalMessages.size + 1)
        systemContent(request, nativeToolNote(request), limits)?.let { system ->
            messages += ChatMessage(role = Role.SYSTEM, content = system, createdAt = 0L, id = "dsh-system")
        }
        messages += transcriptMessages(split.normalMessages, limits, idOffset = 0)
        enforceTranscriptBudget(messages, limits)

        val tools = request.tools.map { definition ->
            ChatToolDefinition(
                name = definition.name,
                description = definition.description,
                parametersJson = normalizeNativeSchema(definition)
            )
        }
        return DshMappedRequest(
            chatRequest = baseChatRequest(messages, generationParams(GenerationParams(), request, limits))
                .copy(tools = tools, toolExchanges = split.exchanges),
            toolMode = DshToolMode.NATIVE_RESPONSES,
            offeredToolNames = tools.map { it.name }
        )
    }

    private fun mapPrompted(
        request: DshModelRequest,
        base: GenerationParams,
        limits: DshAdapterLimits,
        style: DshToolEnvelopeStyle
    ): DshMappedRequest {
        val offered = if (request.tools.isEmpty() || request.toolChoice == DshToolChoice.NONE) {
            emptyList()
        } else {
            request.tools
        }
        val catalog = if (offered.isEmpty()) {
            DshPromptedToolCatalog(instruction = "", offeredNames = emptyList(), omittedNames = emptyList())
        } else {
            try {
                DshPromptedToolProtocol.buildInstruction(offered, request.toolChoice, limits, style)
            } catch (tooLarge: DshMappingException) {
                if (tooLarge.code != DshAdapterErrorCodes.TOOL_CATALOG_TOO_LARGE) throw tooLarge
                // Even the fixed protocol text does not fit: degrade to plain chat instead of
                // failing the turn. The model then sees no tool block at all.
                DshPromptedToolCatalog(
                    instruction = "",
                    offeredNames = emptyList(),
                    omittedNames = offered.map { it.name }
                )
            }
        }
        // An over-budget catalog is expected on small local models with the full DSH tool surface.
        // The builder already names every unavailable tool in the instruction and the parser only
        // accepts the offered subset, so the subset is a bounded, honest degradation, not a silent
        // drop. A turn with no representable tool runs as plain chat.
        val instruction = if (catalog.offeredNames.isEmpty()) "" else catalog.instruction
        val messages = ArrayList<ChatMessage>(request.messages.size + 1)
        systemContent(request, instruction, limits)?.let { system ->
            messages += ChatMessage(role = Role.SYSTEM, content = system, createdAt = 0L, id = "dsh-system")
        }
        messages += transcriptMessages(request.messages, limits, idOffset = 0)
        enforceTranscriptBudget(messages, limits)
        val params = generationParams(base, request, limits)
        // A prompted tool turn needs enough room to emit the envelope. DSH's own budget
        // calculation sends max_tokens=1 when it has no advertised output budget; passing that
        // through guarantees an empty/no-call answer, so a degenerate request is raised to a
        // bounded local budget. Larger explicit values are untouched.
        val effectiveParams = if (catalog.offeredNames.isNotEmpty() && params.nPredict <= 1) {
            params.copy(nPredict = localPromptedOutputFloor(params.nCtx))
        } else {
            params
        }
        return DshMappedRequest(
            chatRequest = baseChatRequest(messages, effectiveParams),
            toolMode = if (catalog.offeredNames.isEmpty()) DshToolMode.NONE else DshToolMode.PROMPTED,
            offeredToolNames = catalog.offeredNames
        )
    }

    private fun localPromptedOutputFloor(nCtx: Int): Int =
        if (nCtx > 0) (nCtx / 4).coerceIn(256, 4096) else 4096

    private fun baseChatRequest(messages: List<ChatMessage>, params: GenerationParams): ChatRequest = ChatRequest(
        messages = messages,
        params = params,
        protectedMessageIds = emptySet(),
        runtimeSystemContext = "",
        persistentPrefixSystemPrompt = null,
        persistentSessionId = null,
        tools = emptyList(),
        toolExchanges = emptyList(),
        conversationContextRevision = null,
        persistentRoleId = null,
        persistentRoleCardVersion = null,
        persistentMemoryVersion = null,
        appendReasoningInstruction = false
    )

    /**
     * Base generation parameters for a cloud (or otherwise profile-less) call. Reasoning is OFF
     * because DSH consumes visible text and tool calls, not MCA's hidden reasoning channel.
     */
    private fun generationParams(
        base: GenerationParams,
        request: DshModelRequest,
        limits: DshAdapterLimits
    ): GenerationParams {
        val maxTokens = (request.maxTokens ?: base.nPredict)
            .coerceIn(1, limits.maxOutputTokens)
        val temperature = (request.temperature?.toFloat() ?: base.temperature).coerceIn(0f, 2f)
        return base.copy(
            nPredict = maxTokens,
            temperature = temperature,
            systemPrompt = "",
            reasoningMode = ReasoningMode.OFF,
            hideReasoning = true
        )
    }

    private fun parseBaseParameters(runtimeValuesJson: String): GenerationParams {
        val root = runCatching { JSONObject(runtimeValuesJson.ifBlank { "{}" }) }.getOrNull()
            ?: JSONObject()
        return runCatching { GenerationParams.fromJson(root, GenerationParams()) }
            .getOrDefault(GenerationParams())
    }

    private fun systemContent(
        request: DshModelRequest,
        extraInstruction: String,
        limits: DshAdapterLimits
    ): String? {
        val systemText = request.messages
            .asSequence()
            .filter { it.role == DshModelRole.SYSTEM }
            .mapNotNull { it.content?.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        val parts = listOf(systemText, extraInstruction)
            .filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val content = parts.joinToString("\n\n")
        if (content.length > limits.maxMessageChars) {
            throw DshMappingException(
                DshAdapterErrorCodes.REQUEST_TOO_LARGE,
                "The DSH system prompt exceeds the adapter's per-message limit."
            )
        }
        return content
    }

    private fun nativeToolNote(request: DshModelRequest): String =
        if (request.toolChoice == DshToolChoice.REQUIRED) {
            // The existing Responses transport hardcodes tool_choice=auto, so the one thing the
            // adapter can still enforce natively is the "must call a tool" requirement.
            DshPromptedToolProtocol.REQUIRED_TOOL_NOTE
        } else {
            ""
        }

    private fun transcriptMessages(
        source: List<DshModelMessage>,
        limits: DshAdapterLimits,
        idOffset: Int
    ): List<ChatMessage> {
        val namesByCallId = toolCallNames(source)
        val mapped = ArrayList<ChatMessage>(source.size)
        var index = 0
        source.forEach { message ->
            when (message.role) {
                DshModelRole.SYSTEM -> Unit
                DshModelRole.USER -> {
                    val content = boundRequired(message.content.orEmpty(), limits, "A DSH user message")
                    mapped += ChatMessage(
                        role = Role.USER,
                        content = content,
                        createdAt = 0L,
                        id = "dsh-msg-${idOffset + index}"
                    )
                }
                DshModelRole.ASSISTANT -> {
                    val builder = StringBuilder()
                    message.content?.takeIf { it.isNotBlank() }?.let { builder.append(it) }
                    if (message.toolCalls.isNotEmpty()) {
                        if (builder.isNotEmpty()) builder.append("\n\n")
                        builder.append(historicalToolCalls(message.toolCalls, limits))
                    }
                    if (builder.isNotEmpty()) {
                        val content = boundRequired(builder.toString(), limits, "A DSH assistant message")
                        mapped += ChatMessage(
                            role = Role.ASSISTANT,
                            content = content,
                            createdAt = 0L,
                            id = "dsh-msg-${idOffset + index}"
                        )
                    }
                }
                DshModelRole.TOOL -> {
                    val callId = message.toolCallId.orEmpty()
                    val name = namesByCallId[callId] ?: "unknown"
                    mapped += ChatMessage(
                        role = Role.USER,
                        content = historicalToolResult(callId, name, message.content.orEmpty(), limits),
                        createdAt = 0L,
                        id = "dsh-msg-${idOffset + index}"
                    )
                }
            }
            index += 1
        }
        return mapped
    }

    /** First assistant `tool_calls` name seen for each call id; tool messages carry no name. */
    private fun toolCallNames(messages: List<DshModelMessage>): Map<String, String> {
        val names = LinkedHashMap<String, String>()
        messages.forEach { message ->
            message.toolCalls.forEach { call ->
                names.putIfAbsent(call.id, call.name)
            }
        }
        return names
    }

    private fun historicalToolCalls(
        calls: List<DshModelToolCall>,
        limits: DshAdapterLimits
    ): String {
        // History is context, not a template: a callable-looking history envelope made MiMo copy
        // it instead of `<<<MCA_TOOL_CALLS_V1>>>` (the raw text landed in the transcript and no
        // tool ran). Plain prose cannot be mistaken for the call protocol.
        val block = buildString {
            calls.forEach { call ->
                append("Previously called tool \"")
                append(sanitizeAttribute(call.name))
                append("\" (id ")
                append(sanitizeAttribute(call.id))
                append(") with arguments (context only, not a tool-call format):\n")
                append(call.argumentsJson.trim().ifBlank { "{}" })
                append('\n')
            }
        }.trimEnd()
        return boundWithMarker(block, limits.maxAssistantToolCallChars)
    }

    private fun historicalToolResult(
        callId: String,
        name: String,
        output: String,
        limits: DshAdapterLimits
    ): String = buildString {
        append("Tool \"")
        append(sanitizeAttribute(name))
        append("\" (id ")
        append(sanitizeAttribute(callId))
        append(") returned:\n")
        append(boundWithMarker(output.ifBlank { "(empty)" }, limits.maxToolResultChars))
    }

    private fun boundRequired(text: String, limits: DshAdapterLimits, label: String): String {
        if (text.length > limits.maxMessageChars) {
            throw DshMappingException(
                DshAdapterErrorCodes.REQUEST_TOO_LARGE,
                "$label exceeds the adapter's per-message character limit; shorten the DSH context."
            )
        }
        return text
    }

    private fun boundWithMarker(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars) + "\n[mca:truncated ${text.length - maxChars} chars]"
    }

    private fun enforceTranscriptBudget(messages: List<ChatMessage>, limits: DshAdapterLimits) {
        val total = messages.sumOf { it.content.length.toLong() }
        if (total > limits.maxTotalTranscriptChars.toLong()) {
            throw DshMappingException(
                DshAdapterErrorCodes.REQUEST_TOO_LARGE,
                "The DSH transcript exceeds the adapter's character budget; shorten the DSH context."
            )
        }
    }

    private fun sanitizeAttribute(value: String): String {
        val cleaned = value.filter { it.isLetterOrDigit() || it in "_-.:" }.take(128)
        return cleaned.ifEmpty { "unknown" }
    }

    /**
     * Validates tool-call/result history identity and ordering before any transcript is built:
     *  - every assistant tool call has a non-blank id, and ids are unique across the request;
     *  - a tool result has a non-blank `tool_call_id` that appeared in an assistant tool call
     *    *earlier* in the transcript (so results can never precede or orphan their call);
     *  - at most one result exists per call id;
     *  - call arguments are a complete JSON object within the explicit per-call argument limit.
     *
     * An assistant call without a result is left to the caller's round handling (DSH may trim
     * context); complete result pairing is enforced above. Violations are explicit mapping
     * failures, never silently repaired or reordered.
     */
    private fun validateToolHistory(messages: List<DshModelMessage>, limits: DshAdapterLimits) {
        val callIds = HashSet<String>()
        val resultIds = HashSet<String>()
        messages.forEach { message ->
            message.toolCalls.forEach { call ->
                val callId = call.id.trim()
                if (callId.isEmpty()) throw invalidHistory("A tool call id is missing.")
                if (!callIds.add(callId)) {
                    throw invalidHistory("A tool call id is duplicated: ${callId.take(64)}.")
                }
                val name = call.name.trim()
                if (name.isEmpty() || name.length > limits.maxToolNameChars) {
                    throw invalidHistory("A tool call name is missing or too long.")
                }
                val arguments = call.argumentsJson.trim().ifBlank { "{}" }
                if (arguments.length > limits.maxToolCallArgumentsChars) {
                    throw DshMappingException(
                        DshAdapterErrorCodes.TOOL_ARGUMENTS_TOO_LARGE,
                        "Historical tool-call arguments exceed the adapter's per-call argument limit."
                    )
                }
                runCatching { JSONObject(arguments) }.getOrNull()
                    ?: throw invalidHistory("Historical tool-call arguments are not a JSON object.")
            }
            if (message.role == DshModelRole.TOOL) {
                val resultId = message.toolCallId.orEmpty().trim()
                if (resultId.isEmpty()) throw invalidHistory("A tool result has no tool_call_id.")
                if (resultId !in callIds) {
                    throw invalidHistory(
                        "A tool result references an unknown or not-yet-seen tool call id: `${resultId.take(64)}`."
                    )
                }
                if (!resultIds.add(resultId)) {
                    throw invalidHistory("A tool call id has more than one result: ${resultId.take(64)}.")
                }
            }
        }
    }

    private fun invalidHistory(message: String): DshMappingException = DshMappingException(
        DshAdapterErrorCodes.TOOL_HISTORY_INVALID,
        message,
        retryable = false
    )

    private fun validateToolDefinitions(
        tools: List<DshModelToolDefinition>,
        limits: DshAdapterLimits,
        requireNativeObjectSchema: Boolean
    ) {
        if (tools.size > limits.maxToolDefinitions) {
            throw DshMappingException(
                DshAdapterErrorCodes.TOOL_SCHEMA_INVALID,
                "The request carries more than ${limits.maxToolDefinitions} tool definitions."
            )
        }
        val names = HashSet<String>()
        tools.forEach { definition ->
            val name = definition.name.trim()
            if (name.isEmpty() || name.length > limits.maxToolNameChars ||
                !TOOL_NAME_PATTERN.matches(name)
            ) {
                throw DshMappingException(
                    DshAdapterErrorCodes.TOOL_SCHEMA_INVALID,
                    "A DSH tool name is empty, too long, or contains unsupported characters."
                )
            }
            if (!names.add(name)) {
                throw DshMappingException(
                    DshAdapterErrorCodes.TOOL_SCHEMA_INVALID,
                    "The request carries a duplicate tool definition name."
                )
            }
            val schema = runCatching {
                JSONObject(definition.parametersJson.trim().ifBlank { "{}" })
            }.getOrNull() ?: throw DshMappingException(
                DshAdapterErrorCodes.TOOL_SCHEMA_INVALID,
                "A DSH tool parameter schema is not a JSON object."
            )
            if (requireNativeObjectSchema && schema.length() > 0 && schema.optString("type") != "object") {
                throw DshMappingException(
                    DshAdapterErrorCodes.TOOL_SCHEMA_INVALID,
                    "Native Responses tools require a JSON Schema object with type=object."
                )
            }
        }
    }

    private fun normalizeNativeSchema(definition: DshModelToolDefinition): String {
        val schema = runCatching { JSONObject(definition.parametersJson.trim().ifBlank { "{}" }) }
            .getOrNull() ?: JSONObject()
        return if (schema.length() == 0) EMPTY_OBJECT_SCHEMA else schema.toString()
    }

    private data class NativeSplit(
        val normalMessages: List<DshModelMessage>,
        val exchanges: List<ChatToolExchange>
    )

    /**
     * The Responses transport appends `toolExchanges` at the end of the input, so only the trailing
     * (most recent) tool round can be carried natively without reordering history. Every earlier
     * round is preserved as an ordered text transcript instead. If the trailing round is incomplete
     * the whole history is transcribed so no function call is sent without its output.
     */
    private fun splitTrailingToolRound(
        messages: List<DshModelMessage>,
        limits: DshAdapterLimits
    ): NativeSplit {
        val callIndex = messages.indexOfLast { it.role == DshModelRole.ASSISTANT && it.toolCalls.isNotEmpty() }
        if (callIndex < 0) return NativeSplit(messages, emptyList())
        val callMessage = messages[callIndex]
        val callIds = callMessage.toolCalls.map { it.id }
        if (callIds.toSet().size != callIds.size) return NativeSplit(messages, emptyList())
        val tail = messages.subList(callIndex + 1, messages.size)
        if (tail.isEmpty() || tail.any { it.role != DshModelRole.TOOL }) {
            return NativeSplit(messages, emptyList())
        }
        if (tail.map { it.toolCallId }.toSet() != callIds.toSet() || tail.size != callIds.size) {
            return NativeSplit(messages, emptyList())
        }
        val outputs = tail.associate { it.toolCallId to it.content.orEmpty() }
        val exchanges = callMessage.toolCalls.map { call ->
            ChatToolExchange(
                call = ChatToolCall(id = call.id, name = call.name, argumentsJson = call.argumentsJson),
                output = boundWithMarker(outputs.getValue(call.id), limits.maxToolResultChars)
            )
        }
        val normal = ArrayList<DshModelMessage>(callIndex + 1)
        normal.addAll(messages.subList(0, callIndex))
        if (!callMessage.content.isNullOrBlank()) {
            normal += DshModelMessage(
                role = DshModelRole.ASSISTANT,
                content = callMessage.content
            )
        }
        return NativeSplit(normal, exchanges)
    }
}
