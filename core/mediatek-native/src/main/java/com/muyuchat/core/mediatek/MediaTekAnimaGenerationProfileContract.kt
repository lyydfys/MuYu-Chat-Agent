package com.muyuchat.core.mediatek

enum class MediaTekAnimaLatentDistribution {
    STANDARD_NORMAL,
    VERIFIED_ASSET,
}

enum class MediaTekAnimaNumericStage {
    PATCHIFY,
    TEXT_CONTEXT_ADAPTER,
    PRELUDE,
    DIT_BLOCK,
    FLOW_PREDICTION,
    UPDATED_LATENT,
    VAE_OUTPUT,
}

data class MediaTekShapedAssetContract(
    val asset: MediaTekBundleAssetContract,
    val dtype: String,
    val shape: List<Int>,
) {
    fun validate(label: String): List<String> = buildList {
        addAll(asset.validate(label))
        if (dtype.lowercase() !in setOf("float16", "float32", "int8", "int32")) {
            add("$label: unsupported asset dtype '$dtype'")
        }
        if (shape.isEmpty() || shape.size > 8 || shape.any { it <= 0 }) {
            add("$label: shape must contain 1..8 positive dimensions")
        }
    }
}

data class MediaTekAnimaLatentInitializationContract(
    val distribution: MediaTekAnimaLatentDistribution,
    val seedRequired: Boolean,
    val verifiedLatentAsset: MediaTekShapedAssetContract?,
) {
    fun validate(): List<String> = buildList {
        when (distribution) {
            MediaTekAnimaLatentDistribution.STANDARD_NORMAL -> {
                if (verifiedLatentAsset != null) add("standard-normal latent initialization cannot set verifiedLatentAsset")
            }
            MediaTekAnimaLatentDistribution.VERIFIED_ASSET -> {
                if (verifiedLatentAsset == null) add("verified-asset latent initialization requires verifiedLatentAsset")
                else addAll(verifiedLatentAsset.validate("initial latent"))
            }
        }
    }
}

data class MediaTekAnimaGenerationProfile(
    val id: String,
    val minimumSteps: Int,
    val maximumSteps: Int,
    val defaultSteps: Int,
    /** Keys enumerate supported step counts; each value carries stepCount + 1 sigma values. */
    val sigmaSchedules: Map<Int, List<Double>>,
    val blockModulationsByStepCount: Map<Int, MediaTekShapedAssetContract>,
    val finalModulationsByStepCount: Map<Int, MediaTekShapedAssetContract>,
    val defaultCfgScale: Float,
    val minimumCfgScale: Float,
    val maximumCfgScale: Float,
    val widthPixels: Int,
    val heightPixels: Int,
    val latentInitialization: MediaTekAnimaLatentInitializationContract,
    val numericGates: Map<MediaTekAnimaNumericStage, MediaTekNumericDriftGate>,
) {
    fun validate(): List<String> = buildList {
        if (id.isBlank()) add("generation profile id is empty")
        val supportedSteps = sigmaSchedules.keys
        if (minimumSteps !in 1..64 || maximumSteps !in minimumSteps..64) add("generation profile step bounds must be within 1..64")
        if (supportedSteps.isEmpty()) add("generation profile has no supported step counts")
        if (supportedSteps.any { it !in minimumSteps..maximumSteps }) add("supported step count is outside the profile bounds")
        if (supportedSteps.minOrNull() != minimumSteps || supportedSteps.maxOrNull() != maximumSteps) {
            add("step bounds must match minimum and maximum supported step counts")
        }
        if (defaultSteps !in supportedSteps) add("defaultSteps is not supported by this profile")
        if (blockModulationsByStepCount.keys != sigmaSchedules.keys) add("block modulation step counts must match sigma schedules")
        if (finalModulationsByStepCount.keys != sigmaSchedules.keys) add("final modulation step counts must match sigma schedules")
        supportedSteps.forEach { steps ->
            val sigmas = sigmaSchedules[steps]
            if (sigmas == null) add("missing sigma schedule for $steps steps") else {
                if (sigmas.size != steps + 1) add("$steps-step sigma schedule must contain stepCount + 1 values")
                if (sigmas.any { !it.isFinite() || it !in 0.0..1.0 }) add("$steps-step sigma values must be finite and in [0,1]")
                if (sigmas.firstOrNull()?.let { it <= 0.0 } != false) add("$steps-step sigma schedule must start above zero")
                if (sigmas.lastOrNull()?.let { it != 0.0 } != false) add("$steps-step sigma schedule must end at zero")
                if (sigmas.zipWithNext().any { (left, right) -> left <= right }) add("$steps-step sigma schedule must be strictly descending")
            }
            val blockMods = blockModulationsByStepCount[steps]
            val finalMods = finalModulationsByStepCount[steps]
            blockMods?.let { addAll(it.validate("$steps-step block modulations")) }
            finalMods?.let { addAll(it.validate("$steps-step final modulations")) }
            if (blockMods != null && blockMods.shape != listOf(steps, 28, 3, 1, 6144)) {
                add("$steps-step block modulation shape must be [$steps,28,3,1,6144]")
            }
            if (blockMods != null && !blockMods.dtype.equals("float16", ignoreCase = true)) add("$steps-step block modulations must be float16")
            if (finalMods != null && finalMods.shape != listOf(steps, 1, 4096)) {
                add("$steps-step final modulation shape must be [$steps,1,4096]")
            }
            if (finalMods != null && !finalMods.dtype.equals("float16", ignoreCase = true)) add("$steps-step final modulations must be float16")
        }
        if (!minimumCfgScale.isFinite() || !maximumCfgScale.isFinite() || minimumCfgScale < 0f || maximumCfgScale < minimumCfgScale) {
            add("CFG bounds must be finite, nonnegative, and ordered")
        }
        if (!defaultCfgScale.isFinite() || defaultCfgScale !in minimumCfgScale..maximumCfgScale) add("defaultCfgScale is outside the supported CFG range")
        if (widthPixels !in 256..1024 || heightPixels !in 256..1024 || widthPixels % 16 != 0 || heightPixels % 16 != 0) {
            add("resolution must be 256..1024 pixels and divisible by 16")
        }
        addAll(latentInitialization.validate())
        val missingGates = MediaTekAnimaNumericStage.entries - numericGates.keys
        if (missingGates.isNotEmpty()) add("missing numeric gates for ${missingGates.joinToString()}")
        val extraGates = numericGates.keys - MediaTekAnimaNumericStage.entries.toSet()
        if (extraGates.isNotEmpty()) add("unexpected numeric gates for ${extraGates.joinToString()}")
        numericGates.forEach { (stage, gate) -> addAll(gate.validate(stage)) }
    }

    fun sigmas(steps: Int): List<Double>? = sigmaSchedules[steps]

    fun blockModulations(steps: Int): MediaTekShapedAssetContract? = blockModulationsByStepCount[steps]

    fun finalModulations(steps: Int): MediaTekShapedAssetContract? = finalModulationsByStepCount[steps]
}

data class MediaTekNumericDriftGate(
    val minimumStd: Double,
    val maximumStd: Double,
    val maximumAbs: Double,
    val minimumOutputToInputStdRatio: Double?,
    val maximumOutputToInputStdRatio: Double?,
    val maximumNonFiniteElements: Long = 0,
) {
    fun validate(stage: MediaTekAnimaNumericStage): List<String> = buildList {
        if (!minimumStd.isFinite() || minimumStd <= 0.0) add("$stage minimumStd must be finite and positive")
        if (!maximumStd.isFinite() || maximumStd <= 0.0 || maximumStd < minimumStd) add("$stage maximumStd must be finite and at least minimumStd")
        if (!maximumAbs.isFinite() || maximumAbs <= 0.0) add("$stage maximumAbs must be finite and positive")
        if (maximumAbs > RUNTIME_MAXIMUM_ABS) add("$stage maximumAbs exceeds the runtime safety ceiling $RUNTIME_MAXIMUM_ABS")
        if (maximumNonFiniteElements != 0L) add("$stage maximumNonFiniteElements must be zero")
        val minRatio = minimumOutputToInputStdRatio
        val maxRatio = maximumOutputToInputStdRatio
        if ((minRatio == null) != (maxRatio == null)) add("$stage drift ratio bounds must be both present or both absent")
        if (minRatio != null && maxRatio != null &&
            (!minRatio.isFinite() || !maxRatio.isFinite() || minRatio <= 0.0 || maxRatio < minRatio)
        ) add("$stage drift ratio bounds must be finite, nonnegative, and ordered")
        if (stage == MediaTekAnimaNumericStage.DIT_BLOCK && minRatio != null && minRatio < DIT_BLOCK_MIN_STD_RATIO) {
            add("$stage minimum drift ratio cannot be lower than $DIT_BLOCK_MIN_STD_RATIO")
        }
        if (stage == MediaTekAnimaNumericStage.DIT_BLOCK && maxRatio != null && maxRatio > DIT_BLOCK_MAX_STD_RATIO) {
            add("$stage maximum drift ratio cannot exceed $DIT_BLOCK_MAX_STD_RATIO")
        }
        if (stage in setOf(
                MediaTekAnimaNumericStage.TEXT_CONTEXT_ADAPTER,
                MediaTekAnimaNumericStage.DIT_BLOCK,
                MediaTekAnimaNumericStage.UPDATED_LATENT,
            ) &&
            (minRatio == null || maxRatio == null)
        ) add("$stage requires an input/output standard-deviation drift band")
    }

    companion object {
        const val RUNTIME_MAXIMUM_ABS = 1_000.0
        const val DIT_BLOCK_MIN_STD_RATIO = 0.3
        const val DIT_BLOCK_MAX_STD_RATIO = 3.0
    }
}
