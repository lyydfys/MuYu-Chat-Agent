package com.muyuchat.mca

import com.muyuchat.core.engine.AuthorizedPendingSignatureVerification
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.tuning.AdaptiveTuningRecommendation
import com.muyuchat.core.tuning.MeasurementEnvelope
import com.muyuchat.feature.chat.ImagePromptTokenMeasurement
import java.io.File
import java.util.LinkedHashMap

// R3/C2: private → internal：随 enqueue 体移入 ImageGenerationOwner 所需。
internal fun chatImageCommitJournalJobId(jobId: String, imageId: String): String {
    val safeImageId = imageId.filter { it.isLetterOrDigit() || it in "._:-" }
        .take(128)
        .ifBlank { "image" }
    return "${jobId.take(120)}.$safeImageId"
}

/**
 * Widened from `private` so the conversion that builds it can live in its own file; only the
 * modifier changed. The type is still not part of any public surface.
 */
internal data class CandidateCanaryResult(
    val passed: Boolean,
    val output: String,
    val stats: RuntimeStats,
    val detail: String,
    val measurement: MeasurementEnvelope,
    val safetyPassed: Boolean,
    val signatureVerification: AuthorizedPendingSignatureVerification?,
    val crashCount: Int = 0,
    val anrCount: Int = 0,
    val nativeFatalSignalCount: Int = 0,
    val testedProfileId: String? = null,
    val testedResolvedLoadSignature: String? = null,
    val testedCommittedExecutionSignature: String? = null,
    val evidenceJson: String = "{}"
)

// R3/C1: private → internal：随 captureLoadedRuntimeSnapshotFor 移入运行时缝（C1 裁定 B）。
internal data class LoadedRuntimeSnapshot(
    val model: ModelManifest,
    val profile: ModelExecutionProfile,
    val uiState: MainUiState,
    val adaptiveRecommendation: AdaptiveTuningRecommendation?
)

internal data class LocalApiApplyResult(
    val running: Boolean,
    val restEnabled: Boolean,
    val failure: Throwable? = null
)

internal val imageAttachmentRegex = Regex("""【上传图片：([^】]+)】(?:\s*\n描述：[^\n]+)?\s*\n(\S+)""")
internal val oldImagePlaceholderRegex = Regex("""\s*（当前文本模型会收到图片占位信息；完整识图能力后续接入多模态模型。）""")
internal const val FILE_ATTACHMENT_MARKER = "【上传文件："
internal const val MAX_CHAT_IMAGES_PER_MESSAGE = 4
internal const val CHAT_SEND_ATTACHMENT_IDENTITY_MAX_BYTES = 64L * 1024L * 1024L

internal data class LocalImagePromptTokenizerDescriptor(
    val modelKey: String,
    val bundleRoot: File,
    val backend: ImageTokenizerBackend,
    val route: ImagePromptTokenMeasurementRoute,
    val tokenizerJsonPath: File?,
    val bosId: Int,
    val eosId: Int,
    val padId: Int,
    val maxTokens: Int,
    val promptWeightingEnabled: Boolean,
)

/** Keeps UI-only tokenizer results bounded without ever caching an unavailable backend. */
internal class ImagePromptTokenMeasurementCache(
    private val maximumEntries: Int = 96
) {
    private val entries = object : LinkedHashMap<String, ImagePromptTokenMeasurement>(
        maximumEntries,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ImagePromptTokenMeasurement>?
        ): Boolean = size > maximumEntries
    }

    init {
        require(maximumEntries > 0)
    }

    fun get(key: String): ImagePromptTokenMeasurement? = synchronized(entries) { entries[key] }

    fun put(key: String, value: ImagePromptTokenMeasurement) {
        synchronized(entries) { entries[key] = value }
    }
}

internal sealed interface DownloadedModelRegistration {
    data class Chat(val model: ModelManifest) : DownloadedModelRegistration
    data class Image(val model: LocalImageModelRecord) : DownloadedModelRegistration
    data class VisionProjector(val model: ModelManifest, val shouldReload: Boolean) : DownloadedModelRegistration
}

internal sealed interface VisionBundleDownloadResult {
    data class ChatModel(val model: ModelManifest) : VisionBundleDownloadResult
    data class EngineBundle(
        val displayName: String,
        val bundleDir: File,
        val report: LocalVisionNpuReport
    ) : VisionBundleDownloadResult
}
