package com.muyuchat.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import java.util.Locale

/**
 * The completed-turn metrics as complete fields ("上行 1,245 tokens", "12.4 tok/s", ...) so the UI
 * can wrap between fields instead of between a number and its unit.
 */
internal fun ChatMessage.generationMetricsFields(): List<String>? {
    if (role != Role.ASSISTANT) return null
    val metrics = generationMetrics ?: return null
    fun count(value: Int?, estimated: Boolean): String = value?.takeIf { it >= 0 }?.let {
        (if (estimated) "约 " else "") + String.format(Locale.US, "%,d", it)
    } ?: "—"
    val speed = metrics.tokensPerSecond?.takeIf { it.isFinite() && it >= 0.0 }?.let {
        (if (metrics.completionTokensEstimated) "约 " else "") + String.format(Locale.US, "%.1f", it)
    } ?: "—"
    val elapsed = "用时 ${formatGenerationElapsed(metrics.elapsedMs)}" +
        if (metrics.promptTokensEstimated || metrics.completionTokensEstimated) "（含估算）" else ""
    return listOf(
        "上行 ${count(metrics.promptTokens, metrics.promptTokensEstimated)} tokens",
        "下行 ${count(metrics.completionTokens, metrics.completionTokensEstimated)} tokens",
        "$speed tok/s",
        elapsed
    )
}

internal fun ChatMessage.generationMetricsSummary(): String? =
    generationMetricsFields()?.joinToString(" · ")

/**
 * Metrics laid out as complete fields with tabular figures. Wrapping happens between fields, so a
 * number never separates from its unit and a continuation line never starts with an orphan "·".
 */
@Composable
internal fun GenerationMetricsRow(fields: List<String>) {
    if (fields.isEmpty()) return
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        fields.forEach { field ->
            Text(
                text = field,
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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
