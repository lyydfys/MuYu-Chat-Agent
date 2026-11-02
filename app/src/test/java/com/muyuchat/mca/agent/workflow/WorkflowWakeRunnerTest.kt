package com.muyuchat.mca.agent.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0008 worker ownership: one wake uses the process root, and every terminal path settles a
 * worker-only process exactly once. This is behavioural proof; the source-contract test only
 * checks wiring that cannot be exercised without Android.
 */
class WorkflowWakeRunnerTest {

    private val idle = WorkflowTriggerOutcome.Idle("wf-1", 1L)

    @Test
    fun `a due slot posts foreground before running and settles once after`() = runBlocking {
        val events = ArrayList<String>()
        val runner = WorkflowWakeRunner(
            schedules = { events += "schedules"; source(events, due = true, outcomes = listOf(idle)) },
            settle = { events += "settle" }
        )

        val result = runner.run { events += "foreground" }

        assertEquals(WorkflowWakeRunResult.Completed(listOf(idle)), result)
        assertEquals(listOf("schedules", "hasDue", "foreground", "runDue", "settle"), events)
    }

    @Test
    fun `an empty sweep still settles without showing a foreground notification`() = runBlocking {
        val events = ArrayList<String>()
        val runner = WorkflowWakeRunner(
            schedules = { events += "schedules"; source(events, due = false) },
            settle = { events += "settle" }
        )

        val result = runner.run { events += "foreground" }

        assertEquals(WorkflowWakeRunResult.Completed(emptyList()), result)
        assertEquals(listOf("schedules", "hasDue", "runDue", "settle"), events)
    }

    @Test
    fun `failure to obtain the process root settles and never runs a slot`() = runBlocking {
        val events = ArrayList<String>()
        val failure = IllegalStateException("root unavailable")
        val runner = WorkflowWakeRunner(
            schedules = { events += "schedules"; throw failure },
            settle = { events += "settle" }
        )

        val result = runner.run { events += "foreground" }

        assertEquals(WorkflowWakeRunResult.Failed(failure), result)
        assertEquals(listOf("schedules", "settle"), events)
    }

    @Test
    fun `failure while checking due slots settles without running`() = runBlocking {
        val events = ArrayList<String>()
        val failure = IllegalStateException("schedule store corrupt")
        val runner = WorkflowWakeRunner(
            schedules = { eventlessSource(events, dueError = failure) },
            settle = { events += "settle" }
        )

        val result = runner.run { events += "foreground" }

        assertEquals(WorkflowWakeRunResult.Failed(failure), result)
        assertEquals(listOf("hasDue", "settle"), events)
    }

    @Test
    fun `failure while running a slot settles once and reports the cause`() = runBlocking {
        val events = ArrayList<String>()
        val failure = IllegalStateException("workflow start failed")
        val runner = WorkflowWakeRunner(
            schedules = { eventlessSource(events, due = true, runError = failure) },
            settle = { events += "settle" }
        )

        val result = runner.run { events += "foreground" }

        assertEquals(WorkflowWakeRunResult.Failed(failure), result)
        assertEquals(listOf("hasDue", "foreground", "runDue", "settle"), events)
    }

    @Test
    fun `a foreground refusal prevents unattended execution and still settles`() = runBlocking {
        val events = ArrayList<String>()
        val failure = IllegalStateException("foreground not allowed")
        val runner = WorkflowWakeRunner(
            schedules = { eventlessSource(events, due = true) },
            settle = { events += "settle" }
        )

        val result = runner.run { events += "foreground"; throw failure }

        assertEquals(WorkflowWakeRunResult.Failed(failure), result)
        assertEquals(listOf("hasDue", "foreground", "settle"), events)
    }

    @Test
    fun `cancellation rethrows after settling exactly once`() = runBlocking {
        val events = ArrayList<String>()
        val cancelled = CancellationException("work stopped")
        val runner = WorkflowWakeRunner(
            schedules = { eventlessSource(events, due = true, runError = cancelled) },
            settle = { events += "settle" }
        )

        val observed = try {
            runner.run { events += "foreground" }
            null
        } catch (expected: CancellationException) {
            expected
        }

        assertNotNull("cancellation must not be converted into a failed wake", observed)
        assertEquals(1, events.count { it == "settle" })
        assertEquals("settle", events.last())
    }

    private fun source(
        events: MutableList<String>,
        due: Boolean,
        outcomes: List<WorkflowTriggerOutcome> = emptyList()
    ): FakeWakeSource = FakeWakeSource(events, due = due, outcomes = outcomes)

    private fun eventlessSource(
        events: MutableList<String>,
        due: Boolean = false,
        dueError: Throwable? = null,
        runError: Throwable? = null
    ): FakeWakeSource = FakeWakeSource(
        events = events,
        due = due,
        dueError = dueError,
        runError = runError
    )

    private class FakeWakeSource(
        private val events: MutableList<String>,
        private val due: Boolean = false,
        private val outcomes: List<WorkflowTriggerOutcome> = emptyList(),
        private val dueError: Throwable? = null,
        private val runError: Throwable? = null
    ) : WorkflowWakeSource {
        override suspend fun hasDue(): Boolean {
            events += "hasDue"
            dueError?.let { throw it }
            return due
        }

        override suspend fun runDue(): List<WorkflowTriggerOutcome> {
            events += "runDue"
            runError?.let { throw it }
            return outcomes
        }
    }
}
