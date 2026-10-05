package com.muyuchat.mca

import com.muyuchat.core.download.RecommendedImageDefaults

/**
 * P5/S43: the built-in execution-profile template builders, moved verbatim out of the
 * ImageExecutionProfileResolver object together with the revision constants, runtime-asset
 * list and sdcpp scheduler set that only they use. `genericProfile` and `profileTemplate`
 * stay `internal` because the resolver object still calls them; every other builder is
 * file-private. Behavior is unchanged.
 */

private const val QNN_SD15_EXECUTION_PROFILE_REVISION = 5
private const val QNN_DREAMSHAPER_SD15_EXECUTION_PROFILE_REVISION = 6
private const val QNN_REALISTICVISIONHYPER_SD15_EXECUTION_PROFILE_REVISION = 6
private const val QNN_SDXL_EXECUTION_PROFILE_REVISION = 7
private const val QNN_GEN5_EXECUTION_PROFILE_REVISION = 3
private const val QWEN_IMAGE_21_EXECUTION_PROFILE_REVISION = 2

private val QNN_SD15_CONDITIONING_RUNTIME_ASSETS = listOf(
    "tokenizer.json",
    "token_emb.bin",
    "pos_emb.bin"
)

private val stableDiffusionCppSchedulers = setOf(
    ImageSchedulerAlgorithm.EULER,
    ImageSchedulerAlgorithm.EULER_A,
    ImageSchedulerAlgorithm.DDIM,
    ImageSchedulerAlgorithm.DPMPP_2M,
    ImageSchedulerAlgorithm.LCM,
    ImageSchedulerAlgorithm.FLOW_MATCH
)

internal fun profileTemplate(
    target: BuiltInImageProfileTarget,
    fingerprint: String
): ImageExecutionProfile = when (val profileId = target.profileId) {
    "community.sd15.qnn228" -> qnnSd15Profile(
        profileId,
        fingerprint,
        profileRevision = if (target.recommendationId == "dreamshaper_sd15_qnn228") {
            QNN_DREAMSHAPER_SD15_EXECUTION_PROFILE_REVISION
        } else {
            QNN_SD15_EXECUTION_PROFILE_REVISION
        },
        defaultNegativePrompt = if (target.recommendationId == "cyberrealistic_sd15_qnn228") {
            RecommendedImageDefaults.PHOTO_NEGATIVE_PROMPT
        } else {
            RecommendedImageDefaults.SD15_NEGATIVE_PROMPT
        }
    )
    "community.sd15.hyper.qnn228" -> qnnSd15Profile(
        profileId,
        fingerprint,
        ImageModelVariant.HYPER,
        8,
        2.0,
        defaultNegativePrompt = RecommendedImageDefaults.PHOTO_NEGATIVE_PROMPT,
        profileRevision = QNN_REALISTICVISIONHYPER_SD15_EXECUTION_PROFILE_REVISION
    )
    "community.sd15.legacy-fp32.qnn228" -> qnnSd15Profile(
        profileId,
        fingerprint,
        ImageModelVariant.LEGACY_FP32,
        conditioningType = ImageEmbeddingDiskDataType.FP32,
        conversion = ImageEmbeddingConversionStrategy.FP32_TO_FP16_STREAMING,
        defaultNegativePrompt = RecommendedImageDefaults.ANIME_NEGATIVE_PROMPT
    )
    "community.sdxl.base.qnn228" -> qnnSdxlProfile(
        profileId = profileId,
        fingerprint = fingerprint,
        steps = when (target.recommendationId) {
            "animagine_xl_v4_qnn228" -> RecommendedImageDefaults.ANIMAGINE_XL_STEPS
            "cyberrealisticxl_qnn228" -> RecommendedImageDefaults.CYBERREALISTIC_XL_STEPS
            else -> 30
        },
        cfg = when (target.recommendationId) {
            "animagine_xl_v4_qnn228" -> RecommendedImageDefaults.ANIMAGINE_XL_CFG
            "cyberrealisticxl_qnn228" -> RecommendedImageDefaults.CYBERREALISTIC_XL_CFG
            else -> 7.0
        },
        defaultNegativePrompt = when (target.recommendationId) {
            "animagine_xl_v4_qnn228" -> RecommendedImageDefaults.ANIMAGINE_XL_NEGATIVE_PROMPT
            "cyberrealisticxl_qnn228" -> RecommendedImageDefaults.CYBERREALISTIC_XL_NEGATIVE_PROMPT
            else -> RecommendedImageDefaults.SDXL_NEGATIVE_PROMPT
        }
    )
    "community.sdxl.dmd2-alt.qnn228" -> qnnSdxlProfile(
        profileId,
        fingerprint,
        variant = ImageModelVariant.DMD2_ALT,
        steps = 4,
        cfg = 1.0,
        useCfg = false,
        timestepSpacing = ImageTimestepSpacing.LINSPACE,
        defaultNegativePrompt = null,
        supportsNegativePrompt = false
    )
    "qualcomm.sd15.gen5.qnn245" -> qnnGen5Profile(profileId, fingerprint, sd21 = false)
    "qualcomm.sd21.gen5.qnn245" -> qnnGen5Profile(profileId, fingerprint, sd21 = true)
    "qualcomm.controlnet-canny.gen5.qnn245" -> qnnControlNetProfile(profileId, fingerprint)
    "mnn.sd15.official.512" -> mnnSd15Profile(profileId, fingerprint)
    "mnn.sana-edit.v2" -> sanaEditProfile(profileId, fingerprint)
    "mnn.qwen-image-2.1.opencl" -> qwenImage21MnnProfile(profileId, fingerprint)
    "sdcpp.sd-turbo" -> sdcppProfile(profileId, fingerprint, LocalImageModelFamily.SD_TURBO, ImageModelVariant.SD_TURBO, 4, 1.0, ImageSchedulerAlgorithm.EULER_A, supportsNegativePrompt = false)
    "sdcpp.z-image-turbo" -> sdcppProfile(profileId, fingerprint, LocalImageModelFamily.Z_IMAGE, ImageModelVariant.Z_IMAGE_TURBO, 8, 1.0, ImageSchedulerAlgorithm.FLOW_MATCH, supportsNegativePrompt = false, maxPromptTokens = 512)
    "sdcpp.flux2-klein" -> sdcppProfile(profileId, fingerprint, LocalImageModelFamily.FLUX, ImageModelVariant.FLUX2_KLEIN, 4, 1.0, ImageSchedulerAlgorithm.FLOW_MATCH, 1024, supportsNegativePrompt = false, maxPromptTokens = 512)
    "sdcpp.qwen-image" -> sdcppProfile(profileId, fingerprint, LocalImageModelFamily.QWEN_IMAGE, ImageModelVariant.QWEN_IMAGE, 40, 2.5, ImageSchedulerAlgorithm.FLOW_MATCH, 1024, RecommendedImageDefaults.QWEN_IMAGE_2512_NEGATIVE_PROMPT, maxPromptTokens = 512)
    "sdcpp.qwen-image-2.1" -> qwenImage21GgufProfile(profileId, fingerprint)
    "sdcpp.longcat-image" -> sdcppProfile(profileId, fingerprint, LocalImageModelFamily.LONGCAT_IMAGE, ImageModelVariant.LONGCAT_IMAGE, 20, 5.0, ImageSchedulerAlgorithm.FLOW_MATCH, 1024, RecommendedImageDefaults.LONGCAT_IMAGE_NEGATIVE_PROMPT, maxPromptTokens = 512)
    else -> error("Unknown built-in image profile target: $profileId")
}

private fun qnnSd15Profile(
    profileId: String,
    fingerprint: String,
    variant: ImageModelVariant = ImageModelVariant.STANDARD,
    steps: Int = 20,
    cfg: Double = 7.0,
    conditioningType: ImageEmbeddingDiskDataType = ImageEmbeddingDiskDataType.FP16,
    conversion: ImageEmbeddingConversionStrategy = ImageEmbeddingConversionStrategy.NONE,
    defaultNegativePrompt: String = RecommendedImageDefaults.SD15_NEGATIVE_PROMPT,
    profileRevision: Int = QNN_SD15_EXECUTION_PROFILE_REVISION
): ImageExecutionProfile = imageExecutionProfileContract(
    profileId = profileId,
    fingerprint = fingerprint,
    runtime = LocalImageRuntime.QNN_HTP,
    family = LocalImageModelFamily.SD15,
    variant = variant,
    scheduler = imageExecutionProfileSchedulerContract(
        ImageSchedulerAlgorithm.DPMPP_2M,
        ImagePredictionType.EPSILON,
        steps,
        // The Qualcomm SD1.5 bundles ship a four-step semantic smoke profile and
        // the Local API uses that same bounded request for health checks.  Keeping
        // the historical lower bound at ten makes an otherwise valid `steps=4`
        // request fail profile validation before native execution (the scheduler's
        // defaultSteps becomes smaller than minSteps).  Four is still a conservative
        // floor for this topology; one-step graph smoke remains available through
        // the explicit internal low-step-smoke override.
        if (variant == ImageModelVariant.HYPER) 1 else 4,
        50,
        timestepSpacing = ImageTimestepSpacing.LEADING,
        order = 2
    ),
    tokenizer = imageExecutionProfileClipTokenizerContract(
        ImageTokenizerBackend.TOKENIZERS_CPP,
        supportsTextualInversion = true
    ),
    conditioning = imageExecutionProfileConditioningContract(conditioningType, conversion, 768),
    vae = imageExecutionProfileVaeContract(ImageVaeScalingLocation.HOST_BEFORE_GRAPH, 0.18215, 512),
    graph = imageExecutionProfileQnnGraphContract(
        "clip_v2.mnn",
        "unet.bin",
        "vae_decoder.bin",
        "2.28",
        68,
        strategy = ImageWorkerStrategy.SHARED_UNET_VAE,
        vaeEncoder = "vae_encoder.bin",
        schedulerSidecar = null,
        tokenizerSidecar = null,
        runtimeAssets = QNN_SD15_CONDITIONING_RUNTIME_ASSETS
    ),
    defaults = imageExecutionProfileDefaults(
        512,
        steps,
        cfg,
        useCfg = true,
        defaultNegativePrompt = defaultNegativePrompt
    ),
    capabilities = imageExecutionProfileCapabilities(
        512,
        setOf(
            ImageSchedulerAlgorithm.DPMPP_2M,
            ImageSchedulerAlgorithm.EULER,
            ImageSchedulerAlgorithm.PNDM_PLMS
        ),
        supportsTextualInversion = true,
        supportsLivePreview = true
    ).copy(
        supportsUltraFix = true,
        ultraFixMinWidth = QNN_SHARED_ULTRAFIX_MIN_SIDE,
        ultraFixMaxWidth = QNN_SHARED_ULTRAFIX_MAX_SIDE,
        ultraFixMinHeight = QNN_SHARED_ULTRAFIX_MIN_SIDE,
        ultraFixMaxHeight = QNN_SHARED_ULTRAFIX_MAX_SIDE,
        ultraFixWidthMultiple = QNN_SHARED_ULTRAFIX_DIMENSION_MULTIPLE,
        ultraFixHeightMultiple = QNN_SHARED_ULTRAFIX_DIMENSION_MULTIPLE,
        ultraFixRequiredTileSize = QNN_SHARED_ULTRAFIX_TILE_SIZE
    ),
    profileRevision = profileRevision
)

private fun qnnSdxlProfile(
    profileId: String,
    fingerprint: String,
    variant: ImageModelVariant = ImageModelVariant.SDXL_BASE,
    steps: Int = 30,
    cfg: Double = 7.0,
    useCfg: Boolean = true,
    timestepSpacing: ImageTimestepSpacing = ImageTimestepSpacing.TRAILING,
    defaultNegativePrompt: String? = RecommendedImageDefaults.SDXL_NEGATIVE_PROMPT,
    supportsNegativePrompt: Boolean = true
): ImageExecutionProfile = imageExecutionProfileContract(
    profileId = profileId,
    fingerprint = fingerprint,
    runtime = LocalImageRuntime.QNN_HTP,
    family = LocalImageModelFamily.SDXL,
    variant = variant,
    scheduler = imageExecutionProfileSchedulerContract(
        ImageSchedulerAlgorithm.DPMPP_2M,
        ImagePredictionType.EPSILON,
        steps,
        1,
        50,
        timestepSpacing = timestepSpacing,
        order = 2
    ),
    tokenizer = imageExecutionProfileClipTokenizerContract(
        ImageTokenizerBackend.TOKENIZERS_CPP,
        dualClip = true,
        supportsTextualInversion = true,
        separateNegativePrompt = supportsNegativePrompt
    ),
    conditioning = imageExecutionProfileConditioningContract(ImageEmbeddingDiskDataType.FP16, ImageEmbeddingConversionStrategy.NONE, 2_048, dualEncoder = true, pooled = true),
    vae = imageExecutionProfileVaeContract(ImageVaeScalingLocation.HOST_BEFORE_GRAPH, 0.13025, 1024),
    graph = imageExecutionProfileQnnGraphContract(
        textEncoder = "clip.mnn",
        unet = "unet.bin",
        vae = "vae_decoder.bin",
        qnnSdk = "2.28",
        htpArch = null,
        strategy = ImageWorkerStrategy.SPLIT_UNET_VAE,
        vaeEncoder = "vae_encoder.bin"
    ).copy(
        schedulerSidecar = null,
        tokenizerSidecar = null,
        configSidecars = listOf("clip_2.mnn", "clip_2.mnn.weight", "tokenizer.json", "token_emb.bin",
            "token_emb_2.bin", "pos_emb.bin", "pos_emb_2.bin")
    ),
    defaults = imageExecutionProfileDefaults(1024, steps, cfg, useCfg, defaultNegativePrompt),
    capabilities = imageExecutionProfileCapabilities(
        1024,
        setOf(ImageSchedulerAlgorithm.DPMPP_2M, ImageSchedulerAlgorithm.EULER),
        supportsTextualInversion = true,
        supportsNegativePrompt = supportsNegativePrompt,
        // Split UNet/VAE workers have no resumable scheduler checkpoint. A projection frame
        // would be an approximation and is intentionally not part of the product contract.
        supportsLivePreview = false
    ).copy(
        supportsUltraFix = true,
        ultraFixMinWidth = QNN_SPLIT_SDXL_ULTRAFIX_MIN_SIDE,
        ultraFixMaxWidth = QNN_SPLIT_SDXL_ULTRAFIX_MAX_SIDE,
        ultraFixMinHeight = QNN_SPLIT_SDXL_ULTRAFIX_MIN_SIDE,
        ultraFixMaxHeight = QNN_SPLIT_SDXL_ULTRAFIX_MAX_SIDE,
        ultraFixWidthMultiple = QNN_SPLIT_SDXL_ULTRAFIX_DIMENSION_MULTIPLE,
        ultraFixHeightMultiple = QNN_SPLIT_SDXL_ULTRAFIX_DIMENSION_MULTIPLE,
        ultraFixRequiredTileSize = QNN_SPLIT_SDXL_ULTRAFIX_TILE_SIZE
    ),
    profileRevision = QNN_SDXL_EXECUTION_PROFILE_REVISION
)

private fun qnnGen5Profile(
    profileId: String,
    fingerprint: String,
    sd21: Boolean
): ImageExecutionProfile = imageExecutionProfileContract(
    profileId = profileId,
    fingerprint = fingerprint,
    runtime = LocalImageRuntime.QNN_HTP,
    family = if (sd21) LocalImageModelFamily.SD21 else LocalImageModelFamily.SD15,
    variant = if (sd21) ImageModelVariant.SD21 else ImageModelVariant.STANDARD,
    scheduler = if (sd21) {
        imageExecutionProfileSchedulerContract(
            ImageSchedulerAlgorithm.DDIM,
            ImagePredictionType.V_PREDICTION,
            20,
            1,
            100,
            stepsOffset = 1,
            timestepSpacing = ImageTimestepSpacing.LEADING,
            setAlphaToOne = false,
            skipPrk = true,
            clipSample = false
        )
    } else {
        imageExecutionProfileSchedulerContract(
            ImageSchedulerAlgorithm.EULER,
            ImagePredictionType.EPSILON,
            20,
            1,
            100,
            stepsOffset = 1,
            timestepSpacing = ImageTimestepSpacing.LINSPACE,
            setAlphaToOne = false,
            scaleModelInput = true,
            skipPrk = true
        )
    },
    tokenizer = imageExecutionProfileClipTokenizerContract(
        ImageTokenizerBackend.TOKENIZERS_CPP,
        padZero = sd21,
        supportsPromptWeighting = false
    ),
    conditioning = imageExecutionProfileConditioningContract(ImageEmbeddingDiskDataType.GRAPH_INTERNAL, ImageEmbeddingConversionStrategy.GRAPH_EXECUTION, if (sd21) 1_024 else 768),
    vae = imageExecutionProfileVaeContract(ImageVaeScalingLocation.GRAPH_INTERNAL, 0.18215, 512),
    graph = qnnGen5Graph(if (sd21) "stable_diffusion_v2_1" else "stable_diffusion_v1_5"),
    defaults = imageExecutionProfileDefaults(
        512,
        20,
        7.5,
        useCfg = true,
        defaultNegativePrompt = RecommendedImageDefaults.SD15_NEGATIVE_PROMPT
    ),
    capabilities = imageExecutionProfileCapabilities(
        512,
        setOf(if (sd21) ImageSchedulerAlgorithm.DDIM else ImageSchedulerAlgorithm.EULER),
        supportsPromptWeighting = false,
        supportsLivePreview = true
    ),
    profileRevision = QNN_GEN5_EXECUTION_PROFILE_REVISION
)

private fun qnnControlNetProfile(profileId: String, fingerprint: String): ImageExecutionProfile {
    val base = qnnGen5Profile(profileId, fingerprint, sd21 = false)
    return base.copy(
        variant = ImageModelVariant.CONTROLNET_CANNY,
        task = ImageTask.CONTROL_IMAGE,
        graph = qnnGen5Graph("controlnet_canny", controlNet = true),
        capabilities = base.capabilities.copy(requiresControlImage = true)
    )
}

private fun qnnGen5Graph(prefix: String, controlNet: Boolean = false): ImageGraphContract =
    imageExecutionProfileQnnGraphContract("text_encoder.bin", "unet.bin", "vae.bin", "2.45.0.260326154327", 81).copy(
        textEncoder = ImageGraphArtifactContract("text_encoder.bin", "${prefix}_text_encoder"),
        unet = ImageGraphArtifactContract("unet.bin", "${prefix}_unet"),
        vae = ImageGraphArtifactContract("vae.bin", "${prefix}_vae"),
        controlNet = if (controlNet) ImageGraphArtifactContract("controlnet.bin", "${prefix}_controlnet") else null
    )

private fun mnnSd15Profile(profileId: String, fingerprint: String): ImageExecutionProfile = imageExecutionProfileContract(
    profileId = profileId,
    fingerprint = fingerprint,
    runtime = LocalImageRuntime.MNN_DIFFUSION,
    family = LocalImageModelFamily.SD15,
    variant = ImageModelVariant.STANDARD,
    scheduler = imageExecutionProfileSchedulerContract(
        ImageSchedulerAlgorithm.DPMPP_2M,
        ImagePredictionType.EPSILON,
        20,
        10,
        50,
        timestepSpacing = ImageTimestepSpacing.LEADING,
        order = 2
    ),
    tokenizer = imageExecutionProfileClipTokenizerContract(
        ImageTokenizerBackend.MNN_MTOK,
        supportsPromptWeighting = false
    ),
    conditioning = imageExecutionProfileConditioningContract(ImageEmbeddingDiskDataType.GRAPH_INTERNAL, ImageEmbeddingConversionStrategy.GRAPH_EXECUTION, 768),
    vae = imageExecutionProfileVaeContract(ImageVaeScalingLocation.HOST_BEFORE_GRAPH, 0.18215, 512),
    graph = ImageGraphContract(
        textEncoder = ImageGraphArtifactContract("text_encoder.mnn"),
        unet = ImageGraphArtifactContract("unet.mnn"),
        vae = ImageGraphArtifactContract("vae_decoder.mnn"),
        workerStrategy = ImageWorkerStrategy.IN_PROCESS
    ),
    defaults = imageExecutionProfileDefaults(
        512,
        20,
        7.0,
        useCfg = true,
        defaultNegativePrompt = RecommendedImageDefaults.SD15_NEGATIVE_PROMPT
    ),
    capabilities = imageExecutionProfileCapabilities(
        512,
        setOf(
            ImageSchedulerAlgorithm.DPMPP_2M,
            ImageSchedulerAlgorithm.EULER,
            ImageSchedulerAlgorithm.PNDM_PLMS
        ),
        supportsPromptWeighting = false
    )
)

private fun sanaEditProfile(profileId: String, fingerprint: String): ImageExecutionProfile = imageExecutionProfileContract(
    profileId = profileId,
    fingerprint = fingerprint,
    runtime = LocalImageRuntime.MNN_DIFFUSION,
    family = LocalImageModelFamily.SANA,
    variant = ImageModelVariant.SANA_EDIT,
    task = ImageTask.IMAGE_EDIT,
    scheduler = imageExecutionProfileSchedulerContract(
        ImageSchedulerAlgorithm.FLOW_MATCH,
        ImagePredictionType.FLOW,
        10,
        2,
        50
    ),
    tokenizer = ImageTokenizerContract(
        backend = ImageTokenizerBackend.MNN_MTOK,
        maxLength = 256,
        clip1PadRule = ImageClipPadRule.MODEL_DECLARED,
        supportsPromptWeighting = false,
        separateNegativePrompt = true
    ),
    conditioning = imageExecutionProfileConditioningContract(
        ImageEmbeddingDiskDataType.GRAPH_INTERNAL,
        ImageEmbeddingConversionStrategy.GRAPH_EXECUTION,
        width = 1,
        maxLength = 256
    ),
    vae = imageExecutionProfileVaeContract(ImageVaeScalingLocation.RUNTIME_NATIVE, 1.0, 512),
    graph = ImageGraphContract(
        textEncoder = ImageGraphArtifactContract("llm/llm.mnn"),
        unet = ImageGraphArtifactContract("transformer.mnn"),
        vae = ImageGraphArtifactContract("vae_decoder.mnn"),
        vaeEncoder = ImageGraphArtifactContract("vae_encoder.mnn"),
        configSidecars = listOf("llm/meta_queries.mnn"),
        workerStrategy = ImageWorkerStrategy.DEDICATED_WORKER
    ),
    defaults = imageExecutionProfileDefaults(
        512,
        10,
        4.5,
        useCfg = true,
        defaultNegativePrompt = RecommendedImageDefaults.EDIT_NEGATIVE_PROMPT
    ),
    capabilities = imageExecutionProfileCapabilities(
        512,
        setOf(ImageSchedulerAlgorithm.FLOW_MATCH),
        supportsPromptWeighting = false
    ).copy(requiresInputImage = true, supportsMask = false)
)

private fun qwenImage21MnnProfile(profileId: String, fingerprint: String): ImageExecutionProfile = imageExecutionProfileContract(
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

private fun qwenImage21GgufProfile(profileId: String, fingerprint: String): ImageExecutionProfile {
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

private fun sdcppProfile(
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
