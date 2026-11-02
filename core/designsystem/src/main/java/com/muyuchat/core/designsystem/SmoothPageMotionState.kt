package com.muyuchat.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Composition-local motion owner for [SmoothRightToLeftPage].
 *
 * Public because feature modules that want their own page curves (for example the chat menu
 * pages) must drive the very same owner instead of keeping a second implementation that can
 * drift. There is still exactly one instance per composed page; this is not a global writer.
 *
 * All commands -- entrance, predictive-back samples, gesture cancellation and dismissal --
 * are posted to one conflated mailbox and consumed by [run] in order:
 *
 * - A gesture takes over the current offset instead of stacking another translation on it.
 * - [dismiss] commits synchronously. Once committed, late progress samples or a gesture cancel
 *   cannot reverse the exit, and the completion callback can run at most once.
 * - The running animation belongs to the owner's scope, so disposal cancels it without firing
 *   the completion callback or leaving a frame wait behind.
 */
public class SmoothPageMotionState(initialWidthPx: Int) {
    public val offsetX: Animatable<IntOffset, AnimationVector2D> = Animatable(
        IntOffset(initialWidthPx, 0),
        IntOffset.VectorConverter
    )
    private val commands = Channel<Command>(Channel.CONFLATED)
    private var dismissRequested = false

    public fun enter() {
        if (!dismissRequested) commands.trySend(Command.Enter)
    }

    public fun progress(fraction: Float) {
        if (!dismissRequested) commands.trySend(Command.Progress(fraction))
    }

    public fun cancel() {
        if (!dismissRequested) commands.trySend(Command.Cancel)
    }

    public fun dismiss() {
        if (dismissRequested) return
        // Commit before posting: a late progress sample in the same UI frame must not overwrite
        // dismissal in the conflated mailbox before the consumer gets its next turn.
        dismissRequested = true
        commands.trySend(Command.Dismiss)
    }

    /**
     * Single consumer of every command.
     *
     * [widthPx] and [motion] are read per command so a window resize or a replaced motion
     * contract applies to the next gesture or the next settle without cancelling the current
     * animation. [cancelSlide] is read for a cancelled gesture; it defaults to the entrance
     * curve, which is what the shared page has always used, while callers that historically
     * recovered faster can supply their own curve.
     */
    public suspend fun run(
        widthPx: () -> Int,
        motion: () -> McaPageMotion,
        cancelSlide: () -> FiniteAnimationSpec<IntOffset> = { motion().enterSlide },
        onDismissStart: () -> Unit = {},
        onDismiss: () -> Unit
    ): Unit = coroutineScope {
        var settleJob: Job? = null
        var gestureBase: Int? = null
        var dismissing = false
        for (command in commands) {
            // Once committed, further taps or late gesture samples cannot restart the exit or
            // invoke its callback twice. Disposal cancels this owner and all its animation work.
            if (dismissing) continue
            when (command) {
                Command.Enter -> {
                    // An explicit entrance request outranks a leftover gesture base. The conflated
                    // mailbox may drop a Cancel that was posted in the same frame as this Enter, and
                    // a base that is never cleared would then swallow every later entrance while the
                    // page sits at its last dragged offset. Clearing it here means the page still
                    // settles home from the current position (no pre-jump, no second translation);
                    // only `visible` transitions to true call enter(), so a real drag cannot be
                    // yanked by this.
                    gestureBase = null
                    settleJob?.cancelAndJoin()
                    settleJob = launch {
                        settle(IntOffset.Zero, motion().enterSlide)
                    }
                }

                is Command.Progress -> {
                    if (!command.fraction.isFinite()) continue
                    settleJob?.cancelAndJoin()
                    settleJob = null
                    val width = widthPx().coerceAtLeast(0)
                    val base = gestureBase ?: offsetX.value.x.coerceIn(0, width).also {
                        gestureBase = it
                    }
                    offsetX.snapTo(
                        IntOffset(
                            (base + (width - base) * command.fraction.coerceIn(0f, 1f)).roundToInt(),
                            0
                        )
                    )
                }

                Command.Cancel -> {
                    gestureBase = null
                    settleJob?.cancelAndJoin()
                    settleJob = launch {
                        settle(IntOffset.Zero, cancelSlide())
                    }
                }

                Command.Dismiss -> {
                    dismissing = true
                    gestureBase = null
                    onDismissStart()
                    settleJob?.cancelAndJoin()
                    settleJob = launch {
                        val width = widthPx().coerceAtLeast(0)
                        settle(
                            IntOffset(width, 0),
                            motion().dismissSlide.horizontalOffsetSpec()
                        )
                        onDismiss()
                    }
                }
            }
        }
    }

    /**
     * Settle to [target] without ever waiting for a frame when system animations are off.
     *
     * Compose's animators already collapse a zero duration scale, but the page also owns work
     * outside the animator (the exit must still reach its terminal state and notify the caller),
     * so the zero-scale path snaps explicitly. That keeps an exit from hanging on a frame loop
     * the platform may never run.
     */
    private suspend fun settle(target: IntOffset, spec: FiniteAnimationSpec<IntOffset>) {
        val durationScale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
        if (durationScale == 0f) {
            offsetX.snapTo(target)
        } else {
            offsetX.animateTo(target, animationSpec = spec)
        }
    }

    private sealed interface Command {
        data object Enter : Command
        data class Progress(val fraction: Float) : Command
        data object Cancel : Command
        data object Dismiss : Command
    }
}

/**
 * Preserve the existing public Float dismiss spec while sharing the IntOffset entrance animator.
 *
 * The conversion keeps the same duration, easing and velocity profile; only the value domain is
 * rounded per frame, which is the same sub-pixel behaviour the page had while it animated a
 * `Float` translation. Exposed to module tests so the equivalence is asserted, not assumed.
 */
internal fun FiniteAnimationSpec<Float>.horizontalOffsetSpec(): FiniteAnimationSpec<IntOffset> =
    object : FiniteAnimationSpec<IntOffset> {
        override fun <V : AnimationVector> vectorize(
            converter: TwoWayConverter<IntOffset, V>
        ): VectorizedFiniteAnimationSpec<V> = this@horizontalOffsetSpec.vectorize(
            TwoWayConverter(
                convertToVector = { value -> converter.convertToVector(IntOffset(value.roundToInt(), 0)) },
                convertFromVector = { vector -> converter.convertFromVector(vector).x.toFloat() }
            )
        )
    }
