package com.muyuchat.core.download

import java.io.File

enum class VisionModelBundleComponentRole(val label: String) {
    MAIN_MODEL("多模态主模型"),
    PROJECTOR("视觉投影器 / mmproj"),
    TOKENIZER("Tokenizer"),
    PREPROCESSOR("图像预处理配置"),
    OPTIONAL("可选组件")
}

data class VisionModelBundleComponentSpec(
    val role: VisionModelBundleComponentRole,
    val repoId: String,
    val fileName: String,
    val revision: String = "master",
    val provider: ModelRepositoryProvider = ModelRepositoryProvider.MODELSCOPE,
    val required: Boolean = true,
    val relativePath: String = fileName.substringAfterLast('/').substringAfterLast('\\')
)

data class VisionModelBundleSpec(
    val id: String,
    val title: String,
    val components: List<VisionModelBundleComponentSpec>,
    val runtime: VisionModelBundleRuntime = VisionModelBundleRuntime.GGUF_MMPROJ,
    val accelerator: VisionModelAccelerator = VisionModelAccelerator.CPU,
    val minDeviceTier: ImageEngineMinDeviceTier = ImageEngineMinDeviceTier.ANY,
    val requiresQnnRuntime: Boolean = false,
    val requiresSmokeTest: Boolean = true,
    /**
     * Whether the recommendation card's primary download action installs every
     * required vision component. Large GGUF models may keep their projector in
     * this bundle for discovery while downloading only the chat model by
     * default; the projector remains available from the file list for an
     * explicit download-and-bind action.
     */
    val downloadProjectorByDefault: Boolean = true,
    val smokeSpec: VisionModelSmokeSpec = VisionModelSmokeSpec()
) {
    val requiredComponents: List<VisionModelBundleComponentSpec>
        get() = components.filter { it.required }

    val runtimeSummary: String
        get() = "${runtime.label} · ${accelerator.label}"
}

enum class VisionModelBundleRuntime(val label: String) {
    GGUF_MMPROJ("GGUF + mmproj"),
    MNN_MULTIMODAL("MNN 多模态"),
    LITERT_QNN("LiteRT / QNN")
}

enum class VisionModelAccelerator(val label: String) {
    CPU("CPU"),
    MNN_CPU("MNN CPU"),
    QNN_HTP("骁龙 NPU")
}

data class VisionModelSmokeSpec(
    val imageWidth: Int = 448,
    val imageHeight: Int = 448,
    val prompt: String = "请用中文描述这张图片",
    val timeoutSeconds: Int = 60
)

data class RemoteModelFile(
    val repoId: String,
    val revision: String,
    val path: String,
    val name: String,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val license: String? = null,
    val downloadUrl: String,
    val provider: ModelRepositoryProvider = ModelRepositoryProvider.MODELSCOPE,
    val mnnBundleRole: MnnModelBundleComponentRole? = null,
    val bundleRole: ImageEngineBundleComponentRole? = null,
    val visionBundleRole: VisionModelBundleComponentRole? = null,
    val relativePath: String = name
) {
    val integrityMetadataStatus: ImageEngineIntegrityMetadataStatus
        get() = integrityMetadataStatus(sizeBytes, sha256)

    val modelPageUrl: String
        get() = when (provider) {
            ModelRepositoryProvider.MODELSCOPE -> modelScopeModelPageUrl(repoId)
            ModelRepositoryProvider.HUGGING_FACE -> huggingFaceModelPageUrl(repoId)
        }
}

enum class ModelRepositoryProvider(val label: String) {
    MODELSCOPE("ModelScope"),
    HUGGING_FACE("Hugging Face")
}

data class ModelScopeHubModel(
    val id: String,
    val displayName: String,
    val description: String = "",
    val downloads: Long = 0,
    val likes: Long = 0,
    val license: String? = null,
    val tasks: List<String> = emptyList(),
    val fileSizeBytes: Long = 0,
    val params: Long = 0,
    val tags: List<String> = emptyList(),
    val private: Boolean = false,
    val gated: Boolean = false
) {
    val modelPageUrl: String
        get() = modelScopeModelPageUrl(id)
}

data class ModelScopeModelSearchResult(
    val query: String,
    val pageNumber: Int,
    val pageSize: Int,
    val totalCount: Int,
    val models: List<ModelScopeHubModel>
)

data class DownloadTaskSnapshot(
    val repoId: String,
    val revision: String,
    val fileName: String,
    val url: String,
    val etag: String? = null,
    val expectedLength: Long = 0,
    val downloadedBytes: Long = 0,
    val speedBytesPerSecond: Long = 0,
    val remainingSeconds: Long? = null,
    val errorMessage: String? = null,
    val tempFile: File,
    val finalFile: File,
    val status: DownloadStatus
)

enum class DownloadStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    FAILED,
    DONE
}

data class ImageEngineBundleComponentSpec(
    val role: ImageEngineBundleComponentRole,
    val repoId: String,
    val fileName: String,
    val revision: String = "main",
    val provider: ModelRepositoryProvider = ModelRepositoryProvider.HUGGING_FACE,
    val required: Boolean = true,
    val expectedSizeBytes: Long? = null,
    val sha256: String? = null,
    val relativePath: String = fileName.substringAfterLast('/').substringAfterLast('\\'),
    /** Optional components can be catalogued without inflating the default download plan. */
    val downloadByDefault: Boolean = true
) {
    init {
        require(!required || downloadByDefault) {
            "Required image bundle components must be included in the default download plan."
        }
    }

    val integrityMetadataStatus: ImageEngineIntegrityMetadataStatus
        get() = integrityMetadataStatus(expectedSizeBytes, sha256)
}

private fun integrityMetadataStatus(
    sizeBytes: Long?,
    sha256: String?
): ImageEngineIntegrityMetadataStatus = when {
    sha256?.matches(SHA256_HEX) == true -> ImageEngineIntegrityMetadataStatus.SOURCE_SHA256
    sizeBytes != null && sizeBytes > 0L -> ImageEngineIntegrityMetadataStatus.SOURCE_SIZE_ONLY
    else -> ImageEngineIntegrityMetadataStatus.UNKNOWN
}

private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
