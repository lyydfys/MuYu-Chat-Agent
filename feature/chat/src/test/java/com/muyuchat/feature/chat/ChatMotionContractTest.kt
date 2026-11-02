package com.muyuchat.feature.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.unit.IntOffset
import com.muyuchat.core.designsystem.McaPageMotion
import com.muyuchat.core.designsystem.SmoothPageMotionState
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Page gestures must stay compositor-only, the chat page must keep its established 240/160/180/
 * 140 ms tween curves, and reusing the shared page owner must not silently swap those curves for
 * [McaPageMotion.Default]'s springs.
 *
 * The behavioural cases below run the real [SmoothPageMotionState] with the chat curves on a
 * deterministic frame clock. They exist because the previous private wrapper kept two channels,
 * so a progress sample could cancel the settle and clear `dismissing`, and a late sample could
 * reverse a committed exit.
 */
class ChatMotionContractTest {

    @Test
    fun `swipe back page still translates through the graphics layer`() {
        val swipe = source("SwipeBackPage.kt")
        assertTrue(
            "SwipeBackPage must translate on the compositor",
            swipe.contains("graphicsLayer { translationX = offsetX.value.x.toFloat() }")
        )
        assertFalse(
            "SwipeBackPage must not place the full page each animation frame",
            swipe.contains(".offset { IntOffset(offsetX")
        )
    }

    @Test
    fun `established chat page transition timings remain intact`() {
        val swipe = source("SwipeBackPage.kt")
        val menu = source("AppMenuTransitions.kt")
        assertTrue(swipe.contains("durationMillis = 260"))
        assertTrue(swipe.contains("durationMillis = 180"))
        // The shell now shares one conflated owner; restoring its old independent channels would
        // allow a late sample to reverse an already committed dismissal.
        assertTrue(swipe.contains("SmoothPageMotionState(widthPx)"))
        assertTrue(swipe.contains("onProgress = pageMotion::progress"))
        assertFalse(swipe.contains("Channel<"))
        assertFalse(swipe.contains("SwipeBackGate"))
        assertTrue(swipe.contains("spring("))
        for (duration in listOf(240, 140, 180, 160)) {
            assertTrue(
                "chat page curve ${duration}ms must stay in AppMenuTransitions.kt",
                menu.contains("durationMillis = $duration")
            )
        }
    }

    @Test
    fun `main shell retains its exact entrance cancel and dismissal curves`() {
        assertEquals(260, (MainShellPageMotion.enterSlide as TweenSpec<*>).durationMillis)
        assertEquals(180, (MainShellPageMotion.enterFade as TweenSpec<*>).durationMillis)
        val cancel = MainShellCancelSlide as SpringSpec<*>
        val dismiss = MainShellPageMotion.dismissSlide as SpringSpec<*>
        assertEquals(0.95f, cancel.dampingRatio, 0f)
        assertEquals(700f, cancel.stiffness, 0f)
        assertEquals(0.92f, dismiss.dampingRatio, 0f)
        assertEquals(620f, dismiss.stiffness, 0f)
    }

    @Test
    fun `main shell back during entrance commits once despite same-frame late samples`() {
        chatMotionTest { clock ->
            val state = SmoothPageMotionState(1_000)
            var started = 0
            var completed = 0
            val owner = launch {
                state.run(
                    widthPx = { 1_000 },
                    motion = { MainShellPageMotion },
                    cancelSlide = { MainShellCancelSlide },
                    onDismissStart = { started++ },
                    onDismiss = { completed++ }
                )
            }
            state.enter()
            drainChatCommands()
            clock.advance(4)
            val before = state.offsetX.value.x
            assertTrue(before in 1..999)
            state.dismiss()
            // These arrive before the mailbox consumer resumes. Dismiss must already own it.
            state.progress(0.1f)
            state.cancel()
            state.dismiss()
            drainChatCommands()
            assertEquals(before, state.offsetX.value.x)
            assertEquals(1, started)
            assertEquals(0, completed)
            clock.advance(80)
            assertEquals(1_000, state.offsetX.value.x)
            assertEquals(1, completed)
            state.progress(0f)
            state.cancel()
            state.dismiss()
            clock.advance(4)
            assertEquals(1_000, state.offsetX.value.x)
            assertEquals(1, started)
            assertEquals(1, completed)
            owner.cancelAndJoin()
        }
    }

    @Test
    fun `main shell interrupted entrance cancels from its dragged position`() {
        chatMotionTest { clock ->
            val state = SmoothPageMotionState(1_000)
            val owner = launch {
                state.run(
                    { 1_000 }, { MainShellPageMotion },
                    cancelSlide = { MainShellCancelSlide }
                ) { fail("a cancelled main-shell gesture cannot dismiss") }
            }
            state.enter()
            drainChatCommands()
            clock.advance(4)
            val before = state.offsetX.value.x
            state.progress(0f)
            drainChatCommands()
            assertEquals(before, state.offsetX.value.x)
            state.progress(0.5f)
            drainChatCommands()
            val dragged = state.offsetX.value.x
            assertTrue(dragged in before..1_000)
            state.cancel()
            drainChatCommands()
            assertEquals(dragged, state.offsetX.value.x)
            clock.advance(80)
            assertEquals(0, state.offsetX.value.x)
            owner.cancelAndJoin()
        }
    }

    @Test
    fun `main shell zero-duration exit reports start then completion without a frame`() {
        val zeroScale = object : MotionDurationScale { override val scaleFactor = 0f }
        runBlocking(zeroScale) {
            val state = SmoothPageMotionState(1_000)
            val callbacks = mutableListOf<String>()
            val owner = launch {
                state.run(
                    { 1_000 }, { MainShellPageMotion },
                    cancelSlide = { MainShellCancelSlide },
                    onDismissStart = { callbacks += "start" }
                ) { callbacks += "complete" }
            }
            state.dismiss()
            drainChatCommands()
            assertEquals(listOf("start", "complete"), callbacks)
            assertEquals(1_000, state.offsetX.value.x)
            owner.cancelAndJoin()
        }
    }

    @Test
    fun `reasoning shimmer moves its remembered shader only in the draw phase`() {
        val reasoning = source("ChatReasoningPerformanceScreens.kt")
        assertTrue(reasoning.contains("val shimmerX = transition.animateFloat("))
        assertFalse(reasoning.contains("val shimmerX by"))
        assertTrue(reasoning.contains("remember(baseColor, highlightColor, shimmerX)"))
        assertTrue(reasoning.contains("modifier = Modifier.drawWithContent"))
        assertTrue(reasoning.contains("matrix.setTranslate(shimmerX.value, 0f)"))
        assertFalse(reasoning.contains("start = Offset(shimmerX"))
    }

    @Test
    fun `completed assistant marks have no infinite animation and generating marks read in drawing`() {
        val mark = source("McaBrandMarks.kt").substringAfter("internal fun AnimatedMcaAssistantMark(")
        assertTrue(mark.indexOf("if (isGenerating)") < mark.indexOf("rememberInfiniteTransition("))
        assertTrue(mark.contains("val angle = if (isGenerating)"))
        assertTrue(mark.indexOf("angle?.value") > mark.indexOf("Canvas("))
        assertFalse(mark.contains("val angle by"))
    }

    @Test
    fun `chat page delegates to the shared page instead of a second motion owner`() {
        val menu = source("AppMenuTransitions.kt")

        assertTrue(
            "the chat page must reuse the shared page primitive",
            menu.contains("SharedSmoothRightToLeftPage(")
        )
        assertTrue(
            "the chat page must supply its own curves to the shared page",
            menu.contains("motion = ChatAppMenuPageMotion")
        )
        assertTrue(
            "the 160 ms gesture-cancel curve must be supplied explicitly",
            menu.contains("cancelSlide = ChatAppMenuCancelSlide")
        )
        assertFalse(
            "the old independent settle mailbox must be gone",
            menu.contains("settleTargets")
        )
        assertFalse(
            "the old independent gesture mailbox must be gone",
            menu.contains("gestureProgress")
        )
        assertFalse(
            "the old local target enum must be gone",
            menu.contains("AppMenuPageTarget")
        )
        assertFalse(
            "a progress sample must not be able to clear a committed dismissal",
            menu.contains("dismissing = false")
        )
    }

    @Test
    fun `chat page curves stay tweens and never fall back to the shared spring default`() {
        val enter = ChatAppMenuPageMotion.enterSlide
        val fade = ChatAppMenuPageMotion.enterFade
        val dismiss = ChatAppMenuPageMotion.dismissSlide
        val cancel = ChatAppMenuCancelSlide

        assertTrue("entrance must stay a tween", enter is TweenSpec<*>)
        assertTrue("fade must stay a tween", fade is TweenSpec<*>)
        assertTrue("dismissal must stay a tween", dismiss is TweenSpec<*>)
        assertTrue("cancelled gestures must stay a tween", cancel is TweenSpec<*>)

        assertEquals(240, (enter as TweenSpec<*>).durationMillis)
        assertEquals(140, (fade as TweenSpec<*>).durationMillis)
        assertEquals(180, (dismiss as TweenSpec<*>).durationMillis)
        assertEquals(160, (cancel as TweenSpec<*>).durationMillis)
        assertEquals(
            "the entrance must keep the tween's default easing",
            FastOutSlowInEasing,
            (enter as TweenSpec<*>).easing
        )

        // The shared default is a spring with a different hand-feel; the chat page must not have
        // been switched to it while adopting the shared owner.
        assertTrue(McaPageMotion.Default.enterSlide is SpringSpec<*>)
        assertTrue(McaPageMotion.Default.dismissSlide is SpringSpec<*>)
        assertFalse(ChatAppMenuPageMotion.enterSlide is SpringSpec<*>)
        assertFalse(ChatAppMenuPageMotion.dismissSlide is SpringSpec<*>)
    }

    @Test
    fun `chat entrance keeps the old float tween trajectory within one pixel`() {
        chatMotionTest { clock ->
            // The private wrapper this replaced animated a Float from one width to zero with
            // tween(240). The shared owner animates IntOffset; the trajectory must be identical
            // apart from integer rounding.
            val reference = Animatable(1_000f)
            val actual = Animatable(IntOffset(1_000, 0), IntOffset.VectorConverter)
            val referenceJob = launch {
                reference.animateTo(0f, tween<Float>(durationMillis = 240))
            }
            val actualJob = launch {
                actual.animateTo(IntOffset.Zero, ChatAppMenuPageMotion.enterSlide)
            }
            drainChatCommands()
            var frames = 0
            while (!referenceJob.isCompleted || !actualJob.isCompleted) {
                clock.step()
                drainChatCommands()
                assertTrue(
                    "chat entrance ${actual.value.x}px drifted from the old float ${reference.value}px",
                    abs(actual.value.x - reference.value) <= 1f
                )
                frames++
                if (frames > 60) fail("the chat entrance never finished")
            }
            assertEquals(0, actual.value.x)
        }
    }

    @Test
    fun `committed chat back ignores late progress and cancel`() {
        chatMotionTest { clock ->
            val state = SmoothPageMotionState(1_000)
            var dismissCount = 0
            val owner = launch {
                state.run(
                    { 1_000 },
                    { ChatAppMenuPageMotion },
                    cancelSlide = { ChatAppMenuCancelSlide }
                ) { dismissCount++ }
            }
            state.enter()
            drainChatCommands()
            clock.advance(4)
            val before = state.offsetX.value.x
            assertTrue("the entrance should be part way on screen", before in 1..999)

            state.dismiss()
            drainChatCommands()
            assertEquals(
                "a back press during the entrance must continue from the current offset",
                before,
                state.offsetX.value.x
            )

            // Late predictive-back samples and a gesture cancel arrive after the exit committed.
            state.progress(0.2f)
            state.cancel()
            drainChatCommands()
            var previous = state.offsetX.value.x
            repeat(6) {
                clock.step()
                drainChatCommands()
                val current = state.offsetX.value.x
                assertTrue(
                    "a committed chat exit must not reverse ($previous -> $current)",
                    current >= previous
                )
                previous = current
            }
            clock.advance(30)
            assertEquals(1_000, state.offsetX.value.x)
            assertEquals("the dismissal callback must fire exactly once", 1, dismissCount)
            owner.cancelAndJoin()
        }
    }

    @Test
    fun `chat gesture cancel returns to open on the 160 ms curve without a pre-jump`() {
        chatMotionTest { clock ->
            val state = SmoothPageMotionState(1_000)
            var dismissCount = 0
            val owner = launch {
                state.run(
                    { 1_000 },
                    { ChatAppMenuPageMotion },
                    cancelSlide = { ChatAppMenuCancelSlide }
                ) { dismissCount++ }
            }
            state.enter()
            drainChatCommands()
            clock.advance(30)
            assertEquals(0, state.offsetX.value.x)

            state.progress(0f)
            drainChatCommands()
            state.progress(0.5f)
            drainChatCommands()
            assertEquals(500, state.offsetX.value.x)

            state.cancel()
            drainChatCommands()
            assertEquals(
                "a cancelled gesture must continue from the dragged position",
                500,
                state.offsetX.value.x
            )
            clock.advance(2)
            assertTrue("the cancel settle must be moving", state.offsetX.value.x in 1..499)
            clock.advance(20)
            assertEquals(
                "the 160 ms cancel curve must reach the open position",
                0,
                state.offsetX.value.x
            )
            assertEquals("a cancelled gesture must not dismiss the page", 0, dismissCount)
            owner.cancelAndJoin()
        }
    }

    @Test
    fun `chat double back never hovers and completes exactly once`() {
        chatMotionTest { clock ->
            val state = SmoothPageMotionState(1_000)
            var dismissCount = 0
            val owner = launch {
                state.run(
                    { 1_000 },
                    { ChatAppMenuPageMotion },
                    cancelSlide = { ChatAppMenuCancelSlide }
                ) { dismissCount++ }
            }
            state.enter()
            drainChatCommands()
            clock.advance(30)
            assertEquals(0, state.offsetX.value.x)

            state.dismiss()
            drainChatCommands()
            clock.advance(2)
            var previous = state.offsetX.value.x
            assertTrue("the first back must start the exit", previous > 0)

            // Fast repeated back presses and a stale cancel while the exit is running.
            repeat(3) { state.dismiss(); drainChatCommands() }
            state.cancel()
            drainChatCommands()
            repeat(6) {
                clock.step()
                drainChatCommands()
                val current = state.offsetX.value.x
                assertTrue("a repeated back must not hover or reverse", current >= previous)
                previous = current
            }
            clock.advance(30)
            assertEquals(1_000, state.offsetX.value.x)
            assertEquals(1, dismissCount)
            owner.cancelAndJoin()
        }
    }

    /**
     * R04：the composables must keep using the single owner and its explicit enter/exit hooks.
     *
     * The interaction contract itself is proven by `ChatComposerMenuLifecycleTest`; this only pins
     * the wiring so a later edit cannot quietly go back to raw booleans, an ungated row or a
     * callback-free exit.
     */
    @Test
    fun `composer menu family is wired to the single lifecycle owner`() {
        val host = source("ChatComposerScreens.kt")
        assertTrue(host.contains("ComposerMenuLifecycle.LifecycleSaver"))
        assertTrue(
            host.contains(
                "BackHandler(enabled = composerMenu.isComposed && !WindowInsets.isImeVisible)"
            )
        )

        val sheet = source("ChatComposerActionMenuViews.kt")
        assertTrue(sheet.contains("menu.runMenuAction("))
        assertTrue(sheet.contains("actionable = menu.menu.acceptsActions"))
        assertTrue(sheet.contains("DisposableEffect(menu)"))
        assertTrue(sheet.contains("menu.menu.onContentDisposed()"))

        val capsule = source("ChatComposerResearchModeViews.kt")
        // Selection is routed through the single owner as a trailing lambda, so the assertion tracks
        // the owner call itself rather than one parenthesised call shape.
        assertTrue(capsule.contains("menu.runResearchAction"))
        assertTrue(capsule.contains("menu.research.onContentComposed()"))
        assertTrue(capsule.contains("menu.research.onContentDisposed()"))
    }

    private fun chatMotionTest(body: suspend CoroutineScope.(ChatManualClock) -> Unit) {
        val clock = ChatManualClock()
        runBlocking(clock) {
            withTimeout(5_000) { body(clock) }
        }
    }

    private suspend fun ChatManualClock.advance(frames: Int) {
        repeat(frames) {
            step()
            drainChatCommands()
        }
    }

    private fun source(name: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, "feature/chat/src/main/java/com/muyuchat/feature/chat/$name")
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate $name")
    }

    private class ChatManualClock : MonotonicFrameClock {
        private val clock = BroadcastFrameClock()
        private var nowNanos = 0L

        fun step() {
            nowNanos += CHAT_FRAME_NANOS
            clock.sendFrame(nowNanos)
        }

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
            clock.withFrameNanos(onFrame)
    }
}

private const val CHAT_FRAME_NANOS = 16_666_667L

private suspend fun drainChatCommands() {
    repeat(8) { yield() }
}
