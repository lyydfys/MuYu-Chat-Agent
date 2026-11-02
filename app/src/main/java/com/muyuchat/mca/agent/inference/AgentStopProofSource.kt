package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentModelAttemptRef
import com.muyuchat.core.engine.EngineStopSettlement
import com.muyuchat.core.engine.McaInferenceService
import kotlinx.coroutines.CancellationException

/**
 * Evidence that one exact Agent model request is no longer executing.
 *
 * [Confirmed] may only be produced from [EngineStopSettlement.Released] or
 * [EngineStopSettlement.NotDispatched] for the same request id. Everything else is deliberately a
 * non-success:
 *  * a missing stop token, a facade/supervisor terminal, a terminated Flow or a dispatched stop is
 *    a lifecycle fact, never native quiescence;
 *  * a timed-out or missing engine proof keeps the resource fence ([TimedOut] / [Unknown] both turn
 *    into `AgentLeaseRelease.Blocked` in [AgentModelLeaseRetention.releaseAfterProof]).
 */
internal sealed interface AgentStopProof {
    /** The engine proved the exact request returned and its native generation is idle. */
    data class Confirmed(
        val evidence: String,
        val waitedMillis: Long
    ) : AgentStopProof {
        fun describe(): String =
            "engine stop settlement confirmed: $evidence (waitedMs=$waitedMillis)"
    }

    /** The bounded engine wait expired; stay STOPPING and keep the lease. */
    data class TimedOut(
        val waitedMillis: Long,
        val detail: String,
        val activity: String
    ) : AgentStopProof {
        fun describe(): String =
            "engine stop settlement timed out after ${waitedMillis}ms (activity=$activity): $detail"
    }

    /**
     * No usable proof exists (missing/stale record, foreign identity, read failure, or the runtime
     * cannot prove quiescence). Never a release.
     */
    data class Unknown(val reason: String) : AgentStopProof
}

/** Bounded stop-proof query; tests and the app composition inject the real engine surface. */
internal interface AgentStopProofSource {
    suspend fun awaitStopped(
        attempt: AgentModelAttemptRef,
        requestId: String,
        timeoutMillis: Long
    ): AgentStopProof
}

/**
 * Narrow, non-blocking engine capability used by the Agent adapter.
 *
 * It is exactly `McaInferenceService.awaitStopSettlement`: a bounded wait over engine-owned
 * execution records plus the runner's non-blocking native activity probe. It never takes the
 * lifecycle mutex, never dispatches a stop and never treats a missing token as proof.
 */
internal fun interface AgentStopSettlementSurface {
    suspend fun awaitStopSettlement(requestId: String, timeoutMillis: Long): EngineStopSettlement
}

internal class McaInferenceServiceStopSettlementSurface(
    private val engine: McaInferenceService
) : AgentStopSettlementSurface {
    override suspend fun awaitStopSettlement(
        requestId: String,
        timeoutMillis: Long
    ): EngineStopSettlement = engine.awaitStopSettlement(
        requestId = requestId,
        timeoutMillis = timeoutMillis
    )
}

/**
 * Maps the engine-owned typed settlement onto the Agent proof contract.
 *
 * The mapping is intentionally total and conservative:
 *  * [EngineStopSettlement.Released] / [EngineStopSettlement.NotDispatched] -> [Confirmed];
 *  * [EngineStopSettlement.TimedOut] -> [TimedOut];
 *  * [EngineStopSettlement.StoppingUnknown] / [EngineStopSettlement.Unknown] -> [Unknown].
 *
 * There is no token/facade fallback: token disappearance is not an input to this class anymore.
 */
internal class EngineAgentStopProofSource(
    private val engine: AgentStopSettlementSurface,
    private val clock: AgentInferenceClock = SystemAgentInferenceClock
) : AgentStopProofSource {
    override suspend fun awaitStopped(
        attempt: AgentModelAttemptRef,
        requestId: String,
        timeoutMillis: Long
    ): AgentStopProof {
        if (requestId.isBlank()) {
            return AgentStopProof.Unknown("attempt ${attempt.attemptId} has no engine request id")
        }
        if (timeoutMillis <= 0L) {
            return AgentStopProof.Unknown(
                "stop settlement timeout must be positive, got ${timeoutMillis}ms"
            )
        }
        val startedAt = clock.nowMillis()
        val settlement = try {
            engine.awaitStopSettlement(requestId, timeoutMillis)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // A failed proof read is missing evidence, never idle evidence.
            return AgentStopProof.Unknown(
                "engine stop settlement read failed: " +
                    (failure.message?.takeIf { it.isNotBlank() }
                        ?: failure.javaClass.simpleName).take(200)
            )
        }
        val waitedMillis = (clock.nowMillis() - startedAt).coerceAtLeast(0L)
        return settlement.toAgentStopProof(requestId, waitedMillis)
    }
}

private fun EngineStopSettlement.toAgentStopProof(
    requestId: String,
    waitedMillis: Long
): AgentStopProof = when (this) {
    is EngineStopSettlement.Released ->
        if (identity.requestId == requestId) {
            AgentStopProof.Confirmed(evidence = evidence, waitedMillis = waitedMillis)
        } else {
            AgentStopProof.Unknown(
                "engine released settlement belongs to request '${identity.requestId}', not '$requestId'"
            )
        }

    is EngineStopSettlement.NotDispatched ->
        if (identity.requestId == requestId) {
            AgentStopProof.Confirmed(
                evidence = "engine stream returned before native dispatch " +
                    "(runtime=${identity.runtime}, loadIncarnation=${identity.loadIncarnation})",
                waitedMillis = waitedMillis
            )
        } else {
            AgentStopProof.Unknown(
                "engine not-dispatched settlement belongs to request '${identity.requestId}', " +
                    "not '$requestId'"
            )
        }

    is EngineStopSettlement.TimedOut -> AgentStopProof.TimedOut(
        waitedMillis = this.waitedMillis,
        detail = detail,
        activity = activity.name
    )

    is EngineStopSettlement.StoppingUnknown -> AgentStopProof.Unknown(
        "native generation is not proven quiet (activity=${activity.name}): $detail"
    )

    is EngineStopSettlement.Unknown -> AgentStopProof.Unknown(reason)
}

/**
 * Observer for the one-shot real lease settlement of an Agent model attempt.
 *
 * [stoppedExecution] is true for a cancel/stop-driven terminal and false for an ordinary successful
 * attempt. The listener is invoked only after [AgentModelLeaseRetention] claimed/performed the
 * release, so it can never observe a lease as released before it is.
 */
internal fun interface AgentLeaseSettlementListener {
    fun onLeaseSettled(
        attempt: AgentModelAttemptRef,
        release: AgentLeaseRelease,
        stoppedExecution: Boolean
    )
}

/** Injected run-ownership check: epoch/current attempt and the user cancel flag live in the owner. */
internal enum class AgentAttemptAuthorityState {
    CURRENT,
    CANCELLED,
    SUPERSEDED
}

internal fun interface AgentAttemptAuthority {
    fun currentState(attempt: AgentModelAttemptRef): AgentAttemptAuthorityState

    companion object {
        val AlwaysCurrent = AgentAttemptAuthority { AgentAttemptAuthorityState.CURRENT }
    }
}
