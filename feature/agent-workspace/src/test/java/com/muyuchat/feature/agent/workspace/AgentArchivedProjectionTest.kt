package com.muyuchat.feature.agent.workspace

import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentArchivedProjectionTest {

    private fun archivedTask() = testTask().copy(lifecycle = AgentTaskLifecycle.IMPORTED_ARCHIVED)

    @Test
    fun `includeArchived projects the toggle and archived row label`() {
        val archived = archivedTask()
        val state = projectWorkspaceState(
            projectionInput(tasks = listOf(archived), includeArchived = true)
        )

        assertTrue(state.home.includeArchived)
        assertFalse(state.home.empty)
        val item = state.home.tasks.single()
        assertTrue(item.archived)
        assertEquals(ARCHIVED_TASK_LABEL, item.statusLabel)
        assertFalse(item.isActive)
        assertTrue(state.home.activeTasks.isEmpty())
        assertEquals(listOf(archived.taskId), state.home.recentTasks.map { it.taskId })
    }

    @Test
    fun `default filter is off and archived rows stay hidden`() {
        val archived = archivedTask()
        val state = projectWorkspaceState(projectionInput(tasks = listOf(archived)))

        assertFalse(state.home.includeArchived)
        assertTrue(state.home.tasks.isEmpty())
        assertTrue(state.home.empty)
    }

    @Test
    fun `default filter is off and normal rows remain non-archived`() {
        val normal = testTask()
        val state = projectWorkspaceState(projectionInput(tasks = listOf(normal)))

        assertFalse(state.home.includeArchived)
        assertFalse(state.home.tasks.single().archived)
        assertFalse(state.home.tasks.single().statusLabel == ARCHIVED_TASK_LABEL)
    }

    @Test
    fun `archived detail is read-only and keeps metadata`() {
        val archived = archivedTask()
        val state = projectWorkspaceState(
            projectionInput(
                selectedTaskId = archived.taskId,
                tasks = listOf(archived),
                selectedTask = archived,
                runFeed = AgentRunFeed.Empty,
                includeArchived = true
            )
        )
        val detail = requireNotNull(state.detail)

        assertTrue(detail.archived)
        assertEquals(archived.taskId, detail.taskId)
        assertEquals(archived.goal, detail.goal)
        assertEquals(ARCHIVED_TASK_LABEL, detail.statusLabel)
        assertTrue(detail.statusDetail.orEmpty().contains("只读"))
        assertFalse(detail.loading)
        assertFalse(detail.canStart)
        assertFalse(detail.canStop)
        assertFalse(detail.canContinue)
        assertFalse(detail.canAnswerQuestion)
        assertFalse(detail.waitingForApproval)
        assertNull(detail.approval)
        assertNull(detail.question)
        assertTrue(detail.timeline.isEmpty())
    }

    @Test
    fun `archived detail ignores a stale run snapshot for runnable actions`() {
        val archived = archivedTask()
        val state = projectWorkspaceState(
            projectionInput(
                selectedTaskId = archived.taskId,
                tasks = listOf(archived),
                selectedTask = archived,
                runFeed = AgentRunFeed.Loaded(
                    testSnapshot(
                        task = archived,
                        run = testRun(state = AgentRunState.RUNNING)
                    )
                ),
                includeArchived = true
            )
        )
        val detail = requireNotNull(state.detail)

        assertTrue(detail.archived)
        assertFalse(detail.canStart)
        assertFalse(detail.canStop)
        assertFalse(detail.canContinue)
        assertFalse(detail.waitingForApproval)
    }

    @Test
    fun `normal task detail keeps runnable actions`() {
        val task = testTask()
        val state = projectWorkspaceState(
            projectionInput(
                selectedTaskId = task.taskId,
                tasks = listOf(task),
                selectedTask = task,
                runFeed = AgentRunFeed.Empty
            )
        )
        val detail = requireNotNull(state.detail)

        assertFalse(detail.archived)
        assertTrue(detail.canStart)
    }
}
