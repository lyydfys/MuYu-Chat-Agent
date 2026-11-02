package com.muyuchat.feature.dsh.workspace.ui

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.designsystem.mcaPressScale
import com.muyuchat.core.designsystem.rememberMcaHaptics
import com.muyuchat.feature.dsh.workspace.DshModelSwitchPort
import com.muyuchat.feature.dsh.workspace.R
import kotlin.math.roundToInt
import com.muyuchat.core.designsystem.McaIconSize

/**
 * One movable DSH ball that carries both host actions.
 *
 * The official DSH page owns the top-left and top-right corners (its two sidebar toggles), so the
 * host may not park a fixed button in either corner: device R35C proved a corner button covered an
 * official control completely. This ball avoids that by being movable (the user places it, like the
 * chat page's device-status ball) and by being a single 56 dp target instead of two fixed buttons.
 *
 * Interaction:
 *  - tap toggles a small two-action panel (返回 MCA / DSH 控制台), never a full-width bar;
 *  - drag moves the ball anywhere inside the page; the position is persisted per app install;
 *  - the panel is placed above the ball when there is room, otherwise below it, and always stays
 *    inside the page bounds;
 *  - the ball is an overlay only: it does not intercept touches outside itself or its panel.
 *
 * The icon is the DeepSeek whale (same brand vector as the drawer entry) instead of a generic
 * terminal/settings glyph.
 */
@Composable
internal fun DshFloatingBall(
    showConsole: Boolean,
    onExit: () -> Unit,
    onOpenConsole: () -> Unit,
    modelSwitch: DshModelSwitchPort? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current.applicationContext
    val haptics = rememberMcaHaptics()
    val preferences = remember(context) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    var horizontalFraction by rememberSaveable {
        mutableStateOf(
            preferences.getFloat(KEY_X, DEFAULT_X_FRACTION)
                .takeIf { it.isFinite() }
                ?.coerceIn(0f, 1f)
                ?: DEFAULT_X_FRACTION
        )
    }
    var verticalFraction by rememberSaveable {
        mutableStateOf(
            preferences.getFloat(KEY_Y, DEFAULT_Y_FRACTION)
                .takeIf { it.isFinite() }
                ?.coerceIn(0f, 1f)
                ?: DEFAULT_Y_FRACTION
        )
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var pendingModelId by rememberSaveable { mutableStateOf<String?>(null) }

    val panelScale = animateFloatAsState(
        targetValue = if (expanded) 1f else 0.92f,
        animationSpec = McaMotion.press,
        label = "dshFloatingPanelScale"
    )
    val panelAlpha = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = McaMotion.fade(millis = 140),
        label = "dshFloatingPanelAlpha"
    )
    val ballIdleAlpha = animateFloatAsState(
        targetValue = if (expanded) 1f else 0.88f,
        animationSpec = McaMotion.fade(millis = 200),
        label = "dshFloatingBallIdleAlpha"
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val edge = 12.dp
        val ballSize = 48.dp
        if (maxWidth < ballSize + edge * 2 || maxHeight < ballSize + edge * 2) return@BoxWithConstraints
        val density = LocalDensity.current
        val horizontalTravel = (maxWidth - ballSize - edge * 2).coerceAtLeast(0.dp)
        val verticalTravel = (maxHeight - ballSize - edge * 2).coerceAtLeast(0.dp)
        val horizontalTravelPx = with(density) { horizontalTravel.toPx() }
        val verticalTravelPx = with(density) { verticalTravel.toPx() }
        val edgePx = with(density) { edge.toPx() }

        fun savePosition() {
            preferences.edit()
                .putFloat(KEY_X, horizontalFraction)
                .putFloat(KEY_Y, verticalFraction)
                .apply()
        }

        val ballX = edge + horizontalTravel * horizontalFraction
        val ballY = edge + verticalTravel * verticalFraction
        val panelWidth = 168.dp
        val panelHeight = if (modelSwitch != null) 156.dp else 104.dp
        val panelBelow = ballY + ballSize + edge + panelHeight <= maxHeight
        val panelX = (ballX + ballSize - panelWidth)
            .coerceIn(edge, (maxWidth - panelWidth - edge).coerceAtLeast(edge))
        val panelY = if (panelBelow) {
            ballY + ballSize + edge
        } else {
            (ballY - panelHeight - edge).coerceAtLeast(edge)
        }

        if (expanded || panelAlpha.value > 0.01f) {
            Surface(
                modifier = Modifier
                    .offset(x = panelX, y = panelY)
                    .width(panelWidth)
                    .graphicsLayer {
                        scaleX = panelScale.value
                        scaleY = panelScale.value
                        alpha = panelAlpha.value
                    },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f)),
                shadowElevation = 10.dp
            ) {
                Column {
                    BallActionRow(
                        label = "返回 MCA",
                        icon = McaIcons.Back,
                        onClick = {
                            expanded = false
                            onExit()
                        }
                    )
                    if (modelSwitch != null) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                        BallActionRow(
                            label = "模型",
                            icon = McaIcons.List,
                            onClick = {
                                expanded = false
                                showModels = true
                            }
                        )
                    }
                    if (showConsole) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                        BallActionRow(
                            label = "DSH 控制台",
                            icon = McaIcons.Settings,
                            onClick = {
                                expanded = false
                                onOpenConsole()
                            }
                        )
                    }
                }
            }
        }

        if (showModels && modelSwitch != null) {
            val switchState by modelSwitch.state.collectAsStateWithLifecycle()
            DshModelSwitchDialog(
                state = switchState,
                pendingModelId = pendingModelId,
                onSelect = { modelId ->
                    pendingModelId = modelId
                    modelSwitch.select(modelId)
                },
                onDismiss = {
                    showModels = false
                    pendingModelId = null
                }
            )
        }

        fun moveBy(x: Float, y: Float): Boolean {
            horizontalFraction = (horizontalFraction + x).coerceIn(0f, 1f)
            verticalFraction = (verticalFraction + y).coerceIn(0f, 1f)
            savePosition()
            return true
        }

        Surface(
            modifier = Modifier
                // Read drag position during placement so pointer updates do not recompose the page.
                .offset {
                    IntOffset(
                        (edgePx + horizontalTravelPx * horizontalFraction).roundToInt(),
                        (edgePx + verticalTravelPx * verticalFraction).roundToInt()
                    )
                }
                .size(ballSize)
                .graphicsLayer {
                    alpha = ballIdleAlpha.value
                }
                .semantics(mergeDescendants = true) {
                    contentDescription = "DSH 悬浮球。点击${if (expanded) "收起" else "展开"}操作，可拖动位置。"
                    stateDescription = if (expanded) "操作已展开" else "操作已收起"
                    role = Role.Button
                    customActions = listOf(
                        CustomAccessibilityAction("向左移动悬浮球") { moveBy(-0.15f, 0f) },
                        CustomAccessibilityAction("向右移动悬浮球") { moveBy(0.15f, 0f) },
                        CustomAccessibilityAction("向上移动悬浮球") { moveBy(0f, -0.15f) },
                        CustomAccessibilityAction("向下移动悬浮球") { moveBy(0f, 0.15f) }
                    )
                }
                .pointerInput(horizontalTravelPx, verticalTravelPx, ballSize) {
                    // Tap and drag share one pointer scope: a sibling clickable would consume the
                    // down event before drag detection on some OEM Compose builds.
                    val touchSlopPx = with(density) { 8.dp.toPx() }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var dragged = false
                        var distance = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUp()) {
                                change.consume()
                                if (!dragged) {
                                    haptics.tick()
                                    expanded = !expanded
                                }
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
                                    expanded = false
                                }
                                if (dragged) {
                                    change.consume()
                                    horizontalFraction = moveDshBallFraction(
                                        horizontalFraction, delta.x, horizontalTravelPx
                                    )
                                    verticalFraction = moveDshBallFraction(
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
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_dsh_whale),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
private fun BallActionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val haptics = rememberMcaHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .mcaPressScale(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = {
                    haptics.tick()
                    onClick()
                }
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(McaIconSize.M),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

/** Pure drag math, unit-tested without Android: fractions are clamped to the travel range. */
internal fun moveDshBallFraction(current: Float, deltaPx: Float, travelPx: Float): Float {
    if (!travelPx.isFinite() || travelPx <= 0f) return current.coerceIn(0f, 1f)
    val moved = current + deltaPx / travelPx
    return if (moved.isFinite()) moved.coerceIn(0f, 1f) else current.coerceIn(0f, 1f)
}

// The ball starts at the top CENTER: the only top band the official page leaves free between its
// left and right sidebar toggles. The user can drag it anywhere; the position is remembered.
private const val PREFERENCES_NAME = "dsh_workspace_floating_ball"
private const val KEY_X = "x_fraction"
private const val KEY_Y = "y_fraction"
private const val DEFAULT_X_FRACTION = 0.5f
private const val DEFAULT_Y_FRACTION = 0.04f
