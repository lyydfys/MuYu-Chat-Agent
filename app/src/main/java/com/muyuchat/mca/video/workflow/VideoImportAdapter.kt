package com.muyuchat.mca.video.workflow

import com.muyuchat.core.video.contract.VideoErrorCode
import com.muyuchat.core.video.contract.VideoImportPort
import com.muyuchat.core.video.contract.VideoImportPreview
import com.muyuchat.core.video.contract.VideoImportSaveResult
import com.muyuchat.mca.video.data.VideoRunStore
import com.muyuchat.mca.video.workflow.importer.ComfyWorkflowLowerer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App-side import capability (V2-01/V2-02) handed to the video feature.
 *
 * The parser/lowerer stay in the implementation boundary; the feature only previews diagnostics
 * and asks for an immutable revision. The raw API-format JSON is stored as the revision's source
 * reference (excluded from the executable digest) so a later explicit remote submit can use the
 * exact imported graph. Import never executes anything and never grants runnability by itself.
 */
class VideoImportAdapter(
    private val store: VideoRunStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : VideoImportPort {

    override suspend fun previewComfyApi(json: String, fileName: String?): VideoImportPreview =
        withContext(Dispatchers.Default) {
            if (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
                return@withContext VideoImportPreview.Rejected(
                    VideoErrorCode.INPUT_TOO_LARGE,
                    listOf(
                        com.muyuchat.core.video.contract.VideoImportDiagnostic(
                            severity = com.muyuchat.core.video.contract.VideoImportSeverity.ERROR,
                            nodeId = null,
                            path = "file",
                            message = "导入文件超过 4 MiB 上限",
                        ),
                    ),
                )
            }
            ComfyWorkflowLowerer.lower(json = json, fileName = fileName, rawSourceRef = json)
        }

    override suspend fun saveComfyApiRevision(json: String, fileName: String?): VideoImportSaveResult {
        return when (val preview = previewComfyApi(json, fileName)) {
            is VideoImportPreview.Rejected -> VideoImportSaveResult.Rejected(preview.code, preview.diagnostics)
            is VideoImportPreview.Ready -> {
                val saved = withContext(Dispatchers.IO) {
                    store.saveRevision(preview.document, clock())
                }
                if (saved == null) {
                    VideoImportSaveResult.Rejected(
                        VideoErrorCode.WORKFLOW_INVALID,
                        preview.diagnostics + com.muyuchat.core.video.contract.VideoImportDiagnostic(
                            severity = com.muyuchat.core.video.contract.VideoImportSeverity.ERROR,
                            nodeId = null,
                            path = "revisionId",
                            message = "同名修订已存在且内容不同，请修改文件名或源图后再导入",
                        ),
                    )
                } else {
                    VideoImportSaveResult.Saved(
                        revisionId = com.muyuchat.core.video.contract.VideoRevisionId(saved),
                        document = preview.document,
                        runnable = preview.runnable,
                        diagnostics = preview.diagnostics,
                    )
                }
            }
        }
    }

    private companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
