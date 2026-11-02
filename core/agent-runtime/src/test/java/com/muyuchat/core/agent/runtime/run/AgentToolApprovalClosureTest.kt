package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentModelResult
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import com.muyuchat.core.agent.runtime.journal.AgentFrozenInvocation
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationLedger
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationRecord
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationUpdate
import com.muyuchat.core.agent.runtime.journal.AgentToolProposal
import com.muyuchat.core.agent.runtime.journal.InMemoryAgentJournal
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
import com.muyuchat.core.agent.runtime.testing.RecordingToolExecutor
import com.muyuchat.core.agent.runtime.validation.AgentV1ToolSpecs
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import com.muyuchat.core.agent.runtime.context.AgentContextAssembler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-A closure tests: PROPOSED persistence -> freeze -> approval -> one-shot authorization ->
 * authorized-only execution -> paired terminal result.
 *
 * These are memory-level JVM tests: no Android, Room, engine or app import. The real
 * `PENDING -> CONSUMED` CAS and the Room ledger are covered by the app-level closure test.
 */
class AgentToolApprovalClosureTest {

    private val validator = SupportedToolSchemaValidator()
    private val codec = StrictAgentJsonCodec()
    private val clock = ManualAgentMonotonicClock()
    private val ids = SequentialAgentIdGenerator("closure")
    private val descriptor = AgentV1ToolSpecs.descriptor(AgentV1ToolSpecs.WORKSPACE_READ_TEXT, validator)

    @Test
    fun toolCallPersistsProposalRecordsRunningAndOneTerminalResult() = runTest {
        val (journal, task, run) = setup()
        val executor = RecordingToolExecutor(descriptor)
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(AgentTestFixtures.toolEnvelope()),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("ok"))
            )
        )
        val result = coordinator(journal, port, FakeToolCatalog(mapOf(descriptor.toolKey to executor)))
            .execute(run, task, null, AgentModelProgressSink { })

        assertTrue(result is AgentLoopResult.Completed)
        assertEquals(1, executor.executeCount)
        val command = executor.commands.single()
        val invocation = journal.readInvocation(command.invocationId)
        assertNotNull("the invocation row must exist", invocation)
        assertEquals(ToolInvocationStatus.SUCCEEDED, invocation!!.status)
        assertEquals(command.argumentsHash, invocation.argumentsHash)
        assertEquals(command.resourceFingerprint, invocation.resourceFingerprint)
        val eventTypes = journal.allEvents(run.runId).map { it.type }
        assertTrue(eventTypes.contains(AgentEventTypes.TOOL_PROPOSED))
        assertTrue(eventTypes.contains(AgentEventTypes.TOOL_STARTED))
        assertTrue(eventTypes.contains(AgentEventTypes.TOOL_RESULT))
        assertTrue(
            journal.allEvents(run.runId)
                .filter { it.type == AgentEventTypes.TOOL_RESULT }
                .all { it.causationId == command.invocationId }
        )
    }

    @Test
    fun authorizedResumeExecutesExactlyOnceAndReplayIsRefused() = runTest {
        val (journal, task, run) = setup()
        val command = seedFrozenInvocation(journal, task, run, markWaiting = false)
        val executor = RecordingToolExecutor(descriptor)
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("first")),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("second"))
            )
        )
        val loop = coordinator(journal, port, FakeToolCatalog(mapOf(descriptor.toolKey to executor)))
        val resume = authorizedResume(command)

        val first = loop.execute(run, task, resume, AgentModelProgressSink { })
        assertTrue(first is AgentLoopResult.Completed)
        assertEquals(1, executor.executeCount)
        assertEquals(
            ToolInvocationStatus.SUCCEEDED,
            journal.readInvocation(command.invocationId)!!.status
        )

        val current = journal.readRun(run.runId)!!.run
        val second = loop.execute(current, task, resume, AgentModelProgressSink { })
        assertTrue(second is AgentLoopResult.Completed)
        assertEquals("a replayed authorization must not execute again", 1, executor.executeCount)
        val toolResult = second.transcript.entries
            .filterIsInstance<AgentTranscriptEntry.ToolResult>()
            .single()
        // The invocation really succeeded, but this replay has no stored result to hand back. The
        // execution gate must not claim NOT_EXECUTED for an already-dispatched row.
        assertEquals(AgentToolResultStatus.UNKNOWN.name, stringField(toolResult.result, "status"))
        assertEquals(
            ToolInvocationStatus.SUCCEEDED,
            journal.readInvocation(command.invocationId)!!.status
        )
    }

    @Test
    fun changedArgumentsAfterFreezeNeverExecuteAndSettleNotExecuted() = runTest {
        val (journal, task, run) = setup()
        val command = seedFrozenInvocation(journal, task, run, markWaiting = false)
        val executor = RecordingToolExecutor(descriptor)
        val port = QueueModelPort(
            mutableListOf(AgentModelResult.Success(AgentTestFixtures.finalEnvelope("after toctou")))
        )
        val tampered = command.copy(argumentsHash = "e".repeat(64))

        val result = coordinator(journal, port, FakeToolCatalog(mapOf(descriptor.toolKey to executor)))
            .execute(run, task, authorizedResume(tampered), AgentModelProgressSink { })

        assertTrue(result is AgentLoopResult.Completed)
        assertEquals(0, executor.executeCount)
        assertEquals(
            ToolInvocationStatus.NOT_EXECUTED,
            journal.readInvocation(command.invocationId)!!.status
        )
        val toolResult = result.transcript.entries
            .filterIsInstance<AgentTranscriptEntry.ToolResult>()
            .single()
        assertEquals(AgentToolResultStatus.NOT_EXECUTED.name, stringField(toolResult.result, "status"))
    }

    @Test
    fun deniedResumeNeverExecutesAndPairsDeniedResult() = runTest {
        val (journal, task, run) = setup()
        val command = seedFrozenInvocation(journal, task, run, markWaiting = true)
        val executor = RecordingToolExecutor(descriptor)
        val port = QueueModelPort(
            mutableListOf(AgentModelResult.Success(AgentTestFixtures.finalEnvelope("denied")))
        )
        val resume = AgentLoopResume(
            transcript = AgentLoopTranscript(
                listOf(AgentTranscriptEntry.AssistantEnvelope(AgentTestFixtures.toolEnvelope())),
                1,
                0
            ),
            toolDecision = AgentResumeToolDecision.NotExecuted(
                invocationId = command.invocationId,
                callHash = CALL_HASH,
                toolKey = command.toolKey,
                status = AgentToolResultStatus.DENIED,
                safeMessage = "The user denied this tool invocation.",
                errorCode = com.muyuchat.core.agent.contract.AgentErrorCodes.PERMISSION_DENIED
            )
        )

        val result = coordinator(journal, port, FakeToolCatalog(mapOf(descriptor.toolKey to executor)))
            .execute(run, task, resume, AgentModelProgressSink { })

        assertTrue(result is AgentLoopResult.Completed)
        assertEquals(0, executor.executeCount)
        val toolResult = result.transcript.entries
            .filterIsInstance<AgentTranscriptEntry.ToolResult>()
            .single()
        assertEquals(AgentToolResultStatus.DENIED.name, stringField(toolResult.result, "status"))
        assertTrue(
            journal.allEvents(run.runId).any { it.type == AgentEventTypes.TOOL_APPROVAL_RESOLVED }
        )
    }

    @Test
    fun cancellationDuringExecutionStaysCancelledAndIsTerminal() = runTest {
        val (journal, task, run) = setup()
        val command = seedFrozenInvocation(journal, task, run, markWaiting = false)
        val executor = CancellingToolExecutor(descriptor)
        val loop = coordinator(journal, QueueModelPort(), FakeToolCatalog(mapOf(descriptor.toolKey to executor)))

        var caught: CancellationException? = null
        try {
            loop.execute(run, task, authorizedResume(command), AgentModelProgressSink { })
        } catch (cancelled: CancellationException) {
            caught = cancelled
        }
        assertNotNull("cancellation must propagate", caught)
        assertEquals(1, executor.executeCount)
        assertEquals(
            ToolInvocationStatus.CANCELLED,
            journal.readInvocation(command.invocationId)!!.status
        )
    }

    @Test
    fun needsApprovalRecordsWaitingInvocationAndRequestedEventBeforeWaiting() = runTest {
        val (journal, task, run) = setup()
        val executor = RecordingToolExecutor(descriptor)
        val hook = RecordingFrozenCommandHook(ledger = journal) { request ->
            val command = AgentRuntimeFixtures.frozenCommand(request)
            AgentFrozenCommandDecision.WaitingApproval(
                command,
                AgentRuntimeFixtures.pendingApprovalFor(command)
            )
        }
        val port = QueueModelPort(
            mutableListOf(AgentModelResult.Success(AgentTestFixtures.toolEnvelope()))
        )

        val result = coordinator(
            journal,
            port,
            FakeToolCatalog(mapOf(descriptor.toolKey to executor)),
            hook = hook
        ).execute(run, task, null, AgentModelProgressSink { })

        assertTrue(result is AgentLoopResult.WaitingApproval)
        result as AgentLoopResult.WaitingApproval
        assertNotNull(result.approval)
        assertEquals(0, executor.executeCount)
        val invocation = journal.readInvocation(result.invocationId)
        assertNotNull(invocation)
        assertEquals(ToolInvocationStatus.WAITING_APPROVAL, invocation!!.status)
        val requested = journal.allEvents(run.runId)
            .filter { it.type == AgentEventTypes.TOOL_APPROVAL_REQUESTED }
        assertEquals(1, requested.size)
        assertEquals(result.invocationId, requested.single().causationId)
        assertTrue(requested.single().summary!!.contains("\"tool\""))
        assertTrue(requested.single().summary!!.contains("\"source\""))
    }

    @Test
    fun approvalNotBoundToFrozenCommandNeverWaitsAndRecordsTerminal() = runTest {
        val (journal, task, run) = setup()
        val executor = RecordingToolExecutor(descriptor)
        val hook = RecordingFrozenCommandHook(ledger = journal) { request ->
            val command = AgentRuntimeFixtures.frozenCommand(request)
            AgentFrozenCommandDecision.WaitingApproval(
                command,
                AgentRuntimeFixtures.pendingApprovalFor(command)
                    .copy(argumentsHash = "f".repeat(64))
            )
        }
        val port = QueueModelPort(
            mutableListOf(
                AgentModelResult.Success(AgentTestFixtures.toolEnvelope()),
                AgentModelResult.Success(AgentTestFixtures.finalEnvelope("after unbound"))
            )
        )

        val result = coordinator(
            journal,
            port,
            FakeToolCatalog(mapOf(descriptor.toolKey to executor)),
            hook = hook
        ).execute(run, task, null, AgentModelProgressSink { })

        assertTrue(result is AgentLoopResult.Completed)
        assertEquals(0, executor.executeCount)
        val toolResult = result.transcript.entries
            .filterIsInstance<AgentTranscriptEntry.ToolResult>()
            .single()
        assertEquals(AgentToolResultStatus.NOT_EXECUTED.name, stringField(toolResult.result, "status"))
        assertEquals(
            ToolInvocationStatus.NOT_EXECUTED,
            journal.readInvocation(toolResult.invocationId)!!.status
        )
    }

    @Test
    fun proposalLedgerIsIdempotentAndRejectsForeignOrMissingRun() = runTest {
        val (journal, task, run) = setup()
        val proposal = proposalFor(run, task, ids.nextId("invocation"), stepIndex = 1)
        assertTrue(journal.recordProposed(proposal) is AgentToolInvocationRecord.Recorded)
        val replay = journal.recordProposed(
            proposal.copy(invocationId = ids.nextId("invocation"))
        )
        assertTrue(replay is AgentToolInvocationRecord.Recorded)
        assertEquals(
            proposal.invocationId,
            (replay as AgentToolInvocationRecord.Recorded).invocation.invocationId
        )

        val foreign = journal.recordProposed(proposal.copy(runId = "other-run"))
        assertTrue(foreign is AgentToolInvocationRecord.Rejected)
        assertEquals(
            com.muyuchat.core.agent.contract.AgentErrorCodes.COMMAND_NOT_FOUND,
            (foreign as AgentToolInvocationRecord.Rejected).errorCode
        )
        assertEquals(1, journal.readRun(run.runId)!!.steps.size)
    }

    // ---------------------------------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------------------------------

    private suspend fun setup(
        budget: AgentBudget = AgentBudget()
    ): Triple<InMemoryAgentJournal, AgentTask, AgentRun> {
        val journal = InMemoryAgentJournal()
        val task = AgentRuntimeFixtures.task()
        journal.commit(CreateTaskTransition("setup-task", task))
        val run = AgentRuntimeFixtures.run(state = AgentRunState.RUNNING, budget = budget)
        journal.commit(
            CreateRunTransition(
                "setup-run",
                run,
                taskRevision = task.revision,
                taskDisplay = AgentDisplayStatus.RUNNING
            )
        )
        return Triple(journal, task, journal.readRun(run.runId)!!.run)
    }

    private fun coordinator(
        journal: InMemoryAgentJournal,
        modelPort: com.muyuchat.core.agent.contract.AgentModelPort,
        catalog: FakeToolCatalog,
        hook: RecordingFrozenCommandHook = RecordingFrozenCommandHook(ledger = journal)
    ): AgentPromptedLoopCoordinator = AgentPromptedLoopCoordinator(
        decoderFactory = { keys -> PromptedV1TurnDecoder(PromptedV1Decoder({ keys }, codec)) },
        serializer = AgentPromptedTranscriptSerializer(codec),
        systemPromptBuilder = AgentPromptedSystemPromptBuilder(),
        catalogProvider = AgentRunToolCatalogProvider { _, _ -> catalog },
        schemaValidator = validator,
        journal = journal,
        toolLedger = journal,
        modelPort = modelPort,
        budgetPolicy = AgentBudgetPolicy(clock),
        contextAssembler = AgentContextAssembler(AgentPromptedTranscriptSerializer(codec), codec = codec),
        commandHook = hook,
        artifactVerifier = FakeArtifactVerifier(),
        ids = ids,
        clock = clock,
        attemptRegistry = AgentActiveAttemptRegistry()
    )

    private suspend fun seedFrozenInvocation(
        journal: InMemoryAgentJournal,
        task: AgentTask,
        run: AgentRun,
        markWaiting: Boolean
    ): FrozenToolCommand {
        val command = FrozenToolCommand(
            invocationId = ids.nextId("invocation"),
            runId = run.runId,
            stepId = ids.nextId("step"),
            callOrdinal = 0,
            toolKey = descriptor.toolKey,
            toolVersion = descriptor.version,
            schemaHash = descriptor.schemaHash,
            normalizedArgumentsJson = ARGUMENTS_JSON,
            argumentsHash = "a".repeat(64),
            resourceFingerprint = "fixture-resource",
            runEpoch = run.epoch,
            policyVersion = run.policy.policyVersion,
            expiresAtMillis = 9_000_000L
        )
        val proposal = AgentToolProposal(
            invocationId = command.invocationId,
            runId = run.runId,
            taskId = task.taskId,
            stepId = command.stepId,
            stepIndex = 1,
            callOrdinal = command.callOrdinal,
            toolKey = descriptor.toolKey,
            toolVersion = descriptor.version,
            schemaHash = descriptor.schemaHash,
            argumentsHash = "b".repeat(64),
            proposedAtMillis = 1_000L
        )
        assertTrue(
            journal.recordProposed(proposal) is AgentToolInvocationRecord.Recorded
        )
        assertTrue(
            journal.recordFrozen(
                AgentFrozenInvocation(
                    command = command,
                    descriptor = descriptor,
                    scope = AgentRuntimeFixtures.scopeFor(task),
                    recordedAtMillis = 1_000L
                )
            ) is AgentToolInvocationRecord.Recorded
        )
        if (markWaiting) {
            assertTrue(
                journal.markWaitingApproval(command.invocationId, 1_000L) is
                    AgentToolInvocationUpdate.Updated
            )
        }
        return command
    }

    private fun proposalFor(
        run: AgentRun,
        task: AgentTask,
        invocationId: String,
        stepIndex: Int
    ): AgentToolProposal = AgentToolProposal(
        invocationId = invocationId,
        runId = run.runId,
        taskId = task.taskId,
        stepId = ids.nextId("step"),
        stepIndex = stepIndex,
        callOrdinal = 0,
        toolKey = descriptor.toolKey,
        toolVersion = descriptor.version,
        schemaHash = descriptor.schemaHash,
        argumentsHash = "b".repeat(64),
        proposedAtMillis = 1_000L
    )

    private fun authorizedResume(command: FrozenToolCommand): AgentLoopResume = AgentLoopResume(
        transcript = AgentLoopTranscript(
            listOf(AgentTranscriptEntry.AssistantEnvelope(AgentTestFixtures.toolEnvelope())),
            1,
            0
        ),
        toolDecision = AgentResumeToolDecision.Authorized(
            authorization = AuthorizedToolCommand(
                command = command,
                approvalId = "approval-1",
                basis = AgentToolAuthorizationBasis.APPROVED_ONCE
            ),
            callHash = CALL_HASH
        )
    )

    private fun stringField(value: com.muyuchat.core.agent.runtime.AgentJsonValue, field: String): String? =
        ((value as? com.muyuchat.core.agent.runtime.AgentJsonValue.Obj)
            ?.fields
            ?.toMap()
            ?.get(field) as? com.muyuchat.core.agent.runtime.AgentJsonValue.Str)?.value

    private class CancellingToolExecutor(
        override val descriptor: AgentToolDescriptor
    ) : AgentToolExecutor {
        var executeCount: Int = 0

        override suspend fun execute(
            command: FrozenToolCommand,
            context: AgentToolContext
        ): AgentToolResult {
            executeCount += 1
            throw CancellationException("cancelled by test")
        }
    }

    private companion object {
        val CALL_HASH = "c".repeat(64)
        const val ARGUMENTS_JSON = """{"limit":4096,"offset":0,"path":"inputs/brief.txt"}"""
    }
}
