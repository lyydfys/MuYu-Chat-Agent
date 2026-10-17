package com.muyuchat.mca

import com.muyuchat.feature.chat.ImageGenerationUiPreviewMode
import com.muyuchat.feature.chat.ImageGenerationUiTaskMode
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal data class LocalImageUiExecutionDefaults(
    val width: Int,
    val height: Int,
    val steps: Int,
    val minSteps: Int,
    val maxSteps: Int,
    val cfgScale: Double,
    val seed: Int,
    val sampler: String,
    val minWidth: Int,
    val maxWidth: Int,
    val minHeight: Int,
    val maxHeight: Int,
    val widthMultiple: Int,
    val heightMultiple: Int,
    val ultraFixMinWidth: Int,
    val ultraFixMaxWidth: Int,
    val ultraFixMinHeight: Int,
    val ultraFixMaxHeight: Int,
    val ultraFixWidthMultiple: Int,
    val ultraFixHeightMultiple: Int,
    val ultraFixRequiredTileSize: Int,
    val supportedSamplers: List<String>,
    val img2ImgSupportedSamplers: List<String>
) {
    val supportsCustomSize: Boolean
        get() = minWidth != maxWidth || minHeight != maxHeight
}

/**
 * One immutable UI view of a package contract.  Keeping the resolution and its
 * error together prevents separate Compose fields from resolving the same
 * manifest three times and, more importantly, prevents a malformed declared
 * profile from being mistaken for a profile-less legacy package.
 */
internal data class LocalImageUiCapabilitiesSnapshot(
    val supportedTaskModes: Set<ImageGenerationUiTaskMode>,
    val isQwenImage21Gguf: Boolean,
    val supportsNegativePrompt: Boolean,
    val supportsClipSkip: Boolean,
    val supportsVaeTiling: Boolean,
    val supportsTextualInversion: Boolean,
    val supportedTextualInversionFormats: Set<String>,
    val supportsUltraFix: Boolean,
    val supportsLivePreview: Boolean,
    val previewMode: ImageGenerationUiPreviewMode?,
    val defaultPreviewInterval: Int,
    val supportsLora: Boolean,
    val nativeMaxBatchCount: Int,
    val maxBatchCount: Int,
    val executionDefaults: LocalImageUiExecutionDefaults,
    val readinessError: String?
)

internal data class LocalImageUiProfileOutcome(
    val resolution: ImageExecutionProfileResolution?,
    val readinessError: String?
)

internal fun productImageBatchCountForUi(
    runtime: LocalImageRuntime,
    nativeMaxBatchCount: Int
): Int {
    val normalizedNativeMaximum = nativeMaxBatchCount.coerceIn(
        1,
        ImageGenerationBatchLineage.MAX_BATCH_COUNT
    )
    return when (runtime) {
        LocalImageRuntime.QNN_HTP,
        LocalImageRuntime.MNN_DIFFUSION -> ImageGenerationBatchLineage.MAX_BATCH_COUNT
        else -> normalizedNativeMaximum
    }
}

internal data class LocalImageUiPreviewTopology(
    val previewMode: ImageGenerationUiPreviewMode?,
    val defaultPreviewInterval: Int
) {
    val supportsLivePreview: Boolean
        get() = previewMode != null
}

/**
 * Preview availability follows executable graph topology only. Device identity and package IDs
 * must never participate in this decision: an unknown compatible device reaches the same native
 * load and graph execution path as every other device.
 */
internal fun localImagePreviewTopologyForUi(
    runtime: LocalImageRuntime,
    task: ImageTask?,
    hasSharedQnnVaePreviewTopology: Boolean,
    hasStableDiffusionPreviewTopology: Boolean = true
): LocalImageUiPreviewTopology = when {
    runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP && hasStableDiffusionPreviewTopology -> LocalImageUiPreviewTopology(
        previewMode = ImageGenerationUiPreviewMode.PROJECTION,
        defaultPreviewInterval = 1
    )
    runtime == LocalImageRuntime.QNN_HTP && hasSharedQnnVaePreviewTopology ->
        LocalImageUiPreviewTopology(
            previewMode = ImageGenerationUiPreviewMode.VAE,
            defaultPreviewInterval = if (task == ImageTask.CONTROL_IMAGE) 5 else 4
        )
    else -> LocalImageUiPreviewTopology(
        previewMode = null,
        defaultPreviewInterval = 0
    )
}

internal fun LocalImageModelRecord.imageCapabilitiesForUi(): LocalImageUiCapabilitiesSnapshot {
    val outcome = resolveExecutionProfileOutcomeForUi()
    val resolution = outcome.resolution
    val declaredTask = readInstalledImageTask()
    val supportedTaskModes = if (resolution != null) {
        val resolvedTaskIsPackageBacked = resolution.sourceChain.any { source ->
            source == ImageProfileSource.MANIFEST ||
                source == ImageProfileSource.SIDECAR ||
                source == ImageProfileSource.BUILT_IN
        }
        if (resolvedTaskIsPackageBacked || declaredTask == null) {
            taskModesFromResolvedProfileForUi(resolution.profile)
        } else {
            legacySupportedImageTaskModesForUi(declaredTask)
        }
    } else {
        legacySupportedImageTaskModesForUi(declaredTask)
    }
    val supportsNegativePrompt = resolution
        ?.profile
        ?.capabilities
        ?.supportsNegativePrompt
        ?: legacySupportsNegativePromptForUi()
    val resolvedCapabilities = resolution?.profile?.capabilities
    val legacyStableDiffusionCpp = runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP
    val supportsClipSkip = resolvedCapabilities?.supportsClipSkip
        ?: (legacyStableDiffusionCpp && family in setOf(
            LocalImageModelFamily.SD15,
            LocalImageModelFamily.SD21,
            LocalImageModelFamily.SDXL,
            LocalImageModelFamily.SD_TURBO
        ))
    val supportsVaeTiling = resolvedCapabilities?.supportsVaeTiling
        ?: legacyStableDiffusionCpp
    val supportsTextualInversion = resolvedCapabilities?.supportsTextualInversion
        ?: (legacyStableDiffusionCpp && family in setOf(
            LocalImageModelFamily.SD15,
            LocalImageModelFamily.SD21,
            LocalImageModelFamily.SDXL
        ))
    val effectiveRuntime = resolution?.profile?.runtime ?: runtime
    val supportedTextualInversionFormats = when {
        !supportsTextualInversion -> emptySet()
        effectiveRuntime == LocalImageRuntime.QNN_HTP ||
            effectiveRuntime == LocalImageRuntime.MNN_DIFFUSION -> setOf("safetensors")
        else -> setOf("safetensors", "pytorch", "checkpoint", "binary")
    }
    val supportsUltraFix = resolvedCapabilities?.supportsUltraFix
        ?: (legacyStableDiffusionCpp && supportsVaeTiling && family in setOf(
            LocalImageModelFamily.SD15,
            LocalImageModelFamily.SD21,
            LocalImageModelFamily.SDXL
        ))
    val previewTopology = localImagePreviewTopologyForUi(
        runtime = resolution?.layers?.resolved?.runtime ?: runtime,
        task = resolution?.profile?.task,
        hasSharedQnnVaePreviewTopology = resolution
            ?.profile
            ?.hasSharedQnnVaePreviewTopology() == true,
        hasStableDiffusionPreviewTopology = resolution?.profile?.variant != ImageModelVariant.QWEN_IMAGE_21,
    )
    val supportsLora = resolvedCapabilities?.supportsLora
        ?: legacyStableDiffusionCpp
    val nativeMaxBatchCount = resolvedCapabilities?.maxBatchCount
        ?: if (legacyStableDiffusionCpp) 8 else 1
    val normalizedNativeMaxBatchCount = nativeMaxBatchCount.coerceIn(
        1,
        ImageGenerationBatchLineage.MAX_BATCH_COUNT
    )
    // A fixed UltraFix tile is only meaningful when this same snapshot advertises UltraFix
    // support.  The unresolved-profile ("legacy") defaults read `ultraFixRequiredTileSize` out
    // of the installed manifest JSON while `supportsUltraFix` above is derived separately, so a
    // package can declare a required tile while the legacy flag disagrees.  That combination is
    // a hard contract in `ChatModelChoice`, which is constructed during Compose recomposition --
    // so an incoherent pair used to abort the whole UI with
    // "A model without UltraFix support cannot publish a fixed UltraFix graph tile."
    // Normalising here keeps every consumer of this snapshot self-consistent.
    val executionDefaults = (
        resolution?.let(::executionDefaultsFromResolutionForUi)
            ?: legacyExecutionDefaultsForUi()
        ).let { defaults ->
        if (supportsUltraFix) defaults else defaults.copy(ultraFixRequiredTileSize = 0)
    }
    return LocalImageUiCapabilitiesSnapshot(
        supportedTaskModes = supportedTaskModes,
        isQwenImage21Gguf = resolution?.profile?.profileId == "sdcpp.qwen-image-2.1",
        supportsNegativePrompt = supportsNegativePrompt,
        supportsClipSkip = supportsClipSkip,
        supportsVaeTiling = supportsVaeTiling,
        supportsTextualInversion = supportsTextualInversion,
        supportedTextualInversionFormats = supportedTextualInversionFormats,
        supportsUltraFix = supportsUltraFix,
        supportsLivePreview = previewTopology.supportsLivePreview,
        previewMode = previewTopology.previewMode,
        defaultPreviewInterval = previewTopology.defaultPreviewInterval,
        supportsLora = supportsLora,
        nativeMaxBatchCount = normalizedNativeMaxBatchCount,
        // QNN and MNN remain physically single-output. The app coordinator exposes a truthful
        // product batch by issuing those native requests sequentially. Native-batch runtimes keep
        // their declared physical limit so the UI cannot offer a request the real profile rejects.
        maxBatchCount = productImageBatchCountForUi(runtime, normalizedNativeMaxBatchCount),
        executionDefaults = executionDefaults,
        readinessError = outcome.readinessError
    )
}
