package com.muyuchat.feature.video.workspace

import com.muyuchat.core.video.contract.VideoErrorCode
import com.muyuchat.core.video.contract.VideoImportDiagnostic
import com.muyuchat.core.video.contract.VideoRevisionId
import com.muyuchat.core.video.contract.VideoWorkflowDocument

/**
 * Observable import flow of the ComfyUI API-format entry (proposal §10, V1-05).
 *
 * The page never treats a missing or thrown result as success: every transition below is driven
 * by a real [com.muyuchat.core.video.contract.VideoImportPort] answer, and [Rejected.detail]
 * keeps local file/decoding failures visible with their original message.
 */
sealed interface ImportUiState {

    /** No import session is open. */
    data object Idle : ImportUiState

    /** File accepted by the picker; the port is still parsing/lowering it. */
    data class Previewing(val fileName: String?) : ImportUiState

    /** Parsed preview. [runnable] is the port's own verdict, never inferred by the page. */
    data class Preview(
        val fileName: String?,
        val json: String,
        val document: VideoWorkflowDocument,
        val runnable: Boolean,
        val diagnostics: List<VideoImportDiagnostic>,
    ) : ImportUiState

    /**
     * The preview/save did not produce a revision. [code] is the typed contract code when the
     * port supplied one; [detail] carries local read/decoding errors verbatim otherwise.
     */
    data class Rejected(
        val fileName: String?,
        val code: VideoErrorCode?,
        val diagnostics: List<VideoImportDiagnostic>,
        val detail: String? = null,
    ) : ImportUiState

    /** A save request is in flight for the preview carried alongside it. */
    data class Saving(
        val fileName: String?,
        val json: String,
        val document: VideoWorkflowDocument,
        val runnable: Boolean,
        val diagnostics: List<VideoImportDiagnostic>,
    ) : ImportUiState

    /** Revision persisted by the port; the returned document is already loaded into the editor. */
    data class Saved(
        val fileName: String?,
        val revisionId: VideoRevisionId,
        val runnable: Boolean,
        val diagnostics: List<VideoImportDiagnostic>,
    ) : ImportUiState
}
