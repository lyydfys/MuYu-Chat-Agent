package com.muyuchat.mca.agent.memory

import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.mca.agent.tools.BuiltInAgentToolCatalog

/**
 * Catalog rule for `memory.search` (plan §7.4) plus the install seam for the composition root.
 *
 * The rule is intentionally narrow and is enforced here, not in a prompt: the tool is part of a
 * run's catalog only when the task already has a frozen, non-revoked selection **and** the run
 * policy is LOCAL_ONLY. A network-enabled run never receives `memory.search`, so memory content
 * cannot reach a cloud provider through this tool in P1. Space liveness is re-checked by the
 * executor before retrieval, so a deleted space denies instead of returning partial results.
 *
 * [withMemoryTools] keeps the existing descriptors/executors by identity, so the broker still sees
 * exactly the frozen catalog entries. Duplicate keys are rejected by the validated built-in catalog
 * rather than silently overriding a built-in.
 */
object AgentMemoryToolSet {
    /**
     * Tools for one run. Returns an empty list (never a throwing catalog) when memory must stay
     * invisible; the caller then uses its base catalog unchanged.
     */
    suspend fun forRun(
        store: AgentMemoryStore,
        grants: AgentMemoryReadGrantSource,
        taskId: String,
        networkPolicy: AgentNetworkPolicy,
        /**
         * Optional frozen `AgentRunPolicy.memorySpaceIds`. When supplied, the persisted selection
         * must match it exactly; a stale selection or a rewritten continue-policy can therefore
         * disable memory but can never widen it. Omitted (tests/library callers) keeps the
         * previous grant-only behavior.
         */
        frozenSpaceIds: Set<String>? = null
    ): List<AgentToolExecutor> {
        if (networkPolicy != AgentNetworkPolicy.LOCAL_ONLY) return emptyList()
        val grant = grants.grantForTask(taskId) ?: return emptyList()
        if (grant.revoked) return emptyList()
        if (frozenSpaceIds != null) {
            val frozen = frozenSpaceIds.map(String::trim).filter(String::isNotEmpty).toSet()
            if (frozen.isEmpty() ||
                frozen.size > AgentMemoryLimits.MAX_SELECTED_SPACES ||
                grant.spaceIds.toSet() != frozen
            ) {
                return emptyList()
            }
        }
        return listOf(MemorySearchTool(store, grants), MemoryGetTool(store, grants))
    }

    /**
     * Returns [base] unchanged when [memoryTools] is empty; otherwise a re-validated built-in catalog
     * that preserves every existing descriptor/executor pair and adds the memory tool(s).
     */
    fun withMemoryTools(
        base: AgentToolCatalog,
        memoryTools: List<AgentToolExecutor>
    ): AgentToolCatalog {
        if (memoryTools.isEmpty()) return base
        val existing = base.snapshot().map { descriptor ->
            requireNotNull(base.find(descriptor.toolKey)) {
                "the base catalog is inconsistent for ${descriptor.toolKey}"
            }
        }
        return BuiltInAgentToolCatalog(existing + memoryTools)
    }

    /** Descriptor keys the memory source contributes; used by assembly/preview surfaces. */
    fun toolKeys(): Set<String> = setOf(
        AgentMemoryToolDescriptors.TOOL_KEY,
        AgentMemoryToolDescriptors.TOOL_KEY_GET
    )
}
