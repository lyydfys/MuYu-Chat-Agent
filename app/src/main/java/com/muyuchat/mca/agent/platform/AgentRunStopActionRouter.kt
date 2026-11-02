package com.muyuchat.mca.agent.platform

import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentRetryability

/** Result reported by the owner callback after it accepted a validated stop action. */
enum class AgentRunStopCallbackOutcome {
    ACCEPTED,
    ALREADY_STOPPING,
    REJECTED
}

data class AgentRunStopCallbackResult(
    val outcome: AgentRunStopCallbackOutcome,
    val detailRef: String? = null
)

/**
 * The owner hook. It receives only the validated runId/epoch action; it is called outside the router
 * lock and must start cancellation asynchronously (no model/network work inline).
 */
fun interface AgentRunStopActionCallback {
    fun onStopRequested(action: AgentStopAction): AgentRunStopCallbackResult

    companion object {
        val Ignore: AgentRunStopActionCallback = AgentRunStopActionCallback {
            AgentRunStopCallbackResult(AgentRunStopCallbackOutcome.REJECTED, "owner_not_connected")
        }
    }
}

enum class AgentRunStopActionRejection {
    MALFORMED,
    NOT_ACTIVE,
    DIFFERENT,
    STALE,
    CALLBACK_FAILED
}

sealed interface AgentRunStopActionRouteResult {
    data class Dispatched(
        val action: AgentStopAction,
        val callbackResult: AgentRunStopCallbackResult
    ) : AgentRunStopActionRouteResult

    data class Rejected(
        val reason: AgentRunStopActionRejection,
        val error: AgentError? = null
    ) : AgentRunStopActionRouteResult
}

/**
 * Validates the stop action carried by the foreground-service notification before it reaches the
 * Agent owner. Only the exact active `(runId, epoch)` can cancel; stale epochs and other runs are
 * rejected before the callback is invoked. The active key is supplied by [AgentRunProcessGuard]
 * (or any other authoritative owner state) so this hook never guesses from stale UI state.
 */
class AgentRunStopActionRouter(
    private val activeRunProvider: () -> AgentStopAction?,
    private val callback: AgentRunStopActionCallback = AgentRunStopActionCallback.Ignore
) {
    fun route(runId: String?, epoch: Long?): AgentRunStopActionRouteResult =
        route(AgentStopActionCodec.decode(runId, epoch))

    fun route(decoded: AgentStopActionDecodeResult): AgentRunStopActionRouteResult {
        val action = when (decoded) {
            is AgentStopActionDecodeResult.Rejected ->
                return AgentRunStopActionRouteResult.Rejected(AgentRunStopActionRejection.MALFORMED)

            is AgentStopActionDecodeResult.Decoded -> decoded.action
        }
        val active = activeRunProvider()
            ?: return AgentRunStopActionRouteResult.Rejected(AgentRunStopActionRejection.NOT_ACTIVE)
        if (active.runId != action.runId) {
            return AgentRunStopActionRouteResult.Rejected(AgentRunStopActionRejection.DIFFERENT)
        }
        if (active.epoch != action.epoch) {
            return AgentRunStopActionRouteResult.Rejected(AgentRunStopActionRejection.STALE)
        }
        return try {
            AgentRunStopActionRouteResult.Dispatched(
                action = action,
                callbackResult = callback.onStopRequested(action)
            )
        } catch (failure: Throwable) {
            AgentRunStopActionRouteResult.Rejected(
                reason = AgentRunStopActionRejection.CALLBACK_FAILED,
                error = AgentError(
                    code = AgentErrorCodes.INTERNAL_UNEXPECTED,
                    category = AgentErrorCategory.INTERNAL,
                    retryability = AgentRetryability.NEVER,
                    safeMessage = "Agent 停止操作未能交给任务负责人，任务状态未改变。",
                    correlationId = action.runId,
                    causeRef = "agent_stop_callback_failed:${failure.javaClass.simpleName}",
                    possibleSideEffects = false
                )
            )
        }
    }
}
