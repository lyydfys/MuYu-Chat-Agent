package com.muyuchat.mca.dsh.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Live state of the top streaming strip.
 *
 * A second window has its own snapshot: the strip may be visible while the ball is not (and vice versa),
 * and the approval card on the strip must not disturb the ball's own bubble slot. The persisted settings
 * live in their own document; this holder only mirrors the applied values for Compose.
 */
internal class DshOverlayStripUiState {
    var visible by mutableStateOf(false)

    /** Already tail-clamped by [DshOverlayStripLogic.lineFor]. */
    var line by mutableStateOf<DshOverlayStripLine?>(null)

    /** Wall-clock start of the live turn for the left elapsed bar; 0 means "no bar yet". */
    var startedAtMs by mutableStateOf(0L)

    /** Set when the turn finished; drives the 6.5 s hold plus the short cross-fade. */
    var fadeStartMs by mutableStateOf<Long?>(null)

    var settings by mutableStateOf(DshOverlayStripSettings())

    /** Text-mode height, recomputed from lines + font scale; the approval card grows instead. */
    var heightDp by mutableStateOf(56)

    /** Pending command approval; when set it replaces the live text inside the strip. */
    var approval by mutableStateOf<DshOverlayApprovalUi?>(null)
}

/**
 * The strip surface itself: one rounded row pinned to the top edge.
 *
 * Visual contract: at most two lines of the newest streamed characters, a 3 dp elapsed-time bar on the
 * left (fills toward 100 %, never spins), and an inline approval card with the masked command, a 30 s
 * countdown and 允许一次 / 拒绝. Tap returns to DSH, long press opens the same settings sheet the ball
 * uses. There is no infinite animation: the only motion is the bar and the end-of-turn fade.
 */
@Composable
internal fun DshOverlayStripContent(
    state: DshOverlayStripUiState,
    overlayUi: DshOverlayUiState,
    actions: DshOverlayWindowActions,
    onTap: () -> Unit,
    onApprovalDecision: (Boolean) -> Unit
) {
    OverlayTheme {
        var panelOpen by remember { mutableStateOf(false) }
        val nowMs = rememberStripClock(state.visible || state.approval != null)
        val alpha = DshOverlayStripLogic.alphaAt(nowMs, state.fadeStartMs)
        val approval = state.approval

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { this.alpha = alpha }
                .semantics {
                    contentDescription = "DSH 顶部流式条。点击返回 DSH，长按打开设置。"
                }
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(16.dp),
                color = stripColor(state.settings),
                tonalElevation = 0.dp,
                shadowElevation = 6.dp,
                border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.14f))
            ) {
                if (approval != null) {
                    StripApprovalRow(
                        approval = approval,
                        nowMs = nowMs,
                        onApprovalDecision = onApprovalDecision
                    )
                } else {
                    StripTextRow(
                        state = state,
                        nowMs = nowMs,
                        onTap = onTap,
                        onLongPress = { panelOpen = !panelOpen }
                    )
                }
            }
            if (panelOpen) {
                SettingsSheet(
                    state = overlayUi,
                    actions = actions,
                    onClosePanel = { panelOpen = false },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun StripTextRow(
    state: DshOverlayStripUiState,
    nowMs: Long,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val line = state.line ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(state.heightDp.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StripProgressBar(
            progress = if (line.active && state.startedAtMs > 0L) {
                DshOverlayStripLogic.progress(nowMs - state.startedAtMs)
            } else if (line.active) {
                0f
            } else {
                1f
            },
            modifier = Modifier.height((state.heightDp - 16).coerceAtLeast(12).dp)
        )
        Text(
            text = line.text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (line.tool) {
                MaterialTheme.colorScheme.primary
            } else {
                stripTextColor(state.settings)
            },
            maxLines = state.settings.lines.coerceIn(
                DshOverlayStripSettings.MIN_LINES,
                DshOverlayStripSettings.MAX_LINES
            ),
            overflow = TextOverflow.Ellipsis,
            lineHeight = 18.sp
        )
    }
}

/** 3 dp elapsed-time bar on the left; fills downward and holds at 100 %, never spins. */
@Composable
private fun StripProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(3.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.16f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(progress.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/**
 * The inline approval card: same data as the ball's card ([DshOverlayApprovalUi]), compressed into one
 * strip row so the decision never competes with a second surface.
 */
@Composable
private fun StripApprovalRow(
    approval: DshOverlayApprovalUi,
    nowMs: Long,
    onApprovalDecision: (Boolean) -> Unit
) {
    val timeout = approval.timeoutMillis.coerceAtLeast(1L)
    val remaining = ((approval.deadlineMillis - nowMs).toFloat() / timeout.toFloat()).coerceIn(0f, 1f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "DSH 想执行一条命令",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            TextButton(onClick = { onApprovalDecision(false) }) { Text("拒绝") }
            TextButton(onClick = { onApprovalDecision(true) }) { Text("允许一次") }
        }
        Text(
            text = approval.commandMasked,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(Color.White.copy(alpha = 0.16f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(remaining)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

private fun stripColor(settings: DshOverlayStripSettings): Color {
    val alpha = settings.opacity.coerceIn(
        DshOverlayStripSettings.MIN_OPACITY,
        DshOverlayStripSettings.MAX_OPACITY
    )
    return when (settings.surface) {
        DshOverlayStripSurface.LIGHT -> Color.White.copy(alpha = alpha)
        DshOverlayStripSurface.DARK -> Color(0xFF202124).copy(alpha = alpha)
        DshOverlayStripSurface.TRANSLUCENT -> Color(0xFF202124).copy(alpha = alpha * 0.62f)
    }
}

private fun stripTextColor(settings: DshOverlayStripSettings): Color =
    if (settings.surface == DshOverlayStripSurface.LIGHT) {
        Color(0xFF202124)
    } else {
        Color(0xFFE8EAED)
    }

/** One 250 ms clock while the strip is on screen; it stops with the window and costs nothing idle. */
@Composable
private fun rememberStripClock(active: Boolean): Long {
    var nowMs by remember(active) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(STRIP_CLOCK_TICK_MILLIS)
        }
    }
    return nowMs
}

private const val STRIP_CLOCK_TICK_MILLIS = 250L
