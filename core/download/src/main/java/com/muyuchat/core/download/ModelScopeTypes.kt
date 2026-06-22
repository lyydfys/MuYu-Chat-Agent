package com.muyuchat.core.download

import java.io.File
import java.net.URLEncoder

enum class ImageEngineBundleComponentRole(val label: String) {
    DIFFUSION("diffusion 主模型"),
    VAE("VAE / AE"),
    TEXT_ENCODER("文本编码器 / LLM"),
    OPTIONAL("可选组件")
}

data class ImageEngineBundleComponentSpec(
    val role: ImageEngineBundleComponentRole,
    val repoId: String,
    val fileName: String,
    val revision: String = "main",
    val provider: ModelRepositoryProvider = ModelRepositoryProvider.HUGGING_FACE,
    val required: Boolean = true
)

data class ImageEngineBundleSpec(
    val id: String,
    val title: String,
    val components: List<ImageEngineBundleComponentSpec>
) {
    val requiredComponents: List<ImageEngineBundleComponentSpec>
        get() = components.filter { it.required }
}

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
    val bundleRole: ImageEngineBundleComponentRole? = null
) {
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

enum class RemoteModelFileKind {
    CHAT_MODEL,
    IMAGE_MODEL,
    IMAGE_VAE,
    IMAGE_TEXT_ENCODER,
    PROJECTOR,
    IMATRIX,
    SPECULATIVE,
    SPLIT_PART,
    OTHER
}

fun RemoteModelFile.fileKind(): RemoteModelFileKind {
    val lowerPath = path.lowercase()
    val lowerName = name.lowercase()
    return when {
        bundleRole == ImageEngineBundleComponentRole.DIFFUSION -> RemoteModelFileKind.IMAGE_MODEL
        bundleRole == ImageEngineBundleComponentRole.VAE -> RemoteModelFileKind.IMAGE_VAE
        bundleRole == ImageEngineBundleComponentRole.TEXT_ENCODER -> RemoteModelFileKind.IMAGE_TEXT_ENCODER
        !lowerName.hasModelFileExtension() -> RemoteModelFileKind.OTHER
        "mmproj" in lowerPath || "projector" in lowerPath -> RemoteModelFileKind.PROJECTOR
        "imatrix" in lowerPath -> RemoteModelFileKind.IMATRIX
        lowerName.startsWith("mtp-") -> RemoteModelFileKind.SPECULATIVE
        Regex("""-\d{5}-of-\d{5}\.gguf$""").containsMatchIn(lowerName) -> RemoteModelFileKind.SPLIT_PART
        looksLikeImageGenerationModel(lowerPath, lowerName) -> RemoteModelFileKind.IMAGE_MODEL
        else -> RemoteModelFileKind.CHAT_MODEL
    }
}

fun RemoteModelFile.isChatModelCandidate(): Boolean = fileKind() == RemoteModelFileKind.CHAT_MODEL

fun RemoteModelFile.isImageModelCandidate(): Boolean = fileKind() == RemoteModelFileKind.IMAGE_MODEL

fun RemoteModelFile.kindLabel(): String = when (fileKind()) {
    RemoteModelFileKind.CHAT_MODEL -> "聊天主模型"
    RemoteModelFileKind.IMAGE_MODEL -> "图像生成模型"
    RemoteModelFileKind.IMAGE_VAE -> "VAE / AE"
    RemoteModelFileKind.IMAGE_TEXT_ENCODER -> "文本编码器 / LLM"
    RemoteModelFileKind.PROJECTOR -> "视觉投影辅助文件"
    RemoteModelFileKind.IMATRIX -> "量化校准辅助文件"
    RemoteModelFileKind.SPECULATIVE -> "投机解码辅助文件"
    RemoteModelFileKind.SPLIT_PART -> "分片文件"
    RemoteModelFileKind.OTHER -> "其他文件"
}

private fun looksLikeImageGenerationModel(lowerPath: String, lowerName: String): Boolean {
    val value = "$lowerPath/$lowerName"
    return listOf(
        "z-image",
        "z_image",
        "zimage",
        "qwen-image",
        "qwen_image",
        "flux",
        "glm-image",
        "glm_image",
        "longcat-image",
        "longcat_image",
        "dreamlite",
        "dreamshaper",
        "meinamix",
        "majicmix",
        "sdxl",
        "stable-diffusion",
        "stable_diffusion",
        "diffusion",
        "text-to-image",
        "image-generation"
    ).any { it in value } &&
        listOf("clip", "t5", "text_encoder", "vae", "mmproj", "projector").none { it in lowerName }
}

private fun String.hasModelFileExtension(): Boolean =
    endsWith(".gguf") ||
        endsWith(".safetensors") ||
        endsWith(".sft") ||
        endsWith(".ckpt") ||
        endsWith(".pth") ||
        endsWith(".pt") ||
        endsWith(".onnx") ||
        endsWith(".zip")

enum class ModelScopeRecommendedKind {
    CHAT,
    IMAGE
}

enum class LocalImageEngineTier(val label: String) {
    QUICK("极速生成"),
    STANDARD("高清生成"),
    COMPACT_QUALITY("画质实验"),
    LARGE_QUALITY("备用实验"),
    HEAVY_EXPERIMENTAL("前沿观察")
}

enum class ModelScopeRecommendedGroup(val label: String) {
    LIGHT_CHAT("轻量聊天"),
    MAIN_CHAT("主力聊天"),
    QUALITY_CHAT("高质量聊天"),
    LOCAL_IMAGE("本地生图")
}

data class ModelScopeRecommendedModel(
    val id: String,
    val title: String,
    val repoId: String,
    val revision: String = "master",
    val description: String,
    val recommendedFileName: String,
    val parameterScale: String,
    val quant: String,
    val minRamGb: Int,
    val tags: List<String> = emptyList(),
    val priority: Int = 0,
    val kind: ModelScopeRecommendedKind = ModelScopeRecommendedKind.CHAT,
    val group: ModelScopeRecommendedGroup = if (kind == ModelScopeRecommendedKind.IMAGE) {
        ModelScopeRecommendedGroup.LOCAL_IMAGE
    } else {
        ModelScopeRecommendedGroup.MAIN_CHAT
    },
    val provider: ModelRepositoryProvider = ModelRepositoryProvider.MODELSCOPE,
    val downloadable: Boolean = true,
    val localImageEngineTier: LocalImageEngineTier? = null,
    val imageEngineBundle: ImageEngineBundleSpec? = null
) {
    val modelPageUrl: String
        get() = when (provider) {
            ModelRepositoryProvider.MODELSCOPE -> modelScopeModelPageUrl(repoId)
            ModelRepositoryProvider.HUGGING_FACE -> huggingFaceModelPageUrl(repoId)
        }
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

fun modelScopeModelPageUrl(repoId: String): String =
    "https://www.modelscope.cn/models/${repoId.modelScopePath()}/summary"

fun huggingFaceModelPageUrl(repoId: String): String =
    "https://huggingface.co/${repoId.modelScopePath()}"

private fun String.modelScopePath(): String =
    trim('/')
        .split('/')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
