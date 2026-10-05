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
