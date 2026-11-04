package com.muyuchat.mca.dsh.overlay

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaMascot
import com.muyuchat.core.designsystem.McaMascotMark
import com.muyuchat.core.designsystem.McaMascotMood
import com.muyuchat.core.designsystem.McaMascotSpeechBubble
import kotlinx.coroutines.delay

/**
 * The live state of one attached overlay window.
 *
 * A plain state holder instead of a ViewModel: the window is owned by a started Service that has no
 * ViewModelStore of its own, and every mutation already happens on the main thread (the service
 * marshals it), so [mutableStateOf] is the whole mechanism needed for recomposition.
 *
 * Bubble content has three sources with a fixed priority: the live tool label, the live stream text,
 * and the finished reply bubble. Only one of them is on screen at a time, so the window never grows
 * two competing bubbles.
 */
internal class DshOverlayUiState {
    var mascot by mutableStateOf(McaMascot.WHALE)
    var mood by mutableStateOf(McaMascotMood.IDLE)
    var markSizeDp by mutableStateOf(56)
    var customMark by mutableStateOf<Bitmap?>(null)
    var bubble by mutableStateOf<String?>(null)
    var bubbleLines by mutableStateOf(3)

    /** A turn is running right now (text or tool); drives the progress arc, not the bubble. */
    var streamActive by mutableStateOf(false)

    /** Live assistant text, already tail-clamped by the reducer. */
    var streamText by mutableStateOf<String?>(null)

    /** Human tool label ("运行命令"), never raw arguments. */
    var streamLabel by mutableStateOf<String?>(null)

    /** Wall-clock start of the live turn; 0 means "no arc" (a tool started before any text). */
    var streamStartedAtMs by mutableStateOf(0L)

    /**
     * Which way the window grows. The mark is anchored to the window edge that faces the screen edge
     * it sits next to, so a wide bubble never pushes the mark sideways and never leaves the screen.
     */
    var alignEnd by mutableStateOf(true)

    /** A bubble shown for a mark near the top has no room above it; it is drawn below instead. */
    var bubbleBelow by mutableStateOf(false)
    var draggable by mutableStateOf(true)
    var panelVisible by mutableStateOf(false)
    var headline by mutableStateOf("")
    var summary by mutableStateOf("")
}

/**
 * The one overlay surface: mascot mark, optional bubble next to it, optional status panel.
 *
 * Gestures are split over two pointer scopes on purpose: the drag scope consumes movement after the
 * touch slop, and the tap scope only fires when nothing consumed the gesture. That is how a draggable
 * mark keeps both "tap to open DSH" and "drag to move" without a second hit target.
 *
 * While a turn is live, one 400 ms clock drives both the progress arc and the tool ellipsis. It is a
 * finite tick, not an infinite transition: the arc advances with elapsed time towards a cap and stops
 * when the turn settles, so an all-day overlay does not keep a spinning animation on screen.
 */
@Composable
internal fun DshOverlayWindowContent(
    state: DshOverlayUiState,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onBubbleTap: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
    OverlayTheme {
        val alignment = if (state.alignEnd) Alignment.End else Alignment.Start
        val nowMs = rememberStreamClock(active = state.streamActive)
        val arcProgress = if (state.streamActive && state.streamStartedAtMs > 0L) {
            ((nowMs - state.streamStartedAtMs).toFloat() / STREAM_PROGRESS_FULL_MILLIS)
                .coerceIn(0f, STREAM_PROGRESS_MAX)
        } else {
            null
        }
        val toolDots = if (state.streamLabel != null) {
            ".".repeat(((nowMs / STREAM_CLOCK_TICK_MILLIS) % 4L).toInt())
        } else {
            ""
        }
        Column(horizontalAlignment = alignment) {
            if (!state.bubbleBelow) ContentBubble(state, toolDots, onBubbleTap)
            Mark(state, arcProgress, onTap, onLongPress, onDrag, onDragEnd)
            if (state.bubbleBelow) ContentBubble(state, toolDots, onBubbleTap)
            if (state.panelVisible) StatusPanel(state, alignment)
        }
    }
}

/**
 * One 400 ms clock while a turn is live; it stops as soon as the stream settles.
 *
 * `remember(active)` restarts it per turn, so the first frame of a new turn already has a plausible
 * timestamp instead of inheriting the previous turn's.
 */
@Composable
private fun rememberStreamClock(active: Boolean): Long {
    var nowMs by remember(active) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(STREAM_CLOCK_TICK_MILLIS)
        }
    }
    return nowMs
}

/**
 * The bubble area, with a fixed priority: tool label → live text → finished reply.
 *
 * Live text keeps its newest characters: the reducer already tail-clamps to 280, and a second tail
 * window keeps the three visible lines on the part that was just produced instead of the opening words
 * the user already read.
 */
@Composable
private fun ContentBubble(state: DshOverlayUiState, toolDots: String, onBubbleTap: () -> Unit) {
    val label = state.streamLabel
    val liveText = state.streamText
    val finished = state.bubble
    val text = when {
        label != null -> "⏺ $label$toolDots"
        liveText != null -> DshOverlayStreamReducer.clampTail(liveText, STREAM_BUBBLE_TAIL_CHARS)
        else -> finished
    } ?: return
    val maxLines = when {
        label != null -> 1
        liveText != null -> state.bubbleLines.coerceIn(1, STREAM_BUBBLE_MAX_LINES)
        else -> state.bubbleLines.coerceIn(1, 5)
    }
    McaMascotSpeechBubble(
        text = text,
        maxWidth = 216.dp,
        maxLines = maxLines,
        modifier = Modifier
            .padding(vertical = 6.dp)
            .clickable(onClick = onBubbleTap)
    )
}

@Composable
private fun Mark(
    state: DshOverlayUiState,
    arcProgress: Float?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(state.markSizeDp.dp)
            .semantics {
                contentDescription = "DSH 悬浮窗。点击打开 DSH，长按查看运行状态。"
                role = Role.Button
            }
            .pointerInput(state.draggable) {
                if (!state.draggable) return@pointerInput
                detectDragGestures(
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() }
                ) { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val bitmap = state.customMark
        if (state.mascot == McaMascot.CUSTOM && bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
        } else {
            McaMascotMark(
                // A CUSTOM mark whose file is missing/undecodable falls back to the vector whale
                // instead of rendering an empty target.
                mascot = if (state.mascot == McaMascot.CUSTOM) McaMascot.WHALE else state.mascot,
                size = state.markSizeDp.dp,
                mood = state.mood
            )
        }
        if (arcProgress != null) StreamProgressArc(arcProgress, modifier = Modifier.matchParentSize())
    }
}

/**
 * A thin progress ring over the badge.
 *
 * Elapsed-time progress rather than a spinner: a spinner means "unknown wait" and would run forever,
 * while a filling ring shows real elapsed time and settles at a complete circle so a long turn never
 * looks like a stuck/looping animation.
 */
@Composable
private fun StreamProgressArc(progress: Float, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        val stroke = (size.minDimension * 0.045f).coerceAtLeast(1.5f)
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * progress.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke)
        )
    }
}

@Composable
private fun StatusPanel(state: DshOverlayUiState, alignment: Alignment.Horizontal) {
    Surface(
        modifier = Modifier
            .padding(vertical = 6.dp)
            .widthIn(max = 216.dp),
        shape = RoundedCornerShape(10.dp),
        color = OverlayPalette.panel,
        shadowElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = state.headline,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                textAlign = if (alignment == Alignment.End) TextAlign.End else TextAlign.Start
            )
            Text(
                text = state.summary,
                color = Color(0xFFB8C4D0),
                style = MaterialTheme.typography.labelSmall,
                textAlign = if (alignment == Alignment.End) TextAlign.End else TextAlign.Start,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * A minimal Material theme for the overlay window.
 *
 * The shared `McaTheme` cannot be used here: it installs system-bar colours by casting the view
 * context to an `Activity`, and a `TYPE_APPLICATION_OVERLAY` window lives in a Service context.
 * These are the exact MCA palette values for the roles this surface actually paints (the authority
 * is `McaTheme.kt`); nothing new is introduced beyond what the mark and the bubble read.
 */
@Composable
private fun OverlayTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF8AB4F8),
            secondary = Color(0xFF89B5F8),
            surface = Color(0xFF202124),
            onSurface = Color(0xFFE8EAED),
            outlineVariant = Color(0xFF3C4043)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF1A73E8),
            secondary = Color(0xFF185ABC),
            surface = Color.White,
            onSurface = Color(0xFF202124),
            outlineVariant = Color(0xFFDADCE0)
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Panel colours kept in one place; they match the previous window's panel tone. */
private object OverlayPalette {
    val panel = Color(0xE6172433)
}

/** One tick of the live clock; also the tool-ellipsis step. */
private const val STREAM_CLOCK_TICK_MILLIS = 400L

/** The arc fills from 0 % to 100 % over this long, then holds a complete ring instead of spinning. */
private const val STREAM_PROGRESS_FULL_MILLIS = 30_000f

private const val STREAM_PROGRESS_MAX = 1f

/** Visible characters of live text in the small bubble; the tail is what the user has not read. */
private const val STREAM_BUBBLE_TAIL_CHARS = 160

/** Live text wraps at most this many lines, whatever the user's finished-bubble setting is. */
private const val STREAM_BUBBLE_MAX_LINES = 3
