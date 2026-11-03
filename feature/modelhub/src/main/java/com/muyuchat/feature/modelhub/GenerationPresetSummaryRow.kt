package com.muyuchat.feature.modelhub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One compact "label · value" fragment of a preset's parameter summary. */
internal data class PresetParameterSegment(
    val label: String,
    val value: String
)

/**
 * Splits the stored summary (`T 0.8 · top_p 0.9 · top_k 40 · 输出 2048`) into label/value fragments.
 *
 * The projection is pure and tolerant: an unexpected or future segment is kept as a value-only
 * fragment instead of disappearing, so the preset page never hides sampling data it does not know
 * how to label. Only the presentation is derived here; the stored summary string is untouched.
 */
internal fun presetParameterSegments(summary: String): List<PresetParameterSegment> {
    if (summary.isBlank()) return emptyList()
    return summary.split('·', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { segment ->
            val splitAt = segment.lastIndexOf(' ')
            if (splitAt <= 0) {
                PresetParameterSegment(label = "", value = segment)
            } else {
                val label = segment.substring(0, splitAt).trim()
                val value = segment.substring(splitAt + 1).trim()
                if (value.isEmpty()) {
                    null
                } else {
                    PresetParameterSegment(label = presetParameterLabel(label), value = value)
                }
            }
        }
}

private fun presetParameterLabel(raw: String): String = when (raw.lowercase(Locale.ROOT)) {
    "t", "temp", "temperature", "温度" -> "温度"
    "top_p", "topp" -> "top_p"
    "top_k", "topk" -> "top_k"
    "min_p", "minp" -> "min_p"
    "输出", "n", "npredict", "n_predict", "max_tokens", "maxtokens" -> "输出"
    "repeat_penalty", "repeatpenalty", "重复惩罚" -> "重复惩罚"
    else -> raw
}

/**
 * Short, right-aligned freshness label for a preset card.
 *
 * Returns an empty string for a record without a timestamp, so a legacy/imported record does not
 * render a fake "1970" date. Older entries fall back to a compact date instead of a growing
 * relative string.
 */
internal fun presetUpdatedAtLabel(
    updatedAtMillis: Long,
    nowMillis: Long = System.currentTimeMillis()
): String {
    if (updatedAtMillis <= 0L) return ""
    val elapsed = (nowMillis - updatedAtMillis).coerceAtLeast(0L)
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        elapsed < minute -> "刚刚"
        elapsed < hour -> "${elapsed / minute} 分钟前"
        elapsed < day -> "${elapsed / hour} 小时前"
        elapsed < 7 * day -> "${elapsed / day} 天前"
        else -> {
            val updated = Calendar.getInstance().apply { timeInMillis = updatedAtMillis }
            val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
            val pattern = if (updated.get(Calendar.YEAR) == now.get(Calendar.YEAR)) {
                "MM-dd"
            } else {
                "yyyy-MM-dd"
            }
            SimpleDateFormat(pattern, Locale.getDefault()).format(Date(updatedAtMillis))
        }
    }
}

/** Secondary line of a preset card: which models currently receive its sampling parameters. */
internal fun presetBoundModelsLabel(boundNames: List<String>): String =
    if (boundNames.isEmpty()) {
        "尚未绑定模型"
    } else {
        "已绑定：${boundNames.joinToString("、")}"
    }

/**
 * Parameter summary as compact label/value pills instead of one long sentence.
 *
 * Wraps with [FlowRow], so four fragments read as a tidy two-line block on a narrow screen and a
 * single line on a wide one without a nested horizontal scroller.
 */
@Composable
internal fun PresetParameterSummaryRow(
    summary: String,
    modifier: Modifier = Modifier
) {
    val segments = remember(summary) { presetParameterSegments(summary) }
    if (segments.isEmpty()) return
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        segments.forEach { segment ->
            PresetParameterPill(segment)
        }
    }
}

@Composable
private fun PresetParameterPill(segment: PresetParameterSegment) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (segment.label.isNotBlank()) {
                Text(
                    text = segment.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = segment.value,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
