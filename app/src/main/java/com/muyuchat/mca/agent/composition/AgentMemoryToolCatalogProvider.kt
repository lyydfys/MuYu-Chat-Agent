package com.muyuchat.mca.agent.composition

import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.runtime.run.AgentRunToolCatalogProvider
import com.muyuchat.mca.agent.memory.AgentMemoryReadGrantSource
import com.muyuchat.mca.agent.memory.AgentMemoryStore
import com.muyuchat.mca.agent.memory.AgentMemoryToolSet

/**
 * Run-scoped catalog decorator that installs `memory.search` for exactly the runs allowed to use it.
 *
 * The eligibility rule lives in [AgentMemoryToolSet.forRun] and is intentionally narrow:
 *
 * - the run policy must be `LOCAL_ONLY` (a network-enabled run never receives memory tools, so a
 *   cloud model cannot receive memory content through this tool);
 * - the task must already have a persisted frozen selection that is not revoked (space liveness is
 *   re-checked by the executor before retrieval);
 * - when the run policy carries `memorySpaceIds`, that frozen mirror must equal the persisted
 *   selection, so a rewritten continue-policy can disable memory but can never widen it.
 *
 * When eligible, [AgentMemoryToolSet.withMemoryTools] re-validates and preserves every existing
 * descriptor/executor pair by identity, adds only `memory.search`, and returns the base catalog
 * unchanged otherwise. This provider is stateless (no socket, no client, no per-run resource), so
 * the interface's default no-op [AgentRunToolCatalogProvider.release] is sufficient.
 *
 * [baseCatalogFor] is resolved once per run (N04), so the Skill snapshot behind the base catalog is
 * the one enabled when that run starts, not the one enabled when the runtime was constructed.
 *
 * It deliberately does not load MCP. MCP remains in the separate
 * [RunScopedAgentToolCatalogProvider] class, which production does not wire while its documented
 * V1.1 approval path is unimplemented.
 */
class AgentMemoryToolCatalogProvider(
    private val baseCatalogFor: suspend (AgentRun, AgentTask) -> AgentToolCatalog,
    private val store: AgentMemoryStore,
    private val selections: AgentMemoryReadGrantSource
) : AgentRunToolCatalogProvider {

    override suspend fun catalogFor(run: AgentRun, task: AgentTask): AgentToolCatalog {
        val baseCatalog = baseCatalogFor(run, task)
        // The selection is keyed by task id; a run/task mismatch must never read another task's
        // memory scope. The runtime normally guarantees this; fail closed anyway. An empty policy
        // mirror also means "no memory" regardless of any leftover selection row.
        if (run.taskId != task.taskId || run.policy.memorySpaceIds.isEmpty()) return baseCatalog
        return AgentMemoryToolSet.withMemoryTools(
            base = baseCatalog,
            memoryTools = AgentMemoryToolSet.forRun(
                store = store,
                grants = selections,
                taskId = task.taskId,
                networkPolicy = run.policy.networkPolicy,
                frozenSpaceIds = run.policy.memorySpaceIds
            )
        )
    }

    /**
     * N04 restore: filter the *current* full catalog (base + memory when eligible) down to the
     * recorded identities. A revoked selection therefore cannot restore `memory.search`, and any
     * other missing/changed tool returns null → the run keeps the drift error.
     */
    override suspend fun catalogForRecorded(
        run: AgentRun,
        task: AgentTask,
        recorded: com.muyuchat.core.agent.runtime.run.AgentRecordedCatalog
    ): AgentToolCatalog? = try {
        com.muyuchat.core.agent.runtime.run.AgentToolCatalogFiltering.filter(
            catalogFor(run, task),
            recorded
        )
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }
}
