package com.muyuchat.feature.agent.workspace

import com.muyuchat.core.agent.contract.AgentArtifactCommitState
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentModelLoadState
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkspaceProjectionTest {

    @Test
    fun requiredExplicitVisualStatesExistInTheEnum() {
        val required = setOf(
            AgentWorkspaceStatus.EMPTY,
            AgentWorkspaceStatus.LOADING,
            AgentWorkspaceStatus.GENERATING,
            AgentWorkspaceStatus.WAITING_RESOURCE,
            AgentWorkspaceStatus.WAITING_APPROVAL,
            AgentWorkspaceStatus.DENIED,
            AgentWorkspaceStatus.FAILED,
            AgentWorkspaceStatus.STOPPING,
            AgentWorkspaceStatus.CANCELLED,
            AgentWorkspaceStatus.SUCCEEDED,
            AgentWorkspaceStatus.RESTORED,
            AgentWorkspaceStatus.NO_MODEL,
            AgentWorkspaceStatus.PROTOCOL_UNSUPPORTED,
            AgentWorkspaceStatus.BUDGET_LIMIT,
            AgentWorkspaceStatus.MCP_OFFLINE,
            AgentWorkspaceStatus.SKILL_MISSING_TOOL
        )
        assertTrue(AgentWorkspaceStatus.entries.containsAll(required))
    }

    @Test
    fun everyDisplayStatusMapsToAWorkspaceStatus() {
        AgentDisplayStatus.entries.forEach { displayStatus ->
            val status = statusFromDisplayStatus(displayStatus)
            assertTrue(
                "display status $displayStatus must map to a labelled state",
                statusLabel(status).isNotBlank()
            )
        }
        assertEquals(
            AgentWorkspaceStatus.RESTORED,
            statusFromDisplayStatus(AgentDisplayStatus.INTERRUPTED)
        )
        assertEquals(
            AgentWorkspaceStatus.GENERATING,
            statusFromDisplayStatus(AgentDisplayStatus.RUNNING)
        )
    }

    @Test
    fun activeTasksArePinnedBeforeNewerFinishedTasks() {
        val running = testTask(
            taskId = "task-running",
            displayStatus = AgentDisplayStatus.RUNNING,
            updatedAtMillis = TEST_NOW - 600_000L
        )
        val succeeded = testTask(
            taskId = "task-succeeded",
            displayStatus = AgentDisplayStatus.SUCCEEDED,
            updatedAtMillis = TEST_NOW - 10_000L
        )
        val ordered = orderTasks(listOf(succeeded, running))
        assertEquals(listOf("task-running", "task-succeeded"), ordered.map { it.taskId })

        val state = projectWorkspaceState(projectionInput(tasks = listOf(succeeded, running)))
        assertEquals(listOf("task-running"), state.home.activeTasks.map { it.taskId })
        assertEquals(listOf("task-succeeded"), state.home.recentTasks.map { it.taskId })
        assertFalse(state.home.empty)
        assertEquals(AgentWorkspaceStatus.IDLE, state.home.status)
    }

    @Test
    fun emptyAndLoadingHomeStatesAreExplicit() {
        val loadingState = projectWorkspaceState(
            projectionInput(tasks = emptyList()).copy(
                taskFeed = AgentTaskFeed(tasks = emptyList(), loading = true)
            )
        )
        assertEquals(AgentWorkspaceStatus.LOADING, loadingState.home.status)
        assertFalse(loadingState.home.empty)

        val emptyState = projectWorkspaceState(projectionInput(tasks = emptyList()))
        assertEquals(AgentWorkspaceStatus.EMPTY, emptyState.home.status)
        assertTrue(emptyState.home.empty)
    }

    @Test
    fun runStatesProjectToTheFrozenRunMachine() {
        fun status(
            state: AgentRunState,
            reason: AgentPauseReason = AgentPauseReason.NONE,
            events: List<AgentEvent> = emptyList()
        ) = projectRunStatus(testSnapshot(run = testRun(state = state, reason = reason)), events)

        assertEquals(AgentWorkspaceStatus.GENERATING, status(AgentRunState.RUNNING))
        assertEquals(AgentWorkspaceStatus.QUEUED, status(AgentRunState.QUEUED))
        assertEquals(
            AgentWorkspaceStatus.WAITING_RESOURCE,
            status(AgentRunState.WAITING_RESOURCE)
        )
        assertEquals(
            AgentWorkspaceStatus.WAITING_APPROVAL,
            status(AgentRunState.WAITING_APPROVAL)
        )
        assertEquals(AgentWorkspaceStatus.WAITING_USER, status(AgentRunState.WAITING_USER))
        assertEquals(AgentWorkspaceStatus.PAUSING, status(AgentRunState.PAUSING))
        assertEquals(AgentWorkspaceStatus.STOPPING, status(AgentRunState.STOPPING))
        assertEquals(AgentWorkspaceStatus.NEEDS_REVIEW, status(AgentRunState.NEEDS_REVIEW))
        assertEquals(AgentWorkspaceStatus.SUCCEEDED, status(AgentRunState.SUCCEEDED))
        assertEquals(AgentWorkspaceStatus.FAILED, status(AgentRunState.FAILED))
        assertEquals(AgentWorkspaceStatus.CANCELLED, status(AgentRunState.CANCELLED))
        assertEquals(AgentWorkspaceStatus.RESTORED, status(AgentRunState.INTERRUPTED))
        assertEquals(AgentWorkspaceStatus.PAUSED, status(AgentRunState.SUPERSEDED))

        assertEquals(
            AgentWorkspaceStatus.DENIED,
            status(AgentRunState.PAUSED, AgentPauseReason.APPROVAL_EXPIRED)
        )
        assertEquals(
            AgentWorkspaceStatus.DENIED,
            status(AgentRunState.PAUSED, AgentPauseReason.PERMISSION_DENIED_LOOP)
        )
        assertEquals(
            AgentWorkspaceStatus.BUDGET_LIMIT,
            status(AgentRunState.PAUSED, AgentPauseReason.BUDGET_LIMIT)
        )
        assertEquals(
            AgentWorkspaceStatus.BUDGET_LIMIT,
            status(AgentRunState.PAUSED, AgentPauseReason.CONTEXT_LIMIT)
        )
        assertEquals(
            AgentWorkspaceStatus.PROTOCOL_UNSUPPORTED,
            status(AgentRunState.PAUSED, AgentPauseReason.MODEL_PROTOCOL_ERROR)
        )
        assertEquals(
            AgentWorkspaceStatus.RESTORED,
            status(AgentRunState.PAUSED, AgentPauseReason.INTERRUPTED)
        )
        assertEquals(
            AgentWorkspaceStatus.PAUSED,
            status(AgentRunState.PAUSED, AgentPauseReason.USER_REQUESTED)
        )
    }

    @Test
    fun runningStatusShowsMcpAndSkillHintsFromSafeSummaries() {
        val mcpEvent = testEvent(
            sequence = 5,
            type = AgentEventTypes.TOOL_RESULT,
            summary = "MCP_OFFLINE: server docs 已断线"
        )
        val skillEvent = testEvent(
            sequence = 6,
            type = AgentEventTypes.TOOL_RESULT,
            summary = "SKILL_MISSING_TOOL: bash 不可用"
        )
        assertEquals(
            AgentWorkspaceStatus.MCP_OFFLINE,
            projectRunStatus(testSnapshot(run = testRun(AgentRunState.RUNNING)), listOf(mcpEvent))
        )
        assertEquals(
            AgentWorkspaceStatus.SKILL_MISSING_TOOL,
            projectRunStatus(testSnapshot(run = testRun(AgentRunState.RUNNING)), listOf(skillEvent))
        )
        assertEquals(
            AgentWorkspaceStatus.GENERATING,
            projectRunStatus(
                testSnapshot(run = testRun(AgentRunState.RUNNING)),
                listOf(testEvent(7, AgentEventTypes.TOOL_RESULT, "已读取 1.2 KB"))
            )
        )
    }

    @Test
    fun defaultModelPrefersOfflineProtocolCapableChoice() {
        val none = testChoice(modelId = "none", protocolMode = AgentProtocolMode.NONE)
        val cloud = testChoice(
            modelId = "cloud",
            protocolMode = AgentProtocolMode.NATIVE,
            availableOffline = false
        )
        val local = testChoice(
            modelId = "local",
            protocolMode = AgentProtocolMode.PROMPTED_V1,
            availableOffline = true
        )
        assertEquals("local", defaultModelChoice(listOf(none, cloud, local))?.modelId)
        assertEquals("cloud", defaultModelChoice(listOf(none, cloud))?.modelId)
        assertEquals("none", defaultModelChoice(listOf(none))?.modelId)
        assertNull(defaultModelChoice(emptyList()))
        assertEquals(
            "cloud",
            resolveSelectedModel(listOf(none, cloud, local), modelChoiceKey(cloud))?.modelId
        )
        assertEquals(
            "local",
            resolveSelectedModel(listOf(none, cloud, local), "missing-key")?.modelId
        )
    }

    @Test
    fun modelPickerExposesProtocolOfflineAndBlockedStates() {
        val noneModel = testChoice(
            modelId = "none",
            displayName = "No Tools",
            protocolMode = AgentProtocolMode.NONE
        )
        val state = projectWorkspaceState(
            projectionInput(
                choices = listOf(noneModel),
                selectedModelKey = modelChoiceKey(noneModel)
            )
        )
        assertEquals("不支持 Agent 协议", state.models.selected?.protocolLabel)
        assertEquals(
            AgentWorkspaceStatus.PROTOCOL_UNSUPPORTED,
            state.models.blockedStatus
        )
        assertFalse(state.models.protocolSupported)
        assertFalse(state.models.canCreate)

        val noModelState = projectWorkspaceState(projectionInput(choices = emptyList()))
        assertEquals(AgentWorkspaceStatus.NO_MODEL, noModelState.models.blockedStatus)
        assertFalse(noModelState.models.canCreate)
    }

    @Test
    fun modelPickerGroupsLocalAndCloudChoicesFromTheCatalogProviderId() {
        val localLoaded = testChoice(modelId = "local-loaded")
        val localUnloaded = testChoice(
            modelId = "local-unloaded",
            availableOffline = false,
            loadState = AgentModelLoadState.UNLOADED
        )
        val cloudNative = testChoice(
            modelId = "cloud-native",
            providerId = "cloud:1",
            protocolMode = AgentProtocolMode.NATIVE,
            availableOffline = false
        )
        val cloudNone = testChoice(
            modelId = "cloud-none",
            providerId = "cloud:2",
            protocolMode = AgentProtocolMode.NONE,
            availableOffline = false
        )
        val state = projectWorkspaceState(
            projectionInput(
                choices = listOf(localLoaded, localUnloaded, cloudNative, cloudNone),
                networkEnabled = true
            )
        )
        assertEquals(
            listOf("local-loaded", "local-unloaded"),
            state.models.localOptions.map { it.modelId }
        )
        assertEquals(
            listOf("cloud-native", "cloud-none"),
            state.models.cloudOptions.map { it.modelId }
        )
        assertEquals(4, state.models.options.size)
        assertTrue(state.models.localOptions.all { it.isLocal })
        assertTrue(state.models.cloudOptions.none { it.isLocal })
        assertTrue(
            state.models.cloudOptions.first { it.modelId == "cloud-native" }.selectable
        )
        assertFalse(state.models.cloudOptions.first { it.modelId == "cloud-none" }.selectable)
        // The default selection still prefers the loaded local runnable model.
        assertEquals("local-loaded", state.models.selected?.modelId)
        assertNull(state.models.selectionReason)
        assertTrue(state.models.canCreate)
    }

    @Test
    fun cloudProtocolNoneIsVisibleButDisabledWithExactReason() {
        val cloudNone = testChoice(
            modelId = "cloud-none",
            displayName = "Unsupported Cloud",
            providerId = "cloud:unsupported",
            protocolMode = AgentProtocolMode.NONE,
            availableOffline = false
        )
        val state = projectWorkspaceState(
            projectionInput(
                choices = listOf(cloudNone),
                selectedModelKey = modelChoiceKey(cloudNone),
                networkEnabled = true
            )
        )
        assertTrue(state.models.localOptions.isEmpty())
        val option = state.models.cloudOptions.single()
        assertEquals("Unsupported Cloud", option.displayName)
        assertEquals("不支持 Agent 协议", option.protocolLabel)
        assertFalse(option.selectable)
        assertEquals("该协议暂不支持 Agent", state.models.selectionReason)
        assertEquals(AgentWorkspaceStatus.PROTOCOL_UNSUPPORTED, state.models.blockedStatus)
        assertFalse(state.models.canCreate)
    }

    @Test
    fun loadedModelStaysSelectableAndRunnable() {
        val loaded = testChoice(modelId = "loaded")
        val state = projectWorkspaceState(
            projectionInput(choices = listOf(loaded), networkEnabled = false)
        )
        assertEquals(AgentModelLoadState.LOADED, state.models.selected?.loadState)
        assertEquals(AgentModelLoadState.LOADED, state.models.loadState)
        assertEquals(AgentModelLoadState.LOADED, state.models.options.single().loadState)
        assertTrue(state.models.protocolSupported)
        assertTrue(state.models.offlineCompatible)
        assertTrue(state.models.canCreate)
        assertNull(state.models.blockedStatus)
    }

    @Test
    fun installedButUnloadedModelIsVisibleButNeverRunnableEvenWhenNetworkIsEnabled() {
        val unloaded = testChoice(
            modelId = "installed",
            displayName = "Qwen3 8B",
            protocolMode = AgentProtocolMode.PROMPTED_V1,
            availableOffline = false,
            loadState = AgentModelLoadState.UNLOADED
        )
        val state = projectWorkspaceState(
            projectionInput(choices = listOf(unloaded), networkEnabled = true)
        )
        // Visible: the installed model is still in the picker instead of disappearing.
        assertEquals(1, state.models.options.size)
        assertEquals("Qwen3 8B", state.models.options.single().displayName)
        assertEquals(AgentModelLoadState.UNLOADED, state.models.options.single().loadState)
        assertTrue(state.models.options.single().isLocal)
        assertTrue(state.models.options.single().selectable)
        assertTrue(state.models.cloudOptions.isEmpty())
        assertEquals(AgentModelLoadState.UNLOADED, state.models.selected?.loadState)
        assertEquals(AgentModelLoadState.UNLOADED, state.models.loadState)
        assertEquals(MODEL_UNLOADED_SELECTION_REASON, state.models.selectionReason)
        // Network policy is satisfied, but a not-loaded model must never be runnable.
        assertTrue(state.models.offlineCompatible)
        assertTrue(state.models.protocolSupported)
        assertEquals(AgentWorkspaceStatus.MODEL_UNLOADED, state.models.blockedStatus)
        assertFalse(state.models.canCreate)
    }

    @Test
    fun selectingAnUnloadedModelNeverEnablesStartingTheTask() {
        val task = testTask()
        val unloaded = testChoice(
            modelId = "installed",
            availableOffline = false,
            loadState = AgentModelLoadState.UNLOADED
        )
        val state = projectWorkspaceState(
            projectionInput(
                selectedTaskId = task.taskId,
                tasks = listOf(task),
                selectedTask = task,
                runFeed = AgentRunFeed.NoRun,
                choices = listOf(unloaded),
                selectedModelKey = modelChoiceKey(unloaded),
                networkEnabled = true
            )
        )
        assertEquals(AgentModelLoadState.UNLOADED, state.models.loadState)
        assertFalse(state.models.canCreate)
        assertFalse(requireNotNull(state.detail).canStart)
    }

    @Test
    fun unloadedModelIsNeverPreferredOverALoadedOneAndNeverFakesLoaded() {
        val unloaded = testChoice(
            modelId = "installed",
            availableOffline = true,
            loadState = AgentModelLoadState.UNLOADED
        )
        val loaded = testChoice(modelId = "loaded", availableOffline = true)
        val state = projectWorkspaceState(
            projectionInput(choices = listOf(unloaded, loaded), networkEnabled = true)
        )
        assertEquals("loaded", state.models.selected?.modelId)
        assertEquals(AgentModelLoadState.LOADED, state.models.selected?.loadState)
        // The unloaded entry remains visible and is honestly marked unloaded.
        assertEquals(2, state.models.options.size)
        assertEquals(
            AgentModelLoadState.UNLOADED,
            state.models.options.first { it.modelId == "installed" }.loadState
        )
        assertTrue(state.models.canCreate)
    }

    @Test
    fun onlyUnloadedCatalogSelectsFirstEntryHonestlyWithoutClaimingRunnable() {
        val first = testChoice(
            modelId = "installed-a",
            availableOffline = false,
            loadState = AgentModelLoadState.UNLOADED
        )
        val second = testChoice(
            modelId = "installed-b",
            availableOffline = false,
            loadState = AgentModelLoadState.UNLOADED
        )
        val state = projectWorkspaceState(
            projectionInput(choices = listOf(first, second), networkEnabled = true)
        )
        assertEquals("installed-a", state.models.selected?.modelId)
        assertEquals(AgentModelLoadState.UNLOADED, state.models.selected?.loadState)
        assertEquals(AgentWorkspaceStatus.MODEL_UNLOADED, state.models.blockedStatus)
        assertFalse(state.models.canCreate)
        assertTrue(state.models.options.none { it.loadState == AgentModelLoadState.LOADED })
    }

    @Test
    fun offlineModeBlocksCloudModelUntilNetworkIsEnabled() {
        val cloud = testChoice(
            modelId = "cloud",
            providerId = "cloud:1",
            protocolMode = AgentProtocolMode.NATIVE,
            availableOffline = false
        )
        val offlineState = projectWorkspaceState(projectionInput(choices = listOf(cloud)))
        assertTrue(offlineState.models.localOptions.isEmpty())
        assertEquals(1, offlineState.models.cloudOptions.size)
        assertFalse(offlineState.models.offlineCompatible)
        assertFalse(offlineState.models.canCreate)
        assertEquals(AgentWorkspaceStatus.NO_MODEL, offlineState.models.blockedStatus)
        assertEquals(MODEL_OFFLINE_SELECTION_REASON, offlineState.models.selectionReason)
        assertTrue(offlineState.models.selected?.offlineLabel?.contains("当前离线") == true)

        val onlineState = projectWorkspaceState(
            projectionInput(choices = listOf(cloud), networkEnabled = true)
        )
        assertTrue(onlineState.models.offlineCompatible)
        assertTrue(onlineState.models.canCreate)
        assertNull(onlineState.models.blockedStatus)
        assertNull(onlineState.models.selectionReason)
    }

    @Test
    fun detailProjectsFinalAnswerArtifactsAndRunActions() {
        val task = testTask(displayStatus = AgentDisplayStatus.SUCCEEDED, lastRunId = TEST_RUN_ID)
        val run = testRun(AgentRunState.SUCCEEDED)
        val committed = testArtifact(artifactId = "artifact-1")
        val preparing = testArtifact(
            artifactId = "artifact-2",
            path = "artifacts/draft.md",
            commitState = AgentArtifactCommitState.PREPARING
        )
        val terminal = testEvent(
            sequence = 9,
            type = AgentEventTypes.RUN_TERMINAL,
            summary = "已写入 artifacts/summary.md。"
        )
        val state = projectWorkspaceState(
            projectionInput(
                selectedTaskId = task.taskId,
                tasks = listOf(task),
                selectedTask = task,
                runFeed = AgentRunFeed.Loaded(
                    testSnapshot(task = task, run = run, artifacts = listOf(committed, preparing))
                ),
                events = listOf(terminal)
            )
        )
        val detail = requireNotNull(state.detail)
        assertEquals(AgentWorkspaceStatus.SUCCEEDED, detail.status)
        assertEquals("已写入 artifacts/summary.md。", detail.finalAnswer)
        assertEquals(2, detail.artifacts.size)
        assertTrue(detail.artifacts.first { it.artifactId == "artifact-1" }.committed)
        assertFalse(detail.artifacts.first { it.artifactId == "artifact-2" }.committed)
        assertFalse(detail.canStart)
        assertFalse(detail.canStop)
        assertTrue(detail.canContinue)
    }

    @Test
    fun completedWithoutToolOrArtifactIsMarkedSelfReported() {
        val task = testTask(displayStatus = AgentDisplayStatus.SUCCEEDED, lastRunId = TEST_RUN_ID)
        val run = testRun(
            state = AgentRunState.SUCCEEDED,
            spentBudget = AgentSpentBudget(toolInvocations = 0)
        )
        val state = projectWorkspaceState(
            projectionInput(
                selectedTaskId = task.taskId,
                tasks = listOf(task),
                selectedTask = task,
                runFeed = AgentRunFeed.Loaded(testSnapshot(task = task, run = run)),
                events = listOf(
                    testEvent(
                        sequence = 9,
                        type = AgentEventTypes.RUN_TERMINAL,
                        summary = "完成"
                    )
                )
            )
        )
        val detail = requireNotNull(state.detail)
        assertEquals(AgentWorkspaceStatus.SUCCEEDED, detail.status)
        assertTrue(detail.selfReportedCompletion)
        assertEquals("完成", detail.finalAnswer)
    }

    @Test
    fun completedWithAToolCallIsNotMarkedSelfReported() {
        val task = testTask(displayStatus = AgentDisplayStatus.SUCCEEDED, lastRunId = TEST_RUN_ID)
        val run = testRun(
            state = AgentRunState.SUCCEEDED,
            spentBudget = AgentSpentBudget(toolInvocations = 1)
        )
        val state = projectWorkspaceState(
            projectionInput(
                selectedTaskId = task.taskId,
                tasks = listOf(task),
                selectedTask = task,
                runFeed = AgentRunFeed.Loaded(testSnapshot(task = task, run = run)),
                events = emptyList()
            )
        )
        assertFalse(requireNotNull(state.detail).selfReportedCompletion)
    }

    @Test
    fun protocolRepairExhaustionIsNotReportedAsPlainBudgetUse() {
        val detail = describeRunStatus(
            snapshot = testSnapshot(
                task = testTask(displayStatus = AgentDisplayStatus.PAUSED, lastRunId = TEST_RUN_ID),
                run = testRun(
                    state = AgentRunState.PAUSED,
                    reason = AgentPauseReason.BUDGET_LIMIT
                )
            ),
            status = AgentWorkspaceStatus.BUDGET_LIMIT,
            events = listOf(
                testEvent(
                    sequence = 7,
                    type = AgentEventTypes.PROTOCOL_ERROR,
                    summary = "PROTOCOL_INVALID_JSON"
                )
            )
        )
        assertTrue(detail != null)
        assertTrue(detail!!.contains("非法协议"))
    }

    @Test
    fun pendingApprovalShowsSheetUntilDismissed() {
        val task = testTask(displayStatus = AgentDisplayStatus.WAITING_APPROVAL, lastRunId = TEST_RUN_ID)
        val approval = testApproval()
        val input = projectionInput(
            selectedTaskId = task.taskId,
            tasks = listOf(task),
            selectedTask = task,
            runFeed = AgentRunFeed.Loaded(
                testSnapshot(
                    task = task,
                    run = testRun(AgentRunState.WAITING_APPROVAL),
                    approval = approval
                )
            )
        )
        val visible = projectWorkspaceState(input)
        assertTrue(visible.approvalSheetVisible)
        assertTrue(requireNotNull(visible.detail).waitingForApproval)
        assertTrue(requireNotNull(visible.detail).canStop)

        val dismissed = projectWorkspaceState(
            input.copy(
                ephemeral = AgentWorkspaceEphemeralState(
                    dismissedApprovalId = approval.approvalId
                )
            )
        )
        assertFalse(dismissed.approvalSheetVisible)
    }

    @Test
    fun approvalEventAloneNeverFabricatesAnApprovalSheet() {
        val task = testTask(
            displayStatus = AgentDisplayStatus.WAITING_APPROVAL,
            lastRunId = TEST_RUN_ID
        )
        val state = projectWorkspaceState(
            projectionInput(
                selectedTaskId = task.taskId,
                tasks = listOf(task),
                selectedTask = task,
                runFeed = AgentRunFeed.Loaded(
                    testSnapshot(
                        task = task,
                        run = testRun(AgentRunState.WAITING_APPROVAL),
                        approval = null
                    )
                ),
                events = listOf(approvalRequestEvent("""{"tool":"workspace.list"}"""))
            )
        )
        val detail = requireNotNull(state.detail)
        assertNull("no pending approval record means no approval card", detail.approval)
        assertFalse(state.approvalSheetVisible)
        assertFalse(detail.waitingForApproval)
        // The event stream can still show a bounded approval activity row; that is not a card.
        assertTrue(detail.timeline.any { it.kind == AgentTimelineKind.APPROVAL })
    }

    @Test
    fun waitingUserOnlyAllowsAnswerWithDraftAndCurrentQuestion() {
        val task = testTask(displayStatus = AgentDisplayStatus.WAITING_USER, lastRunId = TEST_RUN_ID)
        val snapshot = testSnapshot(
            task = task,
            run = testRun(AgentRunState.WAITING_USER),
            question = testQuestion()
        )
        val base = projectionInput(
            selectedTaskId = task.taskId,
            tasks = listOf(task),
            selectedTask = task,
            runFeed = AgentRunFeed.Loaded(snapshot)
        )
        val noDraft = projectWorkspaceState(base)
        assertFalse(requireNotNull(noDraft.detail).canAnswerQuestion)
        assertEquals("question-1", requireNotNull(noDraft.detail?.question).questionId)

        val withDraft = projectWorkspaceState(base.copy(questionAnswerDraft = "覆盖"))
        assertTrue(requireNotNull(withDraft.detail).canAnswerQuestion)
    }

    @Test
    fun commandInFlightDisablesConflictingActions() {
        val task = testTask(displayStatus = AgentDisplayStatus.RUNNING, lastRunId = TEST_RUN_ID)
        val state = projectWorkspaceState(
            projectionInput(
                selectedTaskId = task.taskId,
                tasks = listOf(task),
                selectedTask = task,
                runFeed = AgentRunFeed.Loaded(testSnapshot(task = task, run = testRun())),
                ephemeral = AgentWorkspaceEphemeralState(
                    commandInFlight = AgentWorkspaceCommandKind.CANCEL_RUN
                )
            )
        )
        val detail = requireNotNull(state.detail)
        assertFalse(detail.canStop)
        assertTrue(detail.stopInFlight)
    }

    @Test
    fun loadingDetailIsExplicitAndShowsRetryableError() {
        val loading = projectWorkspaceState(
            projectionInput(
                selectedTaskId = "missing-task",
                selectedTask = null,
                runFeed = AgentRunFeed.Loading
            )
        )
        assertEquals(AgentWorkspaceStatus.LOADING, requireNotNull(loading.detail).status)
        assertTrue(requireNotNull(loading.detail).loading)

        val failed = projectWorkspaceState(
            projectionInput(
                selectedTaskId = "missing-task",
                selectedTask = null,
                runFeed = AgentRunFeed.Failed("运行状态暂时无法载入")
            )
        )
        assertEquals(AgentWorkspaceStatus.FAILED, requireNotNull(failed.detail).status)
        assertEquals("运行状态暂时无法载入", requireNotNull(failed.detail).loadErrorMessage)
    }

    @Test
    fun budgetStatusDetailMentionsUsedBudget() {
        val task = testTask(displayStatus = AgentDisplayStatus.PAUSED, lastRunId = TEST_RUN_ID)
        val run = testRun(
            state = AgentRunState.PAUSED,
            reason = AgentPauseReason.BUDGET_LIMIT,
            spentBudget = AgentSpentBudget(
                modelAttempts = 12,
                toolInvocations = 8
            )
        )
        val state = projectWorkspaceState(
            projectionInput(
                selectedTaskId = task.taskId,
                tasks = listOf(task),
                selectedTask = task,
                runFeed = AgentRunFeed.Loaded(testSnapshot(task = task, run = run))
            )
        )
        val detail = requireNotNull(state.detail)
        assertEquals(AgentWorkspaceStatus.BUDGET_LIMIT, detail.status)
        assertTrue(detail.statusDetail.orEmpty().contains("12/12"))
        assertTrue(detail.statusDetail.orEmpty().contains("8/8"))
        assertTrue(detail.canContinue)
    }

    @Test
    fun detailProjectsRealRunStatsFromTheRunBudget() {
        val task = testTask(displayStatus = AgentDisplayStatus.RUNNING, lastRunId = TEST_RUN_ID)
        val run = testRun(
            spentBudget = AgentSpentBudget(
                modelAttempts = 2,
                toolInvocations = 1,
                generatedTokens = 1_200,
                modelWallTimeMillis = 60_000L
            )
        )
        val state = projectWorkspaceState(
            projectionInput(
                selectedTaskId = task.taskId,
                tasks = listOf(task),
                selectedTask = task,
                runFeed = AgentRunFeed.Loaded(testSnapshot(task = task, run = run))
            )
        )
        val stats = requireNotNull(requireNotNull(state.detail).runStats)
        assertEquals(2, stats.rounds)
        assertEquals(3, stats.steps)
        assertEquals(20.0, requireNotNull(stats.tokensPerSecond), 0.000_001)
        assertEquals(1_200, stats.totalTokens)
        assertNull(stats.contextPercent)
        assertNull(stats.cacheHitPercent)
        assertTrue(stats.running)
    }

    @Test
    fun detailWithoutRunOrWithArchivedTaskOmitsRunStats() {
        val task = testTask()
        val noRun = projectWorkspaceState(
            projectionInput(
                selectedTaskId = task.taskId,
                tasks = listOf(task),
                selectedTask = task,
                runFeed = AgentRunFeed.NoRun
            )
        )
        assertNull(requireNotNull(noRun.detail).runStats)

        val archived = task.copy(lifecycle = AgentTaskLifecycle.IMPORTED_ARCHIVED)
        val archivedState = projectWorkspaceState(
            projectionInput(
                selectedTaskId = archived.taskId,
                tasks = listOf(archived),
                selectedTask = archived,
                runFeed = AgentRunFeed.Loaded(testSnapshot(task = archived, run = testRun()))
            )
        )
        assertNull(requireNotNull(archivedState.detail).runStats)
    }

    @Test
    fun taskDisplayTitleIsSingleLineAndBounded() {
        assertEquals("第一行目标", taskDisplayTitle("第一行目标\n第二行"))
        val longTitle = taskDisplayTitle("长".repeat(200))
        assertTrue(longTitle.length <= 81)
        assertTrue(longTitle.endsWith("…"))
    }
}
