package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRunState

/**
 * Events that may change a run state. The table below mirrors architecture 7.3 exactly; adding a
 * new edge requires the architecture table to change first.
 *
 * Terminal states (SUCCEEDED/FAILED/CANCELLED/SUPERSEDED) have no outgoing edge at all. Continue
 * never revives an old run: it creates a successor and marks the old run SUPERSEDED.
 */
enum class AgentRunEventType {
    START,
    RESOURCE_BUSY,
    RESOURCE_ACQUIRED,
    RESOURCE_WAIT_TIMEOUT,
    APPROVAL_REQUIRED,
    APPROVAL_RESOLVED,
    APPROVAL_EXPIRED,
    ASK_USER,
    USER_ANSWERED,
    PAUSE_REQUESTED,
    PAUSE_SETTLED,
    CANCEL_REQUESTED,
    STOP_SETTLED,
    FINAL_ACCEPTED,
    RECOVERABLE_PAUSE,
    TOOL_RESULT_UNKNOWN,
    FAIL,
    PROCESS_INTERRUPTED,
    RECOVERY_PAUSED,
    RECOVERY_NEEDS_REVIEW,
    CONTINUE_SUPERSEDE
}

data class AgentRunEvent(
    val type: AgentRunEventType,
    val pauseReason: AgentPauseReason = AgentPauseReason.NONE
)

sealed interface AgentRunStateDecision {
    data class Allowed(
        val next: AgentRunState,
        val pauseReason: AgentPauseReason
    ) : AgentRunStateDecision

    data class Rejected(
        val errorCode: String,
        val safeMessage: String
    ) : AgentRunStateDecision
}

/** Pure state machine. It performs no I/O and never holds a lock. */
object AgentRunStateMachine {

    private val ACTIVE_OR_WAITING = setOf(
        AgentRunState.RUNNING,
        AgentRunState.WAITING_RESOURCE,
        AgentRunState.WAITING_APPROVAL,
        AgentRunState.WAITING_USER
    )

    private val RECOVERABLE_PAUSE_REASONS = setOf(
        AgentPauseReason.MODEL_PROTOCOL_ERROR,
        AgentPauseReason.MODEL_BINDING_CHANGED,
        AgentPauseReason.CONTEXT_LIMIT,
        AgentPauseReason.PERMISSION_DENIED_LOOP,
        AgentPauseReason.BUDGET_LIMIT,
        AgentPauseReason.FOREGROUND_SERVICE_UNAVAILABLE,
        AgentPauseReason.USER_REQUESTED,
        AgentPauseReason.UNKNOWN
    )

    private val APPROVAL_PAUSE_REASONS = setOf(
        AgentPauseReason.APPROVAL_EXPIRED,
        AgentPauseReason.APPROVAL_STALE
    )

    fun evaluate(from: AgentRunState, event: AgentRunEvent): AgentRunStateDecision {
        if (from.isTerminal) {
            return rejected(
                AgentErrorCodes.COMMAND_INVALID_STATE,
                "A terminal run (${from.name}) cannot transition to ${event.type.name}."
            )
        }
        return when (event.type) {
            AgentRunEventType.START -> onlyFrom(from, AgentRunState.QUEUED, AgentRunState.RUNNING)
            AgentRunEventType.RESOURCE_BUSY -> onlyFrom(from, AgentRunState.RUNNING, AgentRunState.WAITING_RESOURCE)
            AgentRunEventType.RESOURCE_ACQUIRED -> onlyFrom(from, AgentRunState.WAITING_RESOURCE, AgentRunState.RUNNING)
            AgentRunEventType.RESOURCE_WAIT_TIMEOUT -> if (from == AgentRunState.WAITING_RESOURCE) {
                allowed(AgentRunState.PAUSED, AgentPauseReason.RESOURCE_BUSY)
            } else {
                wrongState(from, event)
            }
            AgentRunEventType.APPROVAL_REQUIRED ->
                onlyFrom(from, AgentRunState.RUNNING, AgentRunState.WAITING_APPROVAL)
            AgentRunEventType.APPROVAL_RESOLVED ->
                onlyFrom(from, AgentRunState.WAITING_APPROVAL, AgentRunState.RUNNING)
            AgentRunEventType.APPROVAL_EXPIRED -> if (from == AgentRunState.WAITING_APPROVAL) {
                val reason = if (event.pauseReason in APPROVAL_PAUSE_REASONS) {
                    event.pauseReason
                } else {
                    AgentPauseReason.APPROVAL_EXPIRED
                }
                allowed(AgentRunState.PAUSED, reason)
            } else {
                wrongState(from, event)
            }
            AgentRunEventType.ASK_USER -> onlyFrom(from, AgentRunState.RUNNING, AgentRunState.WAITING_USER)
            AgentRunEventType.USER_ANSWERED ->
                onlyFrom(from, AgentRunState.WAITING_USER, AgentRunState.RUNNING)
            AgentRunEventType.PAUSE_REQUESTED -> if (from in ACTIVE_OR_WAITING) {
                allowed(AgentRunState.PAUSING, AgentPauseReason.NONE)
            } else {
                wrongState(from, event)
            }
            AgentRunEventType.PAUSE_SETTLED -> if (from == AgentRunState.PAUSING) {
                val reason = when {
                    event.pauseReason == AgentPauseReason.NONE -> AgentPauseReason.USER_REQUESTED
                    event.pauseReason in RECOVERABLE_PAUSE_REASONS -> event.pauseReason
                    else -> AgentPauseReason.USER_REQUESTED
                }
                allowed(AgentRunState.PAUSED, reason)
            } else {
                wrongState(from, event)
            }
            AgentRunEventType.CANCEL_REQUESTED -> when (from) {
                AgentRunState.QUEUED -> allowed(AgentRunState.CANCELLED, AgentPauseReason.NONE)
                AgentRunState.RUNNING,
                AgentRunState.WAITING_RESOURCE,
                AgentRunState.WAITING_APPROVAL,
                AgentRunState.WAITING_USER,
                AgentRunState.PAUSING,
                AgentRunState.PAUSED,
                AgentRunState.NEEDS_REVIEW,
                AgentRunState.INTERRUPTED -> allowed(AgentRunState.STOPPING, AgentPauseReason.NONE)
                else -> wrongState(from, event)
            }
            AgentRunEventType.STOP_SETTLED -> onlyFrom(from, AgentRunState.STOPPING, AgentRunState.CANCELLED)
            AgentRunEventType.FINAL_ACCEPTED -> onlyFrom(from, AgentRunState.RUNNING, AgentRunState.SUCCEEDED)
            AgentRunEventType.RECOVERABLE_PAUSE -> if (from == AgentRunState.RUNNING) {
                if (event.pauseReason in RECOVERABLE_PAUSE_REASONS) {
                    allowed(AgentRunState.PAUSED, event.pauseReason)
                } else {
                    rejected(
                        AgentErrorCodes.COMMAND_INVALID_STATE,
                        "RECOVERABLE_PAUSE needs a recoverable pause reason, got ${event.pauseReason.name}."
                    )
                }
            } else {
                wrongState(from, event)
            }
            AgentRunEventType.TOOL_RESULT_UNKNOWN -> when (from) {
                AgentRunState.RUNNING,
                AgentRunState.STOPPING -> allowed(AgentRunState.NEEDS_REVIEW, AgentPauseReason.UNKNOWN)
                else -> wrongState(from, event)
            }
            AgentRunEventType.FAIL -> when (from) {
                AgentRunState.QUEUED,
                AgentRunState.RUNNING,
                AgentRunState.WAITING_RESOURCE,
                AgentRunState.WAITING_APPROVAL,
                AgentRunState.WAITING_USER,
                AgentRunState.PAUSING -> allowed(AgentRunState.FAILED, AgentPauseReason.NONE)
                else -> wrongState(from, event)
            }
            AgentRunEventType.PROCESS_INTERRUPTED -> if (!from.isTerminal) {
                allowed(AgentRunState.INTERRUPTED, AgentPauseReason.INTERRUPTED)
            } else {
                wrongState(from, event)
            }
            AgentRunEventType.RECOVERY_PAUSED -> if (from == AgentRunState.INTERRUPTED) {
                val reason = if (event.pauseReason == AgentPauseReason.NONE) {
                    AgentPauseReason.INTERRUPTED
                } else {
                    event.pauseReason
                }
                allowed(AgentRunState.PAUSED, reason)
            } else {
                wrongState(from, event)
            }
            AgentRunEventType.RECOVERY_NEEDS_REVIEW -> onlyFrom(
                from,
                AgentRunState.INTERRUPTED,
                AgentRunState.NEEDS_REVIEW
            )
            AgentRunEventType.CONTINUE_SUPERSEDE -> when (from) {
                AgentRunState.PAUSED,
                AgentRunState.NEEDS_REVIEW -> allowed(AgentRunState.SUPERSEDED, AgentPauseReason.NONE)
                else -> wrongState(from, event)
            }
        }
    }

    fun canTransition(from: AgentRunState, to: AgentRunState): Boolean =
        from.let { state ->
            AgentRunEventType.entries.any { type ->
                val decision = evaluate(state, AgentRunEvent(type))
                decision is AgentRunStateDecision.Allowed && decision.next == to
            }
        }

    private fun onlyFrom(
        from: AgentRunState,
        expected: AgentRunState,
        next: AgentRunState
    ): AgentRunStateDecision = if (from == expected) {
        allowed(next, AgentPauseReason.NONE)
    } else {
        rejected(
            AgentErrorCodes.COMMAND_INVALID_STATE,
            "Event is only valid from ${expected.name}, current state is ${from.name}."
        )
    }

    private fun wrongState(
        from: AgentRunState,
        event: AgentRunEvent
    ): AgentRunStateDecision = rejected(
        AgentErrorCodes.COMMAND_INVALID_STATE,
        "Event ${event.type.name} is not valid from ${from.name}."
    )

    private fun allowed(next: AgentRunState, reason: AgentPauseReason): AgentRunStateDecision =
        AgentRunStateDecision.Allowed(next, reason)

    private fun rejected(code: String, message: String): AgentRunStateDecision =
        AgentRunStateDecision.Rejected(code, message)
}
