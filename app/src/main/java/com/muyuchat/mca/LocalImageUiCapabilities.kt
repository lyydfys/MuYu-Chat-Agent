package com.muyuchat.mca

import com.muyuchat.feature.chat.ImageGenerationUiTaskMode
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal data class LocalImageUiExecutionDefaults(
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfgScale: Double,
    val seed: Int,
    val sampler: String,
    val minWidth: Int,
    val maxWidth: Int,
    val minHeight: Int,
    val maxHeight: Int,
    val widthMultiple: Int,
    val heightMultiple: Int,
    val supportedSamplers: List<String>
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
    val supportsLivePreview: Boolean,
    val supportsLora: Boolean,
    val maxBatchCount: Int,
    val executionDefaults: LocalImageUiExecutionDefaults,
    val readinessError: String?
)

private data class LocalImageUiProfileOutcome(
    val resolution: ImageExecutionProfileResolution?,
    val readinessError: String?
)

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
    val supportsLivePreview = resolvedCapabilities?.supportsLivePreview
        ?: legacyStableDiffusionCpp
    val supportsLora = resolvedCapabilities?.supportsLora
        ?: legacyStableDiffusionCpp
    val maxBatchCount = resolvedCapabilities?.maxBatchCount
        ?: if (legacyStableDiffusionCpp) 8 else 1
    val executionDefaults = resolution
        ?.let(::executionDefaultsFromResolutionForUi)
        ?: legacyExecutionDefaultsForUi()
    return LocalImageUiCapabilitiesSnapshot(
        supportedTaskModes = supportedTaskModes,
        supportsNegativePrompt = supportsNegativePrompt,
        supportsClipSkip = supportsClipSkip,
        supportsVaeTiling = supportsVaeTiling,
        supportsLivePreview = supportsLivePreview,
        supportsLora = supportsLora,
        maxBatchCount = maxBatchCount.coerceIn(1, 8),
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
    return LocalImageUiExecutionDefaults(
        width = resolved.width,
        height = resolved.height,
        steps = resolved.steps,
        cfgScale = resolved.cfgScale,
        seed = resolved.seed.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        sampler = sampler,
        minWidth = capabilities.minWidth,
        maxWidth = capabilities.maxWidth,
        minHeight = capabilities.minHeight,
        maxHeight = capabilities.maxHeight,
        widthMultiple = capabilities.widthMultiple,
        heightMultiple = capabilities.heightMultiple,
        supportedSamplers = supported
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
    return LocalImageUiExecutionDefaults(
        width = width,
        height = height,
        steps = steps,
        cfgScale = cfgScale,
        seed = seed,
        sampler = sampler,
        minWidth = minWidth,
        maxWidth = maxWidth,
        minHeight = minHeight,
        maxHeight = maxHeight,
        widthMultiple = widthMultiple,
        heightMultiple = heightMultiple,
        supportedSamplers = supported
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
    } catch (error: Throwable) {
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
        val json = runCatching { JSONObject(manifest.readText(Charsets.UTF_8)) }
            .getOrElse { return true }
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
