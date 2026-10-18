package com.muyuchat.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.visionDeduplicationKey
import com.muyuchat.core.engine.ReasoningMode
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun CompactInputActionMenu(
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onPhoto: () -> Unit,
    onFile: () -> Unit,
    onLibrary: () -> Unit,
    onImagePromptMode: () -> Unit,
    assistantImageToolAvailable: Boolean,
    assistantImageToolUnavailableReason: String,
    assistantImageToolAutoApproval: Boolean,
    assistantImageToolCanChange: Boolean,
    onSetAssistantImageToolAutoApproval: (Boolean) -> Unit,
    imagePromptMode: Boolean,
    onWebSearch: () -> Unit,
    onResearchMode: () -> Unit,
    onOpenWebSearchSettings: () -> Unit,
    webSearchEnabled: Boolean,
    webSearchConfigured: Boolean,
    webSearchEnabledForTurn: Boolean,
    webSearchTurnModeLabel: String,
    webSearchResearchMode: String,
    webSearchResearchModeLabel: String,
    webSearchResearchOverridden: Boolean,
    webSearchProviderLabel: String,
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomStart
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(onClick = onDismiss)
        )
        // This sheet is only composed while it is open, so `visible = true` on the first frame
        // suppressed the entry animation. Start from an explicit hidden state so the spring-settled
        // slide shared with the other composer surfaces can actually play.
        val menuVisibility = remember { MutableTransitionState(initialState = false) }
        menuVisibility.targetState = true
        AnimatedVisibility(
            visibleState = menuVisibility,
            enter = fadeIn(tween(120)) + slideInHorizontally(
                animationSpec = spring(
                    dampingRatio = ChatMotion.SURFACE_DAMPING_RATIO,
                    stiffness = ChatMotion.SURFACE_STIFFNESS,
                    visibilityThreshold = IntOffset.VisibilityThreshold
                ),
                initialOffsetX = { -it / 5 }
            ),
            exit = fadeOut(tween(100)),
            modifier = Modifier
                .padding(bottom = 86.dp)
                .width(238.dp)
        ) {
            Surface(
                modifier = Modifier.clickable(enabled = false) {},
                color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
                shape = RoundedCornerShape(30.dp),
                shadowElevation = 18.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = if (imagePromptMode) "退出聊天生图" else "在聊天中生成图片",
                        onClick = onImagePromptMode
                    )
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = if (assistantImageToolAutoApproval) {
                            "角色生图：本对话自动授权"
                        } else {
                            "角色生图：每次询问"
                        },
                        subtitle = when {
                            !assistantImageToolAvailable -> assistantImageToolUnavailableReason
                            !assistantImageToolCanChange -> "发送一条消息创建对话后即可设置"
                            assistantImageToolAutoApproval -> "Responses 角色请求将直接生成，可随时关闭"
                            else -> "Responses 角色请求会先显示确认卡片"
                        },
                        enabled = assistantImageToolAvailable && assistantImageToolCanChange,
                        onClick = {
                            onSetAssistantImageToolAutoApproval(!assistantImageToolAutoApproval)
                        }
                    )
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = "相机",
                        onClick = onCamera
                    )
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = "照片",
                        onClick = onPhoto
                    )
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = "文件",
                        onClick = onFile
                    )
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = "从文件库添加",
                        onClick = onLibrary
                    )
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = when {
                            !webSearchEnabled -> "联网检索：去启用"
                            !webSearchConfigured && webSearchProviderLabel.contains("协议自检源") -> "联网检索：协议自检"
                            !webSearchConfigured -> "联网检索：网页直读"
                            webSearchTurnModeLabel.isNotBlank() -> "联网检索：$webSearchTurnModeLabel"
                            webSearchProviderLabel.contains("智能") -> "联网检索：智能判断"
                            webSearchProviderLabel.contains("始终") -> "联网检索：始终开启"
                            webSearchEnabledForTurn -> "联网检索：本轮开启"
                            else -> "联网检索：手动开启"
                        },
                        subtitle = when {
                            !webSearchEnabled -> "设置中开启"
                            !webSearchConfigured && webSearchProviderLabel.contains("协议自检源") -> "可直读链接 · 关键词搜索未接入"
                            !webSearchConfigured -> "可读取链接 · 搜索未配置"
                            else -> webSearchProviderLabel.takeIf { it.isNotBlank() }
                        },
                        onClick = onWebSearch
                    )
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = "研究模式：${webSearchResearchModeLabel.ifBlank { "自动" }}",
                        subtitle = when {
                            !webSearchEnabled -> "先启用联网检索"
                            webSearchResearchOverridden -> "仅影响下一轮发送"
                            else -> "跟随联网检索默认设置"
                        },
                        onClick = onResearchMode
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactInputActionRow(
    icon: @Composable () -> Unit,
    label: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            color = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF1F3F4),
            shape = CircleShape
        ) {
            Box(contentAlignment = Alignment.Center) {
                icon()
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = label,
                color = (if (darkTheme) MaterialTheme.colorScheme.onSurface else Color(0xFF202124))
                    .copy(alpha = if (enabled) 1f else 0.58f),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 19.sp),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.takeIf(String::isNotBlank)?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.82f else 0.62f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
