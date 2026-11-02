package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentModelResult
import com.muyuchat.core.agent.contract.AgentModelTurn
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.AgentTurnFinish
import com.muyuchat.core.agent.contract.AgentTurnProtocol
import com.muyuchat.core.agent.contract.AgentTurnToolCall
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import com.muyuchat.core.agent.runtime.context.AgentContextAssembler
import com.muyuchat.core.agent.runtime.journal.InMemoryAgentJournal
import com.muyuchat.core.agent.runtime.protocol.AgentNativeTranscriptSerializer
import com.muyuchat.core.agent.runtime.protocol.AgentPromptedSystemPromptBuilder
import com.muyuchat.core.agent.runtime.protocol.AgentPromptedTranscriptSerializer
import com.muyuchat.core.agent.runtime.protocol.PromptedV1Decoder
import com.muyuchat.core.agent.runtime.protocol.PromptedV1TurnDecoder
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import com.muyuchat.core.agent.runtime.testing.AgentRuntimeFixtures
import com.muyuchat.core.agent.runtime.testing.AgentTestFixtures
import com.muyuchat.core.agent.runtime.testing.FakeArtifactVerifier
import com.muyuchat.core.agent.runtime.testing.FakeToolCatalog
import com.muyuchat.core.agent.runtime.testing.QueueModelPort
import com.muyuchat.core.agent.runtime.testing.RecordingFrozenCommandHook
import com.muyuchat.core.agent.runtime.validation.AgentV1ToolSpecs
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N06: bounded read-only batches.
 *
 * These tests prove the two properties the design requires: at most three executor calls are in
 * flight at once (a fourth waits for a free slot), and the transcript/`tool.result` order is the
 * model's input ordinal even when the executors finish in a different order. A batch never partially
 * executes when one call needs approval, and the budget precheck refuses the batch before any freeze.
 */
class AgentToolBatchExecutionTest {

    private val validator = SupportedToolSchemaValidator()
    private val codec = StrictAgentJsonCodec()
    private val clock = ManualAgentMonotonicClock()
    private val ids = SequentialAgentIdGenerator("batch")
    private val descriptor = AgentV1ToolSpecs.descriptor(AgentV1ToolSpecs.WORKSPACE_READ_TEXT, validator)

    /**
     * Executor that only returns once [expectedConcurrent] calls have really overlapped, so the test
     * proves concurrency without needing to steer the scheduler from the test body. [delays] lets a
     * later call finish before an earlier one.
     */
    private class CountingReadExecutor(
        override val descriptor: AgentToolDescriptor,
        private val expectedConcurrent: Int,
        private val delays: Map<String, Long> = emptyMap()
    ) : AgentToolExecutor {
        val entered = mutableListOf<String>()
        val commands = mutableListOf<FrozenToolCommand>()
        private val enoughInFlight = CompletableDeferred<Unit>()
        var inFlight: Int = 0
        var maxInFlight: Int = 0
        var executeCount: Int = 0

        override suspend fun execute(
            command: FrozenToolCommand,
            context: AgentToolContext
        ): AgentToolResult {
            val path = pathOf(command)
            executeCount += 1
            commands += command
            inFlight += 1
            maxInFlight = maxOf(maxInFlight, inFlight)
            entered += path
            if (entered.size >= expectedConcurrent && !enoughInFlight.isCompleted) {
                enoughInFlight.complete(Unit)
            }
            enoughInFlight.await()
            delays[path]?.let { delay(it) }
            inFlight -= 1
            return AgentRuntimeFixtures.successResult(
                summary = "read $path",
                dataJson = """{"content":"$path"}"""
            )
        }

        private fun pathOf(command: FrozenToolCommand): String {
            val parsed = StrictAgentJsonCodec().parse(command.normalizedArgumentsJson)
            val root = (parsed as? AgentJsonParseResult.Parsed)?.value as? AgentJsonValue.Obj
            return (root?.fields?.toMap()?.get("path") as? AgentJsonValue.Str)?.value.orEmpty()
        }
    }

    @Test
    fun threeReadOnlyCallsRunConcurrentlyAndSettleInOrdinalOrder() = runTest {
        val (journal, task, run) = setup()
        val executor = CountingReadExecutor(descriptor, expectedConcurrent = 3)
        val hook = RecordingFrozenCommandHook(ledger = journal)
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(visibleText = "", turn = batchTurn("call-1", "call-2", "call-3")),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("batch done"))
            )
        )
        val result = coordinator(journal, port, executor, hook)
            .execute(nativeRun(run), task, null) { }

        assertTrue("expected a completed batch run, got $result", result is AgentLoopResult.Completed)
        result as AgentLoopResult.Completed
        assertEquals("all three read-only calls are in flight together", 3, executor.maxInFlight)
        assertEquals(listOf("inputs/1", "inputs/2", "inputs/3"), executor.entered)
        val entries = result.transcript.entries
        assertEquals("three pairs plus the final assistant record", 7, entries.size)
        assertEquals("call-1", (entries[0] as AgentTranscriptEntry.AssistantEnvelope).providerCallId)
        assertEquals("call-1", (entries[1] as AgentTranscriptEntry.ToolResult).providerCallId)
        assertEquals("call-2", (entries[2] as AgentTranscriptEntry.AssistantEnvelope).providerCallId)
        assertEquals("call-2", (entries[3] as AgentTranscriptEntry.ToolResult).providerCallId)
        assertEquals("call-3", (entries[4] as AgentTranscriptEntry.AssistantEnvelope).providerCallId)
        assertEquals("call-3", (entries[5] as AgentTranscriptEntry.ToolResult).providerCallId)
        assertEquals(
            "the result observations follow the input ordinal",
            listOf("read inputs/1", "read inputs/2", "read inputs/3"),
            entries.filterIsInstance<AgentTranscriptEntry.ToolResult>()
                .map { payloadField(it, "summary") }
        )
        assertEquals(listOf("call-1", "call-2", "call-3"), hook.requests.map { it.providerCallId })
        val resultEvents = journal.allEvents(run.runId)
            .filter { it.type == AgentEventTypes.TOOL_RESULT }
        assertEquals(3, resultEvents.size)
        assertEquals(
            "tool.result events are committed in ordinal order, not completion order",
            executor.commands.map { it.invocationId },
            resultEvents.map { it.causationId }
        )
    }

    @Test
    fun aFourthCallWaitsForAFreeParallelSlot() = runTest {
        val (journal, task, run) = setup()
        val executor = CountingReadExecutor(descriptor, expectedConcurrent = 3)
        val hook = RecordingFrozenCommandHook(ledger = journal)
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(
                    visibleText = "",
                    turn = batchTurn("call-1", "call-2", "call-3", "call-4")
                ),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("batch done"))
            )
        )
        val result = coordinator(journal, port, executor, hook)
            .execute(nativeRun(run), task, null) { }

        assertTrue("expected a completed batch run, got $result", result is AgentLoopResult.Completed)
        assertEquals("the fourth call still executes after a slot frees up", 4, executor.executeCount)
        assertEquals("never more than three executor calls overlap", 3, executor.maxInFlight)
        assertEquals(
            listOf("inputs/1", "inputs/2", "inputs/3", "inputs/4"),
            executor.entered
        )
        assertEquals(
            "four pairs plus the final assistant record",
            9,
            (result as AgentLoopResult.Completed).transcript.entries.size
        )
    }

    @Test
    fun resultsSettleInOrdinalOrderEvenWhenExecutorsFinishInReverse() = runTest {
        val (journal, task, run) = setup()
        val executor = CountingReadExecutor(
            descriptor,
            expectedConcurrent = 3,
            delays = mapOf("inputs/1" to 40L, "inputs/2" to 20L, "inputs/3" to 0L)
        )
        val hook = RecordingFrozenCommandHook(ledger = journal)
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(visibleText = "", turn = batchTurn("call-1", "call-2", "call-3")),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("reverse batch done"))
            )
        )
        val result = coordinator(journal, port, executor, hook)
            .execute(nativeRun(run), task, null) { }

        assertTrue("expected a completed batch run, got $result", result is AgentLoopResult.Completed)
        assertEquals(3, executor.maxInFlight)
        val entries = (result as AgentLoopResult.Completed).transcript.entries
        assertEquals("three pairs plus the final assistant record", 7, entries.size)
        assertEquals("call-1", (entries[0] as AgentTranscriptEntry.AssistantEnvelope).providerCallId)
        assertEquals("call-1", (entries[1] as AgentTranscriptEntry.ToolResult).providerCallId)
        assertEquals("call-2", (entries[2] as AgentTranscriptEntry.AssistantEnvelope).providerCallId)
        assertEquals("call-2", (entries[3] as AgentTranscriptEntry.ToolResult).providerCallId)
        assertEquals("call-3", (entries[4] as AgentTranscriptEntry.AssistantEnvelope).providerCallId)
        assertEquals("call-3", (entries[5] as AgentTranscriptEntry.ToolResult).providerCallId)
        assertEquals(
            listOf("read inputs/1", "read inputs/2", "read inputs/3"),
            entries.filterIsInstance<AgentTranscriptEntry.ToolResult>()
                .map { payloadField(it, "summary") }
        )
    }

    @Test
    fun aBudgetPrecheckRefusesTheBatchBeforeAnyFreezeOrExecution() = runTest {
        val budget = AgentBudget().copy(toolInvocations = 2)
        val (journal, task, run) = setup(budget = budget)
        val executor = CountingReadExecutor(descriptor, expectedConcurrent = 1)
        val hook = RecordingFrozenCommandHook(ledger = journal)
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(visibleText = "", turn = batchTurn("call-1", "call-2", "call-3"))
            )
        )
        val result = coordinator(journal, port, executor, hook)
            .execute(nativeRun(run), task, null) { }

        assertTrue(result is AgentLoopResult.Paused)
        assertEquals(AgentPauseReason.BUDGET_LIMIT, (result as AgentLoopResult.Paused).reason)
        assertEquals(0, executor.executeCount)
        assertTrue("no call may be frozen when the batch does not fit", hook.requests.isEmpty())
        assertTrue(
            "no tool.proposed event may be committed",
            journal.allEvents(run.runId).none { it.type == AgentEventTypes.TOOL_PROPOSED }
        )
        assertTrue(result.transcript.entries.isEmpty())
    }

    @Test
    fun anApprovalPauseNeverPartiallyExecutesTheFrozenBatch() = runTest {
        val (journal, task, run) = setup()
        val executor = CountingReadExecutor(descriptor, expectedConcurrent = 1)
        val hook = RecordingFrozenCommandHook(ledger = journal) { request ->
            val command = AgentRuntimeFixtures.frozenCommand(request)
            if (request.providerCallId == "call-3") {
                AgentFrozenCommandDecision.WaitingApproval(
                    command,
                    AgentRuntimeFixtures.pendingApprovalFor(command)
                )
            } else {
                AgentFrozenCommandDecision.ReadyToExecute(command)
            }
        }
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(visibleText = "", turn = batchTurn("call-1", "call-2", "call-3"))
            )
        )
        val result = coordinator(journal, port, executor, hook)
            .execute(nativeRun(run), task, null) { }

        assertTrue("expected WaitingApproval, got $result", result is AgentLoopResult.WaitingApproval)
        result as AgentLoopResult.WaitingApproval
        assertEquals("no executor may run for a partially frozen batch", 0, executor.executeCount)
        assertEquals(3, hook.requests.size)
        assertEquals("call-3", hook.requests[2].providerCallId)
        assertEquals(
            "the waiting call keeps its pair for the resume path",
            "call-3",
            (result.transcript.entries.last() as AgentTranscriptEntry.AssistantEnvelope).providerCallId
        )
        assertEquals(5, result.transcript.entries.size)
        assertEquals("call-1", (result.transcript.entries[0] as AgentTranscriptEntry.AssistantEnvelope).providerCallId)
        val firstObservation = result.transcript.entries[1] as AgentTranscriptEntry.ToolResult
        assertEquals(AgentToolResultStatus.NOT_EXECUTED.name, payloadField(firstObservation, "status"))
        assertEquals("call-2", (result.transcript.entries[2] as AgentTranscriptEntry.AssistantEnvelope).providerCallId)
        assertTrue(
            "the call waiting for approval is not settled",
            journal.readInvocation(hook.requests[2].invocationId)?.status ==
                ToolInvocationStatus.WAITING_APPROVAL
        )
        assertTrue(
            "no call of the aborted batch may be started",
            journal.allEvents(run.runId).none { it.type == AgentEventTypes.TOOL_STARTED }
        )
    }

    private fun batchTurn(vararg callIds: String): AgentModelTurn = AgentModelTurn(
        protocol = AgentTurnProtocol.NATIVE,
        finish = AgentTurnFinish.TOOL_CALL,
        calls = callIds.mapIndexed { index, callId ->
            AgentTurnToolCall(
                callId = callId,
                name = AgentV1ToolSpecs.WORKSPACE_READ_TEXT,
                argumentsJson = """{"path":"inputs/${index + 1}"}"""
            )
        },
        terminalProof = "responses:completed"
    )

    private fun nativeRun(run: AgentRun): AgentRun = run.copy(
        modelBinding = run.modelBinding.copy(protocolMode = AgentProtocolMode.NATIVE)
    )

    private suspend fun setup(
        budget: AgentBudget = AgentBudget()
    ): Triple<InMemoryAgentJournal, AgentTask, AgentRun> {
        val journal = InMemoryAgentJournal()
        val task = AgentRuntimeFixtures.task()
        journal.commit(CreateTaskTransition("setup-task", task))
        val run = AgentRuntimeFixtures.run(state = AgentRunState.RUNNING, budget = budget)
            .copy(modelBinding = AgentRuntimeFixtures.binding())
        journal.commit(
            CreateRunTransition(
                "setup-run",
                run,
                taskRevision = task.revision,
                taskDisplay = AgentDisplayStatus.RUNNING
            )
        )
        return Triple(journal, task, run)
    }

    private fun coordinator(
        journal: InMemoryAgentJournal,
        modelPort: com.muyuchat.core.agent.contract.AgentModelPort,
        executor: AgentToolExecutor,
        hook: RecordingFrozenCommandHook,
        toolLedger: com.muyuchat.core.agent.runtime.journal.AgentToolInvocationLedger = journal
    ): AgentPromptedLoopCoordinator = AgentPromptedLoopCoordinator(
        decoderFactory = { keys -> PromptedV1TurnDecoder(PromptedV1Decoder({ keys }, codec)) },
        serializer = AgentPromptedTranscriptSerializer(codec),
        systemPromptBuilder = AgentPromptedSystemPromptBuilder(),
        catalogProvider = AgentRunToolCatalogProvider { _, _ ->
            FakeToolCatalog(mapOf(executor.descriptor.toolKey to executor))
        },
        schemaValidator = validator,
        journal = journal,
        toolLedger = toolLedger,
        modelPort = modelPort,
        budgetPolicy = AgentBudgetPolicy(clock),
        contextAssembler = AgentContextAssembler(
            AgentPromptedTranscriptSerializer(codec),
            nativeSerializer = AgentNativeTranscriptSerializer(codec),
            codec = codec
        ),
        commandHook = hook,
        artifactVerifier = FakeArtifactVerifier(),
        ids = ids,
        clock = clock,
        attemptRegistry = AgentActiveAttemptRegistry()
    )

    @Test
    fun cancellingDuringAdmissionSettlesStartedAndUnstartedCalls() = runTest {
        val (journal, task, run) = setup()
        val executor = CountingReadExecutor(descriptor, expectedConcurrent = 1)
        val ledger = CancelOnSecondRunningLedger(journal)
        val hook = RecordingFrozenCommandHook(ledger = ledger)
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(visibleText = "", turn = batchTurn("call-1", "call-2"))
            )
        )

        var cancelled = false
        try {
            coordinator(journal, port, executor, hook, toolLedger = ledger)
                .execute(nativeRun(run), task, null) { }
        } catch (_: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }

        assertTrue("cancellation must propagate", cancelled)
        assertEquals("no executor may run after an admission cancel", 0, executor.executeCount)
        val invocations = journal.readRun(run.runId)!!.steps
            .mapNotNull { it.invocationId }
            .map { journal.readInvocation(it)!! }
        assertEquals(2, invocations.size)
        assertEquals(
            com.muyuchat.core.agent.contract.ToolInvocationStatus.CANCELLED,
            invocations[0].status
        )
        assertEquals(
            "a frozen-but-unstarted call must not stay VALIDATED",
            com.muyuchat.core.agent.contract.ToolInvocationStatus.NOT_EXECUTED,
            invocations[1].status
        )
    }

    private class CancelOnSecondRunningLedger(
        private val delegate: InMemoryAgentJournal
    ) : com.muyuchat.core.agent.runtime.journal.AgentToolInvocationLedger by delegate {
        private var running = 0

        override suspend fun markRunning(
            invocationId: String,
            atMillis: Long
        ): com.muyuchat.core.agent.runtime.journal.AgentToolInvocationUpdate {
            running += 1
            if (running == 2) throw kotlinx.coroutines.CancellationException("cancelled at admission")
            return delegate.markRunning(invocationId, atMillis)
        }
    }

    /** Reads a field of the tool observation stored inside the transcript v2 output record. */
    private fun payloadField(entry: AgentTranscriptEntry.ToolResult, field: String): String? {
        val record = entry.result as? AgentJsonValue.Obj ?: return null
        val payload = (record.fields.toMap()["result"] as? AgentJsonValue.Obj) ?: return null
        return (payload.fields.toMap()[field] as? AgentJsonValue.Str)?.value
    }
}
