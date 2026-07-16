package com.muyuchat.mca

import com.muyuchat.core.modelstore.ModelManifest
import org.json.JSONObject

/** String-only Binder protocol so a crashed QAIRT process cannot marshal app objects back to UI. */
internal object QairtDryRunWorkerProtocol {
    private const val VERSION = 1

    data class Request(
        val requestId: String,
        val modelId: String,
        val nCtx: Int,
        val nThreads: Int
    )

    data class Progress(
        val requestId: String,
        val stage: String,
        val message: String,
        val elapsedMs: Long
    )

    data class Result(
        val requestId: String,
        val bundleSha256: String,
        val npuEvidence: String,
        val visibleChars: Int,
        val visionChecked: Boolean,
        val elapsedMs: Long
    )

    data class Error(
        val requestId: String,
        val code: String,
        val message: String
    )

    fun start(requestId: String, modelId: String, nCtx: Int, nThreads: Int): String =
        JSONObject()
            .put("version", VERSION)
            .put("requestId", requestId)
            .put("modelId", modelId)
            .put("nCtx", nCtx)
            .put("nThreads", nThreads)
            .toString()

    fun parseStart(raw: String): Request {
        val json = JSONObject(raw)
        return Request(
            requestId = json.requiredString("requestId"),
            modelId = json.requiredString("modelId"),
            nCtx = json.optInt("nCtx", DEFAULT_N_CTX).coerceIn(MIN_N_CTX, MAX_N_CTX),
            nThreads = json.optInt("nThreads", 1).coerceIn(MIN_THREADS, MAX_THREADS)
        )
    }

    fun cancel(requestId: String): String =
        JSONObject()
            .put("version", VERSION)
            .put("requestId", requestId)
            .toString()

    fun parseCancel(raw: String): String? =
        runCatching { JSONObject(raw).optString("requestId").takeIf(String::isNotBlank) }.getOrNull()

    fun progress(requestId: String, stage: String, message: String, elapsedMs: Long): String =
        JSONObject()
            .put("version", VERSION)
            .put("requestId", requestId)
            .put("stage", stage)
            .put("message", message)
            .put("elapsedMs", elapsedMs)
            .toString()

    fun parseProgress(raw: String): Progress {
        val json = JSONObject(raw)
        return Progress(
            requestId = json.requiredString("requestId"),
            stage = json.optString("stage").ifBlank { "working" },
            message = json.optString("message").ifBlank { "正在执行 QAIRT 隔离安全启动。" },
            elapsedMs = json.optLong("elapsedMs").coerceAtLeast(0L)
        )
    }

    fun complete(
        requestId: String,
        bundleSha256: String,
        npuEvidence: String,
        visibleChars: Int,
        visionChecked: Boolean,
        elapsedMs: Long
    ): String = JSONObject()
        .put("version", VERSION)
        .put("requestId", requestId)
        .put("bundleSha256", bundleSha256)
        .put("npuEvidence", npuEvidence)
        .put("visibleChars", visibleChars)
        .put("visionChecked", visionChecked)
        .put("elapsedMs", elapsedMs)
        .toString()

    fun parseComplete(raw: String): Result {
        val json = JSONObject(raw)
        return Result(
            requestId = json.requiredString("requestId"),
            bundleSha256 = json.requiredString("bundleSha256"),
            npuEvidence = json.requiredString("npuEvidence"),
            visibleChars = json.optInt("visibleChars").coerceAtLeast(1),
            visionChecked = json.optBoolean("visionChecked"),
            elapsedMs = json.optLong("elapsedMs").coerceAtLeast(0L)
        )
    }

    fun error(requestId: String, code: String, message: String): String = JSONObject()
        .put("version", VERSION)
        .put("requestId", requestId)
        .put("code", code)
        .put("message", message)
        .toString()

    fun parseError(raw: String): Error {
        val json = JSONObject(raw)
        return Error(
            requestId = json.optString("requestId"),
            code = json.optString("code").ifBlank { "worker_error" },
            message = json.optString("message").ifBlank { "QAIRT 隔离安全启动失败。" }
        )
    }

    private fun JSONObject.requiredString(key: String): String =
        optString(key).takeIf(String::isNotBlank) ?: error("Missing $key.")

    const val DEFAULT_N_CTX = 2_048
    const val MIN_N_CTX = 1_024
    const val MAX_N_CTX = 4_096
    const val MIN_THREADS = 1
    const val MAX_THREADS = 8
}

/** Fixed, non-user-data probes used only to prove an isolated QAIRT path works. */
internal object QairtDryRunPolicy {
    const val TEXT_PROMPT = "请计算 6×7，只回复数字答案。"
    const val VISION_PROMPT = "请只回答图片中方块的颜色，不要解释。"

    fun requiresVision(model: ModelManifest): Boolean {
        val identity = listOf(model.displayName, model.architecture, model.repoId, model.fileName, model.path)
            .filterNotNull()
            .joinToString(" ")
            .lowercase()
        return "-vl" in identity || "_vl" in identity || "vision" in identity || "minicpm-v" in identity
    }

    fun textAnswerPasses(value: String): Boolean {
        val normalized = value.trim()
        if (normalized.isBlank() || "<|" in normalized || "|>" in normalized) return false
        return Regex("(^|\\D)42(\\D|$)").containsMatchIn(normalized) || "四十二" in normalized
    }

    fun visionAnswerPasses(value: String): Boolean {
        val normalized = value.lowercase()
        return "蓝" in normalized || "blue" in normalized
    }
}
