package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.mca.agent.extensions.skills.AgentSkillSnapshotSkill
import com.muyuchat.mca.agent.extensions.skills.AgentSkillToolScope
import java.util.Collections

/** Where an assembled tool came from. Skills never contribute executors; they only narrow. */
enum class AgentToolSource { BUILT_IN, MCP }

/** Version + schema identity of one descriptor variant used by a conflict report. */
data class AgentToolDescriptorVariant(
    val version: String,
    val schemaHash: String
)

/**
 * Typed reason a per-run tool catalog cannot be assembled.
 *
 * A rejected assembly must not be silently replaced by a smaller/looser catalog: the caller decides
 * whether to fail the run start or present the conflict. Codes are stable and content-free.
 */
sealed interface AgentToolCatalogConflict {
    val code: String
    val subject: String

    data class DuplicateToolKey(
        val toolKey: String,
        val sources: List<AgentToolSource>
    ) : AgentToolCatalogConflict {
        override val code: String = "DUPLICATE_TOOL_KEY"
        override val subject: String get() = toolKey
    }

    data class SchemaConflict(
        val toolKey: String,
        val variants: List<AgentToolDescriptorVariant>
    ) : AgentToolCatalogConflict {
        override val code: String = "TOOL_SCHEMA_CONFLICT"
        override val subject: String get() = toolKey
    }

    data class McpToolKeyInvalid(val toolKey: String) : AgentToolCatalogConflict {
        override val code: String = "MCP_TOOL_KEY_INVALID"
        override val subject: String get() = toolKey
    }

    data class DuplicateSkillId(val skillId: String) : AgentToolCatalogConflict {
        override val code: String = "DUPLICATE_SKILL_ID"
        override val subject: String get() = skillId
    }

    data class CatalogInconsistent(val toolKey: String) : AgentToolCatalogConflict {
        override val code: String = "CATALOG_INCONSISTENT"
        override val subject: String get() = toolKey
    }
}

/** Non-fatal assembly finding that the caller must record; it never grants or widens a tool. */
sealed interface AgentToolCatalogWarning {
    val code: String

    /**
     * An enabled Skill named an exact tool or namespace that does not exist in this run's tool set.
     * The matching declaration still narrows the intersection; it can never create the tool.
     */
    data class SkillToolMissing(
        val skillId: String,
        val skillVersion: Long,
        val pattern: String
    ) : AgentToolCatalogWarning {
        override val code: String = "SKILL_MISSING_TOOL"
    }
}

sealed interface AgentToolCatalogAssemblyResult {
    /**
     * Immutable catalog for exactly this run's tool set. [effectiveToolKeys] is the Skill
     * intersection over the tools that were actually available; the catalog is already filtered to
     * that set, so the runtime/model cannot discover or execute a narrowed-out tool.
     */
    data class Assembled(
        val catalog: AgentToolCatalog,
        val effectiveToolKeys: Set<String>,
        val warnings: List<AgentToolCatalogWarning>
    ) : AgentToolCatalogAssemblyResult

    data class Rejected(val conflicts: List<AgentToolCatalogConflict>) : AgentToolCatalogAssemblyResult
}

/**
 * K15 per-run catalog assembler.
 *
 * Inputs are the frozen K08 built-in catalog, the enabled Skills frozen for the run, and the MCP
 * adapters of currently connected servers. The result is an immutable snapshot:
 *
 * - built-ins and MCP adapters are merged under their frozen `toolKey` without re-wrapping the
 *   executors (the K07 broker therefore sees exactly the descriptors/hashes that were frozen);
 * - every non-empty Skill declaration filters the available set (intersection); declarations can
 *   only remove tools, never add one;
 * - unavailable MCP servers simply contribute no adapters, removing their tools and producing a
 *   typed `SKILL_MISSING_TOOL` warning when an enabled Skill referenced them;
 * - duplicate keys, conflicting schemas, non-`mcp:` adapters in the MCP list, duplicate Skill ids
 *   and inconsistent built-in catalogs reject the whole assembly with typed conflicts;
 * - ordering is deterministic (sorted by tool key), and both the catalog and [effectiveToolKeys]
 *   are unmodifiable copies, so a later extension update cannot mutate a live run's view.
 *
 * This class is stateless and safe to call once per run start.
 */
class AgentToolCatalogAssembler {
    fun assemble(
        builtInCatalog: AgentToolCatalog,
        enabledSkills: List<AgentSkillSnapshotSkill>,
        mcpTools: List<AgentToolExecutor>
    ): AgentToolCatalogAssemblyResult {
        val conflicts = ArrayList<AgentToolCatalogConflict>()
        recordDuplicateSkills(enabledSkills, conflicts)

        val entries = ArrayList<Pair<AgentToolExecutor, AgentToolSource>>(
            builtInCatalog.snapshot().size + mcpTools.size
        )
        for (descriptor in builtInCatalog.snapshot()) {
            val executor = builtInCatalog.find(descriptor.toolKey)
            if (executor == null || executor.descriptor.toolKey != descriptor.toolKey) {
                conflicts += AgentToolCatalogConflict.CatalogInconsistent(descriptor.toolKey)
            } else {
                entries += executor to AgentToolSource.BUILT_IN
            }
        }
        for (executor in mcpTools) {
            val toolKey = executor.descriptor.toolKey
            if (!MCP_TOOL_KEY.matches(toolKey)) {
                conflicts += AgentToolCatalogConflict.McpToolKeyInvalid(toolKey)
            } else {
                entries += executor to AgentToolSource.MCP
            }
        }
        recordDuplicateToolKeys(entries, conflicts)

        if (conflicts.isNotEmpty()) {
            return AgentToolCatalogAssemblyResult.Rejected(
                conflicts.sortedWith(
                    compareBy<AgentToolCatalogConflict> { it.code }.thenBy { it.subject }
                )
            )
        }

        val available = entries.map { it.first }
        val availableKeys = available.mapTo(LinkedHashSet()) { it.descriptor.toolKey }
        val effective = AgentSkillToolScope.effectiveToolKeys(enabledSkills, availableKeys)
        val warnings = skillWarnings(enabledSkills, availableKeys)
        val selected = available.filter { it.descriptor.toolKey in effective }
        return AgentToolCatalogAssemblyResult.Assembled(
            catalog = AssembledAgentToolCatalog(selected),
            effectiveToolKeys = effective,
            warnings = warnings
        )
    }

    private fun recordDuplicateSkills(
        enabledSkills: List<AgentSkillSnapshotSkill>,
        conflicts: MutableList<AgentToolCatalogConflict>
    ) {
        enabledSkills.groupBy { it.id }
            .filterValues { group -> group.size > 1 }
            .keys
            .forEach { skillId -> conflicts += AgentToolCatalogConflict.DuplicateSkillId(skillId) }
    }

    private fun recordDuplicateToolKeys(
        entries: List<Pair<AgentToolExecutor, AgentToolSource>>,
        conflicts: MutableList<AgentToolCatalogConflict>
    ) {
        entries.groupBy { it.first.descriptor.toolKey }.forEach { (toolKey, group) ->
            if (group.size <= 1) return@forEach
            val descriptors = group.map { it.first.descriptor }
            val first = descriptors.first()
            val identical = descriptors.all {
                it.version == first.version && it.schemaHash == first.schemaHash &&
                    it.schemaJson == first.schemaJson
            }
            if (identical) {
                conflicts += AgentToolCatalogConflict.DuplicateToolKey(
                    toolKey = toolKey,
                    sources = group.map { it.second }.distinct().sortedBy { it.name }
                )
            } else {
                conflicts += AgentToolCatalogConflict.SchemaConflict(
                    toolKey = toolKey,
                    variants = descriptors
                        .map { AgentToolDescriptorVariant(it.version, it.schemaHash) }
                        .distinct()
                        .sortedWith(compareBy<AgentToolDescriptorVariant>({ it.version }, { it.schemaHash }))
                )
            }
        }
    }

    private fun skillWarnings(
        enabledSkills: List<AgentSkillSnapshotSkill>,
        availableKeys: Set<String>
    ): List<AgentToolCatalogWarning> = enabledSkills.sortedBy { it.id }.flatMap { skill ->
        skill.declaredToolPatterns
            .filter { pattern -> availableKeys.none { pattern.matches(it) } }
            .map { pattern ->
                AgentToolCatalogWarning.SkillToolMissing(
                    skillId = skill.id,
                    skillVersion = skill.version,
                    pattern = pattern.canonical
                )
            }
            .sortedBy { it.pattern }
    }

    private companion object {
        /** Mirrors the frozen MCP key shape in `AgentToolDescriptor`; built-ins use dotted keys. */
        val MCP_TOOL_KEY = Regex("^mcp:[a-z0-9][a-z0-9-]{1,63}:[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    }
}

/**
 * Immutable snapshot of exactly the executors allowed for one run.
 *
 * The constructor copies the list and sorts it, and both exposed views are unmodifiable. Executors
 * themselves are not wrapped: a frozen command approved for a built-in/MCP descriptor still maps to
 * the same descriptor/schema hash at execution time.
 */
private class AssembledAgentToolCatalog(executors: List<AgentToolExecutor>) : AgentToolCatalog {
    private val byKey: Map<String, AgentToolExecutor>
    private val descriptors: List<AgentToolDescriptor>

    init {
        val sorted = executors.sortedBy { it.descriptor.toolKey }
        val map = LinkedHashMap<String, AgentToolExecutor>(sorted.size)
        sorted.forEach { executor ->
            require(map.put(executor.descriptor.toolKey, executor) == null) {
                "assembled tool catalog contains a duplicate tool key after filtering"
            }
        }
        byKey = Collections.unmodifiableMap(map)
        descriptors = Collections.unmodifiableList(sorted.map { it.descriptor })
    }

    override fun find(toolKey: String): AgentToolExecutor? = byKey[toolKey]

    override fun snapshot(): List<AgentToolDescriptor> = descriptors
}
