package com.muyuchat.mca

/**
 * Anima is a 16-channel DiT executed by stable-diffusion.cpp with three split
 * components: the diffusion model, the Qwen-image VAE and the Qwen3-0.6B text
 * encoder (native `llm` slot). stable-diffusion.cpp forces flow prediction for
 * this architecture while the sampler itself is Euler, so the contract must not
 * reuse the SD1.5/SDXL epsilon profile.
 */
private const val ANIMA_EXECUTION_PROFILE_REVISION = 1
private const val ANIMA_DEFAULT_SIZE = 512
private const val ANIMA_DEFAULT_STEPS = 8
private const val ANIMA_DEFAULT_CFG = 6.0
private const val ANIMA_LATENT_CHANNELS = 16
private const val ANIMA_LATENT_DOWNSAMPLE = 8
private const val ANIMA_MAX_PROMPT_TOKENS = 512

internal fun animaSdcppProfile(
    profileId: String,
    fingerprint: String,
    variant: ImageModelVariant = ImageModelVariant.ANIMA_PREVIEW
): ImageExecutionProfile {
    val size = ANIMA_DEFAULT_SIZE
    val latentShape = listOf(
        1,
        ANIMA_LATENT_CHANNELS,
        size / ANIMA_LATENT_DOWNSAMPLE,
        size / ANIMA_LATENT_DOWNSAMPLE
    )
    return imageExecutionProfileContract(
        profileId = profileId,
        fingerprint = fingerprint,
        runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
        family = LocalImageModelFamily.ANIMA,
        variant = variant,
        scheduler = imageExecutionProfileSchedulerContract(
            ImageSchedulerAlgorithm.EULER,
            ImagePredictionType.FLOW,
            ANIMA_DEFAULT_STEPS,
            1,
            100
        ),
        tokenizer = imageExecutionProfileClipTokenizerContract(
            ImageTokenizerBackend.SDCPP_NATIVE,
            maxLength = ANIMA_MAX_PROMPT_TOKENS,
            supportsPromptWeighting = false,
            supportsTextualInversion = false,
            separateNegativePrompt = true
        ).copy(
            // AnimaConditioner uses the Qwen BPE tokenizer; CLIP BOS/EOS ids
            // would be false evidence even though SDCPP owns the tokenizer.
            bosId = null,
            eosId = null,
            padId = null,
            lowercase = false,
            clip1PadRule = ImageClipPadRule.MODEL_DECLARED
        ),
        conditioning = imageExecutionProfileConditioningContract(
            ImageEmbeddingDiskDataType.RUNTIME_NATIVE,
            ImageEmbeddingConversionStrategy.RUNTIME_NATIVE,
            1,
            maxLength = ANIMA_MAX_PROMPT_TOKENS,
            separateNegativePrompt = true
        ),
        vae = ImageVaeContract(
            scalingLocation = ImageVaeScalingLocation.RUNTIME_NATIVE,
            scalingFactor = 1.0,
            inputShape = latentShape,
            outputShape = listOf(1, 3, size, size),
            inputLayout = ImageTensorLayout.RUNTIME_NATIVE,
            outputLayout = ImageTensorLayout.RUNTIME_NATIVE,
            outputRange = ImagePixelRange.RUNTIME_NATIVE,
            channelOrder = ImageChannelOrder.RUNTIME_NATIVE
        ),
        graph = ImageGraphContract(workerStrategy = ImageWorkerStrategy.IN_PROCESS),
        defaults = imageExecutionProfileDefaults(
            size,
            ANIMA_DEFAULT_STEPS,
            ANIMA_DEFAULT_CFG,
            useCfg = true,
            defaultNegativePrompt = ""
        ),
        capabilities = ImageGenerationCapabilities(
            supportedSchedulers = setOf(ImageSchedulerAlgorithm.EULER),
            minWidth = 256,
            maxWidth = 1_024,
            minHeight = 256,
            maxHeight = 1_024,
            widthMultiple = 64,
            heightMultiple = 64,
            supportsNegativePrompt = true,
            supportsPromptWeighting = false,
            supportsTextualInversion = false,
            supportsVaeTiling = false,
            supportsLivePreview = false,
            supportsLora = false,
            maxBatchCount = 1
        ),
        profileRevision = ANIMA_EXECUTION_PROFILE_REVISION
    ).copy(
        latent = ImageLatentContract(
            ANIMA_LATENT_CHANNELS,
            ANIMA_LATENT_DOWNSAMPLE,
            ImageTensorLayout.NCHW,
            ImageTensorLayout.NCHW,
            latentShape
        )
    )
}

/**
 * Built-in target template for the pinned catalog bundle. Unlike the generic
 * import fallback, it records the exact three component names so the legacy
 * compatibility path matches the catalog-owned profile.
 */
internal fun animaCatalogProfile(
    profileId: String,
    fingerprint: String
): ImageExecutionProfile = animaSdcppProfile(profileId, fingerprint).copy(
    graph = ImageGraphContract(
        textEncoder = ImageGraphArtifactContract(
            "qwen_3_06b_base.safetensors",
            graphName = "runtime-native"
        ),
        unet = ImageGraphArtifactContract(
            "anima-preview-Q4_K_M.gguf",
            graphName = "runtime-native"
        ),
        vae = ImageGraphArtifactContract(
            "qwen_image_vae.safetensors",
            graphName = "runtime-native"
        ),
        workerStrategy = ImageWorkerStrategy.IN_PROCESS
    )
)
