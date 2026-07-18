package com.muyuchat.mca

import com.muyuchat.core.download.ImageEngineBundleRuntime
import com.muyuchat.core.download.ImageEngineBundleSpec

/**
 * Converts the catalog-owned contract into the exact app/runtime contract stored beside downloaded
 * model bytes. The catalog is authoritative for new installs; built-in resolver templates remain an
 * old-package fallback only.
 */
internal fun materializeDownloadedImageExecutionProfile(
    bundle: ImageEngineBundleSpec,
    modelFingerprint: String
): ImageExecutionProfile? {
    val source = bundle.executionProfile ?: return null
    fun artifact(path: String?): ImageGraphArtifactContract? = path
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(::ImageGraphArtifactContract)

    val defaults = source.defaults
    val profile = ImageExecutionProfile(
        profileId = source.profileId,
        profileRevision = source.profileRevision,
        modelFingerprint = modelFingerprint.trim().lowercase(),
        runtime = when (bundle.runtime) {
            ImageEngineBundleRuntime.STABLE_DIFFUSION_CPP -> LocalImageRuntime.STABLE_DIFFUSION_CPP
            ImageEngineBundleRuntime.MNN_DIFFUSION -> LocalImageRuntime.MNN_DIFFUSION
            ImageEngineBundleRuntime.QNN_HTP -> LocalImageRuntime.QNN_HTP
        },
        family = LocalImageModelFamily.from(source.family.name),
        variant = ImageModelVariant.valueOf(source.variant.name),
        task = ImageTask.valueOf(source.task.name),
        provenance = ImageProfileProvenance(
            primarySource = ImageProfileSource.MANIFEST,
            sources = listOf(ImageProfileSource.MANIFEST),
            recommendationId = bundle.recommendationId
        ),
        tokenizer = ImageTokenizerContract(
            backend = ImageTokenizerBackend.valueOf(source.tokenizer.backend.name),
            bosId = source.tokenizer.bosId,
            eosId = source.tokenizer.eosId,
            padId = source.tokenizer.padId,
            maxLength = source.tokenizer.maxLength,
            clip1PadRule = ImageClipPadRule.valueOf(source.tokenizer.clip1PadRule.name),
            clip2PadRule = source.tokenizer.clip2PadRule?.let { ImageClipPadRule.valueOf(it.name) },
            supportsPromptWeighting = source.tokenizer.supportsPromptWeighting,
            supportsTextualInversion = source.tokenizer.supportsTextualInversion,
            separateNegativePrompt = source.tokenizer.separateNegativePrompt
        ),
        conditioning = ImageConditioningContract(
            diskDataType = ImageEmbeddingDiskDataType.valueOf(source.conditioning.diskDataType.name),
            textEncoderInputShape = source.conditioning.textEncoderInputShape,
            textEncoderOutputShapes = source.conditioning.textEncoderOutputShapes,
            conversionStrategy = ImageEmbeddingConversionStrategy.valueOf(
                source.conditioning.conversionStrategy.name
            ),
            dualEncoder = source.conditioning.dualEncoder,
            pooledOutput = source.conditioning.pooledOutput,
            concatenationOrder = source.conditioning.concatenationOrder
        ),
        scheduler = ImageSchedulerContract(
            algorithm = ImageSchedulerAlgorithm.valueOf(source.scheduler.algorithm.name),
            predictionType = ImagePredictionType.valueOf(source.scheduler.predictionType.name),
            numTrainTimesteps = source.scheduler.numTrainTimesteps,
            noiseSchedule = ImageNoiseSchedule.valueOf(source.scheduler.noiseSchedule.name),
            betaStart = source.scheduler.betaStart,
            betaEnd = source.scheduler.betaEnd,
            timestepSpacing = ImageTimestepSpacing.valueOf(source.scheduler.timestepSpacing.name),
            stepsOffset = source.scheduler.stepsOffset,
            setAlphaToOne = source.scheduler.setAlphaToOne,
            skipPrkSteps = source.scheduler.skipPrkSteps,
            finalSigmaType = ImageFinalSigmaType.valueOf(source.scheduler.finalSigmaType.name),
            clipSample = source.scheduler.clipSample,
            clipSampleRange = source.scheduler.clipSampleRange,
            thresholding = source.scheduler.thresholding,
            eta = source.scheduler.eta,
            lowerOrderFinal = source.scheduler.lowerOrderFinal,
            initNoiseSigma = source.scheduler.initNoiseSigma,
            scaleModelInput = source.scheduler.scaleModelInput,
            order = source.scheduler.order,
            defaultSteps = source.scheduler.defaultSteps,
            minSteps = source.scheduler.minSteps,
            maxSteps = source.scheduler.maxSteps,
            rng = ImageRngContract.MT19937,
            seedBits = source.scheduler.seedBits
        ),
        latent = ImageLatentContract(
            channels = 4,
            downsampleFactor = 8,
            schedulerLayout = ImageTensorLayout.NCHW,
            graphLayout = ImageTensorLayout.NCHW,
            initialShape = listOf(1, 4, defaults.height / 8, defaults.width / 8),
            dataType = ImageEmbeddingDiskDataType.FP32
        ),
        vae = ImageVaeContract(
            scalingLocation = ImageVaeScalingLocation.valueOf(source.vae.scalingLocation.name),
            scalingFactor = source.vae.scalingFactor,
            inputShape = source.vae.inputShape,
            outputShape = source.vae.outputShape,
            inputLayout = ImageTensorLayout.valueOf(source.vae.inputLayout.name),
            outputLayout = ImageTensorLayout.valueOf(source.vae.outputLayout.name),
            outputRange = ImagePixelRange.valueOf(source.vae.outputRange.name),
            channelOrder = ImageChannelOrder.valueOf(source.vae.channelOrder.name)
        ),
        graph = ImageGraphContract(
            textEncoder = artifact(source.graph.textEncoder),
            unet = artifact(source.graph.unet),
            vae = artifact(source.graph.vae),
            vaeEncoder = artifact(source.graph.vaeEncoder),
            controlNet = artifact(source.graph.controlNet),
            schedulerSidecar = source.graph.schedulerSidecar,
            tokenizerSidecar = source.graph.tokenizerSidecar,
            configSidecars = source.graph.configSidecars,
            qnnSdk = source.graph.qnnSdk,
            htpArch = source.graph.htpArch,
            workerStrategy = ImageWorkerStrategy.valueOf(source.graph.workerStrategy.name)
        ),
        defaults = ImageGenerationDefaults(
            width = defaults.width,
            height = defaults.height,
            steps = defaults.steps,
            cfgScale = defaults.cfgScale,
            seed = defaults.seed,
            useCfg = defaults.useCfg,
            defaultPrompt = defaults.defaultPrompt,
            defaultNegativePrompt = defaults.defaultNegativePrompt
        ),
        capabilities = ImageGenerationCapabilities(
            supportedSchedulers = source.capabilities.supportedSchedulers
                .mapTo(linkedSetOf()) { ImageSchedulerAlgorithm.valueOf(it.name) },
            minWidth = source.capabilities.minWidth,
            maxWidth = source.capabilities.maxWidth,
            minHeight = source.capabilities.minHeight,
            maxHeight = source.capabilities.maxHeight,
            widthMultiple = source.capabilities.widthMultiple,
            heightMultiple = source.capabilities.heightMultiple,
            supportsNegativePrompt = source.capabilities.supportsNegativePrompt,
            supportsPromptWeighting = source.capabilities.supportsPromptWeighting,
            supportsTextualInversion = source.capabilities.supportsTextualInversion,
            requiresControlImage = source.capabilities.requiresControlImage,
            requiresInputImage = source.capabilities.requiresInputImage,
            supportsMask = source.capabilities.supportsMask,
            supportsClipSkip = source.capabilities.supportsClipSkip,
            supportsVaeTiling = source.capabilities.supportsVaeTiling,
            supportsLivePreview = source.capabilities.supportsLivePreview,
            supportsLora = source.capabilities.supportsLora,
            maxBatchCount = source.capabilities.maxBatchCount
        )
    )
    val validation = ImageExecutionProfileValidator.validate(profile, modelFingerprint)
    require(validation.valid) {
        validation.issues.joinToString(" ") { issue ->
            "${issue.code}:${issue.field}:${issue.message}"
        }
    }
    return profile
}
