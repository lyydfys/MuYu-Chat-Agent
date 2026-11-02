package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.ResolveAgentApproval
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.journal.AgentJournalRejectedException
import com.muyuchat.core.agent.runtime.testing.AgentOwnerHarness
import com.muyuchat.core.agent.runtime.testing.AgentRuntimeFixtures
import com.muyuchat.core.agent.runtime.testing.ScriptedRunExecutor
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N03: approval resolution must commit the approval CAS and the run/ledger transition inside one
 * [AgentRunTransaction] and must not launch model/tool work while that transaction is open.
 *
 * These are memory-level JVM tests: they prove the boundary and ordering, not a real Room rollback.
 * The database rollback itself needs the shared Room runner and is recorded as a device/instrumented
 * gap until the environment allows it.
 */
class AgentApprovalTransactionTest {

    private class RecordingTransaction(
        private val events: MutableList<String>
    ) : AgentRunTransaction {
        var entered: Int = 0
        var insideHook: (suspend () -> Unit)? = null
        var failAfterBlock: Throwable? = null

        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            entered += 1
            events += "tx-begin"
            val result = block()
            insideHook?.invoke()
            events += "tx-end"
            failAfterBlock?.let { throw it }
            return result
        }
    }

    private class RecordingExecutor(
        private val events: MutableList<String>
    ) : ScriptedRunExecutor() {
        override suspend fun execute(
            run: AgentRun,
            task: AgentTask,
            resume: AgentLoopResume?,
            progress: AgentModelProgressSink
        ): AgentLoopResult {
            events += "execute"
            return super.execute(run, task, resume, progress)
        }
    }

    @Test
    fun allowCommitsTheRunTransitionInsideTheTransactionAndLaunchesAfterwards() = runTest {
        val events = mutableListOf<String>()
        val executor = RecordingExecutor(events)
        val transaction = RecordingTransaction(events)
        val commandTemplate = AgentRuntimeFixtures.frozenCommandFor()
        val approval = AgentRuntimeFixtures.pendingApprovalFor(commandTemplate)
        executor.enqueue(AgentRuntimeFixtures.waitingApproval(AgentRuntimeFixtures.run(), approval))
        val harness = AgentOwnerHarness(backgroundScope, executor, transaction = transaction)
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        val runId = started.runId!!
        val command = commandTemplate.copy(runId = runId)
        harness.seedFrozenInvocation(command)
        runCurrent()
        val waiting = harness.run(runId)!!
        assertEquals(AgentRunState.WAITING_APPROVAL, waiting.state)
        assertEquals("approval resolution is the only transaction entry point here", 0, transaction.entered)

        transaction.insideHook = {
            assertEquals(
                "the WAITING_APPROVAL -> RUNNING commit must be visible inside the transaction",
                AgentRunState.RUNNING,
                harness.run(runId)!!.state
            )
            assertEquals(
                "the authorization must also be recorded as a durable APPROVED intent",
                ToolInvocationStatus.APPROVED,
                harness.journal.readInvocation(command.invocationId)!!.status
            )
        }

        val result = harness.owner.resolveApproval(
            ResolveAgentApproval(
                commandId = "cmd-tx-allow",
                runId = runId,
                expectedRunRevision = waiting.revision,
                approvalId = approval.approvalId,
                decision = AgentApprovalDecision.ALLOW_ONCE
            )
        )

        assertTrue(result is AgentCommandResult.Accepted)
        assertEquals(1, transaction.entered)
        runCurrent()
        assertEquals(
            "model/tool execution must start only after the transaction closed",
            listOf("execute", "tx-begin", "tx-end", "execute"),
            events
        )
        assertEquals(AgentRunState.SUCCEEDED, harness.run(runId)!!.state)
    }

    @Test
    fun denyMarksTheInvocationTerminalInsideTheSameTransaction() = runTest {
        val events = mutableListOf<String>()
        val executor = RecordingExecutor(events)
        val transaction = RecordingTransaction(events)
        val commandTemplate = AgentRuntimeFixtures.frozenCommandFor(invocationId = "invocation-tx-deny")
        val approval = AgentRuntimeFixtures.pendingApprovalFor(
            commandTemplate,
            approvalId = "approval-tx-deny"
        )
        executor.enqueue(AgentRuntimeFixtures.waitingApproval(AgentRuntimeFixtures.run(), approval))
        val harness = AgentOwnerHarness(backgroundScope, executor, transaction = transaction)
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        val runId = started.runId!!
        harness.seedFrozenInvocation(commandTemplate.copy(runId = runId))
        runCurrent()
        val waiting = harness.run(runId)!!
        transaction.insideHook = {
            assertEquals(
                ToolInvocationStatus.DENIED,
                harness.journal.readInvocation(commandTemplate.invocationId)!!.status
            )
        }

        val result = harness.owner.resolveApproval(
            ResolveAgentApproval(
                commandId = "cmd-tx-deny",
                runId = runId,
                expectedRunRevision = waiting.revision,
                approvalId = approval.approvalId,
                decision = AgentApprovalDecision.DENY
            )
        )

        assertTrue(result is AgentCommandResult.Accepted)
        assertEquals(1, transaction.entered)
        runCurrent()
        assertEquals(listOf("execute", "tx-begin", "tx-end", "execute"), events)
        assertEquals(AgentRunState.SUCCEEDED, harness.run(runId)!!.state)
    }

    @Test
    fun aFailedTransactionNeverLaunchesTheResume() = runTest {
        val events = mutableListOf<String>()
        val executor = RecordingExecutor(events)
        val transaction = RecordingTransaction(events).apply {
            // Simulates the durable commit failing after the block ran (the real Room transaction
            // would roll the approval CAS and invocation writes back; the in-memory fake cannot).
            failAfterBlock = AgentJournalRejectedException(
                AgentErrorCodes.STORAGE_CORRUPTION,
                "commit failed"
            )
        }
        val commandTemplate = AgentRuntimeFixtures.frozenCommandFor(invocationId = "invocation-tx-fail")
        val approval = AgentRuntimeFixtures.pendingApprovalFor(commandTemplate, approvalId = "approval-tx-fail")
        executor.enqueue(AgentRuntimeFixtures.waitingApproval(AgentRuntimeFixtures.run(), approval))
        val harness = AgentOwnerHarness(backgroundScope, executor, transaction = transaction)
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        val runId = started.runId!!
        harness.seedFrozenInvocation(commandTemplate.copy(runId = runId))
        runCurrent()
        val waiting = harness.run(runId)!!

        val result = harness.owner.resolveApproval(
            ResolveAgentApproval(
                commandId = "cmd-tx-fail",
                runId = runId,
                expectedRunRevision = waiting.revision,
                approvalId = approval.approvalId,
                decision = AgentApprovalDecision.ALLOW_ONCE
            )
        )

        assertTrue(result is AgentCommandResult.Rejected)
        assertEquals(
            AgentErrorCodes.STORAGE_CORRUPTION,
            (result as AgentCommandResult.Rejected).error.code
        )
        runCurrent()
        assertEquals("a failed transaction must not launch the resume", 1, events.count { it == "execute" })
    }

    @Test
    fun directTransactionRunsTheBlockWithoutABoundary() = runTest {
        var ran = false
        val value = AgentRunTransaction.Direct.inTransaction {
            ran = true
            "ok"
        }
        assertTrue(ran)
        assertEquals("ok", value)
    }
}
