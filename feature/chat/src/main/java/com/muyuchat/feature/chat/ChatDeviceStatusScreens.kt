package com.muyuchat.feature.chat

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// P9/S16：从 ChatChromeScreens.kt 等值移出的状态栏与设备状态簇。
// 原样逐字迁移，未做行为改动；无新增提升（入口原即 internal，双向扫描零跨区）。

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
        mutableStateOf(
            preferences.getFloat("x_fraction", DEFAULT_FLOATING_X_FRACTION)
                .takeIf { it.isFinite() }
                ?.let { stored ->
                    // Older builds parked the ball at the right edge, where it covered the copy
                    // action. Migrate that stored default left of the action column.
                    if (stored >= 0.97f) DEFAULT_FLOATING_X_FRACTION else stored.coerceIn(0f, 1f)
                }
                ?: DEFAULT_FLOATING_X_FRACTION
        )
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
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(context, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                // Refresh immediately on every foreground entry, then keep the compact ball's
                // necessary memory indicator current. Page overlays never cancel inference,
                // downloads, Local API, or background persistence.
                memorySample = context.readChatDeviceMemorySample()
                temperatureSample = readChatDeviceTemperatureSample(context)
                lastSampleAtMs = System.currentTimeMillis()
                delay(5_000L)
            }
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
        val fontScale = LocalDensity.current.fontScale
        val ballTier = floatingDeviceStatusBallTierForFontScale(
            tier = floatingDeviceStatusBallTier(ballSize.value),
            fontScale = fontScale,
        )
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
