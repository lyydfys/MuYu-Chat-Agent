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
        val started = visible(task)
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
        val task = visible(
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

    @Test
    fun invisibleWindowCannotBeginOrNavigate() {
        val queued = BrowserTaskStateMachine.create("chat:session:message", "https://example.com")
        assertEquals(queued, BrowserTaskStateMachine.begin(queued))
        assertEquals(queued, BrowserTaskStateMachine.requestNavigation(queued, "https://example.com/next", false))
        assertEquals(BrowserTaskPhase.NAVIGATING, visible(queued).phase)
    }

    @Test
    fun failedBlockedAndCancelledTasksIgnoreLateCompletion() {
        val started = visible(BrowserTaskStateMachine.create("chat:session:message", "https://example.com"))
        listOf(
            BrowserTaskStateMachine.fail(started, "network"),
            BrowserTaskStateMachine.block(started, "unsafe"),
            BrowserTaskStateMachine.cancel(started)
        ).forEach { terminal ->
            assertEquals(terminal, BrowserTaskStateMachine.complete(terminal, "https://example.com"))
        }
    }

    @Test
    fun nextUserNavigationRetainsOwnerAndRejectsOldTaskEvents() {
        val started = visible(BrowserTaskStateMachine.create(
            "chat:session:message", "https://example.com", sessionId = "session", messageId = "message"
        ))
        val completed = BrowserTaskStateMachine.complete(started)
        val next = BrowserTaskStateMachine.reduce(completed, BrowserTaskEvent(
            completed.taskId, completed.navigationId, BrowserTaskAction.REQUEST_NAVIGATION,
            url = "https://result.test/article", userInitiated = true
        ))
        assertNotEquals(completed.taskId, next.taskId)
        assertEquals("session", next.sessionId)
        assertEquals("message", next.messageId)
        assertEquals(BrowserTaskPhase.AWAITING_CONFIRMATION, next.phase)
        assertEquals(next, BrowserTaskStateMachine.reduce(next, BrowserTaskEvent(
            completed.taskId, completed.navigationId, BrowserTaskAction.COMPLETE, url = completed.currentUrl
        )))
    }

    @Test
    fun redirectApprovalIsBoundToThePendingNavigationAndOrigin() {
        val started = visible(BrowserTaskStateMachine.create("chat:session:message", "https://search.test"))
        val pending = BrowserTaskStateMachine.reduce(started, BrowserTaskEvent(
            started.taskId, started.navigationId, BrowserTaskAction.REQUEST_NAVIGATION,
            url = "https://result.test:8443/article"
        ))
        assertEquals(pending, BrowserTaskStateMachine.reduce(pending, BrowserTaskEvent(
            pending.taskId, started.navigationId, BrowserTaskAction.CONFIRM_NAVIGATION
        )))
        assertEquals(pending, BrowserTaskStateMachine.reduce(pending, BrowserTaskEvent(
            pending.taskId, pending.navigationId, BrowserTaskAction.CONFIRM_NAVIGATION
        )))
        val approved = BrowserTaskStateMachine.reduce(pending, BrowserTaskEvent(
            pending.taskId, pending.navigationId, BrowserTaskAction.CONFIRM_NAVIGATION,
            userInitiated = true
        ))
        assertEquals(setOf("https://result.test:8443"), approved.approvedOrigins)
        val redirected = BrowserTaskStateMachine.reduce(approved, BrowserTaskEvent(
            approved.taskId, approved.navigationId, BrowserTaskAction.REQUEST_NAVIGATION,
            url = "https://result.test/article"
        ))
        assertEquals(BrowserTaskPhase.AWAITING_CONFIRMATION, redirected.phase)
    }

    @Test
    fun searchResultsRequireExplicitSelectionFromPublishedHttpsCandidates() {
        val searching = BrowserTaskStateMachine.beginBackgroundSearch(
            "chat-web-search", "session", "message", "release notes"
        )
        val results = BrowserTaskStateMachine.publishSearchResults(searching, listOf(
            "http://insecure.test/", "https://result.test/article",
            "https://result.test/article", "javascript:alert(1)"
        ), "2 results")
        assertEquals(BrowserTaskPhase.AWAITING_CONFIRMATION, results.phase)
        assertEquals(listOf("https://result.test/article"), results.candidateUrls)
        val unselected = BrowserTaskEvent(
            results.taskId, results.navigationId, BrowserTaskAction.SELECT_RESULT,
            url = "https://result.test/article"
        )
        assertEquals(results, BrowserTaskStateMachine.reduce(results, unselected))
        assertEquals(results, BrowserTaskStateMachine.reduce(results, unselected.copy(
            url = "https://other.test/article", userInitiated = true
        )))
        val selected = BrowserTaskStateMachine.reduce(results, unselected.copy(userInitiated = true))
        assertEquals(BrowserTaskKind.SOURCE, selected.kind)
        assertEquals(BrowserTaskPhase.QUEUED, selected.phase)
        assertEquals("https://result.test/article", selected.initialUrl)
        assertEquals(results.taskId, selected.parentTaskId)
        assertEquals("session", selected.sessionId)
        assertEquals("message", selected.messageId)
        assertEquals(selected, BrowserTaskStateMachine.reduce(selected, unselected.copy(userInitiated = true)))
    }

    @Test
    fun cancelledSearchIgnoresLateResultsAndSelection() {
        val searching = BrowserTaskStateMachine.beginBackgroundSearch(
            "chat-web-search", "session", "message", "release notes"
        )
        val cancelled = BrowserTaskStateMachine.reduce(searching, BrowserTaskEvent(
            searching.taskId, searching.navigationId, BrowserTaskAction.CANCEL
        ))
        assertEquals(BrowserTaskPhase.CANCELLED, cancelled.phase)
        assertEquals(cancelled, BrowserTaskStateMachine.publishSearchResults(
            cancelled, listOf("https://result.test/article"), null
        ))
        assertEquals(cancelled, BrowserTaskStateMachine.reduce(cancelled, BrowserTaskEvent(
            cancelled.taskId, cancelled.navigationId, BrowserTaskAction.SELECT_RESULT,
            url = "https://result.test/article", userInitiated = true
        )))
    }

    @Test
    fun lateNavigationEventsCannotCompleteOrReplaceNewNavigation() {
        val started = visible(BrowserTaskStateMachine.create(
            "chat:session:message", "https://source.test/start"
        ))
        val next = BrowserTaskStateMachine.reduce(started, BrowserTaskEvent(
            started.taskId, started.navigationId, BrowserTaskAction.REQUEST_NAVIGATION,
            url = "https://source.test/next", userInitiated = true
        ))
        assertEquals(BrowserTaskPhase.NAVIGATING, next.phase)
        assertEquals(next, BrowserTaskStateMachine.reduce(next, BrowserTaskEvent(
            started.taskId, started.navigationId, BrowserTaskAction.COMPLETE,
            url = "https://source.test/start"
        )))
        assertEquals(next, BrowserTaskStateMachine.reduce(next, BrowserTaskEvent(
            started.taskId, started.navigationId, BrowserTaskAction.REQUEST_NAVIGATION,
            url = "https://other.test/", userInitiated = true
        )))
    }

    private fun visible(task: BrowserTask): BrowserTask = BrowserTaskStateMachine.reduce(task,
        BrowserTaskEvent(task.taskId, task.navigationId, BrowserTaskAction.VISIBLE))
}
