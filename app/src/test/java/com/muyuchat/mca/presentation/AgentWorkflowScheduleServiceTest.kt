package com.muyuchat.mca.presentation

import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.runtime.workflow.WorkflowSchedule
import com.muyuchat.feature.agent.workspace.AgentWorkflowScheduleCommandUi
import com.muyuchat.feature.agent.workspace.AgentWorkflowScheduleKind
import com.muyuchat.feature.agent.workspace.AgentWorkflowScheduleListResult
import com.muyuchat.feature.agent.workspace.AgentWorkflowScheduleOutcome
import com.muyuchat.feature.agent.workspace.AgentWorkflowWeekday
import com.muyuchat.mca.agent.workflow.FileWorkflowEntryReceiptStore
import com.muyuchat.mca.agent.workflow.FileWorkflowRunHistoryStore
import com.muyuchat.mca.agent.workflow.FileWorkflowScheduleStore
import com.muyuchat.mca.agent.workflow.InMemoryAgentWorkflowRecipeStore
import com.muyuchat.mca.agent.workflow.RecipeWorkflowDefinitionStore
import com.muyuchat.mca.agent.workflow.WorkflowEntryService
import com.muyuchat.mca.agent.workflow.WorkflowExecutionResult
import com.muyuchat.mca.agent.workflow.WorkflowScheduleErrorCodes
import com.muyuchat.mca.agent.workflow.WorkflowScheduleRecord
import com.muyuchat.mca.agent.workflow.WorkflowScheduleService
import com.muyuchat.mca.agent.workflow.WorkflowTriggerFacade
import com.muyuchat.mca.agent.workflow.WorkflowWakeScheduler
import com.muyuchat.mca.agent.workflow.testWorkflowRecipe
import com.muyuchat.mca.agent.workflow.testWorkflowRecord
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM contract for the recipe → schedule adapter: one recipe owns one pinned plan, the loaded model
 * is frozen into the row, and every rejection happens before a durable write.
 */
class AgentWorkflowScheduleServiceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = 10 * 60_000L
    private val clock: () -> Long = { now }

    private val binding = AgentModelBinding(
        providerId = "agent.local",
        modelId = "qwen-local",
        runtime = "llama.cpp",
        assetFingerprint = "b".repeat(64),
        protocolMode = AgentProtocolMode.PROMPTED_V1,
        modelDisplayName = "Qwen Local"
    )

    private val recipes = InMemoryAgentWorkflowRecipeStore(
        listOf(testWorkflowRecord(testWorkflowRecipe(id = "weekly-review", version = 1L)))
    )

    private fun service(wakes: WorkflowWakeScheduler = RecordingWakes()): WorkflowScheduleService =
        WorkflowScheduleService(
            store = FileWorkflowScheduleStore(folder.root, clock),
            definitions = RecipeWorkflowDefinitionStore(recipes),
            facade = WorkflowTriggerFacade(
                entry = WorkflowEntryService(
                    executor = { request ->
                        WorkflowExecutionResult.Completed(
                            request.runId ?: "run",
                            emptyMap(),
                            emptyList()
                        )
                    },
                    history = FileWorkflowRunHistoryStore(folder.root),
                    clock = clock
                ),
                receipts = FileWorkflowEntryReceiptStore(folder.root),
                clock = clock
            ),
            wakes = wakes,
            clock = clock
        )

    private fun adapter(
        service: WorkflowScheduleService,
        model: AgentModelBinding? = binding
    ): AgentWorkflowScheduleService = AgentWorkflowScheduleService(
        recipes = recipes,
        schedules = service,
        activeBinding = { model },
        ioDispatcher = Dispatchers.Unconfined
    )

    private fun rows(service: WorkflowScheduleService): List<WorkflowScheduleRecord> = runBlocking {
        service.list()
    }

    @Test
    fun `list rejects an unknown recipe without touching the store`() = runBlocking {
        val service = service()
        val result = adapter(service).list("missing-recipe")

        assertTrue(result is AgentWorkflowScheduleListResult.Rejected)
        assertEquals(
            "WORKFLOW_SCHEDULE_RECIPE_NOT_FOUND",
            (result as AgentWorkflowScheduleListResult.Rejected).code
        )
        assertTrue(rows(service).isEmpty())
    }

    @Test
    fun `save pins the published recipe version and the loaded model`() = runBlocking {
        val wakes = RecordingWakes()
        val service = service(wakes)
        val outcome = adapter(service).save(
            AgentWorkflowScheduleCommandUi(
                recipeId = "weekly-review",
                kind = AgentWorkflowScheduleKind.INTERVAL,
                everyMinutes = 120,
                zoneId = "Asia/Shanghai"
            )
        )

        assertTrue(outcome is AgentWorkflowScheduleOutcome.Saved)
        val saved = outcome as AgentWorkflowScheduleOutcome.Saved
        assertTrue(saved.wakeArmed)
        assertEquals(1L, saved.schedule.definitionVersion)
        assertEquals(120, saved.schedule.everyMinutes)

        val row = rows(service).single()
        assertEquals("recipe-weekly-review", row.definitionId)
        assertEquals(1L, row.version)
        assertEquals(WorkflowSchedule.Interval(120), row.schedule)
        assertEquals("qwen-local", row.agentTask?.modelBinding?.modelId)
        assertEquals("每周回顾", row.agentTask?.displayTitle)
        assertEquals(1, wakes.scheduled.size)
    }

    @Test
    fun `save without a loaded model is rejected before any write`() = runBlocking {
        val service = service()
        val outcome = adapter(service, model = null).save(
            AgentWorkflowScheduleCommandUi(
                recipeId = "weekly-review",
                kind = AgentWorkflowScheduleKind.INTERVAL,
                everyMinutes = 60,
                zoneId = "Asia/Shanghai"
            )
        )

        assertTrue(outcome is AgentWorkflowScheduleOutcome.Rejected)
        assertEquals(
            "WORKFLOW_MODEL_NOT_LOADED",
            (outcome as AgentWorkflowScheduleOutcome.Rejected).code
        )
        assertTrue(rows(service).isEmpty())
    }

    @Test
    fun `save of an inactive recipe is rejected`() = runBlocking {
        val archived = InMemoryAgentWorkflowRecipeStore(
            listOf(testWorkflowRecord(testWorkflowRecipe(id = "old-recipe")))
        )
        archived.archive("old-recipe", now)
        val service = WorkflowScheduleService(
            store = FileWorkflowScheduleStore(folder.root, clock),
            definitions = RecipeWorkflowDefinitionStore(archived),
            facade = WorkflowTriggerFacade(
                entry = WorkflowEntryService(
                    executor = { request ->
                        WorkflowExecutionResult.Completed("run", emptyMap(), emptyList())
                    },
                    history = FileWorkflowRunHistoryStore(folder.root),
                    clock = clock
                ),
                receipts = FileWorkflowEntryReceiptStore(folder.root),
                clock = clock
            ),
            wakes = RecordingWakes(),
            clock = clock
        )
        val outcome = AgentWorkflowScheduleService(
            recipes = archived,
            schedules = service,
            activeBinding = { binding },
            ioDispatcher = Dispatchers.Unconfined
        ).save(
            AgentWorkflowScheduleCommandUi(
                recipeId = "old-recipe",
                kind = AgentWorkflowScheduleKind.DAILY,
                timeLabel = "08:00",
                zoneId = "Asia/Shanghai"
            )
        )

        assertTrue(outcome is AgentWorkflowScheduleOutcome.Rejected)
        assertEquals(
            "WORKFLOW_SCHEDULE_RECIPE_INACTIVE",
            (outcome as AgentWorkflowScheduleOutcome.Rejected).code
        )
        assertTrue(rows(service).isEmpty())
    }

    @Test
    fun `invalid plans are rejected by the typed domain before a write`() = runBlocking {
        val service = service()
        val port = adapter(service)

        val tooShort = port.save(
            AgentWorkflowScheduleCommandUi(
                recipeId = "weekly-review",
                kind = AgentWorkflowScheduleKind.INTERVAL,
                everyMinutes = 14,
                zoneId = "Asia/Shanghai"
            )
        )
        assertEquals(
            WorkflowScheduleErrorCodes.PLAN_INVALID,
            (tooShort as AgentWorkflowScheduleOutcome.Rejected).code
        )

        val unknownZone = port.save(
            AgentWorkflowScheduleCommandUi(
                recipeId = "weekly-review",
                kind = AgentWorkflowScheduleKind.DAILY,
                timeLabel = "08:00",
                zoneId = "Mars/Olympus"
            )
        )
        assertEquals(
            WorkflowScheduleErrorCodes.PLAN_INVALID,
            (unknownZone as AgentWorkflowScheduleOutcome.Rejected).code
        )

        val badTime = port.save(
            AgentWorkflowScheduleCommandUi(
                recipeId = "weekly-review",
                kind = AgentWorkflowScheduleKind.DAILY,
                timeLabel = "25:00",
                zoneId = "Asia/Shanghai"
            )
        )
        assertEquals(
            WorkflowScheduleErrorCodes.PLAN_INVALID,
            (badTime as AgentWorkflowScheduleOutcome.Rejected).code
        )

        val emptyWeek = port.save(
            AgentWorkflowScheduleCommandUi(
                recipeId = "weekly-review",
                kind = AgentWorkflowScheduleKind.WEEKLY,
                timeLabel = "08:00",
                weekdays = emptySet(),
                zoneId = "Asia/Shanghai"
            )
        )
        assertEquals(
            WorkflowScheduleErrorCodes.PLAN_INVALID,
            (emptyWeek as AgentWorkflowScheduleOutcome.Rejected).code
        )

        assertTrue(rows(service).isEmpty())
    }

    @Test
    fun `clock plans map to typed zoned schedules`() = runBlocking {
        val service = service()
        val port = adapter(service)

        port.save(
            AgentWorkflowScheduleCommandUi(
                recipeId = "weekly-review",
                kind = AgentWorkflowScheduleKind.DAILY,
                timeLabel = "9:05",
                zoneId = "Asia/Shanghai"
            )
        )
        val daily = rows(service).single().schedule
        assertEquals(
            WorkflowSchedule.Daily(LocalTime.of(9, 5), ZoneId.of("Asia/Shanghai")),
            daily
        )

        port.save(
            AgentWorkflowScheduleCommandUi(
                recipeId = "weekly-review",
                kind = AgentWorkflowScheduleKind.WEEKLY,
                timeLabel = "18:30",
                weekdays = setOf(AgentWorkflowWeekday.MONDAY, AgentWorkflowWeekday.SUNDAY),
                zoneId = "Europe/Berlin"
            )
        )
        val weekly = rows(service).single().schedule
        assertEquals(
            WorkflowSchedule.Weekly(
                days = setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY),
                time = LocalTime.of(18, 30),
                zone = ZoneId.of("Europe/Berlin")
            ),
            weekly
        )

        val listed = port.list("weekly-review")
        val ui = (listed as AgentWorkflowScheduleListResult.Loaded).schedules.single()
        assertEquals(AgentWorkflowScheduleKind.WEEKLY, ui.kind)
        assertEquals(
            setOf(AgentWorkflowWeekday.MONDAY, AgentWorkflowWeekday.SUNDAY),
            ui.weekdays
        )
        assertEquals("Europe/Berlin", ui.zoneId)
        assertEquals("18:30", ui.timeLabel)
    }

    @Test
    fun `a new recipe version replaces the stale pinned row`() = runBlocking {
        val service = service()
        val port = adapter(service)
        port.save(
            AgentWorkflowScheduleCommandUi(
                recipeId = "weekly-review",
                kind = AgentWorkflowScheduleKind.INTERVAL,
                everyMinutes = 60,
                zoneId = "Asia/Shanghai"
            )
        )
        assertEquals(1, rows(service).size)

        recipes.publish(testWorkflowRecipe(id = "weekly-review", version = 2L), now)
        val outcome = port.save(
            AgentWorkflowScheduleCommandUi(
                recipeId = "weekly-review",
                kind = AgentWorkflowScheduleKind.INTERVAL,
                everyMinutes = 30,
                zoneId = "Asia/Shanghai"
            )
        )

        assertTrue(outcome is AgentWorkflowScheduleOutcome.Saved)
        assertEquals(
            2L,
            (outcome as AgentWorkflowScheduleOutcome.Saved).schedule.definitionVersion
        )
        val stored = rows(service)
        assertEquals(1, stored.size)
        assertEquals(2L, stored.single().version)
        assertEquals(WorkflowSchedule.Interval(30), stored.single().schedule)
    }

    @Test
    fun `listed rows always reflect the newest pinned version`() = runBlocking {
        val service = service()
        val port = adapter(service)
        port.save(
            AgentWorkflowScheduleCommandUi(
                recipeId = "weekly-review",
                kind = AgentWorkflowScheduleKind.INTERVAL,
                everyMinutes = 60,
                zoneId = "Asia/Shanghai"
            )
        )
        recipes.publish(testWorkflowRecipe(id = "weekly-review", version = 2L), now)
        port.save(
            AgentWorkflowScheduleCommandUi(
                recipeId = "weekly-review",
                kind = AgentWorkflowScheduleKind.INTERVAL,
                everyMinutes = 45,
                zoneId = "Asia/Shanghai"
            )
        )

        val listed = port.list("weekly-review")
        val ui = (listed as AgentWorkflowScheduleListResult.Loaded).schedules.single()
        assertEquals(2L, ui.definitionVersion)
        assertEquals(45, ui.everyMinutes)
    }

    @Test
    fun `empty list is an empty loaded result`() = runBlocking {
        val result = adapter(service()).list("weekly-review")

        assertTrue(result is AgentWorkflowScheduleListResult.Loaded)
        assertTrue((result as AgentWorkflowScheduleListResult.Loaded).schedules.isEmpty())
    }

    @Test
    fun `remove deletes only the recipe rows and reports the second call as absent`() = runBlocking {
        recipes.publish(testWorkflowRecipe(id = "other-recipe"), now)
        val service = service()
        val port = adapter(service)
        port.save(
            AgentWorkflowScheduleCommandUi(
                recipeId = "weekly-review",
                kind = AgentWorkflowScheduleKind.INTERVAL,
                everyMinutes = 60,
                zoneId = "Asia/Shanghai"
            )
        )
        port.save(
            AgentWorkflowScheduleCommandUi(
                recipeId = "other-recipe",
                kind = AgentWorkflowScheduleKind.INTERVAL,
                everyMinutes = 90,
                zoneId = "Asia/Shanghai"
            )
        )
        assertEquals(2, rows(service).size)

        val removed = port.remove("weekly-review")
        assertTrue(removed is AgentWorkflowScheduleOutcome.Removed)
        assertTrue((removed as AgentWorkflowScheduleOutcome.Removed).removed)
        assertEquals(
            listOf("recipe-other-recipe"),
            rows(service).map { it.definitionId }
        )

        val again = port.remove("weekly-review")
        assertTrue(again is AgentWorkflowScheduleOutcome.Removed)
        assertFalse((again as AgentWorkflowScheduleOutcome.Removed).removed)
    }

    @Test
    fun `disabling a plan keeps the row but cancels its wake`() = runBlocking {
        val wakes = RecordingWakes()
        val service = service(wakes)
        val port = adapter(service)
        port.save(
            AgentWorkflowScheduleCommandUi(
                recipeId = "weekly-review",
                kind = AgentWorkflowScheduleKind.INTERVAL,
                everyMinutes = 60,
                zoneId = "Asia/Shanghai",
                enabled = false
            )
        )

        val row = rows(service).single()
        assertFalse(row.enabled)
        assertTrue(wakes.cancelled.contains("recipe-weekly-review@1"))
        assertNull(row.lastDeliveredMillis)
    }

    private class RecordingWakes : WorkflowWakeScheduler {
        val scheduled = ArrayList<Pair<WorkflowScheduleRecord, Long>>()
        val cancelled = ArrayList<String>()
        var sweepEnabled = false

        override fun schedule(record: WorkflowScheduleRecord, nowMillis: Long): Boolean {
            scheduled += record to nowMillis
            return true
        }

        override fun cancel(definitionId: String, version: Long) {
            cancelled += "$definitionId@$version"
        }

        override fun setSweepEnabled(enabled: Boolean): Boolean {
            sweepEnabled = enabled
            return true
        }
    }
}
