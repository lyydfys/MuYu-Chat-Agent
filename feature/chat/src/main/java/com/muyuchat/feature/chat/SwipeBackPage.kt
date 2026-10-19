package com.muyuchat.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.muyuchat.core.designsystem.SystemBackMotionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * A page that follows the system predictive-back gesture and can also be dismissed by its own
 * horizontal motion.
 *
 * Motion contract:
 * - [offsetX] is the single position source shared by entrance, predictive-back drag, cancel and
 *   dismissal. The page enters from one width off screen through the same value a gesture later
 *   takes over, so a back action during the entrance continues from the current on-screen position
 *   instead of restarting another slide on top of it.
 * - The value is read only inside `graphicsLayer`, so dragging invalidates the layer, never the
 *   composition.
 * - Predictive-back progress arrives once per frame. It is funnelled through a **conflated channel**
 *   into one consumer that snaps the shared offset; it does not allocate one coroutine per callback.
 * - A single settle job (latest command wins) owns the cancel/dismiss animations. Gesture progress
 *   cancels that job immediately and takes over from wherever the page currently is.
 * - Dismissal is idempotent: repeated back presses cannot run the exit twice.
 * - Both settle animations use springs, so the page keeps its weight; with the system animation
 *   scale at zero they complete immediately (standard `Animatable` behaviour).
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
    // hard-cuts instead of animating in.
    val pageVisibility = remember { MutableTransitionState(initialState = false) }
    pageVisibility.targetState = visible
    AnimatedVisibility(
        visibleState = pageVisibility,
        // Only the fade remains on AnimatedVisibility: the horizontal position lives exclusively in
        // offsetX now, so entrance, gesture takeover and dismissal cannot stack two translations.
        enter = drawerPageEnter(),
        exit = ExitTransition.None
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            // The page is composed when it becomes visible, so it starts one width off screen. The
            // remembered value is stable for this page's composition; a configuration change
            // recreates the page (and restarts this entrance), as before.
            val offsetX = remember { Animatable(widthPx) }
            // Collectors are keyed on the stable offset only: a window resize must not cancel a
            // running entrance or dismissal. They still read the current width for their maths.
            val latestWidthPx = rememberUpdatedState(widthPx)
            val gestureProgress = remember { Channel<Float>(Channel.CONFLATED) }
            val settleTargets = remember { Channel<SwipeBackTarget>(Channel.CONFLATED) }
            val gate = remember { SwipeBackGate() }

            // Predictive-back drag: conflated samples, snapped into the shared offset. Taking over
            // cancels any in-flight settle and continues from the current position.
            LaunchedEffect(offsetX) {
                for (progress in gestureProgress) {
                    gate.settleJob?.cancel()
                    gate.settleJob = null
                    gate.dismissing = false
                    if (!gate.gestureActive) {
                        gate.gestureActive = true
                        gate.gestureBase = offsetX.value.coerceIn(0f, latestWidthPx.value)
                    }
                    val fraction = progress.coerceIn(0f, 1f)
                    // A gesture that starts during the entrance maps the remaining travel instead
                    // of jumping back to a fully open page.
                    offsetX.snapTo(
                        gate.gestureBase +
                            (latestWidthPx.value - gate.gestureBase) * fraction
                    )
                }
            }

            // Single settle-job owner. Callbacks above only post targets; the latest target wins and
            // a cancelled animation never reaches its completion callback.
            LaunchedEffect(offsetX) {
                for (target in settleTargets) {
                    when (target) {
                        SwipeBackTarget.Enter -> {
                            // A gesture that already took over owns the position; a stale entrance
                            // target must not pull the page back.
                            if (gate.gestureActive) continue
                            gate.settleJob?.cancel()
                            gate.settleJob = launch {
                                offsetX.animateTo(0f, tween(durationMillis = 260))
                            }
                        }

                        SwipeBackTarget.Cancel -> {
                            gate.gestureActive = false
                            gate.dismissing = false
                            gate.settleJob?.cancel()
                            gate.settleJob = launch {
                                offsetX.animateTo(
                                    0f,
                                    spring(dampingRatio = 0.95f, stiffness = 700f)
                                )
                            }
                        }

                        SwipeBackTarget.Dismiss -> {
                            if (gate.dismissing) continue
                            gate.dismissing = true
                            gate.gestureActive = false
                            onDismissStart()
                            gate.settleJob?.cancel()
                            gate.settleJob = launch {
                                offsetX.animateTo(
                                    latestWidthPx.value,
                                    spring(dampingRatio = 0.92f, stiffness = 620f)
                                )
                                onDismiss()
                            }
                        }
                    }
                }
            }

            // Hidden-first visibility means the content is composed at the exact moment the page
            // starts entering, so this sends exactly one entrance target per page composition.
            LaunchedEffect(visible) {
                if (visible) settleTargets.trySend(SwipeBackTarget.Enter)
            }

            fun closeWithDrawerMotion() {
                settleTargets.trySend(SwipeBackTarget.Dismiss)
            }

            SystemBackMotionHandler(
                enabled = visible && !WindowInsets.isImeVisible,
                onProgress = { progress -> gestureProgress.trySend(progress) },
                onCancel = { settleTargets.trySend(SwipeBackTarget.Cancel) },
                // Deliberately no minimum-offset kick: the exit always continues from the current
                // offset, so a back press during the entrance never jumps.
                onBack = { settleTargets.trySend(SwipeBackTarget.Dismiss) }
            )

            val pageModifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offsetX.value }

            content(pageModifier, ::closeWithDrawerMotion)
        }
    }
}

private enum class SwipeBackTarget { Enter, Cancel, Dismiss }

/** Plain holder: gesture bookkeeping must not invalidate composition. */
private class SwipeBackGate {
    var settleJob: Job? = null
    var dismissing = false
    var gestureActive = false
    var gestureBase = 0f
}

fun drawerPageEnter() = fadeIn(animationSpec = tween(durationMillis = 180))
