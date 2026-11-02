package com.muyuchat.core.designsystem

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McaFeedbackStateTest {
    @Test fun `new feedback replaces the old message without performing its action`() = runTest {
        val host = SnackbarHostState()
        val feedback = McaFeedbackState(backgroundScope, host)
        var oldActions = 0
        feedback.post("old", "undo") { oldActions++ }
        runCurrent()
        val old = requireNotNull(host.currentSnackbarData)
        feedback.post("new")
        runCurrent()
        old.performAction()
        runCurrent()
        assertEquals("new", host.currentSnackbarData?.visuals?.message)
        assertEquals(0, oldActions)
    }

    @Test fun `an action executes once and releases its message`() = runTest {
        val host = SnackbarHostState()
        val feedback = McaFeedbackState(backgroundScope, host)
        var actions = 0
        feedback.post("saved", "undo") { actions++ }
        runCurrent()
        val data = requireNotNull(host.currentSnackbarData)
        data.performAction()
        data.performAction()
        runCurrent()
        assertEquals(1, actions)
        assertNull(host.currentSnackbarData)
    }

    @Test fun `dismiss and owner cancellation never invoke an action`() = runTest {
        val host = SnackbarHostState()
        val feedback = McaFeedbackState(backgroundScope, host)
        var actions = 0
        feedback.post("saved", "undo") { actions++ }
        runCurrent()
        host.currentSnackbarData!!.dismiss()
        runCurrent()
        assertNull(host.currentSnackbarData)
        feedback.post("again", "undo") { actions++ }
        runCurrent()
        backgroundScope.cancel()
        runCurrent()
        assertNull(host.currentSnackbarData)
        assertEquals(0, actions)
    }

    @Test fun `feedback timeout bases preserve Material durations`() {
        assertEquals(4_000L, feedbackDurationMillis(SnackbarDuration.Short))
        assertEquals(10_000L, feedbackDurationMillis(SnackbarDuration.Long))
        assertEquals(Long.MAX_VALUE, feedbackDurationMillis(SnackbarDuration.Indefinite))
    }

    @Test fun `one host owns both transitions and accessible timeout`() {
        val source = designSystemSource("McaFeedback.kt")
        assertTrue(source.contains("AnimatedContent("))
        assertTrue(source.contains("slideOutVertically("))
        assertTrue(source.contains("calculateRecommendedTimeoutMillis("))
        assertTrue(source.contains("LaunchedEffect(current, accessibility)"))
        assertTrue(source.contains("enabled = active"))
        assertFalse(source.contains("MutableTransitionState"))
        assertFalse(source.contains("SnackbarHost(hostState"))
    }
}
