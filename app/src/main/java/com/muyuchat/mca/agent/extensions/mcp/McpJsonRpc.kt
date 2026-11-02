package com.muyuchat.mca.agent.extensions.mcp

/** JSON-RPC id from an incoming server message; our own request ids are always numeric. */
sealed interface McpRpcId {
    data class NumberId(val value: Long) : McpRpcId
    data class StringId(val value: String) : McpRpcId

    fun toJson(): McpJson = when (this) {
        is NumberId -> McpJson.Num(value.toString())
        is StringId -> McpJson.Str(value)
    }
}

data class McpRpcError(
    val code: Int,
    val message: String,
    val dataJson: String? = null
)

sealed interface McpRpcIncoming {
    data class Response(
        val id: McpRpcId,
        val result: McpJson?,
        val error: McpRpcError?
    ) : McpRpcIncoming

    /** A server-to-client request. V1 rejects unsupported methods with JSON-RPC -32601. */
    data class ServerRequest(
        val id: McpRpcId,
        val method: String,
        val params: McpJson?
    ) : McpRpcIncoming

    data class Notification(
        val method: String,
        val params: McpJson?
    ) : McpRpcIncoming
}

sealed interface McpRpcDecodeResult {
    data class Message(val incoming: McpRpcIncoming) : McpRpcDecodeResult
    data class Malformed(val failure: McpFailure) : McpRpcDecodeResult
}

/**
 * Encode/decode one JSON-RPC 2.0 message. The decoder validates the envelope only; each MCP method
 * validates its own `result`/`params` shape. Protocol errors stay typed and never echo the raw body.
 */
class McpJsonRpc(private val codec: McpJsonCodec = McpJsonCodec()) {

    fun encodeRequest(id: Long, method: String, params: McpJson? = null): String {
        require(id > 0L) { "JSON-RPC request id must be positive" }
        require(method.isNotBlank()) { "JSON-RPC method must not be blank" }
        val fields = linkedMapOf<String, McpJson>(
            "jsonrpc" to McpJson.Str(McpProtocol.JSONRPC_VERSION),
            "id" to McpJson.Num(id.toString()),
            "method" to McpJson.Str(method)
        )
        params?.let { fields["params"] = it }
        return codec.write(McpJson.Obj(fields))
    }

    fun encodeNotification(method: String, params: McpJson? = null): String {
        require(method.isNotBlank()) { "JSON-RPC method must not be blank" }
        val fields = linkedMapOf<String, McpJson>(
            "jsonrpc" to McpJson.Str(McpProtocol.JSONRPC_VERSION),
            "method" to McpJson.Str(method)
        )
        params?.let { fields["params"] = it }
        return codec.write(McpJson.Obj(fields))
    }

    fun encodeResult(id: McpRpcId, result: McpJson): String = codec.write(
        McpJson.obj(
            "jsonrpc" to McpJson.Str(McpProtocol.JSONRPC_VERSION),
            "id" to id.toJson(),
            "result" to result
        )
    )

    fun encodeError(id: McpRpcId, code: Int, message: String): String = codec.write(
        McpJson.obj(
            "jsonrpc" to McpJson.Str(McpProtocol.JSONRPC_VERSION),
            "id" to id.toJson(),
            "error" to McpJson.obj(
                "code" to McpJson.Num(code.toString()),
                "message" to McpJson.Str(message)
            )
        )
    )

    fun decode(text: String): McpRpcDecodeResult {
        val parsed = when (val result = codec.parse(text)) {
            is McpJsonParseResult.Failed -> return McpRpcDecodeResult.Malformed(
                McpFailure.Protocol("mcp_rpc_${result.code}")
            )
            is McpJsonParseResult.Parsed -> result.value
        }
        val root = parsed as? McpJson.Obj
            ?: return McpRpcDecodeResult.Malformed(McpFailure.Protocol("mcp_rpc_not_object"))
        if (root.string("jsonrpc") != McpProtocol.JSONRPC_VERSION) {
            return McpRpcDecodeResult.Malformed(McpFailure.Protocol("mcp_rpc_bad_version"))
        }
        val method = root.string("method")
        val id = try {
            readId(root)
        } catch (_: IllegalArgumentException) {
            return McpRpcDecodeResult.Malformed(McpFailure.Protocol("mcp_rpc_bad_id"))
        }
        if (method != null) {
            if (method.isBlank()) {
                return McpRpcDecodeResult.Malformed(McpFailure.Protocol("mcp_rpc_blank_method"))
            }
            val incoming = if (id == null) {
                McpRpcIncoming.Notification(method, root.fields["params"])
            } else {
                McpRpcIncoming.ServerRequest(id, method, root.fields["params"])
            }
            return McpRpcDecodeResult.Message(incoming)
        }
        if (id == null) {
            return McpRpcDecodeResult.Malformed(McpFailure.Protocol("mcp_rpc_missing_id"))
        }
        val hasResult = root.fields.containsKey("result")
        val errorJson = root.objectOrNull("error")
        if (hasResult == (errorJson != null)) {
            return McpRpcDecodeResult.Malformed(McpFailure.Protocol("mcp_rpc_result_xor_error"))
        }
        val error = errorJson?.let { readError(it) }
        return McpRpcDecodeResult.Message(
            McpRpcIncoming.Response(
                id = id,
                result = root.fields["result"],
                error = error
            )
        )
    }

    private fun readId(root: McpJson.Obj): McpRpcId? = when (val value = root.fields["id"]) {
        null, McpJson.Null -> null
        is McpJson.Num -> value.literal.toLongOrNull()?.let(McpRpcId::NumberId)
            ?: throw IllegalArgumentException("JSON-RPC id is not an integer")
        is McpJson.Str -> value.value.takeIf { it.isNotBlank() }?.let(McpRpcId::StringId)
        else -> null
    }

    private fun readError(json: McpJson.Obj): McpRpcError? {
        val code = json.numberOrNull("code")?.literal?.toIntOrNull()
            ?: return McpRpcError(
                code = McpProtocol.JSONRPC_INVALID_PARAMS,
                message = "Malformed JSON-RPC error."
            )
        val message = json.string("message")?.take(512) ?: "JSON-RPC error."
        return McpRpcError(code, message, json.fields["data"]?.let { codec.write(it) })
    }
}
