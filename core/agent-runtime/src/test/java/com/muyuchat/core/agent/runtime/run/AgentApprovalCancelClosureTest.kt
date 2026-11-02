package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.CancelAgentRun
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.core.agent.contract.ResolveAgentApproval
import com.muyuchat.core.agent.contract.StartAgentRun
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.journal.AgentFrozenInvocation
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationRecord
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationUpdate
import com.muyuchat.core.agent.runtime.journal.AgentToolProposal
import com.muyuchat.core.agent.runtime.journal.AgentToolScope
import com.muyuchat.core.agent.runtime.journal.InMemoryAgentJournal
import com.muyuchat.core.agent.runtime.testing.AgentRuntimeFixtures
import com.muyuchat.core.agent.runtime.testing.FixedRunSpecProvider
import com.muyuchat.core.agent.runtime.testing.ScriptedRunExecutor
import com.muyuchat.core.agent.runtime.validation.AgentV1ToolSpecs
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 cancel closure for a run parked at WAITING_APPROVAL.
 *
 * A cancel must not look like a successful tool execution, must write exactly one terminal state,
 * and a late approval click after the terminal can never launch execution again. The setup records a
 * durable VALIDATED/WAITING_APPROVAL invocation because the owner now refuses an unledgered wait.
 */
class AgentApprovalCancelClosureTest {

    @Test
    fun cancelWhileWaitingApprovalWritesOneTerminalAndNoSuccess() = runTest {
        val fixture = waitingApprovalFixture()
        val runId = fixture.runId
        val waiting = fixture.ownerRun(runId)
        assertEquals(AgentRunState.WAITING_APPROVAL, waiting.state)

        val cancelled = fixture.owner.requestCancel(
            CancelAgentRun(fixture.ids.nextId("cmd"), runId, waiting.revision)
        )
        assertTrue("cancel must be accepted, got $cancelled", cancelled is AgentCommandResult.Accepted)
        runCurrent()

        assertEquals(AgentRunState.CANCELLED, fixture.ownerRun(runId).state)
        val events = fixture.journal.allEvents(runId)
        val terminalEvents = events.filter { it.type == AgentEventTypes.RUN_TERMINAL }
        assertTrue("cancel must not write more than one terminal event", terminalEvents.size <= 1)
        if (terminalEvents.isNotEmpty()) {
            assertEquals("CANCELLED", terminalEvents.single().summary)
        }
        assertTrue(
            "no tool-success event may be produced by cancellation",
            events.none { event ->
                event.type == AgentEventTypes.TOOL_RESULT && event.summary?.contains("SUCCEEDED") == true
            }
        )
    }

    @Test
    fun lateApprovalClickAfterCancelIsRejectedAndNeverRunsTheExecutorAgain() = runTest {
        val fixture = waitingApprovalFixture()
        val runId = fixture.runId
        fixture.owner.requestCancel(
            CancelAgentRun(fixture.ids.nextId("cmd"), runId, fixture.ownerRun(runId).revision)
        )
        runCurrent()
        val executionsBefore = fixture.executor.executeCount
        assertEquals(1, executionsBefore)

        val late = fixture.owner.resolveApproval(
            ResolveAgentApproval(
                commandId = fixture.ids.nextId("cmd"),
                runId = runId,
                expectedRunRevision = fixture.ownerRun(runId).revision,
                approvalId = APPROVAL_ID,
                decision = AgentApprovalDecision.ALLOW_ONCE
            )
        )
        assertTrue("a cancelled run must reject a late approval click", late is AgentCommandResult.Rejected)
        assertEquals(
            AgentErrorCodes.COMMAND_INVALID_STATE,
            (late as AgentCommandResult.Rejected).error.code
        )
        runCurrent()
        assertEquals("no second execution may start", executionsBefore, fixture.executor.executeCount)
        assertEquals(AgentRunState.CANCELLED, fixture.ownerRun(runId).state)
    }

    @Test
    fun repeatedCancelKeepsOneTerminalStateAndNeverSucceeds() = runTest {
        val fixture = waitingApprovalFixture()
        val runId = fixture.runId

        val first = fixture.owner.requestCancel(
            CancelAgentRun(fixture.ids.nextId("cmd"), runId, fixture.ownerRun(runId).revision)
        )
        assertTrue(first is AgentCommandResult.Accepted)
        runCurrent()
        val revisionAfterFirstCancel = fixture.ownerRun(runId).revision
        val second = fixture.owner.requestCancel(
            CancelAgentRun(fixture.ids.nextId("cmd"), runId, fixture.ownerRun(runId).revision)
        )
        assertTrue(second is AgentCommandResult.Accepted)
        runCurrent()

        assertEquals(AgentRunState.CANCELLED, fixture.ownerRun(runId).state)
        assertEquals(
            "a repeated cancel must not write a second terminal transition",
            revisionAfterFirstCancel,
            fixture.ownerRun(runId).revision
        )
        assertTrue(
            "a repeated cancel must not write a second terminal event",
            fixture.journal.allEvents(runId)
                .count { it.type == AgentEventTypes.RUN_TERMINAL } <= 1
        )
        assertNotEquals(AgentRunState.SUCCEEDED, fixture.ownerRun(runId).state)
    }

    // ---------------------------------------------------------------------------------------
    // Fixture.
    // ---------------------------------------------------------------------------------------

    private val validator = SupportedToolSchemaValidator()
    private val descriptor = AgentV1ToolSpecs.descriptor(AgentV1ToolSpecs.WORKSPACE_READ_TEXT, validator)

    private data class WaitingFixture(
        val owner: AgentRunOwner,
        val journal: InMemoryAgentJournal,
        val ids: SequentialAgentIdGenerator,
        val executor: ScriptedRunExecutor,
        val runId: String
    ) {
        suspend fun ownerRun(runId: String): com.muyuchat.core.agent.contract.AgentRun =
            journal.readRun(runId)!!.run
    }

    private suspend fun TestScope.waitingApprovalFixture(): WaitingFixture {
        val journal = InMemoryAgentJournal()
        val executor = ScriptedRunExecutor()
        val gate = CompletableDeferred<Unit>()
        executor.gate = gate
        val ids = SequentialAgentIdGenerator("p0-cancel")
        val clock = ManualAgentMonotonicClock()
        val owner = AgentRunOwner(
            journal = journal,
            ledger = journal,
            taskReader = journal,
            questionReader = journal,
            approvalReader = journal,
            toolLedger = journal,
            toolAuthorization = NoAgentToolAuthorization,
            specProvider = FixedRunSpecProvider(),
            executor = executor,
            processor = AgentCommandProcessor(backgroundScope),
            clock = clock,
            ids = ids,
            scope = backgroundScope
        )
        val created = owner.createTask(
            CreateAgentTask(
                commandId = ids.nextId("cmd"),
                goal = "整理 inputs/brief.txt",
                displayTitle = "P0 cancel fixture",
                modelBinding = AgentRuntimeFixtures.binding(),
                policy = com.muyuchat.core.agent.contract.AgentRunPolicy()
            )
        )
        assertTrue("task creation must be accepted, got $created", created is AgentCommandResult.Accepted)
        val task = journal.allTasks().single()
        val started = owner.startRun(
            StartAgentRun(
                commandId = ids.nextId("cmd"),
                taskId = task.taskId,
                expectedTaskRevision = task.revision
            )
        )
        assertTrue("start must be accepted, got $started", started is AgentCommandResult.Accepted)
        val runId = requireNotNull((started as AgentCommandResult.Accepted).runId)
        runCurrent()
        val run = journal.readRun(runId)!!.run
        check(run.state == AgentRunState.RUNNING) {
            "fixture must reach RUNNING before seeding the invocation, got ${run.state}"
        }

        val command = AgentRuntimeFixtures.frozenCommandFor(
            invocationId = INVOCATION_ID,
            runId = runId,
            runEpoch = run.epoch
        )
        val proposal = AgentToolProposal(
            invocationId = INVOCATION_ID,
            runId = runId,
            taskId = task.taskId,
            stepId = command.stepId,
            stepIndex = 0,
            callOrdinal = command.callOrdinal,
            toolKey = descriptor.toolKey,
            toolVersion = descriptor.version,
            schemaHash = descriptor.schemaHash,
            argumentsHash = command.argumentsHash,
            proposedAtMillis = 1_100L
        )
        assertTrue(
            "proposal must be recorded",
            journal.recordProposed(proposal) is AgentToolInvocationRecord.Recorded
        )
        assertTrue(
            "frozen material must be recorded",
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
                    recordedAtMillis = 1_200L
                )
            ) is AgentToolInvocationRecord.Recorded
        )
        assertTrue(
            "invocation must wait for approval",
            journal.markWaitingApproval(INVOCATION_ID, 1_300L) is AgentToolInvocationUpdate.Updated
        )
        val approval = AgentApproval(
            approvalId = APPROVAL_ID,
            invocationId = INVOCATION_ID,
            principal = "local-user",
            argumentsHash = command.argumentsHash,
            toolSchemaHash = command.schemaHash,
            resourceFingerprint = command.resourceFingerprint,
            policyVersion = run.policy.policyVersion,
            runEpoch = run.epoch,
            expiresAtMillis = 5_000_000L,
            status = AgentApprovalStatus.PENDING,
            createdAtMillis = 1_000_000L
        )
        executor.enqueue(AgentRuntimeFixtures.waitingApproval(run, approval))
        gate.complete(Unit)
        runCurrent()
        check(journal.readRun(runId)!!.run.state == AgentRunState.WAITING_APPROVAL) {
            "fixture must park the run at WAITING_APPROVAL, got ${journal.readRun(runId)!!.run.state}"
        }
        return WaitingFixture(owner, journal, ids, executor, runId)
    }

    private companion object {
        const val APPROVAL_ID = "approval-fault-1"
        const val INVOCATION_ID = "invocation-fault-1"
    }
}
