package com.muyuchat.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.muyuchat.core.designsystem.SystemBackMotionHandler
import kotlinx.coroutines.launch

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

            LaunchedEffect(visible) {
                if (visible) offsetX.snapTo(0f)
            }

            suspend fun closeAnimated() {
                onDismissStart()
                offsetX.animateTo(
                    targetValue = widthPx,
                    animationSpec = tween(durationMillis = 180)
                )
                onDismiss()
            }

            fun closeWithDrawerMotion() {
                scope.launch {
                    closeAnimated()
                }
            }

            SystemBackMotionHandler(
                enabled = visible && !WindowInsets.isImeVisible,
                onProgress = { progress ->
                    scope.launch {
                        offsetX.snapTo(widthPx * progress.coerceIn(0f, 1f))
                    }
                },
                onCancel = {
                    scope.launch {
                        offsetX.animateTo(
                            targetValue = 0f,
                            animationSpec = tween(durationMillis = 160)
                        )
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
