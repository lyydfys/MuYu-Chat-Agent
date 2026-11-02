package com.muyuchat.feature.agent.workspace

import com.muyuchat.core.agent.contract.AgentEventPage
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentTaskPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkspacePagingTest {

    @Test
    fun taskPagesMergeDeduplicateAndPreferTheNewerRevision() {
        val taskA = testTask(
            taskId = "task-a",
            updatedAtMillis = TEST_NOW - 90_000L,
            revision = 1L
        )
        val taskB = testTask(
            taskId = "task-b",
            updatedAtMillis = TEST_NOW - 30_000L,
            revision = 1L
        )
        val taskAUpdated = testTask(
            taskId = "task-a",
            updatedAtMillis = TEST_NOW - 10_000L,
            revision = 2L
        )
        val taskC = testTask(
            taskId = "task-c",
            updatedAtMillis = TEST_NOW - 50_000L,
            revision = 1L
        )

        val merged = mergeTaskPage(
            current = listOf(taskA, taskB),
            page = AgentTaskPage(tasks = listOf(taskAUpdated, taskC), nextCursor = "cursor-2")
        )
        assertEquals(listOf("task-a", "task-b", "task-c"), merged.map { it.taskId })
        assertEquals(2L, merged.first().revision)

        val stale = testTask(
            taskId = "task-a",
            updatedAtMillis = TEST_NOW - 999_000L,
            revision = 1L
        )
        val kept = mergeTaskPage(current = listOf(taskAUpdated), page = AgentTaskPage(listOf(stale)))
        assertEquals(2L, kept.single().revision)
    }

    @Test
    fun emptyTaskPageLeavesCurrentListUntouched() {
        val current = listOf(testTask(taskId = "task-a"))
        val merged = mergeTaskPage(current, AgentTaskPage(emptyList(), nextCursor = null))
        assertSame(current, merged)
    }

    @Test
    fun taskPageCapKeepsTheMostRecentlyUpdatedTasks() {
        val tasks = (1..3).map { index ->
            testTask(
                taskId = "task-$index",
                updatedAtMillis = TEST_NOW - index * 1_000L
            )
        }
        val newer = (4..5).map { index ->
            testTask(
                taskId = "task-$index",
                updatedAtMillis = TEST_NOW - index * 1_000L
            )
        }
        val merged = mergeTaskPage(tasks, AgentTaskPage(newer), maxTasks = 3)
        assertEquals(3, merged.size)
        assertTrue(merged.all { it.updatedAtMillis >= TEST_NOW - 3_000L })
    }

    @Test
    fun eventPagesMergeDeduplicateAndSortBySequence() {
        val e1 = testEvent(1, AgentEventTypes.MODEL_ATTEMPT_STARTED)
        val e2 = testEvent(2, AgentEventTypes.TOOL_RESULT, "one")
        val e3 = testEvent(3, AgentEventTypes.TOOL_RESULT, "two")
        val e2Repeated = testEvent(2, AgentEventTypes.TOOL_RESULT, "one")

        val merged = mergeEventPages(
            current = listOf(e2, e1),
            page = AgentEventPage(events = listOf(e3, e2Repeated), nextSequence = 4L)
        )
        assertEquals(listOf(1L, 2L, 3L), merged.events.map { it.sequence })
        assertEquals(3, merged.events.size)
        assertTrue(!merged.truncated)
    }

    @Test
    fun eventPageCapKeepsTheNewestSequencesAndMarksTruncation() {
        val events = (1L..5L).map { sequence ->
            testEvent(sequence, AgentEventTypes.TOOL_RESULT, "chunk $sequence")
        }
        val merged = mergeEventPages(emptyList(), AgentEventPage(events), maxEvents = 3)
        assertEquals(listOf(3L, 4L, 5L), merged.events.map { it.sequence })
        assertTrue(merged.truncated)
    }

    @Test
    fun eventPagingAdvancesOnlyForwardAndStopsAtTheCap() {
        val page = AgentEventPage(events = emptyList(), nextSequence = 101L)
        assertEquals(101L, nextEventPageAfter(page, requestedAfter = 0L, loadedCount = 100))
        assertNull(nextEventPageAfter(page, requestedAfter = 101L, loadedCount = 100))
        assertNull(nextEventPageAfter(page, requestedAfter = 0L, loadedCount = 200))
        assertNull(
            nextEventPageAfter(
                AgentEventPage(events = emptyList(), nextSequence = null),
                requestedAfter = 0L,
                loadedCount = 1
            )
        )
    }

    @Test
    fun taskCursorStopsAtTheCap() {
        val page = AgentTaskPage(tasks = emptyList(), nextCursor = "cursor-2")
        assertEquals("cursor-2", nextTaskPageCursor(page, loadedCount = 50))
        assertNull(nextTaskPageCursor(page, loadedCount = 200))
    }
}
