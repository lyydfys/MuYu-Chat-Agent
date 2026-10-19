package com.muyuchat.feature.chat

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageOrigin
import com.muyuchat.core.engine.ChatImageToolContinuationStatus
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.ChatWebSearchTrace
import com.muyuchat.core.designsystem.mcaPressScale
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
// P9/S17: private→internal（跨区入口：迁移的消息气泡复合区引用）
internal fun AssistantActionRow(
    readOnly: Boolean,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onDeleteLastTurn: () -> Unit,
    onCopy: () -> Unit,
    copied: Boolean = false,
    pinned: Boolean,
    canTogglePinned: Boolean,
    onTogglePinned: () -> Unit
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    var menuOpen by remember { mutableStateOf(false) }
    val regenerateInteraction = remember { MutableInteractionSource() }
    val copyInteraction = remember { MutableInteractionSource() }
    val moreInteraction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!readOnly) {
            IconButton(
                onClick = onRegenerate,
                enabled = canRegenerate,
                interactionSource = regenerateInteraction,
                modifier = Modifier.size(48.dp).mcaPressScale(regenerateInteraction, enabled = canRegenerate),
            ) {
                Icon(
                    Icons.Rounded.Replay,
                    contentDescription = "重新生成",
                    modifier = Modifier.size(19.dp),
                    tint = tint.copy(alpha = if (canRegenerate) 0.78f else 0.32f)
                )
            }
        }
        IconButton(
            onClick = onCopy,
            interactionSource = copyInteraction,
            modifier = Modifier.size(48.dp).mcaPressScale(copyInteraction),
        ) {
            Icon(
                imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                contentDescription = if (copied) "已复制" else "复制消息",
                modifier = Modifier.size(18.dp),
                tint = if (copied) McaPrimaryBlue else tint.copy(alpha = 0.78f)
            )
        }
        if (!readOnly) Box {
            IconButton(
                onClick = { menuOpen = true },
                interactionSource = moreInteraction,
                modifier = Modifier.size(48.dp).mcaPressScale(moreInteraction),
            ) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = "更多消息操作",
                    modifier = Modifier.size(20.dp),
                    tint = tint.copy(alpha = 0.78f)
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp)
            ) {
                DropdownMenuItem(
                    text = { Text("复制回答") },
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onCopy()
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (pinned) "取消固定上下文" else "固定到上下文") },
                    leadingIcon = {
                        Icon(
                            if (pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
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
                    leadingIcon = { Icon(Icons.Rounded.Replay, contentDescription = null) },
                    enabled = canRegenerate,
                    onClick = {
                        menuOpen = false
                        onRegenerate()
                    }
                )
                DropdownMenuItem(
                    text = { Text("\u5220\u9664\u672c\u8f6e\u5bf9\u8bdd", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDeleteLastTurn()
                    }
                )
                DropdownMenuItem(
                    text = { Text("删除本条", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}

