package com.muyuchat.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
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
import com.muyuchat.core.designsystem.McaPageMotion
import com.muyuchat.core.designsystem.SmoothPageMotionState
import com.muyuchat.core.designsystem.SystemBackMotionHandler

// Preserve the shell's established curves while sharing the gesture/settle command owner.
internal val MainShellPageMotion = McaPageMotion(
    enterSlide = tween(durationMillis = 260),
    enterFade = tween(durationMillis = 180),
    dismissSlide = spring(dampingRatio = 0.92f, stiffness = 620f)
)
internal val MainShellCancelSlide = spring<androidx.compose.ui.unit.IntOffset>(
    dampingRatio = 0.95f,
    stiffness = 700f
)

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
 * - Entrance, predictive-back progress, cancel and dismiss share [SmoothPageMotionState]'s one
 *   conflated mailbox; a sample never allocates its own coroutine.
 * - Dismissal commits synchronously: late progress/cancel cannot reverse a committed exit, and
 *   start/completion callbacks each run at most once.
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
            val widthPx = with(density) { maxWidth.roundToPx() }
            // The page is composed when it becomes visible, so it starts one width off screen. The
            // remembered value is stable for this page's composition; a configuration change
            // recreates the page (and restarts this entrance), as before.
            val pageMotion = remember { SmoothPageMotionState(widthPx) }
            val offsetX = pageMotion.offsetX
            // Collectors are keyed on the stable offset only: a window resize must not cancel a
            // running entrance or dismissal. They still read the current width for their maths.
            val latestWidthPx = rememberUpdatedState(widthPx)
            val latestOnDismissStart = rememberUpdatedState(onDismissStart)
            val latestOnDismiss = rememberUpdatedState(onDismiss)
            LaunchedEffect(pageMotion) {
                pageMotion.run(
                    widthPx = { latestWidthPx.value },
                    motion = { MainShellPageMotion },
                    cancelSlide = { MainShellCancelSlide },
                    onDismissStart = { latestOnDismissStart.value() },
                    onDismiss = { latestOnDismiss.value() }
                )
            }

            // Hidden-first visibility means the content is composed at the exact moment the page
            // starts entering, so this sends exactly one entrance target per page composition.
            LaunchedEffect(visible) {
                if (visible) pageMotion.enter()
            }

            SystemBackMotionHandler(
                enabled = visible && !WindowInsets.isImeVisible,
                onProgress = pageMotion::progress,
                onCancel = pageMotion::cancel,
                // Deliberately no minimum-offset kick: the exit always continues from the current
                // offset, so a back press during the entrance never jumps.
                onBack = pageMotion::dismiss
            )

            val pageModifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offsetX.value.x.toFloat() }

            content(pageModifier, pageMotion::dismiss)
        }
    }
}

fun drawerPageEnter() = fadeIn(animationSpec = MainShellPageMotion.enterFade)
