package com.muyuchat.mca

import org.json.JSONArray
import org.json.JSONObject

/**
 * T2B22-T5（b 工位）拆片：执行默认值解析（executionDefaultsForUi /
 * executionDefaultsFromResolutionForUi / legacyExecutionDefaultsForUi）与其专属 JSON 读取 helper
 * （parseImageSizeForUi / optPositiveInt / optFiniteDouble / toStringList / toImageSamplerWireName）自
 * LocalImageUiCapabilities.kt 逐字节平移；跨件消费的声明行登记为 internal，专属 helper 保持 private。
 */

internal fun LocalImageModelRecord.executionDefaultsForUi(): LocalImageUiExecutionDefaults {
    return imageCapabilitiesForUi().executionDefaults
}

internal fun executionDefaultsFromResolutionForUi(
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

internal fun LocalImageModelRecord.legacyExecutionDefaultsForUi(
    mediaTekContract: MediaTekImageProductContract? = mediaTekImageProductContractOrNull()
): LocalImageUiExecutionDefaults {
    if (runtime == LocalImageRuntime.MEDIATEK_NEURON) {
        // The v3/v4 bundle manifest owns the canvas, step count, CFG and sampler. Never fall back
        // to the generic SD defaults here: the package would reject them at execution time.
        mediaTekContract?.let { contract -> return mediaTekImageExecutionDefaultsForUi(contract) }
    }
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
