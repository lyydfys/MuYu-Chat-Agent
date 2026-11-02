package com.muyuchat.feature.agent.workspace

import java.util.Locale

data class AgentMemoryErrorUi(
    val code: String,
    val message: String
)

/** Projected space row. Counts and labels come only from [AgentMemorySpaceItem]. */
data class AgentMemorySpaceUiItem(
    val spaceId: String,
    val displayName: String,
    val description: String,
    val itemCount: Int,
    val itemsLabel: String,
    val egressLabel: String?,
    val updatedLabel: String?
)

/**
 * Selection info is derived from the loaded [spaces]; no separate copy can drift from the list and
 * no memory body is available to render.
 */
data class AgentMemorySelectionUi(
    val spaceId: String,
    val displayName: String,
    val itemCount: Int,
    val itemsLabel: String,
    val description: String,
    val egressLabel: String?
)

/**
 * Memory-spaces surface state. A space list is real data from [AgentMemoryAdminPort]; rejection or
 * an empty result leaves [spaces] empty and [selectedSpace] null instead of a placeholder space.
 */
data class AgentMemoryUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val spaces: List<AgentMemorySpaceUiItem> = emptyList(),
    val selectedSpaceId: String? = null,
    val error: AgentMemoryErrorUi? = null,
    val notice: String? = null
) {
    val empty: Boolean get() = !loading && spaces.isEmpty()

    val canRefresh: Boolean get() = !loading

    val totalItemCount: Int get() = spaces.sumOf { it.itemCount }

    val selectedSpace: AgentMemorySelectionUi?
        get() = spaces.firstOrNull { it.spaceId == selectedSpaceId }?.let(::toSelection)

    fun isSelected(space: AgentMemorySpaceUiItem): Boolean = space.spaceId == selectedSpaceId
}

internal data class AgentMemoryProjectionInput(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val spaces: List<AgentMemorySpaceItem> = emptyList(),
    val selectedSpaceId: String? = null,
    val error: AgentMemoryErrorUi? = null,
    val notice: String? = null
)

internal fun projectMemoryState(input: AgentMemoryProjectionInput): AgentMemoryUiState =
    AgentMemoryUiState(
        visible = input.visible,
        loading = input.loading,
        spaces = input.spaces.map(::projectMemorySpace)
            .sortedWith(compareBy({ it.displayName.lowercase(Locale.ROOT) }, { it.spaceId })),
        selectedSpaceId = input.selectedSpaceId,
        error = input.error,
        notice = input.notice
    )

internal fun projectMemorySpace(item: AgentMemorySpaceItem): AgentMemorySpaceUiItem =
    AgentMemorySpaceUiItem(
        spaceId = item.spaceId,
        displayName = item.displayName.take(AgentMemoryLimits.MAX_DISPLAY_NAME_CHARS),
        description = item.description.take(AgentMemoryLimits.MAX_DESCRIPTION_CHARS),
        itemCount = item.itemCount,
        itemsLabel = memoryItemCountLabel(item.itemCount),
        egressLabel = item.egressLabel
            ?.takeIf { it.isNotBlank() }
            ?.take(AgentMemoryLimits.MAX_LABEL_CHARS),
        updatedLabel = item.updatedLabel
            ?.takeIf { it.isNotBlank() }
            ?.take(AgentMemoryLimits.MAX_LABEL_CHARS)
    )

private fun toSelection(space: AgentMemorySpaceUiItem): AgentMemorySelectionUi =
    AgentMemorySelectionUi(
        spaceId = space.spaceId,
        displayName = space.displayName,
        itemCount = space.itemCount,
        itemsLabel = space.itemsLabel,
        description = space.description,
        egressLabel = space.egressLabel
    )

internal fun memoryItemCountLabel(count: Int): String = when (count) {
    0 -> "0 条记忆"
    1 -> "1 条记忆"
    else -> String.format(Locale.ROOT, "%d 条记忆", count)
}
