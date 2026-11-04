package com.muyuchat.feature.chat

// 图片提示词翻译的交互层（从 ImagePromptBarScreens.kt 的提示词栏拆出）。
// 关键约束：译文只在本控件的独立结果区里预览，绝不写进输入框；只有用户点「使用英文」才回调
// onApply，把译文交给 ImagePromptBar 显式替换正文。翻译中的进度、取消、失败与重试也都在这里，
// 提示词栏只保留一个 translationBusy 标志给发送按钮用。

import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonSize
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaHairline
import com.muyuchat.core.designsystem.McaIconSize
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaMotion

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 译文草稿在结果卡片里最多显示的行数；超出部分由文本框自身滚动，不再撑高提示词输入框。 */
private const val IMAGE_PROMPT_TRANSLATION_VISIBLE_LINES = 3

/** 目标语言标签；当前离线翻译固定产出英文。 */
private const val IMAGE_PROMPT_TRANSLATION_TARGET_TAG = "EN"

private const val IMAGE_PROMPT_TRANSLATION_FALLBACK_FAILURE = "Hy-MT2 翻译失败，请重试；原提示词已保留。"

/** 提示词翻译的四个状态；只有 [Ready] 的草稿可以被显式写回输入框。 */
private sealed interface ImagePromptTranslationStage {
    data object Idle : ImagePromptTranslationStage
    data object Translating : ImagePromptTranslationStage
    data class Ready(val source: String, val draft: String) : ImagePromptTranslationStage
    data class Failed(val source: String, val reason: String) : ImagePromptTranslationStage
}

/** 结果区与状态行的强调色：浅色沿用提示词栏的蓝，深色跟随主题主色。 */
@Composable
private fun imagePromptTranslationAccent(): Color =
    if (isSystemInDarkTheme()) MaterialTheme.colorScheme.primary else McaPrimaryBlue

/**
 * 图片提示词翻译的结果区与状态行。
 *
 * 译文只保存在本控件里：原文始终留在输入框，[onApply] 是唯一的写回入口。源文本一变（用户继续
 * 编辑），旧译文、在跑的请求和错误一起作废，避免过期结果落到新提示词上。
 */
@Composable
internal fun ImagePromptTranslationBarSection(
    source: String,
    enabled: Boolean,
    otherFieldTranslating: Boolean,
    onTranslate: suspend (String) -> String,
    onApply: (String) -> Unit,
    onBusyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = rememberImagePromptTranslationController(onBusyChange)

    // 第一次组合与每次原文变化都作废旧译文和在跑的请求，避免过期结果落到新提示词上。
    LaunchedEffect(source) { controller.reset() }

    Column(modifier = modifier.animateContentSize(animationSpec = McaMotion.sizeChangePx)) {
        when (val stage = controller.stage.visibleFor(source)) {
            ImagePromptTranslationStage.Idle -> ImagePromptTranslationTriggerRow(
                enabled = enabled && !otherFieldTranslating,
                onTranslate = { controller.translate(source, onTranslate) },
            )

            ImagePromptTranslationStage.Translating -> ImagePromptTranslationProgressRow(
                cancelEnabled = enabled,
                onCancel = controller::reset,
            )

            is ImagePromptTranslationStage.Ready -> ImagePromptTranslationResultCard(
                draft = stage.draft,
                onDraftChange = controller::editDraft,
                onApply = { controller.applyReady(onApply) },
                onDismiss = controller::reset,
            )

            is ImagePromptTranslationStage.Failed -> ImagePromptTranslationFailureRow(
                reason = stage.reason,
                onRetry = { controller.translate(source, onTranslate) },
                onDismiss = controller::reset,
            )
        }
    }
}

/**
 * 提示词翻译的页面局部状态持有者：本功能唯一改写译文状态的地方。
 *
 * epoch 让过期请求的回调写不进新一次翻译；[reset] 同时作废译文和在跑请求。译文只保存在这里，
 * 不会因为翻译完成而改动输入框。
 */
private class ImagePromptTranslationController(
    private val scope: CoroutineScope,
    private val onBusyChange: (Boolean) -> Unit,
) {
    var stage by mutableStateOf<ImagePromptTranslationStage>(ImagePromptTranslationStage.Idle)
        private set

    private var worker: Job? = null
    private var workerEpoch = 0

    fun translate(source: String, translate: suspend (String) -> String) {
        worker?.cancel()
        val epoch = workerEpoch + 1
        workerEpoch = epoch
        stage = ImagePromptTranslationStage.Translating
        onBusyChange(true)
        worker = scope.launch {
            try {
                val translated = translate(source)
                if (workerEpoch == epoch) {
                    stage = ImagePromptTranslationStage.Ready(source, translated)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (workerEpoch == epoch) {
                    stage = ImagePromptTranslationStage.Failed(
                        source,
                        failure.message?.takeIf(String::isNotBlank)
                            ?: IMAGE_PROMPT_TRANSLATION_FALLBACK_FAILURE,
                    )
                }
            } finally {
                if (workerEpoch == epoch) {
                    worker = null
                    onBusyChange(false)
                }
            }
        }
    }

    /** 取消翻译或丢弃结果；原文始终不动。 */
    fun reset() {
        workerEpoch += 1
        stage = ImagePromptTranslationStage.Idle
        worker?.cancel()
        worker = null
        onBusyChange(false)
    }

    fun editDraft(next: String) {
        val ready = stage as? ImagePromptTranslationStage.Ready ?: return
        stage = ready.copy(draft = next)
    }

    /** 只有用户点「使用英文」时才把草稿交给 [onApply] 写回输入框。 */
    fun applyReady(onApply: (String) -> Unit) {
        val ready = stage as? ImagePromptTranslationStage.Ready ?: return
        if (ready.draft.isBlank()) return
        onApply(ready.draft)
        stage = ImagePromptTranslationStage.Idle
    }
}

@Composable
private fun rememberImagePromptTranslationController(
    onBusyChange: (Boolean) -> Unit,
): ImagePromptTranslationController {
    val scope = rememberCoroutineScope()
    val latestBusyChange = rememberUpdatedState(onBusyChange)
    return remember(scope) {
        ImagePromptTranslationController(scope) { busy -> latestBusyChange.value(busy) }
    }
}

/** 原文已经变化、但 reset 还没跑完的那一帧，也不展示属于旧原文的译文或错误。 */
private fun ImagePromptTranslationStage.visibleFor(source: String): ImagePromptTranslationStage =
    when (this) {
        is ImagePromptTranslationStage.Ready ->
            if (imagePromptTranslationVisibleFor(this.source, source)) this
            else ImagePromptTranslationStage.Idle
        is ImagePromptTranslationStage.Failed ->
            if (imagePromptTranslationVisibleFor(this.source, source)) this
            else ImagePromptTranslationStage.Idle
        ImagePromptTranslationStage.Idle -> ImagePromptTranslationStage.Idle
        ImagePromptTranslationStage.Translating -> ImagePromptTranslationStage.Translating
    }

/** 空闲态：图标 + 短标签的翻译动作行，旁边一句"不替换原文"的说明。 */
@Composable
private fun ImagePromptTranslationTriggerRow(
    enabled: Boolean,
    onTranslate: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        McaButton(
            onClick = onTranslate,
            style = McaButtonStyle.Text,
            size = McaButtonSize.CardAction,
            enabled = enabled,
            contentPadding = PaddingValues(start = 8.dp, end = 12.dp),
            leadingIcon = {
                Icon(
                    McaIcons.Translate,
                    contentDescription = null,
                    modifier = Modifier.size(McaIconSize.Optical.Inline),
                )
            },
            modifier = Modifier
                .height(32.dp)
                .testTag("image.prompt.translate")
                .semantics { contentDescription = "翻译为英文" },
        ) {
            Text("译成英文", style = MaterialTheme.typography.labelMedium)
        }
        Text(
            "译文单独预览，不替换原文",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 翻译中：内联进度 + 取消，取消仍保留原来的可访问名称。 */
@Composable
private fun ImagePromptTranslationProgressRow(
    cancelEnabled: Boolean,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val accent = imagePromptTranslationAccent()
        CircularProgressIndicator(
            modifier = Modifier.size(McaIconSize.Xs),
            strokeWidth = 2.dp,
            color = accent,
        )
        Text(
            "正在翻译…",
            modifier = Modifier
                .weight(1f)
                .semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        McaButton(
            onClick = onCancel,
            style = McaButtonStyle.Text,
            size = McaButtonSize.CardAction,
            enabled = cancelEnabled,
            contentPadding = PaddingValues(start = 8.dp, end = 10.dp),
            leadingIcon = {
                Icon(
                    McaIcons.Close,
                    contentDescription = null,
                    modifier = Modifier.size(McaIconSize.Xs),
                )
            },
            modifier = Modifier
                .height(32.dp)
                .testTag("image.prompt.translate")
                .semantics { contentDescription = "取消翻译" },
        ) {
            Text("取消", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 已就绪：紧凑卡片，标题行的图标/主色边框是克制的成功信号，正文只是草稿，不算输入框。 */
@Composable
private fun ImagePromptTranslationResultCard(
    draft: String,
    onDraftChange: (String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = imagePromptTranslationAccent()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("image.prompt.translation.preview"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(McaHairline.Width, accent.copy(alpha = 0.32f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ImagePromptTranslationResultHeader(
                canApply = draft.isNotBlank(),
                accent = accent,
                onApply = onApply,
                onDismiss = onDismiss,
            )
            ImagePromptTranslationDraftField(
                draft = draft,
                onDraftChange = onDraftChange,
                accent = accent,
            )
        }
    }
}

/** 结果卡片标题行：状态图标 + 标题 + 目标语言标签，右侧是「撤销」与「使用英文」。 */
@Composable
private fun ImagePromptTranslationResultHeader(
    canApply: Boolean,
    accent: Color,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                McaIcons.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(McaIconSize.Xs),
                tint = accent,
            )
            Text(
                "译文已就绪",
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ImagePromptTranslationTargetTag(accent = accent)
        }
        McaButton(
            onClick = onDismiss,
            style = McaButtonStyle.Text,
            size = McaButtonSize.CardAction,
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier
                .height(32.dp)
                .testTag("image.prompt.translation.dismiss"),
        ) {
            Text("撤销", style = MaterialTheme.typography.labelMedium)
        }
        McaButton(
            onClick = onApply,
            style = McaButtonStyle.Tonal,
            size = McaButtonSize.CardAction,
            enabled = canApply,
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier
                .height(32.dp)
                .testTag("image.prompt.translation.apply"),
        ) {
            Text("使用英文", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 目标语言标签（EN）。 */
@Composable
private fun ImagePromptTranslationTargetTag(accent: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = 0.12f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            IMAGE_PROMPT_TRANSLATION_TARGET_TAG,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.4.sp),
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
    }
}

/** 可编辑的译文草稿：最多 3 行，长译文在框内滚动，绝不撑开提示词输入框。 */
@Composable
private fun ImagePromptTranslationDraftField(
    draft: String,
    onDraftChange: (String) -> Unit,
    accent: Color,
) {
    BasicTextField(
        value = draft,
        onValueChange = onDraftChange,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        ),
        cursorBrush = SolidColor(accent),
        maxLines = IMAGE_PROMPT_TRANSLATION_VISIBLE_LINES,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("image.prompt.translation.draft"),
    )
}

/** 失败态：原因 + 重试 + 关闭，原文始终留在输入框里。 */
@Composable
private fun ImagePromptTranslationFailureRow(
    reason: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            McaHairline.Width,
            MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                McaIcons.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(McaIconSize.Xs),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                reason,
                modifier = Modifier
                    .weight(1f)
                    .testTag("image.prompt.translation.error"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            McaButton(
                onClick = onRetry,
                style = McaButtonStyle.Text,
                size = McaButtonSize.CardAction,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier
                    .height(32.dp)
                    .testTag("image.prompt.translation.retry"),
            ) {
                Text("重试", style = MaterialTheme.typography.labelMedium)
            }
            McaButton(
                onClick = onDismiss,
                style = McaButtonStyle.Text,
                size = McaButtonSize.CardAction,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier
                    .height(32.dp)
                    .testTag("image.prompt.translation.error.dismiss"),
            ) {
                Text("关闭", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
