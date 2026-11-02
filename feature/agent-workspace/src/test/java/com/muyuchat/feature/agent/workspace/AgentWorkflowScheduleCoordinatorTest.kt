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

class AgentWorkflowScheduleCoordinatorTest {

    private class FakeSchedulePort : AgentWorkflowSchedulePort {
        val results = mutableMapOf<String, AgentWorkflowScheduleListResult>()
        var saveOutcome: AgentWorkflowScheduleOutcome = savedOutcome()
        var removeOutcome: AgentWorkflowScheduleOutcome =
            AgentWorkflowScheduleOutcome.Removed(recipeId = "wf-1", removed = true)
        var listGate: CompletableDeferred<Unit>? = null
        var saveGate: CompletableDeferred<Unit>? = null
        var throwOnList = false

        val listCalls = mutableListOf<String>()
        val saveCalls = mutableListOf<AgentWorkflowScheduleCommandUi>()
        val removeCalls = mutableListOf<String>()

        override suspend fun list(recipeId: String): AgentWorkflowScheduleListResult {
            listCalls += recipeId
            listGate?.await()
            if (throwOnList) error("boom")
            return results[recipeId] ?: AgentWorkflowScheduleListResult.Loaded(emptyList())
        }

        override suspend fun save(
            command: AgentWorkflowScheduleCommandUi
        ): AgentWorkflowScheduleOutcome {
            saveCalls += command
            saveGate?.await()
            return saveOutcome
        }

        override suspend fun remove(recipeId: String): AgentWorkflowScheduleOutcome {
            removeCalls += recipeId
            return removeOutcome
        }
    }

    private class Harness {
        val port = FakeSchedulePort()
        val coordinator = AgentWorkflowScheduleCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            port = port,
            zoneProvider = { "Asia/Shanghai" }
        )
    }

    @Test
    fun openingLoadsTheStoredPlanIntoTheDraft() {
        val harness = Harness()
        harness.port.results["wf-1"] = AgentWorkflowScheduleListResult.Loaded(
            listOf(savedSchedule(recipeId = "wf-1", everyMinutes = 90))
        )

        harness.coordinator.open("wf-1", "每周回顾")

        val state = harness.coordinator.state.value
        assertTrue(state.visible)
        assertEquals("wf-1", state.recipeId)
        assertEquals("每周回顾", state.recipeName)
        assertNotNull(state.schedule)
        assertEquals("90", state.draft?.everyMinutesText)
        assertFalse(state.loading)
        assertTrue(state.canSave)
        assertTrue(state.canDelete)
        assertEquals(listOf("wf-1"), harness.port.listCalls)
    }

    @Test
    fun openingWithoutAPlanStartsFromTheDeviceZone() {
        val harness = Harness()

        harness.coordinator.open("wf-2", "新配方")

        val state = harness.coordinator.state.value
        assertNull(state.schedule)
        assertEquals("Asia/Shanghai", state.draft?.zoneId)
        assertTrue(state.canSave)
        assertFalse(state.canDelete)
    }

    @Test
    fun blankRecipeNeverOpensTheDialog() {
        val harness = Harness()

        harness.coordinator.open("  ", "x")

        assertFalse(harness.coordinator.state.value.visible)
        assertTrue(harness.port.listCalls.isEmpty())
    }

    @Test
    fun invalidIntervalIsRejectedLocallyWithoutCallingThePort() {
        val harness = Harness()
        harness.coordinator.open("wf-1", "每周回顾")
        harness.coordinator.updateMinutes("5")

        harness.coordinator.save()

        assertEquals("5", harness.coordinator.state.value.draft?.everyMinutesText)
        assertNotNull(harness.coordinator.state.value.draft?.validationMessage)
        assertTrue(harness.port.saveCalls.isEmpty())
        assertFalse(harness.coordinator.state.value.busy)
    }

    @Test
    fun weeklyPlanWithoutDaysIsRejectedLocally() {
        val harness = Harness()
        harness.coordinator.open("wf-1", "每周回顾")
        harness.coordinator.updateKind(AgentWorkflowScheduleKind.WEEKLY)
        harness.coordinator.toggleWeekday(AgentWorkflowWeekday.MONDAY)

        harness.coordinator.save()

        assertEquals(
            "请至少选择一天。",
            harness.coordinator.state.value.draft?.validationMessage
        )
        assertTrue(harness.port.saveCalls.isEmpty())
    }

    @Test
    fun saveDelegatesOneNormalizedCommand() {
        val harness = Harness()
        harness.coordinator.open("wf-1", "每周回顾")
        harness.coordinator.updateKind(AgentWorkflowScheduleKind.WEEKLY)
        harness.coordinator.updateTime("9:15")
        harness.coordinator.toggleWeekday(AgentWorkflowWeekday.FRIDAY)
        harness.coordinator.updateEnabled(false)

        harness.coordinator.save()

        val command = harness.port.saveCalls.single()
        assertEquals("wf-1", command.recipeId)
        assertEquals(AgentWorkflowScheduleKind.WEEKLY, command.kind)
        assertEquals("9:15", command.timeLabel)
        assertEquals(
            setOf(AgentWorkflowWeekday.MONDAY, AgentWorkflowWeekday.FRIDAY),
            command.weekdays
        )
        assertEquals("Asia/Shanghai", command.zoneId)
        assertFalse(command.enabled)
    }

    @Test
    fun savedPlanReportsWhetherTheWakeWasArmed() {
        val harness = Harness()
        harness.port.saveOutcome = AgentWorkflowScheduleOutcome.Saved(
            schedule = savedSchedule(recipeId = "wf-1", everyMinutes = 120),
            wakeArmed = true
        )
        harness.coordinator.open("wf-1", "每周回顾")
        harness.coordinator.updateMinutes("120")

        harness.coordinator.save()

        val state = harness.coordinator.state.value
        assertFalse(state.busy)
        assertNotNull(state.schedule)
        assertTrue(state.notice.orEmpty().contains("系统唤醒已安装"))
        assertNull(state.error)

        harness.port.saveOutcome = AgentWorkflowScheduleOutcome.Saved(
            schedule = savedSchedule(recipeId = "wf-1", everyMinutes = 120),
            wakeArmed = false
        )
        harness.coordinator.save()

        val unarmed = harness.coordinator.state.value
        assertTrue(unarmed.notice.orEmpty().contains("未能安装唤醒"))
        assertTrue(unarmed.notice.orEmpty().contains("延迟"))
    }

    @Test
    fun hostRejectionKeepsTheDraftAndShowsOnlyTheSafeMessage() {
        val harness = Harness()
        harness.port.saveOutcome = AgentWorkflowScheduleOutcome.Rejected(
            code = "WORKFLOW_MODEL_NOT_LOADED",
            safeMessage = "请先在聊天页加载一个本地模型。"
        )
        harness.coordinator.open("wf-1", "每周回顾")
        harness.coordinator.updateMinutes("45")

        harness.coordinator.save()

        val state = harness.coordinator.state.value
        assertFalse(state.busy)
        assertEquals("WORKFLOW_MODEL_NOT_LOADED", state.error?.code)
        assertEquals("45", state.draft?.everyMinutesText)
        assertEquals("请先在聊天页加载一个本地模型。", state.draft?.validationMessage)
    }

    @Test
    fun repeatedSaveWhileBusyCallsThePortOnce() {
        val harness = Harness()
        harness.coordinator.open("wf-1", "每周回顾")
        harness.port.saveGate = CompletableDeferred()

        harness.coordinator.save()
        harness.coordinator.save()
        harness.coordinator.save()
        harness.coordinator.close()

        assertEquals(1, harness.port.saveCalls.size)
        assertTrue(harness.coordinator.state.value.busy)
        assertTrue(harness.coordinator.state.value.visible)

        harness.port.saveGate?.complete(Unit)
        assertEquals(1, harness.port.saveCalls.size)
        assertFalse(harness.coordinator.state.value.busy)
    }

    @Test
    fun removingClearsThePlanAndResetsTheDraft() {
        val harness = Harness()
        harness.port.results["wf-1"] = AgentWorkflowScheduleListResult.Loaded(
            listOf(savedSchedule(recipeId = "wf-1", everyMinutes = 90))
        )
        harness.coordinator.open("wf-1", "每周回顾")

        harness.coordinator.remove()

        val state = harness.coordinator.state.value
        assertEquals(listOf("wf-1"), harness.port.removeCalls)
        assertNull(state.schedule)
        assertEquals("Asia/Shanghai", state.draft?.zoneId)
        assertTrue(state.notice.orEmpty().contains("已删除"))
        assertFalse(state.canDelete)
    }

    @Test
    fun loadRejectionBlocksSavingUntilRetrySucceeds() {
        val harness = Harness()
        harness.port.results["wf-1"] = AgentWorkflowScheduleListResult.Rejected(
            code = "WORKFLOW_SCHEDULE_RECIPE_NOT_FOUND",
            safeMessage = "找不到要定时的配方。"
        )
        harness.coordinator.open("wf-1", "每周回顾")

        val failed = harness.coordinator.state.value
        assertNull(failed.draft)
        assertFalse(failed.canSave)
        assertEquals("WORKFLOW_SCHEDULE_RECIPE_NOT_FOUND", failed.error?.code)

        harness.coordinator.save()
        assertTrue(harness.port.saveCalls.isEmpty())

        harness.port.results["wf-1"] = AgentWorkflowScheduleListResult.Loaded(emptyList())
        harness.coordinator.retry()

        val retried = harness.coordinator.state.value
        assertNotNull(retried.draft)
        assertNull(retried.error)
        assertEquals(listOf("wf-1", "wf-1"), harness.port.listCalls)
    }

    @Test
    fun thrownLoadBecomesATypedFailure() {
        val harness = Harness()
        harness.port.throwOnList = true

        harness.coordinator.open("wf-1", "每周回顾")

        assertEquals("WORKFLOW_SCHEDULE_FAILED", harness.coordinator.state.value.error?.code)
        assertFalse(harness.coordinator.state.value.canSave)
    }

    @Test
    fun staleLoadIsDroppedWhenAnotherRecipeOpens() {
        val harness = Harness()
        harness.port.results["wf-1"] = AgentWorkflowScheduleListResult.Loaded(
            listOf(savedSchedule(recipeId = "wf-1", everyMinutes = 90))
        )
        harness.port.results["wf-2"] = AgentWorkflowScheduleListResult.Loaded(emptyList())
        harness.port.listGate = CompletableDeferred()

        harness.coordinator.open("wf-1", "A")
        harness.coordinator.open("wf-2", "B")
        harness.port.listGate?.complete(Unit)

        val state = harness.coordinator.state.value
        assertEquals("wf-2", state.recipeId)
        assertEquals("B", state.recipeName)
        assertNull(state.schedule)
        assertEquals("Asia/Shanghai", state.draft?.zoneId)
        assertEquals(listOf("wf-1", "wf-2"), harness.port.listCalls)
    }

    @Test
    fun editingClearsAPreviousValidationMessage() {
        val harness = Harness()
        harness.coordinator.open("wf-1", "每周回顾")
        harness.coordinator.updateMinutes("1")
        harness.coordinator.save()
        assertNotNull(harness.coordinator.state.value.draft?.validationMessage)

        harness.coordinator.updateMinutes("30")

        assertNull(harness.coordinator.state.value.draft?.validationMessage)
        harness.coordinator.save()
        assertEquals("30", harness.port.saveCalls.single().everyMinutes.toString())
    }

    @Test
    fun closeResetsTheDialogOnceNotBusy() {
        val harness = Harness()
        harness.coordinator.open("wf-1", "每周回顾")

        harness.coordinator.close()

        val state = harness.coordinator.state.value
        assertFalse(state.visible)
        assertNull(state.recipeId)
        assertNull(state.draft)
    }

    private companion object {
        fun savedSchedule(recipeId: String, everyMinutes: Int): AgentWorkflowScheduleUi =
            AgentWorkflowScheduleUi(
                recipeId = recipeId,
                definitionVersion = 2,
                kind = AgentWorkflowScheduleKind.INTERVAL,
                everyMinutes = everyMinutes,
                timeLabel = null,
                weekdays = emptySet(),
                zoneId = "Asia/Shanghai",
                enabled = true
            )

        fun savedOutcome(): AgentWorkflowScheduleOutcome =
            AgentWorkflowScheduleOutcome.Saved(
                schedule = savedSchedule(recipeId = "wf-1", everyMinutes = 60),
                wakeArmed = true
            )
    }
}
