package com.muyuchat.feature.chat

// The chat chrome, moved verbatim out of ChatScreen.kt.
//
// ChatScreen.kt held four unrelated page domains; the image workspace, the assistant auxiliary
// screens and the application menu have already left. This is the rest of the chat surface that
// is not the ChatScreen composable itself: the reasoning and status chrome, the device-status
// overlay, the background settings page, the model switcher, the history drawer, the input bar,
// the message bubbles and the assistant block, the generated-image content, the web-search trace
// surfaces, and the markdown block renderers with their pagination helpers. Nothing here is new
// code: one contiguous block was cut from ChatScreen.kt and pasted unchanged.
//
// What stayed behind in ChatScreen.kt: the ChatUiState union and the other UI data classes and
// small helpers at the top of that file, and the ChatScreen composable itself, which still holds
// AssistantRoleScreen, AssistantCurrentCard, AssistantListCard and AssistantEditorPage as local
// functions. Those four cannot leave without restructuring ChatScreen, so they are not here.
//
// Fourteen declarations widened from private to internal because ChatScreen.kt still calls them,
// and two widened the other way: CHAT_BACKGROUND_MAX_DECODE_EDGE and CLOUD_REASONING_LOCKED_TIP
// stayed in ChatScreen.kt but are read from here. Kotlin has no package-private scope, so either
// direction needs internal. These are the only edits in the move.
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
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.graphics.Brush
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
            IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回应用菜单") }
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
                        Surface(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .clickable { onSelectAssistant(assistant.id) },
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
                        Surface(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .clickable { onResume(item.id) },
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

internal fun ChatUiState.imageTaskSummary(): String {
    val running = imageJobs.firstOrNull { job ->
        !job.failed && job.imageAssetId == null && (job.statusLabel == "排队" || job.statusLabel == "生成中")
    }
    if (running != null) {
        return "${running.statusLabel} · ${running.prompt.take(18)}"
    }
    val failedJob = imageJobs.firstOrNull { it.failed }
    if (failedJob != null) {
        return "失败，可进入图片页重试"
    }
    val completed = imageJobs.firstOrNull { it.imageAssetId != null || it.statusLabel == "完成" }
    return if (completed != null) "最近完成 · 可查看图片库" else "空闲 · 可进入图片页"
}

@Composable
// P9/S17: private→internal（跨区入口：迁移的消息气泡复合区引用）
internal fun ChatGeneratedImageContent(
    request: ChatGeneratedImageRequest,
    job: ImageGenerationUiJob?,
    visualState: ChatGeneratedImageUiState,
    images: List<ImageAssetUiItem>,
    readOnly: Boolean,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onContinueAssistantImageTurn: () -> Unit,
    onOpenImageModels: () -> Unit,
    onUseImageAsset: (String) -> Unit,
    onDeleteImageAsset: (String) -> Unit
) {
    val context = LocalContext.current
    var selectedImageId by remember(request.id) { mutableStateOf(images.firstOrNull()?.id) }
    var previewImageId by remember(request.id) { mutableStateOf<String?>(null) }
    val selectedImage = images.firstOrNull { it.id == selectedImageId } ?: images.firstOrNull()
    val darkTheme = isSystemInDarkTheme()

    when (visualState) {
        ChatGeneratedImageUiState.AWAITING_APPROVAL -> {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "角色请求生成图片",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        request.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                    chatGeneratedImageModelLabel(request)?.let { modelLabel ->
                        Text(
                            "将使用：$modelLabel",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (request.backendId.equals("CLOUD", ignoreCase = true)) {
                        Text(
                            "确认后会将这段提示词发送给所选云端生图服务。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        request.message.ifBlank { "是否允许使用当前图像模型生成这张图片？" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!readOnly) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onApprove) { Text("同意生成") }
                            TextButton(onClick = onReject) { Text("拒绝") }
                        }
                    }
                }
            }
        }
        ChatGeneratedImageUiState.RUNNING -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    request.prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                chatGeneratedImageModelLabel(request)?.let { modelLabel ->
                    Text(
                        modelLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (readOnly) {
                    Text(request.message, style = MaterialTheme.typography.bodySmall)
                } else if (job != null) {
                    ImageCreatingPlaceholder(
                        statusText = job.statusLabel.ifBlank { "生成中" },
                        statusMessage = job.message.ifBlank { request.message },
                        startedAtElapsedMs = job.startedAtElapsedMs
                            ?: remember(job.id) { SystemClock.elapsedRealtime() },
                        previewUriString = job.previewUriString,
                        previewStep = job.previewStep,
                        previewRevision = job.previewRevision,
                        onCancel = onCancel
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            request.message.ifBlank { "正在准备图片生成。" },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = onCancel) { Text("取消") }
                    }
                }
            }
        }
        ChatGeneratedImageUiState.RESULT -> {
            if (selectedImage != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        request.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    chatGeneratedImageModelLabel(request)?.let { modelLabel ->
                        Text(
                            modelLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (request.origin == ChatGeneratedImageOrigin.ASSISTANT_TOOL) {
                        val continuationMessage = when (request.toolContinuationStatus) {
                            ChatImageToolContinuationStatus.RUNNING -> "正在继续角色回复…"
                            ChatImageToolContinuationStatus.FAILED -> "图片已完成，角色后续回复未完成。可以重试继续。"
                            ChatImageToolContinuationStatus.NOT_STARTED -> "图片已完成，正在准备角色回复。"
                            ChatImageToolContinuationStatus.COMPLETED -> null
                        }
                        continuationMessage?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (images.size > 1) {
                        Text(
                            "共 ${images.size} 张 · 点击图片查看大图",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ChatGeneratedImageGrid(
                            images = images,
                            selectedImageId = selectedImage.id,
                            onSelect = { imageId ->
                                selectedImageId = imageId
                                previewImageId = imageId
                            }
                        )
                    } else {
                        // Never decode an original camera/PNG at full resolution in the
                        // composition path.  A single 12K image can otherwise allocate tens of
                        // megabytes and take the UI process down when a chat image is selected.
                        val bitmap by rememberChatImageBitmap(
                            context = context,
                            uriString = selectedImage.uriString,
                            maxDimensionPx = 1536
                        )
                        val bitmapValue = bitmap
                        val ratio = remember(selectedImage.width, selectedImage.height) {
                            if (selectedImage.width > 0 && selectedImage.height > 0) {
                                (selectedImage.width.toFloat() / selectedImage.height.toFloat())
                                    .coerceIn(0.5f, 2.0f)
                            } else {
                                1f
                            }
                        }
                        Box(
                            modifier = Modifier
                                .widthIn(max = 480.dp)
                                .fillMaxWidth(0.94f)
                                .aspectRatio(ratio)
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { previewImageId = selectedImage.id },
                            contentAlignment = Alignment.Center
                        ) {
                            if (bitmapValue != null) {
                                Image(
                                    bitmap = bitmapValue.asImageBitmap(),
                                    contentDescription = "生成图片 ${selectedImage.width}×${selectedImage.height}，点击查看大图",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text(
                                    "图片文件暂时无法读取",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (images.size > 1) {
                        val selectedIndex = images.indexOfFirst { it.id == selectedImage.id } + 1
                        Text(
                            "当前选择：第 ${selectedIndex.coerceAtLeast(1)} 张",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { previewImageId = selectedImage.id }) { Text("查看") }
                        if (!readOnly) {
                            TextButton(onClick = { onUseImageAsset(selectedImage.id) }) { Text("编辑") }
                            TextButton(onClick = onRetry) { Text("再生成") }
                            TextButton(onClick = { onDeleteImageAsset(selectedImage.id) }) { Text("删除") }
                        }
                        if (!readOnly && request.origin == ChatGeneratedImageOrigin.ASSISTANT_TOOL &&
                            request.toolContinuationStatus in setOf(
                                ChatImageToolContinuationStatus.NOT_STARTED,
                                ChatImageToolContinuationStatus.FAILED
                            )
                        ) {
                            TextButton(onClick = onContinueAssistantImageTurn) { Text("继续角色回复") }
                        }
                    }
                }
            }
        }
        ChatGeneratedImageUiState.FAILED,
        ChatGeneratedImageUiState.CANCELLED,
        ChatGeneratedImageUiState.INTERRUPTED,
        ChatGeneratedImageUiState.MISSING_RESULT -> {
            val headline = when (visualState) {
                ChatGeneratedImageUiState.FAILED -> "图片生成失败"
                ChatGeneratedImageUiState.CANCELLED -> "图片生成已取消"
                ChatGeneratedImageUiState.INTERRUPTED -> "图片生成中断"
                ChatGeneratedImageUiState.MISSING_RESULT -> "图片文件不可用"
                else -> "图片生成未完成"
            }
            Surface(
                color = if (visualState == ChatGeneratedImageUiState.FAILED) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.34f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(headline, style = MaterialTheme.typography.labelLarge)
                    Text(
                        request.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    chatGeneratedImageModelLabel(request)?.let { modelLabel ->
                        Text(
                            modelLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        job?.message?.takeIf(String::isNotBlank)
                            ?: request.message.ifBlank { "图片没有保存到本地图库。" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!readOnly) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onRetry) { Text("重试") }
                        TextButton(onClick = onOpenImageModels) { Text("生图模型") }
                        if (request.origin == ChatGeneratedImageOrigin.ASSISTANT_TOOL &&
                            request.toolContinuationStatus in setOf(
                                ChatImageToolContinuationStatus.NOT_STARTED,
                                ChatImageToolContinuationStatus.FAILED
                            )
                        ) {
                            TextButton(onClick = onContinueAssistantImageTurn) { Text("继续角色回复") }
                        }
                    }
                }
            }
        }
    }

    val previewImage = images.firstOrNull { it.id == previewImageId }
    if (previewImage != null) {
        // The full-screen preview still uses a bounded bitmap; the original file remains
        // available for save/share and is never decoded into the Compose heap.
        val previewBitmap by rememberChatImageBitmap(
            context = context,
            uriString = previewImage.uriString,
            maxDimensionPx = 2048
        )
        val previewBitmapValue = previewBitmap
        Dialog(
            onDismissRequest = { previewImageId = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .heightIn(max = 760.dp),
                shape = RoundedCornerShape(18.dp),
                color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${previewImage.width}×${previewImage.height}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { previewImageId = null }) { Text("关闭") }
                    }
                    if (previewBitmapValue != null) {
                        Image(
                            bitmap = previewBitmapValue.asImageBitmap(),
                            contentDescription = previewImage.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 680.dp)
                        )
                    } else {
                        Text("图片文件暂时无法读取。")
                    }
                }
            }
        }
    }
}

/** Displays every asset from a batch in the chat event itself. */
@Composable
private fun ChatGeneratedImageGrid(
    images: List<ImageAssetUiItem>,
    selectedImageId: String,
    onSelect: (String) -> Unit
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        images.chunked(2).forEachIndexed { rowIndex, rowImages ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowImages.forEachIndexed { columnIndex, item ->
                    val itemIndex = rowIndex * 2 + columnIndex + 1
                    val bitmap by rememberChatImageBitmap(
                        context = context,
                        uriString = item.uriString,
                        maxDimensionPx = 640
                    )
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clickable { onSelect(item.id) },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(14.dp),
                        border = if (item.id == selectedImageId) {
                            BorderStroke(2.dp, McaPrimaryBlue)
                        } else {
                            null
                        }
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap!!.asImageBitmap(),
                                    contentDescription = "生成图片第 ${itemIndex} 张 ${item.name}",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text(
                                    "图片加载中…",
                                    modifier = Modifier.align(Alignment.Center),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(7.dp),
                                color = Color.Black.copy(alpha = 0.58f),
                                shape = CircleShape
                            ) {
                                Text(
                                    "$itemIndex",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
                if (rowImages.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
// P9/S17: private→internal（跨区入口：迁移的消息气泡复合区引用）
internal fun WebSearchSourcesRow(
    sources: List<ChatSourceReference>,
    trace: ChatWebSearchTrace?,
    sessionId: String?,
    messageId: String,
    onOpenBrowserTask: (BrowserTaskLaunch) -> Unit
) {
    var selectedUrl by remember(sources) { mutableStateOf<String?>(null) }
    val selectedSource = sources.firstOrNull { it.url == selectedUrl }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (trace?.hasContent == true) {
            WebSearchTraceCard(
                trace = trace,
                onOpenBrowser = { query ->
                    InternalBrowserUrlPolicy.buildSearchUrl("https://www.google.com/search?q={query}", query)?.let { url ->
                        onOpenBrowserTask(BrowserTaskLaunch(
                            owner = "chat:$sessionId:$messageId", initialUrl = url,
                            kind = BrowserTaskKind.SEARCH, sessionId = sessionId,
                            messageId = messageId, query = query
                        ))
                    }
                }
            )
        }
        if (sources.isEmpty()) return@Column
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = McaPrimaryBlue
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                sources.webSearchSourceSummaryLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sources.take(8)) { source ->
                val trustLabel = source.displayTrustLabel()
                val hostLabel = source.displayHostLabel()
                val selected = source.url == selectedUrl
                Surface(
                    modifier = Modifier
                        .widthIn(min = 188.dp, max = 256.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable {
                            selectedUrl = if (selected) null else source.url
                        },
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
                    shape = RoundedCornerShape(18.dp),
                    border = if (selected) BorderStroke(1.dp, McaPrimaryBlue.copy(alpha = 0.46f)) else null
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                color = source.webSearchTrustColor().copy(alpha = 0.14f),
                                shape = CircleShape
                            ) {
                                Text(
                                    trustLabel,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = source.webSearchTrustColor(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                hostLabel,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            source.title.ifBlank { source.url },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (source.snippet.isNotBlank()) {
                            Text(
                                source.snippet,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        val providerLine = listOf(source.provider, source.displayTrustReason())
                            .filter { it.isNotBlank() }
                            .distinct()
                            .joinToString(" · ")
                        if (providerLine.isNotBlank()) {
                            Text(
                                providerLine,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = selectedSource != null,
            enter = fadeIn(tween(120)) + expandVertically(animationSpec = tween(180)),
            exit = fadeOut(tween(90)) + shrinkVertically(animationSpec = tween(140))
        ) {
            selectedSource?.let { source ->
                WebSearchSourceDetailCard(
                    source = source,
                    onOpen = {
                        onOpenBrowserTask(BrowserTaskLaunch(
                            owner = "chat:$sessionId:$messageId", initialUrl = source.url,
                            sessionId = sessionId, messageId = messageId
                        ))
                    },
                    onClose = { selectedUrl = null }
                )
            }
        }
    }
}

@Composable
private fun WebSearchTraceCard(
    trace: ChatWebSearchTrace,
    onOpenBrowser: (String) -> Unit
) {
    var expanded by rememberSaveable(trace.query, trace.message, trace.elapsedMs, trace.running) { mutableStateOf(false) }
    val statusColor = trace.webSearchTraceColor()
    val runningRotation = if (trace.running) {
        val transition = rememberInfiniteTransition(label = "web-search-running")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "web-search-running-angle"
        )
        angle
    } else {
        0f
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable { expanded = !expanded },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.16f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(34.dp),
                    color = statusColor.copy(alpha = 0.13f),
                    shape = CircleShape
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(runningRotation),
                            tint = statusColor
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (trace.running) trace.stageLabel.ifBlank { "正在检索" } else "检索过程",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        trace.summaryLine(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起检索过程" else "展开检索过程",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (trace.running) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(99.dp)),
                    color = statusColor,
                    trackColor = statusColor.copy(alpha = 0.10f)
                )
            }
            if (trace.message.isNotBlank()) {
                Text(
                    trace.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                    maxLines = if (expanded) 3 else 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(120)) + expandVertically(animationSpec = tween(180)),
                exit = fadeOut(tween(90)) + shrinkVertically(animationSpec = tween(140))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (trace.query.isNotBlank() && !trace.running) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { onOpenBrowser(trace.query) }) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("在内置浏览器中查看搜索")
                            }
                        }
                    }
                    WebSearchTraceSection(
                        title = "检索目标",
                        values = (trace.searchedQueries + trace.directUrls)
                            .distinct()
                            .take(6)
                    )
                    WebSearchTraceSection(
                        title = "触发依据",
                        values = trace.triggerReasons.take(4)
                    )
                    WebSearchTraceSection(
                        title = "证据分组",
                        values = trace.evidenceGroups.take(5)
                    )
                    WebSearchTraceSection(
                        title = "不确定性",
                        values = (trace.conflictWarnings + trace.warnings)
                            .distinct()
                            .take(5),
                        error = true
                    )
                    WebSearchTraceSection(
                        title = "闭环检查",
                        values = trace.closedLoopChecks.take(6)
                    )
                }
            }
        }
    }
}

@Composable
// P9/S17: private→internal（跨区入口：迁移的消息气泡复合区引用）
internal fun AssistantActionRow(
    readOnly: Boolean,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onDeleteLastTurn: () -> Unit,
    onCopy: () -> Unit,
    pinned: Boolean,
    canTogglePinned: Boolean,
    onTogglePinned: () -> Unit
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!readOnly) {
            IconButton(
                onClick = onRegenerate,
                enabled = canRegenerate,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Replay,
                    contentDescription = "重新生成",
                    modifier = Modifier.size(17.dp),
                    tint = tint.copy(alpha = if (canRegenerate) 0.78f else 0.32f)
                )
            }
        }
        IconButton(
            onClick = onCopy,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = "复制消息",
                modifier = Modifier.size(17.dp),
                tint = tint.copy(alpha = 0.78f)
            )
        }
        if (!readOnly) Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "更多消息操作",
                    modifier = Modifier.size(18.dp),
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
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onCopy()
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (pinned) "取消固定上下文" else "固定到上下文") },
                    leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                    enabled = canTogglePinned,
                    onClick = {
                        menuOpen = false
                        onTogglePinned()
                    }
                )
                DropdownMenuItem(
                    text = { Text("重新生成") },
                    leadingIcon = { Icon(Icons.Default.Replay, contentDescription = null) },
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
                            Icons.Default.Delete,
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
                            Icons.Default.Delete,
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
