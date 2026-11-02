package com.muyuchat.mca.agent.extensions.skills

import java.util.Collections
import java.util.TreeSet

/**
 * One `allowed-tools` declaration.
 *
 * V1 accepts exactly: a full dotted tool key, `workspace.*`, `artifact.*`, or
 * `mcp:<serverId>:*`. Regexes and arbitrary globs are rejected rather than interpreted.
 */
sealed class AgentSkillToolPattern {
    abstract val canonical: String

    abstract fun matches(toolKey: String): Boolean

    data class Exact(val toolKey: String) : AgentSkillToolPattern() {
        init {
            require(TOOL_KEY_PATTERN.matches(toolKey)) {
                "exact allowed-tools entry must be a dotted lowercase tool key"
            }
        }

        override val canonical: String get() = toolKey

        override fun matches(toolKey: String): Boolean = this.toolKey == toolKey
    }

    data object WorkspaceNamespace : AgentSkillToolPattern() {
        override val canonical: String get() = "workspace.*"

        override fun matches(toolKey: String): Boolean =
            toolKey.length > "workspace.".length && toolKey.startsWith("workspace.")
    }

    data object ArtifactNamespace : AgentSkillToolPattern() {
        override val canonical: String get() = "artifact.*"

        override fun matches(toolKey: String): Boolean =
            toolKey.length > "artifact.".length && toolKey.startsWith("artifact.")
    }

    data class McpServer(val serverId: String) : AgentSkillToolPattern() {
        init {
            require(SERVER_ID_PATTERN.matches(serverId)) { "MCP server id is not a stable identifier" }
        }

        override val canonical: String get() = "mcp:$serverId:*"

        override fun matches(toolKey: String): Boolean {
            val prefix = "mcp:$serverId:"
            return toolKey.length > prefix.length && toolKey.startsWith(prefix)
        }
    }

    companion object {
        private val TOOL_KEY_PATTERN = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
        private val SERVER_ID_PATTERN = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")

        fun parse(raw: String): AgentSkillToolPattern {
            val value = raw.trim()
            if (value.isEmpty()) {
                skillFailure(AgentSkillErrorCode.ALLOWED_TOOLS_INVALID, "allowed-tools entry must not be blank")
            }
            if (value == "workspace.*") return WorkspaceNamespace
            if (value == "artifact.*") return ArtifactNamespace
            if (value.startsWith("mcp:")) {
                if (!value.endsWith(":*")) {
                    skillFailure(
                        AgentSkillErrorCode.ALLOWED_TOOLS_INVALID,
                        "MCP allowed-tools entries must use mcp:<serverId>:* (got '$value')"
                    )
                }
                val serverId = value.removePrefix("mcp:").removeSuffix(":*")
                if (!SERVER_ID_PATTERN.matches(serverId)) {
                    skillFailure(
                        AgentSkillErrorCode.ALLOWED_TOOLS_INVALID,
                        "MCP allowed-tools entry has an invalid server id: '$value'"
                    )
                }
                return McpServer(serverId)
            }
            if (value.contains('*') || value.contains('?') || value.contains('[') || value.contains(']')) {
                skillFailure(
                    AgentSkillErrorCode.ALLOWED_TOOLS_INVALID,
                    "allowed-tools does not support regex or globs: '$value'"
                )
            }
            if (!TOOL_KEY_PATTERN.matches(value)) {
                skillFailure(
                    AgentSkillErrorCode.ALLOWED_TOOLS_INVALID,
                    "allowed-tools entry must be a full tool key or workspace.*/artifact.*/mcp:<serverId>:*: '$value'"
                )
            }
            return Exact(value)
        }
    }
}

/** Allowed-tools declaration of one Skill. An empty list means "no additional restriction". */
class AgentSkillToolDeclaration(patternValues: List<String>) {
    val patterns: List<AgentSkillToolPattern>

    init {
        if (patternValues.size > AgentSkillLimits.MAX_TOOL_PATTERNS) {
            skillFailure(
                AgentSkillErrorCode.ALLOWED_TOOLS_INVALID,
                "allowed-tools exceeds ${AgentSkillLimits.MAX_TOOL_PATTERNS} entries"
            )
        }
        val parsed = patternValues.map(AgentSkillToolPattern::parse)
        val canonical = parsed.map { it.canonical }
        if (canonical.distinct().size != canonical.size) {
            skillFailure(AgentSkillErrorCode.ALLOWED_TOOLS_INVALID, "allowed-tools contains duplicate entries")
        }
        patterns = Collections.unmodifiableList(ArrayList(parsed))
    }

    val isEmpty: Boolean get() = patterns.isEmpty()

    fun allows(toolKey: String): Boolean = patterns.any { it.matches(toolKey) }

    companion object {
        val UNRESTRICTED = AgentSkillToolDeclaration(emptyList())
    }
}

/** Creates the intersection of every enabled Skill's declaration. Declarations can only narrow. */
object AgentSkillToolScope {
    fun effectiveToolKeys(
        skills: List<AgentSkillSnapshotSkill>,
        availableToolKeys: Set<String>
    ): Set<String> = effectiveToolKeysFromDeclarations(
        declarations = skills.map { skill ->
            skill.declaredToolPatterns.map { it.canonical }
        },
        availableToolKeys = availableToolKeys
    )

    /**
     * Pure intersection used by the manager and by tests. Empty declarations mean "no additional
     * restriction"; any non-empty declaration filters the available set. Declarations never union.
     * Distinct JVM name because `List<Skill>` and `List<List<String>>` have the same erasure.
     */
    fun effectiveToolKeysFromDeclarations(
        declarations: List<List<String>>,
        availableToolKeys: Set<String>
    ): Set<String> {
        val constrained = declarations
            .filter { it.isNotEmpty() }
            .map { AgentSkillToolDeclaration(it) }
        if (constrained.isEmpty()) return sortedSet(availableToolKeys)
        var effective: Set<String> = availableToolKeys
        for (declaration in constrained) {
            effective = effective.filterTo(LinkedHashSet()) { declaration.allows(it) }
            if (effective.isEmpty()) break
        }
        return sortedSet(effective)
    }

    private fun sortedSet(values: Set<String>): Set<String> =
        Collections.unmodifiableSet(TreeSet(values))
}

/**
 * Immutable run snapshot (architecture 12.1): Skill ids/versions/hashes and the effective tool set
 * resolved once at run start. An update/disable/uninstall cannot mutate this object.
 */
class AgentSkillSnapshot internal constructor(
    val snapshotId: String,
    skills: List<AgentSkillSnapshotSkill>,
    effectiveToolKeys: Set<String>,
    val createdAtMillis: Long,
    val registryToolKeyCount: Int
) {
    val skills: List<AgentSkillSnapshotSkill> = Collections.unmodifiableList(ArrayList(skills))
    val effectiveToolKeys: Set<String> = Collections.unmodifiableSet(TreeSet(effectiveToolKeys))

    init {
        require(snapshotId.matches(Regex("^[0-9a-f]{64}$"))) { "snapshotId must be a sha256" }
        require(this.skills.size <= AgentSkillLimits.MAX_ENABLED_SKILLS) {
            "snapshot carries more than ${AgentSkillLimits.MAX_ENABLED_SKILLS} skills"
        }
        require(this.skills.map { it.id }.distinct().size == this.skills.size) {
            "snapshot skill ids must be unique"
        }
        require(createdAtMillis >= 0L) { "createdAtMillis must not be negative" }
        require(registryToolKeyCount >= 0) { "registryToolKeyCount must not be negative" }
    }

    fun allowsTool(toolKey: String): Boolean = toolKey in effectiveToolKeys
}

/** Skill data frozen into one run snapshot. */
class AgentSkillSnapshotSkill internal constructor(
    val id: String,
    val version: Long,
    val name: String,
    val sha256: String,
    val packageVersion: String,
    val entryPath: String,
    declaredToolPatterns: List<AgentSkillToolPattern>,
    val sourceFingerprint: String
) {
    val declaredToolPatterns: List<AgentSkillToolPattern> =
        Collections.unmodifiableList(ArrayList(declaredToolPatterns))
    val toolDeclaration: AgentSkillToolDeclaration = AgentSkillToolDeclaration(
        this.declaredToolPatterns.map { it.canonical }
    )

    init {
        require(id.isNotBlank()) { "snapshot skill id must not be blank" }
        require(version >= 1L) { "snapshot skill version must be positive" }
        require(sha256.matches(Regex("^[0-9a-f]{64}$"))) { "snapshot skill sha256 must be lowercase hex" }
    }
}
