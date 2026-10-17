package com.muyuchat.api.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import org.json.JSONArray
import org.json.JSONObject
import java.net.Socket
import java.security.MessageDigest
import java.util.LinkedHashMap

    internal suspend fun McaLoopbackServer.handleGenerationStop(socket: Socket, body: String) {
        val root = if (body.isBlank()) JSONObject() else runCatching { JSONObject(body) }.getOrElse {
            writeError(socket, "400 Bad Request", "invalid_request", "Stop body must be a JSON object.")
            return
        }
        if (root.keys().asSequence().any { it != "request_id" }) {
            writeError(socket, "400 Bad Request", "unsupported_parameter", "Only request_id is supported by this stop endpoint.")
            return
        }
        val requestedId = if (root.has("request_id")) {
            (root.opt("request_id") as? String)?.takeIf { REQUEST_ID_PATTERN.matches(it) } ?: run {
                writeError(socket, "400 Bad Request", "invalid_request_id", "request_id must be a valid request identifier.")
                return
            }
        } else {
            null
        }
        // Capture current ownership once. Every native stop uses that exact token, even if a
        // replacement request starts while this HTTP call waits for cancellation to finish.
        val chatLease = synchronized(generationLeaseLock) {
            activeGenerationLease?.takeIf {
                requestedId == null || it.requestId == requestedId || it.clientRequestId == requestedId
            }?.also { it.stopRequested = true }
        }
        val imageJobs = synchronized(imageRequestLock) {
            activeImageRequestJobs.filterKeys { requestedId == null || it == requestedId }.toList()
        }
        if (requestedId != null && chatLease == null && imageJobs.isEmpty()) {
            writeError(
                socket, "409 Conflict", "generation_not_active",
                "No active Local API generation matches this request_id.",
                requestedId?.let { JSONObject().put("request_id", it).toString() } ?: "{}"
            )
            return
        }
        val chatRequestId = chatLease?.requestId?.takeIf(String::isNotBlank)
        if (requestedId == null) {
            // Legacy operation stops the runtime's current generation, including callers
            // outside this server. A supplied ID never takes this global path.
            LocalApiRuntime.stopGeneration()
        } else if (chatRequestId != null) {
            LocalApiRuntime.stopGenerationIfRequestActive(chatRequestId)
        }
        chatLease?.job?.cancel(CancellationException("Local API generation was stopped by the client."))
        imageJobs.forEach { (_, job) -> job.cancel(CancellationException("Local API generation was stopped by the client.")) }
        imageJobs.map { it.second }.joinAll()
        writeJson(
            socket,
            JSONObject()
                .put("stopped", true)
                .put("request_id", requestedId ?: chatRequestId ?: imageJobs.firstOrNull()?.first ?: JSONObject.NULL)
                .toString()
        )
    }

    /**
     * Read-only tuning state endpoint. It deliberately goes through LocalApiRuntime's
     * coordinator bridge and does not use the generation busy gate, so it remains available while
     * a candidate is loading, validating, or being recovered.
     */
    internal fun McaLoopbackServer.handleTuningJobQuery(socket: Socket, path: String) {
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

    internal fun McaLoopbackServer.handleTuningJobCreate(socket: Socket, body: String, idempotencyKey: String) {
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

    internal fun McaLoopbackServer.handleTuningRollback(socket: Socket, body: String, idempotencyKey: String) {
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
    internal fun McaLoopbackServer.handleTuningJobAction(
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

    private fun McaLoopbackServer.requireEmptyControlBody(socket: Socket, body: String): Boolean {
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

    private fun McaLoopbackServer.requireValidIdempotencyKey(socket: Socket, idempotencyKey: String): Boolean {
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

    private fun McaLoopbackServer.writeControlResult(socket: Socket, result: LocalApiControlResult) {
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

    internal data class IdempotencyRecord(
        val fingerprint: String,
        val result: LocalApiControlResult
    )

        internal val idempotencyLock = Any()
        internal val idempotencyRecords = object : LinkedHashMap<String, IdempotencyRecord>(
            MAX_IDEMPOTENCY_RECORDS + 1,
            0.75f,
            true
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, IdempotencyRecord>?
            ): Boolean = size > MAX_IDEMPOTENCY_RECORDS
        }

        internal const val TUNING_JOB_PATH_PREFIX = "/v1/tuning/jobs/"
        private const val MAX_TUNING_MODEL_ID_LENGTH = 512
        private const val MAX_TUNING_PREFERENCE_LENGTH = 64
        private val TUNING_CREATE_FIELDS = setOf("modelId", "mode", "autoApply", "performancePreference")
        private val TUNING_MODES = setOf("quick", "standard", "deep", "power_save")
        private val TUNING_ACTIONS = setOf("pause", "resume", "cancel", "apply")
        private val TUNING_PREFERENCE_PATTERN = Regex("[A-Za-z0-9_.:-]+")
        private const val MAX_IDEMPOTENCY_KEY_LENGTH = 256
        private const val MAX_IDEMPOTENCY_RECORDS = 1024

        private const val HEX_CHARS = "0123456789abcdef"
        private val IDEMPOTENCY_KEY_PATTERN = Regex("[\u0021-\u007E]{1,256}")
        private val TUNING_JOB_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
