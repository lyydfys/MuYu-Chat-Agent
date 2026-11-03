package com.muyuchat.feature.chat
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaIcons

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.designsystem.mcaPressScale
import com.muyuchat.core.designsystem.rememberMcaHaptics
import com.muyuchat.core.designsystem.McaIconSize

// P9/S6：从 ImageWorkspaceScreens.kt 尾部等值移出的生图提示栏与 prompt-token 测量簇。
// 原样逐字迁移，未做行为改动；两个跨区入口 @Composable 按协议提升为 internal。
private const val IMAGE_PROMPT_TOKEN_MEASUREMENT_DEBOUNCE_MILLIS = 250L

private sealed interface ImagePromptTokenUiState {
    data object Unavailable : ImagePromptTokenUiState
    data object Measuring : ImagePromptTokenUiState
    data class Measured(val value: ImagePromptTokenMeasurement) : ImagePromptTokenUiState
}

private data class ImagePromptTokenPublication(
    val modelId: String,
    val prompt: String,
    val measurement: ImagePromptTokenMeasurement?,
)

@Composable
private fun rememberImagePromptTokenUiState(
    modelId: String?,
    prompt: String,
    onMeasureTokens: (suspend (
        modelId: String,
        prompt: String,
    ) -> ImagePromptTokenMeasurement?)?,
): ImagePromptTokenUiState {
    val normalizedModelId = modelId?.trim()?.takeIf(String::isNotEmpty)
    val latestMeasurer by rememberUpdatedState(onMeasureTokens)
    var publication by remember { mutableStateOf<ImagePromptTokenPublication?>(null) }

    LaunchedEffect(normalizedModelId, prompt, onMeasureTokens != null) {
        val selectedModelId = normalizedModelId
        val measurer = latestMeasurer
        if (selectedModelId == null || measurer == null) {
            publication = null
            return@LaunchedEffect
        }
        if (prompt.isBlank()) {
            publication = null
            return@LaunchedEffect
        }

        delay(IMAGE_PROMPT_TOKEN_MEASUREMENT_DEBOUNCE_MILLIS)
        val measured = try {
            measurer(selectedModelId, prompt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        currentCoroutineContext().ensureActive()
        publication = ImagePromptTokenPublication(
            modelId = selectedModelId,
            prompt = prompt,
            measurement = measured?.copy(
                overflowOffset = measured.overflowOffset?.takeIf { offset ->
                    offset <= prompt.length
                },
            ),
        )
    }

    if (normalizedModelId == null || onMeasureTokens == null) {
        return ImagePromptTokenUiState.Unavailable
    }
    // Match Local Dream's empty-field behavior: do not surface BOS/EOS-only
    // counts or a transient spinner before the user has entered a prompt.
    if (prompt.isBlank()) {
        return ImagePromptTokenUiState.Unavailable
    }
    val current = publication
    if (current == null || current.modelId != normalizedModelId || current.prompt != prompt) {
        return ImagePromptTokenUiState.Measuring
    }
    return current.measurement
        ?.let { ImagePromptTokenUiState.Measured(it) }
        ?: ImagePromptTokenUiState.Unavailable
}

private val ImagePromptTokenUiState.measurementOrNull: ImagePromptTokenMeasurement?
    get() = (this as? ImagePromptTokenUiState.Measured)?.value

private class ImagePromptOverflowVisualTransformation(
    private val overflowOffset: Int,
    private val overflowColor: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val start = overflowOffset.coerceIn(0, text.length)
        if (start >= text.length) return TransformedText(text, OffsetMapping.Identity)
        return TransformedText(
            text = buildAnnotatedString {
                append(text)
                addStyle(SpanStyle(color = overflowColor), start, text.length)
            },
            offsetMapping = OffsetMapping.Identity,
        )
    }
}

@Composable
private fun imagePromptVisualTransformation(
    tokenState: ImagePromptTokenUiState,
): VisualTransformation {
    val measurement = tokenState.measurementOrNull
    val overflowColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    return remember(measurement, overflowColor) {
        val overflowOffset = measurement
            ?.takeIf(ImagePromptTokenMeasurement::overflows)
            ?.overflowOffset
        if (overflowOffset == null) {
            VisualTransformation.None
        } else {
            ImagePromptOverflowVisualTransformation(overflowOffset, overflowColor)
        }
    }
}

@Composable
private fun ImagePromptTokenStatus(
    state: ImagePromptTokenUiState,
    showUnavailable: Boolean,
    modifier: Modifier = Modifier,
) {
    val measurement = state.measurementOrNull
    val unavailable = state == ImagePromptTokenUiState.Unavailable && showUnavailable
    val overflowCount = measurement
        ?.takeIf(ImagePromptTokenMeasurement::overflows)
        ?.let { it.count - it.maxTokens }
    val label = when {
        state == ImagePromptTokenUiState.Measuring -> "正在计算 Token…"
        unavailable -> "Token 统计暂不可用 · 生成前仍会检查模型和提示词"
        measurement == null -> null
        overflowCount != null && !measurement.exact ->
            "约 ${measurement.count} / ${measurement.maxTokens} Token · 可能超出"
        overflowCount != null ->
            "超出 $overflowCount 个 Token · ${measurement.count} / ${measurement.maxTokens}"
        !measurement.exact -> "约 ${measurement.count} / ${measurement.maxTokens} Token"
        else -> "${measurement.count} / ${measurement.maxTokens} Token"
    } ?: return
    val accessibilityLabel = when {
        state == ImagePromptTokenUiState.Unavailable ->
            "当前模型暂时无法统计 Token；生成前仍会检查模型完整性和提示词语言"
        overflowCount != null && measurement.exact == false ->
            "提示词估算为 ${measurement.count} 个 Token，模型声明上限 ${measurement.maxTokens}，可能超出"
        overflowCount != null ->
            "提示词超出模型上限 $overflowCount 个 Token，当前 ${measurement.count}，上限 ${measurement.maxTokens}"
        measurement?.exact == false ->
            "提示词估算 Token 数 ${measurement.count}，模型声明上限 ${measurement.maxTokens}"
        measurement != null ->
            "提示词 Token 数 ${measurement.count}，上限 ${measurement.maxTokens}"
        else -> "正在使用模型 Tokenizer 精确计算提示词长度"
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (overflowCount != null) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier.semantics {
            stateDescription = accessibilityLabel
            if (overflowCount != null || unavailable) liveRegion = LiveRegionMode.Polite
        },
    )
}

@Composable
// P9/S6: private→internal（跨文件被 ImageWorkspaceScreens 复合区引用的 @Composable 入口）
internal fun ImageNegativePromptTagField(
    value: String,
    onValueChange: (String) -> Unit,
    modelId: String?,
    onMeasureTokens: (suspend (
        modelId: String,
        prompt: String,
    ) -> ImagePromptTokenMeasurement?)?,
    modifier: Modifier = Modifier
) {
    val editHistory = remember { ImagePromptEditHistory() }
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (value != fieldValue.text) {
            fieldValue = TextFieldValue(value, TextRange(value.length))
            editHistory.replace()
        }
    }

    val tokenState = rememberImagePromptTokenUiState(modelId, fieldValue.text, onMeasureTokens)
    val tokenOverflow = tokenState.measurementOrNull?.overflows == true

    fun commitFieldValue(next: TextFieldValue) {
        fieldValue = next
        if (next.text != value) onValueChange(next.text)
    }

    fun applyFieldValue(next: TextFieldValue) {
        if (next.text != fieldValue.text) editHistory.recordContinuous(fieldValue)
        commitFieldValue(next)
    }

    fun applyDiscreteFieldValue(next: TextFieldValue) {
        if (next.text != fieldValue.text) editHistory.recordDiscrete(fieldValue)
        commitFieldValue(next)
    }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = fieldValue,
            onValueChange = ::applyFieldValue,
            label = { Text("负面提示词") },
            placeholder = { Text("留空使用模型默认") },
            minLines = 2,
            maxLines = 4,
            isError = tokenOverflow,
            visualTransformation = VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
        )
        ImagePromptTokenStatus(
            state = tokenState,
            showUnavailable = fieldValue.text.isNotBlank(),
            modifier = Modifier
                .align(Alignment.End)
                .padding(top = 4.dp, end = 4.dp),
        )
    }
}

@Composable
// P9/S6: private→internal（跨文件被 ImageWorkspaceScreens 复合区引用的 @Composable 入口）
internal fun ImagePromptBar(
    prompt: String,
    onPromptChange: (String) -> Unit,
    modelId: String?,
    onMeasureTokens: (suspend (
        modelId: String,
        prompt: String,
    ) -> ImagePromptTokenMeasurement?)?,
    onOpenPhoto: () -> Unit,
    onSubmit: () -> Unit,
    placeholder: String = "描述图像",
    isGenerating: Boolean = false,
    onStop: () -> Unit = {},
    onTranslatePrompt: (suspend (String) -> String)? = null,
    negativePrompt: String = "",
    onNegativePromptChange: (String) -> Unit = {},
    onTranslateNegativePrompt: (suspend (String) -> String)? = null,
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    val inputShellColor = if (darkTheme) MaterialTheme.colorScheme.surface else McaInputShell
    val inputFieldColor = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else McaInputField
    val inputIconSurfaceColor = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else McaInputIconSurface
    val inputTextColor = if (darkTheme) MaterialTheme.colorScheme.onSurface else McaInputText
    val inputPlaceholderColor = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else McaInputPlaceholder
    val editHistory = remember { ImagePromptEditHistory() }
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(prompt, TextRange(prompt.length)))
    }
    var focused by remember { mutableStateOf(false) }
    // 译文（草稿、状态、取消）由同包的 ImagePromptTranslationPanel.kt 自己持有：这里只保留
    // 一个"翻译进行中"标志，供发送按钮与负面提示词翻译动作判断并发状态。
    var translationBusy by remember { mutableStateOf(false) }
    var negativeTranslationBusy by remember { mutableStateOf(false) }

    LaunchedEffect(prompt) {
        if (prompt != fieldValue.text) {
            fieldValue = TextFieldValue(prompt, TextRange(prompt.length))
            editHistory.replace()
        }
    }

    val tokenState = rememberImagePromptTokenUiState(modelId, fieldValue.text, onMeasureTokens)
    val tokenOverflow = tokenState.measurementOrNull?.overflows == true

    fun commitFieldValue(next: TextFieldValue) {
        fieldValue = next
        if (next.text != prompt) onPromptChange(next.text)
    }

    fun applyFieldValue(next: TextFieldValue) {
        if (next.text != fieldValue.text) editHistory.recordContinuous(fieldValue)
        commitFieldValue(next)
    }

    fun applyDiscreteFieldValue(next: TextFieldValue) {
        if (next.text != fieldValue.text) editHistory.recordDiscrete(fieldValue)
        commitFieldValue(next)
    }

    val haptics = rememberMcaHaptics()
    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            color = inputShellColor,
            shape = RoundedCornerShape(36.dp),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val photoInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .mcaPressScale(photoInteraction)
                        .clip(CircleShape)
                        .background(inputIconSurfaceColor)
                        .clickable(
                            interactionSource = photoInteraction,
                            indication = ripple(bounded = true),
                            onClick = {
                                haptics.tick()
                                onOpenPhoto()
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        McaIcons.Image,
                        contentDescription = "添加图片",
                        modifier = Modifier.size(McaIconSize.M),
                        tint = McaPrimaryBlue
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .animateContentSize(animationSpec = McaMotion.sizeChangePx),
                    color = inputFieldColor,
                    shape = RoundedCornerShape(24.dp),
                    border = if (tokenOverflow) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    } else {
                        BorderStroke(
                            0.5.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (darkTheme) 0.40f else 0.28f)
                        )
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 16.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (fieldValue.text.isBlank()) {
                                    Text(
                                        placeholder,
                                        color = inputPlaceholderColor,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = 15.sp,
                                            lineHeight = 20.sp
                                        )
                                    )
                                }
                                BasicTextField(
                                    value = fieldValue,
                                    onValueChange = ::applyFieldValue,
                                    maxLines = 3,
                                    visualTransformation = VisualTransformation.None,
                                    textStyle = TextStyle(
                                        color = inputTextColor,
                                        fontSize = 15.sp,
                                        lineHeight = 20.sp
                                    ),
                                    cursorBrush = SolidColor(McaPrimaryBlue),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("image.prompt.editor")
                                        .onFocusChanged { focused = it.isFocused }
                                )
                            }
                            ImagePromptTokenStatus(
                                state = tokenState,
                                showUnavailable = fieldValue.text.isNotBlank(),
                                modifier = Modifier
                                    .align(Alignment.End)
                                    .padding(top = 2.dp),
                            )
                        }
                    }
                }
                // 翻译动作不再占据输入框宽度：提示词输入区始终是完整的可编辑宽度，
                // 翻译入口与译文结果区都排在输入框下方（见 ImagePromptTranslationPanel.kt）。
                Spacer(modifier = Modifier.width(10.dp))
                val submitInteraction = remember { MutableInteractionSource() }
                FloatingActionButton(
                    onClick = {
                        when {
                            isGenerating -> {
                                haptics.stop()
                                onStop()
                            }
                            !translationBusy && !negativeTranslationBusy -> {
                                if (prompt.isNotBlank()) haptics.confirm() else haptics.tick()
                                onSubmit()
                            }
                            // 原项目经穷尽枚举分派（ChatScreen.kt:11451-11454）：
                            // 只要不在生成中即走 SUBMIT -> onSubmit()，不因翻译忙标志而静默。
                            // 此处补全兜底，保持与原项目一致的行为（避免标志卡 true 时点击完全无反应）。
                            else -> {
                                if (prompt.isNotBlank()) haptics.confirm() else haptics.tick()
                                onSubmit()
                            }
                        }
                    },
                    containerColor = when {
                        isGenerating -> McaPrimaryBlue
                        prompt.isBlank() -> if (darkTheme) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            Color(0xFFE5ECF8)
                        }
                        else -> McaPrimaryBlue
                    },
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
                    shape = CircleShape,
                    interactionSource = submitInteraction,
                    modifier = Modifier
                        .size(48.dp)
                        .mcaPressScale(submitInteraction)
                        .testTag(if (isGenerating) "image.stop" else "image.generate")
                ) {
                    Crossfade(
                        targetState = isGenerating,
                        animationSpec = McaMotion.sizeChange,
                        label = "imagePromptPrimaryAction"
                    ) { generating ->
                        if (generating) {
                            Icon(McaIcons.Stop, contentDescription = "停止生成", tint = Color.White)
                        } else {
                            Icon(
                                McaIcons.Send,
                                contentDescription = "生成图片",
                                tint = if (prompt.isBlank()) inputPlaceholderColor else Color.White
                            )
                        }
                    }
                }
            }
        }
        // 译文结果区：独立于输入框卡片，标题行给出「译文已就绪 / EN / 撤销 / 使用英文」，
        // 正文最多 3 行且可编辑；原文一直留在上面的输入框里，直到用户主动使用译文。
        if (fieldValue.text.containsHanScriptForTranslationUi() && onTranslatePrompt != null) {
            ImagePromptTranslationBarSection(
                source = fieldValue.text,
                enabled = !isGenerating,
                otherFieldTranslating = negativeTranslationBusy,
                onTranslate = onTranslatePrompt,
                onApply = { translated ->
                    applyDiscreteFieldValue(TextFieldValue(translated, TextRange(translated.length)))
                },
                onBusyChange = { translationBusy = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 2.dp),
            )
        }
        if (negativePrompt.containsHanScriptForTranslationUi() && onTranslateNegativePrompt != null) {
            ImagePromptTranslationDraft(
                source = negativePrompt,
                label = "负面提示词",
                translatingOtherField = translationBusy,
                enabled = !isGenerating,
                onTranslate = onTranslateNegativePrompt,
                onApply = onNegativePromptChange,
                onBusyChange = { negativeTranslationBusy = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                testTagPrefix = "image.negative.translation"
            )
        }
    }
}

@Composable
private fun ImagePromptTranslationDraft(
    source: String,
    label: String,
    translatingOtherField: Boolean,
    enabled: Boolean,
    onTranslate: suspend (String) -> String,
    onApply: (String) -> Unit,
    onBusyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    testTagPrefix: String
) {
    val scope = rememberCoroutineScope()
    val currentSource by rememberUpdatedState(source)
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var translatedSource by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(source) {
        if (translatedSource != source) {
            job?.cancel()
            translatedSource = null
            draft = null
            error = null
        }
    }
    Column(modifier) {
        McaButton(style = McaButtonStyle.Text,
            enabled = enabled && (!translatingOtherField || busy),
            modifier = Modifier.testTag("$testTagPrefix.start"),
            onClick = {
                if (busy) {
                    job?.cancel()
                } else {
                    val requestedSource = source
                    translatedSource = requestedSource
                    error = null
                    job = scope.launch {
                        busy = true
                        onBusyChange(true)
                        try {
                            val translated = onTranslate(requestedSource)
                            if (currentSource == requestedSource) draft = translated
                        } catch (cancelled: CancellationException) {
                            if (currentSource == requestedSource) error = "翻译已取消，原始$label 已保留。"
                            throw cancelled
                        } catch (failure: Exception) {
                            if (currentSource == requestedSource) error = failure.message ?: "翻译失败，原始$label 已保留。"
                        } finally {
                            busy = false
                            onBusyChange(false)
                        }
                    }
                }
            }
        ) { Text(if (busy) "取消$label 翻译" else "翻译$label 为英文") }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("$testTagPrefix.error"))
        }
        if (translatedSource == source && draft != null) {
            Text("$label 英文草稿（可编辑）", style = MaterialTheme.typography.labelSmall)
            OutlinedTextField(
                value = draft.orEmpty(), onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth().testTag("$testTagPrefix.draft"),
                minLines = 2, maxLines = 4
            )
            McaButton(style = McaButtonStyle.Text,
                enabled = enabled && !busy && draft?.isNotBlank() == true,
                modifier = Modifier.testTag("$testTagPrefix.apply"),
                onClick = { draft?.takeIf(String::isNotBlank)?.let(onApply) }
            ) { Text("使用英文$label") }
        }
    }
}
