package com.muyuchat.feature.agent.workspace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun workflowRecipe(
    id: String,
    name: String = id,
    goal: String = "总结本周笔记",
    version: String = "v1",
    updated: String? = null
) = AgentWorkflowRecipeItem(
    recipeId = id,
    name = name,
    goalTemplate = goal,
    versionLabel = version,
    updatedLabel = updated
)

class AgentWorkflowCoordinatorTest {

    private class FakePort : AgentWorkflowAdminPort {
        var listResult: AgentWorkflowListResult =
            AgentWorkflowListResult.Loaded(emptyList())
        var createResult: AgentWorkflowAdminOutcome =
            AgentWorkflowAdminOutcome.Rejected("WORKFLOW_INVALID", "配方无效。")
        var throwOnList = false
        var throwOnCreate = false
        var createGate: CompletableDeferred<Unit>? = null
        var listCalls = 0
        val createCalls = mutableListOf<Pair<String, String>>()

        override suspend fun list(): AgentWorkflowListResult {
            listCalls++
            if (throwOnList) error("boom")
            return listResult
        }

        override suspend fun create(
            name: String,
            goalTemplate: String
        ): AgentWorkflowAdminOutcome {
            createCalls += name to goalTemplate
            createGate?.await()
            if (throwOnCreate) error("boom")
            return createResult
        }
    }

    private class Harness {
        val port = FakePort()
        val coordinator = AgentWorkflowCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            port = port
        )
    }

    @Test
    fun `open lists manual recipes and marks them manual`() {
        val harness = Harness()
        harness.port.listResult = AgentWorkflowListResult.Loaded(
            listOf(
                workflowRecipe("wf-b", name = "周报"),
                workflowRecipe("wf-a", name = "日报")
            )
        )

        harness.coordinator.open()

        val state = harness.coordinator.state.value
        assertTrue(state.visible)
        assertFalse(state.loading)
        assertEquals(listOf("周报", "日报"), state.recipes.map { it.name })
        assertEquals("手动", state.recipes.first().manualLabel)
        assertFalse(state.empty)
        assertNull(state.createDraft)
    }

    @Test
    fun `manual create requires a name and a goal before calling the port`() {
        val harness = Harness()
        harness.port.listResult = AgentWorkflowListResult.Loaded(emptyList())
        harness.coordinator.open()
        harness.coordinator.openCreateDialog()

        harness.coordinator.submitCreate()
        assertEquals(
            "请输入配方名称。",
            harness.coordinator.state.value.createDraft?.validationMessage
        )
        assertTrue(harness.port.createCalls.isEmpty())

        harness.coordinator.updateName("周报")
        harness.coordinator.submitCreate()
        assertEquals(
            "请输入任务目标。",
            harness.coordinator.state.value.createDraft?.validationMessage
        )
        assertTrue(harness.port.createCalls.isEmpty())
    }

    @Test
    fun `successful manual create closes the dialog and appends the recipe`() {
        val harness = Harness()
        harness.port.listResult = AgentWorkflowListResult.Loaded(emptyList())
        harness.coordinator.open()
        harness.coordinator.openCreateDialog()
        harness.coordinator.updateName("  周报  ")
        harness.coordinator.updateGoal("  总结本周笔记  ")
        harness.port.createResult = AgentWorkflowAdminOutcome.Created(
            workflowRecipe("wf-1", name = "周报", goal = "总结本周笔记")
        )

        harness.coordinator.submitCreate()

        assertEquals(listOf("周报" to "总结本周笔记"), harness.port.createCalls)
        val state = harness.coordinator.state.value
        assertNull(state.createDraft)
        assertNull(state.busy)
        assertEquals(listOf("wf-1"), state.recipes.map { it.recipeId })
        assertTrue(state.notice.orEmpty().contains("已创建手动配方"))
    }

    @Test
    fun `rejected create keeps the draft and shows only the safe message`() {
        val harness = Harness()
        harness.port.listResult = AgentWorkflowListResult.Loaded(emptyList())
        harness.coordinator.open()
        harness.coordinator.openCreateDialog()
        harness.coordinator.updateName("周报")
        harness.coordinator.updateGoal("总结本周笔记")
        harness.port.createResult = AgentWorkflowAdminOutcome.Rejected(
            code = "WORKFLOW_NAME_TAKEN",
            safeMessage = "配方名称已存在。"
        )

        harness.coordinator.submitCreate()

        val state = harness.coordinator.state.value
        assertNotNull(state.createDraft)
        assertEquals("周报", state.createDraft?.name)
        assertEquals("总结本周笔记", state.createDraft?.goalTemplate)
        assertEquals("配方名称已存在。", state.createDraft?.validationMessage)
        assertEquals("WORKFLOW_NAME_TAKEN", state.error?.code)
        assertTrue(state.recipes.isEmpty())
    }

    @Test
    fun `a thrown create failure keeps the dialog and becomes a generic safe error`() {
        val harness = Harness()
        harness.port.listResult = AgentWorkflowListResult.Loaded(emptyList())
        harness.coordinator.open()
        harness.coordinator.openCreateDialog()
        harness.coordinator.updateName("周报")
        harness.coordinator.updateGoal("总结本周笔记")
        harness.port.throwOnCreate = true

        harness.coordinator.submitCreate()

        assertEquals("WORKFLOW_ADMIN_FAILED", harness.coordinator.state.value.error?.code)
        assertNotNull(harness.coordinator.state.value.createDraft)
    }

    @Test
    fun `only one create may be in flight and the dialog disables submit`() {
        val harness = Harness()
        harness.port.listResult = AgentWorkflowListResult.Loaded(emptyList())
        harness.coordinator.open()
        harness.coordinator.openCreateDialog()
        harness.coordinator.updateName("周报")
        harness.coordinator.updateGoal("总结本周笔记")
        harness.port.createGate = CompletableDeferred()
        harness.port.createResult = AgentWorkflowAdminOutcome.Created(workflowRecipe("wf-1"))

        harness.coordinator.submitCreate()

        assertEquals(AgentWorkflowOperationKind.CREATING, harness.coordinator.state.value.busy)
        assertFalse(harness.coordinator.state.value.canSubmitCreate)
        assertFalse(harness.coordinator.state.value.canRefresh)
        harness.coordinator.submitCreate()
        harness.coordinator.refresh()
        assertEquals(1, harness.port.createCalls.size)
        assertEquals(1, harness.port.listCalls)

        harness.port.createGate?.complete(Unit)
        assertNull(harness.coordinator.state.value.busy)
        assertNull(harness.coordinator.state.value.createDraft)
        assertEquals(1, harness.coordinator.state.value.recipes.size)
    }

    @Test
    fun `empty and rejected lists stay empty and the unavailable port is safe`() {
        val emptyHarness = Harness()
        emptyHarness.port.listResult = AgentWorkflowListResult.Loaded(emptyList())
        emptyHarness.coordinator.open()
        assertTrue(emptyHarness.coordinator.state.value.empty)

        val rejectedHarness = Harness()
        rejectedHarness.port.listResult = AgentWorkflowListResult.Rejected(
            code = "WORKFLOW_LIST_DENIED",
            safeMessage = "无法读取工作流。"
        )
        rejectedHarness.coordinator.open()
        assertEquals("WORKFLOW_LIST_DENIED", rejectedHarness.coordinator.state.value.error?.code)
        assertTrue(rejectedHarness.coordinator.state.value.recipes.isEmpty())

        val unavailable = AgentWorkflowCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            port = AgentWorkflowAdminPort.Unavailable
        )
        unavailable.open()
        assertEquals(
            "WORKFLOW_ADMIN_UNAVAILABLE",
            unavailable.state.value.error?.code
        )
    }

    @Test
    fun `close dismisses the create dialog without touching recipes`() {
        val harness = Harness()
        harness.port.listResult = AgentWorkflowListResult.Loaded(listOf(workflowRecipe("wf-1")))
        harness.coordinator.open()
        harness.coordinator.openCreateDialog()
        assertNotNull(harness.coordinator.state.value.createDraft)

        harness.coordinator.close()

        assertFalse(harness.coordinator.state.value.visible)
        assertNull(harness.coordinator.state.value.createDraft)
        assertEquals(1, harness.coordinator.state.value.recipes.size)
    }
}
