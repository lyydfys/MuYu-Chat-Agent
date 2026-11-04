package com.muyuchat.feature.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.McaIconPlate
import com.muyuchat.core.designsystem.McaIconSize
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaPlateSize
import com.muyuchat.core.designsystem.rememberMcaHaptics

/**
 * The overflow menu on a user message.
 *
 * Editing a sent user message is the one action that retires later turns, so its disabled wording
 * explains why it cannot run while generating. Quoting is available for every role and is the entry
 * point for the composer quote bar; deletion is deliberately not here — the single delete entry is
 * the assistant row's "删除本轮对话", which cannot orphan half a turn.
 */
@Composable
internal fun UserMessageActionMenu(
    readOnly: Boolean,
    canEdit: Boolean,
    onEdit: () -> Unit,
    onQuote: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = rememberMcaHaptics()
    val interaction = remember { MutableInteractionSource() }
    val editEnabled = !readOnly && canEdit
    val containerColor by animateColorAsState(
        targetValue = if (menuOpen) {
            McaPrimaryBlue.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        animationSpec = tween(durationMillis = 160),
        label = "userMessageMenuPillBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (menuOpen) {
            McaPrimaryBlue.copy(alpha = 0.38f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
        },
        animationSpec = tween(durationMillis = 160),
        label = "userMessageMenuPillBorder"
    )
    Box(modifier = modifier) {
        McaIconButton(
            contentDescription = "更多消息操作",
            onClick = {
                haptics.tick()
                menuOpen = true
            },
            interactionSource = interaction,
            glyphSize = McaPlateSize.Medium,
            hapticsEnabled = false
        ) {
            McaIconPlate(
                size = 32.dp,
                containerColor = containerColor,
                borderColor = borderColor,
                animateColors = false
            ) {
                Icon(
                    imageVector = McaIcons.MoreVert,
                    contentDescription = null,
                    modifier = Modifier.size(McaIconSize.S),
                    tint = if (menuOpen) {
                        McaPrimaryBlue
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    }
                )
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(
                0.5.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
            )
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        when {
                            readOnly -> "编辑并重发（只读）"
                            !canEdit -> "编辑并重发（生成中不可用）"
                            else -> "编辑并重发"
                        }
                    )
                },
                leadingIcon = { Icon(McaIcons.Edit, contentDescription = null) },
                enabled = editEnabled,
                onClick = {
                    menuOpen = false
                    onEdit()
                }
            )
            DropdownMenuItem(
                text = { Text("引用回复") },
                leadingIcon = { Icon(McaIcons.ChatBubbleOutline, contentDescription = null) },
                enabled = !readOnly,
                onClick = {
                    menuOpen = false
                    onQuote()
                }
            )
        }
    }
}
