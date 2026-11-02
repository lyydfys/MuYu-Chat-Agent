package com.muyuchat.mca.agent.platform

import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentCommitResult
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentEventPage
import com.muyuchat.core.agent.contract.AgentJournal
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRunSnapshot
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTransition
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.contract.ToolInvocation
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.contract.UpsertApprovalTransition
import com.muyuchat.core.agent.contract.UpsertInvocationTransition
import com.muyuchat.core.agent.contract.UpsertStepTransition
import com.muyuchat.core.agent.runtime.journal.InMemoryAgentJournal
import com.muyuchat.mca.agent.data.AgentTestFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 restart closure: a pending or already-clicked approval must never turn into execution after
 * process death, and the invocation behind it must get a paired terminal observation
 * (`NOT_EXECUTED`/`EXPIRED`), not stay reusable forever.
 *
 * Uses the production in-memory journal plus a recording delegate; no Room/Android is required.
 * The negative `approvedButUnconsumed...` case intentionally fails against the current production
 * recovery path and is the red guard for the P0 wiring.
 */
class AgentApprovalRestartClosureTest {

    @Test
    fun pendingApprovalAfterRestartExpiresApprovalAndSettlesInvocationNotExecuted() = runBlocking {
        val fixture = fixture(
            approvalStatus = AgentApprovalStatus.PENDING,
            invocationStatus = ToolInvocationStatus.WAITING_APPROVAL
        )

        val report = recover(fixture)

        val result = report.results.single()
        assertTrue("expected a settled recovery, got $result", result is AgentRunRecoveryResult.Recovered)
        result as AgentRunRecoveryResult.Recovered
        assertEquals(AgentPauseReason.APPROVAL_EXPIRED, result.pauseReason)
        assertEquals(AgentRunState.PAUSED, fixture.journal.readRun(RUN_ID)!!.run.state)
        assertEquals(AgentApprovalStatus.EXPIRED, fixture.journal.readApproval(APPROVAL_ID)!!.status)
        assertEquals(
            ToolInvocationStatus.NOT_EXECUTED,
            fixture.recording.invocations.getValue(INVOCATION_ID).status
        )
        assertTrue(
            "recovery must commit the invocation settlement, not only the run state",
            fixture.recording.transitions.any {
                it is UpsertInvocationTransition &&
                    it.invocation.invocationId == INVOCATION_ID &&
                    it.invocation.status == ToolInvocationStatus.NOT_EXECUTED
            }
        )
        assertNoSuccessCommitted(fixture)
    }

    @Test
    fun approvedButUnconsumedAfterRestartNeverExecutesOrSucceeds() = runBlocking {
        val fixture = fixture(
            approvalStatus = AgentApprovalStatus.APPROVED,
            invocationStatus = ToolInvocationStatus.APPROVED
        )

        recover(fixture)

        // Legacy/upgrade state only: P0 resume consumes PENDING directly, so an APPROVED row can
        // only be historical. Recovery must fail closed rather than execute or report success.
        val approval = fixture.journal.readApproval(APPROVAL_ID)!!.status
        assertNotEquals("an unrecovered approval must not remain usable", AgentApprovalStatus.PENDING, approval)
        assertNotEquals(AgentRunState.SUCCEEDED, fixture.journal.readRun(RUN_ID)!!.run.state)
        assertNotEquals(
            ToolInvocationStatus.SUCCEEDED,
            fixture.recording.invocations.getValue(INVOCATION_ID).status
        )
        assertNoSuccessCommitted(fixture)
    }

    @Test
    fun consumedApprovalAfterRestartIsNotReplayedAndRunNeverSucceeds() = runBlocking {
        val fixture = fixture(
            approvalStatus = AgentApprovalStatus.CONSUMED,
            invocationStatus = ToolInvocationStatus.RUNNING
        )

        recover(fixture)

        assertEquals(AgentApprovalStatus.CONSUMED, fixture.journal.readApproval(APPROVAL_ID)!!.status)
        assertNotEquals(AgentRunState.SUCCEEDED, fixture.journal.readRun(RUN_ID)!!.run.state)
        assertNotEquals(
            ToolInvocationStatus.SUCCEEDED,
            fixture.recording.invocations.getValue(INVOCATION_ID).status
        )
        assertNoSuccessCommitted(fixture)
    }

    // ---------------------------------------------------------------------------------------
    // Fixture.
    // ---------------------------------------------------------------------------------------

    private data class Fixture(
        val journal: InMemoryAgentJournal,
        val recording: RecordingJournal
    )

    private suspend fun fixture(
        approvalStatus: AgentApprovalStatus,
        invocationStatus: ToolInvocationStatus
    ): Fixture {
        val journal = InMemoryAgentJournal()
        val task = AgentTestFixtures.task(
            taskId = "task-1",
            workspaceId = "workspace-1",
            lifecycle = com.muyuchat.core.agent.contract.AgentTaskLifecycle.ACTIVE,
            displayStatus = AgentDisplayStatus.WAITING_APPROVAL,
            revision = 0L,
            lastRunId = null
        )
        journal.commit(CreateTaskTransition("p0-create-task", task))

        val run = AgentTestFixtures.run(
            runId = RUN_ID,
            taskId = "task-1",
            state = AgentRunState.WAITING_APPROVAL,
            reason = AgentPauseReason.NONE,
            epoch = 7L,
            lastSequence = 0L,
            revision = 1L
        )
        journal.commit(
            CreateRunTransition(
                commandId = "p0-create-run",
                run = run,
                taskRevision = task.revision,
                taskDisplay = AgentDisplayStatus.WAITING_APPROVAL
            )
        )

        val step = AgentTestFixtures.step(
            stepId = STEP_ID,
            runId = RUN_ID,
            index = 0,
            status = com.muyuchat.core.agent.contract.AgentStepStatus.WAITING
        ).copy(invocationId = INVOCATION_ID)
        journal.commit(
            UpsertStepTransition(
                commandId = "p0-step",
                step = step,
                expectedRevision = run.revision
            )
        )

        val invocation = AgentTestFixtures.invocation(
            invocationId = INVOCATION_ID,
            runId = RUN_ID,
            stepId = STEP_ID,
            status = invocationStatus
        ).copy(
            toolKey = "artifact.write_text",
            toolVersion = "1.0.0",
            schemaHash = "schema-sha",
            argumentsHash = "args-sha",
            resourceFingerprint = "resource-sha",
            idempotencyKey = "idem-1",
            updatedAtMillis = 2_000L
        )
        journal.commit(
            UpsertInvocationTransition(
                commandId = "p0-invocation",
                invocation = invocation,
                expectedRevision = run.revision
            )
        )

        val approval = AgentTestFixtures.approval(
            approvalId = APPROVAL_ID,
            status = approvalStatus,
            expiresAtMillis = 301_000L
        )
        journal.commit(
            UpsertApprovalTransition(
                commandId = "p0-approval",
                approval = approval,
                expectedRevision = run.revision
            )
        )

        val recording = RecordingJournal(journal).apply {
            invocations[INVOCATION_ID] = invocation
        }
        return Fixture(journal, recording)
    }

    private suspend fun recover(fixture: Fixture): AgentStartupRecoveryReport {
        val records = object : AgentRecoveryRecordSource {
            override suspend fun findInvocation(invocationId: String): ToolInvocation? =
                fixture.recording.invocations[invocationId]

            override suspend fun findArtifactCommit(artifactId: String): com.muyuchat.core.agent.contract.AgentArtifactCommit? =
                null

            override suspend fun findLatestAttempt(runId: String): com.muyuchat.core.agent.contract.AgentModelAttempt? =
                null
        }
        return AgentStartupRecovery(
            journal = fixture.recording,
            records = records,
            clock = { 10_000_000L }
        ).reconcile()
    }

    private fun assertNoSuccessCommitted(fixture: Fixture) {
        assertTrue(
            "no SUCCEEDED invocation may be written by recovery",
            fixture.recording.transitions.none {
                it is UpsertInvocationTransition &&
                    it.invocation.status == ToolInvocationStatus.SUCCEEDED
            }
        )
        assertTrue(
            "recovery must never move the run to SUCCEEDED",
            fixture.recording.transitions.none {
                it is com.muyuchat.core.agent.contract.UpdateRunTransition &&
                    it.run.state == AgentRunState.SUCCEEDED
            }
        )
    }

    /** Delegates all durable behavior while exposing the transitions/invocations the test needs. */
    private class RecordingJournal(
        private val delegate: InMemoryAgentJournal
    ) : AgentJournal {
        val transitions = mutableListOf<AgentTransition>()
        val invocations = mutableMapOf<String, ToolInvocation>()

        override suspend fun readRun(runId: String): AgentRunSnapshot? = delegate.readRun(runId)

        override suspend fun listNonTerminalRuns(limit: Int): List<AgentRunSnapshot> =
            delegate.listNonTerminalRuns(limit)

        override suspend fun readEvents(
            runId: String,
            afterSequence: Long,
            limit: Int
        ): AgentEventPage = delegate.readEvents(runId, afterSequence, limit)

        override suspend fun commit(command: AgentTransition): AgentCommitResult {
            val result = delegate.commit(command)
            transitions += command
            if (command is UpsertInvocationTransition) {
                invocations[command.invocation.invocationId] = command.invocation
            }
            return result
        }
    }

    private companion object {
        const val RUN_ID = "run-1"
        const val STEP_ID = "step-1"
        const val INVOCATION_ID = "invocation-1"
        const val APPROVAL_ID = "approval-1"
    }
}
