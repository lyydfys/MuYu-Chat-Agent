package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.workflow.WorkflowSchedule
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * N20/N22 entry facade: manual and scheduled commands reuse the original receipt for a repeated
 * command id or run id, record terminal history once, and fail closed when the receipt store is corrupt.
 */
class WorkflowTriggerFacadeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = 10_000L
    private var executions = 0
    private val executedRequests = mutableListOf<WorkflowExecutionRequest>()

    private fun entry(): WorkflowEntryService = WorkflowEntryService(
        executor = { request ->
            executions += 1
            executedRequests += request
            WorkflowExecutionResult.Completed(
                runId = request.runId ?: "missing-run",
                outputs = mapOf("result" to AgentJsonValue.Str("ok")),
                outcomes = emptyList()
            )
        },
        history = FileWorkflowRunHistoryStore(folder.root),
        clock = { now },
        runIdFactory = { "wfrun-generated" }
    )

    private fun facade(
        receiptStore: WorkflowEntryReceiptStore = FileWorkflowEntryReceiptStore(folder.root)
    ): WorkflowTriggerFacade = WorkflowTriggerFacade(
        entry = entry(),
        receipts = receiptStore,
        clock = { now }
    )

    private fun manualCommand(
        commandId: String = "manual-1",
        runId: String? = null
    ): WorkflowManualRunCommand = WorkflowManualRunCommand(
        commandId = commandId,
        definitionId = "wf-1",
        version = 1L,
        runId = runId
    )

    @Test
    fun aRepeatedManualCommandReusesTheOriginalReceiptAndRunsOnce() = runBlocking {
        val facade = facade()
        val plan = WorkflowAgentTaskPlan(
            modelBinding = testWorkflowBinding(),
            policy = AgentRunPolicy(
                networkPolicy = AgentNetworkPolicy.LOCAL_ONLY,
                offlineForced = true
            )
        )
        val command = manualCommand().copy(agentTask = plan)

        val first = facade.runManual(command)
        val second = facade.runManual(command)

        assertEquals(1, executions)
        assertEquals("the frozen model plan reaches execution", plan, executedRequests.single().agentTaskPlan)
        assertEquals(WorkflowEntryStatus.COMPLETED, first.status)
        assertEquals("wfrun-generated", first.runId)
        assertTrue("the second call is a replay", second.idempotentReplay)
        assertEquals(first.runId, second.runId)
        assertEquals(first.outputsJson, second.outputsJson)
        assertEquals(WorkflowEntryStatus.COMPLETED, second.status)
    }

    @Test
    fun aRepeatedRunIdReusesTheReceiptAcrossDifferentCommandIds() = runBlocking {
        val facade = facade()

        val first = facade.runManual(manualCommand(commandId = "manual-a", runId = "run-fixed"))
        val replay = facade.runManual(manualCommand(commandId = "manual-b", runId = "run-fixed"))

        assertEquals(1, executions)
        assertEquals("run-fixed", executedRequests.single().runId)
        assertEquals("run-fixed", first.runId)
        assertEquals("run-fixed", replay.runId)
        assertTrue(replay.idempotentReplay)
    }

    @Test
    fun aFailedRunIsAlsoIdempotent() = runBlocking {
        val facade = WorkflowTriggerFacade(
            entry = WorkflowEntryService(
                executor = {
                    executions += 1
                    WorkflowExecutionResult.Failed(
                        runId = "run-failed",
                        errorCode = "WF_EXPR_ERROR",
                        safeMessage = "bad condition",
                        outcomes = emptyList()
                    )
                },
                history = FileWorkflowRunHistoryStore(folder.root),
                clock = { now }
            ),
            receipts = FileWorkflowEntryReceiptStore(folder.root),
            clock = { now }
        )

        val first = facade.runManual(manualCommand(commandId = "manual-fail"))
        val second = facade.runManual(manualCommand(commandId = "manual-fail"))

        assertEquals(1, executions)
        assertEquals(WorkflowEntryStatus.FAILED, first.status)
        assertEquals("WF_EXPR_ERROR", first.errorCode)
        assertTrue(second.idempotentReplay)
        assertEquals("WF_EXPR_ERROR", second.errorCode)
    }

    @Test
    fun aScheduleTickClaimsOneSlotAndItsReplayIsIdempotent() = runBlocking {
        val scheduleStore = FileWorkflowScheduleStore(folder.root, { now })
        scheduleStore.put(
            WorkflowScheduleRecord("wf-1", 1L, WorkflowSchedule.Interval(60))
        )
        val facade = facade()
        val trigger = facade.scheduleTrigger(scheduleStore)

        // First tick only seeds: no historical fire.
        val seeded = trigger.runDue().single()
        assertTrue(seeded is WorkflowTriggerOutcome.Seeded)
        assertEquals(0, executions)

        // Next due slot executes exactly once through the entry service.
        now = 71 * 60_000L
        val fired = trigger.runDue().single()
        val executed = fired as WorkflowTriggerOutcome.Executed
        assertEquals(1, executions)
        assertEquals(60 * 60_000L, executed.slotMillis)

        // A repeated tick sees the claimed slot, so it never executes again.
        now = 72 * 60_000L
        trigger.runDue()
        assertEquals(1, executions)

        // Replaying the same slot through the explicit command returns the stored receipt.
        val replay = facade.runScheduled(
            WorkflowScheduledRunCommand(
                commandId = "manual-replay",
                definitionId = "wf-1",
                version = 1L,
                slotMillis = 60 * 60_000L
            )
        )
        assertEquals(1, executions)
        assertTrue(replay.idempotentReplay)
        assertEquals(WorkflowEntryStatus.COMPLETED, replay.status)
    }

    @Test
    fun aCorruptReceiptStoreFailsClosedWithoutStartingARun() = runBlocking {
        val receiptStore = FileWorkflowEntryReceiptStore(folder.root)
        val directory = File(folder.root, "workflow-receipts").also { it.mkdirs() }
        File(directory, "receipts.json").writeText("{broken", Charsets.UTF_8)
        val facade = facade(receiptStore)

        val receipt = facade.runManual(manualCommand())

        assertEquals(0, executions)
        assertEquals(WorkflowEntryStatus.REJECTED, receipt.status)
        assertEquals(WorkflowEntryErrorCodes.RECEIPT_STORE_UNAVAILABLE, receipt.errorCode)
    }

    @Test
    fun terminalHistoryIsRecordedOncePerRun() = runBlocking {
        val history = FileWorkflowRunHistoryStore(folder.root)
        val facade = WorkflowTriggerFacade(
            entry = WorkflowEntryService(
                executor = { request ->
                    executions += 1
                    WorkflowExecutionResult.Completed(
                        runId = request.runId ?: "missing-run",
                        outputs = mapOf("result" to AgentJsonValue.Str("ok")),
                        outcomes = emptyList()
                    )
                },
                history = history,
                clock = { now },
                runIdFactory = { "wfrun-history" }
            ),
            receipts = FileWorkflowEntryReceiptStore(folder.root),
            clock = { now }
        )

        facade.runManual(manualCommand(commandId = "history-cmd"))
        facade.runManual(manualCommand(commandId = "history-cmd"))

        val rows = history.list(10)
        assertEquals(1, rows.size)
        assertEquals("wfrun-history", rows.single().runId)
        assertEquals(WorkflowRunStatus.COMPLETED, rows.single().status)
    }
}
