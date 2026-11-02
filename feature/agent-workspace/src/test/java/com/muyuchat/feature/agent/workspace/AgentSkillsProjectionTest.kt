package com.muyuchat.feature.agent.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSkillsProjectionTest {

    private fun item(
        id: String,
        name: String = id,
        enabled: Boolean = false,
        version: Long = 1L,
        packageVersion: String = "",
        toolCount: Int = 0,
        description: String = ""
    ) = AgentSkillAdminItem(
        skillId = id,
        name = name,
        description = description,
        version = version,
        packageVersion = packageVersion,
        enabled = enabled,
        allowedToolCount = toolCount
    )

    @Test
    fun `projects version tool count and bounded description`() {
        val projected = projectSkillItem(
            item(
                id = "skill-1",
                name = "Notes Helper",
                version = 3L,
                packageVersion = "2.1.0",
                toolCount = 2,
                description = "x".repeat(500)
            )
        )
        assertEquals("Notes Helper", projected.name)
        assertEquals("2.1.0", projected.versionLabel)
        assertEquals("2 个工具", projected.allowedToolsLabel)
        assertTrue(projected.description.length <= 160)
    }

    @Test
    fun `version falls back to installation version and tool labels are explicit`() {
        assertEquals("v3", skillVersionLabel(item(id = "a", version = 3L)))
        assertEquals("不限制工具", skillAllowedToolsLabel(0))
        assertEquals("1 个工具", skillAllowedToolsLabel(1))
        assertEquals("7 个工具", skillAllowedToolsLabel(7))
    }

    @Test
    fun `enabled count and limit are derived from items`() {
        val three = listOf(
            item("a", enabled = true),
            item("b", enabled = true),
            item("c", enabled = false)
        )
        val state = projectSkillsState(AgentSkillsProjectionInput(items = three))
        assertEquals(2, state.enabledCount)
        assertFalse(state.limitReached)
        assertFalse(state.empty)
        assertTrue(state.canImport)
        assertTrue(state.canToggle(state.items.first { it.skillId == "c" }))
        assertTrue(state.canUninstall())

        val four = three.map { it.copy(enabled = true) } + item("d", enabled = true)
        val capped = projectSkillsState(AgentSkillsProjectionInput(items = four))
        assertEquals(4, capped.enabledCount)
        assertTrue(capped.limitReached)
        assertTrue(capped.canToggle(capped.items.first { it.enabled }))
        assertTrue(capped.canImport)
    }

    @Test
    fun `busy disables import toggle and uninstall while keeping list visible`() {
        val state = projectSkillsState(
            AgentSkillsProjectionInput(
                items = listOf(item("a", enabled = true), item("b")),
                busy = AgentSkillOperationKind.IMPORT_ZIP
            )
        )
        assertFalse(state.canImport)
        state.items.forEach { assertFalse(state.canToggle(it)) }
        assertFalse(state.canUninstall())
        assertEquals(2, state.items.size)
    }

    @Test
    fun `empty and loading projections stay distinct`() {
        val loading = projectSkillsState(AgentSkillsProjectionInput(loading = true))
        assertTrue(loading.loading)
        assertFalse(loading.empty)

        val empty = projectSkillsState(AgentSkillsProjectionInput(loading = false))
        assertTrue(empty.empty)
        assertFalse(empty.loading)
    }

    @Test
    fun `visible skills surface wins over task detail routing`() {
        val skills = AgentSkillsUiState(visible = true, items = listOf(projectSkillItem(item("a"))))
        val home = projectWorkspaceState(projectionInput(skillsState = skills))
        assertEquals(AgentWorkspaceSurface.SKILLS, home.surface)
        assertEquals(listOf("a"), home.skills.items.map { it.skillId })

        val hidden = projectWorkspaceState(projectionInput())
        assertEquals(AgentWorkspaceSurface.HOME, hidden.surface)

        val detail = projectWorkspaceState(
            projectionInput(
                selectedTaskId = TEST_TASK_ID,
                selectedTask = testTask(),
                runFeed = AgentRunFeed.Loaded(testSnapshot())
            )
        )
        assertEquals(AgentWorkspaceSurface.DETAIL, detail.surface)
    }

    @Test
    fun `uninstall confirmation is projected with the skill name`() {
        val state = projectSkillsState(
            AgentSkillsProjectionInput(
                items = listOf(item("a", name = "Alpha")),
                uninstallConfirmation = AgentSkillUninstallConfirmationUi("a", "Alpha")
            )
        )
        assertEquals("Alpha", state.uninstallConfirmation?.name)
    }
}
