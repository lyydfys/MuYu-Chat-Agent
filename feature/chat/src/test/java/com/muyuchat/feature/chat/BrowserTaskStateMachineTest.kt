package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTaskStateMachineTest {
    @Test
    fun taskHasStableOwnerAndUniqueId() {
        val first = BrowserTaskStateMachine.create("chat:web-search", "https://example.com")
        val second = BrowserTaskStateMachine.create("settings:web-search", "https://example.com")

        assertEquals("chat:web-search", first.owner)
        assertEquals("settings:web-search", second.owner)
        assertNotEquals(first.taskId, second.taskId)
        assertEquals(BrowserTaskPhase.QUEUED, first.phase)
    }

    @Test
    fun crossHostNavigationWaitsForExplicitConfirmation() {
        val task = BrowserTaskStateMachine.create(
            owner = "chat:web-search",
            initialUrl = "https://docs.example.com/article"
        )
        val started = BrowserTaskStateMachine.begin(task)
        val pending = BrowserTaskStateMachine.requestNavigation(
            started,
            "https://www.google.com/search?q=mca",
            requiresConfirmation = true
        )

        assertEquals(BrowserTaskPhase.AWAITING_CONFIRMATION, pending.phase)
        assertEquals("https://www.google.com/search?q=mca", pending.pendingUrl)
        assertEquals("https://docs.example.com/article", pending.currentUrl)

        val confirmed = BrowserTaskStateMachine.confirmNavigation(pending)
        assertEquals(BrowserTaskPhase.NAVIGATING, confirmed.phase)
        assertEquals(pending.pendingUrl, confirmed.currentUrl)
        assertNull(confirmed.pendingUrl)
    }

    @Test
    fun cancellationAndFailureAreTerminalAndCannotResume() {
        val task = BrowserTaskStateMachine.begin(
            BrowserTaskStateMachine.create("chat:web-search", "https://example.com")
        )
        val cancelled = BrowserTaskStateMachine.cancel(task, "user cancelled")
        assertTrue(cancelled.isTerminal)
        assertEquals(cancelled, BrowserTaskStateMachine.requestNavigation(
            cancelled,
            "https://example.com/next",
            requiresConfirmation = false
        ))

        val failed = BrowserTaskStateMachine.fail(task, "renderer stopped")
        assertEquals(BrowserTaskPhase.FAILED, failed.phase)
        assertEquals(failed, BrowserTaskStateMachine.confirmNavigation(failed))
    }
}
