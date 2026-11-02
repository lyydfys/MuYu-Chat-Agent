package com.muyuchat.mca.agent.composition

import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.runtime.run.AgentRunToolCatalogProvider
import com.muyuchat.mca.agent.extensions.skills.AgentSkillSnapshot
import com.muyuchat.mca.agent.tools.AgentToolCatalogAssembler
import com.muyuchat.mca.agent.tools.AgentToolCatalogAssemblyResult
import com.muyuchat.mca.agent.tools.AgentToolCatalogConflict

/** Typed failure raised when a run's built-in + Skill catalog cannot be assembled consistently. */
class AgentRunCatalogAssemblyException(
    val conflicts: List<AgentToolCatalogConflict>
) : IllegalStateException(
    "agent tool catalog conflict: " + conflicts.joinToString { it.code + ":" + it.subject }
)

/**
 * Resolves the built-in + enabled-Skill catalog for one run at run start (N04).
 *
 * V1 built the Skill snapshot once when the runtime was constructed, so enabling, disabling or
 * updating a Skill afterwards changed nothing until the process restarted — neither the next run
 * (TOOL-21) nor an in-flight run could be reasoned about. This provider calls [skillSnapshotSource]
 * on every [catalogFor], which means:
 *
 * - the snapshot is the set enabled at *this* run's start, with that content's SHA-256;
 * - a run that already resolved its catalog keeps that immutable instance for its whole execution
 *   (the coordinator resolves once and never re-reads);
 * - the enabled declarations can only narrow the available tool keys, because
 *   [AgentToolCatalogAssembler] intersects them with [builtInCatalog].
 *
 * MCP adapters are not added here: production does not wire MCP while its per-call approval path is
 * unimplemented (`RunScopedAgentToolCatalogProvider`, B02). Adding an extension source must not be
 * disguised as "MCP is now available".
 */
class RunScopedSkillCatalogProvider(
    private val builtInCatalog: AgentToolCatalog,
    private val skillSnapshotSource: suspend (availableToolKeys: Set<String>) -> AgentSkillSnapshot,
    private val assembler: AgentToolCatalogAssembler = AgentToolCatalogAssembler()
) : AgentRunToolCatalogProvider {

    override suspend fun catalogFor(run: AgentRun, task: AgentTask): AgentToolCatalog {
        val availableKeys = builtInCatalog.snapshot().mapTo(LinkedHashSet()) { it.toolKey }
        val snapshot = skillSnapshotSource(availableKeys)
        val mcpTools: List<AgentToolExecutor> = emptyList()
        return when (val assembled = assembler.assemble(builtInCatalog, snapshot.skills, mcpTools)) {
            is AgentToolCatalogAssemblyResult.Assembled -> assembled.catalog
            is AgentToolCatalogAssemblyResult.Rejected -> throw AgentRunCatalogAssemblyException(
                assembled.conflicts
            )
        }
    }

    /**
     * N04 restore: the current Skill set may have changed while the run was in flight. Rebuild the
     * current catalog and keep only the exact recorded `(key, version, schemaHash)` triples; any
     * missing or changed tool returns null so the coordinator keeps the fail-closed drift error.
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
