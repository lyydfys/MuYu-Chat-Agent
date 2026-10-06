package com.muyuchat.mca

import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

// R1/S3: 从 MainViewModel 抽出的 Local API 幂等键哈希（纯函数，JVM 可直测）。
internal fun localApiIdempotencyKeyHash(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

internal fun encodeDurableLocalApiResult(
    requestFingerprint: String,
    result: com.muyuchat.api.local.LocalApiControlResult
): JSONObject = JSONObject()
    .put("formatVersion", 1)
    .put("requestFingerprint", requestFingerprint)
    .put("state", "completed")
    .put("updatedAt", System.currentTimeMillis())
    .apply {
        when (result) {
            is com.muyuchat.api.local.LocalApiControlResult.Success -> {
                put("resultType", "success")
                put("httpStatus", result.httpStatus)
                put("retryAfterMs", result.retryAfterMs)
                put("json", result.json)
            }
            is com.muyuchat.api.local.LocalApiControlResult.Rejected -> {
                put("resultType", "rejected")
                put("httpStatus", result.httpStatus)
                put("retryAfterMs", result.retryAfterMs)
                put("code", result.code)
                put("message", result.message)
                put("detailsJson", result.detailsJson)
            }
        }
    }

internal fun decodeDurableLocalApiResult(
    entry: JSONObject,
    localApiTuningRejected: (Int, String, String) -> com.muyuchat.api.local.LocalApiControlResult
): com.muyuchat.api.local.LocalApiControlResult = when (entry.optString("resultType")) {
    "success" -> com.muyuchat.api.local.LocalApiControlResult.Success(
        json = entry.optString("json", "{}"),
        httpStatus = entry.optInt("httpStatus", 200),
        retryAfterMs = entry.optLong("retryAfterMs", 0L).coerceAtLeast(0L)
    )
    "rejected" -> com.muyuchat.api.local.LocalApiControlResult.Rejected(
        httpStatus = entry.optInt("httpStatus", 409),
        code = entry.optString("code", "control_plane_rejected"),
        message = entry.optString("message", "本地运行时控制面拒绝了该请求。"),
        detailsJson = entry.optString("detailsJson", "{}"),
        retryAfterMs = entry.optLong("retryAfterMs", 0L).coerceAtLeast(0L)
    )
    else -> localApiTuningRejected(
        409,
        "idempotency_journal_corrupt",
        "该 Idempotency-Key 的最终响应记录不可读；为避免重复执行，已拒绝重放。"
    )
}

internal fun runDurableLocalApiControlFor(
    preferences: SharedPreferences,
    lock: Any,
    idempotency: com.muyuchat.api.local.LocalApiIdempotencyContext,
    localApiTuningRejected: (Int, String, String) -> com.muyuchat.api.local.LocalApiControlResult,
    operation: () -> com.muyuchat.api.local.LocalApiControlResult
): com.muyuchat.api.local.LocalApiControlResult = synchronized(lock) {
    if (idempotency.key.isBlank() || idempotency.requestFingerprint.isBlank()) {
        return@synchronized localApiTuningRejected(
            400,
            "invalid_idempotency_context",
            "Idempotency-Key 或请求指纹无效。"
        )
    }
    val storageKey = "entry_${localApiIdempotencyKeyHash(idempotency.key)}"
    preferences.getString(storageKey, null)?.let { persisted ->
        val entry = runCatching { JSONObject(persisted) }.getOrNull()
            ?: return@synchronized localApiTuningRejected(
                409,
                "idempotency_journal_corrupt",
                "该 Idempotency-Key 的持久记录不可读；为避免重复执行，已拒绝重放。"
            )
        if (entry.optString("requestFingerprint") != idempotency.requestFingerprint) {
            return@synchronized localApiTuningRejected(
                409,
                "idempotency_key_conflict",
                "同一 Idempotency-Key 不能用于不同请求。"
            )
        }
        if (entry.optString("state") == "completed") {
            return@synchronized decodeDurableLocalApiResult(entry, localApiTuningRejected)
        }
        return@synchronized localApiTuningRejected(
            409,
            "idempotency_recovery_required",
            "该写操作曾开始但未持久化最终响应；请先查询任务/profile 状态，系统不会自动重复执行。"
        )
    }
    val pending = JSONObject()
        .put("formatVersion", 1)
        .put("requestFingerprint", idempotency.requestFingerprint)
        .put("state", "pending")
        .put("updatedAt", System.currentTimeMillis())
    if (!preferences.edit().putString(storageKey, pending.toString()).commit()) {
        return@synchronized localApiTuningRejected(
            503,
            "idempotency_journal_unavailable",
            "无法持久化幂等事务，写操作未执行。"
        )
    }
    val result = runCatching(operation).getOrElse {
        com.muyuchat.api.local.LocalApiControlResult.Rejected(
            httpStatus = 500,
            code = "control_plane_failure",
            message = "本地运行时控制面执行失败。"
        )
    }
    val completed = encodeDurableLocalApiResult(idempotency.requestFingerprint, result)
    preferences.edit().putString(storageKey, completed.toString()).commit()
    result
}
