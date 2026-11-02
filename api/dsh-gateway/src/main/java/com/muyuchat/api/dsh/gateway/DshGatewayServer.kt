package com.muyuchat.api.dsh.gateway

import com.muyuchat.core.dsh.contract.DshGatewayEndpoint
import com.muyuchat.core.dsh.contract.DshGatewayModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Internal, loopback-only OpenAI-compatible model gateway for the official DSH runtime.
 *
 * Lifecycle and ownership:
 * - one [start] binds `127.0.0.1:0` (ephemeral port), generates a fresh cryptographically random
 *   bearer token and begins accepting; [stop] cancels every active call individually, closes the
 *   listener and every client socket, and joins the server scope;
 * - the host owns the instance and calls [start] on demand for one DSH host generation, passing
 *   that generation's `runtimeEpoch` so call ids stay `dsh:<runtimeEpoch>:<ordinal>`;
 * - [currentEndpoint] is the only place the token is exposed. It is never logged and is cleared
 *   by [stop].
 *
 * The server is a protocol adapter only. It never executes DSH tools, never touches MCA chat
 * history, never reads cloud keys and never rewrites the DSH prompt. Model execution, admission
 * and precise cancellation belong to the injected [DshGatewayBackend].
 */
class DshGatewayServer(
    private val backend: DshGatewayBackend,
    private val runtimeEpoch: Long = 0L,
    private val limits: DshGatewayLimits = DshGatewayLimits.Default,
    private val logger: DshGatewayLogger = DshGatewayLogger.None
) {
    private val startStopMutex = Mutex()
    private val stateLock = Any()
    private val activeSockets: MutableSet<Socket> = Collections.synchronizedSet(linkedSetOf())
    private val connectionCount = AtomicInteger(0)

    @Volatile
    private var endpoint: DshGatewayEndpoint? = null

    @Volatile
    private var running: RunningState? = null

    @Volatile
    private var catalogRevision: String = ""

    private val calls = DshGatewayCallRegistry(
        runtimeEpoch = runtimeEpoch,
        maxConcurrentCalls = limits.maxConcurrentCalls,
        onCancel = { callId -> dispatchCancel(callId) }
    )
    private val chatRunner = DshGatewayChatRunner(backend, limits, logger)

    val isRunning: Boolean
        get() = endpoint != null

    /** The current loopback endpoint, or null when stopped. Contains the run's bearer token. */
    fun currentEndpoint(): DshGatewayEndpoint? = endpoint

    /**
     * Starts the listener and returns the endpoint for the current run.
     *
     * Idempotent for one run: a repeated call returns the same endpoint (and therefore the same
     * token) instead of rotating credentials. The caller must stop before starting a new run.
     */
    suspend fun start(): DshGatewayEndpoint = startStopMutex.withLock {
        endpoint?.let { return@withLock it }
        val catalogSnapshot = runCatching { backend.catalog() }
        val revision = catalogSnapshot.getOrNull()?.let(::catalogRevision) ?: "unavailable"
        catalogSnapshot.exceptionOrNull()?.let { failure ->
            log(
                DshGatewayLogLevel.WARN,
                "Model catalog unavailable while starting the DSH gateway (${failure.javaClass.simpleName})."
            )
        }
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(LOOPBACK, 0), SERVER_BACKLOG)
            check(socket.inetAddress.hostAddress == LOOPBACK_HOST) {
                "The DSH gateway must bind 127.0.0.1."
            }
            val token = DshGatewaySecret.newBearerToken()
            val startedAtSeconds = System.currentTimeMillis() / 1000L
            val state = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val resolved = DshGatewayEndpoint(
                baseUrl = "http://$LOOPBACK_HOST:${socket.localPort}/v1",
                bearerToken = token,
                catalogRevision = revision
            )
            val runningState = RunningState(socket, state, startedAtSeconds)
            synchronized(stateLock) {
                running = runningState
                endpoint = resolved
                catalogRevision = revision
            }
            // Publish the endpoint before accepting: a connection that arrives before the state
            // assignment must not observe an unauthenticated gateway.
            runningState.acceptJob = state.launch { acceptLoop(socket, state) }
            log(
                DshGatewayLogLevel.INFO,
                "Internal DSH model gateway listening on $LOOPBACK_HOST:${socket.localPort}"
            )
            return@withLock resolved
        } catch (failure: Throwable) {
            synchronized(stateLock) {
                running = null
                endpoint = null
            }
            runCatching { socket.close() }
            throw failure
        }
    }

    /** Stops the listener and cancels each active call with [DshCallCancelReason.SERVER_STOP]. */
    suspend fun stop() = startStopMutex.withLock {
        val state = synchronized(stateLock) {
            val current = running ?: return@withLock
            running = null
            endpoint = null
            current
        }
        calls.cancelAll(DshCallCancelReason.SERVER_STOP)
        runCatching { state.socket.close() }
        state.acceptJob?.cancel()
        snapshotSockets().forEach { client -> runCatching { client.close() } }
        // Bounded: a backend collector blocked inside native work must not hold gateway teardown
        // (or `start`, which waits on this mutex) indefinitely. The scope is already cancelled;
        // a stuck collector is left to unwind when its native call finally returns.
        val joined = withTimeoutOrNull(STOP_JOIN_TIMEOUT_MILLIS) {
            state.scope.coroutineContext[Job]?.cancelAndJoin()
            true
        }
        if (joined == null) {
            log(
                DshGatewayLogLevel.WARN,
                "DSH gateway stop did not join a stuck backend collector within " +
                    "${STOP_JOIN_TIMEOUT_MILLIS} ms; the listener and calls are already stopped."
            )
        }
        log(DshGatewayLogLevel.INFO, "Internal DSH model gateway stopped")
    }

    private suspend fun acceptLoop(serverSocket: ServerSocket, owner: CoroutineScope) {
        while (owner.isActive && !serverSocket.isClosed) {
            val client = try {
                serverSocket.accept()
            } catch (closed: SocketException) {
                break
            } catch (failure: IOException) {
                if (!serverSocket.isClosed) {
                    log(
                        DshGatewayLogLevel.WARN,
                        "DSH gateway accept failed (${failure.javaClass.simpleName})."
                    )
                }
                break
            }
            if (!client.inetAddress.isLoopbackAddress) {
                runCatching { client.close() }
                continue
            }
            if (!tryAcquireConnection()) {
                runCatching {
                    DshGatewayHttp.writeError(
                        client, 503, "server_error", "server_busy",
                        "The internal DSH model gateway connection limit was reached."
                    )
                }
                runCatching { client.close() }
                continue
            }
            activeSockets.add(client)
            owner.launch { handleConnection(client, owner) }
        }
    }

    private suspend fun handleConnection(socket: Socket, owner: CoroutineScope) {
        try {
            socket.soTimeout = limits.clientReadTimeoutMillis
            socket.tcpNoDelay = true
            val request = try {
                DshGatewayHttp.read(socket, limits)
            } catch (closed: EOFException) {
                return
            } catch (rejection: DshGatewayHttpRejection) {
                writeRejection(socket, rejection)
                return
            }
            socket.soTimeout = 0
            if (!isAuthorized(request)) {
                writeRejection(
                    socket,
                    DshGatewayHttpRejection(
                        401, "authentication_error", "invalid_api_key",
                        "Missing or invalid bearer token."
                    ),
                    extraHeaders = mapOf("WWW-Authenticate" to "Bearer")
                )
                return
            }
            route(socket, request, owner)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            // Peer closed / reset. Nothing to write; the call (if any) is cancelled by its monitor.
        } catch (failure: Throwable) {
            log(
                DshGatewayLogLevel.WARN,
                "DSH gateway request failed (${failure.javaClass.simpleName})."
            )
            runCatching {
                DshGatewayHttp.writeError(
                    socket, 500, "server_error", "internal_error",
                    "The internal DSH model gateway failed to process the request."
                )
            }
        } finally {
            runCatching { socket.close() }
            activeSockets.remove(socket)
            releaseConnection()
        }
    }

    private suspend fun route(
        socket: Socket,
        request: DshGatewayHttpRequest,
        owner: CoroutineScope
    ) {
        when {
            request.method == "GET" && request.path == "/healthz" -> handleHealthz(socket)
            request.method == "GET" && request.path == "/v1/models" -> handleModels(socket)
            request.method == "POST" && request.path == "/v1/chat/completions" ->
                handleChatCompletion(socket, request, owner)
            request.method == "POST" && request.path == CANCELLATION_PATH ->
                handleExplicitCancellation(socket, request)
            request.path in KNOWN_ROUTES -> {
                val allowed = requireNotNull(KNOWN_ROUTES[request.path])
                DshGatewayHttp.writeError(
                    socket, 405, "invalid_request_error", "method_not_allowed",
                    "Method ${request.method} is not allowed for ${request.path}.",
                    extraHeaders = mapOf("Allow" to allowed)
                )
            }
            else -> DshGatewayHttp.writeError(
                socket, 404, "invalid_request_error", "not_found",
                "No such route: ${request.path}."
            )
        }
    }

    private fun handleHealthz(socket: Socket) {
        val body = JSONObject()
            .put("status", "ok")
            .put("service", "mca-dsh-gateway")
            .put("active_calls", calls.activeCount())
            .put("catalog_revision", catalogRevision)
            .toString()
        DshGatewayHttp.writeJson(socket, 200, body)
    }

    private suspend fun handleModels(socket: Socket) {
        val models = try {
            backend.catalog()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            log(
                DshGatewayLogLevel.WARN,
                "DSH gateway catalog request failed (${failure.javaClass.simpleName})."
            )
            DshGatewayHttp.writeError(
                socket, 503, "server_error", "catalog_unavailable",
                "The MCA model catalog is currently unavailable."
            )
            return
        }
        val revision = catalogRevision(models)
        publishCatalogRevision(revision)
        // ECO-5: a bound row without a declared context window makes official compaction fail
        // closed. Report it on the host log as well as in the listing sidecar, so the unusable row
        // is diagnosable instead of silently advertised.
        DshContextCapacityProbe.summary(models)?.let { warning ->
            log(DshGatewayLogLevel.WARN, "DSH model catalog: $warning")
        }
        val started = running?.startedAtSeconds ?: (System.currentTimeMillis() / 1000L)
        DshGatewayHttp.writeJson(
            socket, 200,
            DshOpenAiWire.modelsJson(models, revision, started)
        )
    }

    private suspend fun handleChatCompletion(
        socket: Socket,
        http: DshGatewayHttpRequest,
        owner: CoroutineScope
    ) {
        val parsed = when (val result = DshOpenAiRequest.parse(http.body, "", limits)) {
            is DshOpenAiParse.Success -> result
            is DshOpenAiParse.Rejected -> {
                DshGatewayHttp.writeError(
                    socket, result.status, result.type, result.code, result.message, result.param
                )
                return
            }
        }
        val call = calls.admit()
        if (call == null) {
            DshGatewayHttp.writeError(
                socket, 429, "rate_limit_error", "too_many_concurrent_calls",
                "The internal DSH model gateway is already serving " +
                    "${limits.maxConcurrentCalls} model calls.",
                extraHeaders = mapOf("Retry-After" to "1")
            )
            return
        }
        try {
            val models = try {
                backend.catalog()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                log(
                    DshGatewayLogLevel.WARN,
                    "DSH gateway catalog lookup failed before a call (${failure.javaClass.simpleName})."
                )
                DshGatewayHttp.writeError(
                    socket, 503, "server_error", "catalog_unavailable",
                    "The MCA model catalog is currently unavailable."
                )
                return
            }
            val model = models.firstOrNull { it.id == parsed.request.modelId }
            if (model == null) {
                DshGatewayHttp.writeError(
                    socket, 404, "invalid_request_error", "model_not_found",
                    "Model '${parsed.request.modelId}' is not in the current MCA model catalog.",
                    param = "model"
                )
                return
            }
            if (parsed.request.tools.isNotEmpty() && !model.supportsTools) {
                DshGatewayHttp.writeError(
                    socket, 400, "invalid_request_error", "tools_not_supported",
                    "Model '${model.id}' does not support tools.", param = "tools"
                )
                return
            }
            val request = parsed.request.copy(callId = call.callId)
            chatRunner.execute(socket, parsed, request, call, owner)
        } finally {
            calls.release(call)
        }
    }

    private suspend fun handleExplicitCancellation(socket: Socket, http: DshGatewayHttpRequest) {
        val root = DshGatewayJson.parseObject(http.body)
        val callId = (root?.opt("call_id") as? String)?.trim()
        if (callId.isNullOrEmpty()) {
            DshGatewayHttp.writeError(
                socket, 400, "invalid_request_error", "invalid_request",
                "The cancellation body must be a JSON object with a non-empty 'call_id'.",
                param = "call_id"
            )
            return
        }
        val cancelled = calls.cancel(callId, DshCallCancelReason.EXPLICIT_REQUEST)
        if (!cancelled) {
            DshGatewayHttp.writeError(
                socket, 404, "invalid_request_error", "call_not_found",
                "No in-flight gateway call with that id."
            )
            return
        }
        val body = JSONObject()
            .put("call_id", callId)
            .put("cancelled", true)
            .toString()
        DshGatewayHttp.writeJson(socket, 200, body)
    }

    /**
     * Dispatches one exact call cancel to the backend under a bounded window.
     *
     * The adapter's [DshGatewayBackend.cancel] sends the precise native stop (for a local model
     * `ChatExecutionFacade.stopGenerationIfActive(requestId)` -> `runner.requestStop()`), which is
     * the only way to break a native prefill that never reaches a cooperative suspension point.
     * The gateway therefore awaits the dispatch - a cancel is only acknowledged once the stop
     * signal exists - but never awaits native settle evidence: the engine's own stop window is
     * 500 ms and [DshGatewayLimits.cancelDispatchTimeoutMillis] bounds a broken adapter from the
     * SSE/response path. A late or unknown call id never reaches this hook at all (it is only
     * invoked for a live call on its first cancel transition), so this can never stop a newer call.
     */
    private suspend fun dispatchCancel(callId: String) {
        try {
            val dispatched = withTimeoutOrNull(limits.cancelDispatchTimeoutMillis) {
                backend.cancel(callId)
                true
            }
            if (dispatched == null) {
                log(
                    DshGatewayLogLevel.WARN,
                    "DSH gateway cancel dispatch timed out for $callId; the call stays cancelled."
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // A failing adapter stop must not break the cancel transition; the collecting flow is
            // cancelled structurally by the caller regardless.
            log(
                DshGatewayLogLevel.WARN,
                "DSH gateway cancel dispatch failed (${failure.javaClass.simpleName})."
            )
        }
    }

    private fun writeRejection(
        socket: Socket,
        rejection: DshGatewayHttpRejection,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        runCatching {
            DshGatewayHttp.writeError(
                socket = socket,
                status = rejection.status,
                type = rejection.type,
                code = rejection.code,
                message = rejection.message,
                param = rejection.param,
                extraHeaders = extraHeaders
            )
        }
    }

    private fun isAuthorized(request: DshGatewayHttpRequest): Boolean {
        val expected = endpoint?.bearerToken ?: return false
        val authorization = request.headers["authorization"] ?: return false
        if (!authorization.startsWith("Bearer ", ignoreCase = true)) return false
        return DshGatewaySecret.matches(expected, authorization.substring(7).trim())
    }

    private fun tryAcquireConnection(): Boolean {
        while (true) {
            val current = connectionCount.get()
            if (current >= limits.maxConnections) return false
            if (connectionCount.compareAndSet(current, current + 1)) return true
        }
    }

    private fun releaseConnection() {
        while (true) {
            val current = connectionCount.get()
            val next = (current - 1).coerceAtLeast(0)
            if (connectionCount.compareAndSet(current, next)) return
        }
    }

    private fun snapshotSockets(): List<Socket> = synchronized(activeSockets) {
        activeSockets.toList()
    }

    private fun catalogRevision(models: List<DshGatewayModel>): String {
        val payload = models.joinToString("\n") { model ->
            listOf(
                model.id,
                model.displayName,
                model.contextWindow?.toString().orEmpty(),
                model.maxOutputTokens?.toString().orEmpty(),
                model.supportsTools.toString(),
                model.nativeToolCalling.toString(),
                model.toolProtocolLabel.orEmpty()
            ).joinToString("|")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return buildString {
            digest.take(8).forEach { byte ->
                append((byte.toInt() and 0xFF).toString(16).padStart(2, '0'))
            }
        }
    }

    /** Keeps the published endpoint's catalog revision current after a listing refresh. */
    private fun publishCatalogRevision(revision: String) {
        catalogRevision = revision
        synchronized(stateLock) {
            val current = endpoint ?: return
            if (current.catalogRevision != revision) {
                endpoint = current.copy(catalogRevision = revision)
            }
        }
    }

    private fun log(level: DshGatewayLogLevel, message: String) {
        runCatching { logger.log(level, message) }
    }

    private class RunningState(
        val socket: ServerSocket,
        val scope: CoroutineScope,
        val startedAtSeconds: Long
    ) {
        @Volatile
        var acceptJob: Job? = null
    }

    private companion object {
        val LOOPBACK: InetAddress =
            InetAddress.getByAddress("127.0.0.1", byteArrayOf(127, 0, 0, 1))
        const val LOOPBACK_HOST = "127.0.0.1"
        const val SERVER_BACKLOG = 32
        const val CANCELLATION_PATH = "/v1/mca/calls/cancel"
        const val STOP_JOIN_TIMEOUT_MILLIS = 2_000L
        val KNOWN_ROUTES: Map<String, String> = linkedMapOf(
            "/healthz" to "GET",
            "/v1/models" to "GET",
            "/v1/chat/completions" to "POST",
            CANCELLATION_PATH to "POST"
        )
    }
}
