package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.ResolveAgentApproval
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchExecution
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxEntry
import com.muyuchat.core.agent.runtime.testing.AgentOwnerHarness
import com.muyuchat.core.agent.runtime.testing.AgentRuntimeFixtures
import com.muyuchat.core.agent.runtime.testing.ScriptedRunExecutor
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N03 production hand-off: an allowed approval is offered to the outbox dispatch path and the loop
 * consumes the *exact in-memory authorization* through [AgentRunOwner.dispatchApproved].
 *
 * These tests prove the boundary without a second engine: no inline launch when a consumer accepts,
 * no second execution on a duplicate dispatch call, and a typed refusal when no authorization
 * exists.
 */
class AgentApprovedDispatchOutboxTest {

    @Test
    fun anAcceptedHandOffDefersExecutionToTheOutboxConsumerExactlyOnce() = runTest {
        val executor = ScriptedRunExecutor()
        val commandTemplate = AgentRuntimeFixtures.frozenCommandFor(invocationId = "invocation-outbox")
        val approval = AgentRuntimeFixtures.pendingApprovalFor(
            commandTemplate,
            approvalId = "approval-outbox"
        )
        executor.enqueue(AgentRuntimeFixtures.waitingApproval(AgentRuntimeFixtures.run(), approval))
        val handoff = InMemoryAgentApprovedDispatchHandoff()
        var offered: AgentApprovedDispatch? = null
        val sink = AgentApprovedDispatchSink { dispatch ->
            offered = dispatch
            handoff.publish(dispatch)
            AgentApprovedDispatchOffer.HandedOff
        }
        val harness = AgentOwnerHarness(
            scope = backgroundScope,
            executor = executor,
            dispatchHandoff = handoff,
            dispatchSink = sink
        )
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        val runId = started.runId!!
        val command = commandTemplate.copy(runId = runId)
        harness.seedFrozenInvocation(command)
        runCurrent()
        val waiting = harness.run(runId)!!

        val resolved = harness.owner.resolveApproval(
            ResolveAgentApproval(
                commandId = "cmd-outbox-allow",
                runId = runId,
                expectedRunRevision = waiting.revision,
                approvalId = approval.approvalId,
                decision = AgentApprovalDecision.ALLOW_ONCE
            )
        )

        assertTrue(resolved is AgentCommandResult.Accepted)
        runCurrent()
        assertEquals("the sink accepted, so no inline resume may run", 1, executor.executeCount)
        val dispatch = requireNotNull(offered)
        assertEquals(runId, dispatch.runId)
        assertEquals(command.invocationId, dispatch.invocationId)
        assertEquals(command.invocationId, dispatch.authorization.command.invocationId)
        assertEquals(
            ToolInvocationStatus.APPROVED,
            harness.journal.readInvocation(command.invocationId)!!.status
        )
        val entry = AgentDispatchOutboxEntry.approved(
            harness.journal.readInvocation(command.invocationId)!!,
            1_000L
        )

        val first = harness.owner.dispatchApproved(entry)

        assertEquals(AgentDispatchExecution.HandedOff, first)
        runCurrent()
        assertEquals(2, executor.executeCount)
        val resume = requireNotNull(executor.resumes.last())
        val authorized = resume.toolDecision as AgentResumeToolDecision.Authorized
        assertEquals(command.invocationId, authorized.authorization.command.invocationId)

        val duplicate = harness.owner.dispatchApproved(entry)

        assertTrue("a second dispatch must not reuse the authorization", duplicate is AgentDispatchExecution.Refused)
        assertEquals(
            AgentErrorCodes.RESOURCE_LEASE_LOST,
            (duplicate as AgentDispatchExecution.Refused).errorCode
        )
        runCurrent()
        assertEquals("no duplicate execution", 2, executor.executeCount)
    }

    @Test
    fun aRejectedOfferFallsBackToTheInlineOwnerLaunchAndDiscardsTheHandOff() = runTest {
        val executor = ScriptedRunExecutor()
        val commandTemplate = AgentRuntimeFixtures.frozenCommandFor(invocationId = "invocation-fallback")
        val approval = AgentRuntimeFixtures.pendingApprovalFor(
            commandTemplate,
            approvalId = "approval-fallback"
        )
        executor.enqueue(AgentRuntimeFixtures.waitingApproval(AgentRuntimeFixtures.run(), approval))
        val handoff = InMemoryAgentApprovedDispatchHandoff()
        val sink = AgentApprovedDispatchSink {
            AgentApprovedDispatchOffer.NotAccepted(
                "outbox_loop_not_running",
                "fixture: no live consumer"
            )
        }
        val harness = AgentOwnerHarness(
            scope = backgroundScope,
            executor = executor,
            dispatchHandoff = handoff,
            dispatchSink = sink
        )
        val task = harness.createTask()
        val runId = (harness.start(task) as AgentCommandResult.Accepted).runId!!
        val command = commandTemplate.copy(runId = runId)
        harness.seedFrozenInvocation(command)
        runCurrent()
        val waiting = harness.run(runId)!!

        val resolved = harness.owner.resolveApproval(
            ResolveAgentApproval(
                commandId = "cmd-outbox-fallback",
                runId = runId,
                expectedRunRevision = waiting.revision,
                approvalId = approval.approvalId,
                decision = AgentApprovalDecision.ALLOW_ONCE
            )
        )

        assertTrue(resolved is AgentCommandResult.Accepted)
        runCurrent()
        assertEquals("the owner fallback still executes the approved resume", 2, executor.executeCount)
        assertNull("the unmatched hand-off must not stay claimable", handoff.claim(command.invocationId))
    }

    @Test
    fun dispatchWithoutAHandOffIsRefusedWithZeroSideEffects() = runTest {
        val executor = ScriptedRunExecutor()
        val commandTemplate = AgentRuntimeFixtures.frozenCommandFor(invocationId = "invocation-refused")
        val approval = AgentRuntimeFixtures.pendingApprovalFor(commandTemplate, approvalId = "approval-refused")
        executor.enqueue(AgentRuntimeFixtures.waitingApproval(AgentRuntimeFixtures.run(), approval))
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val task = harness.createTask()
        val runId = (harness.start(task) as AgentCommandResult.Accepted).runId!!
        val command = commandTemplate.copy(runId = runId)
        harness.seedFrozenInvocation(command)
        runCurrent()
        val invocation = harness.journal.readInvocation(command.invocationId)!!
        val entry = AgentDispatchOutboxEntry.approved(
            invocation.copy(status = ToolInvocationStatus.APPROVED),
            1_000L
        )

        val outcome = harness.owner.dispatchApproved(entry)

        assertTrue(outcome is AgentDispatchExecution.Refused)
        assertEquals(AgentErrorCodes.INTERNAL_INVARIANT, (outcome as AgentDispatchExecution.Refused).errorCode)
        runCurrent()
        assertEquals(1, executor.executeCount)
    }
}
