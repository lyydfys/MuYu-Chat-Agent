package com.muyuchat.feature.agent.workspace

import androidx.lifecycle.SavedStateHandle

/**
 * Small Saveable owner for the home "显示已导入归档" filter.
 *
 * The callback is the view model's query-restart seam; the coordinator itself only owns the
 * persisted boolean and de-duplicates toggles. Imported archived tasks are read-only regardless of
 * this filter; it controls visibility only.
 */
internal class AgentArchivedFilterCoordinator(
    private val savedStateHandle: SavedStateHandle,
    private val onChanged: (Boolean) -> Unit
) {
    val includeArchived: Boolean
        get() = savedStateHandle.get<Boolean>(KEY_INCLUDE_ARCHIVED) ?: false

    fun toggle() {
        set(!includeArchived)
    }

    fun set(include: Boolean) {
        if (includeArchived == include) return
        savedStateHandle[KEY_INCLUDE_ARCHIVED] = include
        onChanged(include)
    }

    companion object {
        internal const val KEY_INCLUDE_ARCHIVED = "agent.workspace.includeArchived"
    }
}
