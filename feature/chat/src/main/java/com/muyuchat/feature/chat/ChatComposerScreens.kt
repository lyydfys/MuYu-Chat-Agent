package com.muyuchat.feature.chat

import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.mcaIconPlate
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import com.muyuchat.core.designsystem.McaIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
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
import com.muyuchat.core.designsystem.McaActionLabels
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.designsystem.mcaPressScale
import com.muyuchat.core.designsystem.rememberMcaHaptics
import com.muyuchat.core.designsystem.McaIconSize

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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    /** `+` menu "转为 Agent 任务"; durable app state, so the toggle is reported upward. */
    agentMode: Boolean = false,
    onAgentModeChange: (Boolean) -> Unit = {},
    /** Clears the next-turn research override when Agent mode is armed instead. */
    onClearWebSearchResearchMode: () -> Unit = {},
    /** One-turn quote shown above the input; null hides the bar. */
    quotedMessage: ChatQuotedMessage? = null,
    onDismissQuote: () -> Unit = {},
    onMeasuredHeight: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // R04：one owner for the sheet + research submenu pair. The two former booleans could disagree
    // (the capsule was removed by a host condition while the sheet was still fading, and neither
    // row gating nor the back gesture followed either of them).
    val composerMenu = rememberSaveable(saver = ComposerMenuLifecycle.LifecycleSaver) {
        ComposerMenuLifecycle()
    }
    // The three composer modes are mutually exclusive; [ChatComposerModes] owns that rule.
    //
    // Ownership is split on purpose:
    // - agent mode is durable app state (`MainUiState.agentModeEnabled`) because the app layer's send
    //   path reads it after the session is saved;
    // - the image-prompt draft belongs to this screen and is discarded with it;
    // - research is derived from the app-state override, so it is read-only here.
    // Toggles therefore report upward (agent) or write the local draft, and the plain booleans below
    // are only *reads* of the three owners. They cannot disagree, because every write goes through
    // the single exclusion rule in ChatComposerModes.
    var imagePromptMode by rememberSaveable { mutableStateOf(false) }
    val composerModes = ChatComposerModes(
        agentMode = agentMode,
        imagePromptMode = imagePromptMode,
        researchMode = webSearchResearchOverridden
    )
    // One place that applies a transition to all three owners: the local draft is written here, the
    // durable agent flag is reported upward, and the research override is cleared through its own
    // owner. Every enable-path below funnels through this, so the three can never disagree.
    val applyModes: (ChatComposerModes) -> Unit = { next ->
        imagePromptMode = next.imagePromptMode
        if (next.agentMode != agentMode) onAgentModeChange(next.agentMode)
    }
    // Selecting a research mode arms that mode and clears the other two first. Both entry points
    // (the sheet row and the floating capsule) use this same wrapper, so "research on + agent on"
    // cannot be produced by whichever one the user happened to tap.
    val selectResearchMode: (String) -> Unit = { mode ->
        applyModes(ChatComposerModes(researchMode = true))
        onSelectWebSearchResearchMode(mode)
    }
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
    val quoteForExit = remember { mutableStateOf(quotedMessage) }
    SideEffect {
        if (composerStatus != null) composerStatusForExit.value = composerStatus
        if (composerWebSearchStatus != null) webSearchStatusForExit.value = composerWebSearchStatus
        if (attachmentPresent) attachmentForExit.value = input
        if (quotedMessage != null) quoteForExit.value = quotedMessage
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
                McaButton(style = McaButtonStyle.Text,onClick = {
                    showAutoImageApprovalConfirmation = false
                    onSetAssistantImageToolAutoApproval(true)
                }) { Text("允许本对话自动生成") }
            },
            dismissButton = {
                McaButton(style = McaButtonStyle.Text,onClick = { showAutoImageApprovalConfirmation = false }) { Text("取消") }
            }
        )
    }
    // R04：back closes exactly one layer: top Dialog (own window) -> research submenu -> sheet ->
    // page, while the existing IME rule still hides the keyboard first. A layer that is already
    // fading keeps consuming the gesture, so a double back never pops two layers at once and never
    // reaches the page through a still-visible exit frame.
    BackHandler(enabled = composerMenu.isComposed && !WindowInsets.isImeVisible) {
        composerMenu.handleBack()
    }
    // Always composed: the host cannot remove it on dismiss or the exit animation never gets a
    // frame. CompactInputActionMenu draws nothing while closed.
    CompactInputActionMenu(
        menu = composerMenu,
        onCamera = onOpenCamera,
        onPhoto = onOpenPhoto,
        onFile = onOpenFile,
        onLibrary = onOpenFileLibrary,
        onImagePromptMode = {
            val next = composerModes.toggle(ChatComposerMode.IMAGE_PROMPT)
            // Only the enabling transition clears the other two; leaving a mode arms nothing.
            if (next.imagePromptMode) onClearWebSearchResearchMode()
            applyModes(next)
        },
        assistantImageToolAvailable = assistantImageToolAvailable,
        assistantImageToolUnavailableReason = assistantImageToolUnavailableReason,
        assistantImageToolAutoApproval = assistantImageToolAutoApproval,
        assistantImageToolCanChange = assistantImageToolCanChange,
        onSetAssistantImageToolAutoApproval = { enabled ->
            if (enabled) {
                showAutoImageApprovalConfirmation = true
            } else {
                onSetAssistantImageToolAutoApproval(false)
            }
        },
        agentMode = composerModes.agentMode,
        onToggleAgentMode = {
            val next = composerModes.toggle(ChatComposerMode.AGENT)
            if (next.agentMode) onClearWebSearchResearchMode()
            applyModes(next)
        },
        imagePromptMode = imagePromptMode,
        onWebSearch = {
            // Enabling web search is also "this is a normal chat turn": the two exclusive modes leave
            // through the same applier instead of silently staying armed.
            if (!webSearchEnabledForTurn) applyModes(ChatComposerModes())
            onToggleWebSearchForTurn()
        },
        onSelectWebSearchResearchMode = selectResearchMode,
        onOpenWebSearchSettings = onOpenWebSearchSettings,
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
    // R04/R05：kept composed so its own exit frame can draw (the host used to remove it on the same
    // frame the dismiss happened), with the same real-height budget as the sheet.
    ResearchModeFloatingCapsule(
        menu = composerMenu,
        selected = webSearchResearchMode,
        overridden = webSearchResearchOverridden,
        onSelect = selectResearchMode,
        modifier = modifier
    )
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { onMeasuredHeight(it.height) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        color = inputShellColor,
        shape = RoundedCornerShape(36.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        // Calibrated down from 10dp/8dp: a subtle 4dp shadow + 0.5dp optical hairline gives a clean flagship floating shell.
        shadowElevation = 4.dp
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
                    McaIconButton(
                        contentDescription = "退出聊天生图模式",
                        onClick = { imagePromptMode = false },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            McaIcons.Close,
                            contentDescription = null,
                            modifier = Modifier.size(McaIconSize.Xs),
                            tint = inputPlaceholderColor
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = quotedMessage != null,
                enter = composerRowEnter,
                exit = composerRowExit
            ) {
                quoteForExit.value?.let { quoted ->
                    ComposerQuoteBar(
                        quotedMessage = quoted,
                        onDismiss = onDismissQuote,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp)
                    )
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
            val haptics = rememberMcaHaptics()
            if (!isGenerating && slashCommandSuggestions.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 50.dp, end = 48.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    slashCommandSuggestions.forEach { command ->
                        val commandInteraction = remember(command.command) { MutableInteractionSource() }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .mcaPressScale(commandInteraction)
                                .clickable(
                                    interactionSource = commandInteraction,
                                    indication = ripple(bounded = true)
                                ) {
                                    haptics.tick()
                                    onInputChange(
                                        mergeInputWithExistingAttachment(
                                            input,
                                            insertChatSlashCommand(visibleInput, command)
                                        )
                                    )
                                },
                            color = inputFieldColor,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(
                                0.5.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (darkTheme) 0.40f else 0.28f)
                            )
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
            val moreInteraction = remember { MutableInteractionSource() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // R08：visible circle (40dp) and glyph (24dp) are unchanged. The press feedback now
                // uses the shared scale + a bounded ripple, and the button carries role/name and an
                // explicit action label instead of an unnamed bare clickable. The interactive bounds
                // stay 40dp here: a 48dp target inside this frozen 40dp composer row would either
                // grow the composer by 8dp or need an out-of-bounds hit area; the exact exception is
                // reported in ChatComposerMenuMetricsTest instead of quietly stretching the layout.
                val moreOpen = composerMenu.menu.wantsVisible
                val moreRotation = animateFloatAsState(
                    targetValue = if (moreOpen) 45f else 0f,
                    animationSpec = McaMotion.press,
                    label = "composerMoreRotation"
                )
                val moreBgColor = animateColorAsState(
                    targetValue = when {
                        isGenerating -> MaterialTheme.colorScheme.surfaceVariant
                        moreOpen -> McaPrimaryBlue.copy(alpha = if (darkTheme) 0.24f else 0.14f)
                        else -> inputIconSurfaceColor
                    },
                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 160),
                    label = "composerMoreBg"
                )
                val moreBorderColor = animateColorAsState(
                    targetValue = if (moreOpen) {
                        McaPrimaryBlue.copy(alpha = 0.38f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (darkTheme) 0.45f else 0.32f)
                    },
                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 160),
                    label = "composerMoreBorder"
                )
                  Box(
                    modifier = Modifier
                        .size(ComposerMenuMetrics.COMPOSER_MORE_VISIBLE_DP.dp)
                        .mcaPressScale(moreInteraction, enabled = !isGenerating)
                        .mcaIconPlate(
                            size = ComposerMenuMetrics.COMPOSER_MORE_VISIBLE_DP.dp,
                            shape = CircleShape,
                            containerColor = moreBgColor.value,
                            borderColor = moreBorderColor.value,
                            animateColors = false,
                        )
                        .clickable(
                            interactionSource = moreInteraction,
                            indication = ripple(bounded = true),
                            enabled = !isGenerating,
                            onClickLabel = McaActionLabels.OPEN_MORE,
                            role = Role.Button,
                            onClick = {
                                haptics.tick()
                                composerMenu.open()
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                      Icon(
                          McaIcons.Add,
                          contentDescription = "更多操作",
                          modifier = Modifier
                              .size(ComposerMenuMetrics.COMPOSER_MORE_GLYPH_DP.dp)
                              .graphicsLayer { rotationZ = moreRotation.value },
                          tint = if (isGenerating) inputPlaceholderColor else McaPrimaryBlue
                      )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .animateContentSize(animationSpec = McaMotion.sizeChangePx),
                    color = inputFieldColor,
                    // 20dp corner radius is optically a true capsule at single-line 40dp height,
                    // while preventing giant semicircular side distortion when expanding to 2-4 lines.
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(
                        0.5.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (darkTheme) 0.40f else 0.28f)
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (visibleInput.isBlank()) {
                            Text(
                                ChatComposerModes.composerPlaceholder(composerModes),
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
                            modifier = Modifier
                                .fillMaxWidth()
                                // The placeholder is a separate Text; give the field itself a
                                // name so TalkBack (and the a11y probe) can announce it.
                                .semantics { contentDescription = "消息输入框" }
                                .testTag("chat.editor")
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                // An empty composer with no attachment must not present an enabled primary action:
                // the button previously looked ready while send was a no-op.
                val hasSendableContent = visibleInput.isNotBlank() || attachmentPresent
                val sendFabEnabled = isGenerating || (!attachmentPreflightRunning && hasSendableContent)
                val sendFabAlpha = animateFloatAsState(
                    targetValue = if (sendFabEnabled) 1f else 0.55f,
                    animationSpec = McaMotion.press,
                    label = "sendFabEmphasis"
                )
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
                        .graphicsLayer { alpha = sendFabAlpha.value }
                        .border(
                            0.5.dp,
                            Color.White.copy(alpha = if (sendFabEnabled) 0.28f else 0.14f),
                            CircleShape
                        )
                        .mcaPressScale(sendInteraction, enabled = sendFabEnabled)
                        .semantics { if (!sendFabEnabled) disabled() },
                    interactionSource = sendInteraction
                ) {
                    if (attachmentPreflightRunning && !isGenerating) {
                        CircularProgressIndicator(
                            // The glyph is replaced by this ring during preflight; name the ring so
                            // the disabled FAB does not become an unnamed clickable in that state.
                            modifier = Modifier
                                .size(McaIconSize.Optical.Compact)
                                .semantics { contentDescription = "正在检查附件" },
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
                                    ComposerPrimaryAction.Stop -> McaIcons.Stop
                                    ComposerPrimaryAction.GenerateImage -> McaIcons.Image
                                    else -> McaIcons.Send
                                },
                                contentDescription = when (action) {
                                    ComposerPrimaryAction.Stop -> McaActionLabels.STOP
                                    ComposerPrimaryAction.GenerateImage -> "生成图片"
                                    else -> McaActionLabels.SEND
                                },
                                // The send glyph's ink sits left of its 24dp box; a 2dp shift puts
                                // the visible mass on the FAB centre (stop/image stay untouched).
                                modifier = if (action == ComposerPrimaryAction.Send) {
                                    Modifier.offset(x = 2.dp)
                                } else {
                                    Modifier
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
