package com.muyuchat.feature.agent.workspace.memory

/**
 * Projection of one memory space for the management list.
 *
 * All values come from `AgentMemoryAdminPort.list()`; [itemsLabel] is derived from the same count so
 * the row never shows a count it did not receive.
 */
data class MemorySpaceRowUi(
    val spaceId: String,
    val displayName: String,
    val description: String,
    val itemCount: Int,
    val itemsLabel: String,
    val egressLabel: String?,
    val updatedLabel: String?
)

/** One row of the management list. The body is an excerpt; full text is loaded on demand. */
data class MemoryItemRowUi(
    val spaceId: String,
    val itemId: String,
    val title: String,
    val excerpt: String,
    val revision: Long,
    val sourceLabel: String,
    val updatedLabel: String,
    val revoked: Boolean,
    val hasSourceRef: Boolean
)

/** One reviewable extraction candidate; [errorLabel] keeps a store rejection visible. */
data class MemoryCandidateRowUi(
    val candidateId: String,
    val title: String,
    val text: String,
    val confidenceLabel: String,
    val originLabel: String,
    val duplicateLabel: String?,
    val errorLabel: String? = null
)

/** Editor buffer for one item; [expectedRevision] is the CAS token sent on save. */
data class MemoryEditorUi(
    val itemId: String,
    val title: String,
    val text: String,
    val expectedRevision: Long,
    val loading: Boolean,
    val saving: Boolean = false,
    val error: MemoryManagementErrorUi? = null
)

/** Typed, content-free failure surfaced by the page. */
data class MemoryManagementErrorUi(
    val code: String,
    val message: String
)

/**
 * Complete, immutable state of the memory management surface.
 *
 * Every branch the page can render is derivable from this value: loading (spaces/items/more/candidates),
 * empty (spaces/items/candidates), typed errors per section, one-shot notice, editor buffer and the
 * per-row in-flight marker. The state holder is the only writer.
 */
data class MemoryManagementState(
    val loadingSpaces: Boolean = false,
    val spaces: List<MemorySpaceRowUi> = emptyList(),
    val selectedSpaceId: String? = null,
    val spacesError: MemoryManagementErrorUi? = null,

    val query: String = "",
    val includeRevoked: Boolean = false,
    val loadingItems: Boolean = false,
    val loadingMore: Boolean = false,
    val items: List<MemoryItemRowUi> = emptyList(),
    val itemsTotal: Int = 0,
    val hasMoreItems: Boolean = false,
    val itemsError: MemoryManagementErrorUi? = null,

    val extractionInput: String = "",
    val submittingExtraction: Boolean = false,
    val loadingCandidates: Boolean = false,
    val candidates: List<MemoryCandidateRowUi> = emptyList(),
    val candidatesError: MemoryManagementErrorUi? = null,
    val candidatesSubmittedLabel: String? = null,

    val editor: MemoryEditorUi? = null,
    val actionInFlight: String? = null,
    val candidateActionInFlight: Boolean = false,
    val actionError: MemoryManagementErrorUi? = null,
    val notice: String? = null,
    val semanticLabel: String? = null
) {
    val selectedSpace: MemorySpaceRowUi?
        get() = spaces.firstOrNull { it.spaceId == selectedSpaceId }

    val hasSelectedSpace: Boolean get() = selectedSpace != null

    val spacesEmpty: Boolean get() = spaces.isEmpty() && !loadingSpaces && spacesError == null

    val itemsEmpty: Boolean
        get() = hasSelectedSpace && items.isEmpty() && !loadingItems && itemsError == null

    val canLoadMore: Boolean
        get() = hasSelectedSpace && hasMoreItems && !loadingItems && !loadingMore

    val candidatesEmpty: Boolean
        get() = !loadingCandidates && candidates.isEmpty() && candidatesError == null

    /** True while any operation that changes this surface is running. */
    val busy: Boolean
        get() = loadingSpaces ||
            loadingItems ||
            loadingMore ||
            submittingExtraction ||
            loadingCandidates ||
            candidateActionInFlight ||
            actionInFlight != null ||
            editor?.saving == true ||
            editor?.loading == true

    fun isSelected(spaceId: String): Boolean = selectedSpaceId == spaceId
}
