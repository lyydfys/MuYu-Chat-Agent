package com.muyuchat.mca

/** T2B22-T4: runtime-generic compatibility profile builder; moved verbatim from ImageExecutionProfileTemplates.kt. */

private val stableDiffusionCppSchedulers = setOf(
    ImageSchedulerAlgorithm.EULER,
    ImageSchedulerAlgorithm.EULER_A,
    ImageSchedulerAlgorithm.DDIM,
    ImageSchedulerAlgorithm.DPMPP_2M,
    ImageSchedulerAlgorithm.LCM,
    ImageSchedulerAlgorithm.FLOW_MATCH
)

internal fun genericProfile(
    runtime: LocalImageRuntime,
    family: LocalImageModelFamily,
    fingerprint: String
): ImageExecutionProfile {
    val flowFamily = family in setOf(
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.FLUX
    )
    val size = if (family == LocalImageModelFamily.SDXL || flowFamily) 1024 else 512
    val conditionalOnly = family in setOf(
        LocalImageModelFamily.SD_TURBO,
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.FLUX
    )
    val genericSteps = when (family) {
        LocalImageModelFamily.SD_TURBO,
        LocalImageModelFamily.FLUX -> 4
        LocalImageModelFamily.Z_IMAGE -> 8
        LocalImageModelFamily.QWEN_IMAGE -> 40
        else -> 20
    }
    val genericCfg = when (family) {
        LocalImageModelFamily.QWEN_IMAGE -> 2.5
        LocalImageModelFamily.LONGCAT_IMAGE -> 5.0
        else -> if (conditionalOnly) 1.0 else 7.0
    }
    val algorithm = when (runtime) {
        LocalImageRuntime.STABLE_DIFFUSION_CPP -> if (flowFamily) {
            ImageSchedulerAlgorithm.FLOW_MATCH
        } else {
            ImageSchedulerAlgorithm.EULER_A
        }
        LocalImageRuntime.QNN_HTP, LocalImageRuntime.MNN_DIFFUSION -> ImageSchedulerAlgorithm.PNDM_PLMS
        else -> ImageSchedulerAlgorithm.EULER
    }
    val graph = when (runtime) {
        LocalImageRuntime.QNN_HTP -> imageExecutionProfileQnnGraphContract(
            "text_encoder.bin",
            "unet.bin",
            "vae.bin",
            null,
            null,
            strategy = ImageWorkerStrategy.IN_PROCESS
        )
        LocalImageRuntime.MNN_DIFFUSION -> ImageGraphContract(
            textEncoder = ImageGraphArtifactContract("text_encoder.mnn"),
            unet = ImageGraphArtifactContract("unet.mnn"),
            vae = ImageGraphArtifactContract("vae_decoder.mnn"),
            workerStrategy = ImageWorkerStrategy.IN_PROCESS
        )
        else -> ImageGraphContract(workerStrategy = ImageWorkerStrategy.IN_PROCESS)
    }
    return imageExecutionProfileContract(
        profileId = "generic.compat.${runtime.name.lowercase()}.${family.name.lowercase()}",
        fingerprint = fingerprint,
        runtime = runtime,
        family = family,
        variant = ImageModelVariant.GENERIC_COMPATIBLE,
        scheduler = imageExecutionProfileSchedulerContract(
            algorithm,
            if (algorithm == ImageSchedulerAlgorithm.FLOW_MATCH) {
                ImagePredictionType.FLOW
            } else {
                ImagePredictionType.EPSILON
            },
            genericSteps,
            1,
            100,
            skipPrk = algorithm == ImageSchedulerAlgorithm.PNDM_PLMS
        ),
        tokenizer = imageExecutionProfileClipTokenizerContract(
            if (runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
                ImageTokenizerBackend.SDCPP_NATIVE
            } else {
                ImageTokenizerBackend.MNN_MTOK
            },
            separateNegativePrompt = !conditionalOnly,
            supportsTextualInversion = runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP &&
                family.imageExecutionProfileFamilySupportsTextualInversion()
        ),
        conditioning = imageExecutionProfileConditioningContract(
            ImageEmbeddingDiskDataType.RUNTIME_NATIVE,
            ImageEmbeddingConversionStrategy.RUNTIME_NATIVE,
            1,
            separateNegativePrompt = !conditionalOnly
        ),
        vae = imageExecutionProfileVaeContract(ImageVaeScalingLocation.RUNTIME_NATIVE, 1.0, size),
        graph = graph,
        defaults = imageExecutionProfileDefaults(
            size,
            genericSteps,
            genericCfg,
            useCfg = !conditionalOnly
        ),
        capabilities = if (runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
            imageExecutionProfileStableDiffusionCapabilities(
                if (flowFamily) {
                    setOf(ImageSchedulerAlgorithm.FLOW_MATCH)
                } else {
                    stableDiffusionCppSchedulers
                },
                family = family,
                supportsNegativePrompt = !conditionalOnly
            )
        } else {
            imageExecutionProfileCapabilities(
                size,
                ImageSchedulerAlgorithm.entries.toSet(),
                supportsPromptWeighting = false,
                supportsNegativePrompt = !conditionalOnly
            )
        }
    ).copy(
        provenance = ImageProfileProvenance(
            primarySource = ImageProfileSource.GENERIC_FALLBACK,
            sources = listOf(ImageProfileSource.GENERIC_FALLBACK)
        )
    )
}
