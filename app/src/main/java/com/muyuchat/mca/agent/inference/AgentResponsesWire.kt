package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentModelMessage
import com.muyuchat.core.agent.contract.AgentModelMessageKind
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentTranscriptRole
import com.muyuchat.core.agent.contract.AgentTurnToolCall
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.mca.CloudApiConfig
import com.muyuchat.mca.addCloudApiKeyHeaders
import com.muyuchat.mca.buildOpenAiResponsesJson
import com.muyuchat.mca.responsesEndpointUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Responses wire declarations for the frozen run catalog (N05c).
 *
 * Responses function tools use a flat `{type,name,description,parameters}` shape and a narrower name
 * charset than MCA tool keys (`workspace.read_text`), so the mapping is explicit and deterministic:
 * non `[A-Za-z0-9_-]` characters become `_`, an over-long name is truncated with a content hash, and
 * a collision gets a stable hash suffix. The reverse map is built from the same catalog, never from
 * model output, so a call can only resolve to a tool this run declared.
 */
internal object AgentResponsesToolDeclarations {
    const val MAX_WIRE_NAME = 64
    private const val MAX_TOOLS = 64

    data class Declarations(
        val toolsJson: JSONArray,
        val wireNameToToolKey: Map<String, String>
    )

    /** Returns a safe message when the frozen catalog cannot be declared, otherwise null. */
    fun validate(tools: List<AgentToolDescriptor>): String? {
        if (tools.size > MAX_TOOLS) return "A run may declare at most $MAX_TOOLS tools."
        tools.forEach { descriptor ->
            val parameters = runCatching { JSONObject(descriptor.schemaJson) }.getOrNull()
                ?: return "Tool '${descriptor.toolKey}' has an unreadable schema."
            if (parameters.optString("type") != "object") {
                return "Tool '${descriptor.toolKey}' schema root must be an object."
            }
            if (parameters.opt("additionalProperties") != false) {
                return "Tool '${descriptor.toolKey}' schema must forbid additional properties."
            }
            if (descriptor.description.isBlank()) {
                return "Tool '${descriptor.toolKey}' needs a description for native declarations."
            }
        }
        return null
    }

    fun declarations(tools: List<AgentToolDescriptor>): Declarations {
        val used = HashSet<String>(tools.size * 2)
        val wireToKey = LinkedHashMap<String, String>(tools.size * 2)
        val array = JSONArray()
        tools.sortedBy { it.toolKey }.forEach { descriptor ->
            var wire = sanitize(descriptor.toolKey)
            if (wire in used) {
                wire = withHashSuffix(wire, descriptor.toolKey)
            }
            while (wire in used) wire = withHashSuffix(wire, wire + used.size)
            used += wire
            wireToKey[wire] = descriptor.toolKey
            array.put(
                JSONObject()
                    .put("type", "function")
                    .put("name", wire)
                    .put("description", descriptor.description)
                    .put("parameters", JSONObject(descriptor.schemaJson))
            )
        }
        return Declarations(array, wireToKey)
    }

    private fun sanitize(toolKey: String): String {
        val mapped = toolKey.map { char ->
            if (char.isLetterOrDigit() || char == '_' || char == '-') char else '_'
        }.joinToString("")
        return if (mapped.length <= MAX_WIRE_NAME) {
            mapped
        } else {
            withHashSuffix(mapped.take(MAX_WIRE_NAME - 9), toolKey)
        }
    }

    private fun withHashSuffix(base: String, seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(Charsets.UTF_8))
        val hex = buildString(8) {
            for (index in 0 until 4) append("%02x".format(digest[index]))
        }
        val room = MAX_WIRE_NAME - 9
        return base.take(room) + "_" + hex
    }
}

/**
 * Strict probe over the provider terminal event (N05c).
 *
 * Only `response.completed` is inspected, and only `response.output[*].function_call` items with a
 * usable name/arguments string are converted. Anything else (deltas, reasoning items, unknown
 * types, malformed JSON) is ignored, so a partial stream can never be reconstructed as an executable
 * call.
 */
internal object AgentResponsesTurnProbe {
    fun isCompletedEvent(data: String): Boolean =
        runCatching { JSONObject(data).optString("type") == "response.completed" }.getOrDefault(false)

    fun terminalProof(data: String): String? {
        val root = runCatching { JSONObject(data) }.getOrNull() ?: return null
        if (root.optString("type") != "response.completed") return null
        val status = root.optJSONObject("response")?.optString("status").orEmpty()
        return if (status.isBlank()) "responses:completed" else "responses:$status"
    }

    /**
     * Function calls in terminal order; unknown wire names keep their wire name.
     *
     * A zero-argument tool legitimately streams `arguments: ""`; that is normalized to `{}` so the
     * call still runs (and a tool that requires fields gets the schema validator's typed rejection
     * instead of the call being dropped and the turn silently ending without it).
     */
    fun calls(
        data: String,
        wireNameToToolKey: Map<String, String>
    ): List<AgentTurnToolCall> {
        val root = runCatching { JSONObject(data) }.getOrNull() ?: return emptyList()
        if (root.optString("type") != "response.completed") return emptyList()
        val output = root.optJSONObject("response")?.optJSONArray("output") ?: return emptyList()
        val calls = ArrayList<AgentTurnToolCall>(output.length())
        for (index in 0 until output.length()) {
            val item = output.optJSONObject(index) ?: continue
            functionCall(item, wireNameToToolKey)?.let(calls::add)
        }
        return calls
    }

    private fun functionCall(
        item: JSONObject?,
        wireNameToToolKey: Map<String, String>
    ): AgentTurnToolCall? {
        if (item == null || item.optString("type") != "function_call") return null
        val wireName = item.optString("name")
        val arguments = item.optString("arguments")
        if (wireName.isBlank()) return null
        return AgentTurnToolCall(
            callId = item.optString("call_id").takeIf { it.isNotBlank() },
            name = wireNameToToolKey[wireName] ?: wireName,
            argumentsJson = if (arguments.isBlank()) "{}" else arguments
        )
    }
}

/**
 * Provider input items for one Agent request.
 *
 * Returns null for a purely textual request (the shared chat body's `input` stays authoritative).
 * When the transcript contains native items (N05c), the array preserves transcript order:
 * `function_call` / `function_call_output` items keep their provider call ids, text messages stay
 * plain role messages, and SYSTEM is skipped because the body already carries `instructions`.
 */
internal fun agentResponsesInputJson(messages: List<AgentModelMessage>): JSONArray? {
    if (messages.none { it.kind != AgentModelMessageKind.TEXT }) return null
    val array = JSONArray()
    messages.forEach { message ->
        when (message.kind) {
            AgentModelMessageKind.TEXT -> when (message.role) {
                AgentTranscriptRole.SYSTEM -> Unit
                AgentTranscriptRole.USER,
                AgentTranscriptRole.ASSISTANT -> array.put(
                    JSONObject()
                        .put("role", message.role.name.lowercase())
                        .put("content", message.content)
                )
            }

            AgentModelMessageKind.FUNCTION_CALL -> array.put(
                JSONObject()
                    .put("type", "function_call")
                    .put("call_id", message.callId)
                    .put("name", message.toolName)
                    .put("arguments", message.content)
            )

            AgentModelMessageKind.FUNCTION_OUTPUT -> array.put(
                JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", message.callId)
                    .put("output", message.content)
            )
        }
    }
    return array
}

/**
 * Responses request for one Agent attempt.
 *
 * The shared chat body builder stays authoritative for model/instructions/params; this function only
 * adds the frozen tool declarations (and `tool_choice=auto`) and, for a native transcript, replaces
 * `input` with provider items. Chat requests never call it, so the chat wire is unchanged.
 */
internal fun agentResponsesHttpRequest(
    config: CloudApiConfig,
    chatRequest: ChatRequest,
    tools: List<AgentToolDescriptor>,
    inputItems: JSONArray? = null
): Request {
    val body = buildOpenAiResponsesJson(config, chatRequest, stream = true)
    if (tools.isNotEmpty()) {
        body.put("tools", AgentResponsesToolDeclarations.declarations(tools).toolsJson)
        body.put("tool_choice", "auto")
    }
    if (inputItems != null) {
        body.put("input", inputItems)
    }
    return Request.Builder().url(responsesEndpointUrl(config.baseUrl)).addCloudApiKeyHeaders(config)
        .header("Accept", "text/event-stream")
        .post(
            body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        )
        .build()
}
