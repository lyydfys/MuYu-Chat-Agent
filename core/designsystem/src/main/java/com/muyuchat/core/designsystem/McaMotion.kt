package com.muyuchat.core.designsystem

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay

/**
 * The app-wide motion language.
 *
 * One set of specs for every module so chat, settings, model hub and agent do not drift into
 * different hand-feel. All values are deliberately small and physical: no artificial bounce,
 * no decorative loops, and every animated value is expected to be read in a draw/layer phase
 * (`graphicsLayer`, `drawBehind`) rather than during composition.
 */
public object McaMotion {
    /** Pressed scale for tappable cards and rows. Subtle enough to read as weight, not zoom. */
    public const val PRESS_SCALE: Float = 0.97f

    /** Per-item delay used by [mcaEnterReveal] style staggers. */
    public const val STAGGER_MILLIS: Int = 26

    /** Maximum stagger steps; beyond this items share the last delay. */
    public const val MAX_STAGGER_STEPS: Int = 8

    /** Critical-damped: settles without overshoot, ~180 ms. */
    public val press: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 1f, stiffness = 1100f)

    /** Content entrance: slight overshoot is allowed (damping 0.9), ~240 ms. */
    public val contentEnter: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.9f, stiffness = 380f)

    /** Row/column height or size changes. */
    public val sizeChange: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.86f, stiffness = 420f)

    /**
     * The same spring expressed in pixels: `animateContentSize`, `AnimatedContent` size
     * transforms and `expandVertically/shrinkVertically` animate [IntSize], not [Float].
     */
    public val sizeChangePx: FiniteAnimationSpec<IntSize> =
        spring(
            dampingRatio = 0.86f,
            stiffness = 420f,
            visibilityThreshold = IntSize.VisibilityThreshold,
        )

    /** Horizontal page slide; critical damping keeps it aligned with [McaPageMotion]. */
    public val pageSlide: FiniteAnimationSpec<IntOffset> =
        spring(
            dampingRatio = 1f,
            stiffness = 760f,
            visibilityThreshold = IntOffset.VisibilityThreshold,
        )

    /** Short fade used with entrance/exit alongside a slide. */
    public fun fade(millis: Int = 160): FiniteAnimationSpec<Float> =
        tween(durationMillis = millis)
}

/**
 * Press feedback for cards, rows and icon surfaces.
 *
 * The scale is read inside `graphicsLayer`, so holding the press does not recompose the caller.
 * Pass the same [InteractionSource] the clickable/indication already uses.
 */
@Composable
public fun Modifier.mcaPressScale(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
): Modifier {
    val pressed = interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed.value && enabled) McaMotion.PRESS_SCALE else 1f,
        animationSpec = McaMotion.press,
        label = "mca-press-scale",
    )
    // animateFloatAsState respects MotionDurationScale internally: with system animations
    // disabled it lands on the target in the first frame instead of drifting.
    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * Staggered entrance for a list item or a stacked block.
 *
 * [index] only affects the delay; [animate] lets an owner replay the entrance deliberately.
 * As with [mcaPressScale], the value is read in `graphicsLayer` so the follow-up animation does
 * not recompose the row.
 *
 * When the system animator duration scale is 0 (animations disabled), the reveal snaps to its
 * final state immediately: the stagger delay is skipped instead of leaving the row invisible
 * for up to [McaMotion.MAX_STAGGER_STEPS] * [McaMotion.STAGGER_MILLIS] ms.
 */
@Composable
public fun Modifier.mcaEnterReveal(
    index: Int = 0,
    animate: Boolean = true,
    travelDp: Int = 12,
): Modifier {
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(animate) {
        animateEnterReveal(progress = progress, index = index, animate = animate)
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * travelDp.dp.toPx()
    }
}

/**
 * The suspend half of [mcaEnterReveal], split out so the reduced-motion branch is testable
 * without composing a UI.
 *
 * The delay is [kotlinx.coroutines.delay], which Compose's MotionDurationScale does not scale;
 * reading the scale from the coroutine context is therefore required to stop the row from
 * staying invisible when animations are turned off. A missing scale (previews, plain JVM
 * callers) falls back to the normal animated path.
 */
internal suspend fun animateEnterReveal(
    progress: Animatable<Float, AnimationVector1D>,
    index: Int,
    animate: Boolean,
) {
    val durationScale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
    if (!animate || durationScale == 0f) {
        progress.snapTo(1f)
        return
    }
    delay(enterRevealDelayMillis(index))
    progress.animateTo(1f, McaMotion.contentEnter)
}

/** Stagger delay for [index], clamped to [McaMotion.MAX_STAGGER_STEPS] steps. */
internal fun enterRevealDelayMillis(index: Int): Long =
    index.coerceIn(0, McaMotion.MAX_STAGGER_STEPS) * McaMotion.STAGGER_MILLIS.toLong()

/**
 * Hidden-first visibility used by pages that may be composed while already visible.
 *
 * `AnimatedVisibility(visible = true)` never plays `enter` on its first composition because the
 * initial and target states are equal. Starting from `false` and setting the target explicitly
 * makes conditionally-composed pages animate in as intended.
 */
@Composable
public fun rememberEnterVisibility(visible: Boolean): MutableTransitionState<Boolean> =
    remember { MutableTransitionState(false) }.also { it.targetState = visible }
