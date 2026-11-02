package com.muyuchat.feature.agent.workspace

import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentProtocolMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun launchModel(): AgentModelBinding = AgentModelBinding(
    providerId = "local",
    modelId = "model-1",
    runtime = "mnn",
    assetFingerprint = "fingerprint-1",
    protocolMode = AgentProtocolMode.PROMPTED_V1,
    modelDisplayName = "Qwen3 4B"
)

private fun launchPreview(
    recipeId: String = "wf-1",
    canCreate: Boolean = true,
    inputs: List<AgentWorkflowLaunchInputUi> = listOf(
        AgentWorkflowLaunchInputUi(
            inputId = "topic",
            label = "主题",
            editable = true,
            required = true,
            maxChars = 200
        )
    ),
    blockedMessage: String? = null
): AgentWorkflowLaunchPreviewUi = AgentWorkflowLaunchPreviewUi(
    recipeId = recipeId,
    versionLabel = "v1",
    title = "每周回顾",
    goal = "总结 计划 的要点",
    modelLabel = "Qwen3 4B",
    inputs = inputs,
    checks = listOf(AgentWorkflowLaunchCheckUi("目标检查", passed = canCreate)),
    canCreate = canCreate,
    blockedMessage = blockedMessage
)

class AgentWorkflowLaunchCoordinatorTest {

    private class FakeLaunchPort : AgentWorkflowLaunchPort {
        var previewResult: AgentWorkflowLaunchPreviewResult =
            AgentWorkflowLaunchPreviewResult.Loaded(launchPreview())
        var launchResult: AgentWorkflowLaunchOutcome =
            AgentWorkflowLaunchOutcome.Started(taskId = "task-1", runId = "run-1")
        var previewGate: CompletableDeferred<Unit>? = null
        var launchGate: CompletableDeferred<Unit>? = null
        var throwOnPreview = false
        var throwOnLaunch = false

        val previewCalls = mutableListOf<Pair<String, Map<String, String>>>()
        val launchCalls = mutableListOf<Triple<String, Map<String, String>, AgentModelBinding>>()

        override suspend fun preview(
            recipeId: String,
            inputValues: Map<String, String>,
            model: AgentModelBinding
        ): AgentWorkflowLaunchPreviewResult {
            previewCalls += recipeId to inputValues.toMap()
            previewGate?.await()
            if (throwOnPreview) error("boom")
            return previewResult
        }

        override suspend fun createManualTask(
            recipeId: String,
            inputValues: Map<String, String>,
            model: AgentModelBinding
        ): AgentWorkflowLaunchOutcome {
            launchCalls += Triple(recipeId, inputValues.toMap(), model)
            launchGate?.await()
            if (throwOnLaunch) error("boom")
            return launchResult
        }
    }

    private class Harness(model: AgentModelBinding? = launchModel()) {
        val port = FakeLaunchPort()
        val coordinator = AgentWorkflowLaunchCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            port = port,
            modelProvider = { model }
        )
    }

    @Test
    fun selectingARecipeLoadsItsPreview() {
        val harness = Harness()
        harness.coordinator.select("wf-1")

        val state = harness.coordinator.state.value
        assertEquals("wf-1", state.selectedRecipeId)
        assertNotNull(state.preview)
        assertTrue(state.canCreate)
        assertEquals(listOf("wf-1" to emptyMap<String, String>()), harness.port.previewCalls)
    }

    @Test
    fun noModelBlocksPreviewAndLaunch() {
        val harness = Harness(model = null)
        harness.coordinator.select("wf-1")

        val state = harness.coordinator.state.value
        assertNull(state.preview)
        assertFalse(state.canCreate)
        assertEquals("WORKFLOW_MODEL_UNAVAILABLE", state.error?.code)
        assertTrue(harness.port.previewCalls.isEmpty())

        harness.coordinator.createTask()
        assertTrue(harness.port.launchCalls.isEmpty())
    }

    @Test
    fun inputChangesRefreshThePreviewWithTheBoundedDraft() {
        val harness = Harness()
        harness.coordinator.select("wf-1")
        harness.coordinator.updateInput("topic", "计划")

        val state = harness.coordinator.state.value
        assertEquals("计划", state.inputValues["topic"])
        assertEquals("计划", harness.port.previewCalls.last().second["topic"])
    }

    @Test
    fun overlongInputIsBoundedBeforeThePortCall() {
        val harness = Harness()
        harness.coordinator.select("wf-1")
        harness.coordinator.updateInput("topic", "x".repeat(500))

        assertEquals(200, harness.coordinator.state.value.inputValues["topic"]?.length)
        assertEquals(200, harness.port.previewCalls.last().second["topic"]?.length)
    }

    @Test
    fun createDelegatesExactlyOnceWithTheSelectedInputsAndModel() {
        val harness = Harness()
        harness.coordinator.select("wf-1")
        harness.coordinator.updateInput("topic", "计划")
        harness.port.launchResult =
            AgentWorkflowLaunchOutcome.Started(taskId = "task-9", runId = "run-9")

        harness.coordinator.createTask()

        assertEquals(1, harness.port.launchCalls.size)
        val call = harness.port.launchCalls.single()
        assertEquals("wf-1", call.first)
        assertEquals(mapOf("topic" to "计划"), call.second)
        assertEquals("model-1", call.third.modelId)
        val state = harness.coordinator.state.value
        assertFalse(state.busy)
        assertTrue(state.notice.orEmpty().contains("已创建任务"))
        assertEquals("task-9", state.lastCreatedTaskId)
    }

    @Test
    fun repeatedCreateWhileBusyCallsThePortOnlyOnce() {
        val harness = Harness()
        harness.coordinator.select("wf-1")
        harness.port.launchGate = CompletableDeferred()

        harness.coordinator.createTask()
        harness.coordinator.createTask()
        harness.coordinator.createTask()

        assertEquals(1, harness.port.launchCalls.size)
        assertTrue(harness.coordinator.state.value.busy)

        harness.port.launchGate?.complete(Unit)
        assertEquals(1, harness.port.launchCalls.size)
        assertFalse(harness.coordinator.state.value.busy)
    }

    @Test
    fun createdButUnstartedTaskIsReportedTruthfully() {
        val harness = Harness()
        harness.coordinator.select("wf-1")
        harness.port.launchResult = AgentWorkflowLaunchOutcome.TaskCreatedRunRejected(
            taskId = "task-3",
            code = "WORKFLOW_RUN_START_REJECTED",
            safeMessage = "任务已创建，但启动被拒绝。"
        )

        harness.coordinator.createTask()

        val state = harness.coordinator.state.value
        assertEquals("task-3", state.lastCreatedTaskId)
        assertNull(state.notice)
        assertTrue(state.error?.message.orEmpty().contains("未开始运行"))
    }

    @Test
    fun rejectionShowsOnlyTheSafeMessage() {
        val harness = Harness()
        harness.coordinator.select("wf-1")
        harness.port.launchResult = AgentWorkflowLaunchOutcome.Rejected(
            code = "WORKFLOW_INPUT_REQUIRED",
            safeMessage = "请填写模板要求的输入。"
        )

        harness.coordinator.createTask()

        assertEquals("请填写模板要求的输入。", harness.coordinator.state.value.error?.message)
        assertNull(harness.coordinator.state.value.lastCreatedTaskId)
    }

    @Test
    fun previewRejectionClearsThePreviewAndBlocksCreate() {
        val harness = Harness()
        harness.port.previewResult = AgentWorkflowLaunchPreviewResult.Rejected(
            code = "WORKFLOW_ARCHIVED",
            safeMessage = "任务模板已归档，不能创建任务。"
        )

        harness.coordinator.select("wf-1")

        val state = harness.coordinator.state.value
        assertNull(state.preview)
        assertFalse(state.canCreate)
        assertEquals("WORKFLOW_ARCHIVED", state.error?.code)
        harness.coordinator.createTask()
        assertTrue(harness.port.launchCalls.isEmpty())
    }

    @Test
    fun thrownPreviewIsConvertedToATypedFailure() {
        val harness = Harness()
        harness.port.throwOnPreview = true

        harness.coordinator.select("wf-1")

        assertEquals("WORKFLOW_ADMIN_FAILED", harness.coordinator.state.value.error?.code)
    }

    @Test
    fun clearResetsSelectionAndPreview() {
        val harness = Harness()
        harness.coordinator.select("wf-1")
        harness.coordinator.updateInput("topic", "计划")

        harness.coordinator.clear()

        val state = harness.coordinator.state.value
        assertNull(state.selectedRecipeId)
        assertNull(state.preview)
        assertTrue(state.inputValues.isEmpty())
        assertFalse(state.canCreate)
    }
}
