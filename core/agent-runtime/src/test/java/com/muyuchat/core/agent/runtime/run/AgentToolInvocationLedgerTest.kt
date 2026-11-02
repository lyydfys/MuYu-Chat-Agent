package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.journal.AgentFrozenInvocation
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationRecord
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationUpdate
import com.muyuchat.core.agent.runtime.journal.AgentToolProposal
import com.muyuchat.core.agent.runtime.journal.AgentToolScope
import com.muyuchat.core.agent.runtime.journal.InMemoryAgentJournal
import com.muyuchat.core.agent.runtime.testing.AgentRuntimeFixtures
import com.muyuchat.core.agent.runtime.validation.AgentV1ToolSpecs
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 invocation-ledger contract at the runtime seam.
 *
 * AGENT_TOOLS_MEMORY_WORKFLOW_PLAN §4.1: the PROPOSED row (step + invocation in one transaction) is
 * written before freeze, is idempotent on `(runId, stepId, callOrdinal)`, rejects a missing/foreign
 * run, and gives exactly one `markRunning` gate plus an immutably terminal result.
 *
 * This exercises the production in-memory ledger implementation. The Room adapter must satisfy the
 * same contract; its SQL uniqueness is covered by `AgentInvocationApprovalSchemaClosureTest`, its
 * DAO behavior remains an instrumentation item.
 */
class AgentToolInvocationLedgerTest {

    private val validator = SupportedToolSchemaValidator()
    private val descriptor = AgentV1ToolSpecs.descriptor(AgentV1ToolSpecs.WORKSPACE_READ_TEXT, validator)

    @Test
    fun proposedRowIsPersistedBeforeAnyFreeze() = runTest {
        val (journal, task, run) = setup()
        val proposal = proposal(run, task)

        val recorded = journal.recordProposed(proposal)

        assertTrue("expected a recorded row, got $recorded", recorded is AgentToolInvocationRecord.Recorded)
        recorded as AgentToolInvocationRecord.Recorded
        assertEquals(ToolInvocationStatus.PROPOSED, recorded.invocation.status)
        assertEquals(proposal.invocationId, recorded.invocation.invocationId)
        assertEquals(proposal.idempotencyKey, recorded.invocation.idempotencyKey)
        assertEquals(
            ToolInvocationStatus.PROPOSED,
            journal.readInvocation(proposal.invocationId)!!.status
        )
        val snapshot = journal.readRun(run.runId)!!
        assertEquals(1, snapshot.steps.size)
        assertEquals(proposal.invocationId, snapshot.steps.single().invocationId)
    }

    @Test
    fun replayOfTheSameOrdinalReturnsTheExistingRowWithoutDuplication() = runTest {
        val (journal, task, run) = setup()
        val proposal = proposal(run, task)

        val first = journal.recordProposed(proposal) as AgentToolInvocationRecord.Recorded
        val replay = journal.recordProposed(
            // Same logical call: a retry may mint a new invocation id, but the ordinal identity wins.
            proposal.copy(invocationId = "invocation-retry", argumentsHash = "b".repeat(64))
        ) as AgentToolInvocationRecord.Recorded

        assertEquals(first.invocation.invocationId, replay.invocation.invocationId)
        assertEquals(1, journal.readRun(run.runId)!!.steps.size)
        assertEquals(
            first.invocation.invocationId,
            journal.readRun(run.runId)!!.steps.single().invocationId
        )
        assertNull(journal.readInvocation("invocation-retry"))
    }

    @Test
    fun missingOrForeignRunIsRejectedWithoutCreatingARow() = runTest {
        val (journal, task, run) = setup()
        val proposal = proposal(run, task)

        val missing = journal.recordProposed(proposal.copy(runId = "missing-run"))
        assertTrue("missing run must be rejected", missing is AgentToolInvocationRecord.Rejected)
        assertEquals("COMMAND_NOT_FOUND", (missing as AgentToolInvocationRecord.Rejected).errorCode)

        val foreignTask = journal.recordProposed(proposal.copy(taskId = "foreign-task"))
        assertTrue("foreign task must be rejected", foreignTask is AgentToolInvocationRecord.Rejected)
        assertEquals(
            "COMMAND_INVALID_STATE",
            (foreignTask as AgentToolInvocationRecord.Rejected).errorCode
        )
        assertNull(journal.readInvocation(proposal.invocationId))
        assertEquals(0, journal.readRun(run.runId)!!.steps.size)
    }

    @Test
    fun frozenMaterialMustMatchTheProposalAndIsRecordedOnce() = runTest {
        val (journal, task, run) = setup()
        val proposed = journal.recordProposed(proposal(run, task)) as AgentToolInvocationRecord.Recorded
        val command = command(run, invocationId = proposed.invocation.invocationId)
        val frozen = AgentFrozenInvocation(
            command = command,
            descriptor = descriptor,
            scope = scopeFor(task),
            recordedAtMillis = 1_100L
        )

        val mismatched = journal.recordFrozen(
            frozen.copy(command = command.copy(stepId = "other-step"))
        )
        assertTrue("mismatched frozen material must be rejected", mismatched is AgentToolInvocationRecord.Rejected)
        assertEquals(
            "INTERNAL_INVARIANT",
            (mismatched as AgentToolInvocationRecord.Rejected).errorCode
        )

        val recorded = journal.recordFrozen(frozen)
        assertTrue("expected VALIDATED, got $recorded", recorded is AgentToolInvocationRecord.Recorded)
        assertEquals(
            ToolInvocationStatus.VALIDATED,
            (recorded as AgentToolInvocationRecord.Recorded).invocation.status
        )
        assertEquals(
            command.resourceFingerprint,
            journal.readInvocation(command.invocationId)!!.resourceFingerprint
        )
        assertEquals(command, journal.readFrozenInvocation(command.invocationId)!!.command)
    }

    @Test
    fun runningGateAndTerminalValueAreSingleUse() = runTest {
        val (journal, task, run) = setup()
        val proposed = journal.recordProposed(proposal(run, task)) as AgentToolInvocationRecord.Recorded
        val command = command(run, invocationId = proposed.invocation.invocationId)
        journal.recordFrozen(
            AgentFrozenInvocation(
                command = command,
                descriptor = descriptor,
                scope = scopeFor(task),
                recordedAtMillis = 1_100L
            )
        )

        assertTrue(journal.markRunning(command.invocationId, 1_200L) is AgentToolInvocationUpdate.Updated)
        assertTrue(
            "a second RUNNING gate must lose",
            journal.markRunning(command.invocationId, 1_300L) is AgentToolInvocationUpdate.Rejected
        )

        assertTrue(
            journal.markTerminal(
                invocationId = command.invocationId,
                status = ToolInvocationStatus.SUCCEEDED,
                possibleSideEffects = false,
                resultRef = "result-1",
                atMillis = 1_400L
            ) is AgentToolInvocationUpdate.Updated
        )
        assertTrue(
            "a different terminal value must never overwrite",
            journal.markTerminal(
                invocationId = command.invocationId,
                status = ToolInvocationStatus.FAILED,
                possibleSideEffects = false,
                resultRef = null,
                atMillis = 1_500L
            ) is AgentToolInvocationUpdate.Rejected
        )
        assertTrue(
            "repeating the same terminal value is idempotent",
            journal.markTerminal(
                invocationId = command.invocationId,
                status = ToolInvocationStatus.SUCCEEDED,
                possibleSideEffects = false,
                resultRef = "result-1",
                atMillis = 1_600L
            ) is AgentToolInvocationUpdate.Updated
        )
        assertEquals(
            ToolInvocationStatus.SUCCEEDED,
            journal.readInvocation(command.invocationId)!!.status
        )
    }

    @Test
    fun waitingApprovalCannotStartExecutionBeforeTheApprovedIntent() = runTest {
        val (journal, task, run) = setup()
        val proposed = journal.recordProposed(proposal(run, task)) as AgentToolInvocationRecord.Recorded
        val command = command(run, invocationId = proposed.invocation.invocationId)
        journal.recordFrozen(
            AgentFrozenInvocation(
                command = command,
                descriptor = descriptor,
                scope = scopeFor(task),
                recordedAtMillis = 1_100L
            )
        )
        assertTrue(
            journal.markWaitingApproval(command.invocationId, 1_150L) is
                AgentToolInvocationUpdate.Updated
        )

        assertTrue(
            "an unapproved row must not pass the execution gate",
            journal.markRunning(command.invocationId, 1_200L) is AgentToolInvocationUpdate.Rejected
        )
        assertEquals(
            ToolInvocationStatus.WAITING_APPROVAL,
            journal.readInvocation(command.invocationId)!!.status
        )

        assertTrue(journal.markApproved(command.invocationId, 1_250L) is AgentToolInvocationUpdate.Updated)
        assertTrue(
            "only the recorded APPROVED intent opens the gate",
            journal.markRunning(command.invocationId, 1_300L) is AgentToolInvocationUpdate.Updated
        )
    }

    @Test
    fun approvedRecordsTheDurableDispatchIntentAndStaysExecutable() = runTest {
        val (journal, task, run) = setup()
        val proposed = journal.recordProposed(proposal(run, task)) as AgentToolInvocationRecord.Recorded
        val command = command(run, invocationId = proposed.invocation.invocationId)
        journal.recordFrozen(
            AgentFrozenInvocation(
                command = command,
                descriptor = descriptor,
                scope = scopeFor(task),
                recordedAtMillis = 1_100L
            )
        )
        assertTrue(
            journal.markWaitingApproval(command.invocationId, 1_150L) is
                AgentToolInvocationUpdate.Updated
        )

        assertTrue(
            "authorization must produce a durable APPROVED intent",
            journal.markApproved(command.invocationId, 1_200L) is AgentToolInvocationUpdate.Updated
        )
        assertEquals(
            ToolInvocationStatus.APPROVED,
            journal.readInvocation(command.invocationId)!!.status
        )
        assertEquals(
            com.muyuchat.core.agent.contract.AgentStepStatus.WAITING,
            journal.readRun(run.runId)!!.steps.single().status
        )
        assertTrue(
            "re-approving the same invocation is idempotent",
            journal.markApproved(command.invocationId, 1_250L) is AgentToolInvocationUpdate.Updated
        )
        assertTrue(
            "an APPROVED intent may enter the single RUNNING gate",
            journal.markRunning(command.invocationId, 1_300L) is AgentToolInvocationUpdate.Updated
        )
        assertEquals(
            ToolInvocationStatus.RUNNING,
            journal.readInvocation(command.invocationId)!!.status
        )
        assertTrue(
            "an already-started invocation cannot be re-approved",
            journal.markApproved(command.invocationId, 1_350L) is AgentToolInvocationUpdate.Rejected
        )
    }

    // ---------------------------------------------------------------------------------------
    // Fixture.
    // ---------------------------------------------------------------------------------------

    private suspend fun setup(): Triple<InMemoryAgentJournal, AgentTask, AgentRun> {
        val journal = InMemoryAgentJournal()
        val task = AgentRuntimeFixtures.task()
        journal.commit(CreateTaskTransition("p0-ledger-task", task))
        val run = AgentRuntimeFixtures.run(state = AgentRunState.RUNNING)
        journal.commit(
            CreateRunTransition(
                commandId = "p0-ledger-run",
                run = run,
                taskRevision = task.revision,
                taskDisplay = AgentDisplayStatus.RUNNING
            )
        )
        return Triple(journal, task, run)
    }

    private fun proposal(run: AgentRun, task: AgentTask): AgentToolProposal = AgentToolProposal(
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

    private fun command(run: AgentRun, invocationId: String): FrozenToolCommand = FrozenToolCommand(
        invocationId = invocationId,
        runId = run.runId,
        stepId = "step-1",
        callOrdinal = 0,
        toolKey = descriptor.toolKey,
        toolVersion = descriptor.version,
        schemaHash = descriptor.schemaHash,
        normalizedArgumentsJson = """{"limit":4096,"offset":0,"path":"inputs/brief.txt"}""",
        argumentsHash = "a".repeat(64),
        resourceFingerprint = "ledger-resource",
        runEpoch = run.epoch,
        policyVersion = run.policy.policyVersion,
        expiresAtMillis = 9_000_000L
    )

    private fun scopeFor(task: AgentTask): AgentToolScope = AgentToolScope(
        taskRoot = "${task.workspaceId}/tasks/${task.taskId}",
        logicalPaths = listOf("inputs/brief.txt"),
        expectedOldHash = null,
        networkDestinations = emptyList()
    )
}
