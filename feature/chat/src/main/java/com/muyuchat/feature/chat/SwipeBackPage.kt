package com.muyuchat.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.muyuchat.core.designsystem.SystemBackMotionHandler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * A page that follows the system predictive-back gesture and can also be dismissed by its own
 * horizontal motion.
 *
 * Gesture smoothness contract:
 * - The page offset is a single [Animatable] read inside `graphicsLayer`, so dragging invalidates
 *   the layer, never the composition.
 * - Predictive-back progress arrives once per frame. It is funnelled through a **conflated
 *   channel** instead of `scope.launch { snapTo(...) }`, so a drag does not allocate one
 *   coroutine per callback.
 * - Both the cancel snapping-back and the dismiss completion use springs, so the page settles
 *   with weight instead of a fixed tween.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SwipeBackPage(
    visible: Boolean,
    onDismissStart: () -> Unit = {},
    onDismiss: () -> Unit,
    content: @Composable (Modifier, () -> Unit) -> Unit
) {
    // Start hidden so the enter transition also plays when this page is first composed while it is
    // already the active tab (cold start on a non-chat tab, activity recreation). Passing `visible`
    // directly makes currentState and targetState both true on that first composition, so the page
    // hard-cuts instead of sliding in.
    val pageVisibility = remember { MutableTransitionState(initialState = false) }
    pageVisibility.targetState = visible
    AnimatedVisibility(
        visibleState = pageVisibility,
        enter = drawerPageEnter(),
        exit = ExitTransition.None
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val scope = rememberCoroutineScope()
            val offsetX = remember { Animatable(0f) }
            val gestureProgress = remember { Channel<Float>(Channel.CONFLATED) }
            var settling by remember { mutableStateOf(false) }

            LaunchedEffect(visible) {
                if (visible) {
                    settling = false
                    offsetX.snapTo(0f)
                }
            }

            // One long-lived collector: the latest gesture progress wins, stale samples are
            // dropped while a settle animation owns the offset.
            LaunchedEffect(offsetX, widthPx) {
                for (progress in gestureProgress) {
                    if (!settling) {
                        offsetX.snapTo(widthPx * progress.coerceIn(0f, 1f))
                    }
                }
            }

            suspend fun closeAnimated() {
                settling = true
                onDismissStart()
                offsetX.animateTo(
                    targetValue = widthPx,
                    animationSpec = spring(dampingRatio = 0.92f, stiffness = 620f),
                )
                settling = false
                onDismiss()
            }

            fun closeWithDrawerMotion() {
                scope.launch { closeAnimated() }
            }

            SystemBackMotionHandler(
                enabled = visible && !WindowInsets.isImeVisible,
                onProgress = { progress -> gestureProgress.trySend(progress) },
                onCancel = {
                    scope.launch {
                        settling = true
                        offsetX.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(dampingRatio = 0.95f, stiffness = 700f),
                        )
                        settling = false
                    }
                },
                onBack = {
                    scope.launch {
                        if (offsetX.value < widthPx * 0.08f) {
                            offsetX.snapTo(widthPx * 0.08f)
                        }
                        closeAnimated()
                    }
                }
            )

            val pageModifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offsetX.value }

            content(pageModifier, ::closeWithDrawerMotion)
        }
    }
}

fun drawerPageEnter() = slideInHorizontally(
    animationSpec = tween(durationMillis = 260),
    initialOffsetX = { it }
) + fadeIn(animationSpec = tween(durationMillis = 180))
