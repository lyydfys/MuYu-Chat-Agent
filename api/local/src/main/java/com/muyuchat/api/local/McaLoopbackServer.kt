package com.muyuchat.api.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * OpenAI-compatible clients commonly render only the model `id`. Keep that id readable while
 * retaining the manifest id as a deterministic suffix, so duplicate display names stay unique.
 */
fun localApiPublicModelId(displayName: String?, internalId: String): String {
    val stableId = internalId.trim()
    val readableName = displayName
        .orEmpty()
        .trim()
        .replace(Regex("\\s+"), " ")
    return when {
        readableName.isBlank() -> stableId
        stableId.isBlank() || readableName == stableId -> readableName
        readableName.endsWith("[$stableId]") -> readableName
        else -> "$readableName [$stableId]"
    }
}

class McaLoopbackServer(
    private val port: Int = 11435,
    private val bindHost: String = "127.0.0.1",
    internal val apiKey: String = ""
) {
    private var dispatcher: ExecutorCoroutineDispatcher = newDispatcher()
    internal var scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    internal val clientSocketLock = Any()
    private val activeClientSockets = linkedSetOf<Socket>()
    internal val responseStartedSockets = mutableSetOf<Socket>()
    private var clientAdmissionOpen = false
    internal val generationLeaseLock = Any()
    internal var generationAdmissionOpen = false
    internal var activeGenerationLease: GenerationLease? = null
    internal val imageRequestLock = Any()
    internal val activeImageRequestJobs = linkedMapOf<String, Job>()
    internal var imageRequestCancellationEpoch: Long = 0L
    internal var imageRequestAdmissionPauseCount: Int = 0
    internal val clientRequestIdLock = Any()
    internal val usedClientRequestIds = mutableSetOf<String>()

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    fun start() {
        if (isRunning) return
        if (!scope.isActive) {
            dispatcher.close()
            dispatcher = newDispatcher()
            scope = CoroutineScope(SupervisorJob() + dispatcher)
        }
        val bindAddress = InetAddress.getByName(bindHost)
        require(bindAddress.isLoopbackAddress || apiKey.isNotBlank()) {
            "A non-loopback Local API listener requires a non-empty API key."
        }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bindAddress, port), SERVER_BACKLOG)
        synchronized(imageRequestLock) { imageRequestAdmissionPauseCount = 0 }
        synchronized(generationLeaseLock) { generationAdmissionOpen = true }
        synchronized(clientSocketLock) { clientAdmissionOpen = true }
        serverSocket = socket
        logInfo("Local API listening on $bindHost:$port")
        acceptJob = scope.launch {
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    client.soTimeout = CLIENT_READ_TIMEOUT_MS
                    client.tcpNoDelay = true
                    val admitted = synchronized(clientSocketLock) {
                        if (clientAdmissionOpen && activeClientSockets.size < MAX_ACTIVE_CLIENTS) {
                            activeClientSockets += client
                            true
                        } else {
                            false
                        }
                    }
                    if (!admitted) {
                        runCatching {
                            writeError(
                                client,
                                "503 Service Unavailable",
                                "server_busy",
                                "The Local API connection limit has been reached."
                            )
                        }
                        runCatching { client.close() }
                        continue
                    }
                    logDebug("Accepted ${client.inetAddress?.hostAddress}:${client.port}")
                    launch {
                        try {
                            handle(client)
                        } catch (error: Throwable) {
                            val rejection = error as? HttpRequestRejected
                            if (rejection == null) {
                                logWarning("Request failed: ${error.message}", error)
                            } else {
                                logDebug("Request rejected: ${rejection.code}")
                            }
                            runCatching {
                                if (!client.isClosed && synchronized(clientSocketLock) { client !in responseStartedSockets }) {
                                    writeError(
                                        client,
                                        rejection?.status ?: "500 Internal Server Error",
                                        rejection?.code ?: "request_failed",
                                        error.message ?: "Local API request failed."
                                    )
                                }
                            }
                        } finally {
                            runCatching { client.close() }
                            synchronized(clientSocketLock) {
                                activeClientSockets.remove(client)
                                responseStartedSockets.remove(client)
                            }
                        }
                    }
                } catch (error: SocketException) {
                    if (!socket.isClosed) logWarning("Accept failed: ${error.message}", error)
                } catch (error: Throwable) {
                    logWarning("Accept loop failed: ${error.message}", error)
                }
            }
        }
    }

    fun stop() {
        val generationLease = synchronized(generationLeaseLock) {
            generationAdmissionOpen = false
            activeGenerationLease
        }
        generationLease?.job?.cancel(CancellationException("Local API server stopped."))
        generationLease?.requestId?.takeIf(String::isNotBlank)?.let { requestId ->
            scope.launch { LocalApiRuntime.stopGenerationIfRequestActive(requestId) }
        }
        cancelActiveImageRequests(
            "Local API server stopped during image generation."
        )
        val clients = synchronized(clientSocketLock) {
            clientAdmissionOpen = false
            activeClientSockets.toList().also { activeClientSockets.clear() }
        }
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.forEach { client -> runCatching { client.close() } }
        logInfo("Local API stopped")
    }

    fun shutdown() {
        stop()
        scope.cancel()
        dispatcher.close()
    }

    internal fun clearProcessIdempotencyCacheForTests() {
        synchronized(idempotencyLock) { idempotencyRecords.clear() }
    }

    internal fun activeClientCountForTests(): Int = synchronized(clientSocketLock) {
        activeClientSockets.size
    }

    private suspend fun handle(socket: Socket) {
        // Keep the socket open on rejection paths; the launch wrapper is the only close point.
        run { val client = socket
            val request = try {
                readHttpRequest(client)
            } catch (rejection: HttpRequestRejected) {
                writeError(client, rejection.status, rejection.code, rejection.message.orEmpty())
                return
            }
            val headers = request.headers
            val body = request.body
            val parts = request.requestLine.split(" ")
            val method = parts.getOrNull(0).orEmpty().uppercase()
            val path = parts.getOrNull(1).orEmpty().substringBefore("?")
            logDebug("$method $path")
            if (method == "OPTIONS") {
                writeNoContent(client)
                return
            }
            if (method == "GET" && (path == "/" || path == "/index.html")) {
                writeHtml(client, mcaLocalApiChatPageHtml())
                return
            }
            if (!isPublicRoute(method, path) && !isAuthorized(headers)) {
                writeError(client, "401 Unauthorized", "unauthorized", "API Key 不正确或缺失。")
                return
            }
            when {
                method == "HEAD" && path == "/health" -> writeNoContent(client)
                method == "GET" && path == "/health" -> writeJson(client, """{"status":"ok","name":"MuYu Chat Agent"}""")
                method == "GET" && (path == "/v1/models" || path == "/models") -> writeJson(client, LocalApiRuntime.modelsJson())
                method == "GET" && path == LocalApiCapabilityRegistry.PATH ->
                    writeJson(client, LocalApiCapabilityRegistry.json())
                method == "GET" && path in IMAGE_TEXTUAL_INVERSION_PATHS ->
                    writeJson(client, LocalApiRuntime.imageTextualInversionsJson())
                method == "GET" && path == "/v1/mca/device" -> writeJson(client, LocalApiRuntime.deviceProfileJsonProvider())
                method == "GET" && path == "/v1/mca/runtime" -> writeJson(client, localApiRuntimeStateJson())
                method == "GET" && path == "/v1/mca/profile" -> writeJson(client, LocalApiRuntime.publicProfileJson())
                method == "GET" && path == "/v1/mca/tuning" -> writeJson(client, LocalApiRuntime.publicTuningJson())
                method == "POST" && path == "/v1/tuning/jobs" -> {
                    handleTuningJobCreate(client, body, headers["idempotency-key"].orEmpty())
                }
                method == "POST" && path == "/v1/tuning/rollback" -> {
                    handleTuningRollback(client, body, headers["idempotency-key"].orEmpty())
                }
                method == "GET" && path.startsWith(TUNING_JOB_PATH_PREFIX) -> handleTuningJobQuery(client, path)
                method == "POST" && path.startsWith(TUNING_JOB_PATH_PREFIX) -> {
                    handleTuningJobAction(client, path, body, headers["idempotency-key"].orEmpty())
                }
                method == "POST" && path == "/v1/mca/recommend" -> writeJson(client, LocalApiRuntime.agentRecommendationJsonProvider(body))
                method == "POST" && path == "/v1/mca/benchmark" -> writeJson(client, LocalApiRuntime.benchmarkJsonProvider(body))
                method == "POST" && path in IMAGE_GENERATION_PATHS -> {
                    handleImageGeneration(client, body, validatedClientRequestId(headers))
                }
                method == "POST" && path in RESPONSES_PATHS -> {
                    val clientRequestId = validatedClientRequestId(headers)
                    val streaming = body.isStreamingRequest(headers)
                    when (val prepared = prepareResponsesRequest(body, streaming)) {
                        is PreparedResponsesRequest.Ready -> {
                            if (clientRequestId != null) prepared.lease.clientRequestId = clientRequestId
                            try {
                                if (streaming) {
                                    streamResponses(client, prepared.request, prepared.lease)
                                } else {
                                    completeResponses(client, prepared.request, prepared.lease)
                                }
                            } finally {
                                releaseGenerationLease(prepared.lease)
                            }
                        }
                        is PreparedResponsesRequest.Rejected -> writePreflightRejection(client, prepared.rejection)
                    }
                }
                method == "GET" && path == "/metrics" -> writeText(client, LocalApiRuntime.metricsJson())
                method == "POST" && path == "/v1/generate/stop" -> {
                    handleGenerationStop(client, body)
                }
                method == "POST" && path in GENERATION_PATHS -> {
                    val clientRequestId = validatedClientRequestId(headers)
                    val streaming = body.isStreamingRequest(headers)
                    when (val prepared = prepareGenerationRequest(path, body, streaming)) {
                        is PreparedGenerationRequest.Ready -> {
                            if (clientRequestId != null) prepared.lease.clientRequestId = clientRequestId
                            try {
                                if (streaming) {
                                    streamChat(client, prepared.request, prepared.lease)
                                } else {
                                    completeChat(client, prepared.request, prepared.lease)
                                }
                            } finally {
                                releaseGenerationLease(prepared.lease)
                            }
                        }
                        is PreparedGenerationRequest.Rejected -> writePreflightRejection(client, prepared)
                    }
                }
                else -> writeError(client, "404 Not Found", "not_found", "接口不存在：$path")
            }
        }
    }

    internal fun isPublicRoute(method: String, path: String): Boolean =
        method == "OPTIONS" ||
            method == "HEAD" && path == "/health" ||
            method == "GET" && (
                path == "/health" ||
                    path == "/" ||
                    path == "/index.html" ||
                    path == "/v1/models" ||
                    path == "/models"
                )

    private fun writeHtml(socket: Socket, body: String, status: String = "200 OK") {
        writeText(socket, body, status, "text/html; charset=utf-8")
    }

    internal fun requestBodyLimitFor(method: String, path: String): Int {
        val normalizedPath = path.substringBefore("?")
        return when {
            method.equals("POST", ignoreCase = true) && normalizedPath in IMAGE_GENERATION_PATHS ->
                MAX_IMAGE_REQUEST_BODY_BYTES
            method.equals("POST", ignoreCase = true) &&
                (normalizedPath in GENERATION_PATHS || normalizedPath in RESPONSES_PATHS) ->
                MAX_CHAT_REQUEST_BODY_BYTES
            else -> MAX_CONTROL_REQUEST_BODY_BYTES
        }
    }

    private companion object {

        private val GENERATION_PATHS = setOf("/v1/chat/completions", "/chat/completions", "/v1/completions", "/completion")
        private val RESPONSES_PATHS = setOf("/v1/responses", "/responses")
        private val IMAGE_GENERATION_PATHS = setOf("/v1/images/generations", "/images/generations")
        private val IMAGE_TEXTUAL_INVERSION_PATHS = setOf(
            "/v1/images/textual-inversions",
            "/images/textual-inversions"
        )

        /** Image generation compatibility limit retained from the original API. */
        private const val MAX_IMAGE_REQUEST_BODY_BYTES = 64 * 1024 * 1024
        private const val MAX_CHAT_REQUEST_BODY_BYTES = 8 * 1024 * 1024
        private const val MAX_CONTROL_REQUEST_BODY_BYTES = 1 * 1024 * 1024

        private const val CLIENT_READ_TIMEOUT_MS = 15_000
        private const val SERVER_BACKLOG = 128
        private const val MAX_ACTIVE_CLIENTS = 16

        private val threadIds = AtomicInteger(1)

        private fun newDispatcher(): ExecutorCoroutineDispatcher =
            Executors.newFixedThreadPool(MAX_ACTIVE_CLIENTS + 1) { runnable ->
                Thread(runnable, "MCA-Local-API-${threadIds.getAndIncrement()}").apply {
                    isDaemon = true
                }
            }.asCoroutineDispatcher()

    }

}
