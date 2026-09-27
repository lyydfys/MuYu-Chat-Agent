package com.muyuchat.feature.chat

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import java.util.Locale

internal fun ChatMessage.generationMetricsSummary(): String? {
    if (role != Role.ASSISTANT) return null
    val metrics = generationMetrics ?: return null
    fun count(value: Int?, estimated: Boolean): String = value?.takeIf { it >= 0 }?.let {
        (if (estimated) "约 " else "") + String.format(Locale.US, "%,d", it)
    } ?: "—"
    val speed = metrics.tokensPerSecond?.takeIf { it.isFinite() && it >= 0.0 }?.let {
        (if (metrics.completionTokensEstimated) "约 " else "") + String.format(Locale.US, "%.1f", it)
    } ?: "—"
    return "上行 ${count(metrics.promptTokens, metrics.promptTokensEstimated)} tokens · " +
        "下行 ${count(metrics.completionTokens, metrics.completionTokensEstimated)} tokens · " +
        "$speed tok/s · 用时 ${formatGenerationElapsed(metrics.elapsedMs)}" +
        if (metrics.promptTokensEstimated || metrics.completionTokensEstimated) "（含估算）" else ""
}

internal fun formatGenerationElapsed(elapsedMs: Long): String {
    val ms = elapsedMs.coerceAtLeast(0L)
    if (ms in 1..999) return "不足1秒"
    val seconds = ms / 1000L
    return when {
        seconds >= 3600L -> "${seconds / 3600}小时${seconds / 60 % 60}分${seconds % 60}秒"
        seconds >= 60L -> "${seconds / 60}分${seconds % 60}秒"
        else -> "${seconds}秒"
    }
}
