package com.muyuchat.feature.chat

import com.muyuchat.core.designsystem.McaIcons
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import com.muyuchat.core.designsystem.McaIconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIconPlate
import com.muyuchat.core.designsystem.McaPlateSize
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageOrigin
import com.muyuchat.core.engine.ChatImageToolContinuationStatus
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.ChatWebSearchTrace
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import com.muyuchat.core.designsystem.McaActionLabels
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.designsystem.rememberMcaHaptics
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.muyuchat.core.designsystem.McaIconSize

@Composable
// P9/S17: private→internal（跨区入口：迁移的消息气泡复合区引用）
internal fun AssistantActionRow(
    readOnly: Boolean,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onDeleteLastTurn: () -> Unit,
    onQuote: () -> Unit,
    onCopy: () -> Unit,
    copied: Boolean = false,
    pinned: Boolean,
    canTogglePinned: Boolean,
    onTogglePinned: () -> Unit
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val haptics = rememberMcaHaptics()
    var menuOpen by remember { mutableStateOf(false) }
    val regenerateInteraction = remember { MutableInteractionSource() }
    val copyInteraction = remember { MutableInteractionSource() }
    val moreInteraction = remember { MutableInteractionSource() }
    val copyIconScale by animateFloatAsState(
        targetValue = if (copied) 1.15f else 1.0f,
        animationSpec = McaMotion.sizeChange,
        label = "assistantCopyIconScale"
    )
    val copyPillBg by animateColorAsState(
        targetValue = if (copied) {
            McaPrimaryBlue.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 160),
        label = "assistantCopyPillBg"
    )
    val copyPillBorder by animateColorAsState(
        targetValue = if (copied) {
            McaPrimaryBlue.copy(alpha = 0.38f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
        },
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 160),
        label = "assistantCopyPillBorder"
    )
    val morePillBg by animateColorAsState(
        targetValue = if (menuOpen) {
            McaPrimaryBlue.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 160),
        label = "assistantMorePillBg"
    )
    val morePillBorder by animateColorAsState(
        targetValue = if (menuOpen) {
            McaPrimaryBlue.copy(alpha = 0.38f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
        },
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 160),
        label = "assistantMorePillBorder"
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!readOnly) {
            McaIconButton(
                contentDescription = McaActionLabels.REGENERATE,
                onClick = {
                    haptics.tick()
                    onRegenerate()
                },
                enabled = canRegenerate,
                interactionSource = regenerateInteraction,
                glyphSize = McaPlateSize.Medium,
                // R08/a11y: the name lives on the clickable button, never only on the vector.
                // When an open composer menu clips this row, uiautomator keeps the partially
                // visible clickable but prunes its label child, which used to surface as an
                // unnamed sliver in the naming audit. A button-owned name survives that state.
                hapticsEnabled = false,
            ) {
                McaIconPlate(
                    size = 32.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                        alpha = if (canRegenerate) 0.45f else 0.22f
                    ),
                    borderColor = MaterialTheme.colorScheme.outlineVariant.copy(
                        alpha = if (canRegenerate) 0.42f else 0.20f
                    ),
                    animateColors = false,
                ) {
                    Icon(
                        McaIcons.Replay,
                        contentDescription = null,
                        modifier = Modifier.size(McaIconSize.S),
                        tint = tint.copy(alpha = if (canRegenerate) 0.85f else 0.32f)
                    )
                }
            }
        }
        McaIconButton(
            contentDescription = if (copied) "已复制" else "${McaActionLabels.COPY}回答",
            onClick = onCopy,
            hapticsEnabled = false,
            interactionSource = copyInteraction,
            glyphSize = McaPlateSize.Medium,
        ) {
              McaIconPlate(
                  size = 32.dp,
                  containerColor = copyPillBg,
                  borderColor = copyPillBorder,
                  animateColors = false,
              ) {
                Icon(
                    imageVector = if (copied) McaIcons.Check else McaIcons.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier
                        .size(McaIconSize.Optical.Inline)
                        .graphicsLayer {
                            scaleX = copyIconScale
                            scaleY = copyIconScale
                        },
                    tint = if (copied) McaPrimaryBlue else tint.copy(alpha = 0.85f)
                )
            }
        }
        if (!readOnly) Box {
            McaIconButton(
                contentDescription = "更多消息操作",
                onClick = {
                    haptics.tick()
                    menuOpen = true
                },
                interactionSource = moreInteraction,
                glyphSize = McaPlateSize.Medium,
                hapticsEnabled = false,
            ) {
                  McaIconPlate(
                      size = 32.dp,
                      containerColor = morePillBg,
                      borderColor = morePillBorder,
                      animateColors = false,
                  ) {
                    Icon(
                        McaIcons.MoreVert,
                        contentDescription = null,
                        modifier = Modifier.size(McaIconSize.S),
                        tint = if (menuOpen) McaPrimaryBlue else tint.copy(alpha = 0.85f)
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
                    text = { Text("复制回答") },
                    leadingIcon = { Icon(McaIcons.ContentCopy, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onCopy()
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (pinned) "取消固定上下文" else "固定到上下文") },
                    leadingIcon = {
                        Icon(
                            if (pinned) McaIcons.PushPin else McaIcons.PinUnselected,
                            contentDescription = null
                        )
                    },
                    enabled = canTogglePinned,
                    onClick = {
                        menuOpen = false
                        onTogglePinned()
                    }
                )
                DropdownMenuItem(
                    text = { Text("重新生成") },
                    leadingIcon = { Icon(McaIcons.Replay, contentDescription = null) },
                    enabled = canRegenerate,
                    onClick = {
                        menuOpen = false
                        onRegenerate()
                    }
                )
                DropdownMenuItem(
                    text = { Text("引用回复") },
                    leadingIcon = { Icon(McaIcons.ChatBubbleOutline, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onQuote()
                    }
                )
                DropdownMenuItem(
                    text = { Text("\u5220\u9664\u672c\u8f6e\u5bf9\u8bdd", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            McaIcons.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDeleteLastTurn()
                    }
                )
            }
        }
    }
}

