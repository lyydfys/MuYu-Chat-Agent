package com.muyuchat.core.designsystem

import androidx.compose.animation.core.MutableTransitionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural contract for [SmoothRightToLeftPage].
 *
 * This is deliberately a source-structure guard, not a rendering test: it cannot prove that a
 * page looks smooth on a device, but it pins the properties that regressed before - the
 * transition must start hidden (otherwise a page composed with `visible = true` never plays its
 * enter animation), the animated offset must be read only inside `graphicsLayer`, every command
 * must travel through one position owner, and a committed dismissal must stay terminal.
 *
 * The behavioural side of the same contract (interruption, late samples, callback replacement,
 * resize, disposal, zero-duration animations) is covered by [SmoothPageMotionStateTest].
 */
class SmoothPageStructureContractTest {

    @Test
    fun `transition state starts hidden and then targets the requested visibility`() {
        val state = MutableTransitionState(initialState = false)
        state.targetState = true

        // currentState only advances when a transition frame runs, so even a first-frame
        // visible page starts from hidden and therefore has an enter animation to play.
        assertEquals(false, state.currentState)
        assertEquals(true, state.targetState)
    }

    @Test
    fun `page owns one position and reads it only from the layer`() {
        val source = smoothPageSource()
        val ownerSource = designSystemSource("SmoothPageMotionState.kt")

        assertTrue(
            "SmoothRightToLeftPage must start AnimatedVisibility from a hidden state",
            Regex("""MutableTransitionState\(\s*initialState\s*=\s*false\s*\)""")
                .containsMatchIn(source)
        )
        assertTrue(
            "the transition state must be handed to AnimatedVisibility",
            Regex("""visibleState\s*=\s*pageVisibility""").containsMatchIn(source)
        )
        assertFalse(
            "a plain visible flag would re-introduce the dead first-frame slide",
            Regex("""AnimatedVisibility\(\s*visible\s*=\s*visible\b""").containsMatchIn(source)
        )

        assertTrue(
            "the page must create exactly one position owner",
            Regex("""remember\s*\{\s*SmoothPageMotionState\(\s*widthPx\s*\)\s*\}""")
                .containsMatchIn(source)
        )
        assertEquals(
            "a second position owner would stack two horizontal translations",
            1,
            Regex("""SmoothPageMotionState\(""").findAll(source).count()
        )
        assertTrue(
            "the latest width, motion, cancel curve and callback must be read, not captured once",
            source.contains("rememberUpdatedState(widthPx)") &&
                source.contains("rememberUpdatedState(motion)") &&
                source.contains("rememberUpdatedState(cancelSlide)") &&
                source.contains("rememberUpdatedState(onDismiss)")
        )
        assertTrue(
            "every gesture and back callback must post to the same owner",
            Regex("""onProgress\s*=\s*position::progress""").containsMatchIn(source) &&
                Regex("""onCancel\s*=\s*position::cancel""").containsMatchIn(source) &&
                Regex("""onBack\s*=\s*position::dismiss""").containsMatchIn(source)
        )
        assertFalse(
            "entrance must not add a second horizontal translation",
            source.contains("slideInHorizontally")
        )
        assertTrue(
            "the enter fade must come from McaPageMotion",
            Regex("""fadeIn\(animationSpec\s*=\s*motion\.enterFade\)""").containsMatchIn(source)
        )
        assertTrue(
            "the animated offset must be read inside graphicsLayer",
            Regex("""graphicsLayer\s*\{[\s\S]*translationX\s*=\s*offsetX\.value\.x\.toFloat\(\)""")
                .containsMatchIn(source)
        )
        assertFalse(
            "the old composition-phase offset read must not come back",
            Regex("""offsetX\.value\.roundToInt|\.offset\s*\{""").containsMatchIn(source)
        )
    }

    @Test
    fun `position owner keeps one command consumer and a terminal dismissal gate`() {
        val ownerSource = designSystemSource("SmoothPageMotionState.kt")

        assertEquals(
            "every command must share one conflated mailbox",
            1,
            Regex("""Channel<Command>\(Channel\.CONFLATED\)""").findAll(ownerSource).count()
        )
        assertEquals(
            "a single consumer loop must own all animation jobs",
            1,
            Regex("""for \(command in commands\)""").findAll(ownerSource).count()
        )

        assertTrue(
            "the entrance slide must come from McaPageMotion",
            ownerSource.contains("motion().enterSlide")
        )
        assertTrue(
            "the dismiss slide must come from McaPageMotion",
            ownerSource.contains("motion().dismissSlide.horizontalOffsetSpec()")
        )
        assertTrue(
            "a cancelled gesture must use the owner's cancel curve, not a hard-coded default",
            ownerSource.contains("cancelSlide()") &&
                Regex(
                    """cancelSlide\s*:\s*\(\)\s*->\s*FiniteAnimationSpec<IntOffset>\s*=\s*\{\s*motion\(\)\.enterSlide\s*\}"""
                ).containsMatchIn(ownerSource)
        )

        // Dismissal commits before it is posted, so a late progress sample or gesture cancel in
        // the same frame cannot replace the queued Dismiss in the conflated mailbox.
        assertTrue(
            "dismiss must commit its terminal flag synchronously",
            Regex("""if \(dismissRequested\) return""").containsMatchIn(ownerSource) &&
                ownerSource.contains("dismissRequested = true")
        )
        assertTrue(
            "the consumer must ignore commands queued behind a committed dismissal",
            Regex("""if \(dismissing\) continue""").containsMatchIn(ownerSource)
        )

        val dismissBranch = ownerSource.indexOf("Command.Dismiss ->")
        assertTrue("the owner must handle dismiss", dismissBranch >= 0)
        val branchBody = ownerSource.substring(dismissBranch)
        val branchSettle = branchBody.indexOf("settle(")
        val branchCallback = branchBody.indexOf("onDismiss()")
        assertTrue(
            "the dismissal callback must run only after the exit settled",
            branchSettle >= 0 && branchCallback > branchSettle
        )

        // Zero-duration system animations must reach the terminal state without a frame wait.
        assertTrue(
            "the owner must special-case the zero animation duration",
            ownerSource.contains("durationScale == 0f") &&
                ownerSource.contains("offsetX.snapTo(target)")
        )
    }

    private fun smoothPageSource(): String = designSystemSource("SmoothPage.kt")
}
