package com.muyuchat.feature.agent.workspace

import java.util.Locale

/** Operation currently owned by [AgentSkillsCoordinator]; at most one is in flight. */
enum class AgentSkillOperationKind { LISTING, IMPORT_ZIP, IMPORT_TREE, TOGGLE, UNINSTALL }

data class AgentSkillAdminErrorUi(
    val code: String,
    val message: String
)

/** Uninstall confirmation carries the display name so the dialog names what is removed. */
data class AgentSkillUninstallConfirmationUi(
    val skillId: String,
    val name: String
)

/** Projected row for the Skills list. Display strings are bounded in [projectSkillItem]. */
data class AgentSkillUiItem(
    val skillId: String,
    val name: String,
    val description: String,
    val versionLabel: String,
    val enabled: Boolean,
    val allowedToolsLabel: String
)

/**
 * Skills surface state. It is page-local: no Skill manifest/hash/tool object reaches the composable.
 * [enabledCount] and [limitReached] derive from [items], so a stale caller cannot display a wrong
 * "4/4" badge.
 */
data class AgentSkillsUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val items: List<AgentSkillUiItem> = emptyList(),
    val busy: AgentSkillOperationKind? = null,
    val error: AgentSkillAdminErrorUi? = null,
    val notice: String? = null,
    val uninstallConfirmation: AgentSkillUninstallConfirmationUi? = null
) {
    val enabledCount: Int get() = items.count { it.enabled }

    val limitReached: Boolean get() = enabledCount >= AgentSkillAdminLimits.MAX_ENABLED_SKILLS

    val empty: Boolean get() = !loading && items.isEmpty()

    val canImport: Boolean get() = busy == null

    fun canToggle(item: AgentSkillUiItem): Boolean =
        busy == null && (item.enabled || !limitReached)

    fun canUninstall(): Boolean = busy == null
}

internal data class AgentSkillsProjectionInput(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val items: List<AgentSkillAdminItem> = emptyList(),
    val busy: AgentSkillOperationKind? = null,
    val error: AgentSkillAdminErrorUi? = null,
    val notice: String? = null,
    val uninstallConfirmation: AgentSkillUninstallConfirmationUi? = null
)

internal fun projectSkillsState(input: AgentSkillsProjectionInput): AgentSkillsUiState =
    AgentSkillsUiState(
        visible = input.visible,
        loading = input.loading,
        items = input.items.map(::projectSkillItem),
        busy = input.busy,
        error = input.error,
        notice = input.notice,
        uninstallConfirmation = input.uninstallConfirmation
    )

internal fun projectSkillItem(item: AgentSkillAdminItem): AgentSkillUiItem = AgentSkillUiItem(
    skillId = item.skillId,
    name = item.name,
    description = item.description.take(MAX_SKILL_DESCRIPTION_CHARS),
    versionLabel = skillVersionLabel(item),
    enabled = item.enabled,
    allowedToolsLabel = skillAllowedToolsLabel(item.allowedToolCount)
)

/** Package version is the user-facing value; the MCA installation version is a fallback. */
internal fun skillVersionLabel(item: AgentSkillAdminItem): String {
    val packageVersion = item.packageVersion.trim()
    return if (packageVersion.isEmpty()) "v${item.version}" else packageVersion
}

internal fun skillAllowedToolsLabel(count: Int): String = when (count) {
    0 -> "不限制工具"
    1 -> "1 个工具"
    else -> String.format(Locale.ROOT, "%d 个工具", count)
}

private const val MAX_SKILL_DESCRIPTION_CHARS = 160
