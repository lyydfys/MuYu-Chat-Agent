package com.muyuchat.mca

/** T2B22-T4: Qwen-Image MNN/GGUF and stable-diffusion.cpp profile builders; moved verbatim from ImageExecutionProfileTemplates.kt. */

private const val QWEN_IMAGE_21_EXECUTION_PROFILE_REVISION = 2

internal fun qwenImage21MnnProfile(profileId: String, fingerprint: String): ImageExecutionProfile = imageExecutionProfileContract(
    profileId = profileId,
    fingerprint = fingerprint,
    runtime = LocalImageRuntime.MNN_DIFFUSION,
    family = LocalImageModelFamily.QWEN_IMAGE,
    variant = ImageModelVariant.QWEN_IMAGE_21,
    scheduler = imageExecutionProfileSchedulerContract(
        ImageSchedulerAlgorithm.FLOW_MATCH,
        ImagePredictionType.FLOW,
        20,
        2,
        40
    ),
    tokenizer = ImageTokenizerContract(
        backend = ImageTokenizerBackend.MNN_QWEN_IMAGE,
        maxLength = 512,
        clip1PadRule = ImageClipPadRule.MODEL_DECLARED,
        supportsPromptWeighting = false,
        supportsTextualInversion = false,
        separateNegativePrompt = false
    ),
    conditioning = imageExecutionProfileConditioningContract(
        ImageEmbeddingDiskDataType.GRAPH_INTERNAL,
        ImageEmbeddingConversionStrategy.GRAPH_EXECUTION,
        width = 4_096,
        maxLength = 512,
        separateNegativePrompt = false
    ),
    vae = ImageVaeContract(
        scalingLocation = ImageVaeScalingLocation.RUNTIME_NATIVE,
        scalingFactor = 1.0,
        inputShape = listOf(1, 64, 32, 32),
        outputShape = listOf(1, 4, 512, 512),
        inputLayout = ImageTensorLayout.RUNTIME_NATIVE,
        outputLayout = ImageTensorLayout.RUNTIME_NATIVE,
        outputRange = ImagePixelRange.RUNTIME_NATIVE,
        channelOrder = ImageChannelOrder.RUNTIME_NATIVE
    ),
    graph = ImageGraphContract(
        textEncoder = ImageGraphArtifactContract("text_encoder/llm.mnn"),
        unet = ImageGraphArtifactContract("dit.mnn"),
        vae = ImageGraphArtifactContract("vae_decoder.mnn"),
        // This is an MNN runtime tokenizer table, not a JSON tokenizer config sidecar.
        tokenizerSidecar = null,
        configSidecars = listOf(
            "text_encoder/te_config.json",
            "text_encoder/te_llm_config.json",
            "text_encoder/llm_config.json",
            "text_encoder/embeddings_int4.bin",
            "txt_in.mnn",
            "txt_in.mnn.weight",
            "img_in.mnn",
            "img_in.mnn.weight"
        ),
        workerStrategy = ImageWorkerStrategy.DEDICATED_WORKER
    ),
    defaults = imageExecutionProfileDefaults(512, 20, 1.0, useCfg = false),
    capabilities = ImageGenerationCapabilities(
        // The pinned MNN port resizes the DiT/VAE graph. Only the explicit
        // QwenImage21SizeContract grid is product-verified; provider and
        // worker admission reject other aligned pairs inside these bounds.
        minWidth = 256,
        maxWidth = 672,
        minHeight = 256,
        maxHeight = 672,
        widthMultiple = 32,
        heightMultiple = 32,
        supportedSchedulers = setOf(ImageSchedulerAlgorithm.FLOW_MATCH),
        supportsNegativePrompt = false,
        supportsPromptWeighting = false,
        supportsTextualInversion = false,
        supportsVaeTiling = false,
        supportsLivePreview = false,
        supportsLora = false,
        maxBatchCount = 1
    ),
    profileRevision = QWEN_IMAGE_21_EXECUTION_PROFILE_REVISION
)

internal fun qwenImage21GgufProfile(profileId: String, fingerprint: String): ImageExecutionProfile {
    val base = sdcppProfile(
        profileId, fingerprint, LocalImageModelFamily.QWEN_IMAGE, ImageModelVariant.QWEN_IMAGE_21,
        20, 6.0, ImageSchedulerAlgorithm.FLOW_MATCH, 512, defaultNegativePrompt = "", maxPromptTokens = 512
    )
    return base.copy(
        profileRevision = 3,
        tokenizer = base.tokenizer.copy(
            bosId = null, eosId = null, padId = null, lowercase = false,
            supportsPromptWeighting = false, clip1PadRule = ImageClipPadRule.MODEL_DECLARED
        ),
        conditioning = imageExecutionProfileConditioningContract(ImageEmbeddingDiskDataType.RUNTIME_NATIVE, ImageEmbeddingConversionStrategy.RUNTIME_NATIVE, 4096, maxLength = 512),
        latent = ImageLatentContract(64, 16, ImageTensorLayout.NCHW, ImageTensorLayout.NCHW, listOf(1, 64, 32, 32)),
        vae = base.vae.copy(
            inputShape = listOf(1, 64, 32, 32),
            outputShape = listOf(1, 4, 512, 512),
            inputLayout = ImageTensorLayout.RUNTIME_NATIVE,
            outputLayout = ImageTensorLayout.RUNTIME_NATIVE,
            outputRange = ImagePixelRange.RUNTIME_NATIVE,
            channelOrder = ImageChannelOrder.RUNTIME_NATIVE
        ),
        graph = ImageGraphContract(
            // stable-diffusion.cpp exposes one runtime-owned graph for the
            // split Qwen artifacts; `model` is only the legacy QNN/MNN
            // default and is rejected by the native contract.
            textEncoder = ImageGraphArtifactContract(
                "Qwen3-VL-8B-Instruct-UD-Q4_K_XL.gguf",
                graphName = "runtime-native"
            ),
            unet = ImageGraphArtifactContract(
                "qwen-image-2.1-Q4_K_M.gguf",
                graphName = "runtime-native"
            ),
            vae = ImageGraphArtifactContract(
                "qwen_image_2.1_vae_bf16.safetensors",
                graphName = "runtime-native"
            ),
            workerStrategy = ImageWorkerStrategy.IN_PROCESS
        ),
        capabilities = base.capabilities.copy(
            minWidth = 256, maxWidth = 1536, minHeight = 256, maxHeight = 1536,
            widthMultiple = 32, heightMultiple = 32,
            supportsPromptWeighting = false, supportsLora = true,
            supportsLivePreview = false, supportsUltraFix = false, maxBatchCount = 1
        )
    )
}

internal fun sdcppProfile(
    profileId: String,
    fingerprint: String,
    family: LocalImageModelFamily,
    variant: ImageModelVariant,
    steps: Int,
    cfg: Double,
    algorithm: ImageSchedulerAlgorithm,
    size: Int = 512,
    defaultNegativePrompt: String? = null,
    supportsNegativePrompt: Boolean = true,
    runtime: LocalImageRuntime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
    maxPromptTokens: Int = 77
): ImageExecutionProfile = imageExecutionProfileContract(
    profileId = profileId,
    fingerprint = fingerprint,
    runtime = runtime,
    family = family,
    variant = variant,
    scheduler = imageExecutionProfileSchedulerContract(
        algorithm,
        if (algorithm == ImageSchedulerAlgorithm.FLOW_MATCH) ImagePredictionType.FLOW else ImagePredictionType.EPSILON,
        steps,
        1,
        100
    ),
    tokenizer = imageExecutionProfileClipTokenizerContract(
        ImageTokenizerBackend.SDCPP_NATIVE,
        maxLength = maxPromptTokens,
        separateNegativePrompt = supportsNegativePrompt,
        supportsTextualInversion = runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP &&
            family.imageExecutionProfileFamilySupportsTextualInversion()
    ),
    conditioning = imageExecutionProfileConditioningContract(
        ImageEmbeddingDiskDataType.RUNTIME_NATIVE,
        ImageEmbeddingConversionStrategy.RUNTIME_NATIVE,
        1,
        maxLength = maxPromptTokens,
        separateNegativePrompt = supportsNegativePrompt
    ),
    vae = imageExecutionProfileVaeContract(ImageVaeScalingLocation.RUNTIME_NATIVE, 1.0, size),
    graph = ImageGraphContract(workerStrategy = ImageWorkerStrategy.IN_PROCESS),
    defaults = imageExecutionProfileDefaults(
        size,
        steps,
        cfg,
        useCfg = kotlin.math.abs(cfg - 1.0) > 1.0e-12,
        defaultNegativePrompt = defaultNegativePrompt
    ),
    capabilities = imageExecutionProfileStableDiffusionCapabilities(
        when (algorithm) {
            ImageSchedulerAlgorithm.FLOW_MATCH -> setOf(ImageSchedulerAlgorithm.FLOW_MATCH)
            ImageSchedulerAlgorithm.EULER_A -> setOf(
                ImageSchedulerAlgorithm.EULER_A,
                ImageSchedulerAlgorithm.EULER,
                ImageSchedulerAlgorithm.DPMPP_2M
            )
            else -> setOf(algorithm)
        },
        family = family,
        supportsNegativePrompt = supportsNegativePrompt,
        fixedSize = size
    ),
    profileRevision = if (family.imageExecutionProfileFamilySupportsTextualInversion() || maxPromptTokens > 77) 2 else 1
)
