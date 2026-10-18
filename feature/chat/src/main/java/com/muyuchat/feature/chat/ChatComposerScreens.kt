package com.muyuchat.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.semantics.disabled
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
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.designsystem.mcaPressScale

// P9/S13：从 ChatChromeScreens.kt 等值移出的输入区簇（附件助手 + 输入栏 + composer 状态/菜单）。
// 原样逐字迁移，未做行为改动；双向跨区入口按协议提升 internal（4 随迁函数 + 保留区 2 项）。
/**
 * Composer rows grow/shrink with a spring so an attachment or status line never hard-cuts the input
 * shell height. Only the affected row is animated; the text field, its selection and its composing
 * region keep their composition identity (V9 §15.4).
 */
private val composerRowEnter =
    fadeIn(animationSpec = tween(durationMillis = 140)) + expandVertically(
        animationSpec = McaMotion.sizeChangePx,
        expandFrom = Alignment.Top
    )

private val composerRowExit =
    fadeOut(animationSpec = tween(durationMillis = 90)) + shrinkVertically(
        animationSpec = McaMotion.sizeChangePx,
        shrinkTowards = Alignment.Top
    )

/** What the primary composer FAB currently represents; keeps the cross-fade target stable. */
private enum class ComposerPrimaryAction { Send, Stop, GenerateImage, Preflight }

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
    // Composer rows keep their last non-blank content while their exit transition collapses;
    // otherwise the row is already empty on the first exit frame and disappears without animating.
    // The retained value is local snapshot state written only from SideEffect, never while
    // composing, so composing the input bar does not write snapshot state (hard lock §6). The row
    // content observes the same state, so it still refreshes when a new value arrives.
    val composerStatus = statusMessage?.takeIf { it.isNotBlank() }
    val composerWebSearchStatus = webSearchStatusMessage?.takeIf { it.isNotBlank() }
    val attachmentPresent = remember(input) { extractAttachmentName(input) != null }
    val composerStatusForExit = remember { mutableStateOf(composerStatus) }
    val webSearchStatusForExit = remember { mutableStateOf(composerWebSearchStatus) }
    val attachmentForExit = remember {
        mutableStateOf(if (attachmentPresent) input else null)
    }
    SideEffect {
        if (composerStatus != null) composerStatusForExit.value = composerStatus
        if (composerWebSearchStatus != null) webSearchStatusForExit.value = composerWebSearchStatus
        if (attachmentPresent) attachmentForExit.value = input
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
            AnimatedVisibility(
                visible = composerStatus != null,
                enter = composerRowEnter,
                exit = composerRowExit
            ) {
                composerStatusForExit.value?.let { message ->
                    if (userNotice(message) != null) {
                        GuidanceNotice(
                            message,
                            onOpenModels,
                            onDismissStatusMessage,
                            onDismissStatusMessage
                        )
                    } else {
                        ComposerStatusMessage(
                            message = message,
                            onDismiss = onDismissStatusMessage,
                            modifier = Modifier.padding(start = 4.dp, end = 2.dp, bottom = 6.dp)
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = composerWebSearchStatus != null,
                enter = composerRowEnter,
                exit = composerRowExit
            ) {
                webSearchStatusForExit.value?.let { message ->
                    WebSearchStatusChip(
                        message = message,
                        active = webSearchEnabledForTurn,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp)
                    )
                }
            }
            AnimatedVisibility(
                visible = imagePromptMode,
                enter = composerRowEnter,
                exit = composerRowExit
            ) {
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
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "退出聊天生图模式",
                            modifier = Modifier.size(16.dp),
                            tint = inputPlaceholderColor
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = attachmentPresent,
                enter = composerRowEnter,
                exit = composerRowExit
            ) {
                attachmentForExit.value?.let { attachmentInput ->
                    AttachmentPreview(
                        input = attachmentInput,
                        visionCapabilityLabel = visionCapabilityLabel,
                        visionCapabilityDetail = visionCapabilityDetail,
                        visionCapabilityReady = visionCapabilityReady,
                        onRemove = { onInputChange(removeAttachmentFromInput(attachmentInput)) }
                    )
                }
            }
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
                    Icon(Icons.Rounded.Add, contentDescription = "更多操作", modifier = Modifier.size(24.dp), tint = if (isGenerating) inputPlaceholderColor else McaPrimaryBlue)
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
                val sendFabEnabled = isGenerating || !attachmentPreflightRunning
                val primaryAction = when {
                    attachmentPreflightRunning && !isGenerating -> ComposerPrimaryAction.Preflight
                    isGenerating -> ComposerPrimaryAction.Stop
                    imagePromptMode -> ComposerPrimaryAction.GenerateImage
                    else -> ComposerPrimaryAction.Send
                }
                val sendInteraction = remember { MutableInteractionSource() }
                FloatingActionButton(
                    onClick = {
                        if (sendFabEnabled) {
                            when {
                                isGenerating -> onStop()
                                imagePromptMode -> onSendImagePrompt()
                                else -> onSend()
                            }
                        }
                    },
                    containerColor = McaPrimaryBlue,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
                    shape = CircleShape,
                    modifier = Modifier
                        .size(40.dp)
                        .mcaPressScale(sendInteraction, enabled = sendFabEnabled)
                        .semantics { if (!sendFabEnabled) disabled() },
                    interactionSource = sendInteraction
                ) {
                    if (attachmentPreflightRunning && !isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(19.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        // Send/stop is the most repeated composer action, so the glyph swap is an
                        // explicit spring cross-fade instead of a hard icon change. Crossfade draws
                        // the alpha ramp in the graphics layer; no animation value is read during
                        // composition. Both outgoing and incoming glyphs are keyed by `action`, so
                        // the crossfade always shows the correct pair.
                        Crossfade(
                            targetState = primaryAction,
                            animationSpec = spring(
                                dampingRatio = ChatMotion.CONTROL_DAMPING_RATIO,
                                stiffness = ChatMotion.CONTROL_STIFFNESS
                            ),
                            label = "composerPrimaryAction"
                        ) { action ->
                            Icon(
                                imageVector = when (action) {
                                    ComposerPrimaryAction.Stop -> Icons.Rounded.Stop
                                    ComposerPrimaryAction.GenerateImage -> Icons.Rounded.Image
                                    else -> Icons.AutoMirrored.Rounded.Send
                                },
                                contentDescription = when (action) {
                                    ComposerPrimaryAction.Stop -> "停止"
                                    ComposerPrimaryAction.GenerateImage -> "生成图片"
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
}
