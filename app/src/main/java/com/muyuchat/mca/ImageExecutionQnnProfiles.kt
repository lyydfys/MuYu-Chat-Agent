package com.muyuchat.mca

import com.muyuchat.core.download.RecommendedImageDefaults

/** T2B22-T4: QNN execution-profile builders (SD1.5/SDXL/Gen5/ControlNet); moved verbatim from ImageExecutionProfileTemplates.kt. */

private const val QNN_SDXL_EXECUTION_PROFILE_REVISION = 7
private const val QNN_GEN5_EXECUTION_PROFILE_REVISION = 3

private val QNN_SD15_CONDITIONING_RUNTIME_ASSETS = listOf(
    "tokenizer.json",
    "token_emb.bin",
    "pos_emb.bin"
)

internal fun qnnSd15Profile(
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

internal fun qnnSdxlProfile(
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

internal fun qnnGen5Profile(
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

internal fun qnnControlNetProfile(profileId: String, fingerprint: String): ImageExecutionProfile {
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
