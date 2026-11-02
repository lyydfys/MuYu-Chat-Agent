package com.muyuchat.feature.agent.workspace.ui

import com.muyuchat.feature.agent.workspace.AgentTaskDetailUiState
import com.muyuchat.feature.agent.workspace.AgentWorkspaceSurface
import com.muyuchat.feature.agent.workspace.AgentWorkspaceUiState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Presentation identity and source wiring; these are not device render/frame-time proofs. */
class AgentWorkspaceMotionContractTest {
    @Test
    fun `live detail updates preserve page identity while different tasks and home do not`() {
        val first = AgentWorkspaceUiState(
            detail = AgentTaskDetailUiState(taskId = "task-1", goal = "original")
        )
        val streamed = first.copy(detail = first.detail!!.copy(finalAnswer = "stream update"))
        assertEquals(first.surfaceContentKey(), streamed.surfaceContentKey())
        assertNotEquals(first.surfaceContentKey(), first.copy(detail = null).surfaceContentKey())
        assertNotEquals(
            first.surfaceContentKey(),
            first.copy(detail = first.detail!!.copy(taskId = "task-2")).surfaceContentKey()
        )
        // Retaining an outgoing value keeps its detail after the next state has returned home.
        val outgoing = first
        val incoming = first.copy(detail = null)
        assertEquals("task-1", outgoing.detail!!.taskId)
        assertEquals(null, incoming.detail)
    }

    @Test
    fun `tool surfaces have their own stable identity independent of stale task detail`() {
        for (surface in listOf(
            AgentWorkspaceSurface.MCP, AgentWorkspaceSurface.SKILLS,
            AgentWorkspaceSurface.TOOL_PACKAGES, AgentWorkspaceSurface.MEMORY,
            AgentWorkspaceSurface.WORKFLOWS
        )) {
            val empty = AgentWorkspaceUiState(surface = surface)
            assertEquals(
                empty.surfaceContentKey(),
                empty.copy(detail = AgentTaskDetailUiState("task-1", "goal")).surfaceContentKey()
            )
            assertNotEquals(AgentWorkspaceUiState().surfaceContentKey(), empty.surfaceContentKey())
        }
    }

    @Test
    fun `outgoing page renders its target snapshot instead of cleared current detail`() {
        val content = source("AgentWorkspaceContent.kt")
        assertTrue(content.contains("targetState = state,"))
        assertTrue(content.contains("contentKey = { it.surfaceContentKey() }"))
        val page = content.substringAfter(") { presentation ->").substringBefore("val approval =")
        assertTrue(page.contains("state = presentation,"))
        assertTrue(page.contains("presentation.detail == null"))
        assertFalse(page.contains("state.detail"))
        assertFalse(page.contains("state = state,"))
    }

    @Test
    fun `task entrance is bounded and scrolling permanently closes its reveal window`() {
        val home = source("AgentWorkspaceHomeScreen.kt")
        assertTrue(home.contains("var taskRevealActive by remember { mutableStateOf(true) }"))
        assertTrue(home.contains("snapshotFlow { latestHasTasks.value }.first { it }"))
        assertTrue(home.contains("delay(700)"))
        assertTrue(home.contains("snapshotFlow { listState.isScrollInProgress }.first { it }"))
        assertTrue(home.contains("animate = taskRevealActive && index <= 8"))
        assertTrue(home.contains("animate = taskRevealActive && activeTasks.size + index <= 8"))
        assertFalse(home.contains("taskRevealActive = true"))
    }

    @Test
    fun `task row navigation uses the mirrored semantic forward glyph`() {
        val home = source("AgentWorkspaceHomeScreen.kt")
        assertTrue(home.contains("imageVector = McaIcons.Forward"))
        assertFalse(home.contains("McaIcons.ChevronRight"))
    }

    private fun source(name: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory,
                "feature/agent-workspace/src/main/java/com/muyuchat/feature/agent/workspace/ui/$name")
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate $name")
    }
}
