package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentWorkflowRunSpec
import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.contract.workflow.WorkflowNode
import com.muyuchat.core.agent.contract.workflow.WorkflowNodeType
import com.muyuchat.core.agent.contract.workflow.WorkflowPort
import com.muyuchat.core.agent.contract.workflow.WorkflowPortType
import com.muyuchat.core.agent.runtime.workflow.WorkflowSchedule
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** N22: durable schedule administration, frozen model plans and WorkManager wake installation. */
class WorkflowScheduleServiceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = 10 * 60_000L
    private val clock: () -> Long = { now }
    private val executed = ArrayList<WorkflowExecutionRequest>()

    private val binding = AgentModelBinding(
        providerId = "agent.local",
        modelId = "qwen-local",
        runtime = "llama.cpp",
        assetFingerprint = "a".repeat(64),
        protocolMode = AgentProtocolMode.PROMPTED_V1,
        modelDisplayName = "Qwen Local",
        contextWindowTokens = 8_192,
        samplingJson = """{"temperature":0.2}""",
        capabilityEvidenceVersion = "evidence-1"
    )
    private val plan = WorkflowAgentTaskPlan(
        modelBinding = binding,
        policy = AgentRunPolicy(
            networkPolicy = AgentNetworkPolicy.LOCAL_ONLY,
            enabledToolKeys = setOf("workspace.read_text"),
            skillIds = setOf("skill-a"),
            memorySpaceIds = setOf("space-a")
        ),
        displayTitle = "定时读取"
    )

    private fun store() = FileWorkflowScheduleStore(folder.root, clock)

    private fun facade(): WorkflowTriggerFacade = WorkflowTriggerFacade(
        entry = WorkflowEntryService(
            executor = { request ->
                executed += request
                WorkflowExecutionResult.Completed(request.runId ?: "run", emptyMap(), emptyList())
            },
            history = FileWorkflowRunHistoryStore(folder.root),
            clock = clock
        ),
        receipts = FileWorkflowEntryReceiptStore(folder.root),
        clock = clock
    )

    private fun service(
        definitions: AgentWorkflowDefinitionStore = Definitions(),
        wakes: WorkflowWakeScheduler = RecordingWakes()
    ) = WorkflowScheduleService(
        store = store(),
        definitions = definitions,
        facade = facade(),
        wakes = wakes,
        clock = clock
    )

    @Test
    fun `a saved plan is persisted with the row and used by the next slot`() = runBlocking {
        val wakes = RecordingWakes()
        val service = service(wakes = wakes)
        val saved = service.upsert(
            WorkflowScheduleCommand("wf-1", 1L, WorkflowSchedule.Interval(60), agentTask = plan)
        )
        assertTrue(saved is WorkflowScheduleOutcome.Saved)
        assertTrue((saved as WorkflowScheduleOutcome.Saved).wakeArmed)
        assertEquals(1, wakes.scheduled.size)
        assertEquals(now, wakes.scheduled.single().second)
        assertTrue(wakes.sweepEnabled)

        val reloaded = store().load().single()
        assertEquals("qwen-local", reloaded.agentTask?.modelBinding?.modelId)
        assertEquals(setOf("workspace.read_text"), reloaded.agentTask?.policy?.enabledToolKeys)
        assertEquals(setOf("space-a"), reloaded.agentTask?.policy?.memorySpaceIds)

        service.runDue()
        assertTrue(executed.isEmpty())
        assertFalse(service.hasDue())
        now = 71 * 60_000L
        assertTrue(service.hasDue())
        val outcome = service.runDue().single()
        assertTrue(outcome is WorkflowTriggerOutcome.Executed)
        assertEquals(1, executed.size)
        assertEquals(plan, executed.single().agentTaskPlan)
    }

    @Test
    fun `a cold start re-arms persisted rows and the sweep without delivering a slot`() = runBlocking {
        service().upsert(WorkflowScheduleCommand("wf-1", 1L, WorkflowSchedule.Interval(60)))
        val wakes = RecordingWakes()
        val restarted = service(wakes = wakes)
        assertFalse(wakes.sweepEnabled)

        assertTrue(restarted.rearm())
        assertEquals(1, wakes.scheduled.size)
        assertEquals(now, wakes.scheduled.single().second)
        assertTrue(wakes.sweepEnabled)
        assertTrue(executed.isEmpty())
        assertFalse(restarted.hasDue())
    }

    @Test
    fun `a wake reuses the frozen record plan instead of the caller's current plan`() = runBlocking {
        val service = service()
        service.upsert(
            WorkflowScheduleCommand("wf-1", 1L, WorkflowSchedule.Interval(60), agentTask = plan)
        )
        service.runDue()
        now = 71 * 60_000L

        val current = plan.copy(
            modelBinding = binding.copy(modelId = "chat-selected-model"),
            displayTitle = "current selection"
        )
        val outcome = facade().scheduleTrigger(store(), current).runDue().single()

        assertTrue(outcome is WorkflowTriggerOutcome.Executed)
        assertEquals(1, executed.size)
        assertEquals(plan, executed.single().agentTaskPlan)
    }

    @Test
    fun `a schedule without a frozen plan may use the caller fallback`() = runBlocking {
        val service = service()
        service.upsert(WorkflowScheduleCommand("wf-1", 1L, WorkflowSchedule.Interval(60)))
        service.runDue()
        now = 71 * 60_000L

        val fallback = plan.copy(displayTitle = "fallback")
        facade().scheduleTrigger(store(), fallback).runDue().single()

        assertEquals(fallback, executed.single().agentTaskPlan)
    }

    @Test
    fun `wake arithmetic is pure, disabled-aware and names rows stably`() {
        val record = WorkflowScheduleRecord("wf-1", 1L, WorkflowSchedule.Interval(60))
        val wake = WorkflowWakePlan.next(record, now)
        assertEquals(60 * 60_000L, wake?.atMillis)
        assertEquals(50 * 60_000L, wake?.delayMillis)
        assertNull(WorkflowWakePlan.next(record.copy(enabled = false), now))

        val name = WorkflowWakePlan.uniqueName("wf-1", 1L)
        assertEquals(name, WorkflowWakePlan.uniqueName("wf-1", 1L))
        assertTrue(name != WorkflowWakePlan.uniqueName("wf-1", 2L))
        assertTrue(name.startsWith("workflow-wake-"))
    }

    @Test
    fun `editing a schedule preserves the delivered slot so old slots do not replay`() = runBlocking {
        val service = service()
        service.upsert(WorkflowScheduleCommand("wf-1", 1L, WorkflowSchedule.Interval(60)))
        service.runDue()
        now = 71 * 60_000L
        service.runDue()
        val beforeEdit = store().load().single()
        assertEquals(60 * 60_000L, beforeEdit.lastDeliveredMillis)

        now = 72 * 60_000L
        service.upsert(WorkflowScheduleCommand("wf-1", 1L, WorkflowSchedule.Interval(120)))
        val afterEdit = store().load().single()
        assertEquals(beforeEdit.lastDeliveredMillis, afterEdit.lastDeliveredMillis)
        assertEquals(beforeEdit.lastSlotKey, afterEdit.lastSlotKey)

        val next = service.runDue().single()
        assertTrue(next is WorkflowTriggerOutcome.Idle)
        assertEquals(1, executed.size)
    }

    @Test
    fun `a record written before plans existed still decodes with no authorized model`() = runBlocking {
        val directory = File(folder.root, "workflow-schedules").also { it.mkdirs() }
        File(directory, "schedules.json").writeText(
            """{"schema":1,"records":[{"definitionId":"wf-old","version":2,"enabled":true,""" +
                """"schedule":{"kind":"interval","everyMinutes":60}}]}""",
            Charsets.UTF_8
        )
        val record = store().load().single()
        assertEquals("wf-old", record.definitionId)
        assertNull(record.agentTask)
    }

    @Test
    fun `removing the last schedule cancels its wake and the periodic sweep`() = runBlocking {
        val wakes = RecordingWakes()
        val service = service(wakes = wakes)
        service.upsert(WorkflowScheduleCommand("wf-1", 1L, WorkflowSchedule.Interval(60)))
        val removed = service.remove("wf-1", 1L)
        assertTrue(removed is WorkflowScheduleOutcome.Removed)
        assertTrue((removed as WorkflowScheduleOutcome.Removed).removed)
        assertTrue(store().load().isEmpty())
        assertEquals(listOf("wf-1@1"), wakes.cancelled)
        assertFalse(wakes.sweepEnabled)
    }

    @Test
    fun `an unpublished version and a nested workflow plan are rejected before any write`() = runBlocking {
        val noDefinition = service(definitions = Definitions(versions = emptySet()))
        val missing = noDefinition.upsert(
            WorkflowScheduleCommand("wf-1", 1L, WorkflowSchedule.Interval(60))
        )
        assertEquals(
            WorkflowScheduleErrorCodes.DEFINITION_NOT_FOUND,
            (missing as WorkflowScheduleOutcome.Rejected).code
        )
        assertTrue(store().load().isEmpty())

        val nested = WorkflowAgentTaskPlan(
            modelBinding = binding,
            policy = AgentRunPolicy(workflow = AgentWorkflowRunSpec("wf-other", 1L)),
            displayTitle = "nested"
        )
        val rejected = service().upsert(
            WorkflowScheduleCommand("wf-1", 1L, WorkflowSchedule.Interval(60), agentTask = nested)
        )
        assertEquals(
            WorkflowScheduleErrorCodes.PLAN_INVALID,
            (rejected as WorkflowScheduleOutcome.Rejected).code
        )
        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `two wakes at the same instant claim the slot once`() = runBlocking {
        val service = service()
        service.upsert(WorkflowScheduleCommand("wf-1", 1L, WorkflowSchedule.Interval(60)))
        service.runDue()
        now = 71 * 60_000L
        val first = service.runDue().single()
        val second = service.runDue().single()
        assertTrue(first is WorkflowTriggerOutcome.Executed)
        assertTrue(second is WorkflowTriggerOutcome.Skipped || second is WorkflowTriggerOutcome.Idle)
        assertEquals(1, executed.size)
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

    private class Definitions(
        private val versions: Set<Pair<String, Long>> = setOf("wf-1" to 1L, "wf-old" to 2L)
    ) : AgentWorkflowDefinitionStore {
        private val definition = WorkflowDefinition(
            definitionId = "wf-1",
            version = 1L,
            displayName = "定时流程",
            outputs = listOf(WorkflowPort("result", WorkflowPortType.STRING)),
            nodes = listOf(
                WorkflowNode("trigger", WorkflowNodeType.TRIGGER),
                WorkflowNode("agent", WorkflowNodeType.AGENT_TASK, configJson = """{"goal":"read"}"""),
                WorkflowNode("done", WorkflowNodeType.OUTPUT, configJson = """{"port":"result"}""")
            ),
            edges = listOf(
                com.muyuchat.core.agent.contract.workflow.WorkflowEdge("trigger", "agent"),
                com.muyuchat.core.agent.contract.workflow.WorkflowEdge("agent", "done")
            )
        )

        override fun list(): List<AgentWorkflowDefinitionSummary> = emptyList()

        override fun draft(definitionId: String): AgentWorkflowDefinitionDraft? = null

        override fun saveDraft(
            definition: WorkflowDefinition,
            expectedRevision: Long?,
            nowMillis: Long
        ): AgentWorkflowDefinitionDraft = error("unused")

        override fun publish(
            definitionId: String,
            expectedDraftRevision: Long,
            nowMillis: Long,
            referencedDefinitions: Map<
                com.muyuchat.core.agent.runtime.workflow.WorkflowDefinitionValidator.SubworkflowRef,
                WorkflowDefinition
                >
        ): WorkflowDefinition = error("unused")

        override fun latest(definitionId: String): WorkflowDefinition? =
            definition.takeIf { definitionId == "wf-1" }

        override fun version(definitionId: String, version: Long): WorkflowDefinition? =
            definition.takeIf { (definitionId to version) in versions }
    }
}
