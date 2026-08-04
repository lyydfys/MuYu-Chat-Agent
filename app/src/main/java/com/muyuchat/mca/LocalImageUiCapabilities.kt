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

private data class LocalImageUiProfileOutcome(
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
    hasSharedQnnVaePreviewTopology: Boolean
): LocalImageUiPreviewTopology = when {
    runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP -> LocalImageUiPreviewTopology(
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
    )
    val supportsLora = resolvedCapabilities?.supportsLora
        ?: legacyStableDiffusionCpp
    val nativeMaxBatchCount = resolvedCapabilities?.maxBatchCount
        ?: if (legacyStableDiffusionCpp) 8 else 1
    val normalizedNativeMaxBatchCount = nativeMaxBatchCount.coerceIn(
        1,
        ImageGenerationBatchLineage.MAX_BATCH_COUNT
    )
    val executionDefaults = resolution
        ?.let(::executionDefaultsFromResolutionForUi)
        ?: legacyExecutionDefaultsForUi()
    return LocalImageUiCapabilitiesSnapshot(
        supportedTaskModes = supportedTaskModes,
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

private fun LocalImageModelRecord.taskModesFromResolvedProfileForUi(
    profile: ImageExecutionProfile
): Set<ImageGenerationUiTaskMode> = when (profile.task) {
    ImageTask.CONTROL_IMAGE -> setOf(ImageGenerationUiTaskMode.CONTROL)
    ImageTask.IMAGE_EDIT -> setOf(ImageGenerationUiTaskMode.EDIT)
    ImageTask.TEXT_TO_IMAGE -> buildSet {
        add(ImageGenerationUiTaskMode.TEXT_TO_IMAGE)
        if (runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
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

private fun LocalImageModelRecord.legacySupportedImageTaskModesForUi(
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
        LocalImageRuntime.ONNX_RUNTIME,
        LocalImageRuntime.CUSTOM -> setOf(ImageGenerationUiTaskMode.TEXT_TO_IMAGE)
    }
}

internal fun LocalImageModelRecord.supportsNegativePromptForUi(): Boolean {
    return imageCapabilitiesForUi().supportsNegativePrompt
}

private fun LocalImageModelRecord.legacySupportsNegativePromptForUi(): Boolean {
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

internal fun LocalImageModelRecord.executionDefaultsForUi(): LocalImageUiExecutionDefaults {
    return imageCapabilitiesForUi().executionDefaults
}

private fun executionDefaultsFromResolutionForUi(
    resolution: ImageExecutionProfileResolution
): LocalImageUiExecutionDefaults {
    val resolved = resolution.layers.resolved
    val capabilities = resolution.profile.capabilities
    val sampler = imageSchedulerProductName(resolved.scheduler)
    val supported = capabilities.supportedSchedulers
        .map(::imageSchedulerProductName)
        .distinct()
        .ifEmpty { listOf(sampler) }
    val img2ImgSupported = resolution.profile
        .orderedSchedulersForProductTask(LocalImageTaskMode.IMG2IMG)
        .map(::imageSchedulerProductName)
        .distinct()
    return LocalImageUiExecutionDefaults(
        width = resolved.width,
        height = resolved.height,
        steps = resolved.steps,
        minSteps = resolution.profile.scheduler.minSteps,
        maxSteps = resolution.profile.scheduler.maxSteps,
        cfgScale = resolved.cfgScale,
        seed = resolved.seed.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        sampler = sampler,
        minWidth = capabilities.minWidth,
        maxWidth = capabilities.maxWidth,
        minHeight = capabilities.minHeight,
        maxHeight = capabilities.maxHeight,
        widthMultiple = capabilities.widthMultiple,
        heightMultiple = capabilities.heightMultiple,
        ultraFixMinWidth = capabilities.ultraFixMinWidth,
        ultraFixMaxWidth = capabilities.ultraFixMaxWidth,
        ultraFixMinHeight = capabilities.ultraFixMinHeight,
        ultraFixMaxHeight = capabilities.ultraFixMaxHeight,
        ultraFixWidthMultiple = capabilities.ultraFixWidthMultiple,
        ultraFixHeightMultiple = capabilities.ultraFixHeightMultiple,
        ultraFixRequiredTileSize = capabilities.ultraFixRequiredTileSize,
        supportedSamplers = supported,
        img2ImgSupportedSamplers = img2ImgSupported
    )
}

private fun LocalImageModelRecord.legacyExecutionDefaultsForUi(): LocalImageUiExecutionDefaults {
    val profile = readInstalledImageManifest()?.optJSONObject("executionProfile")
    val defaults = profile?.optJSONObject("defaults")
    val scheduler = profile?.optJSONObject("scheduler")
    val capabilities = profile?.optJSONObject("capabilities")
    val fallbackSize = parseImageSizeForUi(imageSize) ?: when (family) {
        LocalImageModelFamily.SDXL,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE -> 1_024 to 1_024
        else -> 512 to 512
    }
    val width = defaults?.optPositiveInt("width") ?: fallbackSize.first
    val height = defaults?.optPositiveInt("height") ?: fallbackSize.second
    val steps = defaults?.optPositiveInt("steps") ?: when (family) {
        LocalImageModelFamily.SDXL -> 30
        LocalImageModelFamily.SD_TURBO -> 4
        LocalImageModelFamily.Z_IMAGE -> 8
        LocalImageModelFamily.QWEN_IMAGE -> 40
        LocalImageModelFamily.FLUX -> 4
        LocalImageModelFamily.SANA -> 10
        else -> 20
    }
    val minSteps = scheduler?.optPositiveInt("minSteps") ?: 1
    val maxSteps = (scheduler?.optPositiveInt("maxSteps") ?: 1_000).coerceAtLeast(minSteps)
    val cfgScale = defaults?.optFiniteDouble("cfgScale") ?: when (family) {
        LocalImageModelFamily.SD_TURBO,
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.FLUX -> 1.0
        LocalImageModelFamily.SANA -> 4.5
        LocalImageModelFamily.QWEN_IMAGE -> 2.5
        LocalImageModelFamily.LONGCAT_IMAGE -> 5.0
        else -> 7.0
    }
    val seedLong = defaults?.optLong("seed", 42L) ?: 42L
    val seed = seedLong.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    val sampler = scheduler?.optString("algorithm")
        ?.toImageSamplerWireName()
        ?.takeIf(String::isNotBlank)
        ?: when (runtime) {
            LocalImageRuntime.STABLE_DIFFUSION_CPP -> "euler_a"
            LocalImageRuntime.QNN_HTP,
            LocalImageRuntime.MNN_DIFFUSION -> "dpmpp_2m"
            else -> "euler"
        }
    val supported = capabilities?.optJSONArray("supportedSchedulers")
        .toStringList()
        .map(String::toImageSamplerWireName)
        .filter(String::isNotBlank)
        .distinct()
        .ifEmpty { listOf(sampler) }
    val stableCpp = runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP
    val minWidth = capabilities?.optPositiveInt("minWidth") ?: if (stableCpp) 256 else width
    val maxWidth = capabilities?.optPositiveInt("maxWidth") ?: if (stableCpp) 1_536 else width
    val minHeight = capabilities?.optPositiveInt("minHeight") ?: if (stableCpp) 256 else height
    val maxHeight = capabilities?.optPositiveInt("maxHeight") ?: if (stableCpp) 1_536 else height
    val widthMultiple = capabilities?.optPositiveInt("widthMultiple") ?: if (stableCpp) 64 else 8
    val heightMultiple = capabilities?.optPositiveInt("heightMultiple") ?: if (stableCpp) 64 else 8
    val supportsUltraFix = capabilities?.optBoolean("supportsUltraFix", false) == true
    val ultraFixMultiple = if (family == LocalImageModelFamily.SDXL) 32 else 64
    val ultraFixMinWidth = capabilities?.optPositiveInt("ultraFixMinWidth")
        ?: if (stableCpp && supportsUltraFix) 128 else 0
    val ultraFixMaxWidth = capabilities?.optPositiveInt("ultraFixMaxWidth")
        ?: if (stableCpp && supportsUltraFix) 8_192 else 0
    val ultraFixMinHeight = capabilities?.optPositiveInt("ultraFixMinHeight")
        ?: if (stableCpp && supportsUltraFix) 128 else 0
    val ultraFixMaxHeight = capabilities?.optPositiveInt("ultraFixMaxHeight")
        ?: if (stableCpp && supportsUltraFix) 8_192 else 0
    val ultraFixWidthMultiple = capabilities?.optPositiveInt("ultraFixWidthMultiple")
        ?: if (stableCpp && supportsUltraFix) ultraFixMultiple else 0
    val ultraFixHeightMultiple = capabilities?.optPositiveInt("ultraFixHeightMultiple")
        ?: if (stableCpp && supportsUltraFix) ultraFixMultiple else 0
    val ultraFixRequiredTileSize = capabilities?.optPositiveInt("ultraFixRequiredTileSize") ?: 0
    return LocalImageUiExecutionDefaults(
        width = width,
        height = height,
        steps = steps.coerceIn(minSteps, maxSteps),
        minSteps = minSteps,
        maxSteps = maxSteps,
        cfgScale = cfgScale,
        seed = seed,
        sampler = sampler,
        minWidth = minWidth,
        maxWidth = maxWidth,
        minHeight = minHeight,
        maxHeight = maxHeight,
        widthMultiple = widthMultiple,
        heightMultiple = heightMultiple,
        ultraFixMinWidth = ultraFixMinWidth,
        ultraFixMaxWidth = ultraFixMaxWidth,
        ultraFixMinHeight = ultraFixMinHeight,
        ultraFixMaxHeight = ultraFixMaxHeight,
        ultraFixWidthMultiple = ultraFixWidthMultiple,
        ultraFixHeightMultiple = ultraFixHeightMultiple,
        ultraFixRequiredTileSize = ultraFixRequiredTileSize,
        supportedSamplers = supported,
        img2ImgSupportedSamplers = supported
    )
}

private fun LocalImageModelRecord.resolveExecutionProfileOutcomeForUi(): LocalImageUiProfileOutcome {
    val root = installedImageBundleRootForUi()
    return try {
        LocalImageUiProfileOutcome(
            resolution = resolveLocalImageExecutionProfile(
                model = this,
                options = LocalImageGenerationOptions(),
                bundleRoot = root
            ),
            readinessError = null
        )
    } catch (error: Exception) {
        if (!root.hasDeclaredImageExecutionContractForUi()) {
            LocalImageUiProfileOutcome(resolution = null, readinessError = null)
        } else {
            LocalImageUiProfileOutcome(
                resolution = null,
                readinessError = imageExecutionProfileReadinessErrorForUi(error)
            )
        }
    }
}

private fun File?.hasDeclaredImageExecutionContractForUi(): Boolean {
    val root = this?.takeIf(File::isDirectory) ?: return false
    val manifest = findInstalledImageManifestForUi(root)
    if (manifest != null) {
        val json = try {
            JSONObject(manifest.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            return true
        }
        if (json.has("executionProfile")) return true
        if (IMAGE_EXECUTION_BEHAVIOR_CONTAINER_FIELDS.any { field -> json.has(field) }) return true
        if (IMAGE_EXECUTION_BEHAVIOR_FIELDS.any { field -> json.has(field) }) return true
    }
    return listOf(
        "scheduler/scheduler_config.json",
        "tokenizer/tokenizer_config.json",
        "config.json"
    ).any { relativePath -> File(root, relativePath).isFile }
}

private val IMAGE_EXECUTION_BEHAVIOR_CONTAINER_FIELDS = listOf(
    "modelConfig",
    "model_config",
    "generation",
    "generationConfig",
    "generation_config",
    "generationDefaults",
    "generation_defaults",
    "defaults"
)

private val IMAGE_EXECUTION_BEHAVIOR_FIELDS = listOf(
    "defaultPrompt",
    "default_prompt",
    "positivePrompt",
    "positive_prompt",
    "prompt",
    "defaultNegativePrompt",
    "default_negative_prompt",
    "negativePrompt",
    "negative_prompt",
    "defaultSteps",
    "default_steps",
    "numInferenceSteps",
    "num_inference_steps",
    "steps",
    "defaultCfg",
    "default_cfg",
    "cfgScale",
    "cfg_scale",
    "guidanceScale",
    "guidance_scale",
    "cfg",
    "defaultScheduler",
    "default_scheduler",
    "sampleMethod",
    "sample_method",
    "schedulerName",
    "scheduler_name",
    "schedulerType",
    "scheduler_type",
    "samplerName",
    "sampler_name",
    "sampler",
    "scheduler",
    "imageSize",
    "image_size",
    "resolution",
    "defaultWidth",
    "default_width",
    "width",
    "defaultHeight",
    "default_height",
    "height",
    "useCfg",
    "use_cfg",
    "doClassifierFreeGuidance",
    "do_classifier_free_guidance",
    "modelFamily",
    "model_family",
    "family",
    "modelVariant",
    "model_variant",
    "variant",
    "modelType",
    "model_type",
    "pipelineType",
    "pipeline_type",
    "dmd2",
    "isDmd2",
    "is_dmd2",
    "turbo",
    "isTurbo",
    "is_turbo"
)

private fun imageExecutionProfileReadinessErrorForUi(error: Throwable): String {
    val detail = error.message
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: error::class.java.simpleName
    return "图像模型包执行配置无效：$detail"
}

private fun LocalImageModelRecord.readInstalledImageTask(): String? {
    val json = readInstalledImageManifest() ?: return null
    return runCatching {
        json.optString("task").trim().uppercase().takeIf(String::isNotEmpty)
            ?: json.optJSONObject("executionProfile")
                ?.optString("task")
                ?.trim()
                ?.uppercase()
                ?.takeIf(String::isNotEmpty)
            ?: json.optString("recommendationId")
                .trim()
                .lowercase()
                .let { id ->
                    when {
                        "controlnet" in id -> "CONTROL_IMAGE"
                        "sana" in id && "edit" in id -> "IMAGE_EDIT"
                        else -> null
                    }
                }
    }.getOrNull()
}

private fun LocalImageModelRecord.readInstalledImageManifest(): JSONObject? {
    val root = installedImageBundleRootForUi() ?: return null
    val manifest = findInstalledImageManifestForUi(root) ?: return null
    return runCatching { JSONObject(manifest.readText(Charsets.UTF_8)) }.getOrNull()
}

private fun findInstalledImageManifestForUi(root: File): File? =
    File(root, "manifest.json").takeIf(File::isFile)
        ?: root.walkTopDown()
            .maxDepth(4)
            .firstOrNull { file -> file.isFile && file.name.equals("manifest.json", ignoreCase = true) }

private fun LocalImageModelRecord.installedImageBundleRootForUi(): File? = bundleRoot
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
        ?.takeIf(File::isDirectory)
        ?: File(path).parentFile?.takeIf(File::isDirectory)

private fun parseImageSizeForUi(raw: String): Pair<Int, Int>? {
    val match = Regex("^(\\d{2,4})[xX](\\d{2,4})$").matchEntire(raw.trim()) ?: return null
    val width = match.groupValues[1].toIntOrNull() ?: return null
    val height = match.groupValues[2].toIntOrNull() ?: return null
    if (width <= 0 || height <= 0) return null
    return width to height
}

private fun JSONObject.optPositiveInt(name: String): Int? =
    optInt(name, -1).takeIf { it > 0 }

private fun JSONObject.optFiniteDouble(name: String): Double? =
    optDouble(name, Double.NaN).takeIf(Double::isFinite)

private fun JSONArray?.toStringList(): List<String> = if (this == null) {
    emptyList()
} else {
    (0 until length()).mapNotNull { index -> optString(index).takeIf(String::isNotBlank) }
}

private fun String.toImageSamplerWireName(): String = when (
    trim().uppercase().replace('-', '_').replace(' ', '_')
) {
    "DPMPP_2M", "DPM++_2M", "DPM++2M" -> "dpmpp_2m"
    "EULER" -> "euler"
    "EULER_A", "EULER_ANCESTRAL" -> "euler_a"
    "DDIM" -> "ddim"
    "PNDM", "PNDM_PLMS", "PLMS" -> "pndm"
    "LCM" -> "lcm"
    "FLOW", "FLOW_MATCH" -> "flow_match"
    else -> trim().lowercase()
}
