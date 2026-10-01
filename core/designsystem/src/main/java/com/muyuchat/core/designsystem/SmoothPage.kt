package com.muyuchat.core.designsystem

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * A full-screen page that slides in from the right and leaves under its own motion.
 *
 * Extracted verbatim from AgentScreen and ModelHubScreen, which each carried this same
 * 45-line function byte for byte. Both copies were private, so neither could be reused and
 * the two pages' entry and exit motion could drift apart without anything noticing; the
 * animation timings below are user-visible, so a divergence would have been a visible
 * inconsistency rather than an internal detail.
 *
 * Only the visibility changed -- private to public, because the callers are now in other
 * modules. The body, including every animation duration, is unchanged.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun SmoothRightToLeftPage(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable (Modifier, () -> Unit) -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            animationSpec = tween(durationMillis = 240),
            initialOffsetX = { it }
        ) + fadeIn(animationSpec = tween(durationMillis = 140)),
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
                        animationSpec = tween(durationMillis = 180)
                    )
                    onDismiss()
                }
            }

            BackHandler(enabled = visible && !WindowInsets.isImeVisible) {
                closeWithMotion()
            }

            val pageModifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }

            content(pageModifier, ::closeWithMotion)
        }
    }
}
