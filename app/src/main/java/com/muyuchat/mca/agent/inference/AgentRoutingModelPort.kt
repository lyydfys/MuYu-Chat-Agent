package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentModelAttemptRef
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentModelPort
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentModelRequest
import com.muyuchat.core.agent.contract.AgentModelResult
import com.muyuchat.core.agent.contract.AgentStopDispatch
import java.util.concurrent.ConcurrentHashMap

/** Provider-id prefix the catalog writes for a configured cloud chat record (`cloud:<recordId>`). */
internal const val AGENT_CLOUD_PROVIDER_PREFIX: String = "cloud:"

/** Runtime prefix the catalog writes for a cloud binding (`CLOUD_<API_FORMAT>`). */
internal const val AGENT_CLOUD_RUNTIME_PREFIX: String = "CLOUD_"

/** Which concrete adapter owns one frozen Agent binding. */
internal enum class AgentModelRoute {
    LOCAL,
    CLOUD
}

/**
 * Binding-only route decision: cloud when the frozen runtime starts with `CLOUD_` or the provider
 * id starts with `cloud:`, local otherwise. Nothing here reads chat selection or store config.
 */
internal fun agentModelRouteFor(binding: AgentModelBinding): AgentModelRoute =
    if (binding.runtime.startsWith(AGENT_CLOUD_RUNTIME_PREFIX) ||
        binding.providerId.startsWith(AGENT_CLOUD_PROVIDER_PREFIX)
    ) {
        AgentModelRoute.CLOUD
    } else {
        AgentModelRoute.LOCAL
    }

/**
 * Single [AgentModelPort] over the local engine adapter and the cloud Responses adapter (P05).
 *
 * `infer` is routed from the frozen binding only, so a cloud choice created from a configured
 * `CloudModelRecord` reaches [AgentCloudModelAdapter] and a local choice keeps the exact existing
 * engine path. `requestStop` is routed by the attempt identity registered at dispatch time: the
 * delegates verify the full [AgentModelAttemptRef] anyway, and attempt ids are never reused, so a
 * stop can never reach the opposite adapter.
 *
 * The runtime contract dispatches each attempt exactly once; the map only records which adapter
 * currently owns a live attempt and is removed when `infer` returns. A stop that arrives after the
 * attempt finished is rejected (`accepted=false`) by the owning adapter, never re-routed.
 */
internal class AgentRoutingModelPort(
    private val local: AgentModelPort,
    private val cloud: AgentModelPort
) : AgentModelPort {

    private val routedAttempts = ConcurrentHashMap<String, RoutedAttempt>()

    override suspend fun infer(
        request: AgentModelRequest,
        progress: AgentModelProgressSink
    ): AgentModelResult {
        val route = agentModelRouteFor(request.binding)
        val routed = RoutedAttempt(attempt = request.attempt, route = route)
        routedAttempts[request.attempt.attemptId] = routed
        return try {
            portFor(route).infer(request, progress)
        } finally {
            routedAttempts.remove(request.attempt.attemptId, routed)
        }
    }

    override suspend fun requestStop(attempt: AgentModelAttemptRef): AgentStopDispatch {
        val routed = routedAttempts[attempt.attemptId]
            ?.takeIf { it.attempt == attempt }
            ?: return AgentStopDispatch(false, "no live Agent model attempt for this route")
        return portFor(routed.route).requestStop(attempt)
    }

    private fun portFor(route: AgentModelRoute): AgentModelPort = when (route) {
        AgentModelRoute.LOCAL -> local
        AgentModelRoute.CLOUD -> cloud
    }

    /** Identity equality is intentional: only the dispatch that registered this entry may remove it. */
    private class RoutedAttempt(
        val attempt: AgentModelAttemptRef,
        val route: AgentModelRoute
    )
}
