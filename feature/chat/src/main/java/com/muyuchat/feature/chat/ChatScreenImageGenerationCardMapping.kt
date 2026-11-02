package com.muyuchat.feature.chat

import android.app.ActivityManager
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll



























import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role as SemanticsRole
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
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.muyuchat.core.designsystem.SystemBackMotionHandler
import com.muyuchat.core.engine.ChatGeneratedImageOrigin
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.ChatImageToolContinuationStatus
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.ChatWebSearchTrace
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.PersistProgress
import com.muyuchat.core.engine.PromptContextUsage
import com.muyuchat.core.engine.PromptMessageRetention
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.TokenProgress
import com.muyuchat.core.engine.isAsciiTriggerWordCharacter
import com.muyuchat.core.engine.visionDeduplicationKey
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chat image-generation card mapping: the job/asset state a card renders from.
 *
 * Cut verbatim out of ChatScreen.kt. Every declaration here is already `internal` and
 * stateless -- it maps generator state to a card kind, a label and a batch of assets, and
 * touches no Android API.
 */

internal enum class ImageAssistantCardKind {
    FAILURE,
    RESULT,
    TERMINAL,
    CREATING
}

internal fun imageAssistantCardKind(
    job: ImageGenerationUiJob?,
    image: ImageAssetUiItem?
): ImageAssistantCardKind = when {
    job?.failed == true -> ImageAssistantCardKind.FAILURE
    image != null -> ImageAssistantCardKind.RESULT
    job?.terminal == true -> ImageAssistantCardKind.TERMINAL
    else -> ImageAssistantCardKind.CREATING
}

internal enum class ChatGeneratedImageUiState {
    RUNNING,
    AWAITING_APPROVAL,
    RESULT,
    FAILED,
    CANCELLED,
    INTERRUPTED,
    MISSING_RESULT
}

internal fun chatGeneratedImageJob(
    request: ChatGeneratedImageRequest,
    jobs: List<ImageGenerationUiJob>,
    activeConversationId: String?
): ImageGenerationUiJob? {
    val currentJobId = request.currentJobId
    if (currentJobId != null) return jobs.firstOrNull { it.id == currentJobId }
    return jobs.firstOrNull { job ->
        job.chatMessageId == request.id && job.chatSessionId == activeConversationId
    }
}

internal fun chatGeneratedImageAssetIds(
    request: ChatGeneratedImageRequest,
    job: ImageGenerationUiJob?
): List<String> {
    if (request.status == ChatGeneratedImageStatus.DONE && request.imageAssetIds.isNotEmpty()) {
        return request.imageAssetIds
    }
    return job?.imageAssetIds.orEmpty().ifEmpty { job?.imageAssetId?.let(::listOf).orEmpty() }
}

/**
 * Resolves the complete batch for a chat image event while preserving the
 * generator's order.  The gallery list is not ordered by the chat event, so
 * looking up only the first asset (or sorting the gallery) can make a batch
 * appear to contain a single image until the user leaves and re-enters the
 * page.  Missing assets are omitted until the library publishes them.
 */
internal fun chatGeneratedImageItems(
    request: ChatGeneratedImageRequest,
    job: ImageGenerationUiJob?,
    availableImages: List<ImageAssetUiItem>
): List<ImageAssetUiItem> {
    val byId = availableImages.associateBy(ImageAssetUiItem::id)
    return chatGeneratedImageAssetIds(request, job)
        .asSequence()
        .filter(String::isNotBlank)
        .distinct()
        .mapNotNull(byId::get)
        .toList()
}

internal fun chatGeneratedImageUiState(
    request: ChatGeneratedImageRequest,
    job: ImageGenerationUiJob?,
    imageCount: Int
): ChatGeneratedImageUiState = when {
    imageCount > 0 -> ChatGeneratedImageUiState.RESULT
    job?.failed == true -> ChatGeneratedImageUiState.FAILED
    job != null && !job.terminal -> ChatGeneratedImageUiState.RUNNING
    job?.terminal == true && job.statusLabel == "已取消" -> ChatGeneratedImageUiState.CANCELLED
    job?.terminal == true && job.failed -> ChatGeneratedImageUiState.FAILED
    job?.terminal == true -> ChatGeneratedImageUiState.MISSING_RESULT
    request.status == ChatGeneratedImageStatus.FAILED -> ChatGeneratedImageUiState.FAILED
    request.status == ChatGeneratedImageStatus.CANCELLED -> ChatGeneratedImageUiState.CANCELLED
    request.status == ChatGeneratedImageStatus.AWAITING_APPROVAL -> ChatGeneratedImageUiState.AWAITING_APPROVAL
    request.status == ChatGeneratedImageStatus.QUEUED -> ChatGeneratedImageUiState.RUNNING
    request.status == ChatGeneratedImageStatus.INTERRUPTED ||
        request.status == ChatGeneratedImageStatus.GENERATING -> ChatGeneratedImageUiState.INTERRUPTED
    else -> ChatGeneratedImageUiState.MISSING_RESULT
}

internal fun chatGeneratedImageModelLabel(request: ChatGeneratedImageRequest): String? {
    val model = request.modelName?.trim()?.takeIf(String::isNotBlank)
    val backend = when (request.backendId?.trim()?.uppercase()) {
        "LOCAL" -> "本地"
        "CLOUD" -> "云端"
        else -> null
    }
    return listOfNotNull(model, backend).joinToString(" · ").takeIf(String::isNotBlank)
}
