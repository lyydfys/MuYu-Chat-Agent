package com.muyuchat.feature.agent.workspace

/**
 * Feature-local Skill administration port.
 *
 * The workspace page never sees an Android `Uri`, a `File`, an engine or a persistence class. The
 * Lead adapts this surface to `FileAgentSkillStore` / `AgentSkillManager` and to the SAF
 * `OpenDocument` (ZIP) / `OpenDocumentTree` (directory) launchers. Every outcome is a plain value:
 * no exception message is ever shown to the user; only [AgentSkillAdminOutcome.Rejected.safeMessage]
 * and [AgentSkillAdminListResult.Rejected.safeMessage] may be rendered.
 */

object AgentSkillAdminLimits {
    /** Frozen V1 cap (architecture 12.1). Enforced by the page and again by the manager. */
    const val MAX_ENABLED_SKILLS: Int = 4
}

/** One installed Skill as the page needs it; no tool-service or manifest internals leak in. */
data class AgentSkillAdminItem(
    val skillId: String,
    val name: String,
    val description: String = "",
    val version: Long,
    val packageVersion: String = "",
    val enabled: Boolean,
    val allowedToolCount: Int
) {
    init {
        require(skillId.isNotBlank()) { "skillId must not be blank" }
        require(name.isNotBlank()) { "skill name must not be blank" }
        require(version >= 1L) { "skill version must be positive" }
        require(allowedToolCount >= 0) { "allowedToolCount must not be negative" }
    }
}

sealed interface AgentSkillAdminListResult {
    data class Loaded(val items: List<AgentSkillAdminItem>) : AgentSkillAdminListResult

    data class Rejected(val code: String, val safeMessage: String) : AgentSkillAdminListResult {
        init {
            require(code.isNotBlank()) { "list rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "list rejection message must not be blank" }
        }
    }
}

/** Typed operation outcomes. [Rejected] is a normal value, not an exception. */
sealed interface AgentSkillAdminOutcome {
    data class Imported(val item: AgentSkillAdminItem) : AgentSkillAdminOutcome

    data class Updated(val item: AgentSkillAdminItem) : AgentSkillAdminOutcome

    data class Enabled(val item: AgentSkillAdminItem) : AgentSkillAdminOutcome

    data class Disabled(val item: AgentSkillAdminItem) : AgentSkillAdminOutcome

    data class Uninstalled(val skillId: String, val name: String) : AgentSkillAdminOutcome {
        init {
            require(skillId.isNotBlank()) { "uninstalled skillId must not be blank" }
            require(name.isNotBlank()) { "uninstalled name must not be blank" }
        }
    }

    data class Rejected(val code: String, val safeMessage: String) : AgentSkillAdminOutcome {
        init {
            require(code.isNotBlank()) { "rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "rejection message must not be blank" }
        }
    }
}

/** Narrow command/query surface the Lead wires to K12's store and manager. */
interface AgentSkillAdminPort {
    suspend fun list(): AgentSkillAdminListResult

    /** `source` is the user-picked ZIP document; validation/limits happen behind the port. */
    suspend fun importZip(source: AgentDocumentRef): AgentSkillAdminOutcome

    /** `source` is the user-picked directory tree; the host adapts the tree to the K12 adapter. */
    suspend fun importTree(source: AgentDocumentRef): AgentSkillAdminOutcome

    suspend fun setEnabled(skillId: String, enabled: Boolean): AgentSkillAdminOutcome

    suspend fun uninstall(skillId: String): AgentSkillAdminOutcome

    companion object {
        /** Preview/test fallback only; production wiring must provide a real port. */
        val Unavailable: AgentSkillAdminPort = object : AgentSkillAdminPort {
            override suspend fun list(): AgentSkillAdminListResult = rejectedList()

            override suspend fun importZip(source: AgentDocumentRef): AgentSkillAdminOutcome =
                rejected()

            override suspend fun importTree(source: AgentDocumentRef): AgentSkillAdminOutcome =
                rejected()

            override suspend fun setEnabled(
                skillId: String,
                enabled: Boolean
            ): AgentSkillAdminOutcome = rejected()

            override suspend fun uninstall(skillId: String): AgentSkillAdminOutcome = rejected()

            private fun rejected() = AgentSkillAdminOutcome.Rejected(
                code = "SKILL_ADMIN_UNAVAILABLE",
                safeMessage = "Skills 管理尚未接入。"
            )

            private fun rejectedList() = AgentSkillAdminListResult.Rejected(
                code = "SKILL_ADMIN_UNAVAILABLE",
                safeMessage = "Skills 管理尚未接入。"
            )
        }
    }
}
