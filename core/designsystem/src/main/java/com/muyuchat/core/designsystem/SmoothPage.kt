package com.muyuchat.core.designsystem

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch

/**
 * A full-screen page that slides in from the right and leaves under its own motion.
 *
 * Extracted from AgentScreen and ModelHubScreen, which each carried this same 45-line function
 * byte for byte. Both copies were private, so neither could be reused and the two pages' entry
 * and exit motion could drift apart without anything noticing; the animation timings are
 * user-visible, so a divergence would have been a visible inconsistency rather than an internal
 * detail. The body and its initial 240/140/180 ms curves were preserved during that move.
 *
 * V9/V03: [motion] replaces the hard-coded curves with a configurable contract whose defaults
 * keep the same hand-feel; see [McaPageMotion]. The animated offset is read only inside
 * `graphicsLayer`, never during composition, so a running slide invalidates the layer rather
 * than restarting the composable.
 *
 * The transition state is created hidden-first on purpose. With `AnimatedVisibility(visible =
 * true)` on a page that appears through conditional composition, the first frame has
 * currentState == targetState == true and the enter transition never runs (Agent/ModelHub cold
 * start and rotation were all dead slides). Starting at `initialState = false` and assigning
 * `targetState` before the first frame makes the enter transition run exactly once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun SmoothRightToLeftPage(
    visible: Boolean,
    onDismiss: () -> Unit,
    motion: McaPageMotion = McaPageMotion.Default,
    content: @Composable (Modifier, () -> Unit) -> Unit
) {
    val pageVisibility = remember { MutableTransitionState(initialState = false) }
    pageVisibility.targetState = visible
    AnimatedVisibility(
        visibleState = pageVisibility,
        enter = slideInHorizontally(
            animationSpec = motion.enterSlide,
            initialOffsetX = { it }
        ) + fadeIn(animationSpec = motion.enterFade),
        exit = ExitTransition.None
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val scope = rememberCoroutineScope()
            val offsetX = remember { Animatable(0f) }

            LaunchedEffect(visible) {
                if (visible) offsetX.snapTo(0f)
            }

            fun closeWithMotion() {
                scope.launch {
                    offsetX.animateTo(
                        targetValue = widthPx,
                        animationSpec = motion.dismissSlide
                    )
                    onDismiss()
                }
            }

            BackHandler(enabled = visible && !WindowInsets.isImeVisible) {
                closeWithMotion()
            }

            val pageModifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Deferred, draw-phase read: composition never observes the animated value.
                    translationX = offsetX.value
                }

            content(pageModifier, ::closeWithMotion)
        }
    }
}
