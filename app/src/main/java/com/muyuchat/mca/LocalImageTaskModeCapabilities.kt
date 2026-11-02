package com.muyuchat.mca

import com.muyuchat.feature.chat.ImageGenerationUiTaskMode
import java.io.File

/**
 * T2B22-T5（b 工位）拆片：任务模式能力解析（supportedImageTaskModesForUi /
 * exposesQnnImg2ImgForUi / exposesQnnInpaintForUi / taskModesFromResolvedProfileForUi 族）自
 * LocalImageUiCapabilities.kt 逐字节平移；仅 private 声明行登记为 internal。
 */

/**
 * Product modes are derived from the selected package and executable runtime,
 * never from chipset or per-device validation state.
 */
internal fun LocalImageModelRecord.supportedImageTaskModesForUi(): Set<ImageGenerationUiTaskMode> {
    return imageCapabilitiesForUi().supportedTaskModes
}

internal fun ImageExecutionProfile.exposesQnnImg2ImgForUi(): Boolean =
    hasExecutableQnnImg2ImgTopology() &&
        supportedSchedulersForProductTask(LocalImageTaskMode.IMG2IMG).isNotEmpty()

internal fun ImageExecutionProfile.exposesQnnInpaintForUi(): Boolean =
    hasExecutableQnnInpaintTopology() &&
        supportedQnnInpaintSchedulers().isNotEmpty()

internal fun LocalImageModelRecord.taskModesFromResolvedProfileForUi(
    profile: ImageExecutionProfile
): Set<ImageGenerationUiTaskMode> = when (profile.task) {
    ImageTask.CONTROL_IMAGE -> setOf(ImageGenerationUiTaskMode.CONTROL)
    ImageTask.IMAGE_EDIT -> setOf(ImageGenerationUiTaskMode.EDIT)
    ImageTask.TEXT_TO_IMAGE -> buildSet {
        add(ImageGenerationUiTaskMode.TEXT_TO_IMAGE)
        if (runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP && profile.variant != ImageModelVariant.QWEN_IMAGE_21) {
            // The public stable-diffusion.cpp generation API consumes init images and masks through
            // the same loaded image context. A package need not expose a separate VAE-encoder file:
            // monolithic checkpoints carry that capability internally, while split packages resolve
            // their declared VAE component at native load time. Keep these runtime capabilities open
            // and let a concrete missing/unsupported native component fail with its real error.
            add(ImageGenerationUiTaskMode.IMG2IMG)
            add(ImageGenerationUiTaskMode.INPAINT)
        }
        if (profile.exposesQnnImg2ImgForUi()) {
            // Availability follows the executable graph topology only. Device/profile discovery
            // remains advisory; the real encoder graph load and execute decide compatibility.
            add(ImageGenerationUiTaskMode.IMG2IMG)
        }
        if (profile.exposesQnnInpaintForUi()) {
            // A regular four-channel shared QNN UNet follows the same per-step latent-blend
            // contract as Local Dream. Native still inspects the loaded graph before execution.
            add(ImageGenerationUiTaskMode.INPAINT)
        }
        if (hasConcreteControlComponentForUi(profile)) {
            add(ImageGenerationUiTaskMode.CONTROL)
        }
    }
}

private fun LocalImageModelRecord.hasConcreteControlComponentForUi(
    profile: ImageExecutionProfile
): Boolean {
    val root = installedImageBundleRootForUi()
    if (profile.capabilities.requiresControlImage && profile.graph.controlNet != null && root != null) {
        val profileControlPath = runCatching { resolveProfileControlNetPath(root, profile) }.getOrNull()
        if (profileControlPath != null) return true
    }
    if (runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP) return false
    return runCatching { resolveStableDiffusionComponentSelection(this) }
        .getOrNull()
        ?.controlNetPath
        ?.let(::File)
        ?.isFile == true
}

internal fun LocalImageModelRecord.legacySupportedImageTaskModesForUi(
    declaredTask: String?
): Set<ImageGenerationUiTaskMode> {
    if (declaredTask == "CONTROL_IMAGE") {
        return setOf(ImageGenerationUiTaskMode.CONTROL)
    }
    if (declaredTask == "IMAGE_EDIT" ||
        (declaredTask == null && family == LocalImageModelFamily.SANA)
    ) {
        return setOf(ImageGenerationUiTaskMode.EDIT)
    }

    return when (runtime) {
        LocalImageRuntime.STABLE_DIFFUSION_CPP -> buildSet {
            add(ImageGenerationUiTaskMode.TEXT_TO_IMAGE)
            add(ImageGenerationUiTaskMode.IMG2IMG)
            add(ImageGenerationUiTaskMode.INPAINT)
            runCatching { resolveStableDiffusionComponentSelection(this@legacySupportedImageTaskModesForUi) }
                .getOrNull()
                ?.controlNetPath
                ?.let(::File)
                ?.takeIf(File::isFile)
                ?.let { add(ImageGenerationUiTaskMode.CONTROL) }
        }
        LocalImageRuntime.MNN_DIFFUSION,
        LocalImageRuntime.QNN_HTP,
        LocalImageRuntime.MEDIATEK_NEURON,
        LocalImageRuntime.ONNX_RUNTIME,
        LocalImageRuntime.CUSTOM -> setOf(ImageGenerationUiTaskMode.TEXT_TO_IMAGE)
    }
}

internal fun LocalImageModelRecord.supportsNegativePromptForUi(): Boolean {
    return imageCapabilitiesForUi().supportsNegativePrompt
}

internal fun LocalImageModelRecord.legacySupportsNegativePromptForUi(): Boolean {
    val declared = readInstalledImageManifest()
        ?.optJSONObject("executionProfile")
        ?.optJSONObject("capabilities")
        ?.let { capabilities ->
            if (capabilities.has("supportsNegativePrompt") &&
                !capabilities.isNull("supportsNegativePrompt")
            ) {
                capabilities.optBoolean("supportsNegativePrompt")
            } else {
                null
            }
        }
    return declared ?: (family !in setOf(
        LocalImageModelFamily.SD_TURBO,
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.FLUX
    ))
}
