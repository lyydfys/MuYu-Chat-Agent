package com.muyuchat.mca

/**
 * The contract factories `ImageExecutionProfileResolver` uses to materialize a built-in
 * profile: one pure builder per sub-contract (scheduler, tokenizer, conditioning, VAE,
 * graph, defaults, capabilities) plus the stable-diffusion capability matrix.
 *
 * They were private members of the resolver object. They are pure factories: no device,
 * chipset, catalog or state input, and no admission decision. Every constant, branch and
 * default value is unchanged by the move; only the names gained the
 * `imageExecutionProfile` responsibility prefix so these top-level declarations cannot
 * collide with another file in this package.
 */

internal fun imageExecutionProfileContract(
    profileId: String,
    fingerprint: String,
    runtime: LocalImageRuntime,
    family: LocalImageModelFamily,
    variant: ImageModelVariant,
    scheduler: ImageSchedulerContract,
    tokenizer: ImageTokenizerContract,
    conditioning: ImageConditioningContract,
    vae: ImageVaeContract,
    graph: ImageGraphContract,
    defaults: ImageGenerationDefaults,
    capabilities: ImageGenerationCapabilities,
    task: ImageTask = ImageTask.TEXT_TO_IMAGE,
    profileRevision: Int = 1
): ImageExecutionProfile = ImageExecutionProfile(
    profileId = profileId,
    profileRevision = profileRevision,
    modelFingerprint = fingerprint,
    runtime = runtime,
    family = family,
    variant = variant,
    task = task,
    provenance = ImageProfileProvenance(ImageProfileSource.BUILT_IN, listOf(ImageProfileSource.BUILT_IN)),
    tokenizer = tokenizer,
    conditioning = conditioning,
    scheduler = scheduler,
    latent = ImageLatentContract(4, 8, ImageTensorLayout.NCHW, ImageTensorLayout.NCHW, listOf(1, 4, defaults.height / 8, defaults.width / 8)),
    vae = vae,
    graph = graph,
    defaults = defaults,
    capabilities = capabilities
)

internal fun imageExecutionProfileSchedulerContract(
    algorithm: ImageSchedulerAlgorithm,
    prediction: ImagePredictionType,
    defaultSteps: Int,
    minSteps: Int,
    maxSteps: Int,
    stepsOffset: Int = 0,
    timestepSpacing: ImageTimestepSpacing = when (algorithm) {
        ImageSchedulerAlgorithm.EULER,
        ImageSchedulerAlgorithm.DPMPP_2M -> ImageTimestepSpacing.LINSPACE
        else -> ImageTimestepSpacing.LEADING
    },
    setAlphaToOne: Boolean = false,
    scaleModelInput: Boolean = false,
    skipPrk: Boolean = false,
    clipSample: Boolean = false,
    order: Int = 1
) = ImageSchedulerContract(
    algorithm = algorithm,
    predictionType = prediction,
    noiseSchedule = if (algorithm == ImageSchedulerAlgorithm.FLOW_MATCH) ImageNoiseSchedule.SIGMA else ImageNoiseSchedule.SCALED_LINEAR,
    betaStart = if (algorithm == ImageSchedulerAlgorithm.FLOW_MATCH) null else 0.00085,
    betaEnd = if (algorithm == ImageSchedulerAlgorithm.FLOW_MATCH) null else 0.012,
    timestepSpacing = timestepSpacing,
    stepsOffset = stepsOffset,
    setAlphaToOne = setAlphaToOne,
    skipPrkSteps = skipPrk,
    clipSample = clipSample,
    scaleModelInput = scaleModelInput,
    order = order,
    defaultSteps = defaultSteps,
    minSteps = minSteps,
    maxSteps = maxSteps
)

internal fun imageExecutionProfileClipTokenizerContract(
    backend: ImageTokenizerBackend,
    maxLength: Int = 77,
    dualClip: Boolean = false,
    padZero: Boolean = false,
    supportsPromptWeighting: Boolean = backend != ImageTokenizerBackend.MNN_MTOK,
    supportsTextualInversion: Boolean = false,
    separateNegativePrompt: Boolean = true
) = ImageTokenizerContract(
    backend = backend,
    bosId = 49_406,
    eosId = 49_407,
    padId = if (dualClip || padZero) 0 else 49_407,
    maxLength = maxLength,
    clip1PadRule = if (padZero) ImageClipPadRule.ZERO else ImageClipPadRule.EOS,
    clip2PadRule = if (dualClip) ImageClipPadRule.ZERO else null,
    supportsPromptWeighting = supportsPromptWeighting,
    supportsTextualInversion = supportsTextualInversion,
    separateNegativePrompt = separateNegativePrompt
)

internal fun imageExecutionProfileConditioningContract(
    dataType: ImageEmbeddingDiskDataType,
    conversion: ImageEmbeddingConversionStrategy,
    width: Int,
    maxLength: Int = 77,
    dualEncoder: Boolean = false,
    pooled: Boolean = false,
    separateNegativePrompt: Boolean = true
) = ImageConditioningContract(
    diskDataType = dataType,
    textEncoderInputShape = listOf(1, maxLength),
    textEncoderOutputShapes = if (dualEncoder) {
        listOf(listOf(1, maxLength, 768), listOf(1, maxLength, 1_280))
    } else {
        listOf(listOf(1, maxLength, width))
    },
    conversionStrategy = conversion,
    dualEncoder = dualEncoder,
    pooledOutput = pooled,
    concatenationOrder = when {
        dualEncoder -> listOf("clip1_hidden", "clip2_hidden", "clip2_pooled")
        separateNegativePrompt -> listOf("negative", "positive")
        else -> listOf("positive")
    }
)

internal fun imageExecutionProfileVaeContract(location: ImageVaeScalingLocation, factor: Double, size: Int) = ImageVaeContract(
    scalingLocation = location,
    scalingFactor = factor,
    inputShape = listOf(1, 4, size / 8, size / 8),
    outputShape = listOf(1, 3, size, size),
    inputLayout = ImageTensorLayout.NCHW,
    outputLayout = ImageTensorLayout.NCHW,
    outputRange = ImagePixelRange.NEGATIVE_ONE_TO_ONE,
    channelOrder = ImageChannelOrder.RGB
)

internal fun imageExecutionProfileQnnGraphContract(
    textEncoder: String,
    unet: String,
    vae: String,
    qnnSdk: String?,
    htpArch: Int?,
    strategy: ImageWorkerStrategy = ImageWorkerStrategy.SHARED_TEXT_UNET_VAE,
    vaeEncoder: String? = null,
    schedulerSidecar: String? = "scheduler/scheduler_config.json",
    tokenizerSidecar: String? = "tokenizer/tokenizer_config.json",
    runtimeAssets: List<String> = emptyList()
) = ImageGraphContract(
    textEncoder = ImageGraphArtifactContract(textEncoder),
    unet = ImageGraphArtifactContract(unet),
    vae = ImageGraphArtifactContract(vae),
    vaeEncoder = vaeEncoder?.let(::ImageGraphArtifactContract),
    schedulerSidecar = schedulerSidecar,
    tokenizerSidecar = tokenizerSidecar,
    configSidecars = runtimeAssets,
    qnnSdk = qnnSdk,
    htpArch = htpArch,
    workerStrategy = strategy
)

internal fun imageExecutionProfileDefaults(
    size: Int,
    steps: Int,
    cfg: Double,
    useCfg: Boolean,
    defaultNegativePrompt: String? = null
) = ImageGenerationDefaults(
    width = size,
    height = size,
    steps = steps,
    cfgScale = cfg,
    seed = 42L,
    useCfg = useCfg,
    defaultNegativePrompt = defaultNegativePrompt
)

internal fun imageExecutionProfileCapabilities(
    size: Int,
    schedulers: Set<ImageSchedulerAlgorithm>,
    supportsPromptWeighting: Boolean = true,
    supportsTextualInversion: Boolean = false,
    supportsNegativePrompt: Boolean = true,
    supportsLivePreview: Boolean = false
) = ImageGenerationCapabilities(
    supportedSchedulers = schedulers,
    minWidth = size,
    maxWidth = size,
    minHeight = size,
    maxHeight = size,
    supportsNegativePrompt = supportsNegativePrompt,
    supportsPromptWeighting = supportsPromptWeighting,
    supportsTextualInversion = supportsTextualInversion,
    supportsLivePreview = supportsLivePreview
)

internal fun LocalImageModelFamily.imageExecutionProfileFamilySupportsTextualInversion(): Boolean =
    this == LocalImageModelFamily.SD15 ||
        this == LocalImageModelFamily.SD21 ||
        this == LocalImageModelFamily.SDXL ||
        this == LocalImageModelFamily.SD_TURBO

internal fun LocalImageModelFamily.imageExecutionProfileFamilySupportsUltraFix(): Boolean =
    this == LocalImageModelFamily.SD15 ||
        this == LocalImageModelFamily.SD21 ||
        this == LocalImageModelFamily.SDXL ||
        this == LocalImageModelFamily.SD_TURBO

internal fun imageExecutionProfileStableDiffusionCapabilities(
    schedulers: Set<ImageSchedulerAlgorithm>,
    family: LocalImageModelFamily,
    supportsNegativePrompt: Boolean = true,
    fixedSize: Int? = null
): ImageGenerationCapabilities {
    val supportsUltraFix = family.imageExecutionProfileFamilySupportsUltraFix()
    val ultraFixMultiple = if (family == LocalImageModelFamily.SDXL) 32 else 64
    return ImageGenerationCapabilities(
        supportedSchedulers = schedulers,
        minWidth = fixedSize ?: 256,
        maxWidth = fixedSize ?: 1_536,
        minHeight = fixedSize ?: 256,
        maxHeight = fixedSize ?: 1_536,
        widthMultiple = 64,
        heightMultiple = 64,
        supportsNegativePrompt = supportsNegativePrompt,
        supportsPromptWeighting = true,
        supportsTextualInversion = family.imageExecutionProfileFamilySupportsTextualInversion(),
        supportsClipSkip = family in setOf(
            LocalImageModelFamily.SD15,
            LocalImageModelFamily.SD21,
            LocalImageModelFamily.SDXL,
            LocalImageModelFamily.SD_TURBO
        ),
        supportsVaeTiling = true,
        supportsUltraFix = supportsUltraFix,
        ultraFixMinWidth = if (supportsUltraFix) 128 else 0,
        ultraFixMaxWidth = if (supportsUltraFix) 8_192 else 0,
        ultraFixMinHeight = if (supportsUltraFix) 128 else 0,
        ultraFixMaxHeight = if (supportsUltraFix) 8_192 else 0,
        ultraFixWidthMultiple = if (supportsUltraFix) ultraFixMultiple else 0,
        ultraFixHeightMultiple = if (supportsUltraFix) ultraFixMultiple else 0,
        supportsLivePreview = true,
        supportsLora = true,
        maxBatchCount = 8
    )
}
