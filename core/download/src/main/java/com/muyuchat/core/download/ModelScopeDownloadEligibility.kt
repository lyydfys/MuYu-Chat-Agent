package com.muyuchat.core.download

import java.util.Locale

enum class ModelScopeRecommendedKind {
    CHAT,
    VISION,
    IMAGE
}

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
    GPU_IMAGE,
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
    /**
     * ABI requirements for a bundled native runtime.  This is advisory at
     * download time: an unmatched device keeps the download/import path, but
     * the recommendation card must explain that the APK cannot execute the
     * package on that ABI.
     */
    val requiredAbis: Set<String> = emptySet(),
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
    val computeBackend: RecommendedComputeBackend = RecommendedComputeBackend.CPU,
    val mnnModelBundle: MnnModelBundleSpec? = null,
    val localImageEngineTier: LocalImageEngineTier? = null,
    val imageEngineBundle: ImageEngineBundleSpec? = null,
    val visionModelBundle: VisionModelBundleSpec? = null
) {
    init {
        if (kind == ModelScopeRecommendedKind.IMAGE) {
            val bundle = requireNotNull(imageEngineBundle) {
                "Image recommendations require an executable image bundle."
            }
            require(bundle.recommendationId == id) {
                "Image recommendation and bundle identities must match."
            }
            require(bundle.executionProfile != null) {
                "Image recommendations require a catalog execution profile."
            }
            val expected = recommendedFileName.trim().replace('\\', '/').substringAfterLast('/')
            val diffusionComponents = bundle.requiredComponents.filter {
                it.role == ImageEngineBundleComponentRole.DIFFUSION
            }
            val primaryMatches = diffusionComponents.filter { component ->
                sequenceOf(component.fileName, component.relativePath)
                    .map { it.trim().replace('\\', '/').substringAfterLast('/') }
                    .any { it.equals(expected, ignoreCase = true) }
            }
            require(primaryMatches.size == 1) {
                "Image recommendation $id must bind its recommended file to exactly one diffusion main component."
            }
            require(diffusionComponents.firstOrNull() == primaryMatches.single()) {
                "Image recommendation $id must place its declared diffusion main component first."
            }
        }
    }

    val section: RecommendedModelSection
        get() = when {
            kind == ModelScopeRecommendedKind.IMAGE &&
                imageEngineBundle?.accelerator == ImageEngineAccelerator.QNN_HTP -> RecommendedModelSection.NPU_IMAGE
            kind == ModelScopeRecommendedKind.IMAGE &&
                imageEngineBundle?.accelerator == ImageEngineAccelerator.OPENCL_GPU -> RecommendedModelSection.GPU_IMAGE
            kind == ModelScopeRecommendedKind.IMAGE -> RecommendedModelSection.CPU_IMAGE
            computeBackend == RecommendedComputeBackend.NPU ||
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
