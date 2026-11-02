package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** K03 architecture 7.3 state-table tests (T05/T07/T13 state-only portions). */
class AgentRunStateMachineTest {

    @Test
    fun allowedStateTransitionsMatchArchitectureTable() {
        allowed(AgentRunState.QUEUED, AgentRunEventType.START, AgentRunState.RUNNING)
        allowed(AgentRunState.RUNNING, AgentRunEventType.RESOURCE_BUSY, AgentRunState.WAITING_RESOURCE)
        allowed(AgentRunState.WAITING_RESOURCE, AgentRunEventType.RESOURCE_ACQUIRED, AgentRunState.RUNNING)
        allowed(
            AgentRunState.WAITING_RESOURCE,
            AgentRunEventType.RESOURCE_WAIT_TIMEOUT,
            AgentRunState.PAUSED,
            AgentPauseReason.RESOURCE_BUSY
        )
        allowed(AgentRunState.RUNNING, AgentRunEventType.APPROVAL_REQUIRED, AgentRunState.WAITING_APPROVAL)
        allowed(AgentRunState.WAITING_APPROVAL, AgentRunEventType.APPROVAL_RESOLVED, AgentRunState.RUNNING)
        allowed(
            AgentRunState.WAITING_APPROVAL,
            AgentRunEventType.APPROVAL_EXPIRED,
            AgentRunState.PAUSED,
            AgentPauseReason.APPROVAL_EXPIRED
        )
        allowed(AgentRunState.RUNNING, AgentRunEventType.ASK_USER, AgentRunState.WAITING_USER)
        allowed(AgentRunState.WAITING_USER, AgentRunEventType.USER_ANSWERED, AgentRunState.RUNNING)
        allowed(AgentRunState.RUNNING, AgentRunEventType.PAUSE_REQUESTED, AgentRunState.PAUSING)
        allowed(
            AgentRunState.PAUSING,
            AgentRunEvent(AgentRunEventType.PAUSE_SETTLED, AgentPauseReason.USER_REQUESTED),
            AgentRunState.PAUSED,
            AgentPauseReason.USER_REQUESTED
        )
        allowed(AgentRunState.QUEUED, AgentRunEventType.CANCEL_REQUESTED, AgentRunState.CANCELLED)
        allowed(AgentRunState.RUNNING, AgentRunEventType.CANCEL_REQUESTED, AgentRunState.STOPPING)
        allowed(AgentRunState.PAUSED, AgentRunEventType.CANCEL_REQUESTED, AgentRunState.STOPPING)
        allowed(AgentRunState.INTERRUPTED, AgentRunEventType.CANCEL_REQUESTED, AgentRunState.STOPPING)
        allowed(AgentRunState.STOPPING, AgentRunEventType.STOP_SETTLED, AgentRunState.CANCELLED)
        allowed(AgentRunState.RUNNING, AgentRunEventType.FINAL_ACCEPTED, AgentRunState.SUCCEEDED)
        allowed(
            AgentRunState.RUNNING,
            AgentRunEvent(AgentRunEventType.RECOVERABLE_PAUSE, AgentPauseReason.BUDGET_LIMIT),
            AgentRunState.PAUSED,
            AgentPauseReason.BUDGET_LIMIT
        )
        allowed(
            AgentRunState.RUNNING,
            AgentRunEventType.TOOL_RESULT_UNKNOWN,
            AgentRunState.NEEDS_REVIEW,
            AgentPauseReason.UNKNOWN
        )
        allowed(
            AgentRunState.STOPPING,
            AgentRunEventType.TOOL_RESULT_UNKNOWN,
            AgentRunState.NEEDS_REVIEW,
            AgentPauseReason.UNKNOWN
        )
        allowed(AgentRunState.RUNNING, AgentRunEventType.FAIL, AgentRunState.FAILED)
        allowed(
            AgentRunState.INTERRUPTED,
            AgentRunEventType.RECOVERY_PAUSED,
            AgentRunState.PAUSED,
            AgentPauseReason.INTERRUPTED
        )
        allowed(
            AgentRunState.INTERRUPTED,
            AgentRunEventType.RECOVERY_NEEDS_REVIEW,
            AgentRunState.NEEDS_REVIEW
        )
        allowed(AgentRunState.PAUSED, AgentRunEventType.CONTINUE_SUPERSEDE, AgentRunState.SUPERSEDED)
        allowed(
            AgentRunState.NEEDS_REVIEW,
            AgentRunEventType.CONTINUE_SUPERSEDE,
            AgentRunState.SUPERSEDED
        )
    }

    @Test
    fun processInterruptionMovesEveryNonTerminalStateToInterrupted() {
        for (state in AgentRunState.entries.filterNot { it.isTerminal }) {
            val decision = AgentRunStateMachine.evaluate(state, AgentRunEvent(AgentRunEventType.PROCESS_INTERRUPTED))
            assertTrue("$state -> INTERRUPTED", decision is AgentRunStateDecision.Allowed)
            assertEquals(AgentRunState.INTERRUPTED, (decision as AgentRunStateDecision.Allowed).next)
            assertEquals(AgentPauseReason.INTERRUPTED, decision.pauseReason)
        }
    }

    @Test
    fun terminalRunsHaveNoOutgoingTransitionAtAll() {
        val terminal = listOf(
            AgentRunState.SUCCEEDED,
            AgentRunState.FAILED,
            AgentRunState.CANCELLED,
            AgentRunState.SUPERSEDED
        )
        for (state in terminal) {
            for (event in AgentRunEventType.entries) {
                val decision = AgentRunStateMachine.evaluate(state, AgentRunEvent(event))
                assertTrue(
                    "terminal $state must reject $event",
                    decision is AgentRunStateDecision.Rejected
                )
                assertEquals(
                    com.muyuchat.core.agent.contract.AgentErrorCodes.COMMAND_INVALID_STATE,
                    (decision as AgentRunStateDecision.Rejected).errorCode
                )
            }
            assertFalse(AgentRunStateMachine.canTransition(state, AgentRunState.RUNNING))
        }
    }

    @Test
    fun pausingAndStoppingStillHoldTheActiveSlot() {
        assertTrue(AgentRunState.PAUSING.holdsActiveSlot)
        assertTrue(AgentRunState.STOPPING.holdsActiveSlot)
        assertTrue(AgentRunState.WAITING_APPROVAL.holdsActiveSlot)
        assertTrue(AgentRunState.WAITING_USER.holdsActiveSlot)
        assertFalse(AgentRunState.PAUSED.holdsActiveSlot)
        assertFalse(AgentRunState.NEEDS_REVIEW.holdsActiveSlot)
        assertFalse(AgentRunState.INTERRUPTED.holdsActiveSlot)
    }

    @Test
    fun invalidEventsAreRejectedWithTypedCommandError() {
        rejected(AgentRunState.PAUSED, AgentRunEventType.START)
        rejected(AgentRunState.PAUSED, AgentRunEventType.FINAL_ACCEPTED)
        rejected(AgentRunState.NEEDS_REVIEW, AgentRunEventType.START)
        rejected(AgentRunState.QUEUED, AgentRunEventType.APPROVAL_REQUIRED)
        rejected(AgentRunState.WAITING_USER, AgentRunEventType.APPROVAL_REQUIRED)
        rejected(AgentRunState.PAUSING, AgentRunEventType.FINAL_ACCEPTED)
        rejected(AgentRunState.PAUSED, AgentRunEventType.PAUSE_REQUESTED)
        rejected(
            AgentRunState.RUNNING,
            AgentRunEventType.RECOVERABLE_PAUSE,
            AgentRunEvent(AgentRunEventType.RECOVERABLE_PAUSE, AgentPauseReason.NONE)
        )
        assertFalse(AgentRunStateMachine.canTransition(AgentRunState.SUPERSEDED, AgentRunState.RUNNING))
    }

    @Test
    fun approvalExpiryPreservesStaleReasonAndPauseSettleDefaultsToUserRequested() {
        val stale = AgentRunStateMachine.evaluate(
            AgentRunState.WAITING_APPROVAL,
            AgentRunEvent(AgentRunEventType.APPROVAL_EXPIRED, AgentPauseReason.APPROVAL_STALE)
        ) as AgentRunStateDecision.Allowed
        assertEquals(AgentPauseReason.APPROVAL_STALE, stale.pauseReason)

        val settled = AgentRunStateMachine.evaluate(
            AgentRunState.PAUSING,
            AgentRunEvent(AgentRunEventType.PAUSE_SETTLED)
        ) as AgentRunStateDecision.Allowed
        assertEquals(AgentPauseReason.USER_REQUESTED, settled.pauseReason)

        val interruptedPaused = AgentRunStateMachine.evaluate(
            AgentRunState.INTERRUPTED,
            AgentRunEvent(AgentRunEventType.RECOVERY_PAUSED)
        ) as AgentRunStateDecision.Allowed
        assertEquals(AgentPauseReason.INTERRUPTED, interruptedPaused.pauseReason)
    }

    private fun allowed(
        from: AgentRunState,
        type: AgentRunEventType,
        to: AgentRunState,
        reason: AgentPauseReason = AgentPauseReason.NONE
    ) = allowed(from, AgentRunEvent(type), to, reason)

    private fun allowed(
        from: AgentRunState,
        event: AgentRunEvent,
        to: AgentRunState,
        reason: AgentPauseReason = AgentPauseReason.NONE
    ) {
        val decision = AgentRunStateMachine.evaluate(from, event)
        assertTrue("$from + $event must be allowed, got $decision", decision is AgentRunStateDecision.Allowed)
        decision as AgentRunStateDecision.Allowed
        assertEquals(to, decision.next)
        assertEquals(reason, decision.pauseReason)
        if (reason == AgentPauseReason.NONE) {
            assertTrue(AgentRunStateMachine.canTransition(from, to))
        }
    }

    private fun rejected(from: AgentRunState, type: AgentRunEventType) {
        rejected(from, type, AgentRunEvent(type))
    }

    private fun rejected(from: AgentRunState, type: AgentRunEventType, event: AgentRunEvent) {
        val decision = AgentRunStateMachine.evaluate(from, event)
        assertTrue("$from + $type must be rejected, got $decision", decision is AgentRunStateDecision.Rejected)
        assertEquals(
            com.muyuchat.core.agent.contract.AgentErrorCodes.COMMAND_INVALID_STATE,
            (decision as AgentRunStateDecision.Rejected).errorCode
        )
    }
}
