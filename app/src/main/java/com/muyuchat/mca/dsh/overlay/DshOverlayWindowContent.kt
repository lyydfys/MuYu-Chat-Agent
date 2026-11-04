package com.muyuchat.mca.dsh.overlay

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The three animation moods of the ball; no mascot, no artwork — just the DSH whale ball. */
internal enum class DshOverlayBallMood {
    /** Calm breathing: one soft pulse per [BREATH_IDLE_PERIOD_MILLIS]. */
    IDLE,

    /** DSH is working: the same pulse, faster. No second animation is added. */
    WORKING,

    /** A reply just landed: one short bounce, then back to idle/working. */
    REPLIED
}

/** Which overlay panel is open under the ball. At most one at a time. */
internal enum class DshOverlayPanel { NONE, ACTIONS, SETTINGS }

/**
 * The one pending command-approval card, as the window renders it.
 *
 * [commandMasked] is produced by the feature-side masking rule, so the full command never enters this
 * file. [deadlineMillis] drives the countdown bar; the gate also enforces the same deadline, so a card
 * that lingers after it expired cannot authorise anything.
 */
internal data class DshOverlayApprovalUi(
    val id: Long,
    val commandMasked: String,
    val deadlineMillis: Long,
    val timeoutMillis: Long
)

/**
 * Every intent the overlay window can emit.
 *
 * Kept as an enum with a pure [perform] mapper so the two-row action panel and the inline settings sheet
 * can be asserted on the JVM with a recording fake, without Compose, a Service or a device.
 */
internal enum class DshOverlayActionId {
    RETURN_TO_DSH,
    STOP_BACKGROUND,
    TOGGLE_NOTIFY_REPLIES,
    CYCLE_BUBBLE_LINES,
    TOGGLE_STREAMING,
    TOGGLE_DRAGGABLE,
    TOGGLE_COMMAND_APPROVAL
}

/** The only side effects the window is allowed to ask for; implemented by the service. */
internal interface DshOverlayWindowActions {
    fun returnToDsh()

    fun stopBackground()

    fun updateSettings(transform: (DshOverlaySettings) -> DshOverlaySettings)
}

/** Applies one window intent through [actions]. Pure; every branch is one documented side effect. */
internal fun DshOverlayActionId.perform(actions: DshOverlayWindowActions) {
    when (this) {
        DshOverlayActionId.RETURN_TO_DSH -> actions.returnToDsh()
        DshOverlayActionId.STOP_BACKGROUND -> actions.stopBackground()
        DshOverlayActionId.TOGGLE_NOTIFY_REPLIES ->
            actions.updateSettings { it.copy(notifyReplies = !it.notifyReplies) }

        DshOverlayActionId.CYCLE_BUBBLE_LINES ->
            actions.updateSettings { it.copy(bubbleLines = nextBubbleLines(it.bubbleLines)) }

        DshOverlayActionId.TOGGLE_STREAMING ->
            actions.updateSettings { it.copy(streamingEnabled = !it.streamingEnabled) }

        DshOverlayActionId.TOGGLE_DRAGGABLE ->
            actions.updateSettings { it.copy(draggable = !it.draggable) }

        DshOverlayActionId.TOGGLE_COMMAND_APPROVAL ->
            actions.updateSettings { it.copy(commandApprovalEnabled = !it.commandApprovalEnabled) }
    }
}

/** Cycles 0 → 1 → … → [DshOverlaySettings.MAX_BUBBLE_LINES] → 0; 0 means "no bubble". */
internal fun nextBubbleLines(current: Int): Int =
    (current.coerceIn(0, DshOverlaySettings.MAX_BUBBLE_LINES) + 1) %
        (DshOverlaySettings.MAX_BUBBLE_LINES + 1)

/**
 * The live state of one attached overlay window.
 *
 * A plain state holder instead of a ViewModel: the window is owned by a started Service that has no
 * ViewModelStore of its own, and every mutation already happens on the main thread (the service marshals
 * it), so `mutableStateOf` is the whole mechanism needed for recomposition.
 *
 * Bubble content has three sources with a fixed priority: the live tool label, the live stream text, and
 * the finished reply bubble. Only one of them is on screen at a time.
 */
internal class DshOverlayUiState {
    var mood by mutableStateOf(DshOverlayBallMood.IDLE)
    var markSizeDp by mutableStateOf(DshOverlaySettings.DEFAULT_MARK_SIZE_DP)
    var bubble by mutableStateOf<String?>(null)
    var bubbleLines by mutableStateOf(DshOverlaySettings.DEFAULT_BUBBLE_LINES)
    var draggable by mutableStateOf(true)
    var notifyReplies by mutableStateOf(true)
    var streamingEnabled by mutableStateOf(true)

    /** Mirror of the "命令需要确认" preference, shown in the inline settings sheet. */
    var commandApprovalEnabled by mutableStateOf(DshOverlaySettings.DEFAULT_COMMAND_APPROVAL_ENABLED)

    /** A turn is running right now (text or tool); drives the progress ring, not the bubble. */
    var streamActive by mutableStateOf(false)

    /** Live assistant text, already tail-clamped by the reducer. */
    var streamText by mutableStateOf<String?>(null)

    /** Human tool label ("运行命令"), never raw arguments. */
    var streamLabel by mutableStateOf<String?>(null)

    /** Wall-clock start of the live turn; 0 means "no ring" (a tool started before any text). */
    var streamStartedAtMs by mutableStateOf(0L)

    /** One short line under the ball, e.g. why returning to DSH failed. */
    var notice by mutableStateOf<String?>(null)

    /** DSH runtime projection shown at the bottom of the settings sheet. */
    var headline by mutableStateOf("")
    var summary by mutableStateOf("")

    /** Which panel the ball currently shows. */
    var panel by mutableStateOf(DshOverlayPanel.NONE)

    /**
     * A dangerous command waiting for a decision, if any.
     *
     * Priority above every other content slot: while this is set the ball shows the approval card and no
     * bubble/tool/stream text, so the user can never answer a different question than the one on screen.
     */
    var approval by mutableStateOf<DshOverlayApprovalUi?>(null)

    /**
     * Which way the window grows. The ball is anchored to the window edge that faces the screen edge it
     * sits next to, so a wide bubble never pushes the ball sideways and never leaves the screen.
     */
    var alignEnd by mutableStateOf(true)

    /** A bubble shown for a ball near the top has no room above it; it is drawn below instead. */
    var bubbleBelow by mutableStateOf(false)
}

/**
 * The one overlay surface: the DSH whale ball, its bubble, and whichever panel is open.
 *
 * Visual contract: the ball is the same control as the in-app DSH floating ball — a white disc with the
 * DeepSeek whale glyph, a hairline border and a soft breathing halo. It breathes on one
 * [rememberInfiniteTransition] (scale 0.96↔1.04); the working mood only shortens the period, and a
 * finished reply adds a single bounce. No other animation runs, so an always-on window stays cheap.
 *
 * Gestures: tap toggles the action panel, long press opens the settings sheet, drag moves the window
 * (position persisted by the service). Streaming text and its progress ring are unchanged.
 */
@Composable
internal fun DshOverlayWindowContent(
    state: DshOverlayUiState,
    actions: DshOverlayWindowActions,
    onBallTap: () -> Unit,
    onBallLongPress: () -> Unit,
    onClosePanel: () -> Unit,
    onApprovalDecision: (Boolean) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
    OverlayTheme {
        val alignment = if (state.alignEnd) Alignment.End else Alignment.Start
        val nowMs = rememberStreamClock(state.streamActive || state.approval != null)
        val working = state.mood == DshOverlayBallMood.WORKING
        val breathPeriod = if (working) BREATH_WORKING_PERIOD_MILLIS else BREATH_IDLE_PERIOD_MILLIS
        val transition = rememberInfiniteTransition(label = "dsh-overlay-ball")
        val breath by transition.animateFloat(
            initialValue = BREATH_MIN_SCALE,
            targetValue = BREATH_MAX_SCALE,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = breathPeriod, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dsh-overlay-breath"
        )
        val halo by transition.animateFloat(
            initialValue = HALO_MIN_ALPHA,
            targetValue = HALO_MAX_ALPHA,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = breathPeriod, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dsh-overlay-halo"
        )
        val arcProgress = if (state.streamActive && state.streamStartedAtMs > 0L) {
            ((nowMs - state.streamStartedAtMs).toFloat() / STREAM_PROGRESS_FULL_MILLIS)
                .coerceIn(0f, 1f)
        } else {
            null
        }
        val toolDots = if (state.streamLabel != null) {
            ".".repeat(((nowMs / STREAM_CLOCK_TICK_MILLIS) % 4L).toInt())
        } else {
            ""
        }
        Column(horizontalAlignment = alignment) {
            if (!state.bubbleBelow) ContentSlot(state, toolDots, nowMs, onApprovalDecision)
            Ball(
                state = state,
                breath = breath,
                halo = halo,
                arcProgress = arcProgress,
                onTap = onBallTap,
                onLongPress = onBallLongPress,
                onDrag = onDrag,
                onDragEnd = onDragEnd
            )
            if (state.bubbleBelow) ContentSlot(state, toolDots, nowMs, onApprovalDecision)
            state.notice?.let { notice ->
                InfoSurface(text = notice, modifier = Modifier.padding(vertical = 6.dp))
            }
            when (state.panel) {
                DshOverlayPanel.ACTIONS -> ActionPanel(
                    actions = actions,
                    onClosePanel = onClosePanel,
                    modifier = Modifier.padding(vertical = 6.dp)
                )

                DshOverlayPanel.SETTINGS -> SettingsSheet(
                    state = state,
                    actions = actions,
                    onClosePanel = onClosePanel,
                    modifier = Modifier.padding(vertical = 6.dp)
                )

                DshOverlayPanel.NONE -> Unit
            }
        }
    }
}

/**
 * The content slot above/below the ball, with the approval card at the very top of the priority list:
 * approval > tool label > live text > finished reply.
 */
@Composable
private fun ContentSlot(
    state: DshOverlayUiState,
    toolDots: String,
    nowMs: Long,
    onApprovalDecision: (Boolean) -> Unit
) {
    val approval = state.approval
    if (approval != null) {
        ApprovalCard(
            approval = approval,
            nowMs = nowMs,
            onApprovalDecision = onApprovalDecision,
            modifier = Modifier.padding(vertical = 6.dp)
        )
    } else {
        BubbleArea(state, toolDots)
    }
}

/**
 * "DSH 想执行一条命令": the masked command, the one-shot warning, a 30 s countdown and two buttons.
 *
 * The card is deliberately the only thing this slot can show while it exists: a reply bubble appearing
 * underneath an approval question would invite a tap on the wrong control.
 */
@Composable
private fun ApprovalCard(
    approval: DshOverlayApprovalUi,
    nowMs: Long,
    onApprovalDecision: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val timeoutMs = approval.timeoutMillis.coerceAtLeast(1L)
    val remaining = ((approval.deadlineMillis - nowMs).toFloat() / timeoutMs).coerceIn(0f, 1f)
    Surface(
        modifier = modifier.widthIn(min = 216.dp, max = 300.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 12.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "DSH 想执行一条命令",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = approval.commandMasked,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = APPROVAL_COMMAND_MAX_LINES,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
            Text(
                text = "只允许一次，执行后需要再次确认",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LinearProgressIndicator(
                progress = { remaining },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { onApprovalDecision(false) }) { Text("拒绝") }
                Button(onClick = { onApprovalDecision(true) }) { Text("允许一次") }
            }
        }
    }
}

/**
 * The bubble area, with a fixed priority: tool label → live text → finished reply.
 *
 * Live text keeps its newest characters: the reducer already tail-clamps to 280, and a second tail window
 * keeps the three visible lines on the part that was just produced.
 */
@Composable
private fun BubbleArea(state: DshOverlayUiState, toolDots: String) {
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
        else -> state.bubbleLines.coerceIn(1, DshOverlaySettings.MAX_BUBBLE_LINES)
    }
    BubbleSurface(text = text, maxLines = maxLines, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun BubbleSurface(text: String, maxLines: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(max = 216.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 3.dp
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = maxLines,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

/**
 * The DSH whale ball.
 *
 * Same control as the in-app floating ball (`DshFloatingBall`): white disc, hairline outline, whale glyph
 * tinted with the theme primary. The breathing scale and the halo share one infinite transition, and the
 * reply bounce is a one-shot multiplier owned by the service's mood.
 */
@Composable
private fun Ball(
    state: DshOverlayUiState,
    breath: Float,
    halo: Float,
    arcProgress: Float?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val bounce = if (state.mood == DshOverlayBallMood.REPLIED) REPLIED_BOUNCE_SCALE else 1f
    Box(
        modifier = Modifier
            .size(state.markSizeDp.dp)
            .graphicsLayer {
                scaleX = breath * bounce
                scaleY = breath * bounce
            }
            .semantics {
                contentDescription = "DSH 悬浮球。点击打开操作，长按打开设置，可拖动。"
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
        // Soft breathing halo behind the disc; deliberately very faint so it reads as light, not as a
        // second ring competing with the progress indicator.
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(color = Color.White.copy(alpha = halo), radius = size.minDimension / 2f)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color.White)
                .border(
                    width = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(
                    id = com.muyuchat.feature.dsh.workspace.R.drawable.ic_dsh_whale
                ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(state.markSizeDp.dp * WHALE_GLYPH_RATIO)
            )
        }
        if (arcProgress != null) StreamProgressRing(arcProgress)
    }
}

/**
 * A thin progress ring over the disc.
 *
 * Elapsed-time progress rather than a spinner: a spinner means "unknown wait" and would run forever,
 * while a filling ring shows real elapsed time and settles at a complete circle so a long turn never
 * looks like a stuck/looping animation.
 */
@Composable
private fun StreamProgressRing(progress: Float) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxSize()) {
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
 * A minimal Material theme for the overlay window.
 *
 * The shared `McaTheme` cannot be used here: it installs system-bar colours by casting the view context to
 * an `Activity`, and a `TYPE_APPLICATION_OVERLAY` window lives in a Service context. These are the exact
 * MCA palette values for the roles this surface actually paints (the authority is `McaTheme.kt`).
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

// ---- breathing / layout constants -----------------------------------------------------------

private const val BREATH_IDLE_PERIOD_MILLIS = 2_800
private const val BREATH_WORKING_PERIOD_MILLIS = 1_200
private const val BREATH_MIN_SCALE = 0.96f
private const val BREATH_MAX_SCALE = 1.04f
private const val HALO_MIN_ALPHA = 0.08f
private const val HALO_MAX_ALPHA = 0.18f
private const val REPLIED_BOUNCE_SCALE = 1.08f

/** Whale glyph size relative to the disc; matches the in-app ball's 26 dp inside 48 dp. */
private const val WHALE_GLYPH_RATIO = 0.54f

/** One tick of the live clock; also the tool-ellipsis step. */
private const val STREAM_CLOCK_TICK_MILLIS = 400L

/** The ring fills from 0 % to 100 % over this long, then holds a complete circle instead of spinning. */
private const val STREAM_PROGRESS_FULL_MILLIS = 30_000f

/** Visible characters of live text in the small bubble; the tail is what the user has not read. */
private const val STREAM_BUBBLE_TAIL_CHARS = 160

/** Live text wraps at most this many lines, whatever the finished-bubble setting is. */
private const val STREAM_BUBBLE_MAX_LINES = 3

/** The masked command is short by construction; the card never grows into a wall of text. */
private const val APPROVAL_COMMAND_MAX_LINES = 3
