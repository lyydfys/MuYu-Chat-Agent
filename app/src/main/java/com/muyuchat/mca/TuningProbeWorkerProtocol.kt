package com.muyuchat.mca

import org.json.JSONObject

/**
 * String-only Binder protocol for persisted load-bound probes.
 *
 * Requests intentionally contain only persisted transaction/model/profile identity. Prompts,
 * messages, sampling settings, model paths, and any user-authored data are forbidden at this
 * boundary; the worker owns a small set of compile-time canaries.
 */
internal object TuningProbeWorkerProtocol {
    private const val VERSION = 2
    internal const val HARD_PROCESS_TIMEOUT_MS = 420_000L
    internal const val CLIENT_RUN_TIMEOUT_MS = 450_000L

    enum class ProbeKind {
        TUNING_CANDIDATE,
        BOOTSTRAP_LOAD
    }

    internal val requestKeys: Set<String> = setOf(
        "version",
        "requestId",
        "probeKind",
        "transactionId",
        "identityKey",
        "modelId",
        "profileId",
        "resolvedLoadSignature",
        "committedExecutionSignature"
    )

    data class Request(
        val requestId: String,
        val probeKind: ProbeKind,
        val transactionId: String,
        val identityKey: String,
        val modelId: String,
        val profileId: String,
        val resolvedLoadSignature: String,
        val committedExecutionSignature: String
    )

    data class Progress(
        val requestId: String,
        val stage: String,
        val message: String,
        val elapsedMs: Long
    )

    data class Result(
        val requestId: String,
        val probeKind: ProbeKind,
        val transactionId: String,
        val identityKey: String,
        val modelId: String,
        val profileId: String,
        val resolvedLoadSignature: String,
        val committedExecutionSignature: String,
        val passed: Boolean,
        val signatureMatched: Boolean,
        val output: String,
        val detail: String,
        val runtimeStatsJson: String,
        val evidenceJson: String,
        val startAvailableMemoryBytes: Long,
        val startPssBytes: Long,
        val startRssBytes: Long,
        val endAvailableMemoryBytes: Long,
        val endPssBytes: Long,
        val endRssBytes: Long,
        val lowMemoryTriggered: Boolean,
        val elapsedMs: Long
    )

    data class Error(
        val requestId: String,
        val code: String,
        val message: String
    )

    fun start(request: Request): String = JSONObject()
        .put("version", VERSION)
        .put("requestId", request.requestId)
        .put("probeKind", request.probeKind.name)
        .put("transactionId", request.transactionId)
        .put("identityKey", request.identityKey)
        .put("modelId", request.modelId)
        .put("profileId", request.profileId)
        .put("resolvedLoadSignature", request.resolvedLoadSignature)
        .put("committedExecutionSignature", request.committedExecutionSignature)
        .toString()

    fun parseStart(raw: String): Request {
        val json = JSONObject(raw)
        require(json.optInt("version", -1) == VERSION) { "Unsupported tuning worker protocol version." }
        val unexpected = json.keys().asSequence().toSet() - requestKeys
        require(unexpected.isEmpty()) { "Tuning probe request contains forbidden fields: ${unexpected.sorted()}" }
        return Request(
            requestId = json.requiredString("requestId"),
            probeKind = json.requiredProbeKind("probeKind"),
            transactionId = json.requiredString("transactionId"),
            identityKey = json.requiredString("identityKey"),
            modelId = json.requiredString("modelId"),
            profileId = json.requiredString("profileId"),
            resolvedLoadSignature = json.requiredString("resolvedLoadSignature"),
            committedExecutionSignature = json.requiredString("committedExecutionSignature")
        )
    }

    fun cancel(requestId: String): String = JSONObject()
        .put("version", VERSION)
        .put("requestId", requestId)
        .toString()

    fun parseCancel(raw: String): String? = runCatching {
        JSONObject(raw).optString("requestId").takeIf(String::isNotBlank)
    }.getOrNull()

    fun progress(requestId: String, stage: String, message: String, elapsedMs: Long): String =
        JSONObject()
            .put("version", VERSION)
            .put("requestId", requestId)
            .put("stage", stage.take(MAX_STAGE_CHARS))
            .put("message", message.take(MAX_MESSAGE_CHARS))
            .put("elapsedMs", elapsedMs.coerceAtLeast(0L))
            .toString()

    fun parseProgress(raw: String): Progress {
        val json = JSONObject(raw)
        return Progress(
            requestId = json.requiredString("requestId"),
            stage = json.optString("stage").ifBlank { "working" },
            message = json.optString("message").ifBlank { "Isolated tuning probe is running." },
            elapsedMs = json.optLong("elapsedMs").coerceAtLeast(0L)
        )
    }

    fun complete(result: Result): String = JSONObject()
        .put("version", VERSION)
        .put("requestId", result.requestId)
        .put("probeKind", result.probeKind.name)
        .put("transactionId", result.transactionId)
        .put("identityKey", result.identityKey)
        .put("modelId", result.modelId)
        .put("profileId", result.profileId)
        .put("resolvedLoadSignature", result.resolvedLoadSignature)
        .put("committedExecutionSignature", result.committedExecutionSignature)
        .put("passed", result.passed)
        .put("signatureMatched", result.signatureMatched)
        .put("output", result.output.take(MAX_OUTPUT_CHARS))
        .put("detail", result.detail.take(MAX_DETAIL_CHARS))
        .put("runtimeStats", checkedJson(result.runtimeStatsJson, MAX_STATS_JSON_CHARS))
        .put("evidence", checkedJson(result.evidenceJson, MAX_EVIDENCE_JSON_CHARS))
        .put("startAvailableMemoryBytes", result.startAvailableMemoryBytes.coerceAtLeast(0L))
        .put("startPssBytes", result.startPssBytes.coerceAtLeast(0L))
        .put("startRssBytes", result.startRssBytes.coerceAtLeast(0L))
        .put("endAvailableMemoryBytes", result.endAvailableMemoryBytes.coerceAtLeast(0L))
        .put("endPssBytes", result.endPssBytes.coerceAtLeast(0L))
        .put("endRssBytes", result.endRssBytes.coerceAtLeast(0L))
        .put("lowMemoryTriggered", result.lowMemoryTriggered)
        .put("elapsedMs", result.elapsedMs.coerceAtLeast(0L))
        .toString()

    fun parseComplete(raw: String): Result {
        val json = JSONObject(raw)
        require(json.optInt("version", -1) == VERSION) { "Unsupported tuning worker protocol version." }
        return Result(
            requestId = json.requiredString("requestId"),
            probeKind = json.requiredProbeKind("probeKind"),
            transactionId = json.requiredString("transactionId"),
            identityKey = json.requiredString("identityKey"),
            modelId = json.requiredString("modelId"),
            profileId = json.requiredString("profileId"),
            resolvedLoadSignature = json.requiredString("resolvedLoadSignature"),
            committedExecutionSignature = json.requiredString("committedExecutionSignature"),
            passed = json.optBoolean("passed"),
            signatureMatched = json.optBoolean("signatureMatched"),
            output = json.optString("output").take(MAX_OUTPUT_CHARS),
            detail = json.optString("detail").take(MAX_DETAIL_CHARS),
            runtimeStatsJson = json.requiredObject("runtimeStats").toString(),
            evidenceJson = json.requiredObject("evidence").toString(),
            startAvailableMemoryBytes = json.optLong("startAvailableMemoryBytes").coerceAtLeast(0L),
            startPssBytes = json.optLong("startPssBytes").coerceAtLeast(0L),
            startRssBytes = json.optLong("startRssBytes").coerceAtLeast(0L),
            endAvailableMemoryBytes = json.optLong("endAvailableMemoryBytes").coerceAtLeast(0L),
            endPssBytes = json.optLong("endPssBytes").coerceAtLeast(0L),
            endRssBytes = json.optLong("endRssBytes").coerceAtLeast(0L),
            lowMemoryTriggered = json.optBoolean("lowMemoryTriggered"),
            elapsedMs = json.optLong("elapsedMs").coerceAtLeast(0L)
        )
    }

    fun error(requestId: String, code: String, message: String): String = JSONObject()
        .put("version", VERSION)
        .put("requestId", requestId)
        .put("code", code.take(MAX_CODE_CHARS))
        .put("message", message.take(MAX_MESSAGE_CHARS))
        .toString()

    fun parseError(raw: String): Error {
        val json = JSONObject(raw)
        return Error(
            requestId = json.optString("requestId"),
            code = json.optString("code").ifBlank { "worker_error" },
            message = json.optString("message").ifBlank { "Isolated tuning probe failed." }
        )
    }

    private fun checkedJson(raw: String, limit: Int): JSONObject {
        require(raw.length <= limit) { "Tuning worker evidence exceeds the Binder limit." }
        return JSONObject(raw)
    }

    private fun JSONObject.requiredString(field: String): String =
        optString(field).takeIf(String::isNotBlank) ?: error("Missing $field.")

    private fun JSONObject.requiredObject(field: String): JSONObject =
        optJSONObject(field) ?: error("Missing $field object.")

    private fun JSONObject.requiredProbeKind(field: String): ProbeKind {
        val value = requiredString(field)
        return runCatching { ProbeKind.valueOf(value) }
            .getOrElse { throw IllegalArgumentException("Unknown tuning probe kind: $value", it) }
    }

    private const val MAX_STAGE_CHARS = 96
    private const val MAX_CODE_CHARS = 96
    private const val MAX_MESSAGE_CHARS = 1_024
    private const val MAX_DETAIL_CHARS = 4_096
    private const val MAX_OUTPUT_CHARS = 4_096
    private const val MAX_STATS_JSON_CHARS = 16 * 1_024
    private const val MAX_EVIDENCE_JSON_CHARS = 96 * 1_024
}
