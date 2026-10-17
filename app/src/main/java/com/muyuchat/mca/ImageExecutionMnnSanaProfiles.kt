package com.muyuchat.mca

import com.muyuchat.core.download.RecommendedImageDefaults

/** T2B22-T4: MNN SD1.5 and Sana-edit profile builders; moved verbatim from ImageExecutionProfileTemplates.kt. */

internal fun mnnSd15Profile(profileId: String, fingerprint: String): ImageExecutionProfile = imageExecutionProfileContract(
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

internal fun sanaEditProfile(profileId: String, fingerprint: String): ImageExecutionProfile = imageExecutionProfileContract(
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
