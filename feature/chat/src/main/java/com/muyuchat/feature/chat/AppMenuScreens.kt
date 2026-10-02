package com.muyuchat.feature.chat

// The application menu pages, moved verbatim out of ChatScreen.kt.
//
// ChatScreen.kt held four unrelated page domains. The image workspace and the assistant auxiliary
// screens have already left; this is the third -- the full-screen app menu, its cards and rows,
// the MCA logo mark, and the animated assistant mark that the chat header also uses. Nothing here
// is new code: one contiguous block was cut from ChatScreen.kt and pasted unchanged.
//
// Five declarations widened from private to internal because ChatScreen.kt still calls them, and
// two more widened the other way: displayModelName and ChatUiState.imageTaskSummary stayed in
// ChatScreen.kt but are called from here. Kotlin has no package-private scope, so either
// direction needs internal.
//
// This file is also where appMenuPageEnter lives, the slide-and-fade transition the menu pages
// use. It sat inside the block, so it travelled with it.
//
// The import list is the one from ChatScreen.kt, copied whole rather than trimmed: unused imports
// are warnings in Kotlin, and copying them keeps the move mechanical instead of making it a
// judgement call about which imports each declaration still needs.

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.BatteryManager
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.visionDeduplicationKey
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageOrigin
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import com.muyuchat.core.engine.ChatImageToolContinuationStatus
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.ChatWebSearchTrace
import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.PersistProgress
import com.muyuchat.core.engine.TokenProgress
import com.muyuchat.core.engine.PromptContextUsage
import com.muyuchat.core.engine.PromptMessageRetention
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import com.muyuchat.core.designsystem.SystemBackMotionHandler
import com.muyuchat.core.engine.isAsciiTriggerWordCharacter

@Composable
internal fun PredictiveAppMenuPage(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable (Modifier, () -> Unit) -> Unit
) {
    SmoothRightToLeftPage(
        visible = visible,
        onDismiss = onDismiss,
        content = content
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SmoothRightToLeftPage(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable (Modifier, () -> Unit) -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = appMenuPageEnter(),
        exit = ExitTransition.None
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val scope = rememberCoroutineScope()
            val offsetX = remember { Animatable(0f) }
            // Let the IME own the first Back press. Some Android builds dispatch that event
            // through the page callback while a text field is acquiring focus.
            val imeVisible = WindowInsets.isImeVisible

            LaunchedEffect(visible) {
                if (visible) offsetX.snapTo(0f)
            }

            suspend fun closeAnimated() {
                offsetX.animateTo(
                    targetValue = widthPx,
                    animationSpec = tween(durationMillis = 180)
                )
                onDismiss()
            }

            fun closeWithDrawerMotion() {
                scope.launch {
                    closeAnimated()
                }
            }

            SystemBackMotionHandler(
                enabled = visible && !imeVisible,
                onProgress = { progress ->
                    scope.launch {
                        offsetX.snapTo(widthPx * progress.coerceIn(0f, 1f))
                    }
                },
                onCancel = {
                    scope.launch {
                        offsetX.animateTo(
                            targetValue = 0f,
                            animationSpec = tween(durationMillis = 160)
                        )
                    }
                },
                onBack = {
                    scope.launch {
                        if (offsetX.value < widthPx * 0.08f) {
                            offsetX.snapTo(widthPx * 0.08f)
                        }
                        closeAnimated()
                    }
                }
            )

            val pageModifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }

            content(pageModifier, ::closeWithDrawerMotion)
        }
    }
}

private fun appMenuPageEnter() = slideInHorizontally(
    animationSpec = tween(durationMillis = 240),
    initialOffsetX = { it }
) + fadeIn(animationSpec = tween(durationMillis = 140))

@Composable
internal fun McaAppMenuPage(
    onOpenQuickStart: () -> Unit,
    onOpenBackgroundSettings: () -> Unit,
    state: ChatUiState,
    onClose: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenApi: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestContextCompression: () -> Unit,
    onUndoContextSummary: () -> Unit,
    onSearchContextSummarySources: (String) -> List<ContextSummaryEvidenceUiItem>,
    onSetContextCompressionThreshold: (Int) -> Unit,
    onSetContextSummaryModel: (String) -> Unit,
    onOpenImages: () -> Unit,
    onOpenAssistants: () -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmClear by remember { mutableStateOf(false) }
    var chooseCompressionThreshold by remember { mutableStateOf(false) }
    var chooseSummaryModel by remember { mutableStateOf(false) }
    var showSummaryHistory by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp, vertical = 16.dp)
            .navigationBarsPadding()
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Default.Close, contentDescription = "关闭")
            }
            Spacer(modifier = Modifier.weight(1f))
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Spacer(modifier = Modifier.height(22.dp))
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                McaLogoMark(size = 66.dp, cornerRadius = 18.dp)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "MuYu Chat Agent",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    fontWeight = FontWeight.Black
                )
            }
            Spacer(modifier = Modifier.height(28.dp))

            Text(
                "MCA",
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppMenuCard {
                AppMenuRow(
                    icon = { Icon(Icons.Default.Psychology, null) },
                    title = "助手与角色",
                    subtitle = state.assistants.firstOrNull { it.selected }?.let { "${it.name} · 角色卡与能力" } ?: "当前助手、角色卡、提示词与能力",
                    onClick = onOpenAssistants
                )
                AppMenuRow(icon = { Icon(Icons.Default.Folder, null) }, title = "模型管理", subtitle = "本地高速/兼容引擎与魔塔下载", onClick = onOpenModels)
                AppMenuRow(icon = { McaLogoMark(size = 22.dp, cornerRadius = 7.dp) }, title = "智能调参", subtitle = "测速、推荐与高级参数", onClick = onOpenAgent)
                AppMenuRow(icon = { Icon(Icons.Default.NetworkWifi, null) }, title = "本地 API", subtitle = "接口地址、Key、API 使用文档", onClick = onOpenApi)
                 AppMenuRow(icon = { Icon(Icons.Default.Settings, null) }, title = "系统设置", subtitle = "运行、日志、诊断与高级设置", onClick = onOpenSettings)
                AppMenuRow(icon = { Icon(Icons.Default.Image, null) }, title = "聊天背景", subtitle = "设置全局、助手或当前会话背景图片", onClick = onOpenBackgroundSettings)
                AppMenuRow(icon = { Icon(Icons.Default.Info, null) }, title = "快速开始", subtitle = "聊天、生图与常见问题", onClick = onOpenQuickStart)
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                "本机状态",
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppMenuCard {
                AppMenuRow(
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (state.selectedModelName == null) Color(0xFF9AA0A6) else Color(0xFF34A853))
                        )
                    },
                    title = displayModelName(state.selectedModelName) ?: "未加载模型",
                    subtitle = "${"%.1f".format(state.stats.decodeTps)} token/s · ${state.stats.backend.uppercase()} 后端",
                    onClick = {}
                )
                AppMenuRow(
                    icon = { Icon(Icons.Default.NetworkWifi, null) },
                    title = "本地 API",
                    subtitle = if (state.apiEnabled || state.restEnabled) "已启用" else "未启用",
                    onClick = onOpenApi
                )
                AppMenuRow(
                    icon = { Icon(Icons.Default.Image, null) },
                    title = "图像任务",
                    subtitle = state.imageTaskSummary(),
                    onClick = onOpenImages
                )
                AppMenuRow(
                    icon = { Icon(Icons.Default.Psychology, null) },
                    title = state.assistants.firstOrNull { it.selected }?.name ?: "默认助手",
                    subtitle = state.assistants.firstOrNull { it.selected }?.let {
                        "记忆${if (it.memoryEnabled) "开" else "关"} · 检索${if (it.webSearchEnabled) "开" else "关"}"
                    } ?: "助手能力状态",
                    onClick = onOpenAssistants
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                "对话",
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppMenuCard {
                AppMenuRow(
                    icon = { Icon(Icons.Default.Replay, null) },
                    title = if (state.contextCompressionPending) "已安排压缩" else "下次发送时压缩",
                    subtitle = if (state.contextCompressionPending) {
                        "下一次发送时执行，保留角色设定和最近消息"
                    } else {
                        "压缩较早对话，减少长上下文占用"
                    },
                    enabled = !state.conversationReadOnly,
                    onClick = onRequestContextCompression
                )
                AppMenuRow(
                    icon = { Icon(Icons.Default.Settings, null) },
                    title = "自动压缩阈值 · ${state.contextCompressionThresholdPercent}%",
                    subtitle = "达到模型上下文窗口的该比例后自动压缩",
                    onClick = { chooseCompressionThreshold = true }
                )
                AppMenuRow(
                    icon = { Icon(Icons.Default.Folder, null) },
                    title = "摘要历史",
                    subtitle = if (state.contextSummaryHistory.isEmpty()) "当前对话尚无摘要" else
                        "${state.contextSummaryHistory.size} 个版本 · ${if (state.canUndoContextSummary) "有生效摘要" else "已撤销"}",
                    onClick = { showSummaryHistory = true }
                )
                AppMenuRow(
                    icon = { Icon(Icons.Default.Settings, null) },
                    title = "摘要模型",
                    subtitle = state.contextSummaryModels.firstOrNull {
                        it.id == state.contextSummaryModelSelection
                    }?.label ?: "所选模型当前不可用",
                    onClick = { chooseSummaryModel = true }
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                "历史",
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppMenuCard {
                AppMenuRow(
                    icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    title = "清空全部",
                    subtitle = "删除本机保存的全部聊天记录",
                    contentColor = MaterialTheme.colorScheme.error,
                    onClick = { confirmClear = true }
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部历史") },
            text = { Text("确定清空所有聊天记录吗？当前模型和模型文件不会被删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearHistory()
                        confirmClear = false
                    }
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (chooseCompressionThreshold) {
        AlertDialog(
            onDismissRequest = { chooseCompressionThreshold = false },
            title = { Text("自动压缩阈值") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "阈值越低越早整理上下文；系统消息、角色设定和最近消息会保留。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    listOf(70, 80, 90).forEach { percent ->
                        TextButton(
                            onClick = {
                                onSetContextCompressionThreshold(percent)
                                chooseCompressionThreshold = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "$percent%${if (percent == state.contextCompressionThresholdPercent) "（当前）" else ""}",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { chooseCompressionThreshold = false }) {
                    Text("取消")
                }
            }
        )
    }
    if (chooseSummaryModel) {
        AlertDialog(
            onDismissRequest = { chooseSummaryModel = false },
            title = { Text("摘要模型") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    state.contextSummaryModels.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !state.isGenerating) {
                                onSetContextSummaryModel(option.id)
                                chooseSummaryModel = false
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = option.id == state.contextSummaryModelSelection,
                                onClick = null,
                                enabled = !state.isGenerating
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(option.label, modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { chooseSummaryModel = false }) { Text("关闭") }
            }
        )
    }
    if (showSummaryHistory) {
        var summarySourceQuery by rememberSaveable(state.activeConversationId) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSummaryHistory = false },
            title = { Text("摘要历史") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = summarySourceQuery,
                        onValueChange = { summarySourceQuery = it },
                        label = { Text("查找摘要事实或来源") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (summarySourceQuery.isNotBlank()) {
                        val sourceHits = onSearchContextSummarySources(summarySourceQuery)
                        if (sourceHits.isEmpty()) {
                            Text("当前有效摘要没有匹配的来源。", style = MaterialTheme.typography.bodySmall)
                        }
                        sourceHits.forEach { hit ->
                            SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(hit.text, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${hit.kind} · 来源 ${hit.sourceMessageId} · 位置 ${hit.sourceOffset}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                    if (state.contextSummaryHistory.isEmpty()) {
                        Text("当前对话尚无摘要。", style = MaterialTheme.typography.bodyMedium)
                    }
                    state.contextSummaryHistory.sortedByDescending(ContextSummaryUiItem::version)
                        .forEach { summary ->
                            val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(summary.createdAt))
                            Text(
                                "版本 ${summary.version} · ${if (summary.active) "生效中" else "已撤销"} · $date",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "来源：${when (summary.source) {
                                    "DETERMINISTIC" -> "规则整理"
                                    "LOCAL_MODEL" -> "本地模型"
                                    "CLOUD_MODEL" -> "云端模型"
                                    else -> summary.source
                                }} · " +
                                    "原始消息 ${summary.sourceMessageIds.size} 条",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            summary.modelIdentity?.let { Text("模型：$it", style = MaterialTheme.typography.bodySmall) }
                            summary.diagnostic?.let { Text("诊断：$it", style = MaterialTheme.typography.bodySmall) }
                            if (summary.coverageLimited) {
                                Text(
                                    "覆盖有限：保留 ${summary.evidenceCount} 条来源摘录，" +
                                        "未纳入 ${summary.omittedExcerptCount} 条片段；" +
                                        "另有 ${summary.omittedCharacterCount} 字符超出扫描上限。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            SelectionContainer {
                                Text(summary.text, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (summary.sourceMessageIds.isNotEmpty()) {
                                SelectionContainer {
                                    Text(
                                        "来源消息 ID：${summary.sourceMessageIds.joinToString()}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                }
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            onUndoContextSummary()
                            showSummaryHistory = false
                        },
                        enabled = state.canUndoContextSummary && !state.isGenerating && !state.conversationReadOnly
                    ) { Text("撤销") }
                    TextButton(
                        onClick = {
                            onRequestContextCompression()
                            showSummaryHistory = false
                        },
                        enabled = state.activeConversationId != null && !state.isGenerating && !state.conversationReadOnly
                    ) { Text("下次发送时重建") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showSummaryHistory = false }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun AppMenuCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(content = content)
    }
}

@Composable
private fun AppMenuRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    contentColor: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val rowContentColor = contentColor ?: MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            icon()
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = rowContentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 18.sp),
                fontWeight = FontWeight.Medium
            )
            subtitle.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 15.sp)
                )
            }
        }
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun McaLogoMark(
    size: Dp,
    cornerRadius: Dp = 10.dp,
    modifier: Modifier = Modifier
) {
    val markBackground = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF8F9FA)
    Canvas(modifier = modifier.size(size)) {
        val side = this.size.minDimension
        val scale = side / 108f
        fun s(value: Float) = value * scale

        drawRoundRect(
            color = markBackground,
            topLeft = Offset.Zero,
            size = Size(side, side),
            cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
        )

        val bubble = Path().apply {
            moveTo(s(54f), s(20f))
            cubicTo(s(35.22f), s(20f), s(20f), s(33.43f), s(20f), s(50f))
            cubicTo(s(20f), s(59.39f), s(24.87f), s(67.76f), s(32.22f), s(73.19f))
            lineTo(s(28f), s(88f))
            lineTo(s(43.55f), s(83.33f))
            cubicTo(s(46.86f), s(84.45f), s(50.36f), s(85f), s(54f), s(85f))
            cubicTo(s(72.78f), s(85f), s(88f), s(71.57f), s(88f), s(55f))
            cubicTo(s(88f), s(38.43f), s(72.78f), s(20f), s(54f), s(20f))
            close()
        }
        drawPath(bubble, Color(0xFF1A73E8))

        val star = Path().apply {
            moveTo(s(54f), s(34f))
            lineTo(s(57f), s(47f))
            lineTo(s(70f), s(50f))
            lineTo(s(57f), s(53f))
            lineTo(s(54f), s(66f))
            lineTo(s(51f), s(53f))
            lineTo(s(38f), s(50f))
            lineTo(s(51f), s(47f))
            close()
        }
        drawPath(star, Color.White)
        drawCircle(Color.White.copy(alpha = 0.82f), radius = s(3.5f), center = Offset(s(36f), s(38f)))
        drawCircle(Color.White.copy(alpha = 0.82f), radius = s(3.5f), center = Offset(s(72f), s(62f)))
        drawCircle(Color.White.copy(alpha = 0.50f), radius = s(2.5f), center = Offset(s(42f), s(68f)))
        drawCircle(Color.White.copy(alpha = 0.50f), radius = s(2f), center = Offset(s(66f), s(36f)))
    }
}

@Composable
internal fun AnimatedMcaAssistantMark(
    isGenerating: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "mca-mark")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "mca-ring"
    )
    Box(
        modifier = modifier.size(38.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
            val ringInset = 1.5.dp.toPx()
            val ringSize = Size(this.size.width - ringInset * 2f, this.size.height - ringInset * 2f)
            if (isGenerating) {
                drawArc(
                    color = Color(0xFF1A73E8).copy(alpha = 0.16f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = stroke,
                    topLeft = Offset(ringInset, ringInset),
                    size = ringSize
                )
                drawArc(
                    color = Color(0xFF1A73E8).copy(alpha = 0.88f),
                    startAngle = angle,
                    sweepAngle = 132f,
                    useCenter = false,
                    style = stroke,
                    topLeft = Offset(ringInset, ringInset),
                    size = ringSize
                )
                drawArc(
                    color = Color(0xFF34A853).copy(alpha = 0.74f),
                    startAngle = angle + 180f,
                    sweepAngle = 86f,
                    useCenter = false,
                    style = stroke,
                    topLeft = Offset(ringInset, ringInset),
                    size = ringSize
                )
            } else {
                drawArc(
                    color = Color(0xFF1A73E8).copy(alpha = 0.18f),
                    startAngle = 215f,
                    sweepAngle = 94f,
                    useCenter = false,
                    style = stroke,
                    topLeft = Offset(ringInset, ringInset),
                    size = ringSize
                )
                drawArc(
                    color = Color(0xFF34A853).copy(alpha = 0.16f),
                    startAngle = 34f,
                    sweepAngle = 70f,
                    useCenter = false,
                    style = stroke,
                    topLeft = Offset(ringInset, ringInset),
                    size = ringSize
                )
            }
        }
        McaLogoMark(size = 22.dp, cornerRadius = 8.dp)
    }
}