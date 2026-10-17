package com.muyuchat.mca

import org.json.JSONObject

/** T2B22-T4: native-params JSON codec; moved verbatim from ImageExecutionProfileNativeContract.kt. */

internal fun ImageExecutionProfileNativeContract.toNativeParamsJson(resolution: ImageExecutionProfileResolution): JSONObject =
        resolution.layers.resolved.toJson().apply {
            val profile = resolution.profile
            val scheduler = profile.scheduler
            val resolved = resolution.layers.resolved
            if (scheduler.algorithm != resolved.scheduler ||
                scheduler.predictionType != resolved.predictionType
            ) {
                invalid(
                    "scheduler",
                    "Resolved scheduler details disagree with the execution-layer algorithm or prediction type."
                )
            }
            put("sampleMethod", imageSchedulerProductName(scheduler.algorithm))
            put("numTrainTimesteps", scheduler.numTrainTimesteps)
            put("noiseSchedule", scheduler.noiseSchedule.name)
            scheduler.betaStart?.let { put("betaStart", it) }
            scheduler.betaEnd?.let { put("betaEnd", it) }
            put("timestepSpacing", scheduler.timestepSpacing.name)
            put("stepsOffset", scheduler.stepsOffset)
            put("setAlphaToOne", scheduler.setAlphaToOne)
            put("skipPrkSteps", scheduler.skipPrkSteps)
            put("finalSigmaType", scheduler.finalSigmaType.name)
            put("clipSample", scheduler.clipSample)
            put("clipSampleRange", scheduler.clipSampleRange)
            put("thresholding", scheduler.thresholding)
            put("eta", scheduler.eta)
            put("lowerOrderFinal", scheduler.lowerOrderFinal)
            put("scaleModelInput", scheduler.scaleModelInput)
            put("expectedTimetableCount", resolution.layers.resolved.timetableCount)
            put("expectedUnetExecutionCount", resolution.layers.resolved.unetExecutionCount)
            profile.tokenizer.bosId?.let { put("tokenizerBosId", it) }
            profile.tokenizer.eosId?.let { put("tokenizerEosId", it) }
            profile.tokenizer.padId?.let { put("tokenizerPadId", it) }
            put("tokenizerMaxLength", profile.tokenizer.maxLength)
            put("tokenizerClip1PadRule", profile.tokenizer.clip1PadRule.name)
            profile.tokenizer.clip2PadRule?.let { put("tokenizerClip2PadRule", it.name) }
            profile.conditioning.textEncoderOutputShapes
                .firstOrNull()
                ?.lastOrNull()
                ?.let { put("expectedConditioningWidth", it) }
            if (profile.runtime == LocalImageRuntime.QNN_HTP) {
                val pixelRange = profile.vae.outputRange
                if (pixelRange == ImagePixelRange.RUNTIME_NATIVE) {
                    invalid(
                        "pixelRange",
                        "QNN image execution requires an explicit VAE output pixel range."
                    )
                }
                put("pixelRange", pixelRange.name)
            }
            profile.verifiedNativeSimplifiedChineseLanguageProofSha256()?.let { proofSha256 ->
                // Native receives only the verified opaque digest, never the publisher signature
                // or app signing certificate. It must echo this after consuming the same closure.
                put("languageProofSha256", proofSha256)
            }
        }

internal fun ImageExecutionProfileNativeContract.toNativeParamsJsonString(resolution: ImageExecutionProfileResolution): String =
        toNativeParamsJson(resolution).toString()

private fun ImageResolvedExecution.toJson(): JSONObject = JSONObject()
        .put("profileId", profileId)
        .put("profileRevision", profileRevision)
        .put("modelFingerprint", modelFingerprint)
        .put("runtime", runtime.name)
        .put("scheduler", scheduler.name)
        .put("predictionType", predictionType.name)
        .put("steps", steps)
        .put("timetableCount", timetableCount)
        .put("unetExecutionCount", unetExecutionCount)
        .put("cfgScale", cfgScale)
        .put("useCfg", useCfg)
        .put("unconditionalBranch", unconditionalBranch)
        .put("tokenizerBackend", tokenizerBackend.name)
        .put("tokenCount", tokenCount)
        .put("promptWeightingSupported", promptWeightingSupported)
        .put("embeddingDiskDataType", embeddingDiskDataType.name)
        .put("vaeScalingLocation", vaeScalingLocation.name)
        .put("vaeScalingFactor", vaeScalingFactor)
        .put("width", width)
        .put("height", height)
        .put("seed", seed)
        .put("graphName", graphName)
        .put("fallback", fallback)

internal fun JSONObject.toNativeEffective(): ImageNativeEffectiveExecution {
        val native = ImageNativeEffectiveExecution(
            profileId = nativeContractRequiredString("profileId"),
            profileRevision = nativeContractRequiredInt("profileRevision"),
            modelFingerprint = nativeContractRequiredString("modelFingerprint"),
            runtime = requiredEnum("runtime", LocalImageRuntime.entries),
            scheduler = requiredEnum("scheduler", ImageSchedulerAlgorithm.entries),
            predictionType = requiredEnum("predictionType", ImagePredictionType.entries),
            steps = nativeContractRequiredInt("steps"),
            timetableCount = nativeContractRequiredInt("timetableCount"),
            unetExecutionCount = nativeContractRequiredInt("unetExecutionCount"),
            cfgScale = requiredDouble("cfgScale"),
            useCfg = requiredBoolean("useCfg"),
            unconditionalBranch = requiredBoolean("unconditionalBranch"),
            tokenizerBackend = requiredEnum("tokenizerBackend", ImageTokenizerBackend.entries),
            tokenCount = nativeContractRequiredInt("tokenCount"),
            promptWeightingSupported = requiredBoolean("promptWeightingSupported"),
            promptWeightingApplied = requiredBoolean("promptWeightingApplied"),
            positiveWeightedTokenCount = nativeContractRequiredInt("positiveWeightedTokenCount"),
            negativeWeightedTokenCount = nativeContractRequiredInt("negativeWeightedTokenCount"),
            promptWeightFingerprint = nativeContractRequiredString("promptWeightFingerprint").lowercase(),
            embeddingDiskDataType = requiredEnum(
                "embeddingDiskDataType",
                ImageEmbeddingDiskDataType.entries
            ),
            vaeScalingLocation = requiredEnum(
                "vaeScalingLocation",
                ImageVaeScalingLocation.entries
            ),
            vaeScalingFactor = requiredDouble("vaeScalingFactor"),
            width = nativeContractRequiredInt("width"),
            height = nativeContractRequiredInt("height"),
            seed = requiredLong("seed"),
            graphName = nativeContractRequiredString("graphName"),
            fallback = requiredBoolean("fallback")
        )
        return ImageExecutionProfileNativeContract.validatePromptWeightingEvidence(native)
    }
