package com.muyuchat.mca.agent.extensions.mcp

import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Credential lookup indirection: the transport receives only the resolved bearer value. */
fun interface McpCredentialProvider {
    fun bearerToken(credentialRefId: String): String?
}

/** Result of one MCP operation; failures are typed and never contain a raw server body. */
sealed interface McpResult<out T> {
    data class Ok<T>(val value: T) : McpResult<T>
    data class Failed(val failure: McpFailure) : McpResult<Nothing>
}

/**
 * Tools-only MCP Streamable HTTP client (protocol 2025-06-18 pin).
 *
 * Scope and hard limits:
 * - `initialize` -> `notifications/initialized` -> `tools/list` (<=10 pages / <=1,000 tools);
 * - JSON and POST-returned SSE responses; independent GET streams and legacy HTTP+SSE are not used;
 * - a session id is scoped to this server/origin; a 404 after a session was established is reported
 *   as [McpFailure.SessionExpired]. A **read-only** operation (`tools/list`) is retried at most once
 *   after a single bounded reconnect; a `tools/call` is **never** replayed (a remote side effect
 *   cannot be un-sent), it only triggers the reconnect so the next call has a fresh session;
 * - a `SecretReference` credential that no longer resolves is refused before any HTTP request with
 *   [McpFailure.Authentication] (`mcp_credential_revoked`), so a revoked credential can never be
 *   silently downgraded to an anonymous request;
 * - no OAuth discovery/registration and no automatic retry of side-effecting calls;
 * - one outbound request at a time per client (a [Mutex]); cancellation uses the caller's
 *   coroutine + per-call abort, never a dispatcher-wide cancel.
 */
class McpClient(
    private val config: McpServerConfig,
    private val transport: McpHttpTransport,
    private val credentials: McpCredentialProvider = McpCredentialProvider { null },
    private val rpc: McpJsonRpc = McpJsonRpc(),
    private val requestTimeoutMillis: Long = McpProtocol.DEFAULT_DEADLINE_MILLIS
) : McpToolCaller {
    private val operationLock = Mutex()
    private val nextId = AtomicLong(1L)
    private var session: McpSession? = null
    private var pendingSessionId: String? = null
    private var tools: List<McpRemoteTool> = emptyList()

    /** Observability of the single bounded reconnect; never model text and never a credential. */
    @Volatile
    var reconnectCount: Long = 0L
        private set

    /** Stable cause of the last reconnect (`mcp_session_expired` / `mcp_session_expired_call`). */
    @Volatile
    var lastReconnectReason: String? = null
        private set

    fun currentTools(): List<McpRemoteTool> = tools

    fun currentSession(): McpSession? = session

    override fun currentSchemaJson(remoteName: String): String? =
        tools.firstOrNull { it.remoteName == remoteName }?.inputSchemaJson

    override fun currentSchemaHash(remoteName: String): String? =
        tools.firstOrNull { it.remoteName == remoteName }?.schemaHash

    /**
     * Full handshake + tool discovery. Any failure leaves the client disconnected and never exposes
     * a partial tool list as complete.
     */
    suspend fun connect(): McpResult<McpSession> = operationLock.withLock { connectLocked() }

    /**
     * Handshake while the operation lock is already held. Callers inside the lock must use this
     * variant: [Mutex] is not reentrant, so calling [connect] from here would deadlock.
     */
    private suspend fun connectLocked(): McpResult<McpSession> {
        missingCredentialFailure()?.let { return McpResult.Failed(it) }
        validateEndpoint()?.let { return McpResult.Failed(it) }
        resetLocalState()
        val initialize = rpcExchange(
            method = McpProtocol.METHOD_INITIALIZE,
            params = McpJson.obj(
                "protocolVersion" to McpJson.Str(McpProtocol.VERSION_2025_06_18),
                "capabilities" to McpJson.obj(),
                "clientInfo" to McpJson.obj(
                    "name" to McpJson.Str("mca-agent"),
                    "version" to McpJson.Str("1")
                )
            ),
            useSession = false
        )
        if (initialize is McpResult.Failed) return initialize
        val initializeResult = (initialize as McpResult.Ok).value.result as? McpJson.Obj
            ?: return McpResult.Failed(McpFailure.Protocol("mcp_initialize_missing_result"))
        val version = initializeResult.string("protocolVersion").orEmpty()
        if (version !in McpProtocol.SUPPORTED_VERSIONS) {
            return McpResult.Failed(McpFailure.UnsupportedCapability("mcp_version_unsupported"))
        }
        val capabilities = initializeResult.objectOrNull("capabilities")
            ?: return McpResult.Failed(McpFailure.Protocol("mcp_initialize_missing_capabilities"))
        val toolsCapability = capabilities.objectOrNull("tools")
            ?: return McpResult.Failed(McpFailure.UnsupportedCapability("mcp_tools_unsupported"))
        pendingSessionId = initialize.value.sessionId
        val notified = notify(McpProtocol.METHOD_INITIALIZED)
        if (notified is McpResult.Failed) {
            resetLocalState()
            return notified
        }
        session = McpSession(
            serverId = config.serverId,
            endpointOrigin = canonicalOrigin(config.endpoint),
            sessionId = pendingSessionId,
            negotiatedVersion = version,
            toolsCapability = true,
            listChanged = toolsCapability.boolOrNull("listChanged") ?: false
        )
        val listed = listAllTools()
        if (listed is McpResult.Failed) {
            resetLocalState()
            return listed
        }
        val discovered = (listed as McpResult.Ok).value
        if (discovered.isEmpty()) {
            resetLocalState()
            return McpResult.Failed(McpFailure.Protocol("mcp_no_tools"))
        }
        tools = discovered
        return McpResult.Ok(session!!)
    }

    /**
     * One bounded reconnect after the server forgot the session. Returns the original failure when
     * the reconnect itself fails, so the caller sees the real cause instead of a generic one.
     */
    private suspend fun reconnectLocked(reason: String): McpResult<McpSession> {
        reconnectCount += 1L
        lastReconnectReason = reason
        return connectLocked()
    }

    /**
     * Re-discovers tools without replaying any previous `tools/call`.
     *
     * A `SessionExpired` (HTTP 404 after a session existed) triggers exactly one reconnect and one
     * fresh `tools/list`, because listing is read-only and idempotent. When the reconnect also fails
     * the original typed failure is returned and the local session stays cleared.
     */
    suspend fun refreshTools(): McpResult<List<McpRemoteTool>> = operationLock.withLock {
        if (session == null) return@withLock McpResult.Failed(McpFailure.Protocol("mcp_not_connected"))
        val listed = listAllTools()
        if (listed is McpResult.Ok) {
            tools = listed.value
            return@withLock listed
        }
        val failure = (listed as McpResult.Failed).failure
        if (failure !is McpFailure.SessionExpired) return@withLock listed
        // A 404 while refreshing means the server forgot the session; clear the local identity
        // before reconnecting so no request can present the stale session id.
        resetLocalState()
        if (reconnectLocked(RECONNECT_REASON_SESSION_EXPIRED) is McpResult.Failed) {
            return@withLock McpResult.Failed(failure)
        }
        McpResult.Ok(tools)
    }

    override suspend fun callTool(
        remoteName: String,
        argumentsJson: String
    ): McpToolCallOutcome = operationLock.withLock {
        if (session == null) return@withLock McpToolCallOutcome.Failed(McpFailure.Protocol("mcp_not_connected"))
        val tool = tools.firstOrNull { it.remoteName == remoteName }
            ?: return@withLock McpToolCallOutcome.Failed(McpFailure.Protocol("mcp_tool_not_found"))
        val arguments = when (val parsed = codec.parse(argumentsJson)) {
            is McpJsonParseResult.Parsed -> parsed.value as? McpJson.Obj
                ?: return@withLock McpToolCallOutcome.Failed(McpFailure.Protocol("mcp_arguments_not_object"))
            is McpJsonParseResult.Failed -> return@withLock McpToolCallOutcome.Failed(
                McpFailure.Protocol("mcp_arguments_invalid")
            )
        }
        val params = McpJson.obj(
            "name" to McpJson.Str(tool.remoteName),
            "arguments" to arguments
        )
        when (val outcome = rpcExchange(McpProtocol.METHOD_TOOLS_CALL, params, useSession = true)) {
            is McpResult.Failed -> {
                if (outcome.failure is McpFailure.SessionExpired) {
                    resetLocalState()
                    // Recover the session for the *next* call, but never replay this one: a remote
                    // side effect cannot be proven absent, so the caller gets a paired failure and
                    // the executor reports a possibly-unknown outcome.
                    reconnectLocked(RECONNECT_REASON_SESSION_EXPIRED_CALL)
                }
                McpToolCallOutcome.Failed(outcome.failure)
            }
            is McpResult.Ok -> mapCallResult(outcome.value.result)
        }
    }

    /** Best-effort cancellation notification; the caller still aborts its own HTTP call. */
    suspend fun cancel(requestId: Long, reason: String = "cancelled") {
        if (session == null) return
        val params = McpJson.obj(
            "requestId" to McpJson.Num(requestId.toString()),
            "reason" to McpJson.Str(reason.take(200))
        )
        operationLock.withLock { notify(McpProtocol.METHOD_CANCELLED, params) }
    }

    /**
     * Closes the local session first, then attempts HTTP DELETE on the captured session. A 405 or
     * any failure only means the server does not support termination; local state is already clear.
     */
    suspend fun disconnect() {
        val closed = operationLock.withLock {
            val current = session
            resetLocalState()
            current
        }
        if (closed?.sessionId != null) {
            runCatching {
                withTimeoutOrNull(requestTimeoutMillis) {
                    transport.deleteSession(httpPost(sessionId = closed.sessionId).copy(bodyJson = ""))
                }
            }
        }
    }

    private suspend fun listAllTools(): McpResult<List<McpRemoteTool>> {
        val collected = ArrayList<McpRemoteTool>()
        val seenNames = HashSet<String>()
        var cursor: String? = null
        var pages = 0
        var totalBytes = 0
        do {
            pages += 1
            if (pages > McpProtocol.MAX_TOOL_LIST_PAGES) {
                return McpResult.Failed(McpFailure.SizeLimit("mcp_tools_pages_exceeded"))
            }
            val params = if (cursor == null) {
                McpJson.obj()
            } else {
                McpJson.obj("cursor" to McpJson.Str(cursor))
            }
            val outcome = rpcExchange(McpProtocol.METHOD_TOOLS_LIST, params, useSession = true)
            if (outcome is McpResult.Failed) return outcome
            val result = (outcome as McpResult.Ok).value.result as? McpJson.Obj
                ?: return McpResult.Failed(McpFailure.Protocol("mcp_tools_list_missing_result"))
            val toolsJson = result.arrayOrNull("tools")
                ?: return McpResult.Failed(McpFailure.Protocol("mcp_tools_list_missing_tools"))
            for (element in toolsJson.items) {
                val toolJson = element as? McpJson.Obj
                    ?: return McpResult.Failed(McpFailure.Protocol("mcp_tool_not_object"))
                val remoteName = toolJson.string("name")?.trim().orEmpty()
                if (remoteName.isBlank() || remoteName.length > McpProtocol.MAX_NAME_LENGTH) {
                    return McpResult.Failed(McpFailure.Protocol("mcp_tool_name_invalid"))
                }
                if (!seenNames.add(remoteName)) {
                    return McpResult.Failed(McpFailure.Protocol("mcp_tool_duplicate"))
                }
                val schema = toolJson.objectOrNull("inputSchema")
                    ?: return McpResult.Failed(McpFailure.Protocol("mcp_tool_schema_missing"))
                val schemaJson = codec.write(schema)
                val schemaBytes = schemaJson.toByteArray(Charsets.UTF_8).size
                if (schemaBytes > McpProtocol.MAX_TOOL_SCHEMA_BYTES) {
                    return McpResult.Failed(McpFailure.SizeLimit("mcp_tool_schema_too_large"))
                }
                totalBytes += schemaBytes
                if (totalBytes > McpProtocol.MAX_TOOL_LIST_BYTES) {
                    return McpResult.Failed(McpFailure.SizeLimit("mcp_tool_list_too_large"))
                }
                val annotations = toolJson.objectOrNull("annotations")
                collected += McpRemoteTool(
                    serverId = config.serverId,
                    remoteName = remoteName,
                    displayName = toolJson.string("title")?.take(256) ?: remoteName,
                    description = toolJson.string("description")?.take(4 * 1024).orEmpty(),
                    inputSchemaJson = schemaJson,
                    schemaHash = sha256(schemaJson),
                    readOnlyHint = annotations?.boolOrNull("readOnlyHint"),
                    destructiveHint = annotations?.boolOrNull("destructiveHint")
                )
                if (collected.size > McpProtocol.MAX_TOOLS) {
                    return McpResult.Failed(McpFailure.SizeLimit("mcp_tools_exceeded"))
                }
            }
            cursor = result.string("nextCursor")?.takeIf { it.isNotBlank() }
        } while (cursor != null)
        return McpResult.Ok(collected)
    }

    private suspend fun rpcExchange(
        method: String,
        params: McpJson?,
        useSession: Boolean
    ): McpResult<McpResponse> {
        missingCredentialFailure()?.let { return McpResult.Failed(it) }
        val requestId = nextId.getAndIncrement()
        val body = rpc.encodeRequest(requestId, method, params)
        val post = httpPost(sessionId = if (useSession) session?.sessionId else null)
        val outcome = withTimeoutOrNull(requestTimeoutMillis) {
            transport.post(post.copy(bodyJson = body))
        } ?: return McpResult.Failed(McpFailure.Timeout("mcp_timeout"))
        val messages = when (outcome) {
            is McpHttpResult.Failed -> return McpResult.Failed(outcome.failure)
            is McpHttpResult.Accepted -> return McpResult.Failed(
                McpFailure.Protocol("mcp_unexpected_empty_result")
            )
            is McpHttpResult.Json -> listOf(outcome.body)
            is McpHttpResult.EventStream -> outcome.events
        }
        val responseSessionId = when (outcome) {
            is McpHttpResult.Json -> outcome.sessionId
            is McpHttpResult.EventStream -> outcome.sessionId
            else -> null
        }
        var matched: McpRpcIncoming.Response? = null
        for (message in messages) {
            when (val decoded = rpc.decode(message)) {
                is McpRpcDecodeResult.Malformed -> return McpResult.Failed(decoded.failure)
                is McpRpcDecodeResult.Message -> when (val incoming = decoded.incoming) {
                    is McpRpcIncoming.Notification -> Unit
                    is McpRpcIncoming.ServerRequest -> {
                        // V1 declares no client capabilities; reject unknown server requests.
                        val rejection = rpc.encodeError(
                            incoming.id,
                            McpProtocol.JSONRPC_METHOD_NOT_FOUND,
                            "Method not found"
                        )
                        runCatching { transport.post(post.copy(bodyJson = rejection)) }
                        return McpResult.Failed(McpFailure.UnsupportedCapability("mcp_server_request_unsupported"))
                    }
                    is McpRpcIncoming.Response -> {
                        val responseId = (incoming.id as? McpRpcId.NumberId)?.value
                        if (responseId != requestId) {
                            return McpResult.Failed(McpFailure.Protocol("mcp_response_id_mismatch"))
                        }
                        if (matched != null) {
                            return McpResult.Failed(McpFailure.Protocol("mcp_multiple_responses"))
                        }
                        matched = incoming
                    }
                }
            }
        }
        val response = matched ?: return McpResult.Failed(McpFailure.Protocol("mcp_missing_response"))
        if (response.error != null) {
            return McpResult.Failed(McpFailure.Protocol("mcp_rpc_error_${response.error.code}"))
        }
        return McpResult.Ok(McpResponse(result = response.result, sessionId = responseSessionId))
    }

    private suspend fun notify(method: String, params: McpJson? = null): McpResult<Unit> {
        missingCredentialFailure()?.let { return McpResult.Failed(it) }
        val body = rpc.encodeNotification(method, params)
        val outcome = withTimeoutOrNull(requestTimeoutMillis) {
            transport.post(httpPost(sessionId = session?.sessionId).copy(bodyJson = body))
        } ?: return McpResult.Failed(McpFailure.Timeout("mcp_timeout"))
        return when (outcome) {
            is McpHttpResult.Failed -> McpResult.Failed(outcome.failure)
            else -> McpResult.Ok(Unit)
        }
    }

    private fun mapCallResult(result: McpJson?): McpToolCallOutcome {
        val root = result as? McpJson.Obj
            ?: return McpToolCallOutcome.Failed(McpFailure.Protocol("mcp_call_missing_result"))
        val isError = root.boolOrNull("isError") ?: false
        val content = root.arrayOrNull("content")
        val texts = ArrayList<String>()
        val unsupported = ArrayList<String>()
        var totalBytes = 0
        content?.items?.forEach { item ->
            val block = item as? McpJson.Obj ?: return@forEach
            when (val type = block.string("type")) {
                "text" -> {
                    val text = block.string("text").orEmpty()
                    val remaining = McpProtocol.MAX_RESULT_TEXT_BYTES_TO_MODEL - totalBytes
                    if (remaining <= 0) {
                        texts += "...[truncated]"
                        return@forEach
                    }
                    val bounded = takeUtf8Bytes(text, remaining)
                    totalBytes += bounded.toByteArray(Charsets.UTF_8).size
                    texts += bounded
                    if (bounded.length < text.length) texts += "...[truncated]"
                }
                "image", "audio", "blob", "resource", "resource_link" -> unsupported += type ?: "unknown"
                else -> unsupported += type ?: "unknown"
            }
        }
        val structured = root.fields["structuredContent"]?.let { codec.write(it) }
            ?.takeIf { it.toByteArray(Charsets.UTF_8).size <= McpProtocol.MAX_MESSAGE_BYTES }
        if (isError) {
            return McpToolCallOutcome.ToolError(
                message = texts.joinToString("\n").take(2 * 1024)
                    .ifBlank { "The MCP tool reported an error." },
                isProtocolError = false
            )
        }
        return McpToolCallOutcome.Success(texts, structured, unsupported.distinct().sorted())
    }

    private fun httpPost(sessionId: String?): McpHttpPost = McpHttpPost(        url = config.endpoint,
        bodyJson = "",
        sessionId = sessionId ?: pendingSessionId,
        protocolVersion = session?.negotiatedVersion ?: McpProtocol.VERSION_2025_06_18,
        bearerToken = when (val auth = config.auth) {
            McpAuth.None -> null
            is McpAuth.SecretReference -> credentials.bearerToken(auth.credentialRefId)
        },
        allowCleartext = isLocalCleartextAllowed()
    )

    private fun validateEndpoint(): McpFailure? {
        val uri = runCatching { URI(config.endpoint) }.getOrNull()
            ?: return McpFailure.Endpoint("mcp_endpoint_invalid")
        if (uri.userInfo != null || uri.fragment != null) {
            return McpFailure.Endpoint("mcp_endpoint_has_credentials_or_fragment")
        }
        val host = uri.host ?: return McpFailure.Endpoint("mcp_endpoint_missing_host")
        val scheme = uri.scheme?.lowercase()
        val isLocal = isLocalHost(host)
        if (isLocal && !config.allowLocalServer) {
            // A remote server must not silently point at loopback/private space; a local server
            // needs the explicit LOCAL_SERVER configuration and its own egress grant.
            return McpFailure.Endpoint("mcp_private_origin_denied")
        }
        if (scheme == "https") return null
        if (scheme == "http" && config.allowLocalServer && isLocal) return null
        return McpFailure.Endpoint("mcp_endpoint_insecure")
    }

    private fun isLocalCleartextAllowed(): Boolean =
        config.allowLocalServer && config.endpoint.startsWith("http://")

    private fun canonicalOrigin(endpoint: String): String {
        val uri = URI(endpoint)
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "${uri.scheme}://${uri.host?.lowercase()}$port"
    }

    private fun isLocalHost(host: String): Boolean {
        val normalized = host.trim().lowercase().trimEnd('.')
        if (normalized == "localhost" || normalized == "::1" || normalized == "[::1]") return true
        val parts = normalized.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            val first = parts[0].toInt()
            val second = parts[1].toInt()
            return first == 10 || first == 127 || (first == 192 && second == 168) ||
                (first == 172 && second in 16..31) || (first == 169 && second == 254)
        }
        return false
    }

    private fun resetLocalState() {
        session = null
        pendingSessionId = null
        tools = emptyList()
    }

    /**
     * Credential revocation is a hard stop, not an anonymous retry.
     *
     * A server configured with `McpAuth.SecretReference` must never send a request whose bearer
     * token could not be resolved: silently dropping the header would turn a revoked credential into
     * an unauthenticated call (or a different error class) and could leak the request body to a
     * server that no longer has an active grant. Every rpc/notification entry point checks here,
     * before a request body is even built.
     */
    private fun missingCredentialFailure(): McpFailure? = when (val auth = config.auth) {
        McpAuth.None -> null
        is McpAuth.SecretReference ->
            if (credentials.bearerToken(auth.credentialRefId).isNullOrBlank()) {
                McpFailure.Authentication(CREDENTIAL_REVOKED_CODE)
            } else {
                null
            }
    }

    private fun takeUtf8Bytes(text: String, limit: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= limit) return text
        var bytes = 0
        val builder = StringBuilder()
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            val chars = Character.charCount(codePoint)
            val piece = text.substring(offset, offset + chars)
            val pieceBytes = piece.toByteArray(Charsets.UTF_8).size
            if (bytes + pieceBytes > limit) break
            builder.append(piece)
            bytes += pieceBytes
            offset += chars
        }
        return builder.toString()
    }

    private fun sha256(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private data class McpResponse(val result: McpJson?, val sessionId: String?)

    private companion object {
        val codec = McpJsonCodec()

        /** Typed cause: the configured credential reference no longer resolves. */
        const val CREDENTIAL_REVOKED_CODE: String = "mcp_credential_revoked"
        const val RECONNECT_REASON_SESSION_EXPIRED: String = "mcp_session_expired"
        const val RECONNECT_REASON_SESSION_EXPIRED_CALL: String = "mcp_session_expired_call"
    }
}
