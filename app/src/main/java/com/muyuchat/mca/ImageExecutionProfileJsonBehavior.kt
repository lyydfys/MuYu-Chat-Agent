package com.muyuchat.mca

import java.io.File
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal fun parseBehaviorFields(
    json: JSONObject,
    fieldPrefix: String
): ImagePackageBehaviorConfig? {
    val defaultPrompt = json.optionalStrictStringAlias(
        fieldPrefix,
        listOf("defaultPrompt", "default_prompt", "positivePrompt", "positive_prompt", "prompt"),
        preserveBlank = true
    )
    val defaultNegativePrompt = json.optionalStrictStringAlias(
        fieldPrefix,
        listOf("defaultNegativePrompt", "default_negative_prompt", "negativePrompt", "negative_prompt"),
        preserveBlank = true
    )
    val steps = json.optionalStrictIntAlias(
        fieldPrefix,
        listOf("defaultSteps", "default_steps", "numInferenceSteps", "num_inference_steps", "steps")
    )?.also { value ->
        if (value <= 0) throw formatError("$fieldPrefix.steps", "Default steps must be positive.")
    }
    val cfgScale = json.optionalStrictDoubleAlias(
        fieldPrefix,
        listOf("defaultCfg", "default_cfg", "cfgScale", "cfg_scale", "guidanceScale", "guidance_scale", "cfg")
    )?.also { value ->
        if (value !in 0.0..30.0) throw formatError("$fieldPrefix.cfg", "Default CFG must be between 0 and 30.")
    }
    val scheduler = json.optionalStrictStringAlias(
        fieldPrefix,
        listOf(
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
            "scheduler"
        )
    )?.let { value -> packageBehaviorScheduler(value, "$fieldPrefix.scheduler") }
    val imageSize = json.optionalImageSizeAlias(
        fieldPrefix,
        listOf("imageSize", "image_size", "resolution")
    )
    val width = (json.optionalStrictIntAlias(
        fieldPrefix,
        listOf("defaultWidth", "default_width", "width")
    ) ?: imageSize?.first)?.also { value ->
        if (value <= 0) throw formatError("$fieldPrefix.width", "Default width must be positive.")
    }
    val height = (json.optionalStrictIntAlias(
        fieldPrefix,
        listOf("defaultHeight", "default_height", "height")
    ) ?: imageSize?.second)?.also { value ->
        if (value <= 0) throw formatError("$fieldPrefix.height", "Default height must be positive.")
    }
    val useCfg = json.optionalStrictBooleanAlias(
        fieldPrefix,
        listOf("useCfg", "use_cfg", "doClassifierFreeGuidance", "do_classifier_free_guidance")
    )
    val declaredFamilyValue = json.optionalStrictStringAlias(
        fieldPrefix,
        listOf("modelFamily", "model_family", "family")
    )
    val declaredFamily = declaredFamilyValue?.let(::packageBehaviorFamily)
    val declaredVariantValue = json.optionalStrictStringAlias(
        fieldPrefix,
        listOf("modelVariant", "model_variant", "variant")
    )
    val declaredVariant = declaredVariantValue?.let(::packageBehaviorVariant)
    val modelType = json.optionalStrictStringAlias(
        fieldPrefix,
        listOf("modelType", "model_type", "pipelineType", "pipeline_type")
    )
    val dmd2 = json.optionalStrictBooleanAlias(
        fieldPrefix,
        listOf("dmd2", "isDmd2", "is_dmd2")
    ) ?: false
    val turbo = json.optionalStrictBooleanAlias(
        fieldPrefix,
        listOf("turbo", "isTurbo", "is_turbo")
    ) ?: false
    val marker = listOfNotNull(modelType, declaredVariantValue, declaredFamilyValue)
        .joinToString("_")
        .normalizeBehaviorToken()

    var family = declaredFamily
    var variant = declaredVariant
    if (dmd2 || "dmd2" in marker) {
        family = LocalImageModelFamily.SDXL
        variant = ImageModelVariant.DMD2_ALT
    } else if (turbo || "turbo" in marker) {
        variant = when {
            "z_image" in marker || "zimage" in marker -> ImageModelVariant.Z_IMAGE_TURBO
            else -> ImageModelVariant.SD_TURBO
        }
        family = when {
            "z_image" in marker || "zimage" in marker -> LocalImageModelFamily.Z_IMAGE
            "sd_turbo" in marker || "sdturbo" in marker -> LocalImageModelFamily.SD_TURBO
            else -> family ?: LocalImageModelFamily.SD_TURBO
        }
    }

    val result = ImagePackageBehaviorConfig(
        family = family,
        variant = variant,
        defaultPrompt = defaultPrompt,
        defaultNegativePrompt = defaultNegativePrompt,
        steps = steps,
        cfgScale = cfgScale,
        scheduler = scheduler,
        width = width,
        height = height,
        useCfg = useCfg
    )
    return result.takeUnless { behavior ->
        behavior.family == null &&
            behavior.variant == null &&
            behavior.defaultPrompt == null &&
            behavior.defaultNegativePrompt == null &&
            behavior.steps == null &&
            behavior.cfgScale == null &&
            behavior.scheduler == null &&
            behavior.width == null &&
            behavior.height == null &&
            behavior.useCfg == null
    }
}

private fun packageBehaviorScheduler(value: String, field: String): ImageSchedulerAlgorithm {
    val normalized = value.normalizeBehaviorToken()
    return when (normalized) {
        "dpm", "dpm_karras", "dpm_sde", "dpm_sde_karras", "dpmpp_2m",
        "dpmpp_2m_karras", "dpm_2m", "dpm_plus_plus_2m", "dpmsolvermultistepscheduler" ->
            ImageSchedulerAlgorithm.DPMPP_2M
        "euler", "k_euler", "euler_karras", "euler_discrete", "eulerdiscretescheduler" ->
            ImageSchedulerAlgorithm.EULER
        "euler_a", "eulera", "k_euler_a", "euler_a_karras", "euler_ancestral", "eulerancestraldiscretescheduler" ->
            ImageSchedulerAlgorithm.EULER_A
        "ddim", "ddimscheduler" -> ImageSchedulerAlgorithm.DDIM
        "pndm", "plms", "pndm_plms", "pndmscheduler" -> ImageSchedulerAlgorithm.PNDM_PLMS
        "lcm", "lcmscheduler" -> ImageSchedulerAlgorithm.LCM
        "flow", "flow_match", "flowmatch", "flowmatchscheduler",
        "flow_match_euler_discrete", "flow_match_euler_discrete_scheduler",
        "flowmatcheulerdiscretescheduler" -> ImageSchedulerAlgorithm.FLOW_MATCH
        else -> throw formatError(field, "Unsupported package default scheduler: $value")
    }
}

private fun packageBehaviorFamily(value: String): LocalImageModelFamily {
    val normalized = value.normalizeBehaviorToken()
    return LocalImageModelFamily.entries.firstOrNull { family ->
        family.name.normalizeBehaviorToken() == normalized
    } ?: when (normalized) {
        "sd1_5", "stable_diffusion_1_5", "stable_diffusion_v1_5" -> LocalImageModelFamily.SD15
        "sd2_1", "stable_diffusion_2_1", "stable_diffusion_v2_1" -> LocalImageModelFamily.SD21
        "stable_diffusion_xl" -> LocalImageModelFamily.SDXL
        "sd_turbo", "stable_diffusion_turbo" -> LocalImageModelFamily.SD_TURBO
        "flux2", "flux_2", "flux2_klein" -> LocalImageModelFamily.FLUX
        "qwen_image_2512" -> LocalImageModelFamily.QWEN_IMAGE
        "longcat", "longcat_image" -> LocalImageModelFamily.LONGCAT_IMAGE
        "sana_edit", "sana_edit_v2" -> LocalImageModelFamily.SANA
        else -> LocalImageModelFamily.CUSTOM
    }
}

private fun packageBehaviorVariant(value: String): ImageModelVariant? {
    val normalized = value.normalizeBehaviorToken()
    return ImageModelVariant.entries.firstOrNull { variant ->
        variant.name.normalizeBehaviorToken() == normalized
    } ?: when (normalized) {
        "dmd2", "dmd2_alt", "sdxl_dmd2" -> ImageModelVariant.DMD2_ALT
        "turbo", "sd_turbo" -> ImageModelVariant.SD_TURBO
        "z_image_turbo", "zimage_turbo" -> ImageModelVariant.Z_IMAGE_TURBO
        "flux2", "flux_2", "flux2_klein" -> ImageModelVariant.FLUX2_KLEIN
        "qwen_image_2512" -> ImageModelVariant.QWEN_IMAGE
        "longcat" -> ImageModelVariant.LONGCAT_IMAGE
        "sana_edit_v2" -> ImageModelVariant.SANA_EDIT
        else -> null
    }
}

private fun String.normalizeBehaviorToken(): String = trim()
    .lowercase()
    .replace(Regex("[^a-z0-9]+"), "_")
    .trim('_')

