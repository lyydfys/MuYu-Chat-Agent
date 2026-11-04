package com.muyuchat.mca.dsh.overlay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIcons

/**
 * The two panels the ball can show: the tap "action" panel and the long-press settings sheet.
 *
 * They live in their own file because the window renderer only needs to know *which* panel is open; the
 * row layouts, labels and the ids they emit are a separate concern that changes for its own reasons.
 * Every row emits a [DshOverlayActionId] through [perform], which is what makes the panels testable
 * without Compose (see `DshOverlayWindowActionTest`).
 */

/** The two intents behind a tap: go back to DSH, or stop the background keep-alive. */
@Composable
internal fun ActionPanel(
    actions: DshOverlayWindowActions,
    onClosePanel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.widthIn(min = 148.dp, max = 216.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp
    ) {
        // Tapping the panel's own blank padding closes it; the rows consume their own taps first.
        Column(modifier = Modifier.clickable(onClick = onClosePanel)) {
            PanelRow(
                label = "返回 DSH",
                icon = McaIcons.OpenInNew,
                onClick = {
                    onClosePanel()
                    DshOverlayActionId.RETURN_TO_DSH.perform(actions)
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            PanelRow(
                label = "停止后台运行",
                icon = McaIcons.Stop,
                onClick = {
                    onClosePanel()
                    DshOverlayActionId.STOP_BACKGROUND.perform(actions)
                }
            )
        }
    }
}

/** The inline settings sheet behind a long press: the same switches as the in-app panel. */
@Composable
internal fun SettingsSheet(
    state: DshOverlayUiState,
    actions: DshOverlayWindowActions,
    onClosePanel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.widthIn(min = 200.dp, max = 240.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "悬浮窗设置",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "关闭",
                    modifier = Modifier
                        .clickable(onClick = onClosePanel)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            SettingsRow(
                label = "回复时弹出气泡",
                value = if (state.notifyReplies) "开" else "关",
                onClick = { DshOverlayActionId.TOGGLE_NOTIFY_REPLIES.perform(actions) }
            )
            SettingsRow(
                label = "回复气泡行数",
                value = if (state.bubbleLines == 0) "关闭" else "${state.bubbleLines} 行",
                onClick = { DshOverlayActionId.CYCLE_BUBBLE_LINES.perform(actions) }
            )
            SettingsRow(
                label = "流式上屏",
                value = if (state.streamingEnabled) "开" else "关",
                onClick = { DshOverlayActionId.TOGGLE_STREAMING.perform(actions) }
            )
            SettingsRow(
                label = "允许拖动",
                value = if (state.draggable) "开" else "关",
                onClick = { DshOverlayActionId.TOGGLE_DRAGGABLE.perform(actions) }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            SettingsRow(
                label = "停止后台运行",
                value = "",
                onClick = {
                    onClosePanel()
                    DshOverlayActionId.STOP_BACKGROUND.perform(actions)
                }
            )
            Text(
                text = "开启后 DSH 在后台持续运行，回到 DSH 不会重启。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
            if (state.headline.isNotBlank() || state.summary.isNotBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                Text(
                    text = listOf(state.headline, state.summary)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun PanelRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SettingsRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One short line under the ball, e.g. why returning to DSH failed. */
@Composable
internal fun InfoSurface(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(max = 216.dp),
        shape = RoundedCornerShape(10.dp),
        color = OverlayPalette.panel,
        shadowElevation = 4.dp
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

/** Panel colours kept in one place; they match the previous window's panel tone. */
private object OverlayPalette {
    val panel = Color(0xE6172433)
}
