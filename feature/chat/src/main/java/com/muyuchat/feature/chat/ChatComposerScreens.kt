package com.muyuchat.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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

// P9/S13：从 ChatChromeScreens.kt 等值移出的输入区簇（附件助手 + 输入栏 + composer 状态/菜单）。
// 原样逐字迁移，未做行为改动；双向跨区入口按协议提升 internal（4 随迁函数 + 保留区 2 项）。
// P9/S13: private→internal（跨文件被 ChatChromeScreens 复合区引用）
internal fun extractAttachmentName(input: String): String? =
    ATTACHMENT_NAME_PATTERNS.firstNotNullOfOrNull { pattern ->
        pattern.find(input)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

// P9/S13: private→internal（跨文件被 ChatChromeScreens 复合区引用）
internal fun displayInputWithoutAttachment(input: String): String {
    val marker = firstAttachmentMarker(input)
    if (marker < 0) return input
    return input.substring(0, marker).trimEnd()
}

internal fun saveCameraPreview(context: Context, bitmap: Bitmap): Uri {
    val dir = File(context.cacheDir, "mca_camera").apply { mkdirs() }
    val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
    FileOutputStream(file).use { output ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
    }
    return Uri.fromFile(file)
}

private fun removeAttachmentFromInput(input: String): String =
    displayInputWithoutAttachment(input).trimEnd()

private fun mergeInputWithExistingAttachment(currentInput: String, visibleText: String): String {
    val marker = firstAttachmentMarker(currentInput)
    if (marker < 0) return visibleText
    val attachment = currentInput.substring(marker).trimStart()
    return buildString {
        append(visibleText)
        if (visibleText.isNotBlank()) append("\n\n")
        append(attachment)
    }
}

private val ATTACHMENT_PREFIXES = listOf("【上传文件：", "【上传图片：")

private fun firstAttachmentMarker(input: String): Int =
    ATTACHMENT_PREFIXES
        .map { input.indexOf(it) }
        .filter { it >= 0 }
        .minOrNull() ?: -1

private val ATTACHMENT_NAME_PATTERNS = listOf(
    Regex("""【上传文件：([^】]+)】"""),
    Regex("""【上传图片：([^】]+)】""")
)

private val IMAGE_ATTACHMENT_PATTERN = Regex("""【上传图片：([^】]+)】""")
private val IMAGE_ATTACHMENT_WITH_URI_PATTERN = Regex("""【上传图片：([^】]+)】(?:\s*\n描述：[^\n]+)?\s*\n(\S+)""")

private const val CHAT_ATTACHMENT_IDENTITY_MAX_BYTES = 64L * 1024L * 1024L

/**
 * Resolves the content identity used by the composer preflight and message bubble.
 *
 * Picker URIs are not the only representation that can occur here.  Imported
 * assets are persisted as private `file://` URIs, and an older history can also
 * contain an absolute path.  The old implementation returned `null` for those
 * paths, so two copies of the same picked image survived the UI preflight and
 * were rendered as two attachments until the conversation was reloaded.  Keep
 * content-provider reads context-aware, then delegate every file/data/URL form
 * to the engine's shared identity contract.
 *
 * This function is called from a coroutine on Dispatchers.IO.  It streams raw
 * bytes and never retains the image in memory.
 */
internal fun chatImageAttachmentContentIdentity(context: Context, rawUri: String): String? {
    val uri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return null
    if (!uri.scheme.equals("content", ignoreCase = true)) {
        // Local imported files and inline data URLs can use the same byte-based
        // identity as the native/cloud request path.  Remote URLs remain
        // conservative and are normalized by the shared helper.
        return runCatching {
            ChatImageAttachment(uriString = rawUri).visionDeduplicationKey()
        }.getOrNull()
    }
    return runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = requireNotNull(context.contentResolver.openInputStream(uri)) {
            "Cannot read image attachment URI."
        }
        var totalBytes = 0L
        input.use { stream ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                totalBytes += read
                require(totalBytes <= CHAT_ATTACHMENT_IDENTITY_MAX_BYTES) {
                    "Image attachment is too large to hash for duplicate detection."
                }
                digest.update(buffer, 0, read)
            }
        }
        "bytes:" + digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }.getOrNull()
}

// P9/S13: private→internal（跨文件被 ChatChromeScreens 复合区引用）
internal fun hasImageAttachmentMarker(input: String): Boolean =
    IMAGE_ATTACHMENT_PATTERN.containsMatchIn(input)

// P9/S13: private→internal（跨文件被 ChatChromeScreens 复合区引用）
internal fun extractAttachmentMeta(input: String): String? {
    val uriString = IMAGE_ATTACHMENT_WITH_URI_PATTERN.find(input)
        ?.groupValues
        ?.getOrNull(2)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val sourceLabel = when {
        uriString.startsWith("content://", ignoreCase = true) -> "系统图片"
        uriString.startsWith("file://", ignoreCase = true) -> "本地文件"
        uriString.startsWith("http://", ignoreCase = true) || uriString.startsWith("https://", ignoreCase = true) -> "网络图片"
        uriString.startsWith("data:image", ignoreCase = true) -> "内联图片"
        else -> "图片输入"
    }
    val extension = uriString
        .substringBefore('?')
        .substringAfterLast('.', "")
        .takeIf { it.length in 2..5 }
        ?.uppercase()
    return listOfNotNull(sourceLabel, extension, "发送前自动压缩").joinToString(" · ")
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ChatInputBar(
    onOpenModels: () -> Unit,
    input: String,
    isGenerating: Boolean,
    selectedImageModelName: String?,
    selectedImageModelIsCloud: Boolean,
    assistantImageToolAvailable: Boolean,
    assistantImageToolUnavailableReason: String,
    assistantImageToolAutoApproval: Boolean,
    assistantImageToolCanChange: Boolean,
    imageGenerationBusy: Boolean,
    statusMessage: String?,
    onInputChange: (String) -> Unit,
    onDismissStatusMessage: () -> Unit,
    onSend: () -> Unit,
    attachmentPreflightRunning: Boolean = false,
    onSendImagePrompt: () -> Unit,
    onSetAssistantImageToolAutoApproval: (Boolean) -> Unit,
    onStop: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenPhoto: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenFileLibrary: () -> Unit,
    reasoningMode: ReasoningMode,
    onReasoningModeChange: (ReasoningMode) -> Unit,
    webSearchEnabled: Boolean,
    webSearchConfigured: Boolean,
    webSearchEnabledForTurn: Boolean,
    webSearchStatusMessage: String?,
    webSearchTurnModeLabel: String,
    webSearchResearchMode: String,
    webSearchResearchModeLabel: String,
    webSearchResearchOverridden: Boolean,
    webSearchProviderLabel: String,
    visionCapabilityLabel: String,
    visionCapabilityDetail: String,
    visionCapabilityReady: Boolean,
    onToggleWebSearchForTurn: () -> Unit,
    onSelectWebSearchResearchMode: (String) -> Unit,
    onOpenWebSearchSettings: () -> Unit,
    onMeasuredHeight: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showActionSheet by rememberSaveable { mutableStateOf(false) }
    var researchModeExpanded by rememberSaveable { mutableStateOf(false) }
    var imagePromptMode by rememberSaveable { mutableStateOf(false) }
    var showAutoImageApprovalConfirmation by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val inputShellColor = if (darkTheme) MaterialTheme.colorScheme.surface else McaInputShell
    val inputFieldColor = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else McaInputField
    val inputIconSurfaceColor = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else McaInputIconSurface
    val inputTextColor = if (darkTheme) MaterialTheme.colorScheme.onSurface else McaInputText
    val inputPlaceholderColor = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else McaInputPlaceholder
    val visibleInput = displayInputWithoutAttachment(input)
    val slashCommandSuggestions = remember(visibleInput) {
        chatSlashCommandSuggestions(visibleInput)
    }
    if (showAutoImageApprovalConfirmation) {
        AlertDialog(
            onDismissRequest = { showAutoImageApprovalConfirmation = false },
            title = { Text("允许本对话自动生图？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("之后角色发起的图片请求会直接执行，不再逐次询问。你可以随时切回“每次询问”。")
                    Text("当前生图模型：${selectedImageModelName.orEmpty().ifBlank { "尚未选择" }}")
                    if (selectedImageModelIsCloud) {
                        Text("提示词会发送给云端生图服务；服务商可能按其规则计费。")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAutoImageApprovalConfirmation = false
                    onSetAssistantImageToolAutoApproval(true)
                }) { Text("允许本对话自动生成") }
            },
            dismissButton = {
                TextButton(onClick = { showAutoImageApprovalConfirmation = false }) { Text("取消") }
            }
        )
    }
    if (showActionSheet) {
        CompactInputActionMenu(
            onDismiss = {
                researchModeExpanded = false
                showActionSheet = false
            },
            onCamera = {
                showActionSheet = false
                onOpenCamera()
            },
            onPhoto = {
                showActionSheet = false
                onOpenPhoto()
            },
            onFile = {
                showActionSheet = false
                onOpenFile()
            },
            onLibrary = {
                showActionSheet = false
                onOpenFileLibrary()
            },
            onWebSearch = {
                researchModeExpanded = false
                showActionSheet = false
                onToggleWebSearchForTurn()
            },
            onResearchMode = {
                if (webSearchEnabled) {
                    researchModeExpanded = !researchModeExpanded
                } else {
                    researchModeExpanded = false
                    showActionSheet = false
                    onSelectWebSearchResearchMode(webSearchResearchMode)
                }
            },
            onOpenWebSearchSettings = {
                researchModeExpanded = false
                showActionSheet = false
                onOpenWebSearchSettings()
            },
            onImagePromptMode = {
                researchModeExpanded = false
                showActionSheet = false
                imagePromptMode = !imagePromptMode
            },
            assistantImageToolAvailable = assistantImageToolAvailable,
            assistantImageToolUnavailableReason = assistantImageToolUnavailableReason,
            assistantImageToolAutoApproval = assistantImageToolAutoApproval,
            assistantImageToolCanChange = assistantImageToolCanChange,
            onSetAssistantImageToolAutoApproval = { enabled ->
                researchModeExpanded = false
                showActionSheet = false
                if (enabled) {
                    showAutoImageApprovalConfirmation = true
                } else {
                    onSetAssistantImageToolAutoApproval(false)
                }
            },
            imagePromptMode = imagePromptMode,
            webSearchEnabled = webSearchEnabled,
            webSearchConfigured = webSearchConfigured,
            webSearchEnabledForTurn = webSearchEnabledForTurn,
            webSearchTurnModeLabel = webSearchTurnModeLabel,
            webSearchResearchMode = webSearchResearchMode,
            webSearchResearchModeLabel = webSearchResearchModeLabel,
            webSearchResearchOverridden = webSearchResearchOverridden,
            webSearchProviderLabel = webSearchProviderLabel,
            modifier = modifier
        )
    }
    if (showActionSheet && researchModeExpanded) {
        ResearchModeFloatingCapsule(
            selected = webSearchResearchMode,
            overridden = webSearchResearchOverridden,
            onDismiss = { researchModeExpanded = false },
            onSelect = { mode ->
                researchModeExpanded = false
                showActionSheet = false
                onSelectWebSearchResearchMode(mode)
            },
            modifier = modifier
        )
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { onMeasuredHeight(it.height) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        color = inputShellColor,
        shape = RoundedCornerShape(36.dp),
        shadowElevation = 14.dp
    ) {
        Column(
            modifier = Modifier
                .padding(7.dp)
                .navigationBarsPadding()
        ) {
            statusMessage?.takeIf { it.isNotBlank() }?.let { message ->
                if (userNotice(message) != null) {
                    GuidanceNotice(message, onOpenModels, onDismissStatusMessage, onDismissStatusMessage)
                } else {
                ComposerStatusMessage(
                    message = message,
                    onDismiss = onDismissStatusMessage,
                    modifier = Modifier.padding(start = 4.dp, end = 2.dp, bottom = 6.dp)
                )
                }
            }
            if (!webSearchStatusMessage.isNullOrBlank()) {
                WebSearchStatusChip(
                    message = webSearchStatusMessage,
                    active = webSearchEnabledForTurn,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp)
                )
            }
            if (imagePromptMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 2.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "聊天内生图",
                        color = McaPrimaryBlue,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        when {
                            imageGenerationBusy -> "图片任务进行中，完成后可继续 · ${selectedImageModelName.orEmpty()}"
                            selectedImageModelName.isNullOrBlank() -> "尚未选择生图模型，点击查看模型"
                            else -> "当前：${if (selectedImageModelIsCloud) "云端" else "本地"} · $selectedImageModelName"
                        },
                        color = inputPlaceholderColor,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = selectedImageModelName.isNullOrBlank()) {
                                onOpenModels()
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(
                        onClick = { imagePromptMode = false },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "退出聊天生图模式",
                            modifier = Modifier.size(16.dp),
                            tint = inputPlaceholderColor
                        )
                    }
                }
            }
            AttachmentPreview(
                input = input,
                visionCapabilityLabel = visionCapabilityLabel,
                visionCapabilityDetail = visionCapabilityDetail,
                visionCapabilityReady = visionCapabilityReady,
                onRemove = { onInputChange(removeAttachmentFromInput(input)) }
            )
            if (!isGenerating && slashCommandSuggestions.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 50.dp, end = 48.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    slashCommandSuggestions.forEach { command ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onInputChange(
                                        mergeInputWithExistingAttachment(
                                            input,
                                            insertChatSlashCommand(visibleInput, command)
                                        )
                                    )
                                },
                            color = inputFieldColor,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = command.command,
                                    color = McaPrimaryBlue,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = command.title,
                                        color = inputTextColor,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    Text(
                                        text = command.description,
                                        color = inputPlaceholderColor,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isGenerating) MaterialTheme.colorScheme.surfaceVariant else inputIconSurfaceColor)
                        .clickable(enabled = !isGenerating) { showActionSheet = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = "更多操作", modifier = Modifier.size(24.dp), tint = if (isGenerating) inputPlaceholderColor else McaPrimaryBlue)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp),
                    color = inputFieldColor,
                    shape = CircleShape
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (visibleInput.isBlank()) {
                            Text(
                                if (imagePromptMode) "描述要生成的图片" else "问问 MCA",
                                color = inputPlaceholderColor,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp)
                            )
                        }
                        BasicTextField(
                            value = visibleInput,
                            onValueChange = { visibleText ->
                                onInputChange(mergeInputWithExistingAttachment(input, visibleText))
                            },
                            enabled = !isGenerating && !attachmentPreflightRunning,
                            maxLines = 4,
                            textStyle = TextStyle(
                                color = inputTextColor,
                                fontSize = 15.sp,
                                lineHeight = 20.sp
                            ),
                            cursorBrush = SolidColor(McaPrimaryBlue),
                            modifier = Modifier.fillMaxWidth().testTag("chat.editor")
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                FloatingActionButton(
                    onClick = {
                        if (isGenerating) onStop()
                        else if (attachmentPreflightRunning) Unit
                        else if (imagePromptMode) onSendImagePrompt()
                        else onSend()
                    },
                    containerColor = McaPrimaryBlue,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
                    shape = CircleShape,
                    modifier = Modifier.size(40.dp)
                ) {
                    if (attachmentPreflightRunning && !isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(19.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(
                            imageVector = when {
                                isGenerating -> Icons.Default.Stop
                                imagePromptMode -> Icons.Default.Image
                                else -> Icons.AutoMirrored.Filled.Send
                            },
                            contentDescription = when {
                                isGenerating -> "停止"
                                imagePromptMode -> "生成图片"
                                else -> "发送"
                            },
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

internal fun composerStatusIsError(message: String): Boolean =
    listOf("失败", "无法", "未发送", "未加载", "请先", "超过").any(message::contains)

@Composable
private fun ComposerStatusMessage(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isError = composerStatusIsError(message)
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = contentColor
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "关闭提示",
                modifier = Modifier.size(16.dp),
                tint = contentColor
            )
        }
    }
}

@Composable
private fun WebSearchStatusChip(
    message: String,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (active && !darkTheme) Color(0xFFEAF1FF) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (active) McaPrimaryBlue else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private enum class ResearchModeChoice(val key: String, val label: String) {
    OFF("OFF", "普通"),
    AUTO("AUTO", "自动"),
    DEEP("DEEP", "深度");

    companion object {
        fun selectedKey(value: String): String =
            entries.firstOrNull { it.key.equals(value, ignoreCase = true) }?.key ?: AUTO.key
    }
}

@Composable
private fun ResearchModeFloatingCapsule(
    selected: String,
    overridden: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 86.dp)
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomStart
    ) {
        val capsuleWidth = 128.dp
        val startPadding = minOf(252.dp, maxWidth - capsuleWidth - 12.dp).coerceAtLeast(12.dp)
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(120)) + slideInHorizontally(tween(160), initialOffsetX = { -it / 4 }),
            exit = fadeOut(tween(100)),
            modifier = Modifier
                .padding(start = startPadding)
                .width(capsuleWidth)
        ) {
            val darkTheme = isSystemInDarkTheme()
            Surface(
                modifier = Modifier.clickable(enabled = false) {},
                color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 14.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (overridden) "本轮研究" else "研究模式",
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
                            color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF80868B),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭研究模式选择",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable(onClick = onDismiss)
                        )
                    }
                    val selectedKey = ResearchModeChoice.selectedKey(selected)
                    ResearchModeChoice.entries.forEach { mode ->
                        ReasoningModePill(
                            label = mode.label,
                            selected = selectedKey == mode.key,
                            onClick = { onSelect(mode.key) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactInputActionMenu(
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
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(120)) + slideInHorizontally(
                animationSpec = tween(180),
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
