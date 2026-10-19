package com.muyuchat.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.mcaPressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// P9/S19：从 ChatChromeScreens.kt 等值移出的背景/设置页/新会话对话框/空态簇。
// 原样逐字迁移，未做行为改动；入口原即 internal，双向扫描按协议处理。
@Composable
internal fun ChatBackground(
    state: ChatBackgroundState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = state.imageUri) {
        value = withContext(Dispatchers.IO) {
            val raw = state.imageUri?.trim().orEmpty()
            if (raw.isBlank()) return@withContext null
            decodeChatBackgroundBitmap(context, raw)
        }
    }
    Box(modifier = modifier) {
        bitmap?.let { image ->
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = when (state.scaleMode) {
                    ChatBackgroundScaleMode.CROP -> ContentScale.Crop
                    ChatBackgroundScaleMode.FIT -> ContentScale.Fit
                    ChatBackgroundScaleMode.CENTER -> ContentScale.None
                },
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (state.blurRadius > 0f) Modifier.blur(state.blurRadius.dp) else Modifier)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = state.scrimAlpha.coerceIn(0f, 0.9f)))
            )
        }
    }
}

private fun decodeChatBackgroundBitmap(context: Context, rawUri: String): Bitmap? = runCatching {
    fun openStream(): InputStream? = when {
        rawUri.startsWith("content://") || rawUri.startsWith("android.resource://") || rawUri.startsWith("file://") ->
            context.contentResolver.openInputStream(Uri.parse(rawUri))
        else -> File(rawUri).inputStream()
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > CHAT_BACKGROUND_MAX_DECODE_EDGE ||
        bounds.outHeight / sampleSize > CHAT_BACKGROUND_MAX_DECODE_EDGE
    ) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    openStream()?.use { BitmapFactory.decodeStream(it, null, options) }
}.getOrNull()

@Composable
internal fun ChatBackgroundSettingsPage(
    modifier: Modifier = Modifier,
    effectiveState: ChatBackgroundState,
    globalState: ChatBackgroundState,
    assistantState: ChatBackgroundState?,
    sessionState: ChatBackgroundState?,
    hasActiveSession: Boolean,
    importing: Boolean,
    onDismiss: () -> Unit,
    onChooseImage: (ChatBackgroundScope) -> Unit,
    onChange: (ChatBackgroundScope, ChatBackgroundState?) -> Unit,
    onReset: (ChatBackgroundScope) -> Unit
) {
    var scope by rememberSaveable(hasActiveSession) {
        mutableStateOf(if (hasActiveSession) ChatBackgroundScope.SESSION else ChatBackgroundScope.ASSISTANT)
    }
    val explicitState = when (scope) {
        ChatBackgroundScope.GLOBAL -> globalState
        ChatBackgroundScope.ASSISTANT -> assistantState
        ChatBackgroundScope.SESSION -> sessionState
    }
    val inheritedState = when (scope) {
        ChatBackgroundScope.GLOBAL -> ChatBackgroundState()
        ChatBackgroundScope.ASSISTANT -> globalState
        ChatBackgroundScope.SESSION -> assistantState ?: globalState
    }
    val state = explicitState ?: inheritedState
    val hasOverride = when (scope) {
        ChatBackgroundScope.GLOBAL -> globalState != ChatBackgroundState()
        ChatBackgroundScope.ASSISTANT -> assistantState != null
        ChatBackgroundScope.SESSION -> sessionState != null
    }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Same 48dp target and 24dp glyph as the previous IconButton; only the shared bounded
            // ripple + layer-phase press weight differ. Tint keeps the previous content colour.
            McaIconButton(
                contentDescription = "返回应用菜单",
                onClick = onDismiss,
                glyphSize = 24.dp,
                tint = LocalContentColor.current
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Text("聊天背景", style = MaterialTheme.typography.titleLarge)
        }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("应用范围", style = MaterialTheme.typography.labelLarge)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    FilterChip(
                        selected = scope == ChatBackgroundScope.GLOBAL,
                        onClick = { scope = ChatBackgroundScope.GLOBAL },
                        label = { Text("全局") }
                    )
                    FilterChip(
                        selected = scope == ChatBackgroundScope.ASSISTANT,
                        onClick = { scope = ChatBackgroundScope.ASSISTANT },
                        label = { Text("当前角色") }
                    )
                    FilterChip(
                        selected = scope == ChatBackgroundScope.SESSION,
                        onClick = { scope = ChatBackgroundScope.SESSION },
                        enabled = hasActiveSession,
                        label = { Text("当前会话") }
                    )
                }
                Text(
                    text = when {
                        importing -> "正在复制并优化背景图片…"
                        state.imageUri.isNullOrBlank() -> "使用默认主题背景"
                        explicitState == null -> "正在继承上一级背景"
                        else -> "已为此范围设置自定义背景"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    Button(onClick = { onChooseImage(scope) }, enabled = !importing) { Text("选择图片") }
                    TextButton(
                        onClick = { onReset(scope) },
                        enabled = hasOverride && !importing
                    ) { Text(if (scope == ChatBackgroundScope.GLOBAL) "恢复默认" else "改为继承") }
                }
                Text("遮罩 ${((state.scrimAlpha.coerceIn(0f, 1f)) * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = state.scrimAlpha.coerceIn(0f, 0.9f),
                    onValueChange = { onChange(scope, state.copy(scrimAlpha = it)) },
                    enabled = !importing,
                    valueRange = 0f..0.9f
                )
                Text("模糊 ${state.blurRadius.roundToInt()} dp", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = state.blurRadius.coerceIn(0f, 24f),
                    onValueChange = { onChange(scope, state.copy(blurRadius = it)) },
                    enabled = !importing,
                    valueRange = 0f..24f
                )
                Text("缩放方式", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.scaleMode == ChatBackgroundScaleMode.CROP,
                        onClick = { onChange(scope, state.copy(scaleMode = ChatBackgroundScaleMode.CROP)) },
                        label = { Text("裁剪填充") }
                    )
                    FilterChip(
                        selected = state.scaleMode == ChatBackgroundScaleMode.FIT,
                        onClick = { onChange(scope, state.copy(scaleMode = ChatBackgroundScaleMode.FIT)) },
                        label = { Text("完整显示") }
                    )
                    FilterChip(
                        selected = state.scaleMode == ChatBackgroundScaleMode.CENTER,
                        onClick = { onChange(scope, state.copy(scaleMode = ChatBackgroundScaleMode.CENTER)) },
                        label = { Text("居中") }
                    )
                }
                if (effectiveState.imageUri != state.imageUri) {
                    Text(
                        "当前画布由更高优先级的设置覆盖",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
    }
}

@Composable
internal fun NewConversationAssistantDialog(
    assistants: List<AssistantUiItem>,
    history: List<ChatHistoryItem>,
    selectedAssistantId: String?,
    onSelectAssistant: (String) -> Unit,
    onResume: (String) -> Unit,
    onCreate: (String) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    val selected = assistants.firstOrNull { it.id == selectedAssistantId }
    val previous = history.filter { it.assistantId == selected?.id }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (selected == null) "选择角色卡" else selected.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (selected == null) {
                    if (assistants.isEmpty()) Text("暂无角色卡")
                    assistants.sortedByDescending { it.selected }.forEach { assistant ->
                        val rowInteraction = remember { MutableInteractionSource() }
                        Surface(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .clickable(
                                    interactionSource = rowInteraction,
                                    indication = LocalIndication.current
                                ) { onSelectAssistant(assistant.id) }
                                .mcaPressScale(rowInteraction),
                            color = Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AssistantAvatar(assistant.name, assistant.avatar, selected = assistant.selected)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(assistant.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (assistant.tag.isNotBlank()) {
                                        Text(
                                            assistant.tag,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text("返回已有会话", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    previous.forEach { item ->
                        val rowInteraction = remember { MutableInteractionSource() }
                        Surface(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .clickable(
                                    interactionSource = rowInteraction,
                                    indication = LocalIndication.current
                                ) { onResume(item.id) }
                                .mcaPressScale(rowInteraction),
                            color = if (item.selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    item.summary.ifBlank { item.updatedAtText },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (selected != null) {
                TextButton(onClick = { onCreate(selected.id) }) { Text("创建新会话") }
            }
        },
        dismissButton = {
            TextButton(onClick = if (selected == null) onDismiss else onBack) {
                Text(if (selected == null) "取消" else "返回角色卡")
            }
        }
    )
}

@Composable
internal fun EmptyChatPanel(
    modelName: String?,
    showSetup: Boolean = false,
    onChooseModel: () -> Unit = {},
    onImportModel: () -> Unit = {},
    onOpenQuickStart: () -> Unit = {}
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("MCA", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                displayModelName(modelName) ?: "还没有开始聊天，选择一个模型即可使用。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )

            if (modelName == null && showSetup) {
                Button(onClick = onChooseModel, modifier = Modifier.fillMaxWidth()) {
                    Text("选择推荐模型")
                }
                OutlinedButton(onClick = onImportModel, modifier = Modifier.fillMaxWidth()) {
                    Text("导入已有模型")
                }
                TextButton(onClick = onOpenQuickStart, modifier = Modifier.fillMaxWidth()) {
                    Text("第一次使用？查看 3 步快速开始")
                }
            }
        }
    }
}

internal fun displayModelName(value: String?): String? =
    value?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.removeModelSuffix()

// P9/S14: private→internal（跨区入口：迁移的模型切换复合区引用）
internal fun formatModelBytes(bytes: Long): String? {
    if (bytes <= 0L) return null
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    return if (gb >= 1.0) {
        "%.1f GB".format(gb)
    } else {
        "%.0f MB".format(bytes / 1024.0 / 1024.0)
    }
}

private fun String.removeModelSuffix(): String =
    removeSuffix(".gguf")
        .removeSuffix(".GGUF")
        .removeSuffix(".mnn")
        .removeSuffix(".MNN")
        .removeSuffix("-GGUF")
        .removeSuffix("_GGUF")
        .removeSuffix("-MNN")
        .removeSuffix("_MNN")
