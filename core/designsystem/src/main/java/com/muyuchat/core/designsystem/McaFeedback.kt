package com.muyuchat.core.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * In-app transient feedback, the replacement for the OS-drawn `Toast` inside composable surfaces.
 *
 * The state owns its own scope (the host that creates it), so a message posted from a screen that
 * closes straight afterwards (a preview overlay, a dialog) is still shown instead of being cancelled
 * with the caller's composition. A new message replaces the current one rather than queueing behind
 * it, which is what a "saved / failed" confirmation wants.
 */
@Stable
public class McaFeedbackState internal constructor(
    private val scope: CoroutineScope,
    internal val hostState: SnackbarHostState,
) {
    private var messageJob: Job? = null

    public fun post(
        message: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        messageJob?.cancel()
        messageJob = scope.launch {
            hostState.currentSnackbarData?.dismiss()
            val result = hostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                withDismissAction = false,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
}

@Composable
public fun rememberMcaFeedbackState(): McaFeedbackState {
    val scope = rememberCoroutineScope()
    return remember(scope) { McaFeedbackState(scope, SnackbarHostState()) }
}

/** The nearest installed feedback state, or `null` when no [McaFeedbackHost] owner is above. */
public val LocalMcaFeedback: androidx.compose.runtime.ProvidableCompositionLocal<McaFeedbackState?> =
    staticCompositionLocalOf { null }

/**
 * Returns a `(message) -> Unit` for UI code. A feedback layer must be installed above the caller.
 */
@Composable
public fun rememberMcaNotify(): (String) -> Unit {
    val feedback = LocalMcaFeedback.current
    return remember(feedback) {
        { message: String ->
            feedback?.post(message)
        }
    }
}

/** Installs one feedback owner around a surface and draws its host above navigation/IME insets. */
@Composable
public fun McaFeedbackLayer(content: @Composable () -> Unit) {
    val state = rememberMcaFeedbackState()
    CompositionLocalProvider(LocalMcaFeedback provides state) {
        Box(Modifier.fillMaxSize()) {
            content()
            McaFeedbackHost(
                state = state,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .imePadding(),
            )
        }
    }
}

/** Draws the snackbars of [state]. The caller positions it (usually bottom, above insets). */
@Composable
public fun McaFeedbackHost(
    state: McaFeedbackState,
    modifier: Modifier = Modifier,
) {
    val current = state.hostState.currentSnackbarData
    val accessibility = LocalAccessibilityManager.current
    // One timer and one transition owner. Replacing or disposing a message cancels its timer.
    LaunchedEffect(current, accessibility) {
        if (current != null) {
            val duration = feedbackDurationMillis(current.visuals.duration)
            val timeout = accessibility?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = duration,
                containsIcons = true,
                containsText = true,
                containsControls = current.visuals.actionLabel != null,
            ) ?: duration
            delay(timeout)
            current.dismiss()
        }
    }
    AnimatedContent(
        targetState = current,
        modifier = modifier,
        contentAlignment = Alignment.BottomCenter,
        transitionSpec = {
            ((slideInVertically(spring(stiffness = 700f)) { it / 2 } + fadeIn()) togetherWith
                (slideOutVertically(spring(stiffness = 700f)) { it / 2 } + fadeOut()))
                .using(SizeTransform(clip = false) { _, _ -> snap() })
        },
        label = "mca-feedback",
    ) { data ->
        if (data != null) {
            val active = data === current
            Surface(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .then(if (active) Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        dismiss { data.dismiss(); true }
                    } else Modifier.clearAndSetSemantics { }),
                shape = McaShape.Capsule,
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                border = BorderStroke(McaHairline.Width, McaHairline.subtle()),
                shadowElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = data.visuals.message,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    data.visuals.actionLabel?.let { label ->
                        McaButton(
                            onClick = { data.performAction() },
                            enabled = active,
                            style = McaButtonStyle.Text,
                            contentColor = MaterialTheme.colorScheme.inversePrimary,
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        }
    }
}

/** A modal-window host anchored above its action row, without changing the dialog's measurement. */
@Composable
public fun McaFeedbackPopup(state: McaFeedbackState) {
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val position = remember(gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset = IntOffset(
                x = ((windowSize.width - popupContentSize.width) / 2).coerceAtLeast(0),
                y = (anchorBounds.top - popupContentSize.height - gap).coerceIn(
                    0, (windowSize.height - popupContentSize.height).coerceAtLeast(0),
                ),
            )
        }
    }
    Popup(
        popupPositionProvider = position,
        properties = PopupProperties(focusable = false),
    ) {
        McaFeedbackHost(state)
    }
}

internal fun feedbackDurationMillis(duration: SnackbarDuration): Long = when (duration) {
    SnackbarDuration.Short -> 4_000L
    SnackbarDuration.Long -> 10_000L
    SnackbarDuration.Indefinite -> Long.MAX_VALUE
}
