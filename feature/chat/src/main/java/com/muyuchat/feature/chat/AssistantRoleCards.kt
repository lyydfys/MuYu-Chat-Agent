package com.muyuchat.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun AssistantCurrentCard(
    assistant: AssistantUiItem?,
    selectedModelName: String?,
    onEdit: () -> Unit,
    onExport: () -> Unit
) {
    val assistantTag = assistant?.tag.orEmpty()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("当前助手", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistantAvatar(assistant?.name ?: "MCA", assistant?.avatar.orEmpty(), selected = true)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        assistant?.name ?: "默认助手",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (assistantTag.isNotBlank()) {
                        Text(
                            assistantTag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        assistant?.modelSummary?.takeIf { it.isNotBlank() } ?: selectedModelName ?: "跟随当前模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // 2.0x font: three chips no longer fit one line; FlowRow keeps the 1.0x single row
            // unchanged and wraps instead of clipping when the type scale grows.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistantCapabilityChip("记忆", assistant?.memoryEnabled == true)
                AssistantCapabilityChip("联网检索", assistant?.webSearchEnabled == true)
                AssistantCapabilityChip("文件上下文", assistant?.fileContextEnabled != false)
            }
            // Same wrapping rule for the two text actions: 1.0x stays one row, 2.0x wraps.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // With no assistant available the current-card actions have nothing to act on;
                // disable them instead of leaving dead taps that look enabled.
                TextButton(onClick = onEdit, enabled = assistant != null) { Text("编辑") }
                TextButton(onClick = onExport, enabled = assistant != null) { Text("导出") }
            }
        }
    }
}

@Composable
internal fun AssistantListCard(
    assistant: AssistantUiItem,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (assistant.selected) {
            if (darkTheme) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f) else Color(0xFFEAF1FF)
        } else {
            if (darkTheme) MaterialTheme.colorScheme.surface else Color(0xFFF8F9FA)
        },
        shape = RoundedCornerShape(20.dp),
        border = if (assistant.selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)) else null
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistantAvatar(assistant.name, assistant.avatar, selected = assistant.selected)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        assistant.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        assistant.tag.ifBlank { assistant.systemPrompt.ifBlank { "未设置提示词" } },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (assistant.selected) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            // 2.0x font: three actions can exceed the card width; FlowRow wraps them while the
            // 1.0x layout keeps its single row and 8dp rhythm.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = onSelect, enabled = !assistant.selected) { Text(if (assistant.selected) "使用中" else "切换") }
                TextButton(onClick = onEdit) { Text("编辑") }
                val deletable = assistant.id != "default"
                TextButton(onClick = onDelete, enabled = deletable) {
                    // Disabled delete inherits the Material3 disabled content colour instead of
                    // the normal neutral colour, so it no longer reads as an available action.
                    Text(
                        "删除",
                        color = if (deletable) MaterialTheme.colorScheme.error else LocalContentColor.current
                    )
                }
            }
        }
    }
}
