package com.muyuchat.core.video.contract

/**
 * ComfyUI API-format import results (proposal §10, V2-01/V2-02).
 *
 * Importing never grants execution rights by itself: [VideoImportPreview.Ready.runnable] is true
 * only when every executable node was lowered with a proven, explicitly recorded mapping and the
 * model roles bind to the pinned baseline manifest. Anything weaker stays a saved-but-not-runnable
 * revision with diagnostics that name the real node/port/path. Raw source is kept by the caller as
 * a source reference; it is never executed.
 */
enum class VideoImportSeverity { INFO, WARNING, ERROR }

data class VideoImportDiagnostic(
    val severity: VideoImportSeverity,
    val nodeId: String?,
    val path: String?,
    val message: String,
)

sealed class VideoImportPreview {
    data class Ready(
        val document: VideoWorkflowDocument,
        val runnable: Boolean,
        val diagnostics: List<VideoImportDiagnostic>,
    ) : VideoImportPreview()

    data class Rejected(
        val code: VideoErrorCode,
        val diagnostics: List<VideoImportDiagnostic>,
    ) : VideoImportPreview()
}

sealed class VideoImportSaveResult {
    data class Saved(
        val revisionId: VideoRevisionId,
        val document: VideoWorkflowDocument,
        val runnable: Boolean,
        val diagnostics: List<VideoImportDiagnostic>,
    ) : VideoImportSaveResult()

    data class Rejected(
        val code: VideoErrorCode,
        val diagnostics: List<VideoImportDiagnostic>,
    ) : VideoImportSaveResult()
}

/**
 * Import capability handed to the video feature alongside [VideoWorkflowPort]. Parse/lower
 * implementation lives in the app adapter boundary; the feature only previews diagnostics and
 * asks for an immutable revision to be saved.
 */
interface VideoImportPort {
    suspend fun previewComfyApi(json: String, fileName: String?): VideoImportPreview

    suspend fun saveComfyApiRevision(json: String, fileName: String?): VideoImportSaveResult
}
