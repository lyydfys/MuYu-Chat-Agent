package com.muyuchat.core.download

internal fun mnnSd15ExecutionProfile(): ImageEngineExecutionProfileSpec =
    ImageEngineExecutionProfileSpec(
        profileId = "mnn.sd15.official.512",
        family = ImageEngineModelFamily.SD15,
        variant = ImageEngineModelVariant.STANDARD,
        tokenizer = clipTokenizer(
            ImageEngineTokenizerBackend.MNN_MTOK,
            modelDeclaredTokenIds = false,
            supportsPromptWeighting = false
        ),
        conditioning = imageConditioning(
            ImageEngineEmbeddingDataType.GRAPH_INTERNAL,
            ImageEngineEmbeddingConversionStrategy.GRAPH_EXECUTION,
            768
        ),
        scheduler = imageScheduler(
            algorithm = ImageEngineSchedulerAlgorithm.DPMPP_2M,
            predictionType = ImageEnginePredictionType.EPSILON,
            defaultSteps = 20,
            minSteps = 10,
            maxSteps = 50,
            timestepSpacing = ImageEngineTimestepSpacing.LEADING,
            order = 2
        ),
        vae = imageVae(ImageEngineVaeScalingLocation.HOST_BEFORE_GRAPH, 0.18215, 512),
        graph = ImageEngineGraphContractSpec(
            textEncoder = "text_encoder.mnn",
            unet = "unet.mnn",
            vae = "vae_decoder.mnn",
            workerStrategy = ImageEngineWorkerStrategy.IN_PROCESS
        ),
        defaults = ImageEngineGenerationDefaultsSpec(
            width = 512,
            height = 512,
            steps = 20,
            cfgScale = 7.0,
            useCfg = true,
            defaultNegativePrompt = RecommendedImageDefaults.SD15_NEGATIVE_PROMPT
        ),
        capabilities = fixedImageCapabilities(
            512,
            setOf(
                ImageEngineSchedulerAlgorithm.DPMPP_2M,
                ImageEngineSchedulerAlgorithm.EULER,
                ImageEngineSchedulerAlgorithm.PNDM_PLMS
            ),
            supportsPromptWeighting = false
        )
    )

internal fun sanaEditExecutionProfile(): ImageEngineExecutionProfileSpec =
    ImageEngineExecutionProfileSpec(
        profileId = "mnn.sana-edit.v2",
        family = ImageEngineModelFamily.SANA,
        variant = ImageEngineModelVariant.SANA_EDIT,
        task = ImageEngineTask.IMAGE_EDIT,
        tokenizer = clipTokenizer(ImageEngineTokenizerBackend.MNN_MTOK, maxLength = 256),
        conditioning = imageConditioning(
            ImageEngineEmbeddingDataType.GRAPH_INTERNAL,
            ImageEngineEmbeddingConversionStrategy.GRAPH_EXECUTION,
            width = 1,
            maxLength = 256
        ),
        scheduler = imageScheduler(
            algorithm = ImageEngineSchedulerAlgorithm.FLOW_MATCH,
            predictionType = ImageEnginePredictionType.FLOW,
            defaultSteps = 10,
            minSteps = 2,
            maxSteps = 50
        ),
        vae = imageVae(ImageEngineVaeScalingLocation.RUNTIME_NATIVE, 1.0, 512),
        graph = ImageEngineGraphContractSpec(
            textEncoder = "llm/llm.mnn",
            unet = "transformer.mnn",
            vae = "vae_decoder.mnn",
            vaeEncoder = "vae_encoder.mnn",
            configSidecars = listOf("llm/meta_queries.mnn"),
            workerStrategy = ImageEngineWorkerStrategy.DEDICATED_WORKER
        ),
        defaults = ImageEngineGenerationDefaultsSpec(
            width = 512,
            height = 512,
            steps = 10,
            cfgScale = 4.5,
            useCfg = true,
            defaultNegativePrompt = RecommendedImageDefaults.EDIT_NEGATIVE_PROMPT
        ),
        capabilities = fixedImageCapabilities(
            size = 512,
            schedulers = setOf(ImageEngineSchedulerAlgorithm.FLOW_MATCH),
            supportsPromptWeighting = false,
            requiresInputImage = true,
            supportsMask = false
        )
    )

internal fun stableDiffusionCppExecutionProfile(
    profileId: String,
    family: ImageEngineModelFamily,
    variant: ImageEngineModelVariant,
    steps: Int,
    cfgScale: Double,
    algorithm: ImageEngineSchedulerAlgorithm,
    size: Int = 512,
    defaultNegativePrompt: String? = null,
    supportsNegativePrompt: Boolean = true,
    maxPromptTokens: Int = 77
): ImageEngineExecutionProfileSpec {
    val supportsStableExtensions = family in setOf(
        ImageEngineModelFamily.SD15,
        ImageEngineModelFamily.SD21,
        ImageEngineModelFamily.SDXL,
        ImageEngineModelFamily.SD_TURBO
    )
    return ImageEngineExecutionProfileSpec(
        profileId = profileId,
        profileRevision = if (supportsStableExtensions || maxPromptTokens > 77) 2 else 1,
        family = family,
        variant = variant,
        tokenizer = clipTokenizer(
            ImageEngineTokenizerBackend.SDCPP_NATIVE,
            maxLength = maxPromptTokens,
            supportsTextualInversion = supportsStableExtensions,
            separateNegativePrompt = supportsNegativePrompt
        ),
        conditioning = imageConditioning(
            ImageEngineEmbeddingDataType.RUNTIME_NATIVE,
            ImageEngineEmbeddingConversionStrategy.RUNTIME_NATIVE,
            1,
            maxLength = maxPromptTokens,
            separateNegativePrompt = supportsNegativePrompt
        ),
        scheduler = imageScheduler(
            algorithm = algorithm,
            predictionType = if (algorithm == ImageEngineSchedulerAlgorithm.FLOW_MATCH) {
                ImageEnginePredictionType.FLOW
            } else {
                ImageEnginePredictionType.EPSILON
            },
            defaultSteps = steps,
            minSteps = 1,
            maxSteps = 100
        ),
        vae = imageVae(ImageEngineVaeScalingLocation.RUNTIME_NATIVE, 1.0, size),
        graph = ImageEngineGraphContractSpec(workerStrategy = ImageEngineWorkerStrategy.IN_PROCESS),
        defaults = ImageEngineGenerationDefaultsSpec(
            width = size,
            height = size,
            steps = steps,
            cfgScale = cfgScale,
            useCfg = stableDiffusionCppUsesCfg(cfgScale),
            defaultNegativePrompt = defaultNegativePrompt
        ),
        capabilities = stableDiffusionCppCapabilities(
            stableDiffusionCppSchedulers(algorithm),
            family = family,
            supportsNegativePrompt = supportsNegativePrompt,
            fixedSize = size
        )
    )
}

internal fun recommendedImageExecutionProfile(
    recommendationId: String
): ImageEngineExecutionProfileSpec = when (recommendationId) {
    "cyberrealistic_sd15_qnn228" -> qnnSd15ExecutionProfile(
        profileId = "community.sd15.qnn228",
        defaultNegativePrompt = RecommendedImageDefaults.PHOTO_NEGATIVE_PROMPT
    )
    "dreamshaper_sd15_qnn228" -> qnnSd15ExecutionProfile(
        profileId = "community.sd15.qnn228",
        defaultNegativePrompt = RecommendedImageDefaults.SD15_NEGATIVE_PROMPT,
        profileRevision = QNN_DREAMSHAPER_SD15_EXECUTION_PROFILE_REVISION
    )
    "realisticvisionhyper_sd15_qnn228" -> qnnSd15ExecutionProfile(
        profileId = "community.sd15.hyper.qnn228",
        variant = ImageEngineModelVariant.HYPER,
        steps = 8,
        cfgScale = 2.0,
        defaultNegativePrompt = RecommendedImageDefaults.PHOTO_NEGATIVE_PROMPT,
        profileRevision = QNN_REALISTICVISIONHYPER_SD15_EXECUTION_PROFILE_REVISION
    )
    "meinamix_sd15_qnn228" -> qnnSd15ExecutionProfile(
        profileId = "community.sd15.legacy-fp32.qnn228",
        variant = ImageEngineModelVariant.LEGACY_FP32,
        conditioningDataType = ImageEngineEmbeddingDataType.FP32,
        conversionStrategy = ImageEngineEmbeddingConversionStrategy.FP32_TO_FP16_STREAMING,
        defaultNegativePrompt = RecommendedImageDefaults.ANIME_NEGATIVE_PROMPT
    )
    "sdxl_base_qnn228" -> qnnSdxlExecutionProfile(
        profileId = "community.sdxl.base.qnn228",
        defaultNegativePrompt = RecommendedImageDefaults.SDXL_NEGATIVE_PROMPT
    )
    "animagine_xl_v4_qnn228" -> qnnSdxlExecutionProfile(
        profileId = "community.sdxl.base.qnn228",
        steps = RecommendedImageDefaults.ANIMAGINE_XL_STEPS,
        cfgScale = RecommendedImageDefaults.ANIMAGINE_XL_CFG,
        defaultNegativePrompt = RecommendedImageDefaults.ANIMAGINE_XL_NEGATIVE_PROMPT
    )
    "cyberrealisticxl_qnn228" -> qnnSdxlExecutionProfile(
        profileId = "community.sdxl.base.qnn228",
        steps = RecommendedImageDefaults.CYBERREALISTIC_XL_STEPS,
        cfgScale = RecommendedImageDefaults.CYBERREALISTIC_XL_CFG,
        defaultNegativePrompt = RecommendedImageDefaults.CYBERREALISTIC_XL_NEGATIVE_PROMPT
    )
    "realismsdxl_dmd2_alt_qnn228" -> qnnSdxlExecutionProfile(
        profileId = "community.sdxl.dmd2-alt.qnn228",
        variant = ImageEngineModelVariant.DMD2_ALT,
        steps = 4,
        cfgScale = 1.0,
        useCfg = false,
        timestepSpacing = ImageEngineTimestepSpacing.LINSPACE,
        defaultNegativePrompt = null,
        supportsNegativePrompt = false
    )
    "qualcomm_sd15_gen5_qnn" -> qnnGen5ExecutionProfile(
        profileId = "qualcomm.sd15.gen5.qnn245",
        sd21 = false
    )
    "qualcomm_sd21_gen5_qnn" -> qnnGen5ExecutionProfile(
        profileId = "qualcomm.sd21.gen5.qnn245",
        sd21 = true
    )
    "qualcomm_controlnet_canny_gen5_qnn" -> qnnGen5ExecutionProfile(
        profileId = "qualcomm.controlnet-canny.gen5.qnn245",
        sd21 = false,
        controlNet = true
    )
    "sd15_mnn_512_quality" -> mnnSd15ExecutionProfile()
    "mnn_sana_edit_v2" -> sanaEditExecutionProfile()
    "sd_turbo_512_experimental" -> stableDiffusionCppExecutionProfile(
        profileId = "sdcpp.sd-turbo",
        family = ImageEngineModelFamily.SD_TURBO,
        variant = ImageEngineModelVariant.SD_TURBO,
        steps = 4,
        // A zero native guidance scale selects the unconditional
        // branch. Distilled conditional-only execution uses 1.0 and
        // disables the negative CFG branch explicitly.
        cfgScale = 1.0,
        algorithm = ImageEngineSchedulerAlgorithm.EULER_A,
        supportsNegativePrompt = false
    )
    "z_image_turbo_q4" -> stableDiffusionCppExecutionProfile(
        maxPromptTokens = 512,
        profileId = "sdcpp.z-image-turbo",
        family = ImageEngineModelFamily.Z_IMAGE,
        variant = ImageEngineModelVariant.Z_IMAGE_TURBO,
        steps = 8,
        cfgScale = 1.0,
        algorithm = ImageEngineSchedulerAlgorithm.FLOW_MATCH,
        supportsNegativePrompt = false
    )
    "flux2_klein_4b_q4" -> stableDiffusionCppExecutionProfile(
        maxPromptTokens = 512,
        profileId = "sdcpp.flux2-klein",
        family = ImageEngineModelFamily.FLUX,
        variant = ImageEngineModelVariant.FLUX2_KLEIN,
        // The recommended file is the distilled Klein checkpoint,
        // not the separately published Klein Base checkpoint.
        steps = 4,
        cfgScale = 1.0,
        algorithm = ImageEngineSchedulerAlgorithm.FLOW_MATCH,
        size = 1024,
        supportsNegativePrompt = false
    )
    "qwen_image_21_mnn_opencl" -> qwenImage21MnnExecutionProfile()
    "qwen_image_21_q4_k_m" -> qwenImage21GgufExecutionProfile()
    "qwen_image_2512_q2" -> stableDiffusionCppExecutionProfile(
        maxPromptTokens = 512,
        profileId = "sdcpp.qwen-image",
        family = ImageEngineModelFamily.QWEN_IMAGE,
        variant = ImageEngineModelVariant.QWEN_IMAGE,
        steps = 40,
        cfgScale = 2.5,
        algorithm = ImageEngineSchedulerAlgorithm.FLOW_MATCH,
        size = 1024,
        defaultNegativePrompt = RecommendedImageDefaults.QWEN_IMAGE_2512_NEGATIVE_PROMPT
    )
    "longcat_image_q4" -> stableDiffusionCppExecutionProfile(
        maxPromptTokens = 512,
        profileId = "sdcpp.longcat-image",
        family = ImageEngineModelFamily.LONGCAT_IMAGE,
        variant = ImageEngineModelVariant.LONGCAT_IMAGE,
        steps = 20,
        cfgScale = 5.0,
        algorithm = ImageEngineSchedulerAlgorithm.FLOW_MATCH,
        size = 1024,
        defaultNegativePrompt = RecommendedImageDefaults.LONGCAT_IMAGE_NEGATIVE_PROMPT
    )
    else -> error("Unknown catalog image execution profile: $recommendationId")
}

internal fun qwenImage21GgufExecutionProfile(): ImageEngineExecutionProfileSpec {
    val base = stableDiffusionCppExecutionProfile(
        profileId = "sdcpp.qwen-image-2.1",
        family = ImageEngineModelFamily.QWEN_IMAGE,
        variant = ImageEngineModelVariant.QWEN_IMAGE_21,
        steps = 20,
        cfgScale = 6.0,
        algorithm = ImageEngineSchedulerAlgorithm.FLOW_MATCH,
        size = 512,
        defaultNegativePrompt = "",
        maxPromptTokens = 512
    )
    return base.copy(
        profileRevision = 3,
        tokenizer = base.tokenizer.copy(
            bosId = null,
            eosId = null,
            padId = null,
            clip1PadRule = ImageEngineClipPadRule.MODEL_DECLARED,
            supportsPromptWeighting = false
        ),
        conditioning = imageConditioning(
            ImageEngineEmbeddingDataType.RUNTIME_NATIVE,
            ImageEngineEmbeddingConversionStrategy.RUNTIME_NATIVE,
            width = 4_096,
            maxLength = 512
        ),
        vae = ImageEngineVaeContractSpec(
            scalingLocation = ImageEngineVaeScalingLocation.RUNTIME_NATIVE,
            scalingFactor = 1.0,
            inputShape = listOf(1, 64, 32, 32),
            outputShape = listOf(1, 4, 512, 512),
            inputLayout = ImageEngineTensorLayout.RUNTIME_NATIVE,
            outputLayout = ImageEngineTensorLayout.RUNTIME_NATIVE,
            outputRange = ImageEnginePixelRange.RUNTIME_NATIVE,
            channelOrder = ImageEngineChannelOrder.RUNTIME_NATIVE
        ),
        graph = ImageEngineGraphContractSpec(
            textEncoder = "Qwen3-VL-8B-Instruct-UD-Q4_K_XL.gguf",
            unet = "qwen-image-2.1-Q4_K_M.gguf",
            vae = "qwen_image_2.1_vae_bf16.safetensors",
            workerStrategy = ImageEngineWorkerStrategy.IN_PROCESS
        ),
        capabilities = ImageEngineGenerationCapabilitiesSpec(
            supportedSchedulers = setOf(ImageEngineSchedulerAlgorithm.FLOW_MATCH),
            minWidth = 256,
            maxWidth = 1_536,
            minHeight = 256,
            maxHeight = 1_536,
            widthMultiple = 32,
            heightMultiple = 32,
            supportsNegativePrompt = true,
            supportsVaeTiling = true,
            supportsLora = true
        )
    )
}

internal fun qwenImage21MnnExecutionProfile(): ImageEngineExecutionProfileSpec =
    ImageEngineExecutionProfileSpec(
        profileId = "mnn.qwen-image-2.1.opencl",
        profileRevision = 2,
        family = ImageEngineModelFamily.QWEN_IMAGE,
        variant = ImageEngineModelVariant.QWEN_IMAGE_21,
        tokenizer = ImageEngineTokenizerContractSpec(
            backend = ImageEngineTokenizerBackend.MNN_QWEN_IMAGE,
            maxLength = 512,
            clip1PadRule = ImageEngineClipPadRule.MODEL_DECLARED,
            supportsPromptWeighting = false,
            supportsTextualInversion = false,
            separateNegativePrompt = false
        ),
        conditioning = ImageEngineConditioningContractSpec(
            diskDataType = ImageEngineEmbeddingDataType.GRAPH_INTERNAL,
            conversionStrategy = ImageEngineEmbeddingConversionStrategy.GRAPH_EXECUTION,
            textEncoderInputShape = listOf(1, 512),
            textEncoderOutputShapes = listOf(listOf(1, 512, 4_096)),
            concatenationOrder = listOf("positive")
        ),
        scheduler = imageScheduler(
            algorithm = ImageEngineSchedulerAlgorithm.FLOW_MATCH,
            predictionType = ImageEnginePredictionType.FLOW,
            defaultSteps = 20,
            minSteps = 2,
            maxSteps = 40
        ),
        vae = ImageEngineVaeContractSpec(
            scalingLocation = ImageEngineVaeScalingLocation.RUNTIME_NATIVE,
            scalingFactor = 1.0,
            inputShape = listOf(1, 64, 32, 32),
            outputShape = listOf(1, 4, 512, 512),
            inputLayout = ImageEngineTensorLayout.RUNTIME_NATIVE,
            outputLayout = ImageEngineTensorLayout.RUNTIME_NATIVE,
            outputRange = ImageEnginePixelRange.RUNTIME_NATIVE,
            channelOrder = ImageEngineChannelOrder.RUNTIME_NATIVE
        ),
        graph = ImageEngineGraphContractSpec(
            textEncoder = "text_encoder/llm.mnn",
            unet = "dit.mnn",
            vae = "vae_decoder.mnn",
            // The MNN text tokenizer table is a required component, not a JSON sidecar.
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
            workerStrategy = ImageEngineWorkerStrategy.DEDICATED_WORKER
        ),
        defaults = ImageEngineGenerationDefaultsSpec(
            width = 512,
            height = 512,
            steps = 20,
            cfgScale = 1.0,
            useCfg = false
        ),
        capabilities = ImageEngineGenerationCapabilitiesSpec(
            supportedSchedulers = setOf(ImageEngineSchedulerAlgorithm.FLOW_MATCH),
            // The Android MNN port is dynamic, with the exact verified
            // 21-size grid enforced by the app/provider contract.
            minWidth = 256,
            maxWidth = 672,
            minHeight = 256,
            maxHeight = 672,
            widthMultiple = 32,
            heightMultiple = 32,
            supportsNegativePrompt = false,
            supportsPromptWeighting = false,
            supportsVaeTiling = false,
            supportsLivePreview = false,
            supportsLora = false,
            maxBatchCount = 1
        )
    )
