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
private fun ReasoningPanel(
    content: String,
    durationMs: Long,
    awaitingFinalAnswer: Boolean,
    modifier: Modifier = Modifier
) {
    var visibleCharacters by rememberSaveable { mutableStateOf(MESSAGE_RENDER_PAGE_CHARS) }
    val boundedContent = remember(content, visibleCharacters) {
        content.safePrefix(visibleCharacters)
    }
    val cleaned = remember(boundedContent) { cleanReasoningForDisplay(boundedContent) }
    if (cleaned.isBlank()) return

    var expanded by rememberSaveable { mutableStateOf(true) }
    val seconds = durationMs.div(1000).coerceAtLeast(0)
    val title = if (awaitingFinalAnswer) {
        if (seconds > 0) "正在思考（用时 ${seconds} 秒）" else "正在思考"
    } else if (seconds > 0) {
        "已思考（用时 ${seconds} 秒）"
    } else {
        "已思考"
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val displayText = if (expanded) cleaned else reasoningPreview(cleaned)
    val wrappedDisplayText = remember(displayText) { wrapForDisplay(displayText) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = muted,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "收起思考" else "展开思考",
                tint = muted,
                modifier = Modifier.size(18.dp)
            )
        }
        Row(
            modifier = Modifier
                .padding(top = 4.dp)
                .height(IntrinsicSize.Min)
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(muted.copy(alpha = 0.16f), RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = wrappedDisplayText,
                    color = muted.copy(alpha = 0.74f),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    ),
                    maxLines = if (expanded) Int.MAX_VALUE else REASONING_COLLAPSED_MAX_LINES,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                    softWrap = true
                )
                if (expanded && boundedContent.length < content.length) {
                    TextButton(
                        onClick = {
                            visibleCharacters = (visibleCharacters + MESSAGE_RENDER_PAGE_CHARS)
                                .coerceAtMost(content.length)
                        }
                    ) {
                        Text("显示更多（${boundedContent.length}/${content.length}）")
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingReasoningPanel(
    ownerCreatedAt: Long,
    phase: GenerationPhase?,
    tokenProgress: TokenProgress?,
    persistProgress: PersistProgress?,
    modifier: Modifier = Modifier
) {
    // UI-visible waiting time is separate from runtime timing and persisted UTC.
    val startedAtElapsedMs = remember(ownerCreatedAt) { SystemClock.elapsedRealtime() }
    var elapsedMs by remember(ownerCreatedAt) { mutableStateOf(0L) }
    LaunchedEffect(startedAtElapsedMs) {
        while (true) {
            elapsedMs = (SystemClock.elapsedRealtime() - startedAtElapsedMs).coerceAtLeast(0L)
            delay(1000)
        }
    }
    val seconds = elapsedMs.div(1000).coerceAtLeast(0)
    val phaseTitle = generationPhaseLabel(phase)
    val progressText = tokenProgress?.let { progress ->
        if (phase == GenerationPhase.PREPROCESS) {
            "\u5df2\u5904\u7406 ${progress.completedTokens}/${progress.totalTokens} \u5f20\u56fe\u7247"
        } else {
            "\u5df2\u5904\u7406 ${progress.completedTokens}/${progress.totalTokens} tokens"
        }
    }
    val persist = persistProgress?.takeIf { it.isActive }
    val persistTitle = persist?.let {
        when (it.stage) {
            com.muyuchat.core.engine.PersistStage.ENCODING -> "\u7f13\u5b58\u7f16\u7801\u4e2d"
            com.muyuchat.core.engine.PersistStage.WRITING -> "\u7f13\u5b58\u5e8f\u5217\u5316"
            else -> phaseTitle
        }
    }
    val displayTitle = if (persistTitle != null) {
        if (seconds > 0) "$persistTitle (\u5df2\u7528\u65f6 ${seconds} \u79d2)" else persistTitle
    } else if (seconds > 0) {
        "$phaseTitle (\u5df2\u7528\u65f6 ${seconds} \u79d2)"
    } else {
        phaseTitle
    }
    val persistText = persist?.let { p ->
        val writtenMb = p.writtenBytes / 1048576.0
        val totalMb = p.totalBytes / 1048576.0
        "\u5df2\u5199\u5165 ${"%.1f".format(writtenMb)}/${"%.1f".format(totalMb)} MB"
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                color = muted,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = muted,
                modifier = Modifier.size(18.dp)
            )
        }
        if (phase != null) {
            if (persist != null && persist.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = {
                        (persist.writtenBytes.toFloat() / persist.totalBytes.toFloat()).coerceIn(0f, 1f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(99.dp))
                )
            } else if (tokenProgress != null) {
                LinearProgressIndicator(
                    progress = {
                        tokenProgress.completedTokens.toFloat() / tokenProgress.totalTokens.toFloat()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(99.dp))
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(99.dp))
                )
            }
            (persistText ?: progressText)?.let { text ->
                Text(
                    text = text,
                    modifier = Modifier.padding(top = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = muted
                )
            }
        }
    }
}

@Composable
internal fun PromptContextUsageLine(usage: PromptContextUsage) {
    val summary = buildString {
        append("\u4fdd\u7559 ").append(usage.retainedMessageCount).append(" \u6761")
        if (usage.trimmedMessageCount > 0) {
            append(" \u00b7 \u88c1\u526a ").append(usage.trimmedMessageCount).append(" \u6761")
        }
        append(" \u00b7 \u89d2\u8272 ").append(usage.roleTokens).append(" tokens")
        append(" \u00b7 \u4e16\u754c\u4e66 ").append(usage.worldBookTokens).append(" tokens")
        append(" \u00b7 \u77e5\u8bc6\u5e93 ").append(usage.knowledgeTokens).append(" tokens")
    }
    val retained = usage.messageRetention.filter { it.retained }
    val trimmed = usage.messageRetention.filterNot { it.retained }
    val historyDetails = buildList {
        if (retained.isNotEmpty()) appendPromptDecision("\u4fdd\u7559", retained)
        if (trimmed.isNotEmpty()) appendPromptDecision("\u88c1\u526a", trimmed)
    }.joinToString(" \u00b7 ")
    val sourceDetails = buildList {
        appendIdDecision("\u4e16\u754c\u4e66\u547d\u4e2d", usage.selectedWorldBookEntryIds)
        appendIdDecision("\u4e16\u754c\u4e66\u8df3\u8fc7", usage.skippedWorldBookEntryIds)
        appendIdDecision("\u77e5\u8bc6\u5e93\u547d\u4e2d", usage.selectedKnowledgeChunkIds)
        appendIdDecision("\u77e5\u8bc6\u5e93\u8df3\u8fc7", usage.skippedKnowledgeChunkIds)
    }.joinToString(" \u00b7 ")
    val details = listOf(
        "$summary \u00b7 \u603b ${usage.totalEstimatedTokens} tokens",
        historyDetails,
        sourceDetails
    ).filter { it.isNotBlank() }.joinToString("\n")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 2.dp)
            .semantics {
                stateDescription = "\u5f53\u524d\u4e0a\u4e0b\u6587\u4f7f\u7528\u60c5\u51b5"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = details,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
        )
    }
}

private fun MutableList<String>.appendPromptDecision(
    label: String,
    decisions: List<PromptMessageRetention>
) {
    val visible = decisions.take(4).joinToString(",") { decision ->
        val role = when (decision.role) {
            Role.SYSTEM -> "S"
            Role.USER -> "U"
            Role.ASSISTANT -> "A"
        }
        "#${decision.originalIndex + 1}$role"
    }
    val remainder = decisions.size - minOf(decisions.size, 4)
    add(buildString {
        append(label).append(' ').append(visible)
        if (remainder > 0) append(" +").append(remainder)
    })
}

private fun MutableList<String>.appendIdDecision(label: String, ids: List<String>) {
    if (ids.isEmpty()) return
    val visible = ids.take(3).joinToString(",") { it.take(8) }
    val remainder = ids.size - minOf(ids.size, 3)
    add(buildString {
        append(label).append(' ').append(visible)
        if (remainder > 0) append(" +").append(remainder)
    })
}

private fun generationPhaseLabel(phase: GenerationPhase?): String = when (phase) {
    GenerationPhase.LOAD -> "\u6b63\u5728\u52a0\u8f7d\u8fd0\u884c\u65f6"
    GenerationPhase.PREPROCESS -> "\u6b63\u5728\u51c6\u5907\u56fe\u7247"
    GenerationPhase.TOKENIZE -> "\u6b63\u5728\u5206\u8bcd"
    GenerationPhase.PREFILL -> "\u6b63\u5728\u9884\u5904\u7406\u4e0a\u4e0b\u6587"
    GenerationPhase.DECODE -> "\u6b63\u5728\u751f\u6210\u56de\u7b54"
    GenerationPhase.PERSIST -> "\u6b63\u5728\u5b8c\u6210\u63a8\u7406\u6536\u5c3e"
    null -> "\u6b63\u5728\u601d\u8003"
}

internal fun generationPerformanceSummary(stats: RuntimeStats?): String? {
    val activeStats = stats ?: return null
    return buildList {
        activeStats.prefillTps.takeIf { it.isFinite() && it > 0.0 }?.let { rate ->
            add("预填充 ${formatGenerationRate(rate)} token/s")
        }
        activeStats.effectivePromptTps
            .takeIf {
                it.isFinite() && it > 0.0 &&
                    activeStats.cacheReusedTokens > 0 &&
                    it > activeStats.prefillTps * 1.05
            }
            ?.let { rate ->
                add("缓存有效 ${formatGenerationRate(rate)} token/s")
            }
        activeStats.decodeTps.takeIf { it.isFinite() && it > 0.0 }?.let { rate ->
            add("输出 ${formatGenerationRate(rate)} token/s")
        }
    }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun formatGenerationRate(rate: Double): String =
    "%.1f".format(java.util.Locale.US, rate)

@Composable
private fun GenerationPerformanceLine(stats: RuntimeStats?) {
    val summary = generationPerformanceSummary(stats) ?: return
    Text(
        text = summary,
        modifier = Modifier.padding(top = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
    )
}

private fun cacheEvidenceLabel(stats: RuntimeStats): String? {
    val persistentReason = stats.persistentPrefixCacheReason
        ?.takeUnless { it in setOf("not_requested", "not_attempted", "model_unloaded") }
    val inMemoryReason = stats.cacheReuseReason
        ?.takeUnless { it in setOf("not_attempted", "model_unloaded") }
    return when {
        stats.persistentPrefixCacheHit && stats.cacheReuseReason == "persistent_session_hit" ->
            "会话快照命中 · 复用 ${stats.persistentPrefixCacheTokens} tokens"
        persistentReason == "session_state_saved" ->
            "会话快照已保存 · ${stats.persistentPrefixCacheTokens} tokens"
        stats.persistentPrefixCacheHit ->
            "\u56fa\u5b9a\u524d\u7f00\u7f13\u5b58\u547d\u4e2d \u00b7 \u590d\u7528 ${stats.persistentPrefixCacheTokens} tokens"
        persistentReason?.startsWith("session_") == true -> "会话快照未命中 · $persistentReason"
        persistentReason != null -> "\u56fa\u5b9a\u524d\u7f00\u7f13\u5b58\u672a\u547d\u4e2d \u00b7 $persistentReason"
        stats.cacheReuseReason == "unknown" -> "上下文 KV：未知"
        stats.cacheReuseHit -> "\u4e0a\u4e0b\u6587 KV \u547d\u4e2d \u00b7 \u590d\u7528 ${stats.cacheReusedTokens} tokens"
        stats.cacheReusedTokens > 0 -> "\u4e0a\u4e0b\u6587 KV \u590d\u7528 ${stats.cacheReusedTokens} tokens"
        inMemoryReason != null -> "\u4e0a\u4e0b\u6587 KV \u672a\u547d\u4e2d \u00b7 $inMemoryReason"
        else -> null
    }
}

/** Never attribute stale local throughput/KV evidence to a retained or cloud model selection. */
internal fun ChatUiState.hasReadyLocalModelForHeader(): Boolean =
    !selectedModelIsCloud && stats.loaded && selectedModelId != null &&
        localModels.any { it.id == selectedModelId && !it.cloud && it.loaded }

internal fun ChatUiState.modelHeaderSubtitle(): String {
    modelLoadMessage?.takeIf { it.isNotBlank() }?.let { return it }
    return listOfNotNull(
        modelReadinessLabel?.takeIf { it.isNotBlank() },
        selectedModelRuntimeLabel?.takeIf { it.isNotBlank() },
        generationPerformanceSummary(stats).takeIf { hasReadyLocalModelForHeader() }
    ).joinToString(" · ").ifBlank {
        if (selectedModelIsCloud && selectedModelName != null) "云端 API" else "待加载 · 点击选择模型"
    }
}

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
private fun ModelSwitcherDropdown(
    expanded: Boolean,
    models: List<ChatModelChoice>,
    selectedModelIsCloud: Boolean,
    isGenerating: Boolean,
    reasoningMode: ReasoningMode,
    generationParams: GenerationParams,
    onGenerationParamsChange: (GenerationParams) -> Unit,
    onDismiss: () -> Unit,
    onLoadModel: (String) -> Unit,
    onModelBackendChange: (String, String) -> Unit,
    onReasoningModeChange: (ReasoningMode) -> Unit,
    onCloudReasoningModeLocked: () -> Unit,
    onOpenModels: () -> Unit
) {
    val context = LocalContext.current
    var reasoningExpanded by rememberSaveable { mutableStateOf(false) }
    var generationParamsDialogVisible by rememberSaveable { mutableStateOf(false) }
    var sourceExpanded by rememberSaveable { mutableStateOf<InferenceSource?>(null) }
    val localModels = models.filterNot { it.cloud }
    val cloudModels = models.filter { it.cloud }
    val activeReasoningMode = if (selectedModelIsCloud) ReasoningMode.STANDARD else reasoningMode
    LaunchedEffect(selectedModelIsCloud) {
        if (selectedModelIsCloud) reasoningExpanded = false
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(30.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 18.dp,
        modifier = Modifier
            .widthIn(min = 238.dp, max = 320.dp)
            .clip(RoundedCornerShape(30.dp))
            .padding(vertical = 10.dp, horizontal = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 0.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "推理来源",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                InferenceSourceMenuRow(
                    source = InferenceSource.LOCAL,
                    selected = !selectedModelIsCloud,
                    expanded = sourceExpanded == InferenceSource.LOCAL,
                    count = localModels.size,
                    onClick = {
                        reasoningExpanded = false
                        sourceExpanded = if (sourceExpanded == InferenceSource.LOCAL) null else InferenceSource.LOCAL
                    }
                )
                InferenceSourceMenuRow(
                    source = InferenceSource.CLOUD,
                    selected = selectedModelIsCloud,
                    expanded = sourceExpanded == InferenceSource.CLOUD,
                    count = cloudModels.size,
                    onClick = {
                        reasoningExpanded = false
                        sourceExpanded = if (sourceExpanded == InferenceSource.CLOUD) null else InferenceSource.CLOUD
                    }
                )
                if (localModels.isEmpty() && cloudModels.isEmpty()) {
                    Text(
                        text = "暂无可用推理模型",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isGenerating) {
                    Text(
                        text = "生成中请先停止，再切换模型。",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
                )
                CapsuleMenuRow(
                    leading = {
                        ThinkingModeMark(
                            mode = activeReasoningMode,
                            selected = activeReasoningMode != ReasoningMode.OFF,
                            modifier = Modifier.size(34.dp)
                        )
                    },
                    onClick = {
                        sourceExpanded = null
                        if (selectedModelIsCloud) {
                            reasoningExpanded = false
                            Toast.makeText(context, CLOUD_REASONING_LOCKED_TIP, Toast.LENGTH_SHORT).show()
                            onCloudReasoningModeLocked()
                        } else {
                            reasoningExpanded = !reasoningExpanded
                        }
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "思考模式",
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 19.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (selectedModelIsCloud) "默认开启" else reasoningMode.shortLabel(),
                            color = if (activeReasoningMode == ReasoningMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            fontWeight = FontWeight.Bold
                        )
                        if (!selectedModelIsCloud) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .rotate(if (reasoningExpanded) -90f else 0f)
                                    .size(18.dp)
                            )
                        }
                    }
                }
                CapsuleMenuRow(
                    leading = {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurface)
                    },
                    onClick = {
                        sourceExpanded = null
                        reasoningExpanded = false
                        generationParamsDialogVisible = true
                    }
                ) {
                    Column {
                        Text(
                            text = "生成参数",
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "按当前模型保存采样和输出设置",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                CapsuleMenuRow(
                    leading = {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurface)
                    },
                    onClick = onOpenModels
                ) {
                    Text(
                        text = "更多模型",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            AnimatedVisibility(
                visible = sourceExpanded != null,
                enter = fadeIn(tween(120)) + slideInHorizontally(tween(160), initialOffsetX = { -it / 4 }),
                exit = fadeOut(tween(100))
            ) {
                ModelSourceInlineCapsule(
                    source = sourceExpanded ?: InferenceSource.LOCAL,
                    models = if (sourceExpanded == InferenceSource.CLOUD) cloudModels else localModels,
                    isGenerating = isGenerating,
                    onLoadModel = { id ->
                        onLoadModel(id)
                        sourceExpanded = null
                    },
                    onModelBackendChange = onModelBackendChange,
                    onOpenModels = onOpenModels,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .fillMaxWidth()
                )
            }
            AnimatedVisibility(
                visible = reasoningExpanded && !selectedModelIsCloud,
                enter = fadeIn(tween(120)) + slideInHorizontally(tween(160), initialOffsetX = { -it / 4 }),
                exit = fadeOut(tween(100))
            ) {
                ReasoningModeInlineCapsule(
                    selected = reasoningMode,
                    onSelect = { mode ->
                        onReasoningModeChange(mode)
                        reasoningExpanded = false
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .fillMaxWidth()
                )
            }
        }
    }
    if (generationParamsDialogVisible) {
        GenerationParamsQuickDialog(
            params = generationParams,
            onDismiss = { generationParamsDialogVisible = false },
            onApply = { updated ->
                onGenerationParamsChange(updated)
                generationParamsDialogVisible = false
                onDismiss()
            }
        )
    }
}

private enum class InferenceSource(
    val title: String,
    val subtitle: String
) {
    LOCAL("本地推理", "设备端模型"),
    CLOUD("云端推理", "API 推理引擎")
}

@Composable
private fun InferenceSourceMenuRow(
    source: InferenceSource,
    selected: Boolean,
    expanded: Boolean,
    count: Int,
    onClick: () -> Unit
) {
    val selectedColor = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.primary else Color(0xFF1A73E8)
    CapsuleMenuRow(
        leading = {
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(21.dp))
            } else if (source == InferenceSource.CLOUD) {
                Icon(Icons.Default.NetworkWifi, contentDescription = null, tint = selectedColor, modifier = Modifier.size(21.dp))
            } else {
                McaLogoMark(size = 22.dp, cornerRadius = 7.dp)
            }
        },
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = source.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 19.sp),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .rotate(if (expanded) -90f else 0f)
                    .size(18.dp)
            )
        }
    }
}

@Composable
private fun ModelSourceInlineCapsule(
    source: InferenceSource,
    models: List<ChatModelChoice>,
    isGenerating: Boolean,
    onLoadModel: (String) -> Unit,
    onModelBackendChange: (String, String) -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = modifier,
        color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 14.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text = source.title,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
                color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF80868B),
                fontWeight = FontWeight.Medium
            )
            if (models.isEmpty()) {
                Text(
                    text = if (source == InferenceSource.CLOUD) "暂无云端模型" else "暂无本地模型",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
                    color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF9AA0A6)
                )
                ReasoningModePill(
                    label = "模型管理",
                    selected = false,
                    onClick = onOpenModels
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    models.forEach { model ->
                        ModelChoicePill(
                            model = model,
                            enabled = !isGenerating || model.loaded,
                            onClick = {
                                if (!model.loaded) onLoadModel(model.id)
                            },
                            onBackendChange = onModelBackendChange
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelChoicePill(
    model: ChatModelChoice,
    enabled: Boolean,
    onClick: () -> Unit,
    onBackendChange: (String, String) -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val selectedColor = if (darkTheme) MaterialTheme.colorScheme.primary else Color(0xFF1A73E8)
    var customGpuDialogVisible by rememberSaveable(model.id) { mutableStateOf(false) }
    var customGpuLayersText by rememberSaveable(model.id, model.customGpuLayers) {
        mutableStateOf((model.customGpuLayers ?: 32).toString())
    }
    var customGpuLayerError by rememberSaveable(model.id) { mutableStateOf<String?>(null) }
    if (customGpuDialogVisible) {
        AlertDialog(
            onDismissRequest = {
                customGpuDialogVisible = false
                customGpuLayerError = null
            },
            title = { Text("自定义 GPU 层数") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "仅对当前 GGUF 模型生效。数值越大，尝试放到 GPU 的层越多；0 表示 CPU。" +
                            "设备或显存不足时，加载会给出具体错误，不会静默假装使用 GPU。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = customGpuLayersText,
                        onValueChange = {
                            customGpuLayersText = it.filter(Char::isDigit).take(4)
                            customGpuLayerError = null
                        },
                        label = { Text("GPU 层数（0–4096）") },
                        singleLine = true,
                        isError = customGpuLayerError != null,
                        supportingText = customGpuLayerError?.let { error -> { Text(error) } }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val layers = customGpuLayersText.toIntOrNull()
                    if (layers == null || layers !in 0..4096) {
                        customGpuLayerError = "请输入 0 到 4096 之间的整数。"
                    } else {
                        onBackendChange(model.id, "custom:$layers")
                        customGpuDialogVisible = false
                        customGpuLayerError = null
                    }
                }) { Text("保存并加载") }
            },
            dismissButton = {
                TextButton(onClick = {
                    customGpuDialogVisible = false
                    customGpuLayerError = null
                }) { Text("取消") }
            }
        )
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled, onClick = onClick),
        color = if (model.loaded) {
            if (darkTheme) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f) else Color(0xFFE8F0FE)
        } else {
            Color.Transparent
        },
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (model.loaded) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(17.dp), tint = selectedColor)
            } else {
                Spacer(modifier = Modifier.size(17.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = displayModelName(model.displayName) ?: model.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (model.loaded) selectedColor else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 16.sp),
                    fontWeight = if (model.loaded) FontWeight.SemiBold else FontWeight.Medium
                )
                Text(
                    text = buildList {
                        if (model.loaded) add("当前")
                        if (model.cloud) {
                            if (model.subtitle.isNotBlank()) add(model.subtitle)
                        } else {
                            if (model.subtitle.isNotBlank()) add(model.subtitle)
                            model.quant?.let(::add)
                            formatModelBytes(model.sizeBytes)?.let(::add)
                        }
                    }.joinToString(" · ").ifBlank { if (model.cloud) "云端模型" else "本地推理" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (model.loaded) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                    fontWeight = if (model.loaded) FontWeight.Medium else FontWeight.Normal
                )
                if (model.backendOptions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        model.backendOptions.forEach { option ->
                            FilterChip(
                                selected = model.selectedBackendId == option.id,
                                onClick = {
                                    if (option.id == "custom") {
                                        customGpuLayersText = (model.customGpuLayers ?: 32).toString()
                                        customGpuDialogVisible = true
                                    } else {
                                        onBackendChange(model.id, option.id)
                                    }
                                },
                                enabled = option.enabled && enabled,
                                label = {
                                    Text(
                                        option.label,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp)
                                    )
                                },
                                modifier = Modifier.heightIn(min = 28.dp)
                            )
                        }
                    }
                    model.backendOptions
                        .firstOrNull { it.id == model.selectedBackendId }
                        ?.availabilityNote
                        ?.takeIf(String::isNotBlank)
                        ?.let { note ->
                            Text(
                                text = note,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp)
                            )
                        }
                }
            }
        }
    }
}

@Composable
private fun ThinkingModeMark(
    mode: ReasoningMode,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    val selectedColor = if (darkTheme) MaterialTheme.colorScheme.primary else Color(0xFF1A73E8)
    Surface(
        modifier = modifier,
        color = if (selected) {
            if (darkTheme) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f) else Color(0xFFE8F0FE)
        } else {
            if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF1F3F4)
        },
        shape = CircleShape
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Psychology,
                contentDescription = null,
                tint = if (selected) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            if (mode == ReasoningMode.ADVANCED) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 5.dp, end = 5.dp)
                        .size(5.dp)
                        .background(selectedColor, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun ReasoningModeInlineCapsule(
    selected: ReasoningMode,
    onSelect: (ReasoningMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = modifier,
        color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 14.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text = "思考模式",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
                color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF80868B),
                fontWeight = FontWeight.Medium
            )
            ReasoningMode.entries.forEach { mode ->
                ReasoningModePill(
                    label = mode.shortLabel(),
                    selected = selected == mode,
                    onClick = { onSelect(mode) }
                )
            }
        }
    }
}

@Composable
private fun CapsuleMenuRow(
    leading: @Composable () -> Unit,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(38.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            leading()
        }
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}
@Composable
private fun ReasoningModePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val selectedColor = if (darkTheme) MaterialTheme.colorScheme.primary else Color(0xFF1A73E8)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clickable(onClick = onClick),
        color = if (selected) {
            if (darkTheme) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f) else Color(0xFFE8F0FE)
        } else {
            Color.Transparent
        },
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(17.dp), tint = selectedColor)
            } else {
                Spacer(modifier = Modifier.size(17.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = if (selected) selectedColor else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        }
    }
}

private fun ReasoningMode.shortLabel(): String = when (this) {
    ReasoningMode.OFF -> "关闭"
    ReasoningMode.STANDARD -> "标准"
    ReasoningMode.ADVANCED -> "进阶"
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
internal fun ChatHistoryDrawer(
    state: ChatUiState,
    onClose: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenAppMenu: () -> Unit,
    onOpenImages: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onBranchMixedConversation: (String, String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onClearHistory: () -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onTogglePinConversation: (String) -> Unit,
    onExportConversation: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ChatHistoryItem?>(null) }
    var renameTarget by remember { mutableStateOf<ChatHistoryItem?>(null) }
    var branchTarget by remember { mutableStateOf<ChatHistoryItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    var clearAllRequested by remember { mutableStateOf(false) }
    val filteredHistory = remember(state.history, query) {
        val keyword = query.trim()
        if (keyword.isBlank()) {
            state.history
        } else {
            state.history.filter { item ->
                item.title.contains(keyword, ignoreCase = true) ||
                    item.displayName.contains(keyword, ignoreCase = true) ||
                    item.summary.contains(keyword, ignoreCase = true) ||
                    item.updatedAtText.contains(keyword, ignoreCase = true)
            }
        }
    }

    ModalDrawerSheet(
        modifier = Modifier.fillMaxSize(),
        drawerContainerColor = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .edgeSwipeBack(onClose)
                .background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "MCA",
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = 25.sp),
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = CircleShape,
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.Search, contentDescription = "搜索", modifier = Modifier.size(22.dp))
                            }
                            IconButton(onClick = onOpenAppMenu, modifier = Modifier.size(34.dp)) {
                                McaLogoMark(size = 25.dp, cornerRadius = 9.dp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))
                AppFeatureButton(icon = { Icon(Icons.Default.Image, null) }, text = "图片", onClick = onOpenImages)
                Spacer(modifier = Modifier.height(34.dp))

                Text(
                    "最近",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = 92.dp)
                ) {
                    if (state.history.isEmpty()) {
                        item { HistoryEmptyState("暂无历史记录。发送第一条消息后会自动保存在这里。") }
                    } else if (filteredHistory.isEmpty()) {
                        item { HistoryEmptyState("没有找到匹配的对话。") }
                    } else {
                        historySections(filteredHistory, query).forEach { section ->
                            item(key = "section-${section.title}") {
                                HistorySectionHeader(section.title)
                            }
                            items(section.items, key = { it.id }) { item ->
                                HistoryRow(
                                    item = item,
                                    onClick = { onSelectConversation(item.id) },
                                    onRename = {
                                        renameTarget = item
                                        renameText = item.title
                                    },
                                    onTogglePin = { onTogglePinConversation(item.id) },
                                    onBranch = { branchTarget = item },
                                    onDelete = { deleteTarget = item }
                                )
                            }
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 24.dp)
                    .height(44.dp)
                    .clickable(onClick = onNewConversation),
                color = MaterialTheme.colorScheme.onBackground,
                shape = CircleShape,
                shadowElevation = 12.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.background, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "聊天",
                        color = MaterialTheme.colorScheme.background,
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除对话") },
            text = { Text("确定删除“${item.displayName}”吗？此操作不会删除模型文件。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteConversation(item.id)
                        deleteTarget = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            }
        )
    }

    branchTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { branchTarget = null },
            title = { Text("选择分支角色") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("原始记录保持只读，新分支从空对话开始。")
                    state.assistants.forEach { assistant ->
                        TextButton(
                            onClick = {
                                onBranchMixedConversation(item.id, assistant.id)
                                branchTarget = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(assistant.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { branchTarget = null }) { Text("取消") } }
        )
    }

    renameTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("对话标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameConversation(item.id, renameText)
                        renameTarget = null
                    },
                    enabled = renameText.trim().isNotEmpty()
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (clearAllRequested) {
        AlertDialog(
            onDismissRequest = { clearAllRequested = false },
            title = { Text("清空全部历史") },
            text = { Text("确定清空所有聊天记录吗？当前模型和模型文件不会被删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearHistory()
                        clearAllRequested = false
                    }
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { clearAllRequested = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun Modifier.edgeSwipeBack(onBack: () -> Unit): Modifier {
    val density = LocalDensity.current
    val triggerPx = with(density) { 42.dp.toPx() }
    return pointerInput(onBack, triggerPx) {
        var startedAtEdge = false
        var totalDrag = 0f
        detectHorizontalDragGestures(
            onDragStart = { offset ->
                startedAtEdge = offset.x >= 0f
                totalDrag = 0f
            },
            onHorizontalDrag = { change, dragAmount ->
                if (startedAtEdge) {
                    totalDrag += dragAmount
                    if (dragAmount < 0f) change.consume()
                }
            },
            onDragEnd = {
                if (startedAtEdge && totalDrag < -triggerPx) onBack()
                startedAtEdge = false
                totalDrag = 0f
            },
            onDragCancel = {
                startedAtEdge = false
                totalDrag = 0f
            }
        )
    }
}

@Composable
private fun AppFeatureButton(icon: @Composable () -> Unit, text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(29.dp), contentAlignment = Alignment.Center) {
            icon()
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun HistorySectionHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(top = 10.dp, bottom = 5.dp, start = 8.dp),
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp, lineHeight = 14.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun HistoryEmptyState(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp
    ) {
        Text(
            text,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HistoryRow(
    item: ChatHistoryItem,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onBranch: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Surface(
        color = if (item.selected) {
            MaterialTheme.colorScheme.surface
        } else {
            Color.Transparent
        },
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 0.dp,
        shadowElevation = if (item.selected) 5.dp else 0.dp,
        border = null,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.displayName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 16.sp),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (item.pinned) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                "置顶",
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (item.mixedAssistantHistory) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("混合", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                if (item.summary.isNotBlank()) {
                    Text(
                        "（${item.summary}）",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "${item.updatedAtText} · ${item.messageCount} 条消息",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, lineHeight = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                )
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多操作",
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.width(216.dp),
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    shadowElevation = 18.dp
                ) {
                    Column(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        HistoryMenuItem(
                            text = "重命名",
                            icon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            }
                        )
                        HistoryMenuItem(
                            text = if (item.pinned) "取消置顶" else "置顶",
                            icon = { Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuExpanded = false
                                onTogglePin()
                            }
                        )
                        if (item.mixedAssistantHistory) {
                            HistoryMenuItem(
                                text = "创建角色分支",
                                icon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    menuExpanded = false
                                    onBranch()
                                }
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        )
                        HistoryMenuItem(
                            text = "删除",
                            icon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            contentColor = MaterialTheme.colorScheme.error,
                            iconContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryMenuItem(
    text: String,
    icon: @Composable () -> Unit,
    contentColor: Color? = null,
    iconContainerColor: Color? = null,
    onClick: () -> Unit
) {
    val itemContentColor = contentColor ?: MaterialTheme.colorScheme.onSurface
    val itemIconContainerColor = iconContainerColor ?: MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(30.dp),
            color = itemIconContainerColor,
            shape = RoundedCornerShape(10.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                icon()
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text,
            color = itemContentColor,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 19.sp),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
    }
}

private data class HistorySection(
    val title: String,
    val items: List<ChatHistoryItem>
)

private fun historySections(items: List<ChatHistoryItem>, query: String): List<HistorySection> {
    if (query.trim().isNotEmpty()) {
        return listOf(HistorySection("搜索结果", items))
    }
    val order = listOf("置顶", "今天", "昨天", "7 天内", "更早")
    return items
        .groupBy { historySectionTitle(it) }
        .map { (title, sectionItems) -> HistorySection(title, sectionItems) }
        .sortedBy { section -> order.indexOf(section.title).let { if (it < 0) Int.MAX_VALUE else it } }
}

private fun historySectionTitle(item: ChatHistoryItem): String {
    if (item.pinned) return "置顶"
    val now = Calendar.getInstance()
    val updated = Calendar.getInstance().apply { timeInMillis = item.updatedAtMillis }
    if (sameDay(now, updated)) return "今天"

    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (sameDay(yesterday, updated)) return "昨天"

    val sevenDaysAgo = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }
    return if (updated.after(sevenDaysAgo)) "7 天内" else "更早"
}

private fun sameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

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

private fun formatModelBytes(bytes: Long): String? {
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

private fun extractAttachmentName(input: String): String? =
    ATTACHMENT_NAME_PATTERNS.firstNotNullOfOrNull { pattern ->
        pattern.find(input)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

private fun displayInputWithoutAttachment(input: String): String {
    val marker = firstAttachmentMarker(input)
    if (marker < 0) return input
    return input.substring(0, marker).trimEnd()
}

internal fun saveCameraPreview(context: Context, bitmap: Bitmap): Uri {
    val dir = File(context.cacheDir, "mca_camera").apply { mkdirs() }
    val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
    FileOutputStream(file).use { output ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
    }
    return Uri.fromFile(file)
}

private fun removeAttachmentFromInput(input: String): String =
    displayInputWithoutAttachment(input).trimEnd()

private fun mergeInputWithExistingAttachment(currentInput: String, visibleText: String): String {
    val marker = firstAttachmentMarker(currentInput)
    if (marker < 0) return visibleText
    val attachment = currentInput.substring(marker).trimStart()
    return buildString {
        append(visibleText)
        if (visibleText.isNotBlank()) append("\n\n")
        append(attachment)
    }
}

private val ATTACHMENT_PREFIXES = listOf("【上传文件：", "【上传图片：")

private fun firstAttachmentMarker(input: String): Int =
    ATTACHMENT_PREFIXES
        .map { input.indexOf(it) }
        .filter { it >= 0 }
        .minOrNull() ?: -1

private val ATTACHMENT_NAME_PATTERNS = listOf(
    Regex("""【上传文件：([^】]+)】"""),
    Regex("""【上传图片：([^】]+)】""")
)

private val IMAGE_ATTACHMENT_PATTERN = Regex("""【上传图片：([^】]+)】""")
private val IMAGE_ATTACHMENT_WITH_URI_PATTERN = Regex("""【上传图片：([^】]+)】(?:\s*\n描述：[^\n]+)?\s*\n(\S+)""")

private const val CHAT_ATTACHMENT_IDENTITY_MAX_BYTES = 64L * 1024L * 1024L

/**
 * Resolves the content identity used by the composer preflight and message bubble.
 *
 * Picker URIs are not the only representation that can occur here.  Imported
 * assets are persisted as private `file://` URIs, and an older history can also
 * contain an absolute path.  The old implementation returned `null` for those
 * paths, so two copies of the same picked image survived the UI preflight and
 * were rendered as two attachments until the conversation was reloaded.  Keep
 * content-provider reads context-aware, then delegate every file/data/URL form
 * to the engine's shared identity contract.
 *
 * This function is called from a coroutine on Dispatchers.IO.  It streams raw
 * bytes and never retains the image in memory.
 */
internal fun chatImageAttachmentContentIdentity(context: Context, rawUri: String): String? {
    val uri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return null
    if (!uri.scheme.equals("content", ignoreCase = true)) {
        // Local imported files and inline data URLs can use the same byte-based
        // identity as the native/cloud request path.  Remote URLs remain
        // conservative and are normalized by the shared helper.
        return runCatching {
            ChatImageAttachment(uriString = rawUri).visionDeduplicationKey()
        }.getOrNull()
    }
    return runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = requireNotNull(context.contentResolver.openInputStream(uri)) {
            "Cannot read image attachment URI."
        }
        var totalBytes = 0L
        input.use { stream ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                totalBytes += read
                require(totalBytes <= CHAT_ATTACHMENT_IDENTITY_MAX_BYTES) {
                    "Image attachment is too large to hash for duplicate detection."
                }
                digest.update(buffer, 0, read)
            }
        }
        "bytes:" + digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }.getOrNull()
}

private fun hasImageAttachmentMarker(input: String): Boolean =
    IMAGE_ATTACHMENT_PATTERN.containsMatchIn(input)

private fun extractAttachmentMeta(input: String): String? {
    val uriString = IMAGE_ATTACHMENT_WITH_URI_PATTERN.find(input)
        ?.groupValues
        ?.getOrNull(2)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val sourceLabel = when {
        uriString.startsWith("content://", ignoreCase = true) -> "系统图片"
        uriString.startsWith("file://", ignoreCase = true) -> "本地文件"
        uriString.startsWith("http://", ignoreCase = true) || uriString.startsWith("https://", ignoreCase = true) -> "网络图片"
        uriString.startsWith("data:image", ignoreCase = true) -> "内联图片"
        else -> "图片输入"
    }
    val extension = uriString
        .substringBefore('?')
        .substringAfterLast('.', "")
        .takeIf { it.length in 2..5 }
        ?.uppercase()
    return listOfNotNull(sourceLabel, extension, "发送前自动压缩").joinToString(" · ")
}

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
            statusMessage?.takeIf { it.isNotBlank() }?.let { message ->
                if (userNotice(message) != null) {
                    GuidanceNotice(message, onOpenModels, onDismissStatusMessage, onDismissStatusMessage)
                } else {
                ComposerStatusMessage(
                    message = message,
                    onDismiss = onDismissStatusMessage,
                    modifier = Modifier.padding(start = 4.dp, end = 2.dp, bottom = 6.dp)
                )
                }
            }
            if (!webSearchStatusMessage.isNullOrBlank()) {
                WebSearchStatusChip(
                    message = webSearchStatusMessage,
                    active = webSearchEnabledForTurn,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp)
                )
            }
            if (imagePromptMode) {
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
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "退出聊天生图模式",
                            modifier = Modifier.size(16.dp),
                            tint = inputPlaceholderColor
                        )
                    }
                }
            }
            AttachmentPreview(
                input = input,
                visionCapabilityLabel = visionCapabilityLabel,
                visionCapabilityDetail = visionCapabilityDetail,
                visionCapabilityReady = visionCapabilityReady,
                onRemove = { onInputChange(removeAttachmentFromInput(input)) }
            )
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
                    Icon(Icons.Default.Add, contentDescription = "更多操作", modifier = Modifier.size(24.dp), tint = if (isGenerating) inputPlaceholderColor else McaPrimaryBlue)
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
                FloatingActionButton(
                    onClick = {
                        if (isGenerating) onStop()
                        else if (attachmentPreflightRunning) Unit
                        else if (imagePromptMode) onSendImagePrompt()
                        else onSend()
                    },
                    containerColor = McaPrimaryBlue,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
                    shape = CircleShape,
                    modifier = Modifier.size(40.dp)
                ) {
                    if (attachmentPreflightRunning && !isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(19.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(
                            imageVector = when {
                                isGenerating -> Icons.Default.Stop
                                imagePromptMode -> Icons.Default.Image
                                else -> Icons.AutoMirrored.Filled.Send
                            },
                            contentDescription = when {
                                isGenerating -> "停止"
                                imagePromptMode -> "生成图片"
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

internal fun composerStatusIsError(message: String): Boolean =
    listOf("失败", "无法", "未发送", "未加载", "请先", "超过").any(message::contains)

@Composable
private fun ComposerStatusMessage(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isError = composerStatusIsError(message)
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = contentColor
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "关闭提示",
                modifier = Modifier.size(16.dp),
                tint = contentColor
            )
        }
    }
}

@Composable
private fun WebSearchStatusChip(
    message: String,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (active && !darkTheme) Color(0xFFEAF1FF) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (active) McaPrimaryBlue else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private enum class ResearchModeChoice(val key: String, val label: String) {
    OFF("OFF", "普通"),
    AUTO("AUTO", "自动"),
    DEEP("DEEP", "深度");

    companion object {
        fun selectedKey(value: String): String =
            entries.firstOrNull { it.key.equals(value, ignoreCase = true) }?.key ?: AUTO.key
    }
}

@Composable
private fun ResearchModeFloatingCapsule(
    selected: String,
    overridden: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 86.dp)
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomStart
    ) {
        val capsuleWidth = 128.dp
        val startPadding = minOf(252.dp, maxWidth - capsuleWidth - 12.dp).coerceAtLeast(12.dp)
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(120)) + slideInHorizontally(tween(160), initialOffsetX = { -it / 4 }),
            exit = fadeOut(tween(100)),
            modifier = Modifier
                .padding(start = startPadding)
                .width(capsuleWidth)
        ) {
            val darkTheme = isSystemInDarkTheme()
            Surface(
                modifier = Modifier.clickable(enabled = false) {},
                color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 14.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (overridden) "本轮研究" else "研究模式",
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
                            color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF80868B),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭研究模式选择",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable(onClick = onDismiss)
                        )
                    }
                    val selectedKey = ResearchModeChoice.selectedKey(selected)
                    ResearchModeChoice.entries.forEach { mode ->
                        ReasoningModePill(
                            label = mode.label,
                            selected = selectedKey == mode.key,
                            onClick = { onSelect(mode.key) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactInputActionMenu(
    onDismiss: () -> Unit,
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
    onResearchMode: () -> Unit,
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
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomStart
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(onClick = onDismiss)
        )
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(120)) + slideInHorizontally(
                animationSpec = tween(180),
                initialOffsetX = { -it / 5 }
            ),
            exit = fadeOut(tween(100)),
            modifier = Modifier
                .padding(bottom = 86.dp)
                .width(238.dp)
        ) {
            Surface(
                modifier = Modifier.clickable(enabled = false) {},
                color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
                shape = RoundedCornerShape(30.dp),
                shadowElevation = 18.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = if (imagePromptMode) "退出聊天生图" else "在聊天中生成图片",
                        onClick = onImagePromptMode
                    )
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(20.dp)) },
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
                        onClick = {
                            onSetAssistantImageToolAutoApproval(!assistantImageToolAutoApproval)
                        }
                    )
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = "相机",
                        onClick = onCamera
                    )
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = "照片",
                        onClick = onPhoto
                    )
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = "文件",
                        onClick = onFile
                    )
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = "从文件库添加",
                        onClick = onLibrary
                    )
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
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
                        onClick = onWebSearch
                    )
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = "研究模式：${webSearchResearchModeLabel.ifBlank { "自动" }}",
                        subtitle = when {
                            !webSearchEnabled -> "先启用联网检索"
                            webSearchResearchOverridden -> "仅影响下一轮发送"
                            else -> "跟随联网检索默认设置"
                        },
                        onClick = onResearchMode
                    )
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
    onClick: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            color = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF1F3F4),
            shape = CircleShape
        ) {
            Box(contentAlignment = Alignment.Center) {
                icon()
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = label,
                color = (if (darkTheme) MaterialTheme.colorScheme.onSurface else Color(0xFF202124))
                    .copy(alpha = if (enabled) 1f else 0.58f),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 19.sp),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.takeIf(String::isNotBlank)?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.82f else 0.62f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private enum class FileLibraryFilter(val label: String) {
    ALL("全部"),
    TEXT("文本"),
    CODE("代码"),
    DATA("数据")
}

@Composable
internal fun FileLibraryPage(
    files: List<FileAssetUiItem>,
    onInsert: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(FileLibraryFilter.ALL) }
    val darkTheme = isSystemInDarkTheme()
    val libraryInputFieldColor = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else McaInputField
    val filtered = remember(files, query, filter) {
        files.filter { file ->
            val queryMatched = query.isBlank() ||
                file.name.contains(query, ignoreCase = true) ||
                file.preview.contains(query, ignoreCase = true)
            queryMatched && file.matchesFilter(filter)
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("文件库", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "${files.size} 个本机文件索引",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.Close, contentDescription = "关闭文件库")
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜索文件名或内容预览") },
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = libraryInputFieldColor,
                    unfocusedContainerColor = libraryInputFieldColor,
                    focusedBorderColor = McaPrimaryBlue.copy(alpha = 0.42f),
                    unfocusedBorderColor = Color.Transparent
                )
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                FileLibraryFilter.entries.forEach { item ->
                    FilterChip(
                        selected = filter == item,
                        onClick = { filter = item },
                        label = { Text(item.label) }
                    )
                }
            }
            if (filtered.isEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 22.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            if (files.isEmpty()) "文件库为空" else "没有匹配文件",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (files.isEmpty()) "从输入框上传文本、Markdown、JSON、代码文件后，会自动出现在这里。"
                            else "换个关键词或切换筛选类型试试。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { file ->
                        FileLibraryRow(
                            file = file,
                            onInsert = { onInsert(file.id) },
                            onDelete = { onDelete(file.id) }
                        )
                    }
                }
            }
        }
    }
@Composable
private fun FileLibraryRow(
    file: FileAssetUiItem,
    onInsert: () -> Unit,
    onDelete: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(36.dp),
                    color = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else McaInputIconSurface,
                    shape = CircleShape
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = McaPrimaryBlue
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        file.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${file.sizeText} · ${file.createdAtText}${if (file.truncated) " · 已截取" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                file.preview,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDelete) {
                    Text("删除")
                }
                Button(onClick = onInsert) {
                    Text("插入当前聊天")
                }
            }
        }
    }
}

private fun FileAssetUiItem.matchesFilter(filter: FileLibraryFilter): Boolean {
    if (filter == FileLibraryFilter.ALL) return true
    val lowerName = name.lowercase()
    val lowerMime = mimeType.lowercase()
    return when (filter) {
        FileLibraryFilter.ALL -> true
        FileLibraryFilter.TEXT -> lowerMime.startsWith("text/") &&
            !lowerName.endsWith(".kt") &&
            !lowerName.endsWith(".java") &&
            !lowerName.endsWith(".py") &&
            !lowerName.endsWith(".js") &&
            !lowerName.endsWith(".ts")
        FileLibraryFilter.CODE -> lowerMime.contains("code") ||
            lowerName.endsWith(".kt") ||
            lowerName.endsWith(".java") ||
            lowerName.endsWith(".py") ||
            lowerName.endsWith(".js") ||
            lowerName.endsWith(".ts")
        FileLibraryFilter.DATA -> lowerMime.contains("json") ||
            lowerMime.contains("xml") ||
            lowerName.endsWith(".json") ||
            lowerName.endsWith(".jsonl") ||
            lowerName.endsWith(".xml") ||
            lowerName.endsWith(".csv")
    }
}

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
private fun AttachmentPreview(
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
            color = Color(0xFFEEF4FF),
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
                                color = Color.White.copy(alpha = 0.72f),
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
                        color = Color(0xFF202124),
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
private fun GenerationParamsQuickDialog(
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
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelSmall,
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

private data class ContextTraceSource(
    val sourceId: String,
    val ownerId: String,
    val scope: String?,
    val excerpt: String,
    val estimatedTokens: Int,
    val reason: String,
    val lexicalScore: Int? = null,
    val excerptTruncated: Boolean = false,
    val excerptSha256: String? = null
)

private data class ContextTraceDetail(
    val rolePromptHash: String,
    val assembledHash: String,
    val tokenBudget: Int,
    val retrievalType: String,
    val selectedWorldBooks: List<ContextTraceSource>,
    val skippedWorldBooks: List<ContextTraceSource>,
    val selectedKnowledge: List<ContextTraceSource>,
    val skippedKnowledge: List<ContextTraceSource>,
    val omittedSourceCount: Int = 0
)

private fun JSONObject.traceText(key: String): String =
    (get(key) as? String)?.takeIf(String::isNotBlank) ?: error("Invalid context trace field: $key")

private fun JSONObject.traceCount(key: String): Int =
    ((get(key) as? Number)?.toInt() ?: error("Invalid context trace field: $key"))
        .also { require(it >= 0) }

private fun parseContextAssemblyTrace(raw: String?): ContextTraceDetail? {
    if (raw.isNullOrBlank() || raw.length > 4_000_000) return null
    return runCatching {
        val root = JSONObject(raw)
        require(root.traceCount("schemaVersion") == 1)
        fun worldSources(key: String): List<ContextTraceSource> {
            val array = root.getJSONArray(key)
            require(array.length() <= 4096)
            return List(array.length()) { index ->
                val source = array.getJSONObject(index)
                ContextTraceSource(
                    sourceId = source.traceText("entryId"),
                    ownerId = source.traceText("bookId"),
                    scope = source.traceText("scope"),
                    excerpt = source.traceText("excerpt"),
                    estimatedTokens = source.traceCount("estimatedTokens"),
                    reason = source.traceText("reason"),
                    excerptTruncated = source.optBoolean("excerptTruncated", false),
                    excerptSha256 = source.optString("excerptSha256").takeIf(String::isNotBlank)
                )
            }
        }
        fun knowledgeSources(key: String): List<ContextTraceSource> {
            val array = root.getJSONArray(key)
            require(array.length() <= 4096)
            return List(array.length()) { index ->
                val source = array.getJSONObject(index)
                ContextTraceSource(
                    sourceId = source.traceText("chunkId"),
                    ownerId = source.traceText("knowledgeBaseId"),
                    scope = source.traceText("documentId"),
                    excerpt = source.traceText("excerpt"),
                    estimatedTokens = source.traceCount("estimatedTokens"),
                    reason = source.traceText("reason"),
                    lexicalScore = source.traceCount("lexicalScore"),
                    excerptTruncated = source.optBoolean("excerptTruncated", false),
                    excerptSha256 = source.optString("excerptSha256").takeIf(String::isNotBlank)
                )
            }
        }
        ContextTraceDetail(
            rolePromptHash = root.traceText("rolePromptHash"),
            assembledHash = root.traceText("assembledHash"),
            tokenBudget = root.traceCount("tokenBudget"),
            retrievalType = root.traceText("retrievalType"),
            selectedWorldBooks = worldSources("selectedWorldBookSources"),
            skippedWorldBooks = worldSources("skippedWorldBookSources"),
            selectedKnowledge = knowledgeSources("selectedKnowledgeSources"),
            skippedKnowledge = knowledgeSources("skippedKnowledgeSources"),
            omittedSourceCount = root.optInt("omittedSourceCount", 0).coerceAtLeast(0)
        )
    }.getOrNull()
}

@Composable
private fun ContextAssemblyTraceDialog(trace: ContextTraceDetail, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本轮上下文来源") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("检索方式：${if (trace.retrievalType == "lexical") "词法匹配 (lexical)" else trace.retrievalType}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("动态上下文预算：${trace.tokenBudget} tokens", style = MaterialTheme.typography.bodySmall)
                        if (trace.omittedSourceCount > 0) {
                            Text("另有 ${trace.omittedSourceCount} 条来源未保存详情", style = MaterialTheme.typography.bodySmall)
                        }
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("角色提示词 SHA-256：${trace.rolePromptHash}", style = MaterialTheme.typography.labelSmall)
                                Text("组装上下文 SHA-256：${trace.assembledHash}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                contextTraceSection("世界书 · 已选", trace.selectedWorldBooks, isKnowledge = false)
                contextTraceSection("世界书 · 已跳过", trace.skippedWorldBooks, isKnowledge = false)
                contextTraceSection("知识库 · 已选", trace.selectedKnowledge, isKnowledge = true)
                contextTraceSection("知识库 · 已跳过", trace.skippedKnowledge, isKnowledge = true)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.contextTraceSection(
    label: String,
    sources: List<ContextTraceSource>,
    isKnowledge: Boolean
) {
    item {
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
        Text("$label (${sources.size})", modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.titleSmall)
    }
    if (sources.isEmpty()) {
        item {
            Text("无", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        items(sources) { source ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (isKnowledge) "知识块 ${source.sourceId}" else "条目 ${source.sourceId}",
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(if (isKnowledge) "知识库：${source.ownerId} · 文档：${source.scope}" else
                            "世界书：${source.ownerId} · 范围：${source.scope}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("原因：${contextTraceReasonLabel(source.reason)} (${source.reason}) · " +
                            "估算 ${source.estimatedTokens} tokens" +
                            (source.lexicalScore?.let { " · 词法分数 $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(source.excerpt, style = MaterialTheme.typography.bodySmall)
                        if (source.excerptTruncated) {
                            Text("节选已截断 · 完整内容 SHA-256：${source.excerptSha256.orEmpty()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun contextTraceReasonLabel(reason: String): String = when (reason) {
    "constant" -> "常驻条目"
    "keyword" -> "关键词命中"
    "budget" -> "预算不足"
    "lexical_match" -> "词法匹配"
    "budget_or_limit" -> "预算或条数限制"
    else -> "来源判定"
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
private fun WebSearchTraceSection(
    title: String,
    values: List<String>,
    error: Boolean = false
) {
    if (values.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        values.forEach { value ->
            Text(
                "• $value",
                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ChatWebSearchTrace.webSearchTraceColor(): Color =
    when {
        running -> McaPrimaryBlue
        success && qualityScore >= 72 -> McaPrimaryBlue
        success -> Color(0xFF0F9D58)
        warnings.isNotEmpty() || conflictWarnings.isNotEmpty() -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

private fun ChatWebSearchTrace.summaryLine(): String {
    val targetCount = (searchedQueries.size + directUrls.size).coerceAtLeast(sourceCount)
    return buildList {
        if (providerLabel.isNotBlank()) add(providerLabel)
        if (targetCount > 0) add(if (running) "计划 ${targetCount} 个目标" else "${targetCount} 个目标")
        if (sourceCount > 0) add("${sourceCount} 个来源")
        if (elapsedMs > 0) add(formatWebSearchElapsed(elapsedMs))
        if (qualityLabel.isNotBlank()) add("质量 $qualityLabel ${qualityScore}/100")
        if (researchConfidenceLabel.isNotBlank()) add("置信度 $researchConfidenceLabel ${researchConfidenceScore}/100")
    }.ifEmpty {
        listOf(message.ifBlank { query.ifBlank { "已记录本轮联网检索" } })
    }.joinToString(" · ")
}

private fun formatWebSearchElapsed(elapsedMs: Long): String =
    if (elapsedMs >= 1000L) {
        "${"%.1f".format(elapsedMs / 1000.0)}s"
    } else {
        "${elapsedMs}ms"
    }

@Composable
private fun WebSearchSourceDetailCard(
    source: ChatSourceReference,
    onOpen: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = source.webSearchTrustColor().copy(alpha = 0.14f),
                    shape = CircleShape
                ) {
                    Text(
                        source.displayTrustLabel(),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = source.webSearchTrustColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    source.displayHostLabel(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "收起来源详情",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                source.title.ifBlank { source.url },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            val reason = source.displayTrustReason()
            if (reason.isNotBlank() || source.provider.isNotBlank()) {
                Text(
                    listOf(source.provider, reason)
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (source.snippet.isNotBlank()) {
                Text(
                    source.snippet,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                source.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("MCA 来源链接", source.url))
                            )
                            Toast.makeText(context, "已复制来源链接", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("复制链接")
                }
                Button(onClick = onOpen) {
                    Text("应用内打开")
                }
            }
        }
    }
}

private fun List<ChatSourceReference>.webSearchSourceSummaryLabel(): String {
    if (isEmpty()) return "联网来源"
    val summary = map { it.displayTrustLabel() }
        .filter { it.isNotBlank() }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(2)
        .joinToString(" · ") { "${it.key} ${it.value}" }
    return buildString {
        append("联网来源")
        append(" · ")
        append(size)
        append(" 个")
        if (summary.isNotBlank()) {
            append(" · ")
            append(summary)
        }
    }
}

private fun ChatSourceReference.displayHostLabel(): String =
    hostLabel.ifBlank {
        runCatching { Uri.parse(url).host?.removePrefix("www.").orEmpty() }
            .getOrDefault("")
            .ifBlank { url.removePrefix("https://").removePrefix("http://").substringBefore("/") }
    }

private fun ChatSourceReference.displayTrustLabel(): String =
    trustLabel.ifBlank {
        val host = displayHostLabel().lowercase()
        when {
            provider == "安全拦截" -> "安全拦截"
            host == "github.com" || host.endsWith(".github.com") -> "代码仓库"
            host == "huggingface.co" || host == "modelscope.cn" -> "模型社区"
            host == "arxiv.org" || host.endsWith(".arxiv.org") -> "学术论文"
            host.contains("docs") || url.contains("/docs", ignoreCase = true) || url.contains("/developer", ignoreCase = true) -> "开发者文档"
            host.contains("reddit") || host.contains("stackoverflow") || host.contains("zhihu") -> "社区讨论"
            else -> "普通网页"
        }
    }

private fun ChatSourceReference.displayTrustReason(): String =
    trustReason.ifBlank {
        when (displayTrustLabel()) {
            "安全拦截" -> "受限地址未读取"
            "代码仓库" -> "开发者仓库"
            "模型社区" -> "模型托管与下载站点"
            "学术论文" -> "论文或预印本来源"
            "开发者文档" -> "文档、指南或开发者资料"
            "社区讨论" -> "论坛、问答或社交讨论"
            else -> ""
        }
    }

@Composable
private fun ChatSourceReference.webSearchTrustColor(): Color =
    when (displayTrustLabel()) {
        "官方/一手", "开发者文档", "模型社区", "代码仓库" -> McaPrimaryBlue
        "学术论文" -> Color(0xFF5E6AD2)
        "社区讨论", "媒体报道" -> Color(0xFF0F9D58)
        "安全拦截" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
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

@Composable
private fun PagedPlainMessageText(
    content: String,
    color: Color,
    style: TextStyle
) {
    var visibleCharacters by rememberSaveable { mutableStateOf(MESSAGE_RENDER_PAGE_CHARS) }
    val visibleContent = remember(content, visibleCharacters) {
        content.safePrefix(visibleCharacters)
    }
    val displayContent = remember(visibleContent) { wrapForDisplay(visibleContent) }
    Column {
        Text(
            text = displayContent,
            color = color,
            style = style,
            softWrap = true
        )
        if (visibleContent.length < content.length) {
            TextButton(
                onClick = {
                    visibleCharacters = (visibleCharacters + MESSAGE_RENDER_PAGE_CHARS)
                        .coerceAtMost(content.length)
                }
            ) {
                Text("显示更多（${visibleContent.length}/${content.length}）")
            }
        }
    }
}

@Composable
private fun CharacterHtmlBlocksContent(blocks: List<CharacterHtmlBlock>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is CharacterHtmlBlock.Text -> {
                    if (block.content.isNotBlank()) {
                        PagedAssistantRichText(block.content)
                    }
                }
                is CharacterHtmlBlock.Details -> CharacterHtmlDetails(
                    block = block,
                    key = index
                )
            }
        }
    }
}

@Composable
private fun CharacterHtmlDetails(
    block: CharacterHtmlBlock.Details,
    key: Int
) {
    var expanded by rememberSaveable(key, block.title) { mutableStateOf(block.initiallyOpen) }
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = colors.surfaceVariant.copy(alpha = 0.28f),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.7f))
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics(mergeDescendants = true) {
                        stateDescription = if (expanded) "已展开" else "已收起"
                    }
                    .clickable(
                        role = SemanticsRole.Button,
                        onClickLabel = if (expanded) "收起状态栏" else "展开状态栏"
                    ) { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = block.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起状态栏" else "展开状态栏",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(100)) + expandVertically(tween(160)),
                exit = fadeOut(tween(80)) + shrinkVertically(tween(120))
            ) {
                Column {
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
                    if (block.content.isNotBlank()) {
                        PagedAssistantRichText(
                            content = block.content,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PagedAssistantRichText(content: String, modifier: Modifier = Modifier) {
    var visibleCharacters by rememberSaveable { mutableStateOf(MESSAGE_RENDER_PAGE_CHARS) }
    val visibleContent = remember(content, visibleCharacters) {
        content.safePrefix(visibleCharacters)
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistantRichText(visibleContent, fullContent = content)
        if (visibleContent.length < content.length) {
            TextButton(
                onClick = {
                    visibleCharacters = (visibleCharacters + MESSAGE_RENDER_PAGE_CHARS)
                        .coerceAtMost(content.length)
                }
            ) {
                Text("显示更多（${visibleContent.length}/${content.length}）")
            }
        }
    }
}

@Composable
private fun AssistantRichText(content: String, fullContent: String = content) {
    val blocks = remember(content, fullContent) { parseMarkdownBlocks(content, fullContent) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Code -> CodeBlock(block.language, block.code, block.fileName, block.copyCode)
                is MarkdownBlock.Heading -> HeadingBlock(block.text, block.level)
                is MarkdownBlock.Paragraph -> ParagraphBlock(block.text)
                is MarkdownBlock.BulletList -> ListBlock(block.items, ordered = false)
                is MarkdownBlock.NumberedList -> NumberedListBlock(block.items)
                is MarkdownBlock.Quote -> QuoteBlock(block.text)
                is MarkdownBlock.Table -> TableBlock(block.rows)
                MarkdownBlock.Divider -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun HeadingBlock(text: String, level: Int) {
    Text(
        text = inlineMarkdownText(text),
        color = MaterialTheme.colorScheme.onSurface,
        style = if (level <= 2) {
            MaterialTheme.typography.titleMedium.copy(lineHeight = 23.sp)
        } else {
            MaterialTheme.typography.titleSmall.copy(lineHeight = 21.sp)
        },
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        softWrap = true
    )
}

@Composable
private fun ParagraphBlock(text: String) {
    Text(
        text = inlineMarkdownText(text),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 15.sp,
            lineHeight = 23.sp
        ),
        modifier = Modifier.fillMaxWidth(),
        softWrap = true
    )
}

@Composable
private fun ListBlock(items: List<String>, ordered: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        items.forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    text = if (ordered) "${index + 1}." else "•",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.widthIn(min = if (ordered) 22.dp else 12.dp)
                )
                Text(
                    text = inlineMarkdownText(item),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                    modifier = Modifier.weight(1f),
                    softWrap = true
                )
            }
        }
    }
}

@Composable
private fun NumberedListBlock(items: List<OrderedMarkdownItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    text = "${item.number}.",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.widthIn(min = 22.dp)
                )
                Text(
                    text = inlineMarkdownText(item.text),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                    modifier = Modifier.weight(1f),
                    softWrap = true
                )
            }
        }
    }
}

@Composable
private fun QuoteBlock(text: String) {
    Row(
        modifier = Modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
        )
        Text(
            text = inlineMarkdownText(text),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
            modifier = Modifier.weight(1f),
            softWrap = true
        )
    }
}

@Composable
private fun CodeBlock(language: String?, code: String, fileName: String? = null, copyCode: String = code) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }
    Surface(
        color = Color(0xFF1F2937),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    listOfNotNull(fileName, language?.ifBlank { null }).joinToString(" · ").ifBlank { "代码" },
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("MCA code", copyCode))
                            )
                            copied = true
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = if (copied) "已复制代码" else "复制代码",
                        modifier = Modifier.size(14.dp),
                        tint = Color.White.copy(alpha = 0.78f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (copied) "已复制" else "复制",
                        color = Color.White.copy(alpha = 0.78f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Text(
                text = code,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            )
        }
    }
}

@Composable
private fun TableBlock(rows: List<List<String>>) {
    if (rows.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.74f)),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        Column {
            rows.forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier.background(
                        if (rowIndex == 0) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.74f)
                        } else {
                            Color.Transparent
                        }
                    )
                ) {
                    row.forEach { cell ->
                        Text(
                            text = inlineMarkdownText(cell),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            ),
                            fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .widthIn(min = 92.dp, max = 190.dp)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            softWrap = true
                        )
                    }
                }
                if (rowIndex != rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f))
                }
            }
        }
    }
}

@Composable
private fun inlineMarkdownText(value: String): AnnotatedString {
    val codeColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.52f)
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(value, codeColor, codeBackground, linkColor) {
        buildInlineMarkdown(value, codeColor, codeBackground, linkColor)
    }
}

private sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Heading(val text: String, val level: Int) : MarkdownBlock()
    data class BulletList(val items: List<String>) : MarkdownBlock()
    data class NumberedList(val items: List<OrderedMarkdownItem>) : MarkdownBlock()
    data class Quote(val text: String) : MarkdownBlock()
    data class Code(
        val language: String?,
        val code: String,
        val fileName: String? = null,
        val copyCode: String = code
    ) : MarkdownBlock()
    data class Table(val rows: List<List<String>>) : MarkdownBlock()
    data object Divider : MarkdownBlock()
}

private fun parseMarkdownBlocks(content: String, fullContent: String = content): List<MarkdownBlock> {
    val completeCode = parseChatMarkdownSource(fullContent).filterIsInstance<ChatMarkdownSource.Code>()
    var codeIndex = 0
    return parseChatMarkdownSource(content).flatMap { source ->
        when (source) {
            is ChatMarkdownSource.Code -> {
                val complete = completeCode.getOrNull(codeIndex++)
                listOf(MarkdownBlock.Code(source.language, source.code, source.fileName, complete?.code ?: source.code))
            }
            is ChatMarkdownSource.Text -> parsePlainMarkdownBlocks(source.raw)
        }
    }
}

private fun parsePlainMarkdownBlocks(content: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val listItems = mutableListOf<String>()
    val orderedListItems = mutableListOf<OrderedMarkdownItem>()
    var listOrdered = false
    val plainCodeLines = mutableListOf<String>()
    val tableLines = mutableListOf<String>()

    fun flushParagraph() {
        val value = paragraph.joinToString("")
        if (value.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(value)
        }
        paragraph.clear()
    }

    fun flushList() {
        if (listOrdered && orderedListItems.isNotEmpty()) {
            blocks += MarkdownBlock.NumberedList(orderedListItems.toList())
        } else if (!listOrdered && listItems.isNotEmpty()) {
            blocks += MarkdownBlock.BulletList(listItems.toList())
        }
        listItems.clear()
        orderedListItems.clear()
    }

    fun flushPlainCode() {
        if (plainCodeLines.isEmpty()) return
        val nonBlank = plainCodeLines.filter { it.isNotBlank() }
        val shouldRenderAsCode = nonBlank.size >= 2 || nonBlank.any { it.isStrongCodeLine() }
        if (shouldRenderAsCode) {
            blocks += MarkdownBlock.Code(
                language = detectCodeLanguage(nonBlank),
                code = plainCodeLines.joinToString("")
            )
        } else {
            paragraph += plainCodeLines.joinToString("")
        }
        plainCodeLines.clear()
    }

    fun flushTable() {
        if (tableLines.isEmpty()) return
        val rows = tableLines
            .filterNot { it.isTableSeparatorLine() }
            .map { it.toTableCells() }
            .filter { it.isNotEmpty() }
        if (rows.isNotEmpty()) {
            blocks += MarkdownBlock.Table(rows)
        }
        tableLines.clear()
    }

    chatSourceLines(content).forEach { rawLine ->
        val line = rawLine.removeSuffix("\n").removeSuffix("\r")
        if (line.isTableLine()) {
            flushPlainCode()
            flushParagraph()
            flushList()
            tableLines += line
        } else if (line.isBlank()) {
            flushTable()
            if (plainCodeLines.isNotEmpty()) {
                plainCodeLines += rawLine
            } else {
                paragraph += rawLine
                flushParagraph()
                flushList()
            }
        } else if (line.isHorizontalRule()) {
            flushTable()
            flushPlainCode()
            flushParagraph()
            flushList()
            blocks += MarkdownBlock.Divider
        } else if (line.isLikelyCodeLine(plainCodeLines.isNotEmpty())) {
            flushTable()
            flushParagraph()
            flushList()
            plainCodeLines += rawLine
        } else if (line.trimStart().startsWith("#")) {
            flushTable()
            flushPlainCode()
            flushParagraph()
            flushList()
            val trimmed = line.trimStart()
            val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 4)
            blocks += MarkdownBlock.Heading(trimmed.drop(level).trim(), level)
        } else if (line.trimStart().startsWith(">")) {
            flushTable()
            flushPlainCode()
            flushParagraph()
            flushList()
            blocks += MarkdownBlock.Quote(line.trimStart().removePrefix(">").trim())
        } else if (line.isBulletLine()) {
            flushTable()
            flushPlainCode()
            flushParagraph()
            val ordered = line.isNumberedLine()
            if ((listItems.isNotEmpty() || orderedListItems.isNotEmpty()) && listOrdered != ordered) flushList()
            listOrdered = ordered
            if (ordered) {
                val fallback = orderedListItems.lastOrNull()?.number?.plus(1) ?: 1
                parseOrderedMarkdownItem(line, fallback)?.let { orderedListItems += it }
            } else {
                listItems += stripListMarker(line)
            }
        } else {
            flushTable()
            flushPlainCode()
            flushList()
            paragraph += rawLine
        }
    }
    flushTable()
    flushPlainCode()
    flushParagraph()
    flushList()
    return blocks.ifEmpty { listOf(MarkdownBlock.Paragraph(content)) }
}


private fun String.isBulletLine(): Boolean {
    val trimmed = trimStart()
    return trimmed.startsWith("- ") ||
        trimmed.startsWith("* ") ||
        trimmed.matches(Regex("""\d{1,4}[.)、](?=\s|[^\d\s])\s*.+"""))
}

private fun String.isNumberedLine(): Boolean =
    isOrderedMarkdownLine()

private fun stripListMarker(value: String): String {
    val trimmed = value.trimStart()
    if (trimmed.isOrderedMarkdownLine()) {
        return stripOrderedMarkdownMarker(trimmed)
    }
    return trimmed
        .removePrefix("- ")
        .removePrefix("* ")
}

private fun String.isHorizontalRule(): Boolean {
    val trimmed = trim()
    return trimmed.length >= 3 && trimmed.all { it == '-' || it == '_' || it == '*' || it.isWhitespace() }
}

private fun String.isTableLine(): Boolean {
    val trimmed = trim()
    if (!trimmed.contains("|")) return false
    if (trimmed.count { it == '|' } < 2) return false
    return trimmed.startsWith("|") || trimmed.endsWith("|")
}

private fun String.isTableSeparatorLine(): Boolean {
    val compact = trim().trim('|').replace(" ", "")
    return compact.isNotBlank() && compact.all { it == '-' || it == ':' || it == '|' }
}

private fun String.toTableCells(): List<String> =
    trim()
        .trim('|')
        .split('|')
        .map { it.trim() }
        .filter { it.isNotBlank() }

private fun String.isLikelyCodeLine(continuingCodeBlock: Boolean): Boolean {
    val trimmed = trim()
    if (trimmed.isBlank()) return false
    if (startsWith("    ") || startsWith("\t")) return true
    if (continuingCodeBlock && (
            trimmed == "}" ||
                trimmed == "};" ||
                trimmed.startsWith("}") ||
                trimmed.startsWith("else") ||
                trimmed.startsWith("elif") ||
                trimmed.startsWith("except") ||
                trimmed.startsWith("finally") ||
                trimmed.startsWith("catch") ||
                trimmed.startsWith("//") ||
                trimmed.startsWith("# ")
            )
    ) {
        return true
    }
    return trimmed.isStrongCodeLine() ||
        CODE_KEYWORD_PATTERN.containsMatchIn(trimmed) ||
        CODE_CALL_PATTERN.containsMatchIn(trimmed)
}

private fun String.isStrongCodeLine(): Boolean {
    val trimmed = trim()
    if (trimmed.length < 2) return false
    // Arithmetic tables are common code-like output from small local models.
    // Recognize both ASCII and Chinese multiplication signs so each row keeps
    // its own line instead of being merged into one paragraph.
    if (ARITHMETIC_EXPRESSION_PATTERN.matches(trimmed)) return true
    if (CODE_DECLARATION_PATTERN.containsMatchIn(trimmed)) return true
    if (CODE_KEYWORD_PATTERN.containsMatchIn(trimmed) && (trimmed.endsWith(":") || trimmed.endsWith("{"))) return true
    if (trimmed.startsWith("#include") || trimmed.startsWith("using namespace")) return true
    if (trimmed.startsWith("<") && trimmed.endsWith(">") && "/" in trimmed) return true
    if (trimmed.endsWith(";") && !trimmed.contains("；")) return true
    if (trimmed == "{" || trimmed == "}" || trimmed == "};") return true
    return CODE_ASSIGNMENT_PATTERN.containsMatchIn(trimmed) && !trimmed.any { it.isCjkLike() }
}

private fun detectCodeLanguage(lines: List<String>): String? {
    val sample = lines.joinToString("\n") { it.trim() }
    val lower = sample.lowercase()
    return when {
        Regex("""(?m)^(def|from|import|elif|except|print)\b""").containsMatchIn(sample) -> "python"
        Regex("""(?m)^(fun|val|var|package|override|private|suspend)\b""").containsMatchIn(sample) -> "kotlin"
        Regex("""(?m)^(const|let|function|export|interface|type)\b""").containsMatchIn(sample) -> "typescript"
        "#include" in sample || "std::" in sample -> "cpp"
        Regex("""(?m)^(public|private|protected)\s+(class|static|void|int|String)\b""").containsMatchIn(sample) -> "java"
        Regex("""(?m)^(select|insert|update|delete|create|alter)\b""", RegexOption.IGNORE_CASE).containsMatchIn(sample) -> "sql"
        "<html" in lower || "</" in sample -> "html"
        else -> null
    }
}

private val CODE_DECLARATION_PATTERN = Regex(
    pattern = """^(def|class|fun|function|const|let|var|val|public|private|protected|override|interface|type|struct|enum|async\s+def)\b.*"""
)

private val CODE_KEYWORD_PATTERN = Regex(
    pattern = """^(if|else|elif|for|while|when|switch|case|try|catch|except|finally|with|return|break|continue|await|import|from|package|using|namespace|select|insert|update|delete|create|alter)\b.*""",
    option = RegexOption.IGNORE_CASE
)

private val CODE_CALL_PATTERN = Regex(
    pattern = """^(print|console\.log|System\.out\.println|printf|Log\.[diew]|println)\s*\(.*"""
)

private val CODE_ASSIGNMENT_PATTERN = Regex(
    pattern = """^[A-Za-z_][A-Za-z0-9_.$\[\]]*\s*(=|\+=|-=|\*=|/=|:=)\s*.+"""
)
private val ARITHMETIC_EXPRESSION_PATTERN = Regex(
    pattern = """^\d+\s*[×x*＋+−-]\s*\d+\s*=\s*\d+$"""
)

private fun cleanReasoningForDisplay(value: String): String {
    return value
        .replace("<think>", "", ignoreCase = true)
        .replace("</think>", "", ignoreCase = true)
        .replace("<|think|>", "", ignoreCase = true)
        .replace("<|channel>thought", "", ignoreCase = true)
        .replace("<|channel|>thought", "", ignoreCase = true)
        .replace("<|channel>analysis", "", ignoreCase = true)
        .replace("<|channel|>analysis", "", ignoreCase = true)
        .replace("<channel|>", "", ignoreCase = true)
        .replace("<channel>", "", ignoreCase = true)
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("""[ \t]{2,}"""), " ")
        .replace(Regex("""\n[ \t]+"""), "\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}

private fun reasoningPreview(value: String): String {
    val lines = value
        .lineSequence()
        .map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .take(REASONING_COLLAPSED_MAX_LINES)
        .toList()
    return if (lines.isEmpty()) value.take(REASONING_PREVIEW_MAX_CHARS) else {
        val preview = lines.joinToString("\n")
        if (preview.length <= REASONING_PREVIEW_MAX_CHARS) {
            preview
        } else {
            preview.take(REASONING_PREVIEW_MAX_CHARS).trimEnd()
        }
    }
}

private fun buildInlineMarkdown(
    value: String,
    codeColor: Color,
    codeBackground: Color,
    linkColor: Color
): AnnotatedString = buildAnnotatedString {
    var index = 0

    fun appendPlain(text: String) {
        append(wrapForDisplay(text))
    }

    while (index < value.length) {
        when {
            value.startsWith("[", index) -> {
                val match = MARKDOWN_LINK_PATTERN.find(value, index)
                if (match != null && match.range.first == index) {
                    withStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(wrapForDisplay(match.groupValues[1]))
                    }
                    index = match.range.last + 1
                } else {
                    appendPlain(value[index].toString())
                    index++
                }
            }
            URL_PATTERN.find(value, index)?.range?.first == index -> {
                val match = URL_PATTERN.find(value, index)!!
                withStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline
                    )
                ) {
                    append(wrapForDisplay(match.value))
                }
                index = match.range.last + 1
            }
            value.startsWith("`", index) -> {
                val end = value.indexOf('`', startIndex = index + 1)
                if (end > index + 1) {
                    withStyle(
                        SpanStyle(
                            color = codeColor,
                            background = codeBackground,
                            fontFamily = FontFamily.Monospace
                        )
                    ) {
                        append(wrapForDisplay(value.substring(index + 1, end)))
                    }
                    index = end + 1
                } else {
                    appendPlain(value[index].toString())
                    index++
                }
            }
            value.startsWith("**", index) -> {
                val end = value.indexOf("**", startIndex = index + 2)
                if (end > index + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendPlain(value.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    appendPlain(value[index].toString())
                    index++
                }
            }
            value.startsWith("__", index) -> {
                val end = value.indexOf("__", startIndex = index + 2)
                if (end > index + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendPlain(value.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    appendPlain(value[index].toString())
                    index++
                }
            }
            else -> {
                val next = listOf(
                    value.indexOf('`', startIndex = index).takeIf { it >= 0 } ?: value.length,
                    value.indexOf("**", startIndex = index).takeIf { it >= 0 } ?: value.length,
                    value.indexOf("__", startIndex = index).takeIf { it >= 0 } ?: value.length,
                    value.indexOf("[", startIndex = index).takeIf { it >= 0 } ?: value.length,
                    URL_PATTERN.find(value, index)?.range?.first ?: value.length
                ).min()
                appendPlain(value.substring(index, next))
                index = next
            }
        }
    }
}

private val MARKDOWN_LINK_PATTERN = Regex("""\[(.+?)]\((https?://[^)\s]+)\)""")
private val URL_PATTERN = Regex("""https?://[^\s)）]+""")
private const val REASONING_COLLAPSED_MAX_LINES = 4
private const val REASONING_PREVIEW_MAX_CHARS = 520
private val REASONING_LEADING_MARKERS = listOf(
    Regex("""(?is)^\s*here(?:'s| is)\s+a\s+thinking\s+process[^:：\n]*[:：]?\s*"""),
    Regex("""(?is)^\s*this\s+is\s+(?:my\s+)?(?:thinking|reasoning)\s+process[^:：\n]*[:：]?\s*"""),
    Regex("""(?im)^\s*thinking\s*process\s*\d*\s*[.:：]?\s*"""),
    Regex("""(?im)^\s*thought\s*process\s*\d*\s*[.:：]?\s*"""),
    Regex("""(?im)^\s*reasoning\s*process\s*\d*\s*[.:：]?\s*"""),
    Regex("""(?im)^\s*analysis\s*\d*\s*[:：]?\s*"""),
    Regex("""(?m)^\s*(思考过程|推理过程|分析过程)\s*\d*\s*[:：.]?\s*""")
)
private val REASONING_NUMBERED_STEP_PATTERN = Regex(
    pattern = """(?im)^\s*\d+\.\s*\*\*(analyze|determine|check|identify|construct|decide|consider|reason)[^*]{0,96}\*\*\s*[:：]?\s*"""
)
private val REASONING_BOLD_STEP_PATTERN = Regex(
    pattern = """(?im)^\s*\*\*(analyze|determine|check|identify|construct|decide|consider|reason)[^*]{0,96}\*\*\s*[:：]?\s*"""
)
private val ENGLISH_REASONING_SCAFFOLD_PREFIXES = listOf(
    "* user asks",
    "* role:",
    "* system requirements",
    "* default response language",
    "* style:",
    "* specific thinking pattern",
    "* constraint checklist",
    "* output format",
    "* determine the role",
    "* analyze the request",
    "* context:",
    "* question:"
)

private val ENGLISH_REASONING_SCAFFOLD_KEYWORDS = listOf(
    "the user is asking",
    "user asks",
    "identify core",
    "identity constraints",
    "determine persona",
    "determine the role",
    "formulate the answer",
    "final review",
    "self-correction",
    "system prompt",
    "system instruction",
    "system requirements",
    "default response language",
    "output format",
    "constraint checklist",
    "role:",
    "question:",
    "context:",
    "language:",
    "style:",
    "developer:",
    "nature:",
    "must use",
    "use /think",
    "thinking mode is open"
)

private val REASONING_SCAFFOLD_SEGMENT_SPLIT = Regex(
    pattern = """(?=(?:^|\s)(?:\d+\.\s*)?(?:\*\*)?(?:User|Role|Question|Context|Language|Style|Constraint|Constraints|System|Output|Identify|Determine|Formulate|Final Review|Self-Correction|Developer|Nature|Type)\b)""",
    option = RegexOption.IGNORE_CASE
)

private fun wrapForDisplay(value: String): String = value

private fun String.safePrefix(maxCharacters: Int): String {
    if (length <= maxCharacters) return this
    var end = maxCharacters.coerceAtLeast(0)
    if (end in 1 until length && this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) {
        end--
    }
    return substring(0, end)
}

private fun Char.isCjkLike(): Boolean =
    this in '\u4E00'..'\u9FFF' ||
        this in '\u3400'..'\u4DBF' ||
        this in '\u3040'..'\u30FF' ||
        this in '\uAC00'..'\uD7AF'

private const val MESSAGE_RENDER_PAGE_CHARS = 16_384
internal const val STREAMING_SCROLL_CHAR_STEP = 512