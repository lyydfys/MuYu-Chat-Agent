@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.modelhub

import androidx.compose.runtime.Immutable
import com.muyuchat.core.download.DownloadStatus
import com.muyuchat.core.download.ModelScopeHubModel
import com.muyuchat.core.download.ModelScopeRecommendedModel
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.modelstore.ModelManifest

enum class ModelHubDownloadPhase(val label: String) {
    QUEUED("排队中"),
    DOWNLOADING("下载中"),
    INSTALLING("安装中"),
    INTEGRITY_CHECK("校验文件"),
    EXECUTION_CHECK("检查运行"),
    COMPLETED("已完成")
}

enum class ModelHubDownloadFailureSource(val label: String) {
    DOWNLOAD("下载"),
    INSTALL("导入"),
    INTEGRITY("文件校验"),
    EXECUTION("运行检查")
}

@Immutable
data class GenerationPresetUiItem(
    val id: String,
    val name: String,
    val summary: String,
    val boundLocalModelIds: Set<String> = emptySet(),
    val boundCloudModelIds: Set<String> = emptySet(),
    val updatedAt: Long = 0L
)

@Immutable
data class ModelHubUiState(
    val localModels: List<ModelManifest> = emptyList(),
    val mnnRuntimeAvailable: Boolean = false,
    val localImageModels: List<LocalImageModelUiItem> = emptyList(),
    val remoteFiles: List<RemoteModelFile> = emptyList(),
    val recommendedRemoteModels: List<ModelScopeRecommendedModel> = emptyList(),
    val hubModels: List<ModelScopeHubModel> = emptyList(),
    val hubQuery: String = "Qwen3.5 MNN",
    val hubPage: Int = 1,
    val hubTotalCount: Int = 0,
    val repoInput: String = "",
    val downloadTaskId: String? = null,
    val downloadFileName: String? = null,
    val downloadedBytes: Long = 0L,
    val downloadTotalBytes: Long = 0L,
    val downloadSpeedBytesPerSecond: Long = 0L,
    val downloadRemainingSeconds: Long? = null,
    val downloadStatus: DownloadStatus? = null,
    val downloadPhase: ModelHubDownloadPhase? = null,
    val downloadFailureSource: ModelHubDownloadFailureSource? = null,
    val downloadIntegrityStatus: String = "UNKNOWN",
    val downloadIntegrityMessage: String? = null,
    val downloadExecutionStatus: String = "UNKNOWN",
    val downloadExecutionMessage: String? = null,
    val importTasks: List<ModelImportTaskUi> = emptyList(),
    val deviceTotalRamBytes: Long = 0L,
    val deviceAvailableRamBytes: Long = 0L,
    val deviceAccelerationSummary: String = "",
    val deviceImagePolicy: String = "",
    val deviceImageTier: String = "",
    val deviceChipsetCode: String = "",
    val deviceSupportedAbis: List<String> = emptyList(),
    val deviceIsSnapdragon: Boolean = false,
    val qairtVerifiedLocalModelIds: Set<String> = emptySet(),
    val qairtVerifiedRecommendationIds: Set<String> = emptySet(),
    val cloudApi: CloudApiUiState = CloudApiUiState(),
    val isBusy: Boolean = false,
    val loadedModelId: String? = null,
    /**
     * Whether the local chat engine currently holds a **usable** loaded model.
     *
     * [loadedModelId] is deliberately retained as a recovery target while the isolated local text
     * worker is gone, so a card must not claim "已加载" from the id alone — the reload affordance is
     * gated on the same flag and would otherwise be unreachable.
     *
     * Defaults to true so previews and tests that do not wire the live engine keep their previous
     * behaviour; the app passes the real readiness.
     */
    val localChatEngineReady: Boolean = true,
    val statusMessage: String? = null
)

/**
 * Whether the "本地" list card may claim the model is loaded.
 *
 * Requires **both** the retained id and a live engine: after the isolated local text worker dies the
 * id stays set as a recovery target, and gating the reload affordance on the same flag is what makes
 * recovery reachable again. Pure so it can be unit-tested without Compose.
 */
internal fun localChatModelCardIsLoaded(state: ModelHubUiState, modelId: String): Boolean =
    modelId == state.loadedModelId && state.localChatEngineReady

@Immutable
data class ModelImportTaskUi(
    val id: String,
    val message: String,
    val file: String? = null,
    val bytes: Long = 0,
    val totalBytes: Long = 0,
    val active: Boolean = false,
    val resumable: Boolean = false
)

@Immutable
data class LocalImageModelUiItem(
    val id: String,
    val displayName: String,
    val runtimeLabel: String,
    val familyLabel: String,
    val fileName: String,
    val sizeBytes: Long,
    val imageSize: String,
    val componentCount: Int = 1,
    val readyForGeneration: Boolean = true,
    val readinessMessage: String? = null,
    val readinessLabel: String = "",
    val selected: Boolean = false,
    val storagePath: String = "",
    val recommendationId: String? = null,
    val verificationStatus: String = "UNKNOWN",
    val verificationMessage: String = ""
)

@Immutable
data class CloudApiUiState(
    val enabled: Boolean = false,
    val apiFormat: String = "OPENAI_COMPATIBLE",
    val availableFormats: List<Pair<String, String>> = listOf(
        "OPENAI_COMPATIBLE" to "OpenAI-compatible",
        "OPENAI_RESPONSES" to "OpenAI Responses",
        "ANTHROPIC" to "Anthropic Messages"
    ),
    val providerName: String = "OpenAI-compatible",
    val displayName: String = "自定义推理引擎",
    val baseUrl: String = "",
    val apiKey: String = "",
    val chatModel: String = "",
    val supportsVision: Boolean = false,
    val supportsTools: Boolean = false,
    val responsesReasoningEnabled: Boolean = false,
    /** False asks the gateway for one JSON body; for gateways whose SSE never terminates. */
    val streaming: Boolean = true,
    val imageApiFormat: String = "OPENAI_IMAGES",
    val availableImageFormats: List<Pair<String, String>> = listOf(
        "OPENAI_IMAGES" to "OpenAI Images",
        "DASHSCOPE_IMAGE" to "DashScope Image",
        "CUSTOM_PATH" to "Custom Image Path"
    ),
    val imageModel: String = "",
    val imageSize: String = "1024x1024",
    val imageEndpointPath: String = "images/generations",
    val imageModelPresets: List<String> = emptyList(),
    val imageSizePresets: List<String> = listOf("1024x1024", "1024x1536", "1536x1024"),
    val providerPresets: List<CloudProviderPresetUi> = emptyList(),
    val connectedModels: List<CloudModelUiItem> = emptyList(),
    val selected: Boolean = false,
    val configured: Boolean = false,
    val imageConfigured: Boolean = false,
    val imageSupported: Boolean = true
)

@Immutable
data class CloudProviderPresetUi(
    val key: String,
    val title: String,
    val subtitle: String
)

data class CloudModelUiItem(
    val id: String,
    val kind: String,
    val displayName: String,
    val providerName: String,
    val protocolLabel: String,
    val modelName: String,
    val baseUrl: String,
    val supportsVision: Boolean = false,
    val supportsTools: Boolean = false,
    val imageSize: String = "",
    val selected: Boolean = false
)
