package com.muyuchat.feature.agent.workspace

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muyuchat.core.agent.contract.AgentModelLoadState
import com.muyuchat.core.designsystem.McaTheme
import com.muyuchat.feature.agent.workspace.ui.AgentWorkspaceContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * REQUIRES A CONNECTED DEVICE/EMULATOR. These tests are NOT_RUN in the current verification
 * environment; the K10 verification command only runs the JVM unit tests.
 *
 * Coverage intent: route observation never starts a run; the chat-shaped home renders its exit and
 * drawer entry, empty state, task rows and composer; a typed goal enables send and creates through
 * the existing command port; the drawer opens task history and back returns home; the shield
 * "网络与权限" dialog toggles the existing network preference; the catalog-driven model picker
 * shows the projected protocol/offline labels; approval sheet actions and timeline/artifact
 * rendering stay honest.
 */
@RunWith(AndroidJUnit4::class)
class AgentWorkspaceRouteTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun observingTheRouteNeverStartsOrCreatesWork() {
        val goal = "整理 notes.md 并写入 summary.md"
        val queries = FakeAgentWorkspaceQueries(
            tasks = listOf(uiTask(goal = goal)),
            snapshot = uiSnapshot(task = uiTask(goal = goal))
        )
        val commands = FakeAgentWorkspaceCommands()
        composeRule.setContent {
            McaTheme {
                AgentWorkspaceRoute(
                    queries = queries,
                    commands = commands,
                    modelCatalog = FakeAgentModelCatalog(),
                    onBack = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        waitUntilDisplayed("agent.home.task.$TEST_TASK")
        composeRule.onNodeWithTag("agent.home").assertIsDisplayed()
        composeRule.onNodeWithTag("agent.composer").assertIsDisplayed()
        assertEquals(0, commands.startCommands.size)
        assertEquals(0, commands.createCommands.size)
        assertEquals(0, commands.continueCommands.size)
    }

    @Test
    fun homeShowsEmptyStateAndComposerControls() {
        val queries = FakeAgentWorkspaceQueries(tasks = emptyList())
        composeRule.setContent {
            McaTheme {
                AgentWorkspaceRoute(
                    queries = queries,
                    commands = FakeAgentWorkspaceCommands(),
                    modelCatalog = FakeAgentModelCatalog(),
                    onBack = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        waitForText("开始一个 Agent 任务")
        composeRule.onNodeWithText("开始一个 Agent 任务").assertIsDisplayed()
        composeRule.onNodeWithText("描述目标，Agent 会规划步骤并在本机执行。")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("agent.composer").assertIsDisplayed()
        composeRule.onNodeWithTag("agent.composer.input").assertIsDisplayed()
        composeRule.onNodeWithTag("agent.model").assertIsDisplayed()
        composeRule.onNodeWithTag("agent.send").assertIsDisplayed()
        composeRule.onNodeWithTag("agent.send").assertIsNotEnabled()
        // One leading control, matching the Chat page; the Chat exit lives in the drawer footer.
        composeRule.onNodeWithContentDescription("打开任务与工具抽屉").assertIsDisplayed()
    }

    @Test
    fun composerSendCreatesTheTaskThroughTheExistingCommandPort() {
        val queries = FakeAgentWorkspaceQueries(tasks = emptyList())
        val commands = FakeAgentWorkspaceCommands()
        composeRule.setContent {
            McaTheme {
                AgentWorkspaceRoute(
                    queries = queries,
                    commands = commands,
                    modelCatalog = FakeAgentModelCatalog(),
                    onBack = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        waitForTag("agent.composer.input")
        composeRule.onNodeWithTag("agent.send").assertIsNotEnabled()
        composeRule.onNodeWithTag("agent.composer.input").performTextInput("整理 notes.md")
        composeRule.waitUntil(5_000L) {
            runCatching {
                composeRule.onNodeWithTag("agent.send").assertIsEnabled()
            }.isSuccess
        }
        composeRule.onNodeWithTag("agent.send").performClick()
        composeRule.waitUntil(5_000L) { commands.createCommands.isNotEmpty() }
        assertEquals(1, commands.createCommands.size)
        assertEquals("整理 notes.md", commands.createCommands.single().goal)
        assertEquals(0, commands.startCommands.size)
    }

    @Test
    fun shieldDialogTogglesTheExistingNetworkPreference() {
        composeRule.setContent {
            McaTheme {
                AgentWorkspaceRoute(
                    queries = FakeAgentWorkspaceQueries(tasks = emptyList()),
                    commands = FakeAgentWorkspaceCommands(),
                    modelCatalog = FakeAgentModelCatalog(),
                    onBack = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        waitForTag("agent.composer")
        composeRule.onNodeWithContentDescription("网络与权限说明").performClick()
        waitForText("网络与权限")
        waitForText("切换为联网模式")
        composeRule.onNodeWithText("切换为联网模式").assertIsDisplayed()
        composeRule.onNodeWithText("切换为联网模式").performClick()
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithText("网络与权限").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithContentDescription("网络与权限说明").performClick()
        waitForText("切换为离线模式")
        composeRule.onNodeWithText("切换为离线模式").assertIsDisplayed()
    }

    @Test
    fun taskOpensFromDrawerAndBackReturnsHome() {
        val goal = "整理 notes.md 并写入 summary.md"
        val queries = FakeAgentWorkspaceQueries(
            tasks = listOf(uiTask(goal = goal)),
            snapshot = uiSnapshot(task = uiTask(goal = goal))
        )
        composeRule.setContent {
            McaTheme {
                AgentWorkspaceRoute(
                    queries = queries,
                    commands = FakeAgentWorkspaceCommands(),
                    modelCatalog = FakeAgentModelCatalog(),
                    onBack = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        waitUntilDisplayed("agent.home.task.$TEST_TASK")
        composeRule.onNodeWithContentDescription("打开任务与工具抽屉").performClick()
        waitUntilDisplayed("agent.drawer.task.$TEST_TASK")
        composeRule.onNodeWithTag("agent.drawer.exit").assertIsDisplayed()
        composeRule.onNodeWithTag("agent.drawer.task.$TEST_TASK").performClick()
        waitForText("执行活动")
        waitForText("workspace.read_text")
        composeRule.onNodeWithText("Agent 任务").assertIsDisplayed()
        composeRule.onNodeWithText("workspace.read_text").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("返回工作区").performClick()
        waitUntilDisplayed("agent.home.task.$TEST_TASK")
        composeRule.onNodeWithTag("agent.composer").assertIsDisplayed()
    }

    @Test
    fun approvalSheetShowsBoundedTargetsAndPinnedActions() {
        val state = AgentWorkspaceUiState(
            detail = detailState(
                status = AgentWorkspaceStatus.WAITING_APPROVAL,
                approval = approvalUi(),
                waitingForApproval = true
            ),
            approvalSheetVisible = true
        )
        composeRule.setContent {
            McaTheme {
                AgentWorkspaceContent(
                    state = state,
                    actions = AgentWorkspaceActions(),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        composeRule.onNodeWithText("允许一次").assertIsDisplayed()
        composeRule.onNodeWithText("拒绝").assertIsDisplayed()
        composeRule.onNodeWithText("覆盖变更").assertIsDisplayed()
        composeRule.onNodeWithText("路径：artifacts/summary.md").assertIsDisplayed()
    }

    @Test
    fun modelPickerShowsProtocolModeAndOfflineAvailabilityFromTheCatalog() {
        composeRule.setContent {
            McaTheme {
                AgentWorkspaceRoute(
                    queries = FakeAgentWorkspaceQueries(tasks = emptyList()),
                    commands = FakeAgentWorkspaceCommands(),
                    modelCatalog = FakeAgentModelCatalog(),
                    onBack = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        waitForTag("agent.model")
        composeRule.onNodeWithTag("agent.model").performClick()
        waitUntilDisplayed("agent.model.sheet")
        waitForText("提示词协议 V1")
        composeRule.onNodeWithText("选择模型").assertIsDisplayed()
        composeRule.onNodeWithTag("agent.model.section.local").assertIsDisplayed()
        composeRule.onNodeWithText("本地模型").assertIsDisplayed()
        composeRule.onNodeWithText("提示词协议 V1").assertIsDisplayed()
        composeRule.onNodeWithText("离线可用").assertIsDisplayed()
    }

    @Test
    fun unloadedLocalModelShowsLoadActionThatLeavesForTheChatPage() {
        val unloaded = uiChoice().copy(
            modelId = "model-ui-unloaded",
            displayName = "Qwen3 8B",
            availableOffline = false,
            loadState = AgentModelLoadState.UNLOADED
        )
        var backInvocations = 0
        composeRule.setContent {
            McaTheme {
                AgentWorkspaceRoute(
                    queries = FakeAgentWorkspaceQueries(tasks = emptyList()),
                    commands = FakeAgentWorkspaceCommands(),
                    modelCatalog = FakeAgentModelCatalog(listOf(uiChoice(), unloaded)),
                    onBack = { backInvocations++ },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        waitForTag("agent.model")
        composeRule.onNodeWithTag("agent.model").performClick()
        waitUntilDisplayed("agent.model.sheet")
        composeRule.onNodeWithTag("agent.model.section.local").assertIsDisplayed()
        composeRule.onNodeWithTag("agent.model.load.model-ui-unloaded").assertIsDisplayed()
        composeRule.onNodeWithTag("agent.model.load.model-ui-unloaded").performClick()
        composeRule.waitForIdle()
        assertEquals(1, backInvocations)
    }

    @Test
    fun unloadedLocalModelDirectLoadUsesTheHostLoadPath() {
        val unloaded = uiChoice().copy(
            modelId = "model-ui-unloaded",
            displayName = "Qwen3 8B",
            availableOffline = false,
            loadState = AgentModelLoadState.UNLOADED
        )
        val loadedModelIds = mutableListOf<String>()
        composeRule.setContent {
            McaTheme {
                AgentWorkspaceRoute(
                    queries = FakeAgentWorkspaceQueries(tasks = emptyList()),
                    commands = FakeAgentWorkspaceCommands(),
                    modelCatalog = FakeAgentModelCatalog(listOf(uiChoice(), unloaded)),
                    onBack = {},
                    onLoadLocalModel = { loadedModelIds += it },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        waitForTag("agent.model")
        composeRule.onNodeWithTag("agent.model").performClick()
        waitUntilDisplayed("agent.model.sheet")
        composeRule.onNodeWithTag("agent.model.load.direct.model-ui-unloaded")
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("model-ui-unloaded"), loadedModelIds)
    }

    @Test
    fun collapsedTimelineAndUncommittedArtifactAreRenderedHonestly() {
        val timeline = listOf(
            AgentTimelineRow(
                key = "event-ui-2",
                kind = AgentTimelineKind.TOOL,
                tone = AgentStatusTone.NEUTRAL,
                title = "workspace.list",
                summary = "3 个文件",
                timeText = "12:00",
                eventIds = listOf("event-ui-2", "event-ui-3", "event-ui-4"),
                details = listOf("#2 · 12:00 · 工具结果\n3 个文件"),
                repeatCount = 3,
                expandable = true,
                expanded = false
            )
        )
        val artifacts = listOf(
            AgentArtifactUi(
                artifactId = "artifact-ui-1",
                relativePath = "artifacts/draft.md",
                mediaType = "text/markdown",
                sizeText = "1.5 KB",
                sha256Short = "aaaaaaaaaaaa…",
                committed = false,
                statusText = "写入中",
                createdText = "12:00"
            )
        )
        val state = AgentWorkspaceUiState(
            detail = detailState().copy(
                timeline = timeline,
                artifacts = artifacts,
                canStop = true
            )
        )
        composeRule.setContent {
            McaTheme {
                AgentWorkspaceContent(
                    state = state,
                    actions = AgentWorkspaceActions(),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        composeRule.onNodeWithText("workspace.list").assertIsDisplayed()
        composeRule.onNodeWithText("×3").assertIsDisplayed()
        composeRule.onNodeWithText("不可打开").assertIsDisplayed()
        assertTrue(state.detail?.artifacts?.single()?.committed == false)
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitUntilDisplayed(tag: String) {
        composeRule.waitUntil(5_000L) {
            runCatching {
                composeRule.onNodeWithTag(tag).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
