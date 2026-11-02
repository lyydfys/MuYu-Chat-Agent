package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.journal.InMemoryAgentJournal
import com.muyuchat.core.agent.runtime.testing.AgentRuntimeFixtures
import com.muyuchat.core.agent.runtime.testing.FakeToolCatalog
import com.muyuchat.core.agent.runtime.testing.RecordingFrozenCommandHook
import com.muyuchat.core.agent.runtime.testing.RecordingToolExecutor
import com.muyuchat.core.agent.runtime.validation.AgentV1ToolSpecs
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** N03: the proposal/freeze pipeline is the single seam before any tool execution. */
class AgentToolInvocationPipelineTest {

    private val validator = SupportedToolSchemaValidator()
    private val descriptor = AgentV1ToolSpecs.descriptor(AgentV1ToolSpecs.WORKSPACE_READ_TEXT, validator)
    private val journal = InMemoryAgentJournal()
    private val clock = ManualAgentMonotonicClock()
    private val ids = SequentialAgentIdGenerator("pipeline")
    private lateinit var task: AgentTask
    private lateinit var run: AgentRun

    private fun setup(runBudget: AgentBudget = AgentBudget()) = runBlocking {
        task = AgentRuntimeFixtures.task()
        journal.commit(CreateTaskTransition("pipeline-task", task))
        run = AgentRuntimeFixtures.run(state = com.muyuchat.core.agent.contract.AgentRunState.RUNNING)
            .copy(budget = runBudget)
        journal.commit(
            CreateRunTransition(
                "pipeline-run",
                run,
                taskRevision = task.revision,
                taskDisplay = AgentDisplayStatus.RUNNING
            )
        )
    }

    private fun pipeline(
        hook: RecordingFrozenCommandHook = RecordingFrozenCommandHook(ledger = journal),
        catalog: FakeToolCatalog = FakeToolCatalog(mapOf(descriptor.toolKey to RecordingToolExecutor(descriptor)))
    ) = AgentToolInvocationPipeline(
        catalog = catalog,
        schemaValidator = validator,
        toolLedger = journal,
        commandHook = hook,
        budgetPolicy = AgentBudgetPolicy(clock),
        ids = ids,
        clock = clock
    )

    private fun request(
        name: String = descriptor.toolKey,
        argumentsJson: String = """{"path":"inputs/brief.txt"}""",
        callHash: String = "a".repeat(64),
        spent: AgentSpentBudget = AgentSpentBudget()
    ) = AgentInvocationProposalRequest(
        run = run,
        task = task,
        toolName = name,
        argumentsJson = argumentsJson,
        callHash = callHash,
        stepIndex = 0,
        spent = spent,
        providerCallId = "call-1"
    )

    @Test
    fun aReadyCallRecordsTheProposedRowAndFreezesTheCommand() = runBlocking {
        setup()
        val hook = RecordingFrozenCommandHook(ledger = journal)
        var proposedRunId: String? = null

        val prepared = pipeline(hook).prepare(request()) { _, _, _, _ ->
            proposedRunId = run.runId
            run
        }

        assertTrue(prepared is AgentToolInvocationPipeline.Preparation.Ready)
        assertEquals(run.runId, proposedRunId)
        val invocation = journal.readInvocation(
            (prepared as AgentToolInvocationPipeline.Preparation.Ready).invocationId
        )!!
        assertEquals(ToolInvocationStatus.VALIDATED, invocation.status)
        assertEquals("call-1", hook.requests.single().providerCallId)
        assertEquals("a".repeat(64), hook.requests.single().callHash)
        assertTrue(hook.requests.single().normalizedArgumentsJson.contains("inputs/brief.txt"))
    }

    @Test
    fun anUnknownToolIsRejectedBeforeAnyDurableProposal() = runBlocking {
        setup()
        val prepared = pipeline().prepare(request(name = "workspace.missing")) { _, _, _, _ -> run }

        val rejected = prepared as AgentToolInvocationPipeline.Preparation.Rejected
        assertEquals(AgentToolResultStatus.NOT_EXECUTED, rejected.status)
        assertEquals(AgentErrorCodes.TOOL_NOT_FOUND, rejected.errorCode)
        assertEquals(null, rejected.runAfterProposal)
    }

    @Test
    fun invalidArgumentsAreRejectedBeforeAnyDurableProposal() = runBlocking {
        setup()
        val prepared = pipeline().prepare(request(argumentsJson = "{}")) { _, _, _, _ -> run }

        val rejected = prepared as AgentToolInvocationPipeline.Preparation.Rejected
        assertEquals(AgentToolResultStatus.NOT_EXECUTED, rejected.status)
        assertEquals(null, rejected.runAfterProposal)
    }

    @Test
    fun anExhaustedInvocationBudgetIsReportedWithoutRecordingAnything() = runBlocking {
        setup(runBudget = AgentBudget(toolInvocations = 0))
        val prepared = pipeline().prepare(request()) { _, _, _, _ -> run }

        assertTrue(prepared is AgentToolInvocationPipeline.Preparation.BudgetExceeded)
    }

    @Test
    fun aDeniedFreezeReturnsTheRunAfterTheProposedEvent() = runBlocking {
        setup()
        val deniedHook = RecordingFrozenCommandHook(
            ledger = journal,
            factory = { AgentFrozenCommandDecision.Denied(
                AgentErrorCodes.PERMISSION_DENIED,
                "denied by user"
            ) }
        )

        val prepared = pipeline(deniedHook).prepare(request()) { _, _, _, _ -> run }

        val rejected = prepared as AgentToolInvocationPipeline.Preparation.Rejected
        assertEquals(AgentToolResultStatus.DENIED, rejected.status)
        assertEquals(AgentErrorCodes.PERMISSION_DENIED, rejected.errorCode)
        assertNotNull("the PROPOSED event already committed", rejected.runAfterProposal)
    }

    @Test
    fun anApprovalThatCannotBindToTheFrozenRowIsUnbound() = runBlocking {
        setup()
        val waitingHook = RecordingFrozenCommandHook(
            ledger = journal,
            factory = { request ->
                AgentFrozenCommandDecision.WaitingApproval(
                    command = AgentRuntimeFixtures.frozenCommand(request),
                    approval = null
                )
            }
        )

        val prepared = pipeline(waitingHook).prepare(request()) { _, _, _, _ -> run }

        assertTrue(prepared is AgentToolInvocationPipeline.Preparation.Unbound)
    }
}
