package com.muyuchat.api.local.a2a

import org.json.JSONArray
import org.json.JSONObject

/**
 * A2A 1.0 protocol surface for MCA (Operit-compatible shape).
 *
 * Scope: only the wire format. No task is executed here — [A2aProtocol] must not grow a dependency on
 * the chat runtime, and the route handler is expected to answer with an explicit "not wired" status
 * until a task bridge exists (see the TODO in `McaLoopbackServer`'s A2A branch).
 */

/** JSON-RPC method names this server accepts. Anything else is rejected at parse time. */
enum class A2aMethod(val wireName: String) {
    SEND_MESSAGE("SendMessage"),
    GET_TASK("GetTask");

    companion object {
        fun fromWire(value: String): A2aMethod? = entries.firstOrNull { it.wireName == value }
    }
}

/**
 * One accepted JSON-RPC request.
 *
 * [id] is null only when the client omitted it, which JSON-RPC rejects for a request (the caller must
 * then answer with a null id) — the parser keeps null so the error carries the same id back.
 */
data class A2aRequest(
    val method: A2aMethod,
    val id: String?,
    /** Free-form argument object; the caller reads `message`/`taskId` from it per method. */
    val params: JSONObject
) {
    /** `params.message` for `SendMessage`, null when absent or blank. */
    val messageText: String?
        get() = params.optJSONObject("message")
            ?.optString("text")
            .orEmpty()
            .trim()
            .takeIf(String::isNotEmpty)

    /** `params.id` (GetTask) or `params.taskId`; both spellings are accepted. */
    val taskId: String?
        get() = (params.optString("id").ifBlank { params.optString("taskId") })
            .trim()
            .takeIf(String::isNotEmpty)
}

/** The agent card served at `/.well-known/agent-card.json` (unauthenticated by design). */
data class AgentCard(
    val name: String,
    val version: String,
    val capabilities: List<String>
) {
    init {
        require(name.isNotBlank()) { "agent card name must not be blank" }
        require(version.isNotBlank()) { "agent card version must not be blank" }
    }

    fun toJson(): String = toJsonObject().toString()

    fun toJsonObject(): JSONObject = JSONObject()
        .put("name", name)
        .put("version", version)
        .put("protocolVersion", A2aProtocol.PROTOCOL_VERSION)
        .put("capabilities", JSONArray(capabilities))
        .put("skills", JSONArray(capabilities))
        .put("transport", JSONObject().put("type", "jsonrpc").put("path", A2aProtocol.RPC_PATH))
        .put(
            "authentication",
            JSONObject().put("scheme", "bearer").put("required", true)
        )
}

/** JSON-RPC / A2A codes returned on failure. */
object A2aErrorCode {
    const val PARSE_ERROR: Int = -32700
    const val INVALID_REQUEST: Int = -32600
    const val METHOD_NOT_FOUND: Int = -32601
    const val INVALID_PARAMS: Int = -32602
    const val INTERNAL_ERROR: Int = -32603
}

/** Pure codec for A2A 1.0 JSON-RPC. No IO, no state. */
object A2aProtocol {

    /** A2A major version this server implements; clients echo it in the `A2A-Version` header. */
    const val PROTOCOL_VERSION: String = "1.0"

    /** Header the client must send; compared case-insensitively by the route. */
    const val VERSION_HEADER: String = "a2a-version"

    /** JSON-RPC endpoint. */
    const val RPC_PATH: String = "/a2a"

    /** Unauthenticated discovery doc. */
    const val AGENT_CARD_PATH: String = "/.well-known/agent-card.json"

    /**
     * Parses a JSON-RPC 2.0 envelope whose `method` is one of [A2aMethod].
     *
     * Returns null for: non-JSON, non-object, missing/non-string method, an unknown or unsupported
     * method, a missing `jsonrpc` other than "2.0", and a non-object `params`. It never throws, so the
     * route can always answer with a typed JSON-RPC error.
     */
    fun parseJsonRpc(body: String): A2aRequest? {
        if (body.isBlank()) return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (root.optString("jsonrpc") != "2.0") return null
        val rawMethod = root.optString("method")
        val method = A2aMethod.fromWire(rawMethod) ?: return null
        val params = when (val value = root.opt("params")) {
            null, JSONObject.NULL -> JSONObject()
            is JSONObject -> value
            else -> return null
        }
        val id = when {
            root.isNull("id") -> null
            root.has("id") -> root.opt("id").toString()
            else -> null
        }
        return A2aRequest(method = method, id = id, params = params)
    }

    /** Success envelope: `{"jsonrpc":"2.0","id":...,"result":{...}}`. */
    fun jsonRpcResult(id: String?, result: JSONObject): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("result", result)
        .toString()

    /** Error envelope with the canonical `error.code` / `error.message` shape. */
    fun jsonRpcError(id: String?, code: Int, message: String): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put(
            "error",
            JSONObject()
                .put("code", code)
                .put("message", message)
        )
        .toString()

    /** True when the client's `A2A-Version` header is acceptable for [PROTOCOL_VERSION]. */
    fun acceptsVersion(header: String?): Boolean {
        val raw = header?.trim().orEmpty()
        if (raw.isEmpty()) return false
        // Accept an exact major match ("1.0") and the bare major ("1"), reject anything else.
        return raw == PROTOCOL_VERSION || raw == PROTOCOL_VERSION.substringBefore('.')
    }

    /**
     * **TODO:** hand [request] to the agent task bridge.
     *
     * Not implemented on purpose: executing a task needs the chat runtime (run owner, budget, policy)
     * which this module must not depend on. Until that bridge exists the route answers with
     * [A2aErrorCode.INTERNAL_ERROR] and the message `a2a_task_bridge_not_implemented`, rather than
     * pretending a task was accepted.
     */
    const val TASK_BRIDGE_NOT_IMPLEMENTED: String = "a2a_task_bridge_not_implemented"
}
