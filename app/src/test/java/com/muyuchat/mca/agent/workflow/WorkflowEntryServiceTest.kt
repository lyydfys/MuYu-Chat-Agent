package com.muyuchat.mca.agent.workflow

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** N20/N22: manual/schedule entry records bounded run history without becoming a state machine. */
class WorkflowEntryServiceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_000L

    private fun store() = FileWorkflowRunHistoryStore(folder.root)

    private fun historyOf(store: WorkflowRunHistoryStore): List<WorkflowRunHistoryEntry> = runBlocking {
        store.list(20)
    }

    private fun completed(): WorkflowExecutionResult.Completed =
        WorkflowExecutionResult.Completed("run", emptyMap(), emptyList())

    private fun service(
        result: suspend () -> WorkflowExecutionResult,
        history: WorkflowRunHistoryStore = store(),
        failHistory: Boolean = false,
        runId: String = "wfrun-test"
    ): WorkflowEntryService {
        val safeHistory = if (failHistory) {
            object : WorkflowRunHistoryStore {
                override suspend fun put(entry: WorkflowRunHistoryEntry) = error("history down")
                override suspend fun list(limit: Int) = emptyList<WorkflowRunHistoryEntry>()
            }
        } else {
            history
        }
        return WorkflowEntryService(
            executor = { result() },
            history = safeHistory,
            clock = { now },
            runIdFactory = { runId }
        )
    }

    @Test
    fun `a manual run records STARTED then COMPLETED with the generated run id`() = runBlocking {
        val history = store()
        val result = service({ completed() }, history).runManual("wf-1", 2L)

        assertTrue(result is WorkflowExecutionResult.Completed)
        val entry = historyOf(history).single()
        assertEquals("wfrun-test", entry.runId)
        assertEquals(WorkflowRunStatus.COMPLETED, entry.status)
        assertEquals(WorkflowTriggerKind.MANUAL, entry.trigger)
        assertEquals(null, entry.slotMillis)
        assertNotNull(entry.finishedAtMillis)
    }

    @Test
    fun `a failed run records its typed error`() = runBlocking {
        val history = store()
        now = 2_000L
        service(
            { WorkflowExecutionResult.Failed("wfr", "WF_EXPR_ERROR", "bad condition", emptyList()) },
            history
        ).runManual("wf-1", 2L)

        val entry = historyOf(history).single()
        assertEquals(WorkflowRunStatus.FAILED, entry.status)
        assertEquals("WF_EXPR_ERROR", entry.errorCode)
    }

    @Test
    fun `a rejected run is recorded as rejected`() = runBlocking {
        val history = store()
        service(
            { WorkflowExecutionResult.Rejected("wfr", "WF_DEFINITION_INVALID", "invalid") },
            history
        ).runManual("wf-1", 2L)

        assertEquals(WorkflowRunStatus.REJECTED, historyOf(history).single().status)
    }

    @Test
    fun `a scheduled run records the trigger kind and slot`() = runBlocking {
        val history = store()
        service({ completed() }, history).runScheduled("wf-1", 3L, 60_000L)

        val entry = historyOf(history).single()
        assertEquals(WorkflowTriggerKind.SCHEDULE, entry.trigger)
        assertEquals(60_000L, entry.slotMillis)
    }

    @Test
    fun `an executor exception becomes a typed rejection and a failed history row`() = runBlocking {
        val history = store()
        val result = service({ error("boom") }, history).runManual("wf-1", 2L)

        assertEquals("WF_ENTRY_FAILED", (result as WorkflowExecutionResult.Rejected).errorCode)
        val entry = historyOf(history).single()
        assertEquals(WorkflowRunStatus.FAILED, entry.status)
        assertEquals("WF_ENTRY_FAILED", entry.errorCode)
    }

    @Test
    fun `a history outage never changes the execution result`() = runBlocking {
        val result = service({ completed() }, failHistory = true).runManual("wf-1", 2L)
        assertTrue(result is WorkflowExecutionResult.Completed)
    }

    @Test
    fun `history keeps one row per run and lists newest first`() = runBlocking {
        val history = store()
        now = 1_000L
        service({ completed() }, history, runId = "run-a").runManual("wf-a", 1L)
        now = 5_000L
        service({ completed() }, history, runId = "run-b").runManual("wf-b", 1L)

        val entries = historyOf(history)
        assertEquals(2, entries.size)
        assertEquals(listOf("wf-b", "wf-a"), entries.map { it.definitionId })
    }

    @Test
    fun `a corrupt history file fails closed on read`() = runBlocking {
        val history = store()
        service({ completed() }, history).runManual("wf-1", 1L)
        File(folder.root, "workflow-runs/history.json").writeText("{not json")

        assertTrue(runCatching { history.list(5) }.isFailure)
    }
}
