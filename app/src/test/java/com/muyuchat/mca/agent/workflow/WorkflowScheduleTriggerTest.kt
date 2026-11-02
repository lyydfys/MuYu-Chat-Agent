package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.runtime.workflow.WorkflowSchedule
import java.io.File
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** N22: schedule trigger semantics (seed, latest-only, at-most-once, fixed version). */
class WorkflowScheduleTriggerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = 0L
    private val clock: () -> Long = { now }
    private val zone = ZoneId.of("Asia/Shanghai")
    private val requests = ArrayList<WorkflowExecutionRequest>()

    private fun store() = FileWorkflowScheduleStore(folder.root, clock)

    private fun record(id: String = "wf-1", version: Long = 3L, schedule: WorkflowSchedule =
        WorkflowSchedule.Interval(60)) = WorkflowScheduleRecord(id, version, schedule)

    private fun trigger(store: WorkflowScheduleStore, fail: Boolean = false) = WorkflowScheduleTrigger(
        store = store,
        executor = { request, _ ->
            requests += request
            if (fail) error("executor down")
            WorkflowExecutionResult.Completed(request.runId ?: "run", emptyMap(), emptyList())
        },
        clock = clock
    )

    private fun minute(n: Long): Long = n * 60_000L

    @Test
    fun `the first tick seeds without executing and the next due slot runs once`() = runBlocking {
        val store = store()
        store.put(record())
        now = minute(10)

        val first = trigger(store).runDue().single()
        assertTrue(first is WorkflowTriggerOutcome.Seeded)
        assertTrue(requests.isEmpty())

        now = minute(71)
        val second = trigger(store).runDue().single()
        assertTrue(second is WorkflowTriggerOutcome.Executed)
        assertEquals(listOf(WorkflowExecutionRequest("wf-1", 3L, "wfrun-wf-1-${minute(60)}")), requests)

        now = minute(72)
        val third = trigger(store).runDue().single()
        assertTrue(third is WorkflowTriggerOutcome.Idle)
        assertEquals(1, requests.size)
    }

    @Test
    fun `a long downtime fires only the latest slot`() = runBlocking {
        val store = store()
        store.put(record(schedule = WorkflowSchedule.Interval(60)))
        now = minute(10)
        trigger(store).runDue()
        requests.clear()

        now = minute(10 * 60)
        val outcome = trigger(store).runDue().single()
        assertEquals(
            minute(600),
            (outcome as WorkflowTriggerOutcome.Executed).slotMillis
        )
        assertEquals(1, requests.size)
    }

    @Test
    fun `a disabled schedule never seeds or executes`() = runBlocking {
        val store = store()
        store.put(record().copy(enabled = false))
        now = minute(10)
        val outcome = trigger(store).runDue().single()
        assertEquals("disabled", (outcome as WorkflowTriggerOutcome.Skipped).reason)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `store claims are scoped to the exact definition version`() = runBlocking {
        val store = store()
        store.put(record("wf-2"))
        now = minute(10)
        trigger(store).runDue()
        store.put(record("wf-2").copy(lastDeliveredMillis = minute(10), lastSlotKey = "seed"))

        now = minute(71)
        val used = store.claim("wf-2", 3L, minute(60).toString(), minute(60))
        assertEquals(WorkflowScheduleClaim.Claimed, used)
        assertEquals(
            WorkflowScheduleClaim.NotFound,
            store.claim("missing", 1L, minute(60).toString(), minute(60))
        )
    }

    @Test
    fun `a failed execution consumes the slot and is reported`() = runBlocking {
        val store = store()
        store.put(record())
        now = minute(10)
        trigger(store).runDue()
        now = minute(71)
        val failed = trigger(store, fail = true).runDue().single()
        assertEquals("WF_TRIGGER_FAILED", (failed as WorkflowTriggerOutcome.Failed).errorCode)

        now = minute(72)
        assertTrue(trigger(store).runDue().single() is WorkflowTriggerOutcome.Idle)
    }

    @Test
    fun `schedules for different versions stay independent and sorted`() = runBlocking {
        val store = store()
        store.put(record("wf-b", 2L))
        store.put(record("wf-a", 5L))
        now = minute(10)
        val outcomes = trigger(store).runDue()
        assertEquals(
            listOf("wf-a" to 5L, "wf-b" to 2L),
            outcomes.map { it.definitionId to it.version }
        )
    }

    @Test
    fun `a corrupt schedule file fails closed instead of resetting`() = runBlocking {
        val store = store()
        store.put(record())
        File(folder.root, "workflow-schedules/schedules.json").writeText("{broken")

        assertTrue(runCatching { store.load() }.isFailure)
    }

    @Test
    fun `schedule kinds round-trip through the durable store`() = runBlocking {
        val store = store()
        val daily = WorkflowSchedule.Daily(LocalTime.of(8, 15), zone)
        val weekly = WorkflowSchedule.Weekly(
            setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.FRIDAY),
            LocalTime.of(21, 5),
            zone
        )
        store.put(record("wf-daily", 1L, daily))
        store.put(record("wf-weekly", 2L, weekly))
        store.put(record("wf-interval", 3L, WorkflowSchedule.Interval(90)))

        val loaded = store.load().associateBy { it.definitionId }
        assertEquals(daily, loaded.getValue("wf-daily").schedule)
        assertEquals(weekly, loaded.getValue("wf-weekly").schedule)
        assertEquals(WorkflowSchedule.Interval(90), loaded.getValue("wf-interval").schedule)
    }
}
