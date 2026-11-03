package com.muyuchat.mca.dsh.overlay

import android.graphics.Bitmap
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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

/**
 * The live state of one attached overlay window.
 *
 * A plain state holder instead of a ViewModel: the window is owned by a started Service that has no
 * ViewModelStore of its own, and every mutation already happens on the main thread (the service
 * marshals it), so [mutableStateOf] is the whole mechanism needed for recomposition.
 */
internal class DshOverlayUiState {
    var mascot by mutableStateOf(McaMascot.WHALE)
    var mood by mutableStateOf(McaMascotMood.IDLE)
    var markSizeDp by mutableStateOf(56)
    var customMark by mutableStateOf<Bitmap?>(null)
    var bubble by mutableStateOf<String?>(null)
    var bubbleLines by mutableStateOf(3)

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
 * The one overlay surface: mascot mark, optional reply bubble next to it, optional status panel.
 *
 * Gestures are split over two pointer scopes on purpose: the drag scope consumes movement after the
 * touch slop, and the tap scope only fires when nothing consumed the gesture. That is how a draggable
 * mark keeps both "tap to open DSH" and "drag to move" without a second hit target.
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
        Column(horizontalAlignment = alignment) {
            if (!state.bubbleBelow) ReplyBubble(state, onBubbleTap)
            Mark(state, onTap, onLongPress, onDrag, onDragEnd)
            if (state.bubbleBelow) ReplyBubble(state, onBubbleTap)
            if (state.panelVisible) StatusPanel(state, alignment)
        }
    }
}

@Composable
private fun ReplyBubble(state: DshOverlayUiState, onBubbleTap: () -> Unit) {
    val text = state.bubble ?: return
    McaMascotSpeechBubble(
        text = text,
        maxWidth = 216.dp,
        maxLines = state.bubbleLines.coerceIn(1, 5),
        modifier = Modifier
            .padding(vertical = 6.dp)
            .clickable(onClick = onBubbleTap)
    )
}

@Composable
private fun Mark(
    state: DshOverlayUiState,
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
