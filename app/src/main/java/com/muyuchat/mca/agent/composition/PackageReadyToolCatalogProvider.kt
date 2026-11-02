package com.muyuchat.mca.agent.composition

import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.runtime.run.AgentRecordedCatalog
import com.muyuchat.core.agent.runtime.run.AgentRunToolCatalogProvider

/**
 * Barrier in front of the run catalog chain: [awaitReady] must have committed the executable
 * package surface before a run freezes its own catalog, otherwise a cold-start run would silently
 * execute without the packages the user enabled.
 *
 * The barrier runs once per catalog resolution (the underlying snapshot returns immediately after
 * the first commit). [release] stays delegated, so the MCP grants/sessions owned by the innermost
 * provider are still revoked at the end of the run.
 */
internal class PackageReadyToolCatalogProvider(
    private val delegate: AgentRunToolCatalogProvider,
    private val awaitReady: suspend () -> Unit
) : AgentRunToolCatalogProvider {

    override suspend fun catalogFor(run: AgentRun, task: AgentTask): AgentToolCatalog {
        awaitReady()
        return delegate.catalogFor(run, task)
    }

    override suspend fun catalogForRecorded(
        run: AgentRun,
        task: AgentTask,
        recorded: AgentRecordedCatalog
    ): AgentToolCatalog? {
        awaitReady()
        return delegate.catalogForRecorded(run, task, recorded)
    }

    override suspend fun release(run: AgentRun) = delegate.release(run)
}
