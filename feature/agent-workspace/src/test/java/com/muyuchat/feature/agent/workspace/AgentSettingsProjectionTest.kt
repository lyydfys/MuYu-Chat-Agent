package com.muyuchat.feature.agent.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSettingsProjectionTest {

    @Test
    fun `no settings surface leaves the existing home and detail navigation intact`() {
        val home = projectWorkspaceState(projectionInput())
        assertEquals(AgentWorkspaceSurface.HOME, home.surface)
        assertFalse(home.settings.toolPackages.visible)
        assertFalse(home.settings.memory.visible)
        assertFalse(home.settings.workflows.visible)

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
    fun `each settings surface projects to its own route surface`() {
        val toolPackages = projectWorkspaceState(
            projectionInput(
                settingsState = AgentSettingsUiState(
                    toolPackages = AgentToolPackagesUiState(visible = true)
                )
            )
        )
        assertEquals(AgentWorkspaceSurface.TOOL_PACKAGES, toolPackages.surface)

        val memory = projectWorkspaceState(
            projectionInput(
                settingsState = AgentSettingsUiState(
                    memory = AgentMemoryUiState(visible = true)
                )
            )
        )
        assertEquals(AgentWorkspaceSurface.MEMORY, memory.surface)

        val workflows = projectWorkspaceState(
            projectionInput(
                settingsState = AgentSettingsUiState(
                    workflows = AgentWorkflowsUiState(visible = true)
                )
            )
        )
        assertEquals(AgentWorkspaceSurface.WORKFLOWS, workflows.surface)
    }

    @Test
    fun `existing mcp and skills surfaces keep priority over settings surfaces`() {
        val state = projectWorkspaceState(
            projectionInput(
                skillsState = AgentSkillsUiState(visible = true),
                mcpState = AgentMcpUiState(visible = true),
                settingsState = AgentSettingsUiState(
                    toolPackages = AgentToolPackagesUiState(visible = true),
                    memory = AgentMemoryUiState(visible = true),
                    workflows = AgentWorkflowsUiState(visible = true)
                )
            )
        )
        assertEquals(AgentWorkspaceSurface.MCP, state.surface)
    }

    @Test
    fun `tool package projection bounds labels and keeps catalog exposure closed`() {
        val state = projectToolPackagesState(
            AgentToolPackagesProjectionInput(
                visible = true,
                packages = listOf(
                    AgentToolPackageItem(
                        packageId = "pkg-a",
                        displayName = "A",
                        version = "1.0.0",
                        toolCount = 2,
                        sideEffectSummary = "只读：workspace.read_text",
                        sourceLabel = "本机导入"
                    )
                )
            )
        )

        assertEquals(1, state.items.size)
        assertEquals("2 个工具", state.items.single().toolCountLabel)
        assertEquals("本机导入", state.items.single().sourceLabel)
        assertEquals(2, state.totalToolCount)
        assertTrue(state.previewEnabled)
        assertFalse(state.importEnabled)
        assertEquals(AgentToolPackagePolicy.PACKAGE_CATALOG_EXPOSURE_REQUIRED_COPY, state.gateMessage)
    }

    @Test
    fun `memory selection info uses the projected space and drops a stale id`() {
        val state = projectMemoryState(
            AgentMemoryProjectionInput(
                visible = true,
                spaces = listOf(
                    AgentMemorySpaceItem(
                        spaceId = "space-a",
                        displayName = "项目笔记",
                        description = "复盘",
                        itemCount = 3,
                        egressLabel = "仅本地"
                    )
                ),
                selectedSpaceId = "space-a"
            )
        )

        assertEquals(1, state.spaces.size)
        assertEquals(3, state.totalItemCount)
        assertEquals("3 条记忆", state.selectedSpace?.itemsLabel)
        assertEquals("仅本地", state.selectedSpace?.egressLabel)

        val stale = projectMemoryState(
            AgentMemoryProjectionInput(
                visible = true,
                spaces = state.spaces.map {
                    AgentMemorySpaceItem(
                        spaceId = it.spaceId,
                        displayName = it.displayName,
                        itemCount = it.itemCount
                    )
                },
                selectedSpaceId = "space-missing"
            )
        )
        assertNull(stale.selectedSpace)
    }

    @Test
    fun `workflow projection carries no schedule and validates the manual form`() {
        val state = projectWorkflowState(
            AgentWorkflowProjectionInput(
                visible = true,
                recipes = listOf(
                    AgentWorkflowRecipeItem(
                        recipeId = "wf-1",
                        name = "周报",
                        goalTemplate = "总结本周笔记",
                        versionLabel = "v1"
                    )
                )
            )
        )
        assertEquals("周报", state.recipes.single().name)
        assertEquals("手动", state.recipes.single().manualLabel)
        assertEquals("总结本周笔记", state.recipes.single().goalTemplatePreview)

        assertEquals("请输入配方名称。", workflowDraftValidationError(" ", "目标"))
        assertEquals("请输入任务目标。", workflowDraftValidationError("周报", "  "))
        assertNull(workflowDraftValidationError("周报", "总结本周笔记"))
        assertEquals("周报", normalizedWorkflowName("  周报  "))
        assertEquals("总结本周笔记", normalizedWorkflowGoal("  总结本周笔记  "))
    }
}
