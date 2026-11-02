package com.muyuchat.mca.agent.composition

import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.runtime.run.AgentRecordedCatalog
import com.muyuchat.core.agent.runtime.run.AgentRunToolCatalogProvider
import com.muyuchat.core.agent.runtime.run.AgentToolCatalogFiltering
import com.muyuchat.mca.agent.tools.AgentExtendedToolDescriptors
import com.muyuchat.mca.agent.tools.AgentHttpGetGate
import com.muyuchat.mca.agent.tools.AgentHttpGetGateDecision
import com.muyuchat.mca.agent.tools.AgentHttpTextClient
import com.muyuchat.mca.agent.tools.AgentHttpGetTool
import com.muyuchat.mca.agent.tools.BuiltInAgentToolCatalog
import kotlinx.coroutines.CancellationException

/**
 * Run-scoped catalog decorator for `http.get`.
 *
 * The tool is installed only when the frozen run policy explicitly opts in:
 * - `NETWORK_ENABLED` and not `offlineForced`;
 * - `enabledToolKeys` is non-null and contains `http.get` (a null/default set never enables it);
 * - the run and task identities match.
 *
 * The installed executor is bound to this exact run: a forged context from another run is denied
 * before any socket opens. Destination rules (HTTPS only, no redirects, no loopback/private hosts)
 * remain enforced by the tool itself. No origin allowlist is accepted here, so a policy can enable
 * the capability but cannot silently widen it at runtime; the gate only confirms the run identity.
 */
class AgentHttpToolCatalogProvider(
    private val baseCatalogFor: suspend (AgentRun, AgentTask) -> AgentToolCatalog,
    private val client: AgentHttpTextClient
) : AgentRunToolCatalogProvider {

    override suspend fun catalogFor(run: AgentRun, task: AgentTask): AgentToolCatalog {
        val base = baseCatalogFor(run, task)
        if (run.taskId != task.taskId) return base
        if (run.policy.networkPolicy != AgentNetworkPolicy.NETWORK_ENABLED) return base
        if (run.policy.offlineForced) return base
        val enabled = run.policy.enabledToolKeys ?: return base
        if (AgentExtendedToolDescriptors.HTTP_GET.toolKey !in enabled) return base
        val toolKey = AgentExtendedToolDescriptors.HTTP_GET.toolKey
        if (base.find(toolKey) != null) return base
        val existing = base.snapshot().map { descriptor ->
            requireNotNull(base.find(descriptor.toolKey)) {
                "the base catalog is inconsistent for ${descriptor.toolKey}"
            }
        }
        val boundRunId = run.runId
        val gate = object : AgentHttpGetGate {
            override suspend fun authorize(
                request: com.muyuchat.mca.agent.tools.AgentHttpGetAuthorization
            ): AgentHttpGetGateDecision =
                if (request.runId == boundRunId) {
                    AgentHttpGetGateDecision.Allowed
                } else {
                    AgentHttpGetGateDecision.Denied(
                        "POLICY_EGRESS_DENIED",
                        "This host call does not belong to the authorized run."
                    )
                }
        }
        return BuiltInAgentToolCatalog(existing + AgentHttpGetTool(client, gate))
    }

    override suspend fun catalogForRecorded(
        run: AgentRun,
        task: AgentTask,
        recorded: AgentRecordedCatalog
    ): AgentToolCatalog? = try {
        AgentToolCatalogFiltering.filter(catalogFor(run, task), recorded)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }
}
