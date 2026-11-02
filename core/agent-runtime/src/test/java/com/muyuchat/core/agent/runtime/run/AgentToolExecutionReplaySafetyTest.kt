package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.AgentToolResultStatus.UNKNOWN
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.journal.AgentFrozenInvocation
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationLedger
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationRecord
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationUpdate
import com.muyuchat.core.agent.runtime.journal.AgentToolProposal
import com.muyuchat.core.agent.runtime.journal.AgentToolScope
import com.muyuchat.core.agent.runtime.journal.InMemoryAgentJournal
import com.muyuchat.core.agent.runtime.testing.AgentRuntimeFixtures
import com.muyuchat.core.agent.runtime.testing.FakeToolCatalog
import com.muyuchat.core.agent.runtime.validation.AgentV1ToolSpecs
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay safety at the execution gate: a lost RUNNING CAS must never claim NOT_EXECUTED and must
 * never dispatch the executor again. The persisted row stays the authoritative evidence.
 */
class AgentToolExecutionReplaySafetyTest {

    private val validator = SupportedToolSchemaValidator()
    private val descriptor = AgentV1ToolSpecs.descriptor(AgentV1ToolSpecs.WORKSPACE_READ_TEXT, validator)
    private val executor = RecordingExecutor(descriptor)
    private val catalog = FakeToolCatalog(mapOf(descriptor.toolKey to executor))

    @Test
    fun aLeftoverRunningInvocationBecomesUnknownAndIsNotReExecuted() = runTest {
        val (journal, task, run, command) = frozenRun()
        assertTrue(journal.markRunning(command.invocationId, 1_200L) is AgentToolInvocationUpdate.Updated)
        val emitted = mutableListOf<String>()

        val outcome = pipeline(journal).execute(
            authorization = authorization(command),
            run = run,
            task = task,
            stepIndex = 0,
            emit = { current, type, _, _ -> emitted += type; current },
            readRun = { null }
        )

        assertEquals(UNKNOWN, outcome.result.status)
        assertEquals("TOOL_RESULT_UNKNOWN", outcome.result.errorCode)
        assertTrue("an uncertain side effect must stay visible", outcome.result.possibleSideEffects)
        assertEquals("the RUNNING row is the evidence and must not be overwritten", ToolInvocationStatus.RUNNING, journal.readInvocation(command.invocationId)!!.status)
        assertEquals(0, executor.calls)
        assertTrue("no fake tool.result event may be written", emitted.isEmpty())
    }

    @Test
    fun aTerminalReplayNeverTurnsIntoNotExecutedOrASecondCall() = runTest {
        val (journal, task, run, command) = frozenRun()
        journal.markRunning(command.invocationId, 1_200L)
        journal.markTerminal(
            invocationId = command.invocationId,
            status = ToolInvocationStatus.SUCCEEDED,
            possibleSideEffects = false,
            resultRef = "result-1",
            atMillis = 1_300L
        )

        val outcome = pipeline(journal).execute(
            authorization = authorization(command),
            run = run,
            task = task,
            stepIndex = 0,
            emit = { current, _, _, _ -> current },
            readRun = { null }
        )

        assertEquals(UNKNOWN, outcome.result.status)
        assertTrue(outcome.result.possibleSideEffects)
        assertEquals(ToolInvocationStatus.SUCCEEDED, journal.readInvocation(command.invocationId)!!.status)
        assertEquals(0, executor.calls)
    }

    @Test
    fun aRefusedResumeDoesNotEraseAnApprovedDispatchIntent() = runTest {
        val (journal, task, run, command) = frozenRun()
        journal.markWaitingApproval(command.invocationId, 1_150L)
        journal.markApproved(command.invocationId, 1_180L)
        val stale = command.copy(expiresAtMillis = 1_000L)

        val outcome = pipeline(journal).execute(
            authorization = authorization(stale),
            run = run,
            task = task,
            stepIndex = 0,
            emit = { current, _, _, _ -> current },
            readRun = { null }
        )

        assertEquals(AgentToolResultStatus.NOT_EXECUTED, outcome.result.status)
        assertEquals(
            "an expired command must not erase the durable dispatch intent",
            ToolInvocationStatus.APPROVED,
            journal.readInvocation(command.invocationId)!!.status
        )
        assertEquals(0, executor.calls)
    }

    @Test
    fun aRefusedResumeDoesNotCancelAPendingApproval() = runTest {
        val (journal, task, run, command) = frozenRun()
        journal.markWaitingApproval(command.invocationId, 1_150L)
        val foreignEpoch = command.copy(runEpoch = command.runEpoch + 1)

        val outcome = pipeline(journal).execute(
            authorization = authorization(foreignEpoch),
            run = run,
            task = task,
            stepIndex = 0,
            emit = { current, _, _, _ -> current },
            readRun = { null }
        )

        assertEquals(AgentToolResultStatus.NOT_EXECUTED, outcome.result.status)
        assertEquals(
            ToolInvocationStatus.WAITING_APPROVAL,
            journal.readInvocation(command.invocationId)!!.status
        )
        assertEquals(0, executor.calls)
    }

    @Test
    fun anUnapprovedWaitingInvocationCannotEnterTheExecutionGate() = runTest {
        val (journal, task, run, command) = frozenRun()
        assertTrue(
            journal.markWaitingApproval(command.invocationId, 1_150L) is
                AgentToolInvocationUpdate.Updated
        )

        val outcome = pipeline(journal).execute(
            authorization = authorization(command),
            run = run,
            task = task,
            stepIndex = 0,
            emit = { current, _, _, _ -> current },
            readRun = { null }
        )

        assertEquals(AgentToolResultStatus.NOT_EXECUTED, outcome.result.status)
        assertEquals(
            "a refused start must not destroy the pending approval",
            ToolInvocationStatus.WAITING_APPROVAL,
            journal.readInvocation(command.invocationId)!!.status
        )
        assertEquals(0, executor.calls)
    }

    @Test
    fun aCancellationAfterTheGateKeepsTheUncertainEffectVisible() = runTest {
        val (journal, task, run, command) = frozenRun()
        val recording = RecordingLedger(journal)
        val cancelling = SuspendingExecutor(descriptor)
        val cancellationPipeline = AgentToolExecutionPipeline(
            toolLedger = recording,
            clock = AgentMonotonicClock { 1_500L },
            catalog = FakeToolCatalog(mapOf(descriptor.toolKey to cancelling))
        )

        val job = launch {
            cancellationPipeline.execute(
                authorization = authorization(command),
                run = run,
                task = task,
                stepIndex = 0,
                emit = { current, _, _, _ -> current },
                readRun = { null }
            )
        }
        cancelling.entered.await()
        job.cancelAndJoin()

        assertEquals(1, cancelling.calls)
        assertEquals(
            ToolInvocationStatus.CANCELLED,
            journal.readInvocation(command.invocationId)!!.status
        )
        assertEquals(
            "a cancellation after the RUNNING gate may have produced effects",
            true,
            recording.cancelledSideEffects
        )
    }

    @Test
    fun aPreExecutionRefusalStillSettlesNotExecuted() = runTest {
        val (journal, task, run, command) = frozenRun()
        val expired = command.copy(expiresAtMillis = 1_000L)
        val emitted = mutableListOf<String>()

        val outcome = pipeline(journal).execute(
            authorization = authorization(expired),
            run = run,
            task = task,
            stepIndex = 0,
            emit = { current, type, _, _ -> emitted += type; current },
            readRun = { null }
        )

        assertEquals(AgentToolResultStatus.NOT_EXECUTED, outcome.result.status)
        assertEquals(ToolInvocationStatus.NOT_EXECUTED, journal.readInvocation(command.invocationId)!!.status)
        assertEquals(0, executor.calls)
        assertEquals(listOf(AgentEventTypes.TOOL_RESULT), emitted)
    }

    private fun pipeline(journal: InMemoryAgentJournal) = AgentToolExecutionPipeline(
        toolLedger = journal,
        clock = AgentMonotonicClock { 1_500L },
        catalog = catalog
    )

    private fun authorization(command: FrozenToolCommand) = AuthorizedToolCommand(
        command = command,
        approvalId = null,
        basis = AgentToolAuthorizationBasis.WORKSPACE_READ_GRANT
    )

    private suspend fun frozenRun(): FrozenRun {
        val journal = InMemoryAgentJournal()
        val task = AgentRuntimeFixtures.task()
        journal.commit(CreateTaskTransition("replay-task", task))
        val run = AgentRuntimeFixtures.run(state = AgentRunState.RUNNING)
        journal.commit(
            CreateRunTransition(
                commandId = "replay-run",
                run = run,
                taskRevision = task.revision,
                taskDisplay = AgentDisplayStatus.RUNNING
            )
        )
        val proposal = AgentToolProposal(
            invocationId = "invocation-1",
            runId = run.runId,
            taskId = task.taskId,
            stepId = "step-1",
            stepIndex = 0,
            callOrdinal = 0,
            toolKey = descriptor.toolKey,
            toolVersion = descriptor.version,
            schemaHash = descriptor.schemaHash,
            argumentsHash = "a".repeat(64),
            proposedAtMillis = 1_000L
        )
        val recorded = journal.recordProposed(proposal) as AgentToolInvocationRecord.Recorded
        val command = FrozenToolCommand(
            invocationId = recorded.invocation.invocationId,
            runId = run.runId,
            stepId = recorded.invocation.stepId,
            callOrdinal = 0,
            toolKey = descriptor.toolKey,
            toolVersion = descriptor.version,
            schemaHash = descriptor.schemaHash,
            normalizedArgumentsJson = """{"limit":4096,"offset":0,"path":"inputs/brief.txt"}""",
            argumentsHash = "a".repeat(64),
            resourceFingerprint = "replay-resource",
            runEpoch = run.epoch,
            policyVersion = run.policy.policyVersion,
            expiresAtMillis = 9_000_000L
        )
        journal.recordFrozen(
            AgentFrozenInvocation(
                command = command,
                descriptor = descriptor,
                scope = AgentToolScope(
                    taskRoot = "${task.workspaceId}/tasks/${task.taskId}",
                    logicalPaths = listOf("inputs/brief.txt"),
                    expectedOldHash = null,
                    networkDestinations = emptyList()
                ),
                recordedAtMillis = 1_100L
            )
        )
        return FrozenRun(journal, task, run, command)
    }

    private data class FrozenRun(
        val journal: InMemoryAgentJournal,
        val task: AgentTask,
        val run: AgentRun,
        val command: FrozenToolCommand
    )

    private class RecordingExecutor(
        override val descriptor: AgentToolDescriptor
    ) : AgentToolExecutor {
        var calls = 0

        override suspend fun execute(
            command: FrozenToolCommand,
            context: AgentToolContext
        ): AgentToolResult {
            calls += 1
            return AgentToolResult(status = AgentToolResultStatus.SUCCEEDED, summary = "read ok")
        }
    }

    private class SuspendingExecutor(
        override val descriptor: AgentToolDescriptor
    ) : AgentToolExecutor {
        val entered = CompletableDeferred<Unit>()
        var calls = 0

        override suspend fun execute(
            command: FrozenToolCommand,
            context: AgentToolContext
        ): AgentToolResult {
            calls += 1
            entered.complete(Unit)
            awaitCancellation()
        }
    }

    private class RecordingLedger(
        private val delegate: InMemoryAgentJournal
    ) : AgentToolInvocationLedger {
        var cancelledSideEffects: Boolean? = null

        override suspend fun recordProposed(proposal: AgentToolProposal) =
            delegate.recordProposed(proposal)

        override suspend fun recordFrozen(frozen: AgentFrozenInvocation) =
            delegate.recordFrozen(frozen)

        override suspend fun markWaitingApproval(
            invocationId: String,
            atMillis: Long
        ) = delegate.markWaitingApproval(invocationId, atMillis)

        override suspend fun markApproved(
            invocationId: String,
            atMillis: Long
        ) = delegate.markApproved(invocationId, atMillis)

        override suspend fun markRunning(
            invocationId: String,
            atMillis: Long
        ) = delegate.markRunning(invocationId, atMillis)

        override suspend fun markTerminal(
            invocationId: String,
            status: ToolInvocationStatus,
            possibleSideEffects: Boolean,
            resultRef: String?,
            atMillis: Long
        ): AgentToolInvocationUpdate {
            if (status == ToolInvocationStatus.CANCELLED) {
                cancelledSideEffects = possibleSideEffects
            }
            return delegate.markTerminal(invocationId, status, possibleSideEffects, resultRef, atMillis)
        }

        override suspend fun readInvocation(invocationId: String) =
            delegate.readInvocation(invocationId)

        override suspend fun readFrozenInvocation(invocationId: String) =
            delegate.readFrozenInvocation(invocationId)
    }
}
