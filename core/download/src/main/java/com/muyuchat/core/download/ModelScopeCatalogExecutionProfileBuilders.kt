package com.muyuchat.core.download

internal fun imageScheduler(
    algorithm: ImageEngineSchedulerAlgorithm,
    predictionType: ImageEnginePredictionType,
    defaultSteps: Int,
    minSteps: Int,
    maxSteps: Int,
    timestepSpacing: ImageEngineTimestepSpacing = when (algorithm) {
        ImageEngineSchedulerAlgorithm.EULER,
        ImageEngineSchedulerAlgorithm.DPMPP_2M -> ImageEngineTimestepSpacing.LINSPACE
        else -> ImageEngineTimestepSpacing.LEADING
    },
    stepsOffset: Int = 0,
    setAlphaToOne: Boolean = false,
    skipPrkSteps: Boolean = false,
    scaleModelInput: Boolean = false,
    order: Int = 1
): ImageEngineSchedulerContractSpec = ImageEngineSchedulerContractSpec(
    algorithm = algorithm,
    predictionType = predictionType,
    noiseSchedule = if (algorithm == ImageEngineSchedulerAlgorithm.FLOW_MATCH) {
        ImageEngineNoiseSchedule.SIGMA
    } else {
        ImageEngineNoiseSchedule.SCALED_LINEAR
    },
    betaStart = if (algorithm == ImageEngineSchedulerAlgorithm.FLOW_MATCH) null else 0.00085,
    betaEnd = if (algorithm == ImageEngineSchedulerAlgorithm.FLOW_MATCH) null else 0.012,
    timestepSpacing = timestepSpacing,
    stepsOffset = stepsOffset,
    setAlphaToOne = setAlphaToOne,
    skipPrkSteps = skipPrkSteps,
    scaleModelInput = scaleModelInput,
    order = order,
    defaultSteps = defaultSteps,
    minSteps = minSteps,
    maxSteps = maxSteps
)

internal fun clipTokenizer(
    backend: ImageEngineTokenizerBackend,
    maxLength: Int = 77,
    dualClip: Boolean = false,
    padZero: Boolean = false,
    modelDeclaredTokenIds: Boolean = backend == ImageEngineTokenizerBackend.MNN_MTOK,
    supportsPromptWeighting: Boolean = backend != ImageEngineTokenizerBackend.MNN_MTOK,
    supportsTextualInversion: Boolean = false,
    separateNegativePrompt: Boolean = true
): ImageEngineTokenizerContractSpec = ImageEngineTokenizerContractSpec(
    backend = backend,
    bosId = if (modelDeclaredTokenIds) null else 49_406,
    eosId = if (modelDeclaredTokenIds) null else 49_407,
    padId = when {
        modelDeclaredTokenIds -> null
        dualClip || padZero -> 0
        else -> 49_407
    },
    maxLength = maxLength,
    clip1PadRule = when {
        modelDeclaredTokenIds -> ImageEngineClipPadRule.MODEL_DECLARED
        padZero -> ImageEngineClipPadRule.ZERO
        else -> ImageEngineClipPadRule.EOS
    },
    clip2PadRule = if (dualClip) ImageEngineClipPadRule.ZERO else null,
    supportsPromptWeighting = supportsPromptWeighting,
    supportsTextualInversion = supportsTextualInversion,
    separateNegativePrompt = separateNegativePrompt
)

internal fun imageConditioning(
    diskDataType: ImageEngineEmbeddingDataType,
    conversionStrategy: ImageEngineEmbeddingConversionStrategy,
    width: Int,
    maxLength: Int = 77,
    dualEncoder: Boolean = false,
    pooledOutput: Boolean = false,
    separateNegativePrompt: Boolean = true
): ImageEngineConditioningContractSpec = ImageEngineConditioningContractSpec(
    diskDataType = diskDataType,
    conversionStrategy = conversionStrategy,
    textEncoderInputShape = listOf(1, maxLength),
    textEncoderOutputShapes = if (dualEncoder) {
        listOf(listOf(1, maxLength, 768), listOf(1, maxLength, 1_280))
    } else {
        listOf(listOf(1, maxLength, width))
    },
    dualEncoder = dualEncoder,
    pooledOutput = pooledOutput,
    concatenationOrder = when {
        dualEncoder -> listOf("clip1_hidden", "clip2_hidden", "clip2_pooled")
        separateNegativePrompt -> listOf("negative", "positive")
        else -> listOf("positive")
    }
)

internal fun imageVae(
    scalingLocation: ImageEngineVaeScalingLocation,
    scalingFactor: Double,
    size: Int
): ImageEngineVaeContractSpec = ImageEngineVaeContractSpec(
    scalingLocation = scalingLocation,
    scalingFactor = scalingFactor,
    inputShape = listOf(1, 4, size / 8, size / 8),
    outputShape = listOf(1, 3, size, size)
)

internal fun fixedImageCapabilities(
    size: Int,
    schedulers: Set<ImageEngineSchedulerAlgorithm>,
    supportsPromptWeighting: Boolean = true,
    supportsTextualInversion: Boolean = false,
    supportsNegativePrompt: Boolean = true,
    requiresControlImage: Boolean = false,
    requiresInputImage: Boolean = false,
    supportsMask: Boolean = false,
    supportsLivePreview: Boolean = false
): ImageEngineGenerationCapabilitiesSpec = ImageEngineGenerationCapabilitiesSpec(
    supportedSchedulers = schedulers,
    minWidth = size,
    maxWidth = size,
    minHeight = size,
    maxHeight = size,
    supportsNegativePrompt = supportsNegativePrompt,
    supportsPromptWeighting = supportsPromptWeighting,
    supportsTextualInversion = supportsTextualInversion,
    requiresControlImage = requiresControlImage,
    requiresInputImage = requiresInputImage,
    supportsMask = supportsMask,
    supportsLivePreview = supportsLivePreview
)

internal fun stableDiffusionCppCapabilities(
    schedulers: Set<ImageEngineSchedulerAlgorithm>,
    family: ImageEngineModelFamily,
    supportsNegativePrompt: Boolean = true,
    fixedSize: Int? = null
): ImageEngineGenerationCapabilitiesSpec {
    val supportsStableExtensions = family in setOf(
        ImageEngineModelFamily.SD15,
        ImageEngineModelFamily.SD21,
        ImageEngineModelFamily.SDXL,
        ImageEngineModelFamily.SD_TURBO
    )
    val ultraFixMultiple = if (family == ImageEngineModelFamily.SDXL) 32 else 64
    return ImageEngineGenerationCapabilitiesSpec(
        supportedSchedulers = schedulers,
        minWidth = fixedSize ?: 256,
        maxWidth = fixedSize ?: 1_536,
        minHeight = fixedSize ?: 256,
        maxHeight = fixedSize ?: 1_536,
        widthMultiple = 64,
        heightMultiple = 64,
        supportsNegativePrompt = supportsNegativePrompt,
        supportsPromptWeighting = true,
        supportsTextualInversion = supportsStableExtensions,
        supportsClipSkip = supportsStableExtensions,
        supportsVaeTiling = true,
        supportsUltraFix = supportsStableExtensions,
        ultraFixMinWidth = if (supportsStableExtensions) 128 else 0,
        ultraFixMaxWidth = if (supportsStableExtensions) 8_192 else 0,
        ultraFixMinHeight = if (supportsStableExtensions) 128 else 0,
        ultraFixMaxHeight = if (supportsStableExtensions) 8_192 else 0,
        ultraFixWidthMultiple = if (supportsStableExtensions) ultraFixMultiple else 0,
        ultraFixHeightMultiple = if (supportsStableExtensions) ultraFixMultiple else 0,
        supportsLivePreview = true,
        supportsLora = true,
        maxBatchCount = 8
    )
}

internal fun stableDiffusionCppSchedulers(
    algorithm: ImageEngineSchedulerAlgorithm
): Set<ImageEngineSchedulerAlgorithm> = when (algorithm) {
    // Flow checkpoints are trained against their flow timetable. Do
    // not advertise diffusion-only samplers that the bridge can parse
    // but the selected checkpoint cannot execute faithfully.
    ImageEngineSchedulerAlgorithm.FLOW_MATCH -> setOf(algorithm)
    ImageEngineSchedulerAlgorithm.EULER_A -> setOf(
        ImageEngineSchedulerAlgorithm.EULER_A,
        ImageEngineSchedulerAlgorithm.EULER,
        ImageEngineSchedulerAlgorithm.DPMPP_2M
    )
    else -> setOf(algorithm)
}

internal fun qnnGraph(
    textEncoder: String,
    unet: String,
    vae: String,
    qnnSdk: String?,
    htpArch: Int?,
    vaeEncoder: String? = null,
    workerStrategy: ImageEngineWorkerStrategy = ImageEngineWorkerStrategy.SHARED_TEXT_UNET_VAE,
    controlNet: String? = null,
    schedulerSidecar: String? = "scheduler/scheduler_config.json",
    tokenizerSidecar: String? = "tokenizer/tokenizer_config.json",
    runtimeAssets: List<String> = emptyList()
): ImageEngineGraphContractSpec = ImageEngineGraphContractSpec(
    textEncoder = textEncoder,
    unet = unet,
    vae = vae,
    vaeEncoder = vaeEncoder,
    controlNet = controlNet,
    schedulerSidecar = schedulerSidecar,
    tokenizerSidecar = tokenizerSidecar,
    configSidecars = runtimeAssets,
    qnnSdk = qnnSdk,
    htpArch = htpArch,
    workerStrategy = workerStrategy
)

internal fun qnnSd15ExecutionProfile(
    profileId: String,
    variant: ImageEngineModelVariant = ImageEngineModelVariant.STANDARD,
    steps: Int = 20,
    cfgScale: Double = 7.0,
    conditioningDataType: ImageEngineEmbeddingDataType = ImageEngineEmbeddingDataType.FP16,
    conversionStrategy: ImageEngineEmbeddingConversionStrategy = ImageEngineEmbeddingConversionStrategy.NONE,
    defaultNegativePrompt: String = RecommendedImageDefaults.SD15_NEGATIVE_PROMPT,
    profileRevision: Int = QNN_SD15_EXECUTION_PROFILE_REVISION
): ImageEngineExecutionProfileSpec = ImageEngineExecutionProfileSpec(
    profileId = profileId,
    profileRevision = profileRevision,
    family = ImageEngineModelFamily.SD15,
    variant = variant,
    tokenizer = clipTokenizer(
        ImageEngineTokenizerBackend.TOKENIZERS_CPP,
        supportsTextualInversion = true
    ),
    conditioning = imageConditioning(conditioningDataType, conversionStrategy, 768),
    scheduler = imageScheduler(
        algorithm = ImageEngineSchedulerAlgorithm.DPMPP_2M,
        predictionType = ImageEnginePredictionType.EPSILON,
        defaultSteps = steps,
        minSteps = if (variant == ImageEngineModelVariant.HYPER) 1 else 10,
        maxSteps = 50,
        timestepSpacing = ImageEngineTimestepSpacing.LEADING,
        order = 2
    ),
    vae = imageVae(ImageEngineVaeScalingLocation.HOST_BEFORE_GRAPH, 0.18215, 512),
    graph = qnnGraph(
        textEncoder = "clip_v2.mnn",
        unet = "unet.bin",
        vae = "vae_decoder.bin",
        qnnSdk = "2.28",
        htpArch = 68,
        vaeEncoder = "vae_encoder.bin",
        workerStrategy = ImageEngineWorkerStrategy.SHARED_UNET_VAE,
        schedulerSidecar = null,
        tokenizerSidecar = null,
        runtimeAssets = QNN_SD15_CONDITIONING_RUNTIME_ASSETS
    ),
    defaults = ImageEngineGenerationDefaultsSpec(
        width = 512,
        height = 512,
        steps = steps,
        cfgScale = cfgScale,
        useCfg = true,
        defaultNegativePrompt = defaultNegativePrompt
    ),
    capabilities = fixedImageCapabilities(
        512,
        setOf(
            ImageEngineSchedulerAlgorithm.DPMPP_2M,
            ImageEngineSchedulerAlgorithm.EULER,
            ImageEngineSchedulerAlgorithm.PNDM_PLMS
        ),
        supportsTextualInversion = true,
        supportsLivePreview = true
    ).copy(
        supportsUltraFix = true,
        ultraFixMinWidth = 512,
        ultraFixMaxWidth = 2_048,
        ultraFixMinHeight = 512,
        ultraFixMaxHeight = 2_048,
        ultraFixWidthMultiple = 64,
        ultraFixHeightMultiple = 64,
        ultraFixRequiredTileSize = 512
    )
)

internal fun qnnSdxlExecutionProfile(
    profileId: String,
    variant: ImageEngineModelVariant = ImageEngineModelVariant.SDXL_BASE,
    steps: Int = 30,
    cfgScale: Double = 7.0,
    useCfg: Boolean = true,
    timestepSpacing: ImageEngineTimestepSpacing = ImageEngineTimestepSpacing.TRAILING,
    defaultNegativePrompt: String? = RecommendedImageDefaults.SDXL_NEGATIVE_PROMPT,
    supportsNegativePrompt: Boolean = true
): ImageEngineExecutionProfileSpec = ImageEngineExecutionProfileSpec(
    profileId = profileId,
    profileRevision = QNN_SDXL_EXECUTION_PROFILE_REVISION,
    family = ImageEngineModelFamily.SDXL,
    variant = variant,
    tokenizer = clipTokenizer(
        ImageEngineTokenizerBackend.TOKENIZERS_CPP,
        dualClip = true,
        supportsTextualInversion = true,
        separateNegativePrompt = supportsNegativePrompt
    ),
    conditioning = imageConditioning(
        ImageEngineEmbeddingDataType.FP16,
        ImageEngineEmbeddingConversionStrategy.NONE,
        2_048,
        dualEncoder = true,
        pooledOutput = true
    ),
    scheduler = imageScheduler(
        algorithm = ImageEngineSchedulerAlgorithm.DPMPP_2M,
        predictionType = ImageEnginePredictionType.EPSILON,
        defaultSteps = steps,
        minSteps = 1,
        maxSteps = 50,
        timestepSpacing = timestepSpacing,
        order = 2
    ),
    vae = imageVae(ImageEngineVaeScalingLocation.HOST_BEFORE_GRAPH, 0.13025, 1024),
    graph = qnnGraph(
        textEncoder = "clip.mnn",
        unet = "unet.bin",
        vae = "vae_decoder.bin",
        qnnSdk = "2.28",
        // SDXL packages can mix graph targets (for example V75 UNet
        // with V73 VAE). The package therefore has no single HTP
        // architecture admission value; native context metadata must
        // select transport per graph.
        htpArch = null,
        vaeEncoder = "vae_encoder.bin",
        workerStrategy = ImageEngineWorkerStrategy.SPLIT_UNET_VAE,
        schedulerSidecar = null,
        tokenizerSidecar = null,
        runtimeAssets = QNN_SDXL_CONDITIONING_RUNTIME_ASSETS
    ),
    defaults = ImageEngineGenerationDefaultsSpec(
        width = 1024,
        height = 1024,
        steps = steps,
        cfgScale = cfgScale,
        useCfg = useCfg,
        defaultNegativePrompt = defaultNegativePrompt
    ),
    capabilities = fixedImageCapabilities(
        1024,
        setOf(
            ImageEngineSchedulerAlgorithm.DPMPP_2M,
            ImageEngineSchedulerAlgorithm.EULER
        ),
        supportsTextualInversion = true,
        supportsNegativePrompt = supportsNegativePrompt,
        // Split SDXL must tear down its UNet process before the VAE
        // phase; it cannot publish an in-process live preview.
        supportsLivePreview = false
    ).copy(
        supportsUltraFix = true,
        ultraFixMinWidth = 1_024,
        ultraFixMaxWidth = 2_048,
        ultraFixMinHeight = 1_024,
        ultraFixMaxHeight = 2_048,
        ultraFixWidthMultiple = 64,
        ultraFixHeightMultiple = 64,
        ultraFixRequiredTileSize = 1_024
    )
)

internal fun qnnGen5ExecutionProfile(
    profileId: String,
    sd21: Boolean,
    controlNet: Boolean = false
): ImageEngineExecutionProfileSpec {
    val task = if (controlNet) ImageEngineTask.CONTROL_IMAGE else ImageEngineTask.TEXT_TO_IMAGE
    // Names read from the publisher's pinned context binaries, not Android model ids.
    val graphPrefix = when {
        controlNet -> "controlnet_canny"
        sd21 -> "stable_diffusion_v2_1"
        else -> "stable_diffusion_v1_5"
    }
    return ImageEngineExecutionProfileSpec(
        profileId = profileId,
        profileRevision = QNN_GEN5_EXECUTION_PROFILE_REVISION,
        family = if (sd21) ImageEngineModelFamily.SD21 else ImageEngineModelFamily.SD15,
        variant = when {
            controlNet -> ImageEngineModelVariant.CONTROLNET_CANNY
            sd21 -> ImageEngineModelVariant.SD21
            else -> ImageEngineModelVariant.STANDARD
        },
        task = task,
        tokenizer = clipTokenizer(
            ImageEngineTokenizerBackend.TOKENIZERS_CPP,
            padZero = sd21,
            supportsPromptWeighting = false
        ),
        conditioning = imageConditioning(
            ImageEngineEmbeddingDataType.GRAPH_INTERNAL,
            ImageEngineEmbeddingConversionStrategy.GRAPH_EXECUTION,
            if (sd21) 1_024 else 768
        ),
        // These values are the effective pinned sidecar semantics, not
        // a class-name guess from the publisher scheduler JSON.
        scheduler = if (sd21) {
            imageScheduler(
                algorithm = ImageEngineSchedulerAlgorithm.DDIM,
                predictionType = ImageEnginePredictionType.V_PREDICTION,
                defaultSteps = 20,
                minSteps = 1,
                maxSteps = 100,
                timestepSpacing = ImageEngineTimestepSpacing.LEADING,
                stepsOffset = 1,
                setAlphaToOne = false,
                skipPrkSteps = true
            )
        } else {
            imageScheduler(
                algorithm = ImageEngineSchedulerAlgorithm.EULER,
                predictionType = ImageEnginePredictionType.EPSILON,
                defaultSteps = 20,
                minSteps = 1,
                maxSteps = 100,
                timestepSpacing = ImageEngineTimestepSpacing.LINSPACE,
                stepsOffset = 1,
                setAlphaToOne = false,
                skipPrkSteps = true,
                scaleModelInput = true
            )
        },
        vae = imageVae(ImageEngineVaeScalingLocation.GRAPH_INTERNAL, 0.18215, 512),
        graph = qnnGraph(
            textEncoder = "text_encoder.bin",
            unet = "unet.bin",
            vae = "vae.bin",
            qnnSdk = "2.45.0.260326154327",
            htpArch = 81,
            controlNet = if (controlNet) "controlnet.bin" else null
        ).copy(graphNames = buildMap {
            put("text_encoder.bin", "${graphPrefix}_text_encoder")
            put("unet.bin", "${graphPrefix}_unet")
            put("vae.bin", "${graphPrefix}_vae")
            if (controlNet) put("controlnet.bin", "${graphPrefix}_controlnet")
        }),
        defaults = ImageEngineGenerationDefaultsSpec(
            width = 512,
            height = 512,
            steps = 20,
            cfgScale = 7.5,
            useCfg = true,
            defaultNegativePrompt = RecommendedImageDefaults.SD15_NEGATIVE_PROMPT
        ),
        capabilities = fixedImageCapabilities(
            size = 512,
            schedulers = setOf(
                if (sd21) ImageEngineSchedulerAlgorithm.DDIM else ImageEngineSchedulerAlgorithm.EULER
            ),
            supportsPromptWeighting = false,
            requiresControlImage = controlNet,
            supportsLivePreview = true
        )
    )
}
