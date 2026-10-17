package com.muyuchat.mca

import com.muyuchat.core.download.ImageEngineMinDeviceTier
import java.io.File

/** Bundle contract records for the local-image domain model (moved from LocalImageDomainModel.kt). */

/**
 * The local-image domain model (P5/L5). Moved verbatim out of LocalImageProvider.kt: backend and
 * runtime enums, model-family/verification enums, bundle contract records, the model record, and
 * the generation/result/progress/options records. Same package and visibilities, so the provider,
 * model store, UI and tests are unchanged.
 */
enum class ImageBackend {
    LOCAL,
    CLOUD
}

enum class LocalImageRuntime(val label: String) {
    STABLE_DIFFUSION_CPP("stable-diffusion.cpp"),
    MNN_DIFFUSION("MNN Diffusion"),
    QNN_HTP("骁龙 NPU"),
    ONNX_RUNTIME("ONNX Runtime"),
    CUSTOM("自定义本地图像引擎");

    companion object {
        fun from(value: String?): LocalImageRuntime =
            entries.firstOrNull { it.name == value } ?: when (value) {
                "MEDIAPIPE", "NCNN", "DIFFUSERS" -> STABLE_DIFFUSION_CPP
                "MNN", "MNN_DIFFUSION", "MNN_DIFFUSION_ENGINE" -> MNN_DIFFUSION
                "QNN", "QNN_HTP", "QAIRT", "HTP" -> QNN_HTP
                "ONNX" -> ONNX_RUNTIME
                else -> CUSTOM
            }

        fun infer(fileName: String): LocalImageRuntime {
            val lower = fileName.lowercase()
            return when {
                "qnn" in lower || "qairt" in lower || "htp" in lower -> QNN_HTP
                lower.endsWith(".mnn") -> MNN_DIFFUSION
                lower.endsWith(".zip") && "mnn" in lower -> MNN_DIFFUSION
                lower.endsWith(".onnx") -> ONNX_RUNTIME
                lower.endsWith(".gguf") ||
                    lower.endsWith(".safetensors") ||
                    lower.endsWith(".ckpt") ||
                    lower.endsWith(".pth") ||
                    lower.endsWith(".pt") ||
                    lower.endsWith(".zip") -> STABLE_DIFFUSION_CPP
                else -> CUSTOM
            }
        }
    }
}

enum class LocalImageModelFamily(val label: String) {
    Z_IMAGE("Z-Image"),
    QWEN_IMAGE("Qwen-Image"),
    GLM_IMAGE("GLM-Image"),
    LONGCAT_IMAGE("LongCat-Image"),
    DREAMLITE("DreamLite"),
    SANA("Sana"),
    FLUX("Flux"),
    SD_TURBO("SD-Turbo"),
    SDXL("SDXL"),
    SD21("Stable Diffusion 2.1"),
    SD15("Stable Diffusion 1.5"),
    WAN("Wan"),
    CUSTOM("自定义");

    companion object {
        fun from(value: String?): LocalImageModelFamily {
            val normalized = value?.trim()?.uppercase()
            return when (normalized) {
                "QWEN_IMAGE_21", "QWEN-IMAGE-2.1", "QWEN_IMAGE_2_1" -> QWEN_IMAGE
                else -> entries.firstOrNull { it.name == normalized } ?: CUSTOM
            }
        }

        fun infer(fileName: String): LocalImageModelFamily {
            val lower = fileName.lowercase()
            return when {
                "z-image" in lower || "z_image" in lower || "zimage" in lower -> Z_IMAGE
                "qwen-image" in lower || "qwen_image" in lower -> QWEN_IMAGE
                "glm-image" in lower || "glm_image" in lower -> GLM_IMAGE
                "longcat-image" in lower || "longcat_image" in lower -> LONGCAT_IMAGE
                "dreamlite" in lower -> DREAMLITE
                "sana" in lower -> SANA
                "flux" in lower -> FLUX
                "sd-turbo" in lower || "sd_turbo" in lower -> SD_TURBO
                "sdxl" in lower || "stable-diffusion-xl" in lower -> SDXL
                "sd-2.1" in lower || "sd2.1" in lower || "sd21" in lower || "v2-1" in lower || "stable-diffusion-2-1" in lower -> SD21
                "sd-1.5" in lower || "sd1.5" in lower || "sd15" in lower || "v1-5" in lower || "stable-diffusion-v1-5" in lower -> SD15
                "wan" in lower -> WAN
                else -> CUSTOM
            }
        }
    }
}

enum class LocalImageVerificationStatus {
    UNKNOWN,
    MNN_SMOKE_PASSED,
    QNN_IMAGE_SMOKE_PASSED,
    QNN_SMOKE_PASSED,
    QNN_PIPELINE_PROBE_PASSED,
    PASSED,
    FAILED;

    companion object {
        fun from(value: String?): LocalImageVerificationStatus =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

internal data class LocalImageBundleComponentContract(
    val role: String,
    val relativePath: String,
    val required: Boolean
)

internal data class LocalImageBundleManifest(
    val id: String? = null,
    val recommendationId: String? = null,
    val displayName: String? = null,
    val task: String? = null,
    val runtime: LocalImageRuntime? = null,
    val family: LocalImageModelFamily? = null,
    val imageSize: String? = null,
    val minDeviceTier: ImageEngineMinDeviceTier = ImageEngineMinDeviceTier.ANY,
    val requiresQnnRuntime: Boolean = false,
    val requiredRuntimeProfile: LocalImageQnnRuntimeProfile? = null,
    val requiresSmokeTest: Boolean = true,
    val smokeWidth: Int = 0,
    val smokeHeight: Int = 0,
    val smokeSteps: Int = 0,
    val smokeTimeoutSeconds: Int = 0,
    val qnnSmokeSpec: QnnSmokeSpec = QnnSmokeSpec.Empty,
    val qnnSmokeSpecs: List<QnnSmokeSpec> = emptyList(),
    val primaryFile: File? = null,
    /** The authoritative diffusion path remains available when its file is missing. */
    val declaredPrimaryPath: String? = null,
    val components: List<LocalImageBundleComponentContract> = emptyList(),
    val executionProfileRequiredPaths: List<String> = emptyList(),
    val executionProfileGraphPaths: Map<String, String> = emptyMap(),
    val executionProfileQnnSdk: String? = null,
    val executionProfileDeclared: Boolean = false,
    val componentCount: Int = 0
)

internal sealed interface LocalImageBundleManifestInspection {
    data object Undeclared : LocalImageBundleManifestInspection
    data class Ready(val manifest: LocalImageBundleManifest) : LocalImageBundleManifestInspection
    data class Invalid(val message: String) : LocalImageBundleManifestInspection
}

internal sealed interface LocalImageRuntimeComponentContract {
    data object UndeclaredLegacy : LocalImageRuntimeComponentContract
    data class Ready(
        val requiredPaths: List<String>,
        val missingPaths: List<String>
    ) : LocalImageRuntimeComponentContract
    data class Invalid(val message: String) : LocalImageRuntimeComponentContract
}
