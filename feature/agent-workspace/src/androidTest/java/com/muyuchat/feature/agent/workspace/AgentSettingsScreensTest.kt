package com.muyuchat.feature.agent.workspace

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muyuchat.core.designsystem.McaTheme
import com.muyuchat.feature.agent.workspace.ui.AgentWorkspaceContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * REQUIRES A CONNECTED DEVICE/EMULATOR. Execution status: NOT_RUN. The task forbids Gradle/build
 * and device runs, so none of these Compose assertions has been executed.
 *
 * Coverage intent: the tool-package surface exposes the real read-only ZIP preview, keeps the
 * catalog-exposure gate copy visible, disables import and has no enable switch; the memory page
 * renders real counts and a selected row and invokes the selection callback; the workflow page
 * renders the manual recipe list, its manual create button and the create dialog, with no schedule
 * control.
 */
@RunWith(AndroidJUnit4::class)
class AgentSettingsScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun toolPackagesShowTheRealPreviewAndTheCatalogExposureGate() {
        setContent(
            AgentWorkspaceUiState(
                surface = AgentWorkspaceSurface.TOOL_PACKAGES,
                settings = AgentSettingsUiState(
                    toolPackages = AgentToolPackagesUiState(
                        visible = true,
                        items = listOf(
                            AgentToolPackageUiItem(
                                packageId = "pkg-a",
                                displayName = "只读笔记",
                                versionLabel = "1.0.0",
                                toolCount = 1,
                                toolCountLabel = "1 个工具",
                                sideEffectLabel = "只读：workspace.read_text",
                                sourceLabel = "本机导入"
                            )
                        )
                    )
                )
            )
        )

        composeRule.onNodeWithText(AgentToolPackagePolicy.PACKAGE_CATALOG_EXPOSURE_REQUIRED_COPY)
            .assertIsDisplayed()
        composeRule.onNodeWithText("只读笔记").assertIsDisplayed()
        composeRule.onNodeWithText("1 个工具").assertIsDisplayed()
        composeRule.onNodeWithTag("toolPackages.preview").assertIsEnabled()
        composeRule.onNodeWithTag("toolPackages.import").assertIsNotEnabled()
        composeRule.onNodeWithText(
            "本页不提供启用开关：审批闭环已完成，但工具包目录接入仍需显式启用，" +
                "不会伪造启用状态。工具包不能携带可执行代码、网络客户端或第二套工具执行器。"
        ).assertIsDisplayed()
    }

    @Test
    fun toolPackagePreviewCardShowsMetadataOnly() {
        setContent(
            AgentWorkspaceUiState(
                surface = AgentWorkspaceSurface.TOOL_PACKAGES,
                settings = AgentSettingsUiState(
                    toolPackages = AgentToolPackagesUiState(
                        visible = true,
                        preview = AgentToolPackagePreviewUi(
                            packageId = "pkg-a",
                            displayName = "只读笔记",
                            versionLabel = "1.0.0",
                            toolCountLabel = "1 个工具",
                            sideEffectLabel = "只读",
                            sourceLabel = "本机 ZIP 预览",
                            resourceLabel = "1 个资源",
                            sizeLabel = "128 B",
                            tools = listOf(
                                AgentToolPackagePreviewToolUi(
                                    toolName = "read_brief",
                                    displayName = "Read brief",
                                    hostOperation = "workspace.read_text",
                                    sideEffectLabel = "只读",
                                    dataClassLabel = "私有",
                                    parametersLabel = "offset、limit",
                                    timeoutLabel = "10 秒"
                                )
                            )
                        )
                    )
                )
            )
        )

        composeRule.onNodeWithTag("toolPackages.previewCard").assertIsDisplayed()
        composeRule.onNodeWithText("仅预览").assertIsDisplayed()
        composeRule.onNodeWithText("宿主操作：workspace.read_text").assertIsDisplayed()
        composeRule.onNodeWithText("预览不会启用工具，也不会写入运行目录。").assertIsDisplayed()
    }

    @Test
    fun memoryShowsCountsAndSelectionInvokesThePortCallback() {
        var selected: String? = null
        setContent(
            state = AgentWorkspaceUiState(
                surface = AgentWorkspaceSurface.MEMORY,
                settings = AgentSettingsUiState(
                    memory = AgentMemoryUiState(
                        visible = true,
                        spaces = listOf(
                            AgentMemorySpaceUiItem(
                                spaceId = "space-a",
                                displayName = "项目笔记",
                                description = "任务复盘",
                                itemCount = 3,
                                itemsLabel = "3 条记忆",
                                egressLabel = "仅本地",
                                updatedLabel = null
                            )
                        ),
                        selectedSpaceId = "space-a"
                    )
                )
            ),
            actions = AgentWorkspaceActions(
                settings = AgentSettingsActions(
                    onSelectMemorySpace = { selected = it }
                )
            )
        )

        composeRule.onNodeWithTag("memory.item.space-a").assertIsDisplayed()
        composeRule.onNodeWithTag("memory.selection.info").assertIsDisplayed()
        composeRule.onNodeWithText("已选择").assertIsDisplayed()
        composeRule.onNodeWithTag("memory.item.space-a").performClick()
        assertEquals("space-a", selected)
    }

    @Test
    fun workflowsRenderManualRecipesAndTheCreateDialog() {
        var createClicks = 0
        setContent(
            state = AgentWorkspaceUiState(
                surface = AgentWorkspaceSurface.WORKFLOWS,
                settings = AgentSettingsUiState(
                    workflows = AgentWorkflowsUiState(
                        visible = true,
                        recipes = listOf(
                            AgentWorkflowRecipeUi(
                                recipeId = "wf-1",
                                name = "周报",
                                goalTemplatePreview = "总结本周笔记",
                                versionLabel = "v1",
                                updatedLabel = null
                            )
                        )
                    )
                )
            ),
            actions = AgentWorkspaceActions(
                settings = AgentSettingsActions(onOpenWorkflowCreate = { createClicks++ })
            )
        )

        composeRule.onNodeWithText("周报").assertIsDisplayed()
        composeRule.onNodeWithText("手动 · v1").assertIsDisplayed()
        composeRule.onNodeWithTag("workflows.create").performClick()
        assertEquals(1, createClicks)
    }

    @Test
    fun workflowCreateDialogShowsOnlyManualFields() {
        setContent(
            AgentWorkspaceUiState(
                surface = AgentWorkspaceSurface.WORKFLOWS,
                settings = AgentSettingsUiState(
                    workflows = AgentWorkflowsUiState(
                        visible = true,
                        createDraft = AgentWorkflowDraftUi(name = "周报", goalTemplate = "总结本周笔记")
                    )
                )
            )
        )

        composeRule.onNodeWithText("新建手动配方").assertIsDisplayed()
        composeRule.onNodeWithTag("workflows.name").assertIsDisplayed()
        composeRule.onNodeWithTag("workflows.goal").assertIsDisplayed()
        composeRule.onNodeWithTag("workflows.submit").assertIsDisplayed()
        composeRule.onNodeWithText("只保存名称与任务目标；不会创建定时任务，也不会在后台运行。")
            .assertIsDisplayed()
    }

    private fun setContent(
        state: AgentWorkspaceUiState,
        actions: AgentWorkspaceActions = AgentWorkspaceActions()
    ) {
        composeRule.setContent {
            McaTheme {
                AgentWorkspaceContent(
                    state = state,
                    actions = actions,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
