package com.muyuchat.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * A full-screen page that slides in from the right and leaves under its own motion.
 *
 * Entrance, predictive-back drag, cancellation and dismissal share one position and one animator
 * owner. A back action during entrance takes over the current position instead of stacking a
 * second translation on top of the entry slide. The existing [McaPageMotion] curves are retained.
 * Only the layer reads the animated position; gestures do not recompose the page.
 *
 * [cancelSlide] is the curve used when a predictive-back gesture is cancelled. It defaults to
 * [McaPageMotion.enterSlide], which is what every existing caller already did; a caller whose
 * page historically recovered from a cancelled gesture on a different curve can pass it here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun SmoothRightToLeftPage(
    visible: Boolean,
    onDismiss: () -> Unit,
    motion: McaPageMotion = McaPageMotion.Default,
    cancelSlide: FiniteAnimationSpec<IntOffset>? = null,
    content: @Composable (Modifier, () -> Unit) -> Unit
) {
    val pageVisibility = remember { MutableTransitionState(initialState = false) }
    pageVisibility.targetState = visible
    AnimatedVisibility(
        visibleState = pageVisibility,
        enter = fadeIn(animationSpec = motion.enterFade),
        exit = ExitTransition.None
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx().roundToInt() }
            val position = remember { SmoothPageMotionState(widthPx) }
            val offsetX = position.offsetX
            val latestWidth = rememberUpdatedState(widthPx)
            val latestMotion = rememberUpdatedState(motion)
            val latestCancelSlide = rememberUpdatedState(cancelSlide)
            val latestOnDismiss = rememberUpdatedState(onDismiss)

            LaunchedEffect(position) {
                position.run(
                    widthPx = { latestWidth.value },
                    motion = { latestMotion.value },
                    cancelSlide = {
                        latestCancelSlide.value ?: latestMotion.value.enterSlide
                    },
                    onDismiss = { latestOnDismiss.value() }
                )
            }

            LaunchedEffect(visible) {
                if (visible) position.enter()
            }

            SystemBackMotionHandler(
                enabled = visible && !WindowInsets.isImeVisible,
                onProgress = position::progress,
                onCancel = position::cancel,
                onBack = position::dismiss
            )

            val pageModifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Deferred, draw-phase read: composition never observes the animated value.
                    translationX = offsetX.value.x.toFloat()
                }

            content(pageModifier, position::dismiss)
        }
    }
}
