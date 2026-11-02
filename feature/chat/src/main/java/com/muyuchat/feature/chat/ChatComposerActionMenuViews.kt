package com.muyuchat.feature.chat

import androidx.compose.material3.contentColorFor
import com.muyuchat.core.designsystem.McaIconPlate
import com.muyuchat.core.designsystem.McaIcons
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.designsystem.mcaPressScale
import com.muyuchat.core.designsystem.rememberMcaHaptics
import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.visionDeduplicationKey
import com.muyuchat.core.engine.ReasoningMode
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun CompactInputActionMenu(
    menu: ComposerMenuLifecycle,
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
    onSelectWebSearchResearchMode: (String) -> Unit,
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
    val scrollState = rememberScrollState()
    val haptics = rememberMcaHaptics()
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomStart
    ) {
        // R04：the scrim lives from the first closing frame until the layer is really removed, so
        // the visible exit can never pass a tap to the message list. Its existing look (transparent,
        // no added dim) is unchanged; a repeated dismiss is an idempotent no-op.
        if (menu.isComposed) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    // R12 a11y: this scrim IS the dismiss affordance for every user, so it must be
                    // named. As a bare clickable it showed up in the accessibility tree as a
                    // full-screen unlabelled control (caught by scripts/audit-a11y-naming.ps1).
                    .semantics { contentDescription = "关闭菜单" }
                    .clickable(onClickLabel = "关闭") { menu.dismiss() }
            )
        }
        // R05：the cap is the height the host really leaves above the composer (IME, safe area and
        // composer already removed by the host layout), not a fixed 520dp.
        var contentHeightPx by remember { mutableStateOf(0) }
        val contentHeightDp = with(LocalDensity.current) { contentHeightPx.toDp() }
        val hostAvailableHeightDp = if (constraints.hasBoundedHeight) {
            maxHeight.value.roundToInt()
        } else {
            Int.MAX_VALUE
        }
        val heightBudget = composerMenuHeightBudget(
            hostAvailableHeightDp = hostAvailableHeightDp,
            contentHeightDp = if (contentHeightPx == 0) {
                // Before the first measure: keep the scroll container armed instead of assuming the
                // content fits, so a short screen can never clip its last row for one frame.
                Int.MAX_VALUE
            } else {
                contentHeightDp.value.roundToInt()
            }
        )
        // Composed while open *and* while closing: the exit keeps drawing and the rows stay in the
        // tree, they simply refuse business actions from the first closing frame.
        AnimatedVisibility(
            visible = menu.menu.wantsVisible,
            enter = fadeIn(tween(120)) + slideInHorizontally(
                animationSpec = spring(
                    dampingRatio = ChatMotion.SURFACE_DAMPING_RATIO,
                    stiffness = ChatMotion.SURFACE_STIFFNESS,
                    visibilityThreshold = IntOffset.VisibilityThreshold
                ),
                initialOffsetX = { -it / 5 }
            ),
            exit = fadeOut(tween(100)) + slideOutHorizontally(
                animationSpec = tween(100),
                targetOffsetX = { -it / 5 }
            ),
            modifier = Modifier
                .padding(bottom = ComposerMenuMetrics.SHEET_BOTTOM_OFFSET_DP.dp)
                .width(ComposerMenuMetrics.SHEET_WIDTH_DP.dp)
        ) {
            // Content presence is the lifecycle's "on screen" signal; disposal is the end of the
            // exit. Neither depends on an animation callback that the system may collapse (animator
            // scale 0x) or that a newer open may have overtaken.
            DisposableEffect(menu) {
                menu.menu.onContentComposed()
                onDispose { menu.menu.onContentDisposed() }
            }
            Surface(
                modifier = Modifier.clickable(enabled = false) {},
                color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
                shape = RoundedCornerShape(30.dp),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f)),
                shadowElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier
                        .then(
                            if (heightBudget.maxHeightDp >= Int.MAX_VALUE) {
                                Modifier
                            } else {
                                Modifier.heightIn(max = heightBudget.maxHeightDp.dp)
                            }
                        )
                        .verticalScroll(scrollState, enabled = heightBudget.scrollable)
                ) {
                    Column(
                        modifier = Modifier
                            .onSizeChanged { contentHeightPx = it.height }
                            .padding(
                                horizontal = ComposerMenuMetrics.SHEET_HORIZONTAL_PADDING_DP.dp,
                                vertical = ComposerMenuMetrics.SHEET_VERTICAL_PADDING_DP.dp
                            ),
                        verticalArrangement = Arrangement.spacedBy(
                            ComposerMenuMetrics.SHEET_ROW_SPACING_DP.dp
                        )
                    ) {
                        CompactInputActionRow(
                            icon = { Icon(McaIcons.Image, contentDescription = null, modifier = Modifier.size(ComposerMenuMetrics.ROW_ICON_GLYPH_DP.dp)) },
                            label = if (imagePromptMode) "退出聊天生图" else "在聊天中生成图片",
                            actionable = menu.menu.acceptsActions,
                            onClick = {
                                haptics.tick()
                                menu.runMenuAction(onImagePromptMode)
                            }
                        )
                        CompactInputActionRow(
                            icon = { Icon(McaIcons.Image, contentDescription = null, modifier = Modifier.size(ComposerMenuMetrics.ROW_ICON_GLYPH_DP.dp)) },
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
                            actionable = menu.menu.acceptsActions,
                            onClick = {
                                haptics.tick()
                                menu.runMenuAction {
                                    onSetAssistantImageToolAutoApproval(
                                        !assistantImageToolAutoApproval
                                    )
                                }
                            }
                        )
                        CompactInputActionRow(
                            icon = { Icon(McaIcons.PhotoCamera, contentDescription = null, modifier = Modifier.size(ComposerMenuMetrics.ROW_ICON_GLYPH_DP.dp)) },
                            label = "相机",
                            actionable = menu.menu.acceptsActions,
                            onClick = {
                                haptics.tick()
                                menu.runMenuAction(onCamera)
                            }
                        )
                        CompactInputActionRow(
                            icon = { Icon(McaIcons.PhotoLibrary, contentDescription = null, modifier = Modifier.size(ComposerMenuMetrics.ROW_ICON_GLYPH_DP.dp)) },
                            label = "照片",
                            actionable = menu.menu.acceptsActions,
                            onClick = {
                                haptics.tick()
                                menu.runMenuAction(onPhoto)
                            }
                        )
                        CompactInputActionRow(
                            icon = { Icon(McaIcons.Folder, contentDescription = null, modifier = Modifier.size(ComposerMenuMetrics.ROW_ICON_GLYPH_DP.dp)) },
                            label = "文件",
                            actionable = menu.menu.acceptsActions,
                            onClick = {
                                haptics.tick()
                                menu.runMenuAction(onFile)
                            }
                        )
                        CompactInputActionRow(
                            icon = { Icon(McaIcons.Folder, contentDescription = null, modifier = Modifier.size(ComposerMenuMetrics.ROW_ICON_GLYPH_DP.dp)) },
                            label = "从文件库添加",
                            actionable = menu.menu.acceptsActions,
                            onClick = {
                                haptics.tick()
                                menu.runMenuAction(onLibrary)
                            }
                        )
                        CompactInputActionRow(
                            icon = { Icon(McaIcons.Search, contentDescription = null, modifier = Modifier.size(ComposerMenuMetrics.ROW_ICON_GLYPH_DP.dp)) },
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
                            actionable = menu.menu.acceptsActions,
                            onClick = {
                                haptics.tick()
                                menu.runMenuAction(onWebSearch)
                            }
                        )
                        CompactInputActionRow(
                            icon = { Icon(McaIcons.Psychology, contentDescription = null, modifier = Modifier.size(ComposerMenuMetrics.ROW_ICON_GLYPH_DP.dp)) },
                            label = "研究模式：${webSearchResearchModeLabel.ifBlank { "自动" }}",
                            subtitle = when {
                                !webSearchEnabled -> "先启用联网检索"
                                webSearchResearchOverridden -> "仅影响下一轮发送"
                                else -> "跟随联网检索默认设置"
                            },
                            actionable = menu.menu.acceptsActions,
                            onClick = {
                                haptics.tick()
                                // The submenu is a layer above the sheet, not a new sheet: opening it
                                // keeps the sheet open, while an unavailable search config closes the
                                // family the same way it did before.
                                if (webSearchEnabled) {
                                    menu.toggleResearch()
                                } else {
                                    menu.runMenuAction {
                                        onSelectWebSearchResearchMode(webSearchResearchMode)
                                    }
                                }
                            }
                        )
                    }
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
    actionable: Boolean = true,
    onClick: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val titleColor = if (darkTheme) MaterialTheme.colorScheme.onSurface else Color(0xFF202124)
    // One disabled colour for the whole row: title, subtitle and the leading glyph/plate all drop
    // to the existing 0.58 title alpha instead of the glyph staying at full strength. No new token:
    // the alpha and both base colours already exist in this row.
    val disabledAlpha = 0.58f
    val disabledColor = titleColor.copy(alpha = disabledAlpha)
    val plateColor = (if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF1F3F4))
        .copy(alpha = if (enabled) 1f else disabledAlpha)
    // R04：colour state follows the row's own business availability only. The closing phase stops
    // the action (`interactive`) but must not grey the visible exit out: it keeps its last colours.
    val interactive = enabled && actionable
    val largeFont = LocalDensity.current.fontScale >= ComposerMenuMetrics.LARGE_FONT_SCALE
    var pressed by remember { mutableStateOf(false) }
    val rowScale = animateFloatAsState(
        targetValue = if (pressed && interactive) McaMotion.PRESS_SCALE else 1f,
        animationSpec = McaMotion.press,
        label = "compactInputActionRowPress"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // min height, not a fixed 48dp: at 2.0x font scale a title plus a two-line subtitle
            // must be allowed to grow instead of being clipped/overlapped.
            .heightIn(min = ComposerMenuMetrics.ROW_MIN_HEIGHT_DP.dp)
            .graphicsLayer {
                scaleX = rowScale.value
                scaleY = rowScale.value
            }
            .pointerInput(interactive) {
                if (!interactive) {
                    pressed = false
                    return@pointerInput
                }
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    waitForUpOrCancellation()
                    pressed = false
                }
            }
            .clip(CircleShape)
            .clickable(enabled = interactive, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        McaIconPlate(
            size = ComposerMenuMetrics.ROW_ICON_PLATE_DP.dp,
            enabled = enabled,
            containerColor = plateColor,
            borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (enabled) 0.45f else 0.25f),
            animateColors = false,
        ) {
            // Surface previously resolved this role from plateColor; retain that exact tint.
            CompositionLocalProvider(
                LocalContentColor provides if (enabled) contentColorFor(plateColor) else disabledColor
            ) {
                icon()
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = label,
                color = if (enabled) titleColor else disabledColor,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 19.sp),
                fontWeight = FontWeight.Medium,
                // R05：1x keeps the frozen single-line baseline. From 1.5x the state words
                // ("角色生图：本对话自动授权／每次询问", "联网检索：<长源名>") must wrap completely:
                // the row grows inside the scrollable sheet instead of ellipsising.
                maxLines = if (largeFont) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.takeIf(String::isNotBlank)?.let {
                Text(
                    text = it,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
                    } else {
                        disabledColor
                    },
                    style = MaterialTheme.typography.labelSmall,
                    // R05：the longest permission/backend explanation must stay fully visible at
                    // 1.5x/2x; the permission words are not moved behind a details entry.
                    maxLines = if (largeFont) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
