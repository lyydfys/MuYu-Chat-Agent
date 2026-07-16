package com.muyuchat.api.local

import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.localContextWindowBudget
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

class McaLoopbackServer(
    private val port: Int = 11435,
    private val bindHost: String = "0.0.0.0",
    private val apiKey: String = ""
) {
    private var dispatcher: ExecutorCoroutineDispatcher = newDispatcher()
    private var scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    fun start() {
        if (isRunning) return
        if (!scope.isActive) {
            dispatcher.close()
            dispatcher = newDispatcher()
            scope = CoroutineScope(SupervisorJob() + dispatcher)
        }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName(bindHost), port), SERVER_BACKLOG)
        serverSocket = socket
        logInfo("Local API listening on $bindHost:$port")
        acceptJob = scope.launch {
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    client.soTimeout = CLIENT_READ_TIMEOUT_MS
                    client.tcpNoDelay = true
                    logDebug("Accepted ${client.inetAddress?.hostAddress}:${client.port}")
                    launch {
                        runCatching { handle(client) }
                            .onFailure { error ->
                                logWarning("Request failed: ${error.message}", error)
                                runCatching {
                                    if (!client.isClosed) {
                                        writeError(
                                            client,
                                            "500 Internal Server Error",
                                            "request_failed",
                                            error.message ?: "Local API request failed."
                                        )
                                    }
                                }
                                runCatching { client.close() }
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
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
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

    private suspend fun handle(socket: Socket) {
        socket.use { client ->
            val request = readHttpRequest(client)
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
                writeHtml(client, chatPageHtml())
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
                    handleImageGeneration(client, body)
                }
                method == "GET" && path == "/metrics" -> writeText(client, LocalApiRuntime.metricsJson())
                method == "POST" && path == "/v1/generate/stop" -> {
                    LocalApiRuntime.stopGeneration()
                    writeJson(client, """{"stopped":true}""")
                }
                method == "POST" && path in GENERATION_PATHS -> {
                    val streaming = body.isStreamingRequest(headers)
                    when (val prepared = prepareGenerationRequest(path, body, streaming)) {
                        is PreparedGenerationRequest.Ready -> {
                            if (streaming) {
                                streamChat(client, prepared.request)
                            } else {
                                completeChat(client, prepared.request)
                            }
                        }
                        is PreparedGenerationRequest.Rejected -> writePreflightRejection(client, prepared)
                    }
                }
                else -> writeError(client, "404 Not Found", "not_found", "接口不存在：$path")
            }
        }
    }

    private suspend fun handleImageGeneration(socket: Socket, body: String) {
        val request = try {
            ImageGenerationApiContract.parseRequest(body)
        } catch (error: ImageGenerationContractException) {
            writeError(
                socket,
                "400 Bad Request",
                error.code,
                error.message
            )
            return
        }

        val busy = try {
            LocalApiRuntime.busyState()
        } catch (_: Throwable) {
            writeError(
                socket,
                "503 Service Unavailable",
                "image_control_plane_failed",
                "The local runtime could not report image generation availability."
            )
            return
        }
        if (busy.busy) {
            writeError(
                socket,
                "409 Conflict",
                "image_generation_busy",
                busy.message.ifBlank { "Another local runtime operation is in progress." },
                detailsJson = busy.detailsJson,
                retryAfterMs = busy.retryAfterMs.coerceAtLeast(0L)
            )
            return
        }

        val requestId = "img-${UUID.randomUUID()}"
        val providerResponse = try {
            LocalApiRuntime.generateImage(requestId, request.rawBody)
        } catch (error: Throwable) {
            writeError(
                socket,
                "500 Internal Server Error",
                "image_generation_failed",
                error.message ?: "Local image generation failed."
            )
            return
        }
        if (providerResponse == null) {
            writeError(
                socket,
                "503 Service Unavailable",
                "image_runtime_unavailable",
                "The local image runtime is not attached."
            )
            return
        }

        val response = try {
            ImageGenerationApiContract.parseResponse(requestId, providerResponse)
        } catch (error: ImageGenerationContractException) {
            writeError(
                socket,
                "502 Bad Gateway",
                error.code,
                error.message
            )
            return
        }
        writeJson(socket, response.rawBody)
    }

    /**
     * Read-only tuning state endpoint. It deliberately goes through LocalApiRuntime's
     * coordinator bridge and does not use the generation busy gate, so it remains available while
     * a candidate is loading, validating, or being recovered.
     */
    private fun handleTuningJobQuery(socket: Socket, path: String) {
        val parsed = parseTuningJobId(path)
        if (parsed == null) {
            writeError(
                socket,
                "400 Bad Request",
                "invalid_tuning_job_route",
                "调优任务路径必须是 /v1/tuning/jobs/{id}，id 只能包含字母、数字、点、下划线、冒号或连字符。"
            )
            return
        }
        writeControlResult(socket, LocalApiRuntime.tuningJob(parsed.takeIf(String::isNotBlank)))
    }

    private fun handleTuningJobCreate(socket: Socket, body: String, idempotencyKey: String) {
        if (!requireValidIdempotencyKey(socket, idempotencyKey)) return
        val fingerprint = requestFingerprint("POST", "/v1/tuning/jobs", body)
        cachedIdempotencyResult(idempotencyKey, fingerprint)?.let { cached ->
            writeControlResult(socket, cached)
            return
        }
        val root = runCatching { JSONObject(body) }.getOrElse {
            writeError(socket, "400 Bad Request", "invalid_json", "调优任务请求体必须是有效 JSON。")
            return
        }
        val allowedFields = TUNING_CREATE_FIELDS
        val unknownFields = root.keys().asSequence().filterNot(allowedFields::contains).toList()
        if (unknownFields.isNotEmpty()) {
            writeError(
                socket,
                "400 Bad Request",
                "unsupported_tuning_fields",
                "调优任务包含不支持的字段：${unknownFields.sorted().joinToString(", ")}。"
            )
            return
        }
        val modelId = root.optString("modelId").trim()
        val mode = root.optString("mode").trim().lowercase()
        val autoApplyValue = root.opt("autoApply")
        val performancePreference = root.optString("performancePreference")
            .trim()
            .takeIf(String::isNotBlank)
        when {
            modelId.isBlank() || modelId.length > MAX_TUNING_MODEL_ID_LENGTH -> {
                writeError(socket, "400 Bad Request", "invalid_model_id", "modelId 不能为空且长度必须合法。")
                return
            }
            mode !in TUNING_MODES -> {
                writeError(socket, "400 Bad Request", "invalid_tuning_mode", "mode 必须是 quick、standard、deep 或 power_save。")
                return
            }
            autoApplyValue !is Boolean -> {
                writeError(socket, "400 Bad Request", "invalid_auto_apply", "autoApply 必须是布尔值。")
                return
            }
            performancePreference != null &&
                (performancePreference.length > MAX_TUNING_PREFERENCE_LENGTH ||
                    !TUNING_PREFERENCE_PATTERN.matches(performancePreference)) -> {
                writeError(
                    socket,
                    "400 Bad Request",
                    "invalid_performance_preference",
                    "performancePreference 只能包含安全的短标识字符。"
                )
                return
            }
        }
        val request = LocalApiTuningJobCreateRequest(
            modelId = modelId,
            mode = mode,
            autoApply = autoApplyValue,
            performancePreference = performancePreference
        )
        val idempotency = LocalApiIdempotencyContext(idempotencyKey, fingerprint)
        val result = executeIdempotent(
            idempotencyKey = idempotencyKey,
            fingerprint = fingerprint,
            lifecyclePrecondition = LocalApiRuntime::lifecycleConflict
        ) {
            LocalApiRuntime.createTuningJob(request, idempotency)
        }
        writeControlResult(socket, result)
    }

    private fun handleTuningRollback(socket: Socket, body: String, idempotencyKey: String) {
        if (!requireValidIdempotencyKey(socket, idempotencyKey)) return
        val fingerprint = requestFingerprint("POST", "/v1/tuning/rollback", body)
        cachedIdempotencyResult(idempotencyKey, fingerprint)?.let { cached ->
            writeControlResult(socket, cached)
            return
        }
        if (!requireEmptyControlBody(socket, body)) return
        val idempotency = LocalApiIdempotencyContext(idempotencyKey, fingerprint)
        val result = executeIdempotent(
            idempotencyKey = idempotencyKey,
            fingerprint = fingerprint,
            lifecyclePrecondition = LocalApiRuntime::lifecycleConflict
        ) {
            LocalApiRuntime.rollbackTuning(idempotency)
        }
        writeControlResult(socket, result)
    }

    /**
     * Synchronous, idempotency-key protected tuning transition endpoint. This module enforces the
     * lifecycle gate; the app-owned LocalApiControlPlane remains the sole transition authority.
     */
    private fun handleTuningJobAction(
        socket: Socket,
        path: String,
        body: String,
        idempotencyKey: String
    ) {
        val suffix = path.removePrefix(TUNING_JOB_PATH_PREFIX)
        val segments = suffix.split('/')
        if (segments.size != 2 || segments[0].isBlank() || segments[1].isBlank()) {
            writeError(
                socket,
                "400 Bad Request",
                "invalid_tuning_job_route",
                "调优控制路径必须是 /v1/tuning/jobs/{id}/pause、/resume、/cancel 或 /apply。"
            )
            return
        }
        val jobId = segments[0]
        if (!isValidTuningJobId(jobId) || jobId.equals("current", ignoreCase = true)) {
            writeError(
                socket,
                "400 Bad Request",
                "invalid_tuning_job_id",
                "调优控制需要具体的任务 id，不能使用 current。"
            )
            return
        }
        if (!requireValidIdempotencyKey(socket, idempotencyKey)) return
        val fingerprint = requestFingerprint("POST", path, body)
        cachedIdempotencyResult(idempotencyKey, fingerprint)?.let { cached ->
            writeControlResult(socket, cached)
            return
        }
        if (!requireEmptyControlBody(socket, body)) return
        val action = segments[1].lowercase()
        if (action !in TUNING_ACTIONS) {
            writeError(
                socket,
                "404 Not Found",
                "tuning_action_not_found",
                "不支持的调优任务操作：${segments[1]}。"
            )
            return
        }
        val idempotency = LocalApiIdempotencyContext(idempotencyKey, fingerprint)
        val result = executeIdempotent(
            idempotencyKey = idempotencyKey,
            fingerprint = fingerprint,
            lifecyclePrecondition = {
                if (action == "apply") LocalApiRuntime.lifecycleConflict() else null
            }
        ) {
            when (action) {
                "pause" -> LocalApiRuntime.pauseTuningJob(jobId, idempotency)
                "resume" -> LocalApiRuntime.resumeTuningJob(jobId, idempotency)
                "cancel" -> LocalApiRuntime.cancelTuningJob(jobId, idempotency)
                "apply" -> LocalApiRuntime.applyTuningJob(jobId, idempotency)
                else -> error("validated tuning action was not dispatched")
            }
        }
        writeControlResult(socket, result)
    }

    private fun requireEmptyControlBody(socket: Socket, body: String): Boolean {
        if (body.isBlank()) return true
        val root = runCatching { JSONObject(body) }.getOrElse {
            writeError(
                socket,
                "400 Bad Request",
                "invalid_json",
                "调优控制请求体必须为空或是空 JSON 对象。"
            )
            return false
        }
        if (root.length() != 0) {
            writeError(
                socket,
                "400 Bad Request",
                "unsupported_tuning_fields",
                "该调优控制操作不接受请求字段。"
            )
            return false
        }
        return true
    }

    private fun cachedIdempotencyResult(
        idempotencyKey: String,
        fingerprint: String
    ): LocalApiControlResult? = synchronized(idempotencyLock) {
        val existing = idempotencyRecords[idempotencyKey] ?: return@synchronized null
        if (existing.fingerprint == fingerprint) {
            existing.result
        } else {
            idempotencyConflict(existing.fingerprint, fingerprint)
        }
    }

    /**
     * Executes a tuning mutation at most once for one key during this app process. The
     * canonical request fingerprint is stored with the complete structured result, so an exact
     * retry replays the original status/body while reuse for another request is rejected. The
     * coordinator still receives the key and must journal it for process-restart durability.
     * The synchronized operation also closes the check/act race between lifecycle preflight and
     * starting a second control-plane mutation.
     */
    private fun executeIdempotent(
        idempotencyKey: String,
        fingerprint: String,
        lifecyclePrecondition: () -> LocalApiControlResult.Rejected? = { null },
        operation: () -> LocalApiControlResult
    ): LocalApiControlResult = synchronized(idempotencyLock) {
        val existing = idempotencyRecords[idempotencyKey]
        if (existing != null) {
            if (existing.fingerprint == fingerprint) {
                return@synchronized existing.result
            }
            return@synchronized idempotencyConflict(existing.fingerprint, fingerprint)
        }
        // A transient lifecycle conflict occurs before dispatch and therefore does not consume
        // the key. The caller can honor Retry-After and retry the same logical mutation later.
        lifecyclePrecondition()?.let { return@synchronized it }
        operation().also { result ->
            idempotencyRecords[idempotencyKey] = IdempotencyRecord(fingerprint, result)
        }
    }

    private fun idempotencyConflict(
        originalFingerprint: String,
        conflictingFingerprint: String
    ): LocalApiControlResult.Rejected = LocalApiControlResult.Rejected(
        httpStatus = 409,
        code = "idempotency_key_conflict",
        message = "Idempotency-Key 已用于不同的调优控制请求。",
        detailsJson = JSONObject()
            .put("originalRequestFingerprint", originalFingerprint)
            .put("conflictingRequestFingerprint", conflictingFingerprint)
            .toString()
    )

    private fun requestFingerprint(method: String, path: String, body: String): String {
        val canonicalBody = canonicalJsonBody(body)
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "$method\n$path\n$canonicalBody".toByteArray(Charsets.UTF_8)
        )
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX_CHARS[value ushr 4])
                append(HEX_CHARS[value and 0x0f])
            }
        }
    }

    private fun canonicalJsonBody(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ""
        val value: Any = runCatching { JSONObject(trimmed) }.getOrNull()
            ?: runCatching { JSONArray(trimmed) }.getOrNull()
            ?: return trimmed
        return canonicalJsonValue(value)
    }

    private fun canonicalJsonValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(
            prefix = "{",
            postfix = "}",
            separator = ","
        ) { key -> "${JSONObject.quote(key)}:${canonicalJsonValue(value.opt(key))}" }
        is JSONArray -> (0 until value.length()).joinToString(
            prefix = "[",
            postfix = "]",
            separator = ","
        ) { index -> canonicalJsonValue(value.opt(index)) }
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun requireValidIdempotencyKey(socket: Socket, idempotencyKey: String): Boolean {
        if (idempotencyKey.isBlank()) {
            writeError(
                socket,
                "400 Bad Request",
                "idempotency_key_required",
                "调优控制写操作必须携带 Idempotency-Key。"
            )
            return false
        }
        if (idempotencyKey.length > MAX_IDEMPOTENCY_KEY_LENGTH ||
            !IDEMPOTENCY_KEY_PATTERN.matches(idempotencyKey)) {
            writeError(
                socket,
                "400 Bad Request",
                "invalid_idempotency_key",
                "Idempotency-Key 长度或字符无效。"
            )
            return false
        }
        return true
    }

    private fun parseTuningJobId(path: String): String? {
        val suffix = path.removePrefix(TUNING_JOB_PATH_PREFIX)
        if (suffix.isBlank() || suffix.contains('/')) return null
        // An empty selector is the internal representation of the current job. Returning null
        // here would make the valid `/current` route indistinguishable from malformed input.
        if (suffix.equals("current", ignoreCase = true)) return ""
        return suffix.takeIf(::isValidTuningJobId)
    }

    private fun writeControlResult(socket: Socket, result: LocalApiControlResult) {
        when (result) {
            is LocalApiControlResult.Rejected -> {
                if (result.httpStatus !in 400..599) {
                    writeError(
                        socket,
                        "500 Internal Server Error",
                        "control_plane_invalid_response",
                        "The runtime control plane returned an invalid error status."
                    )
                    return
                }
                writeError(
                    socket = socket,
                    status = result.httpStatus.toHttpStatus(),
                    code = result.code.ifBlank { "control_plane_rejected" },
                    message = result.message.ifBlank { "The runtime control plane rejected the request." },
                    detailsJson = result.detailsJson,
                    retryAfterMs = result.retryAfterMs
                )
            }

            is LocalApiControlResult.Success -> {
                val body = result.json.trim()
                val validJson = runCatching { JSONObject(body) }.isSuccess ||
                    runCatching { JSONArray(body) }.isSuccess
                if (!validJson || body.isBlank()) {
                    writeError(
                        socket,
                        "500 Internal Server Error",
                        "control_plane_invalid_response",
                        "The runtime control plane returned invalid JSON for a tuning request."
                    )
                    return
                }
                if (result.httpStatus !in 200..299) {
                    writeError(
                        socket,
                        "500 Internal Server Error",
                        "control_plane_invalid_response",
                        "The runtime control plane returned an invalid success status."
                    )
                    return
                }
                val status = result.httpStatus.toHttpStatus()
                val retryHeaders = if (result.retryAfterMs > 0L) {
                    mapOf(
                        "Retry-After" to ((result.retryAfterMs + 999L) / 1000L).coerceAtLeast(1L).toString(),
                        "X-Retry-After-Ms" to result.retryAfterMs.toString()
                    )
                } else {
                    emptyMap()
                }
                writeJson(socket, LocalApiRuntime.publicJson(body), status, retryHeaders)
            }
        }
    }

    private fun isValidTuningJobId(jobId: String): Boolean =
        jobId.length in 1..128 && TUNING_JOB_ID_PATTERN.matches(jobId)

    private suspend fun streamChat(socket: Socket, request: ChatRequest) {
        val requestId = "chatcmpl-${UUID.randomUUID().toString().replace("-", "")}"
        val sequenceBefore = LocalApiRuntime.generationSequence()
        val stream = LocalApiRuntime.streamChat(
            request,
            LocalChatExecutionContext(requestId = requestId)
        )
        if (stream == null) {
            writeError(
                socket,
                "503 Service Unavailable",
                "engine_unavailable",
                "MCA engine is not attached.",
                generationTraceJson(requestId, null).toString()
            )
            return
        }
        val created = System.currentTimeMillis() / 1000
        val output = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
        var hasVisibleContent = false
        var terminalSent = false
        var finalStats: RuntimeStats? = null
        var generationSequence: Long? = null
        fun captureGenerationSequence() {
            generationSequence = generationSequence
                ?: LocalApiRuntime.generationSequence()?.takeIf { current ->
                    sequenceBefore?.let { current > it } ?: (current > 0L)
                }
            generationSequence?.let { LocalApiRuntime.recordGenerationSequence(requestId, it) }
        }
        fun writeTerminalFrame(includeEmptyVisibleError: Boolean = true) {
            if (terminalSent) return
            if (includeEmptyVisibleError && !hasVisibleContent) {
                output.write(
                    "data: ${errorJson(
                        "generation_empty_visible_output",
                        emptyVisibleOutputMessage(finalStats, request.params),
                        generationTraceJson(requestId, generationSequence).toString()
                    )}\n\n"
                )
            }
            output.write("data: ${finishSseJson(requestId, created, generationSequence)}\n\n")
            output.write("data: [DONE]\n\n")
            terminalSent = true
        }
        output.write("HTTP/1.1 200 OK\r\n")
        output.write("Content-Type: text/event-stream; charset=utf-8\r\n")
        output.write("Cache-Control: no-cache\r\n")
        output.write("X-Accel-Buffering: no\r\n")
        output.write(corsHeaders())
        output.write("Connection: close\r\n\r\n")
        output.write("data: ${roleSseJson(requestId, created)}\n\n")
        output.flush()
        stream.collect { event ->
            captureGenerationSequence()
            when (event) {
                is GenerateEvent.Chunk -> {
                    finalStats = event.stats
                    if (event.reasoning.isNotBlank()) {
                        output.write(
                            "data: ${event.reasoning.toSseJson(requestId, created, generationSequence, reasoning = true)}\n\n"
                        )
                    }
                    if (event.text.isNotBlank()) {
                        hasVisibleContent = true
                        output.write("data: ${event.text.toSseJson(requestId, created, generationSequence)}\n\n")
                    }
                }
                is GenerateEvent.Done -> {
                    finalStats = event.stats
                    writeTerminalFrame()
                }
                is GenerateEvent.Error -> {
                    output.write(
                        "data: ${errorJson(
                            generationErrorCode(event),
                            event.message,
                            generationErrorDetails(event, requestId, generationSequence).toString()
                        )}\n\n"
                    )
                    writeTerminalFrame(includeEmptyVisibleError = false)
                }
            }
            output.flush()
        }
        writeTerminalFrame()
        output.flush()
    }

    private suspend fun completeChat(socket: Socket, request: ChatRequest) {
        val requestId = "chatcmpl-${UUID.randomUUID().toString().replace("-", "")}"
        val sequenceBefore = LocalApiRuntime.generationSequence()
        val stream = LocalApiRuntime.streamChat(
            request,
            LocalChatExecutionContext(requestId = requestId)
        )
        if (stream == null) {
            writeError(
                socket,
                "503 Service Unavailable",
                "engine_unavailable",
                "MCA engine is not attached.",
                generationTraceJson(requestId, null).toString()
            )
            return
        }
        val created = System.currentTimeMillis() / 1000
        val builder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        var generationError: GenerateEvent.Error? = null
        var finalStats: RuntimeStats? = null
        var generationSequence: Long? = null
        stream.collect { event ->
            generationSequence = generationSequence
                ?: LocalApiRuntime.generationSequence()?.takeIf { current ->
                    sequenceBefore?.let { current > it } ?: (current > 0L)
                }
            generationSequence?.let { LocalApiRuntime.recordGenerationSequence(requestId, it) }
            when (event) {
                is GenerateEvent.Chunk -> {
                    finalStats = event.stats
                    if (event.text.isNotBlank()) builder.append(event.text)
                    if (event.reasoning.isNotBlank()) reasoningBuilder.append(event.reasoning)
                }
                is GenerateEvent.Done -> finalStats = event.stats
                is GenerateEvent.Error -> generationError = event
            }
        }
        if (generationError != null) {
            val error = requireNotNull(generationError)
            writeError(
                socket,
                generationErrorStatus(error),
                generationErrorCode(error),
                error.message,
                generationErrorDetails(error, requestId, generationSequence).toString()
            )
            return
        }
        if (builder.isBlank()) {
            writeError(
                socket,
                "500 Internal Server Error",
                "generation_empty_visible_output",
                emptyVisibleOutputMessage(finalStats, request.params),
                generationTraceJson(requestId, generationSequence).toString()
            )
            return
        }
        val response = JSONObject()
            .put("id", requestId)
            .put("object", "chat.completion")
            .put("created", created)
            .put("model", currentModelName())
            .put("mca_trace", generationTraceJson(requestId, generationSequence))
            .put(
                "choices",
                JSONArray().put(
                    run {
                        val message = JSONObject()
                            .put("role", "assistant")
                            .put("content", builder.toString())
                        if (reasoningBuilder.isNotBlank()) {
                            message.put("reasoning_content", reasoningBuilder.toString())
                        }
                        JSONObject()
                            .put("index", 0)
                            .put("message", message)
                            .put("finish_reason", "stop")
                    }
                )
            )
            .put(
                "usage",
                JSONObject()
                    .put("prompt_tokens", tokenOrNull(finalStats?.promptTokens))
                    .put("completion_tokens", tokenOrNull(finalStats?.completionTokens))
                    .put(
                        "total_tokens",
                        finalStats?.let { stats ->
                            val total = stats.promptTokens + stats.completionTokens
                            if (total > 0) total else JSONObject.NULL
                        } ?: JSONObject.NULL
                    )
            )
        writeJson(socket, response.toString())
    }

    private fun isPublicRoute(method: String, path: String): Boolean =
        method == "HEAD" && path == "/health" ||
            method == "GET" && (
                path == "/health" ||
                    path == "/" ||
                    path == "/index.html" ||
                    path == "/v1/models" ||
                    path == "/models"
                )

    private fun writeJson(
        socket: Socket,
        body: String,
        status: String = "200 OK",
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        writeText(socket, body, status, "application/json; charset=utf-8", extraHeaders)
    }

    private fun writeHtml(socket: Socket, body: String, status: String = "200 OK") {
        writeText(socket, body, status, "text/html; charset=utf-8")
    }

    private fun writeError(
        socket: Socket,
        status: String,
        code: String,
        message: String,
        detailsJson: String = "{}",
        retryAfterMs: Long = 0L
    ) {
        val retryHeaders = if (retryAfterMs > 0L) {
            mapOf(
                "Retry-After" to ((retryAfterMs + 999L) / 1000L).coerceAtLeast(1L).toString(),
                "X-Retry-After-Ms" to retryAfterMs.toString()
            )
        } else {
            emptyMap()
        }
        writeJson(
            socket,
            errorJson(
                code,
                LocalApiRuntime.publicMessage(message),
                LocalApiRuntime.publicJson(detailsJson)
            ).toString(),
            status,
            retryHeaders
        )
    }

    private fun errorJson(code: String, message: String, detailsJson: String = "{}"): JSONObject =
        OpenAiApiCompat.errorJson(code, message, detailsJson)

    private fun writeNoContent(socket: Socket) {
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 204 No Content\r\n".toByteArray())
        output.write(corsHeaders().toByteArray())
        output.write("Content-Length: 0\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.flush()
    }

    private fun isAuthorized(headers: Map<String, String>): Boolean {
        if (apiKey.isBlank()) return true
        val authorization = headers["authorization"].orEmpty()
        val bearer = if (authorization.startsWith("Bearer ", ignoreCase = true)) {
            authorization.substringAfter(' ').trim()
        } else {
            authorization.trim()
        }
        val headerKey = headers["x-api-key"]?.trim()
        return bearer == apiKey || headerKey == apiKey
    }

    private fun writeText(
        socket: Socket,
        body: String,
        status: String = "200 OK",
        contentType: String = "text/plain; charset=utf-8",
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 $status\r\n".toByteArray())
        output.write("Content-Type: $contentType\r\n".toByteArray())
        output.write(corsHeaders().toByteArray())
        extraHeaders.forEach { (name, value) ->
            val safeName = name.filter { it.isLetterOrDigit() || it == '-' }
            val safeValue = value.replace("\r", "").replace("\n", "")
            if (safeName.isNotBlank()) {
                output.write("$safeName: $safeValue\r\n".toByteArray())
            }
        }
        output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun corsHeaders(): String = buildString {
        append(OpenAiApiCompat.corsHeaders())
    }

    private fun prepareGenerationRequest(
        route: String,
        body: String,
        streaming: Boolean
    ): PreparedGenerationRequest {
        val parsed = runCatching {
            OpenAiApiCompat.parseChatRequestChecked(body, LocalApiRuntime.generationParamsProvider())
        }.getOrElse { error ->
            return PreparedGenerationRequest.Rejected(
                httpStatus = 500,
                code = "preflight_failed",
                message = error.message ?: "Local API request preflight failed."
            )
        }
        val request = when (parsed) {
            is OpenAiChatParseResult.Success -> parsed.request
            is OpenAiChatParseResult.Rejected -> {
                return PreparedGenerationRequest.Rejected(
                    httpStatus = 409,
                    code = parsed.rejection.code,
                    message = parsed.rejection.message,
                    detailsJson = parsed.rejection.detailsJson
                )
            }
        }
        request.localApiVisionRejection()?.let { rejection ->
            return PreparedGenerationRequest.Rejected(
                httpStatus = 409,
                code = rejection.code,
                message = rejection.message
            )
        }
        val coordinatorResult = runCatching {
            LocalApiRuntime.preflight(
                LocalApiPreflightRequest(
                    route = route,
                    streaming = streaming,
                    requestedModel = OpenAiApiCompat.requestedModel(body),
                    chatRequest = request
                )
            )
        }.getOrElse { error ->
            return PreparedGenerationRequest.Rejected(
                httpStatus = 503,
                code = "coordinator_preflight_failed",
                message = error.message ?: "The runtime coordinator could not complete preflight."
            )
        }
        return when (coordinatorResult) {
            LocalApiPreflightResult.Ready ->
                request.contextLengthRejection() ?: PreparedGenerationRequest.Ready(request)
            is LocalApiPreflightResult.Rejected -> {
                val contextExceeded = coordinatorResult.isContextLengthExceeded()
                PreparedGenerationRequest.Rejected(
                    httpStatus = if (contextExceeded) 413 else coordinatorResult.httpStatus.coerceIn(400, 599),
                    code = if (contextExceeded) CONTEXT_LENGTH_EXCEEDED_CODE else coordinatorResult.code,
                    message = coordinatorResult.message,
                    retryAfterMs = coordinatorResult.retryAfterMs,
                    detailsJson = coordinatorResult.detailsJson
                )
            }
        }
    }

    private fun ChatRequest.contextLengthRejection(): PreparedGenerationRequest.Rejected? {
        val contextLength = params.nCtx.takeIf { it > 0 } ?: return null
        val budget = localContextWindowBudget(contextLength)
        val promptBudget = budget.promptBudgetTokens
        val systemTokens = messages.asSequence()
            .filter { it.role == Role.SYSTEM }
            .sumOf { estimateLocalApiTokens(it.content) } +
            estimateLocalApiTokens(params.systemPrompt) +
            estimateLocalApiTokens(runtimeSystemContext) +
            REASONING_INSTRUCTION_ESTIMATE_TOKENS
        val latestTurn = messages.lastOrNull { it.role != Role.SYSTEM }
        val latestTurnTokens = latestTurn?.let { estimateLocalApiTokens(it.content) } ?: 0L
        val requiredPromptTokens = systemTokens + latestTurnTokens
        if (
            promptBudget >= budget.minimumPromptBudgetTokens &&
            requiredPromptTokens <= promptBudget.toLong()
        ) {
            return null
        }
        val details = JSONObject()
            .put("n_ctx", contextLength)
            .put("context_length", contextLength)
            .put("max_context_length", contextLength)
            .put("estimated_prompt_tokens", requiredPromptTokens)
            .put("prompt_token_budget", promptBudget.coerceAtLeast(0))
        return PreparedGenerationRequest.Rejected(
            httpStatus = 413,
            code = CONTEXT_LENGTH_EXCEEDED_CODE,
            message = "The request exceeds the active local model context length. Shorten the latest input or reload the model with a larger n_ctx.",
            detailsJson = details.toString()
        )
    }

    /** Mirrors the engine's conservative fallback estimate before a tokenizer is available. */
    private fun estimateLocalApiTokens(text: String): Long {
        if (text.isEmpty()) return 1L
        var denseTokens = 0L
        var sparseCharacters = 0L
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            when {
                codePoint in 0x3400..0x9FFF ||
                    codePoint in 0xF900..0xFAFF ||
                    codePoint in 0x3040..0x30FF ||
                    codePoint in 0xAC00..0xD7AF -> denseTokens += 1L
                Character.isSupplementaryCodePoint(codePoint) -> denseTokens += 2L
                else -> sparseCharacters += 1L
            }
            index += Character.charCount(codePoint)
        }
        return (denseTokens + (sparseCharacters + 2L) / 3L).coerceAtLeast(1L)
    }

    private fun writePreflightRejection(socket: Socket, rejection: PreparedGenerationRequest.Rejected) {
        writeError(
            socket = socket,
            status = rejection.httpStatus.toHttpStatus(),
            code = rejection.code,
            message = rejection.message,
            detailsJson = rejection.detailsJson,
            retryAfterMs = rejection.retryAfterMs
        )
    }

    private fun Int.toHttpStatus(): String = when (this) {
        200 -> "200 OK"
        201 -> "201 Created"
        202 -> "202 Accepted"
        204 -> "204 No Content"
        400 -> "400 Bad Request"
        401 -> "401 Unauthorized"
        403 -> "403 Forbidden"
        404 -> "404 Not Found"
        408 -> "408 Request Timeout"
        409 -> "409 Conflict"
        413 -> "413 Payload Too Large"
        422 -> "422 Unprocessable Entity"
        429 -> "429 Too Many Requests"
        501 -> "501 Not Implemented"
        500 -> "500 Internal Server Error"
        502 -> "502 Bad Gateway"
        503 -> "503 Service Unavailable"
        504 -> "504 Gateway Timeout"
        else -> if (this in 200..299) "$this Success" else "$this Error"
    }

    private fun localApiRuntimeStateJson(): String {
        val busy = runCatching { LocalApiRuntime.busyState() }.getOrDefault(LocalApiBusyState.IDLE)
        return JSONObject()
            .put("busy", busy.busy)
            .put("code", busy.code)
            .put("message", LocalApiRuntime.publicMessage(busy.message))
            .put("retry_after_ms", busy.retryAfterMs.coerceAtLeast(0L))
            .put("details", LocalApiRuntime.publicJson(busy.detailsJson).toJsonValue())
            .put("trace", LocalApiRuntime.traceJson().toJsonValue())
            .put("profile", LocalApiRuntime.publicProfileJson().toJsonValue())
            .put("tuning", LocalApiRuntime.publicTuningJson().toJsonValue())
            .toString()
    }

    private fun String.toJsonValue(): Any =
        runCatching { JSONObject(this) }.getOrNull()
            ?: runCatching { JSONArray(this) }.getOrNull()
            ?: this

    private fun String.isStreamingRequest(headers: Map<String, String>): Boolean {
        val accept = headers["accept"].orEmpty()
        return OpenAiApiCompat.isStreamingRequest(this) ||
            STREAM_TRUE_PATTERN.containsMatchIn(this) ||
            accept.contains("text/event-stream", ignoreCase = true)
    }

    private fun String.toSseJson(
        requestId: String,
        created: Long,
        generationSequence: Long? = null,
        reasoning: Boolean = false
    ): String = JSONObject()
        .put("id", requestId)
        .put("object", "chat.completion.chunk")
        .put("created", created)
        .put("model", currentModelName())
        .put("mca_trace", generationTraceJson(requestId, generationSequence))
        .put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put(
                        "delta",
                        if (reasoning) {
                            JSONObject().put("reasoning_content", this)
                        } else {
                            JSONObject().put("content", this)
                        }
                    )
                    .put("finish_reason", JSONObject.NULL)
            )
        )
        .toString()

    private fun roleSseJson(requestId: String, created: Long): String = JSONObject()
        .put("id", requestId)
        .put("object", "chat.completion.chunk")
        .put("created", created)
        .put("model", currentModelName())
        .put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put("delta", JSONObject().put("role", "assistant"))
                    .put("finish_reason", JSONObject.NULL)
            )
        )
        .toString()

    private fun finishSseJson(
        requestId: String,
        created: Long,
        generationSequence: Long?
    ): String = JSONObject()
        .put("id", requestId)
        .put("object", "chat.completion.chunk")
        .put("created", created)
        .put("model", currentModelName())
        .put("mca_trace", generationTraceJson(requestId, generationSequence))
        .put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put("delta", JSONObject())
                    .put("finish_reason", "stop")
            )
        )
        .toString()

    private fun generationTraceJson(requestId: String, generationSequence: Long?): JSONObject =
        JSONObject()
            .put("requestId", requestId)
            .apply {
                generationSequence?.let { put("generationSequence", it) }
            }

    private fun generationErrorDetails(
        error: GenerateEvent.Error,
        requestId: String,
        generationSequence: Long?
    ): JSONObject = generationTraceJson(requestId, generationSequence).apply {
        error.action?.let { put("action", it) }
        if (error.changedFields.isNotEmpty()) {
            put("changedFields", JSONArray(error.changedFields.sorted()))
        }
    }

    private fun currentModelName(): String =
        runCatching {
            val json = JSONObject(LocalApiRuntime.loadedModelJsonProvider())
            json.optString("displayName")
                .ifBlank { json.optString("id") }
                .ifBlank { "mca-local-model" }
        }.getOrDefault("mca-local-model")

    private fun tokenOrNull(value: Int?): Any =
        value?.takeIf { it > 0 } ?: JSONObject.NULL

    private fun ChatRequest.localApiVisionRejection(): LocalApiVisionRejection? {
        if (!hasImageAttachments()) return null
        val capability = currentLocalApiVisionCapability()
        if (!capability.runtime.isMnnRuntimeToken()) return null
        if (!capability.visionReady) {
            return LocalApiVisionRejection(
                code = "mnn_vision_not_ready",
                message = "本地 MNN 模型「${capability.displayName}」没有就绪的视觉组件，图片请求未下发。" +
                    "请加载包含可读 visual.mnn 的完整多模态包后重试。"
            )
        }
        return null
    }

    private fun ChatRequest.hasImageAttachments(): Boolean =
        messages.any { message ->
            message.imageAttachments.any { attachment ->
                attachment.hasInlineData || attachment.uriString.isNotBlank()
            }
        }

    private fun currentLocalApiVisionCapability(): LocalApiVisionCapability {
        val loaded = LocalApiRuntime.loadedModelJsonProvider().toJsonObject() ?: JSONObject()
        val modelId = loaded.optString("id")
        val catalogEntry = currentCatalogEntry(modelId)
        val nativeStats = LocalApiRuntime.engine?.nativeStatsJson().toJsonObject()
        val records = listOfNotNull(loaded, nativeStats, catalogEntry)
        val runtime = records.firstNotNullOfOrNull { it.runtimeToken().takeIf(String::isNotBlank) }.orEmpty()
        val displayName = listOf(
            loaded.firstNonBlank("displayName", "display_name", "name"),
            catalogEntry?.firstNonBlank("displayName", "display_name", "name").orEmpty(),
            modelId
        ).firstOrNull { it.isNotBlank() }.orEmpty().ifBlank { "当前 MNN 模型" }
        return LocalApiVisionCapability(
            displayName = displayName,
            runtime = runtime,
            visionReady = records.firstNotNullOfOrNull { it.visionReadyFlag() } ?: false
        )
    }

    private fun currentCatalogEntry(modelId: String): JSONObject? {
        if (modelId.isBlank()) return null
        val root = LocalApiRuntime.modelsJsonProvider().toJsonObject() ?: return null
        val data = root.optJSONArray("data") ?: return null
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            if (item.optString("id") == modelId) return item
        }
        return null
    }

    private fun String?.toJsonObject(): JSONObject? =
        runCatching { JSONObject(orEmpty()) }.getOrNull()

    private fun JSONObject.firstNonBlank(vararg keys: String): String =
        keys.asSequence().map(::optString).firstOrNull { it.isNotBlank() }.orEmpty()

    private fun JSONObject.runtimeToken(): String =
        firstNonBlank("runtime", "backend").ifBlank {
            optJSONObject("stats")?.firstNonBlank("runtime", "backend").orEmpty()
        }

    private fun JSONObject.visionReadyFlag(): Boolean? =
        explicitBoolean("vision_ready", "visionReady", "supports_vision", "supportsVision")
            ?: optJSONObject("stats")?.visionReadyFlag()
            ?: optJSONObject("capabilities")?.explicitBoolean("supportsVision", "supports_vision")

    private fun JSONObject.explicitBoolean(vararg keys: String): Boolean? {
        for (key in keys) {
            if (has(key) && !isNull(key)) return optBoolean(key)
        }
        return null
    }

    private data class LocalApiVisionCapability(
        val displayName: String,
        val runtime: String,
        val visionReady: Boolean
    )

    private data class LocalApiVisionRejection(
        val code: String,
        val message: String
    )

    private fun String.isMnnRuntimeToken(): Boolean {
        val normalized = trim().lowercase()
        return normalized == "mnn" ||
            normalized == "mnn_cpu" ||
            normalized == "mnn_llm" ||
            normalized == "mnn-llm" ||
            normalized.startsWith("mnn_") ||
            normalized.startsWith("mnn-")
    }

    private fun generationErrorStatus(error: GenerateEvent.Error): String {
        if (error.isContextLengthExceeded()) return "413 Payload Too Large"
        if (error.code in setOf(
                "model_mismatch",
                "active_profile_drift",
                "model_reload_required",
                "model_reload_required_authorized",
                "execution_override_forbidden",
                "model_behavior_override_forbidden"
            )
        ) {
            return "409 Conflict"
        }
        val message = error.message
        val normalized = message.lowercase()
        return if (
            "请先在模型页加载" in message ||
            "no gguf model is loaded" in normalized ||
            "engine is not attached" in normalized ||
            "no backends are loaded" in normalized
        ) {
            "503 Service Unavailable"
        } else {
            "500 Internal Server Error"
        }
    }

    private fun generationErrorCode(error: GenerateEvent.Error): String =
        if (error.isContextLengthExceeded()) CONTEXT_LENGTH_EXCEEDED_CODE
        else error.code ?: "generation_failed"

    private fun GenerateEvent.Error.isContextLengthExceeded(): Boolean =
        code.equals(CONTEXT_LENGTH_EXCEEDED_CODE, ignoreCase = true) ||
            message.isContextLengthExceededMessage()

    private fun LocalApiPreflightResult.Rejected.isContextLengthExceeded(): Boolean =
        code.equals(CONTEXT_LENGTH_EXCEEDED_CODE, ignoreCase = true) ||
            message.isContextLengthExceededMessage()

    private fun String.isContextLengthExceededMessage(): Boolean {
        val normalized = lowercase()
        return "上下文预算过小" in this ||
            "超过本机安全上下文预算" in this ||
            "历史上下文过长" in this ||
            "超出上下文" in this ||
            "超过上下文" in this ||
            "context_length_exceeded" in normalized ||
            "context length exceeded" in normalized ||
            "maximum context length" in normalized ||
            ("context window" in normalized && ("exceed" in normalized || "too long" in normalized)) ||
            ("prompt" in normalized && "too long" in normalized) ||
            "too many tokens" in normalized
    }

    private fun emptyVisibleOutputMessage(stats: RuntimeStats?, params: GenerationParams): String {
        val generated = stats?.completionTokens?.takeIf { it > 0 }
        val limit = params.nPredict
        val recommended = when {
            limit < 2048 -> "4096"
            limit < 4096 -> "8192"
            limit < 8192 -> "16384"
            else -> "更高的输出长度"
        }
        return buildString {
            append("模型本轮")
            if (generated != null) append("已生成 ").append(generated).append(" token，")
            append("但没有产出可见正文。通常是思考模型把输出预算消耗在推理阶段，或思考内容被隐藏。")
            append("当前 max_tokens/n_predict=").append(limit).append("；")
            append("建议提高到 ").append(recommended).append("，或设置 reasoning_mode=off、hide_reasoning=true 后重试。")
        }
    }

    private fun readHttpRequest(socket: Socket): HttpRequest {
        val input = socket.getInputStream()
        val buffer = ByteArrayOutputStream()
        var headerEnd = -1
        while (headerEnd < 0) {
            val read = input.read()
            if (read < 0) break
            buffer.write(read)
            headerEnd = findHeaderEnd(buffer.toByteArray())
            if (buffer.size() > MAX_HEADER_BYTES) error("HTTP header too large.")
        }

        val raw = buffer.toByteArray()
        val safeHeaderEnd = headerEnd.takeIf { it >= 0 } ?: raw.size
        val bodyStart = when {
            raw.copyOfRange(0, safeHeaderEnd).endsWithBytes(CRLFCRLF) -> safeHeaderEnd
            raw.copyOfRange(0, safeHeaderEnd).endsWithBytes(LFLF) -> safeHeaderEnd
            else -> safeHeaderEnd
        }
        val headerText = String(raw.copyOfRange(0, bodyStart), Charsets.ISO_8859_1)
            .trimEnd('\r', '\n')
        val headerLines = headerText.split(Regex("\r?\n"))
        val requestLine = headerLines.firstOrNull().orEmpty()
        val headers = mutableMapOf<String, String>()
        headerLines.drop(1).forEach { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) headers[parts[0].trim().lowercase()] = parts[1].trim()
        }
        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val alreadyRead = raw.copyOfRange(bodyStart, raw.size)
        val bodyBytes = ByteArray(contentLength)
        val copied = alreadyRead.size.coerceAtMost(contentLength)
        alreadyRead.copyInto(bodyBytes, endIndex = copied)
        var offset = copied
        while (offset < contentLength) {
            val read = input.read(bodyBytes, offset, contentLength - offset)
            if (read <= 0) break
            offset += read
        }
        return HttpRequest(
            requestLine = requestLine,
            headers = headers,
            body = String(bodyBytes.copyOf(offset), Charsets.UTF_8)
        )
    }

    private fun findHeaderEnd(bytes: ByteArray): Int {
        val crlf = bytes.indexOfSequence(CRLFCRLF)
        if (crlf >= 0) return crlf + CRLFCRLF.size
        val lf = bytes.indexOfSequence(LFLF)
        return if (lf >= 0) lf + LFLF.size else -1
    }

    private fun ByteArray.indexOfSequence(target: ByteArray): Int {
        if (target.isEmpty() || size < target.size) return -1
        for (index in 0..(size - target.size)) {
            var matched = true
            for (targetIndex in target.indices) {
                if (this[index + targetIndex] != target[targetIndex]) {
                    matched = false
                    break
                }
            }
            if (matched) return index
        }
        return -1
    }

    private fun ByteArray.endsWithBytes(target: ByteArray): Boolean {
        if (size < target.size) return false
        for (index in target.indices) {
            if (this[size - target.size + index] != target[index]) return false
        }
        return true
    }

    private sealed interface PreparedGenerationRequest {
        data class Ready(val request: ChatRequest) : PreparedGenerationRequest

        data class Rejected(
            val httpStatus: Int,
            val code: String,
            val message: String,
            val retryAfterMs: Long = 0L,
            val detailsJson: String = "{}"
        ) : PreparedGenerationRequest
    }

    private data class HttpRequest(
        val requestLine: String,
        val headers: Map<String, String>,
        val body: String
    )

    private data class IdempotencyRecord(
        val fingerprint: String,
        val result: LocalApiControlResult
    )

    private companion object {
        private val idempotencyLock = Any()
        private val idempotencyRecords = object : LinkedHashMap<String, IdempotencyRecord>(
            MAX_IDEMPOTENCY_RECORDS + 1,
            0.75f,
            true
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, IdempotencyRecord>?
            ): Boolean = size > MAX_IDEMPOTENCY_RECORDS
        }
        private val GENERATION_PATHS = setOf("/v1/chat/completions", "/chat/completions", "/v1/completions", "/completion")
        private val IMAGE_GENERATION_PATHS = setOf("/v1/images/generations", "/images/generations")
        private val CRLFCRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        private val LFLF = byteArrayOf('\n'.code.toByte(), '\n'.code.toByte())
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val CLIENT_READ_TIMEOUT_MS = 15_000
        private const val SERVER_BACKLOG = 128
        private const val TAG = "McaLoopbackServer"
        private const val TUNING_JOB_PATH_PREFIX = "/v1/tuning/jobs/"
        private const val MAX_TUNING_MODEL_ID_LENGTH = 512
        private const val MAX_TUNING_PREFERENCE_LENGTH = 64
        private val TUNING_CREATE_FIELDS = setOf("modelId", "mode", "autoApply", "performancePreference")
        private val TUNING_MODES = setOf("quick", "standard", "deep", "power_save")
        private val TUNING_ACTIONS = setOf("pause", "resume", "cancel", "apply")
        private val TUNING_PREFERENCE_PATTERN = Regex("[A-Za-z0-9_.:-]+")
        private const val MAX_IDEMPOTENCY_KEY_LENGTH = 256
        private const val MAX_IDEMPOTENCY_RECORDS = 1024
        private const val CONTEXT_LENGTH_EXCEEDED_CODE = "context_length_exceeded"
        private const val REASONING_INSTRUCTION_ESTIMATE_TOKENS = 96L
        private const val HEX_CHARS = "0123456789abcdef"
        private val IDEMPOTENCY_KEY_PATTERN = Regex("[\u0021-\u007E]{1,256}")
        private val TUNING_JOB_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private val STREAM_TRUE_PATTERN = Regex(""""stream"\s*:\s*(true|1|"true")""", RegexOption.IGNORE_CASE)
        private val threadIds = AtomicInteger(1)

        private fun newDispatcher(): ExecutorCoroutineDispatcher =
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "MCA-Local-API-${threadIds.getAndIncrement()}").apply {
                    isDaemon = true
                }
            }.asCoroutineDispatcher()

        private fun logInfo(message: String) {
            runCatching { Log.i(TAG, message) }
        }

        private fun logDebug(message: String) {
            runCatching { Log.d(TAG, message) }
        }

        private fun logWarning(message: String, error: Throwable) {
            runCatching { Log.w(TAG, message, error) }
        }
    }

    private fun chatPageHtml(): String = """
        <!doctype html>
        <html lang="zh-CN">
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>MCA Web Chat</title>
            <style>
                :root {
                    color-scheme: light;
                    --primary: #1a73e8;
                    --primary-soft: #e8f0fe;
                    --bg: #f8f9fa;
                    --surface: #ffffff;
                    --text: #202124;
                    --muted: #5f6368;
                    --line: #e5e7eb;
                    --danger: #d93025;
                }
                * { box-sizing: border-box; }
                body {
                    margin: 0;
                    background: var(--bg);
                    color: var(--text);
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif;
                }
                .app {
                    max-width: 900px;
                    height: 100vh;
                    margin: 0 auto;
                    display: flex;
                    flex-direction: column;
                    background: var(--surface);
                    border-left: 1px solid var(--line);
                    border-right: 1px solid var(--line);
                }
                header {
                    padding: 14px 18px;
                    border-bottom: 1px solid var(--line);
                    display: grid;
                    grid-template-columns: 1fr auto;
                    gap: 12px;
                    align-items: center;
                }
                h1 { margin: 0; font-size: 18px; }
                .sub { margin-top: 4px; color: var(--muted); font-size: 12px; }
                .keybar {
                    display: flex;
                    gap: 8px;
                    align-items: center;
                    flex-wrap: wrap;
                    justify-content: flex-end;
                }
                input, textarea, button {
                    font: inherit;
                }
                input {
                    width: 260px;
                    padding: 9px 10px;
                    border: 1px solid var(--line);
                    border-radius: 10px;
                    outline: none;
                }
                input:focus, textarea:focus { border-color: var(--primary); }
                button {
                    border: 0;
                    border-radius: 10px;
                    padding: 9px 14px;
                    background: var(--primary);
                    color: white;
                    cursor: pointer;
                    font-weight: 600;
                }
                button.secondary {
                    background: var(--primary-soft);
                    color: var(--primary);
                }
                button.danger {
                    background: #fce8e6;
                    color: var(--danger);
                }
                button:disabled {
                    opacity: .55;
                    cursor: not-allowed;
                }
                .messages {
                    flex: 1;
                    overflow: auto;
                    padding: 18px;
                    display: flex;
                    flex-direction: column;
                    gap: 14px;
                }
                .msg {
                    max-width: 78%;
                    padding: 12px 14px;
                    border-radius: 16px;
                    white-space: pre-wrap;
                    line-height: 1.55;
                    word-break: break-word;
                }
                .user {
                    align-self: flex-end;
                    background: var(--primary);
                    color: white;
                    border-bottom-right-radius: 4px;
                }
                .assistant {
                    align-self: flex-start;
                    background: #f1f3f4;
                    color: var(--text);
                    border-bottom-left-radius: 4px;
                }
                .reasoning {
                    align-self: flex-start;
                    max-width: 78%;
                    color: var(--muted);
                    background: transparent;
                    border-left: 3px solid var(--line);
                    padding: 4px 0 4px 12px;
                    font-size: 13px;
                    line-height: 1.55;
                    white-space: pre-wrap;
                    word-break: break-word;
                }
                .reasoning summary {
                    cursor: pointer;
                    font-weight: 600;
                    color: var(--muted);
                    margin-bottom: 6px;
                }
                .empty {
                    margin: auto;
                    text-align: center;
                    color: var(--muted);
                    max-width: 520px;
                }
                .composer {
                    border-top: 1px solid var(--line);
                    padding: 14px;
                    display: grid;
                    grid-template-columns: 1fr auto auto auto;
                    gap: 10px;
                    align-items: end;
                    background: var(--surface);
                }
                .compose-main {
                    display: grid;
                    gap: 8px;
                }
                .attachments {
                    display: flex;
                    gap: 8px;
                    overflow-x: auto;
                }
                .attachment {
                    display: inline-flex;
                    align-items: center;
                    gap: 8px;
                    max-width: 220px;
                    padding: 6px 8px;
                    border-radius: 999px;
                    background: var(--primary-soft);
                    color: var(--primary);
                    font-size: 12px;
                    font-weight: 600;
                }
                .attachment img {
                    width: 24px;
                    height: 24px;
                    border-radius: 7px;
                    object-fit: cover;
                }
                .attachment span {
                    overflow: hidden;
                    text-overflow: ellipsis;
                    white-space: nowrap;
                }
                .attachment button {
                    width: 22px;
                    height: 22px;
                    padding: 0;
                    border-radius: 50%;
                    background: rgba(26, 115, 232, .12);
                    color: var(--primary);
                    font-weight: 700;
                }
                textarea {
                    width: 100%;
                    min-height: 54px;
                    max-height: 180px;
                    resize: vertical;
                    border: 1px solid var(--line);
                    border-radius: 14px;
                    padding: 12px;
                    outline: none;
                }
                .status {
                    display: inline-flex;
                    gap: 6px;
                    align-items: center;
                    padding: 5px 8px;
                    border-radius: 999px;
                    background: var(--primary-soft);
                    color: var(--primary);
                    font-size: 12px;
                    font-weight: 600;
                }
                .dot {
                    width: 7px;
                    height: 7px;
                    border-radius: 50%;
                    background: #34a853;
                }
                @media (max-width: 700px) {
                    header { grid-template-columns: 1fr; }
                    .keybar { justify-content: stretch; }
                    input { width: 100%; }
                    .composer { grid-template-columns: 1fr; }
                    .msg { max-width: 92%; }
                }
            </style>
        </head>
        <body>
            <div class="app">
                <header>
                    <div>
                        <h1>MCA Web Chat</h1>
                        <div class="sub">
                            <span class="status"><span class="dot"></span><span id="modelText">连接中...</span></span>
                            <span id="apiText"> 请填写 MCA 页面显示的 API Key</span>
                        </div>
                    </div>
                    <div class="keybar">
                        <input id="apiKey" type="password" placeholder="API Key">
                        <button class="secondary" id="saveKey">保存 Key</button>
                        <button class="secondary" id="clearChat">清空</button>
                    </div>
                </header>
                <main id="messages" class="messages">
                    <div class="empty">
                        在手机 MCA 中开启“开放端口”后，这个页面就可以从电脑访问手机端本地模型。<br>
                        这里不会调用云端推理，消息会发送到当前手机上的 MCA 服务。
                    </div>
                </main>
                <div class="composer">
                    <div class="compose-main">
                        <div id="attachments" class="attachments"></div>
                        <textarea id="input" placeholder="输入消息，Enter 发送，Shift + Enter 换行"></textarea>
                    </div>
                    <button class="secondary" id="pickImage">图片</button>
                    <button id="send">发送</button>
                    <button id="stop" class="danger" disabled>停止</button>
                    <input id="imageInput" type="file" accept="image/*" multiple hidden>
                </div>
            </div>
            <script>
                var apiKeyInput = document.getElementById('apiKey');
                var messagesEl = document.getElementById('messages');
                var inputEl = document.getElementById('input');
                var sendBtn = document.getElementById('send');
                var stopBtn = document.getElementById('stop');
                var pickImageBtn = document.getElementById('pickImage');
                var imageInput = document.getElementById('imageInput');
                var attachmentsEl = document.getElementById('attachments');
                var modelText = document.getElementById('modelText');
                var apiText = document.getElementById('apiText');
                var controller = null;
                var chatMessages = [];
                var pendingImages = [];
                var maxImageBytes = 8 * 1024 * 1024;
                var maxImagesPerTurn = 4;

                apiKeyInput.value = localStorage.getItem('mca_api_key') || '';

                document.getElementById('saveKey').onclick = function () {
                    localStorage.setItem('mca_api_key', apiKeyInput.value.trim());
                    loadModels();
                };
                document.getElementById('clearChat').onclick = function () {
                    chatMessages = [];
                    pendingImages = [];
                    renderAttachments();
                    messagesEl.innerHTML = '<div class="empty">对话已清空。</div>';
                };
                stopBtn.onclick = async function () {
                    if (controller) controller.abort();
                    await fetch('/v1/generate/stop', {
                        method: 'POST',
                        headers: authHeaders()
                    }).catch(function () {});
                    setBusy(false);
                };
                sendBtn.onclick = sendMessage;
                pickImageBtn.onclick = function () {
                    if (!sendBtn.disabled) imageInput.click();
                };
                imageInput.onchange = async function () {
                    var files = Array.prototype.slice.call(imageInput.files || []);
                    for (var i = 0; i < files.length; i++) {
                        await addImageFile(files[i]);
                    }
                    imageInput.value = '';
                };
                inputEl.addEventListener('keydown', function (event) {
                    if (event.key === 'Enter' && !event.shiftKey) {
                        event.preventDefault();
                        sendMessage();
                    }
                });

                function authHeaders(extra) {
                    var headers = extra || {};
                    var key = apiKeyInput.value.trim();
                    if (key) headers['Authorization'] = 'Bearer ' + key;
                    return headers;
                }

                async function loadModels() {
                    if (!apiKeyInput.value.trim()) {
                        modelText.textContent = '未填写 Key';
                        return;
                    }
                    try {
                        var res = await fetch('/v1/models', { headers: authHeaders() });
                        if (!res.ok) throw new Error('HTTP ' + res.status);
                        var json = await res.json();
                        var first = json.data && json.data.length ? json.data[0] : null;
                        modelText.textContent = first ? (first.display_name || first.id) : '未发现模型';
                        var visionReady = first && first.vision_ready;
                        apiText.textContent = visionReady ? ' 已连接本机 MCA API · 识图就绪' : ' 已连接本机 MCA API';
                    } catch (error) {
                        modelText.textContent = '连接失败';
                        apiText.textContent = ' 请检查 Key、手机端开放端口和同网段网络';
                    }
                }

                function addBubble(role, text) {
                    var empty = messagesEl.querySelector('.empty');
                    if (empty) empty.remove();
                    var div = document.createElement('div');
                    div.className = 'msg ' + (role === 'user' ? 'user' : 'assistant');
                    div.textContent = text || '';
                    messagesEl.appendChild(div);
                    messagesEl.scrollTop = messagesEl.scrollHeight;
                    return div;
                }

                function addReasoningBox(beforeEl) {
                    var empty = messagesEl.querySelector('.empty');
                    if (empty) empty.remove();
                    var details = document.createElement('details');
                    details.className = 'reasoning';
                    var summary = document.createElement('summary');
                    summary.textContent = '思考过程';
                    var body = document.createElement('div');
                    details.appendChild(summary);
                    details.appendChild(body);
                    if (beforeEl && beforeEl.parentNode === messagesEl) {
                        messagesEl.insertBefore(details, beforeEl);
                    } else {
                        messagesEl.appendChild(details);
                    }
                    messagesEl.scrollTop = messagesEl.scrollHeight;
                    return body;
                }

                function renderAttachments() {
                    attachmentsEl.innerHTML = '';
                    pendingImages.forEach(function (item, index) {
                        var chip = document.createElement('div');
                        chip.className = 'attachment';
                        var img = document.createElement('img');
                        img.src = item.dataUrl;
                        var name = document.createElement('span');
                        name.textContent = item.name;
                        var remove = document.createElement('button');
                        remove.type = 'button';
                        remove.textContent = '×';
                        remove.onclick = function () {
                            pendingImages.splice(index, 1);
                            renderAttachments();
                        };
                        chip.appendChild(img);
                        chip.appendChild(name);
                        chip.appendChild(remove);
                        attachmentsEl.appendChild(chip);
                    });
                }

                function readAsDataUrl(file) {
                    return new Promise(function (resolve, reject) {
                        var reader = new FileReader();
                        reader.onload = function () { resolve(String(reader.result || '')); };
                        reader.onerror = function () { reject(reader.error || new Error('图片读取失败')); };
                        reader.readAsDataURL(file);
                    });
                }

                async function addImageFile(file) {
                    if (!file || !file.type || !file.type.startsWith('image/')) {
                        alert('请选择图片文件');
                        return;
                    }
                    if (file.size > maxImageBytes) {
                        alert('单张图片不能超过 8 MB');
                        return;
                    }
                    if (pendingImages.length >= maxImagesPerTurn) {
                        alert('每轮最多发送 4 张图片');
                        return;
                    }
                    pendingImages.push({
                        name: file.name || 'image',
                        mimeType: file.type || 'image/jpeg',
                        dataUrl: await readAsDataUrl(file)
                    });
                    renderAttachments();
                }

                function setBusy(busy) {
                    sendBtn.disabled = busy;
                    stopBtn.disabled = !busy;
                    inputEl.disabled = busy;
                    pickImageBtn.disabled = busy;
                }

                async function sendMessage() {
                    var text = inputEl.value.trim();
                    var imagesToSend = pendingImages.slice();
                    if ((!text && imagesToSend.length === 0) || sendBtn.disabled) return;
                    if (!apiKeyInput.value.trim()) {
                        alert('请先填写 MCA 页面里的 API Key');
                        return;
                    }
                    var effectiveText = text || '请描述这张图片。';
                    var contentParts = [];
                    if (effectiveText) contentParts.push({ type: 'text', text: effectiveText });
                    imagesToSend.forEach(function (image) {
                        contentParts.push({
                            type: 'image_url',
                            image_url: { url: image.dataUrl, detail: 'auto' }
                        });
                    });
                    var userMessage = imagesToSend.length
                        ? { role: 'user', content: contentParts }
                        : { role: 'user', content: effectiveText };
                    inputEl.value = '';
                    pendingImages = [];
                    renderAttachments();
                    addBubble('user', effectiveText + (imagesToSend.length ? '\n[已附加 ' + imagesToSend.length + ' 张图片]' : ''));
                    chatMessages.push(userMessage);
                    var reasoningBody = null;
                    var reasoningText = '';
                    var assistantBubble = addBubble('assistant', '');
                    var assistantText = '';
                    controller = new AbortController();
                    setBusy(true);
                    try {
                        var res = await fetch('/v1/chat/completions', {
                            method: 'POST',
                            headers: authHeaders({ 'Content-Type': 'application/json' }),
                            body: JSON.stringify({ messages: chatMessages, stream: true }),
                            signal: controller.signal
                        });
                        if (!res.ok) throw new Error('HTTP ' + res.status);
                        var reader = res.body.getReader();
                        var decoder = new TextDecoder('utf-8');
                        var buffer = '';
                        while (true) {
                            var chunk = await reader.read();
                            if (chunk.done) break;
                            buffer += decoder.decode(chunk.value, { stream: true });
                            var lines = buffer.split('\n');
                            buffer = lines.pop() || '';
                            for (var i = 0; i < lines.length; i++) {
                                var line = lines[i].trim();
                                if (!line.startsWith('data:')) continue;
                                var data = line.slice(5).trim();
                                if (data === '[DONE]') {
                                    buffer = '';
                                    break;
                                }
                                try {
                                    var json = JSON.parse(data);
                                    if (json.error) {
                                        assistantText += '\n[错误] ' + json.error;
                                    } else {
                                        var delta = (((json.choices || [])[0] || {}).delta || {});
                                        var reasoning = delta.reasoning_content || '';
                                        if (reasoning) {
                                            if (!reasoningBody) reasoningBody = addReasoningBox(assistantBubble);
                                            reasoningText += reasoning;
                                            reasoningBody.textContent = reasoningText;
                                        }
                                        assistantText += delta.content || '';
                                    }
                                    assistantBubble.textContent = assistantText;
                                    messagesEl.scrollTop = messagesEl.scrollHeight;
                                } catch (ignore) {}
                            }
                        }
                        chatMessages.push({ role: 'assistant', content: assistantText });
                    } catch (error) {
                        if (error.name !== 'AbortError') {
                            assistantBubble.textContent = assistantText || ('请求失败：' + error.message);
                        }
                    } finally {
                        setBusy(false);
                    }
                }

                loadModels();
            </script>
        </body>
        </html>
    """.trimIndent()
}

