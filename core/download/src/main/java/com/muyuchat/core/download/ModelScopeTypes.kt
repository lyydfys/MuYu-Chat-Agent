package com.muyuchat.core.download

import java.io.File
import java.net.URLEncoder
import java.util.Locale

enum class RecommendedChatRuntime(val label: String) {
    MNN("MNN 高速引擎"),
    GGUF("GGUF 兼容引擎"),
    GENIEX_QAIRT("GenieX QAIRT / 骁龙 NPU")
}

enum class MnnModelBundleComponentRole(val label: String) {
    LLM_CONFIG("LLM config"),
    CONFIG("MNN 配置"),
    MODEL("MNN 主模型"),
    WEIGHT("MNN 权重"),
    TOKENIZER("Tokenizer"),
    OPTIONAL("可选组件")
}

/**
 * Declares how a published MNN bundle is materialized before it becomes a
 * managed product bundle. This is explicit repository metadata rather than a
 * model-id heuristic.
 */
enum class MnnModelBundleInstallProfile {
    STANDARD,
    TEXT_ONLY
}

data class MnnModelBundleComponentSpec(
    val role: MnnModelBundleComponentRole,
    val fileName: String,
    val required: Boolean = true,
    val relativePath: String = fileName.substringAfterLast('/').substringAfterLast('\\')
)

data class MnnModelBundleSpec(
    val id: String,
    val title: String,
    val repoId: String,
    val revision: String = "master",
    val provider: ModelRepositoryProvider = ModelRepositoryProvider.MODELSCOPE,
    val installProfile: MnnModelBundleInstallProfile = MnnModelBundleInstallProfile.STANDARD,
    val components: List<MnnModelBundleComponentSpec> = DEFAULT_COMPONENTS
) {
    val requiredComponents: List<MnnModelBundleComponentSpec>
        get() = components.filter { it.required }

    companion object {
        val DEFAULT_COMPONENTS: List<MnnModelBundleComponentSpec> = listOf(
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.CONFIG, "config.json"),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.MODEL, "llm.mnn"),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.WEIGHT, "llm.mnn.weight"),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.TOKENIZER, "tokenizer.txt"),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.LLM_CONFIG, "llm_config.json"),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.OPTIONAL, "llm.mnn.json", required = false),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.OPTIONAL, "embeddings_bf16.bin", required = false),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.OPTIONAL, "visual.mnn", required = false),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.OPTIONAL, "visual.mnn.weight", required = false)
        )
    }
}

enum class ImageEngineBundleComponentRole(val label: String) {
    CONFIG("运行配置"),
    DIFFUSION("diffusion 主模型"),
    VAE("VAE / AE"),
    TEXT_ENCODER("文本编码器 / LLM"),
    TOKENIZER("Tokenizer"),
    CONDITIONING("条件连接 / 投影"),
    OPTIONAL("可选组件")
}

/**
 * Provenance of integrity metadata supplied before a component is downloaded.
 * A locally observed digest is intentionally not represented here: it cannot
 * establish what the publisher originally released.
 */
enum class ImageEngineIntegrityMetadataStatus(val label: String) {
    SOURCE_SHA256("已提供源端 SHA-256"),
    SOURCE_SIZE_ONLY("仅提供源端大小"),
    UNKNOWN("校验元数据未知")
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
    val relativePath: String = fileName.substringAfterLast('/').substringAfterLast('\\')
) {
    val integrityMetadataStatus: ImageEngineIntegrityMetadataStatus
        get() = integrityMetadataStatus(expectedSizeBytes, sha256)
}

enum class ImageEngineTask(val label: String) {
    TEXT_TO_IMAGE("文生图"),
    IMAGE_EDIT("图像编辑")
}

enum class ImageEngineBundleRuntime(val label: String) {
    STABLE_DIFFUSION_CPP("stable-diffusion.cpp"),
    MNN_DIFFUSION("MNN Diffusion"),
    QNN_HTP("骁龙 NPU")
}

enum class ImageEngineAccelerator(val label: String) {
    CPU("CPU"),
    OPENCL_GPU("OpenCL GPU"),
    QNN_HTP("骁龙 NPU")
}

enum class ImageEngineMinDeviceTier(val label: String) {
    ANY("通用设备"),
    SNAPDRAGON_8_GEN1("骁龙 8 Gen 1+"),
    SNAPDRAGON_8_GEN2("骁龙 8 Gen 2+"),
    SNAPDRAGON_8_GEN3("骁龙 8 Gen 3+"),
    SNAPDRAGON_8_ELITE("骁龙 8 Elite+")
}

data class ImageEngineSmokeSpec(
    val width: Int = 384,
    val height: Int = 384,
    val steps: Int = 1,
    val timeoutSeconds: Int = 180,
    val prompt: String = "a small ceramic cup on a bright wooden desk",
    val expectedOutputMime: String = "image/png"
)

data class ImageEngineQnnSmokeTensorSpec(
    val name: String,
    val dataType: String,
    val shape: List<Int>,
    val role: String = "input",
    val fill: String = "zero"
)

data class ImageEngineQnnSmokeSpec(
    val graphName: String = "model",
    val contextBinary: String,
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 1,
    val timeoutSeconds: Int = 180,
    val prompt: String = "a small ceramic cup on a bright wooden desk",
    val inputs: List<ImageEngineQnnSmokeTensorSpec>,
    val outputs: List<ImageEngineQnnSmokeTensorSpec>
)

/**
 * Exact QNN runtime contract required by a context-binary bundle.
 *
 * Some publisher archives contain only the context graphs and model assets.
 * For those archives, falling back to an unrelated APK runtime can reach HTP
 * with an incompatible SDK/architecture pair.  Persist this contract in the
 * installed manifest so readiness can fail closed before native execution.
 */
data class ImageEngineQnnRuntimeProfileSpec(
    val qnnSdk: String,
    val htpArch: Int,
    val completeBundleRuntime: Boolean = true
)

data class ImageEngineBundleSpec(
    val id: String,
    val title: String,
    val components: List<ImageEngineBundleComponentSpec>,
    val task: ImageEngineTask = ImageEngineTask.TEXT_TO_IMAGE,
    val runtime: ImageEngineBundleRuntime = ImageEngineBundleRuntime.STABLE_DIFFUSION_CPP,
    val accelerator: ImageEngineAccelerator = ImageEngineAccelerator.CPU,
    val minDeviceTier: ImageEngineMinDeviceTier = ImageEngineMinDeviceTier.ANY,
    val requiresQnnRuntime: Boolean = false,
    val requiresSmokeTest: Boolean = true,
    val smokeSpec: ImageEngineSmokeSpec = ImageEngineSmokeSpec(),
    val qnnSmokeSpecs: List<ImageEngineQnnSmokeSpec> = emptyList(),
    val requiredRuntimeProfile: ImageEngineQnnRuntimeProfileSpec? = null,
    /**
     * Explicit runtime family identifier persisted into the installed bundle
     * manifest.  It avoids relying on a checkpoint filename to choose a
     * family-specific execution path.  Legacy bundles may leave this null.
     */
    val modelFamily: String? = null
) {
    val requiredComponents: List<ImageEngineBundleComponentSpec>
        get() = components.filter { it.required }

    val runtimeSummary: String
        get() = "${runtime.label} · ${accelerator.label}"

    /** The least trustworthy required component determines the bundle label. */
    val integrityMetadataStatus: ImageEngineIntegrityMetadataStatus
        get() = requiredComponents
            .map { it.integrityMetadataStatus }
            .maxByOrNull { it.ordinal }
            ?: ImageEngineIntegrityMetadataStatus.UNKNOWN
}

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

enum class RemoteModelFileKind {
    CHAT_MODEL,
    VISION_MODEL,
    IMAGE_MODEL,
    IMAGE_VAE,
    IMAGE_TEXT_ENCODER,
    IMAGE_TOKENIZER,
    MNN_COMPONENT,
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
        bundleRole == ImageEngineBundleComponentRole.TOKENIZER -> RemoteModelFileKind.IMAGE_TOKENIZER
        bundleRole == ImageEngineBundleComponentRole.CONFIG ||
            bundleRole == ImageEngineBundleComponentRole.CONDITIONING -> RemoteModelFileKind.MNN_COMPONENT
        mnnBundleRole != null -> RemoteModelFileKind.MNN_COMPONENT
        visionBundleRole == VisionModelBundleComponentRole.MAIN_MODEL -> RemoteModelFileKind.VISION_MODEL
        visionBundleRole == VisionModelBundleComponentRole.PROJECTOR -> RemoteModelFileKind.PROJECTOR
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

fun RemoteModelFile.isVisionModelCandidate(): Boolean = fileKind() == RemoteModelFileKind.VISION_MODEL

fun RemoteModelFile.isImageModelCandidate(): Boolean = fileKind() == RemoteModelFileKind.IMAGE_MODEL

fun RemoteModelFile.kindLabel(): String = when (fileKind()) {
    RemoteModelFileKind.CHAT_MODEL -> "聊天主模型"
    RemoteModelFileKind.VISION_MODEL -> "本地识图主模型"
    RemoteModelFileKind.IMAGE_MODEL -> "图像生成模型"
    RemoteModelFileKind.IMAGE_VAE -> "VAE / AE"
    RemoteModelFileKind.IMAGE_TEXT_ENCODER -> "文本编码器 / LLM"
    RemoteModelFileKind.IMAGE_TOKENIZER -> "Tokenizer"
    RemoteModelFileKind.PROJECTOR -> "视觉投影辅助文件"
    RemoteModelFileKind.IMATRIX -> "量化校准辅助文件"
    RemoteModelFileKind.SPECULATIVE -> "投机解码辅助文件"
    RemoteModelFileKind.SPLIT_PART -> "分片文件"
    RemoteModelFileKind.OTHER -> "其他文件"
    else -> "MNN 模型组件"
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
        endsWith(".mnn") ||
        endsWith(".litertlm") ||
        endsWith(".tflite") ||
        endsWith(".ctx") ||
        endsWith(".zip")

enum class ModelScopeRecommendedKind {
    CHAT,
    VISION,
    IMAGE
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

enum class LocalImageEngineTier(val label: String) {
    QUICK("离线基线"),
    STANDARD("写实基线"),
    COMPACT_QUALITY("画质优先"),
    LARGE_QUALITY("高端备用"),
    HEAVY_EXPERIMENTAL("前沿画质")
}

enum class ModelScopeRecommendedGroup(val label: String) {
    LIGHT_CHAT("轻量聊天"),
    MAIN_CHAT("主力聊天"),
    QUALITY_CHAT("高质量聊天"),
    LOCAL_VISION("本地识图"),
    LOCAL_IMAGE("本地生图")
}

enum class RecommendedModelStatus(val label: String) {
    /** Product-level acceptance exists for the capability advertised by the catalog card. */
    RECOMMENDED("已验证"),
    /** Downloadable candidate whose advertised text/vision/image scope is not fully accepted yet. */
    EXPERIMENTAL("实验"),
    /** Execution integration is incomplete; configured component packages may still be downloaded for testing. */
    PENDING_INTEGRATION("待接入"),
    /** Tested below the recommendation bar; it must not become a managed default. */
    NOT_RECOMMENDED("实验")
}

enum class RecommendedModelDownloadPolicy {
    /** CPU and other portable packages can be downloaded on every device. */
    ALL_DEVICES,
    /** Listed chipset codes are recommendation hints only; unmatched devices keep the download action. */
    LISTED_CHIPSETS,
    /** Snapdragon detection ranks QNN packages; unknown and non-matching devices remain downloadable. */
    ANY_SNAPDRAGON
}

enum class RecommendedModelSection {
    CPU_CHAT,
    NPU_CHAT,
    CPU_IMAGE,
    NPU_IMAGE
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
    val status: RecommendedModelStatus = RecommendedModelStatus.RECOMMENDED,
    val visibleInRecommendations: Boolean = true,
    val supportedChipsetCodes: Set<String> = emptySet(),
    val downloadPolicy: RecommendedModelDownloadPolicy = RecommendedModelDownloadPolicy.LISTED_CHIPSETS,
    val group: ModelScopeRecommendedGroup = when (kind) {
        ModelScopeRecommendedKind.CHAT -> ModelScopeRecommendedGroup.MAIN_CHAT
        ModelScopeRecommendedKind.VISION -> ModelScopeRecommendedGroup.LOCAL_VISION
        ModelScopeRecommendedKind.IMAGE -> ModelScopeRecommendedGroup.LOCAL_IMAGE
    },
    val provider: ModelRepositoryProvider = ModelRepositoryProvider.MODELSCOPE,
    val downloadable: Boolean = true,
    val downloadBlockReason: String? = null,
    val chatRuntime: RecommendedChatRuntime = RecommendedChatRuntime.GGUF,
    val mnnModelBundle: MnnModelBundleSpec? = null,
    val localImageEngineTier: LocalImageEngineTier? = null,
    val imageEngineBundle: ImageEngineBundleSpec? = null,
    val visionModelBundle: VisionModelBundleSpec? = null
) {
    val section: RecommendedModelSection
        get() = when {
            kind == ModelScopeRecommendedKind.IMAGE &&
                imageEngineBundle?.accelerator == ImageEngineAccelerator.QNN_HTP -> RecommendedModelSection.NPU_IMAGE
            kind == ModelScopeRecommendedKind.IMAGE -> RecommendedModelSection.CPU_IMAGE
            chatRuntime == RecommendedChatRuntime.GENIEX_QAIRT ||
                visionModelBundle?.accelerator == VisionModelAccelerator.QNN_HTP -> RecommendedModelSection.NPU_CHAT
            else -> RecommendedModelSection.CPU_CHAT
        }

    val modelPageUrl: String
        get() = when (provider) {
            ModelRepositoryProvider.MODELSCOPE -> modelScopeModelPageUrl(repoId)
            ModelRepositoryProvider.HUGGING_FACE -> huggingFaceModelPageUrl(repoId)
        }
}

/**
 * Download access is deliberately separate from the device-fit hint shown in
 * the UI. RAM and chipset matching are advisory only: hardware discovery may
 * recommend a better package, but an unknown or unmatched device must never
 * lose the download/import/load/run path. Integration state is displayed
 * separately: a fully configured component package may be downloaded for
 * user testing even while its execution path is still marked pending.
 */
data class RecommendedModelDownloadEligibility(
    val chipsetMatched: Boolean,
    val canDownload: Boolean,
    val blockedReason: String? = null
)

fun ModelScopeRecommendedModel.downloadEligibilityFor(
    deviceChipsetCode: String,
    deviceIsSnapdragon: Boolean = false
): RecommendedModelDownloadEligibility {
    val normalizedDevice = deviceChipsetCode.trim().uppercase(Locale.ROOT)
    val chipsetMatched = when (downloadPolicy) {
        RecommendedModelDownloadPolicy.ALL_DEVICES -> true
        RecommendedModelDownloadPolicy.LISTED_CHIPSETS -> supportedChipsetCodes.isEmpty() ||
            (normalizedDevice.isNotEmpty() && supportedChipsetCodes.any {
                it.trim().uppercase(Locale.ROOT) == normalizedDevice
            })
        RecommendedModelDownloadPolicy.ANY_SNAPDRAGON ->
            deviceIsSnapdragon || normalizedDevice.isSnapdragonChipsetCode()
    }
    if (!downloadable) {
        return RecommendedModelDownloadEligibility(
            chipsetMatched = chipsetMatched,
            canDownload = false,
            blockedReason = downloadBlockReason ?: "该模型暂不提供下载。"
        )
    }
    return RecommendedModelDownloadEligibility(
        chipsetMatched = chipsetMatched,
        canDownload = true
    )
}

private fun String.isSnapdragonChipsetCode(): Boolean {
    if (isBlank()) return false
    if (contains("SNAPDRAGON") || contains("骁龙")) return true
    return matches(Regex("^(?:SM|SDM|MSM|APQ|QCS|QCM)[A-Z0-9_-]+$"))
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
    "https://hf-mirror.com/${repoId.modelScopePath()}"

private fun String.modelScopePath(): String =
    trim('/')
        .split('/')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
