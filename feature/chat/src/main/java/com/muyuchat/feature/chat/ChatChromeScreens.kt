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
internal fun ChatStatusBar(
    state: ChatUiState,
    onOpenHistory: () -> Unit,
    onNewConversation: () -> Unit,
    onLoadModel: (String) -> Unit,
    onModelBackendChange: (String, String) -> Unit,
    generationParams: GenerationParams,
    onGenerationParamsChange: (GenerationParams) -> Unit,
    onOpenModels: () -> Unit,
    onReasoningModeChange: (ReasoningMode) -> Unit,
    onCloudReasoningModeLocked: () -> Unit,
    onOpenAppMenu: () -> Unit
) {
    val apiActive = state.selectedModelIsCloud || state.apiEnabled || state.restEnabled
    var modelMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val cacheEvidence = cacheEvidenceLabel(state.stats).takeIf { state.hasReadyLocalModelForHeader() }
    val modelSubtitle = state.modelHeaderSubtitle()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = CircleShape,
            shadowElevation = 6.dp
        ) {
            IconButton(onClick = onOpenHistory) {
                Icon(Icons.Default.Menu, contentDescription = "打开历史", modifier = Modifier.size(24.dp))
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(modifier = Modifier.weight(1f)) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = "选择模型"
                        role = SemanticsRole.Button
                    }
                    .clickable {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        modelMenuExpanded = true
                    },
                color = MaterialTheme.colorScheme.surface,
                shape = CircleShape,
                shadowElevation = 5.dp
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    McaLogoMark(size = 24.dp, cornerRadius = 8.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayModelName(state.selectedModelName) ?: "选择模型",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 15.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = modelSubtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 13.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        cacheEvidence?.let { evidence ->
                            Text(
                                text = evidence,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                            )
                        }
                    }
                    Surface(
                        color = if (apiActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    ) {
                        Text(
                            text = "API",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = if (apiActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(15.dp)
                    )
                }
            }
            ModelSwitcherDropdown(
                expanded = modelMenuExpanded,
                models = state.localModels,
                selectedModelIsCloud = state.selectedModelIsCloud,
                isGenerating = state.isGenerating,
                reasoningMode = state.reasoningMode,
                onDismiss = { modelMenuExpanded = false },
                onLoadModel = { id ->
                    modelMenuExpanded = false
                    onLoadModel(id)
                },
                onModelBackendChange = onModelBackendChange,
                generationParams = generationParams,
                onGenerationParamsChange = onGenerationParamsChange,
                onReasoningModeChange = onReasoningModeChange,
                onCloudReasoningModeLocked = onCloudReasoningModeLocked,
                onOpenModels = {
                    modelMenuExpanded = false
                    onOpenModels()
                }
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = CircleShape,
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNewConversation, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "新建聊天", modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = onOpenAppMenu, modifier = Modifier.size(38.dp)) {
                    McaLogoMark(size = 28.dp, cornerRadius = 10.dp)
                }
            }
        }
    }
}

internal data class ChatDeviceStatusItem(
    val key: String,
    val label: String,
    val value: String,
    val detail: String,
    val active: Boolean,
    val known: Boolean = true,
    val tone: ChatDeviceStatusTone =
        if (known) ChatDeviceStatusTone.NEUTRAL else ChatDeviceStatusTone.UNKNOWN
)

/**
 * How loudly one status row is drawn.
 *
 * Deliberately separate from [ChatDeviceStatusItem.active]: `active` records whether an
 * accelerator is executing *with native proof*, while `tone` decides emphasis.  Keeping
 * them apart is what lets unprobed evidence be drawn as unknown instead of being painted
 * like a reading, so the panel cannot dress configuration up as execution.
 */
internal enum class ChatDeviceStatusTone { NEUTRAL, ACTIVE, WARN, CRITICAL, UNKNOWN }

@Composable
private fun chatDeviceStatusToneColor(tone: ChatDeviceStatusTone): Color = when (tone) {
    ChatDeviceStatusTone.ACTIVE -> McaPrimaryBlue
    ChatDeviceStatusTone.WARN -> McaStatusAmber
    ChatDeviceStatusTone.CRITICAL -> MaterialTheme.colorScheme.error
    ChatDeviceStatusTone.UNKNOWN -> MaterialTheme.colorScheme.outline
    ChatDeviceStatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Normalizes a user/native backend label to the accelerator family it names. */
internal fun normalizeChatAcceleratorBackend(raw: String?): String? {
    val value = raw?.trim()?.lowercase().orEmpty()
    if (value.isBlank() || value == "unknown" || value == "unavailable" || value == "none") return null
    return when {
        value == "cpu" || value.contains("cpu") -> "cpu"
        value == "gpu" || value.contains("gpu") || value.contains("opencl") ||
            value.contains("adreno") -> "gpu"
        value == "npu" || value.contains("npu") || value.contains("htp") ||
            value.contains("qnn") || value.contains("qairt") || value.contains("qualcomm") -> "npu"
        else -> value
    }
}

/**
 * Pure projection for one accelerator row.  `supported`, `selectedBackend`,
 * and `actualBackend` are intentionally independent so a configured delegate
 * cannot be presented as an executing delegate without native proof.
 */
internal fun chatAcceleratorStatusItem(
    key: String,
    label: String,
    accelerator: String,
    cloud: Boolean,
    supported: Boolean?,
    selectedBackend: String?,
    actualBackend: String?,
    executionObserved: Boolean,
    unavailableValue: String = "不可用",
    unknownValue: String = "待检测",
    idleValue: String = "未使用",
    extraDetail: String? = null
): ChatDeviceStatusItem {
    val selected = normalizeChatAcceleratorBackend(selectedBackend)
    val actual = normalizeChatAcceleratorBackend(actualBackend)
    val running = !cloud && executionObserved && actual == accelerator
    val selectedHere = !cloud && selected == accelerator
    val value = when {
        cloud -> idleValue
        running -> "运行中"
        supported == false -> unavailableValue
        selectedHere -> "已选择"
        supported == true -> "可用"
        else -> unknownValue
    }
    val supportLabel = when (supported) {
        true -> "支持：可用"
        false -> "支持：不可用"
        null -> "支持：未探测"
    }
    val selectedLabel = when {
        cloud -> "已选：云端"
        selectedHere -> "已选：${selectedBackend?.trim().orEmpty().ifBlank { accelerator }}"
        selected != null -> "已选：${selectedBackend?.trim().orEmpty()}"
        else -> "已选：未指定"
    }
    val actualLabel = when {
        cloud -> "实际：未使用本机"
        running -> "实际：${actualBackend?.trim().orEmpty().ifBlank { accelerator }}（原生证据）"
        actual != null -> "实际：${actualBackend?.trim().orEmpty()}（未确认执行）"
        else -> "实际：未确认"
    }
    // Proof first.  This line is the narrowest thing in the panel, so whichever fragment
    // survives must be the one that says whether execution was actually proven.
    val details = buildList {
        add(actualLabel)
        add(supportLabel)
        add(selectedLabel)
        extraDetail?.takeIf(String::isNotBlank)?.let(::add)
    }.joinToString(" · ")
    val known = cloud || supported != null || selected != null || actual != null || executionObserved
    return ChatDeviceStatusItem(
        key = key,
        label = label,
        value = value,
        detail = details,
        active = running,
        known = known,
        tone = when {
            running -> ChatDeviceStatusTone.ACTIVE
            !known -> ChatDeviceStatusTone.UNKNOWN
            else -> ChatDeviceStatusTone.NEUTRAL
        }
    )
}

internal data class ChatDeviceMemorySample(
    val totalKb: Long,
    val availableKb: Long,
    val lowMemory: Boolean
)

internal data class ChatDeviceTemperatureSample(
    val celsius: Float,
    val source: String
)

/**
 * Absolute ball-size presets.
 *
 * The chosen size is stored as an absolute dp value rather than a fraction of the screen.
 * A screen fraction made the same setting produce 21.6dp on a 360dp phone and 26.9dp on a
 * 448dp one, and let the soft keyboard resize the ball whenever it changed the overlay
 * host's height.  An absolute value means the number the user picks is the size they get,
 * on every device, in every orientation.
 */
internal enum class FloatingDeviceStatusSize(val dp: Float, val label: String) {
    SMALL(28f, "小"),
    MEDIUM(36f, "中"),
    LARGE(44f, "大"),
    EXTRA_LARGE(52f, "特大");

    companion object {
        fun nearest(storedDp: Float): FloatingDeviceStatusSize =
            entries.minByOrNull { kotlin.math.abs(it.dp - storedDp) } ?: LARGE
    }
}

internal const val DEFAULT_FLOATING_DEVICE_STATUS_SIZE_DP = 44f

/** Ceiling on how much of the short side the ball may take on an unusually small host. */
private const val MAX_FLOATING_DEVICE_STATUS_SHORT_SIDE_SHARE = 0.16f
private const val MIN_FLOATING_DEVICE_STATUS_SIZE_DP = 16f

/**
 * Resolves the drawn size from the user's absolute choice.
 *
 * The available area only acts as a safety clamp for very small or split-screen hosts.
 * There is deliberately no fixed lower/upper bound inside the selectable range: the old
 * `coerceIn(18f, 56f)` around a screen fraction made the first 12.5% of the slider dead
 * on a 360dp phone and left the 56dp ceiling unreachable on every phone.
 */
internal fun floatingDeviceStatusBallSize(
    chosenDp: Float,
    availableShortSideDp: Float
): Dp {
    val ceiling = (availableShortSideDp * MAX_FLOATING_DEVICE_STATUS_SHORT_SIDE_SHARE)
        .takeIf { it.isFinite() && it > 0f }
        ?: chosenDp
    return chosenDp.coerceAtMost(ceiling).coerceAtLeast(MIN_FLOATING_DEVICE_STATUS_SIZE_DP).dp
}

/**
 * Content tiers, one per preset.
 *
 * The previous single `ballSize >= 32.dp` branch made the ball's contents reflow all at
 * once part-way through the range.  Each tier now has a designed layout, so growing the
 * ball adds detail progressively instead of jumping.
 */
internal enum class FloatingDeviceStatusBallTier { COMPACT, STANDARD, FULL }

internal fun floatingDeviceStatusBallTier(ballDp: Float): FloatingDeviceStatusBallTier = when {
    ballDp < 40f -> FloatingDeviceStatusBallTier.COMPACT
    ballDp < 48f -> FloatingDeviceStatusBallTier.STANDARD
    else -> FloatingDeviceStatusBallTier.FULL
}

/**
 * Ring weight scales with the ball so the small presets stay legible and the large ones
 * stay elegant.  The old fixed 2.5dp stroke was invisible on a 52dp ball.
 */
internal fun floatingDeviceStatusRingStrokeDp(ballDp: Float): Float =
    (ballDp * 0.085f).coerceIn(2.5f, 4.5f)

/**
 * "4.7 GB" -> "4.7G".  The compact form is what lets the 28dp and 36dp presets render the
 * value inside the ring without clipping; the full form measured ~24dp against ~21.6dp of
 * available width at the old default size.
 */
internal fun compactMemoryLabel(label: String): String =
    label.replace(" ", "").replace("GB", "G").replace("MB", "M")

internal fun chatTemperatureTone(celsius: Float): ChatDeviceStatusTone = when {
    celsius >= 65f -> ChatDeviceStatusTone.CRITICAL
    celsius >= 45f -> ChatDeviceStatusTone.WARN
    else -> ChatDeviceStatusTone.NEUTRAL
}

internal fun chatTemperatureBandLabel(celsius: Float): String = when {
    celsius >= 65f -> "偏热，可能接近降频"
    celsius >= 45f -> "偏热"
    else -> "正常"
}

internal fun memoryUsedFraction(totalKb: Long, availableKb: Long): Float? =
    if (totalKb > 0L) {
        (1f - availableKb.coerceIn(0L, totalKb).toFloat() / totalKb.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }

/**
 * Converts the native runtime evidence already present in [ChatUiState] into
 * concise status rows. Unknown GPU/NPU evidence stays visibly unknown; it is
 * never presented as a device rejection.
 */
internal fun ChatUiState.deviceStatusItems(
    memorySample: ChatDeviceMemorySample? = null,
    temperatureSample: ChatDeviceTemperatureSample? = null
): List<ChatDeviceStatusItem> {
    val backend = stats.backend.trim().lowercase()
    val selectedLocalModel = localModels.firstOrNull { it.id == selectedModelId }
    val selectedBackend = if (selectedModelIsCloud) {
        null
    } else {
        selectedLocalModel?.selectedBackendId
            ?: runCatching { JSONObject(generationParams.advancedJson.ifBlank { "{}" }) }
                .getOrNull()
                ?.let { root ->
                    root.optString("backend")
                        .ifBlank { root.optString("backend_type") }
                        .takeIf(String::isNotBlank)
                }
    }
    val nativeActualBackend = stats.actualBackend
    val gpuExecutionObserved = stats.hasVerifiedGpuExecution ||
        (stats.backendExecutionObserved &&
            normalizeChatAcceleratorBackend(nativeActualBackend) == "gpu")
    val npuExecutionObserved = stats.npuExecutionObserved ||
        (stats.backendExecutionObserved &&
            normalizeChatAcceleratorBackend(nativeActualBackend) == "npu")
    val gpuActualBackend = if (gpuExecutionObserved) {
        nativeActualBackend?.takeIf(String::isNotBlank) ?: "gpu"
    } else {
        nativeActualBackend
    }
    val npuActualBackend = if (npuExecutionObserved) {
        nativeActualBackend?.takeIf(String::isNotBlank) ?: "npu"
    } else {
        nativeActualBackend
    }
    val gpuExtraDetail = when {
        stats.hasVerifiedGpuExecution && stats.gpuOffloadLayersKnown ->
            "${stats.gpuOffloadLayers} 层已验证"
        stats.hasVerifiedGpuExecution -> "已完成原生执行验证"
        stats.backendExecutionEvidence?.isNotBlank() == true -> stats.backendExecutionEvidence
        else -> null
    }
    val gpuItem = chatAcceleratorStatusItem(
        key = "gpu",
        label = "GPU",
        accelerator = "gpu",
        cloud = selectedModelIsCloud,
        supported = stats.gpuOffloadSupported,
        selectedBackend = selectedBackend,
        actualBackend = gpuActualBackend,
        executionObserved = gpuExecutionObserved,
        extraDetail = gpuExtraDetail
    )
    val npuItem = chatAcceleratorStatusItem(
        key = "npu",
        label = "NPU",
        accelerator = "npu",
        cloud = selectedModelIsCloud,
        supported = stats.npuSupported,
        selectedBackend = selectedBackend,
        actualBackend = npuActualBackend,
        executionObserved = npuExecutionObserved,
        // Without an explicit capability probe or execution proof, NPU is
        // idle/unknown.  A configured LiteRT NPU delegate is not execution.
        unknownValue = "未使用",
        extraDetail = stats.backendExecutionEvidence
    )
    val freshMemory = memorySample?.takeIf { it.totalKb > 0L }
    val memoryTotal = freshMemory?.totalKb ?: stats.totalMemKb
    val memoryAvailable = (freshMemory?.availableKb ?: stats.availMemKb)
        .coerceIn(0L, memoryTotal.coerceAtLeast(0L))
    val memoryUsed = (memoryTotal - memoryAvailable).coerceAtLeast(0L)
    val memoryValue = if (memoryTotal > 0L) {
        "${formatMemoryMb(memoryUsed)} / ${formatMemoryMb(memoryTotal)}"
    } else {
        "待检测"
    }
    val memoryDetail = if (memoryTotal > 0L) {
        "可用 ${formatMemoryMb(memoryAvailable)}"
    } else {
        "系统内存数据尚未返回"
    }
    return listOf(
        ChatDeviceStatusItem(
            key = "cpu",
            label = "CPU",
            value = if (!selectedModelIsCloud && stats.loaded && backend.contains("cpu")) "运行中" else "在线",
            // CPU previously carried an unconditional "系统线程与回退执行" blurb, so the one
            // backend that is always available was the only one with no proof statement.
            detail = when {
                selectedModelIsCloud -> "实际：未使用本机 · 支持：可用"
                !selectedModelIsCloud && stats.loaded && backend.contains("cpu") ->
                    "实际：本机 CPU 执行（原生证据） · 支持：可用"
                else -> "支持：可用 · 实际：未确认"
            },
            active = !selectedModelIsCloud && stats.loaded && backend.contains("cpu"),
            tone = if (!selectedModelIsCloud && stats.loaded && backend.contains("cpu")) {
                ChatDeviceStatusTone.ACTIVE
            } else {
                ChatDeviceStatusTone.NEUTRAL
            }
        ),
        gpuItem,
        npuItem,
        ChatDeviceStatusItem(
            key = "memory",
            label = "内存",
            value = memoryValue,
            detail = memoryDetail,
            active = freshMemory?.lowMemory ?: stats.isLowMemory,
            known = memoryTotal > 0L,
            tone = when {
                memoryTotal <= 0L -> ChatDeviceStatusTone.UNKNOWN
                freshMemory?.lowMemory ?: stats.isLowMemory -> ChatDeviceStatusTone.CRITICAL
                else -> ChatDeviceStatusTone.NEUTRAL
            }
        ),
        ChatDeviceStatusItem(
            key = "temperature",
            label = "温度",
            value = temperatureSample?.let { "%.1f°C".format(java.util.Locale.US, it.celsius) } ?: "不可用",
            detail = temperatureSample?.let {
                "传感器：${it.source} · ${chatTemperatureBandLabel(it.celsius)}"
            } ?: "未检测到可读的系统温度传感器",
            active = temperatureSample?.celsius?.let { it >= 45f } ?: false,
            known = temperatureSample != null,
            tone = temperatureSample?.let { chatTemperatureTone(it.celsius) }
                ?: ChatDeviceStatusTone.UNKNOWN
        )
    )
}

private fun formatMemoryMb(kb: Long): String {
    val mb = kb / 1024.0
    return if (mb >= 1024.0) {
        "%.1f GB".format(java.util.Locale.US, mb / 1024.0)
    } else {
        "%.0f MB".format(java.util.Locale.US, mb)
    }
}

internal fun moveFloatingDeviceStatusFraction(current: Float, deltaPx: Float, travelPx: Float): Float =
    if (travelPx > 0f && travelPx.isFinite()) {
        (current.coerceIn(0f, 1f) + deltaPx / travelPx).coerceIn(0f, 1f)
    } else {
        0f
    }

private fun Context.readChatDeviceMemorySample(): ChatDeviceMemorySample? = runCatching {
    val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return@runCatching null
    val info = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(info)
    if (info.totalMem <= 0L) null else ChatDeviceMemorySample(
        totalKb = info.totalMem / 1024L,
        availableKb = info.availMem / 1024L,
        lowMemory = info.lowMemory
    )
}.getOrNull()

/** Reads whichever real thermal sensor is readable; zone numbers are never assumed. */
internal fun readChatDeviceTemperatureSample(
    thermalRoot: File = File("/sys/class/thermal")
): ChatDeviceTemperatureSample? = runCatching {
    if (!thermalRoot.isDirectory) return@runCatching null
    val zones = thermalRoot.listFiles { file ->
        file.isDirectory && file.name.startsWith("thermal_zone")
    }.orEmpty()
    val candidates = zones.mapNotNull { zone ->
        val raw = zone.resolve("temp").readText().trim().toFloatOrNull() ?: return@mapNotNull null
        if (!raw.isFinite()) return@mapNotNull null
        val celsius = if (kotlin.math.abs(raw) > 200f) raw / 1000f else raw
        if (!celsius.isFinite() || celsius !in -40f..150f) return@mapNotNull null
        val type = zone.resolve("type").takeIf { it.isFile }?.readText()?.trim().orEmpty()
        val normalized = type.lowercase()
        val priority = when {
            normalized.contains("soc") -> 0
            normalized.contains("cpu") -> 1
            normalized.contains("gpu") -> 2
            normalized.contains("tsens") -> 3
            normalized.isNotBlank() -> 4
            else -> 5
        }
        Triple(priority, celsius, type.ifBlank { zone.name })
    }
    candidates.minWithOrNull(compareBy<Triple<Int, Float, String>> { it.first }.thenBy { it.third })
        ?.let { ChatDeviceTemperatureSample(it.second, it.third) }
}.getOrNull()

/** Battery temperature is the fallback exposed by Android when vendor thermal zones are hidden. */
internal fun batteryTemperatureSample(rawTenthsCelsius: Int): ChatDeviceTemperatureSample? {
    if (rawTenthsCelsius == Int.MIN_VALUE) return null
    val celsius = rawTenthsCelsius / 10f
    return celsius.takeIf { it.isFinite() && it in -40f..100f }
        ?.let { ChatDeviceTemperatureSample(it, "battery") }
}

internal fun readChatDeviceTemperatureSample(
    context: Context,
    thermalRoot: File = File("/sys/class/thermal")
): ChatDeviceTemperatureSample? = readChatDeviceTemperatureSample(thermalRoot) ?: runCatching {
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    batteryTemperatureSample(
        battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
    )
}.getOrNull()

@Composable
internal fun ChatDeviceStatusOverlay(
    state: ChatUiState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) {
        context.getSharedPreferences("chat_device_status_overlay", Context.MODE_PRIVATE)
    }
    var horizontalFraction by rememberSaveable {
        mutableStateOf(preferences.getFloat("x_fraction", 1f).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f)
    }
    var verticalFraction by rememberSaveable {
        mutableStateOf(preferences.getFloat("y_fraction", 0.70f).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.70f)
    }
    var sizeDp by rememberSaveable {
        mutableStateOf(
            preferences.getFloat("ball_size_dp", DEFAULT_FLOATING_DEVICE_STATUS_SIZE_DP)
                .takeIf { it.isFinite() && it > 0f }
                ?: DEFAULT_FLOATING_DEVICE_STATUS_SIZE_DP
        )
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var memorySample by remember { mutableStateOf<ChatDeviceMemorySample?>(null) }
    var temperatureSample by remember { mutableStateOf<ChatDeviceTemperatureSample?>(null) }
    var lastSampleAtMs by remember { mutableStateOf(0L) }
    var clockMs by remember { mutableStateOf(0L) }
    LaunchedEffect(context) {
        while (true) {
            memorySample = context.readChatDeviceMemorySample()
            temperatureSample = readChatDeviceTemperatureSample(context)
            lastSampleAtMs = System.currentTimeMillis()
            delay(5_000L)
        }
    }
    // Only ticks while the panel is open, and only the panel reads this clock, so the
    // closed ball never re-composes.
    LaunchedEffect(expanded) {
        if (!expanded) return@LaunchedEffect
        clockMs = System.currentTimeMillis()
        while (true) {
            clockMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val items = state.deviceStatusItems(memorySample, temperatureSample)
    val memoryItem = items.first { it.key == "memory" }
    val memoryTotalKb = memorySample?.totalKb?.takeIf { it > 0L } ?: state.stats.totalMemKb
    val memoryAvailableKb = (memorySample?.availableKb ?: state.stats.availMemKb)
        .coerceIn(0L, memoryTotalKb.coerceAtLeast(0L))
    val usedFraction = memoryUsedFraction(memoryTotalKb, memoryAvailableKb)
    val ballMemoryLabel = if (memoryTotalKb > 0L) formatMemoryMb(memoryAvailableKb) else "--"
    val ballCompactMemoryLabel = compactMemoryLabel(ballMemoryLabel)
    val hasActive = items.any { it.key != "memory" && it.active }
    val ringTrackColor = MaterialTheme.colorScheme.surfaceVariant
    // The ring and the bar plot *used* memory, so they stay blue in normal conditions and
    // only take a warning tone when the reading is actually remarkable.
    val memoryAccent = when (memoryItem.tone) {
        ChatDeviceStatusTone.WARN -> McaStatusAmber
        ChatDeviceStatusTone.CRITICAL -> MaterialTheme.colorScheme.error
        else -> McaPrimaryBlue
    }
    val memoryHeadlineColor = when (memoryItem.tone) {
        ChatDeviceStatusTone.WARN, ChatDeviceStatusTone.CRITICAL -> chatDeviceStatusToneColor(memoryItem.tone)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val headerTone = when {
        items.any { it.tone == ChatDeviceStatusTone.CRITICAL } -> ChatDeviceStatusTone.CRITICAL
        items.any { it.tone == ChatDeviceStatusTone.WARN } -> ChatDeviceStatusTone.WARN
        items.any { it.tone == ChatDeviceStatusTone.ACTIVE } -> ChatDeviceStatusTone.ACTIVE
        else -> ChatDeviceStatusTone.NEUTRAL
    }
    val freshnessLabel = if (lastSampleAtMs <= 0L || clockMs <= lastSampleAtMs) {
        "刚刚更新"
    } else {
        "更新于 ${(clockMs - lastSampleAtMs) / 1000L}s 前"
    }
    val statusSummary = items.joinToString("，") { "${it.label}${it.value}" }

    BoxWithConstraints(modifier = modifier) {
        val shortSide = minOf(maxWidth, maxHeight)
        val ballSize = floatingDeviceStatusBallSize(sizeDp, shortSide.value)
        val ballTier = floatingDeviceStatusBallTier(ballSize.value)
        val edge = 12.dp
        if (maxWidth < ballSize + edge * 2 || maxHeight < ballSize + edge * 2) return@BoxWithConstraints
        val density = LocalDensity.current
        val horizontalTravel = (maxWidth - ballSize - edge * 2).coerceAtLeast(0.dp)
        val verticalTravel = (maxHeight - ballSize - edge * 2).coerceAtLeast(0.dp)
        val horizontalTravelPx = with(density) { horizontalTravel.toPx() }
        val verticalTravelPx = with(density) { verticalTravel.toPx() }
        val edgePx = with(density) { edge.toPx() }

        fun savePosition() {
            preferences.edit()
                .putFloat("x_fraction", horizontalFraction)
                .putFloat("y_fraction", verticalFraction)
                .apply()
        }

        fun saveSize() {
            preferences.edit().putFloat("ball_size_dp", sizeDp).apply()
        }

        fun moveBy(x: Float, y: Float): Boolean {
            horizontalFraction = (horizontalFraction + x).coerceIn(0f, 1f)
            verticalFraction = (verticalFraction + y).coerceIn(0f, 1f)
            savePosition()
            return true
        }

        if (expanded) {
            val ballX = edge + horizontalTravel * horizontalFraction
            val ballY = edge + verticalTravel * verticalFraction
            val panelWidth = (maxWidth - edge * 2).coerceAtMost(324.dp)
            val spaceAbove = (ballY - edge - 10.dp).coerceAtLeast(0.dp)
            val spaceBelow = (maxHeight - ballY - ballSize - edge - 10.dp).coerceAtLeast(0.dp)
            val panelBelow = spaceBelow >= spaceAbove
            // Height is content-driven with a cap, instead of the old fixed 236dp that
            // clipped the accelerator rows on every device.
            val panelHeight = maxOf(spaceAbove, spaceBelow).coerceAtMost(560.dp)
            val panelX = (ballX + ballSize - panelWidth)
                .coerceIn(edge, (maxWidth - panelWidth - edge).coerceAtLeast(edge))
            val panelY = if (panelBelow) {
                ballY + ballSize + 10.dp
            } else {
                (ballY - panelHeight - 10.dp).coerceAtLeast(edge)
            }

            Surface(
                modifier = Modifier
                    .offset(x = panelX, y = panelY)
                    .width(panelWidth)
                    .heightIn(max = panelHeight)
                    .testTag("chat-device-status-panel"),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.97f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 13.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(chatDeviceStatusToneColor(headerTone))
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Text(
                                "MCA 设备状态",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                freshnessLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { expanded = false }) {
                            Icon(Icons.Default.Close, contentDescription = "收起设备状态", modifier = Modifier.size(20.dp))
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        modifier = Modifier.testTag("chat-device-status-memory"),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("内存", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (memoryTotalKb > 0L) "可用 $ballMemoryLabel" else "内存待检测",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = memoryHeadlineColor
                            )
                        }
                        usedFraction?.let { fraction ->
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = memoryAccent,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                        // The bar plots *used* memory, so it is captioned in used terms.
                        // Labelling the row "可用" directly above a 68%-filled bar made the
                        // two read as opposites.
                        Text(
                            if (memoryTotalKb > 0L) {
                                "已用 ${formatMemoryMb((memoryTotalKb - memoryAvailableKb).coerceAtLeast(0L))} / 共 ${formatMemoryMb(memoryTotalKb)}"
                            } else {
                                "系统内存数据尚未返回"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    items.filter { it.key != "memory" }.forEach { item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("chat-device-status-${item.key}"),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(6.dp).clip(CircleShape)
                                        .background(chatDeviceStatusToneColor(item.tone))
                                )
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    item.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    item.value,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = chatDeviceStatusToneColor(item.tone),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            // Full-width rows give the evidence line roughly 296dp instead of
                            // the ~135dp a two-column cell allowed, so 支持/已选/实际 is no
                            // longer guaranteed to collapse into an ellipsis.
                            Text(
                                item.detail,
                                modifier = Modifier.padding(start = 13.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // The size control used to be a continuous screen-fraction slider sitting
                    // in the middle of the readout, with a dead first eighth on 360dp phones.
                    // It is now a labelled absolute-dp preset row at the bottom of the panel.
                    Column(
                        modifier = Modifier.testTag("chat-device-status-size"),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("悬浮球大小", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            Text("${ballSize.value.toInt()} dp", style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FloatingDeviceStatusSize.entries.forEach { preset ->
                                val selected = preset == FloatingDeviceStatusSize.nearest(sizeDp)
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(40.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .clickable {
                                            sizeDp = preset.dp
                                            saveSize()
                                        },
                                    shape = RoundedCornerShape(11.dp),
                                    color = if (selected) McaPrimaryBlue else MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) McaPrimaryBlue else MaterialTheme.colorScheme.outlineVariant
                                    )
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            "${preset.label} ${preset.dp.toInt()}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                // Read drag position during placement so pointer updates do not recompose the overlay.
                .offset {
                    IntOffset(
                        (edgePx + horizontalTravelPx * horizontalFraction).roundToInt(),
                        (edgePx + verticalTravelPx * verticalFraction).roundToInt()
                    )
                }
                .size(ballSize)
                // The open panel already shows these numbers, so the ball steps back
                // instead of competing with it, while staying usable as the drag anchor.
                .alpha(if (expanded) 0.62f else 1f)
                .testTag("chat-device-status-ball")
                .semantics(mergeDescendants = true) {
                    contentDescription = "MCA 设备状态，可用内存 $ballMemoryLabel。点击${if (expanded) "收起" else "展开"}；可拖动位置。$statusSummary"
                    role = SemanticsRole.Button
                    stateDescription = if (expanded) "已展开" else "已收起"
                    customActions = listOf(
                        CustomAccessibilityAction("向左移动悬浮球") { moveBy(-0.15f, 0f) },
                        CustomAccessibilityAction("向右移动悬浮球") { moveBy(0.15f, 0f) },
                        CustomAccessibilityAction("向上移动悬浮球") { moveBy(0f, -0.15f) },
                        CustomAccessibilityAction("向下移动悬浮球") { moveBy(0f, 0.15f) }
                    )
                }
                .pointerInput(horizontalTravelPx, verticalTravelPx, ballSize) {
                    // Keep tap and drag in one pointer scope.  A sibling clickable modifier can
                    // consume the initial down event before detectDragGestures reaches touch
                    // slop, which made the ball feel immovable on some Compose/MIUI builds.
                    // This state machine only toggles on a real tap and consumes movement after
                    // a small slop, so every pixel of a drag updates the persisted position.
                    val touchSlopPx = with(density) { 8.dp.toPx() }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var dragged = false
                        var distance = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (change.changedToUp()) {
                                change.consume()
                                if (!dragged) expanded = !expanded
                                savePosition()
                                break
                            }
                            if (!change.pressed) {
                                savePosition()
                                break
                            }
                            val delta = change.positionChange()
                            if (delta != Offset.Zero) {
                                distance += delta.getDistance()
                                if (!dragged && distance >= touchSlopPx) {
                                    dragged = true
                                    // A drag should never leave the details panel open over the
                                    // moving ball.
                                    expanded = false
                                }
                                if (dragged) {
                                    change.consume()
                                    horizontalFraction = moveFloatingDeviceStatusFraction(
                                        horizontalFraction, delta.x, horizontalTravelPx
                                    )
                                    verticalFraction = moveFloatingDeviceStatusFraction(
                                        verticalFraction, delta.y, verticalTravelPx
                                    )
                                }
                            }
                        }
                    }
                },
            color = MaterialTheme.colorScheme.surface,
            shape = CircleShape,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Box(contentAlignment = Alignment.Center) {
                val ringStroke = floatingDeviceStatusRingStrokeDp(ballSize.value).dp
                usedFraction?.let { fraction ->
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val inset = ringStroke.toPx() / 2f + 1.dp.toPx()
                        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                        drawArc(
                            color = ringTrackColor,
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(width = ringStroke.toPx(), cap = StrokeCap.Round)
                        )
                        drawArc(
                            color = memoryAccent,
                            startAngle = -90f,
                            sweepAngle = fraction * 360f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(width = ringStroke.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
                // Designed per preset instead of one hard `ballSize >= 32.dp` branch, so
                // resizing adds detail progressively rather than reflowing mid-range.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when (ballTier) {
                        FloatingDeviceStatusBallTier.FULL -> {
                            McaLogoMark(size = (ballSize * 0.30f).coerceIn(14.dp, 22.dp), cornerRadius = 6.dp)
                            Text(if (memoryTotalKb > 0L) "可用" else "内存",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(ballMemoryLabel,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 11.sp),
                                fontWeight = FontWeight.SemiBold, maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                        FloatingDeviceStatusBallTier.STANDARD -> {
                            McaLogoMark(size = (ballSize * 0.28f).coerceIn(12.dp, 18.dp), cornerRadius = 5.dp)
                            Text(ballCompactMemoryLabel,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 10.sp),
                                fontWeight = FontWeight.SemiBold, maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                        FloatingDeviceStatusBallTier.COMPACT -> {
                            // The compact label is what keeps this readable: the full
                            // "4.7 GB" measured wider than the whole ball at 28dp.
                            Text(ballCompactMemoryLabel,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 9.sp),
                                fontWeight = FontWeight.SemiBold, maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                if (hasActive) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 7.dp, end = 7.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(McaPrimaryBlue)
                    )
                }
            }
        }
    }
}

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
// P9/S13: private→internal（跨区入口：迁移的输入区复合区引用）
internal fun AttachmentPreview(
    input: String,
    visionCapabilityLabel: String,
    visionCapabilityDetail: String,
    visionCapabilityReady: Boolean,
    onRemove: () -> Unit
) {
    val name = remember(input) { extractAttachmentName(input) } ?: return
    val isImageAttachment = remember(input) { hasImageAttachmentMarker(input) }
    val imageAttachmentCount = remember(input) { chatImageAttachmentMarkerCount(input) }
    val statusColor = if (visionCapabilityReady) McaPrimaryBlue else MaterialTheme.colorScheme.error
    val containerColor = if (isImageAttachment) {
        if (visionCapabilityReady) McaInputIconSurface.copy(alpha = 0.92f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.44f)
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
    }
    val contentColor = if (isImageAttachment) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        color = containerColor,
        shape = RoundedCornerShape(16.dp),
        border = if (isImageAttachment) BorderStroke(1.dp, statusColor.copy(alpha = 0.22f)) else null
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .heightIn(min = 30.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isImageAttachment) Icons.Default.Image else Icons.Default.Folder,
                        contentDescription = null,
                        tint = if (isImageAttachment) statusColor else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isImageAttachment) "识图" else "文件",
                        color = if (isImageAttachment) statusColor else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isImageAttachment && imageAttachmentCount > 1) {
                            "$name · 共 $imageAttachmentCount 张图片"
                        } else {
                            name
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = contentColor,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                if (isImageAttachment) {
                    Text(
                        text = "$visionCapabilityLabel · $visionCapabilityDetail",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                    extractAttachmentMeta(input)?.let { meta ->
                        Text(
                            text = meta,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "移除附件",
                    modifier = Modifier.size(16.dp),
                    tint = contentColor.copy(alpha = 0.74f)
                )
            }
        }
    }
}

@Composable
internal fun MessageBubble(
    message: ChatMessage,
    readOnly: Boolean,
    browserSessionId: String?,
    characterCardHtml: Boolean,
    onOpenBrowserTask: (BrowserTaskLaunch) -> Unit,
    generatedImageRequest: ChatGeneratedImageRequest?,
    generatedImageJob: ImageGenerationUiJob?,
    generatedImageVisualState: ChatGeneratedImageUiState?,
    generatedImages: List<ImageAssetUiItem>,
    onRetryChatImage: () -> Unit,
    onCancelChatImage: () -> Unit,
    onApproveChatImage: () -> Unit,
    onRejectChatImage: () -> Unit,
    onContinueAssistantImageTurn: () -> Unit,
    onOpenImageModels: () -> Unit,
    onUseImageAsset: (String) -> Unit,
    onDeleteImageAsset: (String) -> Unit,
    showAssistantActions: Boolean,
    canRegenerate: Boolean,
    isGenerating: Boolean,
    generationPhase: GenerationPhase?,
    generationTokenProgress: TokenProgress?,
    generationPersistProgress: PersistProgress?,
    generationStats: RuntimeStats?,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onTogglePinned: () -> Unit,
    canTogglePinned: Boolean,
    onDeleteLastTurn: () -> Unit
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isUser = message.role == Role.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (isUser) {
            UserMessageBubble(
                message = message,
                readOnly = readOnly,
                onTogglePinned = onTogglePinned,
                canTogglePinned = canTogglePinned,
                onCopy = {
                    val copyText = displayInputWithoutAttachment(message.content)
                    if (copyText.isNotBlank()) {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("MCA", copyText))
                            )
                            Toast.makeText(context, "已复制提示词", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        } else {
            AssistantMessageBlock(
                modifier = Modifier.fillMaxWidth(),
                message = message,
                readOnly = readOnly,
                browserSessionId = browserSessionId,
                characterCardHtml = characterCardHtml,
                onOpenBrowserTask = onOpenBrowserTask,
                generatedImageRequest = generatedImageRequest,
                generatedImageJob = generatedImageJob,
                generatedImageVisualState = generatedImageVisualState,
                generatedImages = generatedImages,
                onRetryChatImage = onRetryChatImage,
                onCancelChatImage = onCancelChatImage,
                onApproveChatImage = onApproveChatImage,
                onRejectChatImage = onRejectChatImage,
                onContinueAssistantImageTurn = onContinueAssistantImageTurn,
                onOpenImageModels = onOpenImageModels,
                onUseImageAsset = onUseImageAsset,
                onDeleteImageAsset = onDeleteImageAsset,
                showActions = showAssistantActions,
                canRegenerate = canRegenerate,
                isGenerating = isGenerating,
                generationPhase = generationPhase,
                generationTokenProgress = generationTokenProgress,
                generationPersistProgress = generationPersistProgress,
                generationStats = generationStats,
                onRegenerate = onRegenerate,
                onDelete = onDelete,
                onTogglePinned = onTogglePinned,
                canTogglePinned = canTogglePinned,
                onDeleteLastTurn = onDeleteLastTurn,
                onCopy = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("MCA", message.content))
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun UserMessageBubble(
    message: ChatMessage,
    readOnly: Boolean,
    onTogglePinned: () -> Unit,
    canTogglePinned: Boolean,
    onCopy: () -> Unit
) {
    val context = LocalContext.current
    // The bubble colour was hardcoded to a light value, so on a dark theme the user's own
    // messages rendered as solid white blocks. It now follows the same
    // "if (darkTheme) colorScheme.<role> else <light value>" shape the rest of this file uses --
    // primaryContainer at 0.42 is the brand-tinted surface already used for blue surfaces at
    // lines 2287, 2389 and 2490, and surfaceVariant is the neutral one used at 2391 and 4080.
    // Only the two colour expressions change: size, radius, padding, spacing and text are untouched.
    val darkTheme = isSystemInDarkTheme()
    val bubbleColor = if (darkTheme) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
    } else {
        Color(0xFFEEF4FF)
    }
    // The colour above is an alpha-modified derived value, so Surface cannot resolve a matching
    // content colour through contentColorFor and falls back to the ambient one, which left the text
    // dim against the blue. On dark the pair is explicit: onPrimaryContainer is the role that
    // belongs with primaryContainer. On light LocalContentColor.current is exactly what Surface
    // would have fallen back to, so the light rendering is unchanged down to the colour value.
    val bubbleContentColor = if (darkTheme) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        LocalContentColor.current
    }
    val attachmentPlaceholderColor = if (darkTheme) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        Color.White.copy(alpha = 0.72f)
    }
    val copyableContent = displayInputWithoutAttachment(message.content)
    val visibleImageAttachments by produceState(
        initialValue = message.imageAttachments.takeIf { it.size <= 1 }.orEmpty(),
        key1 = message.imageAttachments
    ) {
        // Legacy histories may keep distinct content:// URIs for the same bytes.
        // Resolve their identities off the UI thread before showing thumbnails.
        value = withContext(Dispatchers.IO) {
            deduplicateChatImageAttachments(message.imageAttachments) { uri ->
                chatImageAttachmentContentIdentity(context, uri)
            }
        }
    }
    Column(
        modifier = Modifier.widthIn(max = 280.dp),
        horizontalAlignment = Alignment.End
    ) {
        Surface(
            color = bubbleColor,
            contentColor = bubbleContentColor,
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = 20.dp,
                bottomEnd = 6.dp
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 17.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (visibleImageAttachments.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        visibleImageAttachments.forEach { attachment ->
                            val bitmap by rememberChatImageBitmap(
                                context,
                                attachment.uriString,
                                maxDimensionPx = 384
                            )
                            val displayBitmap = bitmap
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = attachmentPlaceholderColor,
                                modifier = Modifier.size(104.dp)
                            ) {
                                if (displayBitmap != null) {
                                    Image(
                                        bitmap = displayBitmap.asImageBitmap(),
                                        contentDescription = attachment.name.ifBlank { "上传图片" },
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Image,
                                            contentDescription = null,
                                            tint = McaPrimaryBlue.copy(alpha = 0.72f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (message.content.isNotBlank()) {
                    PagedPlainMessageText(
                        content = message.content,
                        // Third hardcoded light value in this function. The bubble background became
                        // theme-aware first, which left this near-black text on a blue surface:
                        // readable enough to look deliberate, dim enough to be wrong. An explicit
                        // colour here also wins over the Surface contentColor above, which is why
                        // that pair on its own did not fix it. Light keeps 0xFF202124 exactly.
                        color = if (darkTheme) MaterialTheme.colorScheme.onPrimaryContainer else Color(0xFF202124),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 24.sp)
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!readOnly) {
                IconButton(onClick = onTogglePinned, enabled = canTogglePinned, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.PushPin,
                        contentDescription = if (message.pinned) "取消固定上下文" else "固定到上下文",
                        modifier = Modifier.size(17.dp),
                        tint = if (message.pinned) McaPrimaryBlue else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (copyableContent.isNotBlank()) {
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "复制提示词",
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                )
            }
            }
        }
    }
}

@Composable
// P9/S14: private→internal（跨区入口：迁移的模型切换复合区引用）
internal fun GenerationParamsQuickDialog(
    params: GenerationParams,
    onDismiss: () -> Unit,
    onApply: (GenerationParams) -> Unit
) {
    var temperatureText by remember(params) { mutableStateOf(params.temperature.cleanParamText()) }
    var topPText by remember(params) { mutableStateOf(params.topP.cleanParamText()) }
    var topKText by remember(params) { mutableStateOf(params.topK.toString()) }
    var minPText by remember(params) { mutableStateOf(params.minP.cleanParamText()) }
    var repeatPenaltyText by remember(params) { mutableStateOf(params.repeatPenalty.cleanParamText()) }
    var nPredictText by remember(params) { mutableStateOf(params.nPredict.toString()) }
    var reasoningMode by remember(params) { mutableStateOf(params.reasoningMode) }
    var errorText by remember { mutableStateOf<String?>(null) }

    fun parseFloat(label: String, value: String, min: Float, max: Float): Float? {
        val parsed = value.trim().toFloatOrNull()
        if (parsed == null || !parsed.isFinite() || parsed !in min..max) {
            errorText = "$label 请输入 ${min.cleanParamText()} 到 ${max.cleanParamText()} 之间的数字"
            return null
        }
        return parsed
    }

    fun parseInt(label: String, value: String, min: Int, max: Int): Int? {
        val parsed = value.trim().toIntOrNull()
        if (parsed == null || parsed !in min..max) {
            errorText = "$label 请输入 $min 到 $max 之间的整数"
            return null
        }
        return parsed
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("当前模型参数") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "只影响当前模型，切换模型后会自动恢复该模型的设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(value = temperatureText, onValueChange = { temperatureText = it; errorText = null }, label = { Text("temperature") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = topPText, onValueChange = { topPText = it; errorText = null }, label = { Text("top_p") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = topKText, onValueChange = { topKText = it; errorText = null }, label = { Text("top_k") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = minPText, onValueChange = { minPText = it; errorText = null }, label = { Text("min_p") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = repeatPenaltyText, onValueChange = { repeatPenaltyText = it; errorText = null }, label = { Text("重复惩罚") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = nPredictText, onValueChange = { nPredictText = it; errorText = null }, label = { Text("最大输出 tokens") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("思考模式", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReasoningMode.entries.forEach { mode ->
                        FilterChip(
                            selected = reasoningMode == mode,
                            onClick = { reasoningMode = mode },
                            label = { Text(mode.shortLabel()) }
                        )
                    }
                }
                errorText?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val temperature = parseFloat("temperature", temperatureText, 0f, 2f) ?: return@TextButton
                val topP = parseFloat("top_p", topPText, 0f, 1f) ?: return@TextButton
                val topK = parseInt("top_k", topKText, 0, 1000) ?: return@TextButton
                val minP = parseFloat("min_p", minPText, 0f, 1f) ?: return@TextButton
                val repeatPenalty = parseFloat("重复惩罚", repeatPenaltyText, 0.5f, 2f) ?: return@TextButton
                val nPredict = parseInt("最大输出 tokens", nPredictText, 16, 65_536) ?: return@TextButton
                onApply(params.copy(temperature = temperature, topP = topP, topK = topK, minP = minP, repeatPenalty = repeatPenalty, nPredict = nPredict, reasoningMode = reasoningMode, hideReasoning = reasoningMode == ReasoningMode.OFF))
            }) { Text("应用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private data class CharacterHtmlProjection(
    val messageId: String,
    val source: String,
    val preview: String?,
    val blocks: List<CharacterHtmlBlock>?
)

@Composable
private fun AssistantMessageBlock(
    modifier: Modifier = Modifier,
    message: ChatMessage,
    readOnly: Boolean,
    browserSessionId: String?,
    characterCardHtml: Boolean,
    onOpenBrowserTask: (BrowserTaskLaunch) -> Unit,
    generatedImageRequest: ChatGeneratedImageRequest?,
    generatedImageJob: ImageGenerationUiJob?,
    generatedImageVisualState: ChatGeneratedImageUiState?,
    generatedImages: List<ImageAssetUiItem>,
    onRetryChatImage: () -> Unit,
    onCancelChatImage: () -> Unit,
    onApproveChatImage: () -> Unit,
    onRejectChatImage: () -> Unit,
    onContinueAssistantImageTurn: () -> Unit,
    onOpenImageModels: () -> Unit,
    onUseImageAsset: (String) -> Unit,
    onDeleteImageAsset: (String) -> Unit,
    showActions: Boolean,
    canRegenerate: Boolean,
    isGenerating: Boolean,
    generationPhase: GenerationPhase?,
    generationTokenProgress: TokenProgress?,
    generationPersistProgress: PersistProgress?,
    generationStats: RuntimeStats?,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onTogglePinned: () -> Unit,
    canTogglePinned: Boolean,
    onDeleteLastTurn: () -> Unit,
    onCopy: () -> Unit
) {
    val contextTrace = remember(message.id, message.contextAssemblyTraceJson) {
        parseContextAssemblyTrace(message.contextAssemblyTraceJson)
    }
    var showContextTrace by remember(message.id) { mutableStateOf(false) }
    val htmlCandidate = remember(message.content, characterCardHtml) {
        characterHtmlMayNeedPreview(message.content) &&
            (characterCardHtml || characterHtmlHasStatus(message.content))
    }
    val htmlProjection by produceState<CharacterHtmlProjection?>(
        initialValue = null,
        key1 = message.id,
        key2 = message.content,
        key3 = htmlCandidate
    ) {
        val blocks = if (htmlCandidate) {
            withContext(Dispatchers.Default) { characterHtmlBlocks(message.content) }
        } else null
        val preview = blocks?.joinToString("\n\n") { block ->
            when (block) {
                is CharacterHtmlBlock.Text -> block.content
                is CharacterHtmlBlock.Details -> listOf(block.title, block.content)
                    .filter(String::isNotBlank)
                    .joinToString("\n")
            }
        }
        value = CharacterHtmlProjection(message.id, message.content, preview, blocks)
    }
    val currentProjection = htmlProjection?.takeIf { it.messageId == message.id }
    val visibleHtmlPreview = currentProjection?.preview?.takeIf { htmlCandidate }
    val visibleHtmlBlocks = currentProjection?.blocks?.takeIf { htmlCandidate }
    val previewPending = htmlCandidate && currentProjection?.source != message.content && visibleHtmlPreview == null
    var showHtmlSource by remember(message.id) { mutableStateOf(false) }
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(0.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = null,
        modifier = modifier
            .fillMaxWidth()
            .padding(end = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (message.reasoningContent.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    AnimatedMcaAssistantMark(
                        isGenerating = isGenerating,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    ReasoningPanel(
                        content = message.reasoningContent,
                        durationMs = message.reasoningDurationMs,
                        awaitingFinalAnswer = isGenerating && message.content.isBlank(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (message.content.isBlank() && message.reasoningContent.isBlank()) {
                if (isGenerating) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        AnimatedMcaAssistantMark(
                            isGenerating = true,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        PendingReasoningPanel(
                            ownerCreatedAt = message.createdAt,
                            phase = generationPhase,
                            tokenProgress = generationTokenProgress,
                            persistProgress = generationPersistProgress,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(18.dp))
                }
            } else if (message.content.isNotBlank() && generatedImageRequest == null) {
                if (visibleHtmlPreview != null) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { showHtmlSource = !showHtmlSource }) {
                                Text(if (showHtmlSource) "返回预览" else "查看原文")
                            }
                        }
                        SelectionContainer {
                            if (showHtmlSource) {
                                PagedAssistantRichText(message.content)
                            } else if (visibleHtmlBlocks != null) {
                                CharacterHtmlBlocksContent(visibleHtmlBlocks)
                            } else {
                                PagedAssistantRichText(visibleHtmlPreview)
                            }
                        }
                    }
                } else if (previewPending) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    SelectionContainer {
                        PagedAssistantRichText(message.content)
                    }
                }
            }
            if (generatedImageRequest != null && generatedImageVisualState != null) {
                ChatGeneratedImageContent(
                    request = generatedImageRequest,
                    job = generatedImageJob,
                    visualState = generatedImageVisualState,
                    images = generatedImages,
                    readOnly = readOnly,
                    onRetry = onRetryChatImage,
                    onCancel = onCancelChatImage,
                    onApprove = onApproveChatImage,
                    onReject = onRejectChatImage,
                    onContinueAssistantImageTurn = onContinueAssistantImageTurn,
                    onOpenImageModels = onOpenImageModels,
                    onUseImageAsset = onUseImageAsset,
                    onDeleteImageAsset = onDeleteImageAsset
                )
            }
            if (isGenerating) {
                GenerationPerformanceLine(generationStats)
            } else {
                message.generationMetricsSummary()?.let { summary ->
                    // 与生成中态保持一致：labelSmall(11sp) 在手机上偏小，提到 labelMedium(12sp)。
                    // 颜色沿用 onSurfaceVariant（Material3 对次级文本的正确 token），不做额外 alpha 折减。
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (message.sourceReferences.isNotEmpty() || message.webSearchTrace?.hasContent == true) {
                WebSearchSourcesRow(
                    sources = message.sourceReferences,
                    trace = message.webSearchTrace,
                    sessionId = browserSessionId,
                    messageId = message.id,
                    onOpenBrowserTask = onOpenBrowserTask
                )
            }
            if (contextTrace != null) {
                TextButton(
                    onClick = { showContextTrace = true },
                    modifier = Modifier.testTag("context-trace-${message.id}"),
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("上下文来源", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (showActions) {
                AssistantActionRow(
                    readOnly = readOnly,
                    canRegenerate = canRegenerate,
                    onRegenerate = onRegenerate,
                    onDelete = onDelete,
                    onDeleteLastTurn = onDeleteLastTurn,
                    onCopy = onCopy,
                    pinned = message.pinned,
                    canTogglePinned = canTogglePinned,
                    onTogglePinned = onTogglePinned
                )
            } else if (!readOnly) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onTogglePinned, enabled = canTogglePinned, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = if (message.pinned) "取消固定上下文" else "固定到上下文",
                            modifier = Modifier.size(16.dp),
                            tint = if (message.pinned) McaPrimaryBlue else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
    if (showContextTrace && contextTrace != null) {
        ContextAssemblyTraceDialog(contextTrace, onDismiss = { showContextTrace = false })
    }
}

@Composable
private fun ChatGeneratedImageContent(
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
private fun WebSearchSourcesRow(
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
private fun AssistantActionRow(
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
