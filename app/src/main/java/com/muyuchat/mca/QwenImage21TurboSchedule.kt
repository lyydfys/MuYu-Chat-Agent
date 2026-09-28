package com.muyuchat.mca

import org.json.JSONObject

/**
 * Only the publisher's exact v0.2.1 LoRA bytes at the published 1.0 strength
 * opt into Viggle's distilled schedule.
 * Every other adapter, and the Qwen base with no adapter, keeps its native timetable.
 * SHA-256 values are the LFS object IDs published at
 * https://huggingface.co/Viggle/Qwen-Image-2.1-viggle-turbo/tree/main.
 */
internal object QwenImage21TurboSchedule {
    private val officialV021LoraSha256 = setOf(
        "bafb91d0047df3f9b8a5a850b0c967f051164314d8aad778dfa34d9c24ec345b",
        "2a0148f5c73abbed5f97da5ea356e439318aadb281d01fce4af39cdf43728803"
    )

    fun isOfficialV021Sha256(sha256: String): Boolean =
        sha256.lowercase() in officialV021LoraSha256

    fun isOfficialV021(lora: LocalImagePreparedLora): Boolean =
        isOfficialV021Sha256(lora.sha256) && lora.multiplier == 1.0

    fun acceptsControls(options: LocalImageGenerationOptions): Boolean =
        (options.cfgScale == null || options.cfgScale == 1.0) &&
            options.useCfg != true &&
            options.negativePrompt.isNullOrBlank() &&
            options.flowShift == null &&
            options.distilledGuidance == null

    fun selectedFor(
        profile: ImageExecutionProfile,
        loras: List<LocalImagePreparedLora>,
        steps: Int
    ): String? {
        if (profile.runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP ||
            profile.profileId != "sdcpp.qwen-image-2.1" ||
            loras.size != 1 || !isOfficialV021(loras.single())
        ) return null
        return when (steps) {
            4, 5, 6, 7, 8 -> "viggle_v021_$steps"
            else -> null
        }
    }

    fun verifyNativeEcho(result: JSONObject, expected: String?) {
        val native = result.optJSONObject("nativeEffective")
            ?: error("Qwen-Image-2.1 did not report native execution metadata.")
        if (expected == null) {
            require(!native.has("qwenTurboSchedule") &&
                !native.has("qwenRawSigmas") &&
                !native.has("qwenShiftedSigmas")) {
                "Qwen-Image-2.1 reported an unrequested Turbo schedule."
            }
            return
        }
        require(native.optString("qwenTurboSchedule") == expected) {
            "Qwen-Image-2.1 did not execute the selected Viggle schedule."
        }
        val expectedSteps = expected.substringAfterLast('_').toInt()
        val raw = native.optJSONArray("qwenRawSigmas")
        val shifted = native.optJSONArray("qwenShiftedSigmas")
        require(raw?.length() == expectedSteps && shifted?.length() == expectedSteps + 1 &&
            shifted?.optDouble(expectedSteps, Double.NaN) == 0.0) {
            "Qwen-Image-2.1 did not report the complete shifted Viggle timetable."
        }
    }
}
