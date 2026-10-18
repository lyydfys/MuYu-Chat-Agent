package com.muyuchat.core.designsystem

import androidx.compose.animation.core.MutableTransitionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural contract for [SmoothRightToLeftPage].
 *
 * This is deliberately a source-structure guard, not a rendering test: it cannot prove that a
 * page looks smooth on a device, but it does pin the two properties that regressed before -
 * the transition must start hidden (otherwise a page composed with `visible = true` never
 * plays its enter animation), and the animated offset must be read inside `graphicsLayer`
 * rather than during composition. The rendering side is covered by the device screenshot
 * verification in the V9/V03 task record.
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
    fun `page wrapper wires the hidden-first state and the configured motion`() {
        val source = smoothPageSource()

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
            "the enter slide must come from McaPageMotion",
            Regex("""animationSpec\s*=\s*motion\.enterSlide""").containsMatchIn(source)
        )
        assertTrue(
            "the enter fade must come from McaPageMotion",
            Regex("""fadeIn\(animationSpec\s*=\s*motion\.enterFade\)""").containsMatchIn(source)
        )
        assertTrue(
            "the dismiss slide must come from McaPageMotion",
            Regex("""animationSpec\s*=\s*motion\.dismissSlide""").containsMatchIn(source)
        )
        assertTrue(
            "the animated offset must be read inside graphicsLayer",
            Regex("""graphicsLayer\s*\{[\s\S]*translationX\s*=\s*offsetX\.value""")
                .containsMatchIn(source)
        )
        assertFalse(
            "the old offset-lambda read must not come back",
            Regex("""offsetX\.value\.roundToInt|\.offset\s*\{""").containsMatchIn(source)
        )
    }

    private fun smoothPageSource(): String {
        val relative = "src/main/java/com/muyuchat/core/designsystem/SmoothPage.kt"
        val workingDirectory = System.getProperty("user.dir") ?: "."
        var directory = File(workingDirectory).absoluteFile
        while (true) {
            val candidates = listOf(
                File(directory, relative),
                File(directory, "core/designsystem/$relative")
            )
            candidates.firstOrNull { it.isFile }?.let { return it.readText() }
            directory = directory.parentFile ?: break
        }
        throw AssertionError("SmoothPage.kt was not found while walking up from $workingDirectory")
    }
}
