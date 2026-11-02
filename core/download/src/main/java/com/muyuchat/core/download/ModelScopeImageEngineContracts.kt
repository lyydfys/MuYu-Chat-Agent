package com.muyuchat.core.download

enum class ImageEngineVaeScalingLocation { HOST_BEFORE_GRAPH, GRAPH_INTERNAL, RUNTIME_NATIVE }
enum class ImageEngineTensorLayout { NCHW, RUNTIME_NATIVE }
enum class ImageEnginePixelRange { NEGATIVE_ONE_TO_ONE, RUNTIME_NATIVE }
enum class ImageEngineChannelOrder { RGB, RUNTIME_NATIVE }
enum class ImageEngineWorkerStrategy {
    IN_PROCESS,
    DEDICATED_WORKER,
    SPLIT_UNET_VAE,
    SHARED_UNET_VAE,
    SHARED_TEXT_UNET_VAE
}

data class ImageEngineSchedulerContractSpec(
    val algorithm: ImageEngineSchedulerAlgorithm,
    val predictionType: ImageEnginePredictionType,
    val numTrainTimesteps: Int = 1_000,
    val noiseSchedule: ImageEngineNoiseSchedule = ImageEngineNoiseSchedule.SCALED_LINEAR,
    val betaStart: Double? = 0.00085,
    val betaEnd: Double? = 0.012,
    val timestepSpacing: ImageEngineTimestepSpacing = ImageEngineTimestepSpacing.LEADING,
    val stepsOffset: Int = 0,
    val setAlphaToOne: Boolean = false,
    val skipPrkSteps: Boolean = false,
    val finalSigmaType: ImageEngineFinalSigmaType = ImageEngineFinalSigmaType.ZERO,
    val clipSample: Boolean = false,
    val clipSampleRange: Double = 1.0,
    val thresholding: Boolean = false,
    val eta: Double = 0.0,
    val lowerOrderFinal: Boolean = true,
    val initNoiseSigma: Double = 1.0,
    val scaleModelInput: Boolean = false,
    val order: Int = 1,
    val defaultSteps: Int,
    val minSteps: Int,
    val maxSteps: Int,
    val seedBits: Int = 32
) {
    init {
        require(defaultSteps in minSteps..maxSteps) { "Default image steps must be inside the supported range." }
        require(numTrainTimesteps > 0) { "Image scheduler train timesteps must be positive." }
        require(order > 0) { "Image scheduler order must be positive." }
        require(seedBits in 1..64) { "Image scheduler seed width is invalid." }
        if (noiseSchedule == ImageEngineNoiseSchedule.SIGMA) {
            require(betaStart == null && betaEnd == null) { "Flow schedulers must not declare beta endpoints." }
        } else {
            require(betaStart != null && betaEnd != null) { "Diffusion schedulers require beta endpoints." }
        }
    }
}

data class ImageEngineTokenizerContractSpec(
    val backend: ImageEngineTokenizerBackend,
    val bosId: Int? = null,
    val eosId: Int? = null,
    val padId: Int? = null,
    val maxLength: Int,
    val clip1PadRule: ImageEngineClipPadRule = ImageEngineClipPadRule.EOS,
    val clip2PadRule: ImageEngineClipPadRule? = null,
    val supportsPromptWeighting: Boolean = false,
    val supportsTextualInversion: Boolean = false,
    val separateNegativePrompt: Boolean = true
) {
    init {
        require(maxLength > 0) { "Tokenizer max length must be positive." }
    }
}

data class ImageEngineConditioningContractSpec(
    val diskDataType: ImageEngineEmbeddingDataType,
    val conversionStrategy: ImageEngineEmbeddingConversionStrategy,
    val textEncoderInputShape: List<Int>,
    val textEncoderOutputShapes: List<List<Int>>,
    val dualEncoder: Boolean = false,
    val pooledOutput: Boolean = false,
    val concatenationOrder: List<String>
)

data class ImageEngineVaeContractSpec(
    val scalingLocation: ImageEngineVaeScalingLocation,
    val scalingFactor: Double,
    val inputShape: List<Int>,
    val outputShape: List<Int>,
    val inputLayout: ImageEngineTensorLayout = ImageEngineTensorLayout.NCHW,
    val outputLayout: ImageEngineTensorLayout = ImageEngineTensorLayout.NCHW,
    val outputRange: ImageEnginePixelRange = ImageEnginePixelRange.NEGATIVE_ONE_TO_ONE,
    val channelOrder: ImageEngineChannelOrder = ImageEngineChannelOrder.RGB
) {
    init {
        require(scalingFactor.isFinite() && scalingFactor > 0.0) { "VAE scaling factor must be positive." }
        require(inputShape.all { it > 0 } && outputShape.all { it > 0 }) { "VAE tensor shapes must be positive." }
    }
}

data class ImageEngineGraphContractSpec(
    val textEncoder: String? = null,
    val unet: String? = null,
    val vae: String? = null,
    val vaeEncoder: String? = null,
    val controlNet: String? = null,
    val schedulerSidecar: String? = null,
    val tokenizerSidecar: String? = null,
    /**
     * Immutable non-graph files required by execution. The historical field
     * name is retained for manifest compatibility; entries may include binary
     * conditioning tables as well as configuration sidecars.
     */
    val configSidecars: List<String> = emptyList(),
    val qnnSdk: String? = null,
    val htpArch: Int? = null,
    val workerStrategy: ImageEngineWorkerStrategy,
    /** Actual graph names inside each context binary, keyed by the declared artifact path. */
    val graphNames: Map<String, String> = emptyMap()
)

data class ImageEngineGenerationDefaultsSpec(
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfgScale: Double,
    val seed: Long = 42L,
    val useCfg: Boolean,
    val defaultPrompt: String? = null,
    val defaultNegativePrompt: String? = null
) {
    init {
        require(width > 0 && height > 0) { "Default image dimensions must be positive." }
        require(steps > 0) { "Default image steps must be positive." }
        require(cfgScale.isFinite() && cfgScale in 0.0..30.0) { "Default image CFG is invalid." }
    }
}

data class ImageEngineGenerationCapabilitiesSpec(
    val supportedSchedulers: Set<ImageEngineSchedulerAlgorithm>,
    val minWidth: Int,
    val maxWidth: Int,
    val minHeight: Int,
    val maxHeight: Int,
    val widthMultiple: Int = 8,
    val heightMultiple: Int = 8,
    val supportsNegativePrompt: Boolean = true,
    val supportsPromptWeighting: Boolean = false,
    val supportsTextualInversion: Boolean = false,
    val requiresControlImage: Boolean = false,
    val requiresInputImage: Boolean = false,
    val supportsMask: Boolean = false,
    val supportsClipSkip: Boolean = false,
    val supportsVaeTiling: Boolean = false,
    val supportsUltraFix: Boolean = false,
    val ultraFixMinWidth: Int = if (supportsUltraFix) minWidth else 0,
    val ultraFixMaxWidth: Int = if (supportsUltraFix) maxWidth else 0,
    val ultraFixMinHeight: Int = if (supportsUltraFix) minHeight else 0,
    val ultraFixMaxHeight: Int = if (supportsUltraFix) maxHeight else 0,
    val ultraFixWidthMultiple: Int = if (supportsUltraFix) widthMultiple else 0,
    val ultraFixHeightMultiple: Int = if (supportsUltraFix) heightMultiple else 0,
    /** Zero denotes a runtime-selectable tile; QNN fixed graphs publish their exact pixel tile. */
    val ultraFixRequiredTileSize: Int = 0,
    val supportsLivePreview: Boolean = false,
    val supportsLora: Boolean = false,
    val maxBatchCount: Int = 1
) {
    init {
        require(supportedSchedulers.isNotEmpty()) { "At least one image scheduler must be supported." }
        require(minWidth in 1..maxWidth && minHeight in 1..maxHeight) { "Image capability bounds are invalid." }
        require(widthMultiple > 0 && heightMultiple > 0) { "Image dimension multiples must be positive." }
        if (supportsUltraFix) {
            require(ultraFixMinWidth in 64..ultraFixMaxWidth &&
                ultraFixMinHeight in 64..ultraFixMaxHeight &&
                ultraFixMaxWidth <= 8_192 && ultraFixMaxHeight <= 8_192 &&
                ultraFixWidthMultiple > 0 && ultraFixHeightMultiple > 0 &&
                ultraFixMinWidth % ultraFixWidthMultiple == 0 &&
                ultraFixMaxWidth % ultraFixWidthMultiple == 0 &&
                ultraFixMinHeight % ultraFixHeightMultiple == 0 &&
                ultraFixMaxHeight % ultraFixHeightMultiple == 0 &&
                (ultraFixRequiredTileSize == 0 ||
                    (ultraFixRequiredTileSize in ultraFixMinWidth..ultraFixMaxWidth &&
                        ultraFixRequiredTileSize in ultraFixMinHeight..ultraFixMaxHeight &&
                        ultraFixRequiredTileSize % ultraFixWidthMultiple == 0 &&
                        ultraFixRequiredTileSize % ultraFixHeightMultiple == 0))
            ) { "UltraFix dimension capabilities are invalid." }
        } else {
            require(listOf(
                ultraFixMinWidth,
                ultraFixMaxWidth,
                ultraFixMinHeight,
                ultraFixMaxHeight,
                ultraFixWidthMultiple,
                ultraFixHeightMultiple,
                ultraFixRequiredTileSize
            ).all { it == 0 }) { "Unsupported UltraFix capabilities must not publish bounds." }
        }
        require(maxBatchCount in 1..8) { "Image batch capability must be between 1 and 8." }
    }
}

data class ImageEngineExecutionProfileSpec(
    val profileId: String,
    val profileRevision: Int = 1,
    val family: ImageEngineModelFamily,
    val variant: ImageEngineModelVariant,
    val task: ImageEngineTask = ImageEngineTask.TEXT_TO_IMAGE,
    val tokenizer: ImageEngineTokenizerContractSpec,
    val conditioning: ImageEngineConditioningContractSpec,
    val scheduler: ImageEngineSchedulerContractSpec,
    val vae: ImageEngineVaeContractSpec,
    val graph: ImageEngineGraphContractSpec,
    val defaults: ImageEngineGenerationDefaultsSpec,
    val capabilities: ImageEngineGenerationCapabilitiesSpec,
    /**
     * Optional positive admission proof for direct non-English text. Absence intentionally means
     * English-dominant, including old catalog records and third-party package declarations.
     */
    val textEncoderLanguage: ImageEngineTextEncoderLanguageContractSpec? = null
) {
    init {
        require(profileId.isNotBlank()) { "Image execution profile id must not be blank." }
        require(profileRevision > 0) { "Image execution profile revision must be positive." }
        require(defaults.steps == scheduler.defaultSteps) { "Image defaults and scheduler steps must match." }
        require(conditioning.textEncoderInputShape.getOrNull(1) == tokenizer.maxLength) {
            "Tokenizer length and conditioning input sequence axis must match."
        }
        require(conditioning.textEncoderOutputShapes.all { shape ->
            shape.getOrNull(1) == tokenizer.maxLength
        }) {
            "Tokenizer length and conditioning output sequence axes must match."
        }
        textEncoderLanguage?.takeIf {
            it.capability == ImageEngineTextEncoderLanguageCapability.NATIVE_MULTILINGUAL
        }?.let { contract ->
            val evidence = requireNotNull(contract.evidence)
            require(!conditioning.dualEncoder) {
                "Direct Chinese admission requires every text encoder to be evidence-bound; dual-encoder topology is not represented by this contract."
            }
            require(
                graph.textEncoder?.trim()?.replace('\\', '/')?.equals(
                    evidence.textEncoderAssetPath.trim().replace('\\', '/'),
                    ignoreCase = true
                ) == true
            ) {
                "Text encoder language evidence must bind the graph text encoder consumed at runtime."
            }
        }
        require(scheduler.algorithm in capabilities.supportedSchedulers) { "Default scheduler must be supported." }
        require(defaults.width in capabilities.minWidth..capabilities.maxWidth) { "Default width is unsupported." }
        require(defaults.height in capabilities.minHeight..capabilities.maxHeight) { "Default height is unsupported." }
        require(defaults.width % capabilities.widthMultiple == 0) { "Default width violates the profile multiple." }
        require(defaults.height % capabilities.heightMultiple == 0) { "Default height violates the profile multiple." }
        require(!capabilities.supportsPromptWeighting || tokenizer.supportsPromptWeighting) {
            "Image capabilities cannot advertise prompt weighting without tokenizer support."
        }
        require(capabilities.supportsNegativePrompt || defaults.defaultNegativePrompt == null) {
            "A conditional-only image profile cannot declare a default negative prompt."
        }
        require(capabilities.supportsNegativePrompt || !tokenizer.separateNegativePrompt) {
            "A conditional-only image profile cannot declare a separate negative prompt branch."
        }
        require(task != ImageEngineTask.CONTROL_IMAGE || capabilities.requiresControlImage) {
            "Control-image profiles must require a control image."
        }
        require((task == ImageEngineTask.CONTROL_IMAGE) == capabilities.requiresControlImage) {
            "Control-image capability and profile task must match exactly."
        }
        require(!capabilities.requiresControlImage || !graph.controlNet.isNullOrBlank()) {
            "Control-image profiles must declare a ControlNet graph."
        }
        require(task != ImageEngineTask.IMAGE_EDIT || capabilities.requiresInputImage) {
            "Image-edit profiles must require an input image."
        }
        require((task == ImageEngineTask.IMAGE_EDIT) == capabilities.requiresInputImage) {
            "Input-image capability and profile task must match exactly."
        }
        require(!capabilities.supportsMask || capabilities.requiresInputImage) {
            "Mask support requires an input-image task."
        }
        require(defaults.useCfg || defaults.cfgScale == 1.0) {
            "CFG-disabled defaults must retain the conditional branch at scale 1.0."
        }
    }
}

enum class ImageEngineBundleRuntime(val label: String) {
    STABLE_DIFFUSION_CPP("stable-diffusion.cpp"),
    MNN_DIFFUSION("MNN Diffusion"),
    QNN_HTP("骁龙 NPU"),
    MEDIATEK_NEURON("MediaTek NeuroPilot / Neuron")
}

enum class ImageEngineAccelerator(val label: String) {
    CPU("CPU"),
    OPENCL_GPU("OpenCL GPU"),
    QNN_HTP("骁龙 NPU"),
    MEDIATEK_NPU("MediaTek NPU")
}

enum class ImageEngineMinDeviceTier(val label: String) {
    ANY("通用设备"),
    SNAPDRAGON_8_GEN1("骁龙 8 Gen 1+"),
    SNAPDRAGON_8_GEN2("骁龙 8 Gen 2+"),
    SNAPDRAGON_8_GEN3("骁龙 8 Gen 3+"),
    SNAPDRAGON_8_ELITE("骁龙 8 Elite+")
}

data class ImageEngineSmokeSpec(
    val width: Int = 384,
    val height: Int = 384,
    val steps: Int = 1,
    val timeoutSeconds: Int = 180,
    val prompt: String = "a small ceramic cup on a bright wooden desk",
    val expectedOutputMime: String = "image/png"
)

data class ImageEngineQnnSmokeTensorSpec(
    val name: String,
    val dataType: String,
    val shape: List<Int>,
    val role: String = "input",
    val fill: String = "zero"
)

data class ImageEngineQnnSmokeSpec(
    val graphName: String = "model",
    val contextBinary: String,
    val expectedContextSizeBytes: Long? = null,
    val expectedContextSha256: String? = null,
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 1,
    val timeoutSeconds: Int = 180,
    val prompt: String = "a small ceramic cup on a bright wooden desk",
    val inputs: List<ImageEngineQnnSmokeTensorSpec>,
    val outputs: List<ImageEngineQnnSmokeTensorSpec>
) {
    init {
        require((expectedContextSizeBytes == null) == (expectedContextSha256 == null)) {
            "QNN smoke context identity must declare both size and SHA-256 or neither."
        }
        expectedContextSizeBytes?.let { size ->
            require(size > 0L) { "QNN smoke context identity size must be positive." }
        }
        expectedContextSha256?.let { sha256 ->
            require(sha256.matches(Regex("^[0-9a-f]{64}$"))) {
                "QNN smoke context identity SHA-256 must use 64 lowercase hexadecimal digits."
            }
        }
    }
}

/**
 * Exact QNN runtime contract required by a context-binary bundle.
 *
 * Some publisher archives contain only the context graphs and model assets.
 * For those archives, falling back to an unrelated APK runtime can reach HTP
 * with an incompatible SDK/architecture pair.  Persist this contract in the
 * installed manifest so readiness can fail closed before native execution.
 */
data class ImageEngineQnnRuntimeProfileSpec(
    val qnnSdk: String,
    val htpArch: Int,
    val completeBundleRuntime: Boolean = true
)

data class ImageEngineBundleSpec(
    val id: String,
    val title: String,
    val components: List<ImageEngineBundleComponentSpec>,
    val recommendationId: String? = null,
    val task: ImageEngineTask = ImageEngineTask.TEXT_TO_IMAGE,
    val runtime: ImageEngineBundleRuntime = ImageEngineBundleRuntime.STABLE_DIFFUSION_CPP,
    val accelerator: ImageEngineAccelerator = ImageEngineAccelerator.CPU,
    val minDeviceTier: ImageEngineMinDeviceTier = ImageEngineMinDeviceTier.ANY,
    val requiresQnnRuntime: Boolean = false,
    val requiresSmokeTest: Boolean = true,
    val smokeSpec: ImageEngineSmokeSpec = ImageEngineSmokeSpec(),
    val qnnSmokeSpecs: List<ImageEngineQnnSmokeSpec> = emptyList(),
    val requiredRuntimeProfile: ImageEngineQnnRuntimeProfileSpec? = null,
    val executionProfile: ImageEngineExecutionProfileSpec? = null,
    /** Relative required component containing the standalone MediaTek Anima v3 manifest. */
    val mediatekAnimaManifestPath: String? = null,
    /**
     * Explicit runtime family identifier persisted into the installed bundle
     * manifest.  It avoids relying on a checkpoint filename to choose a
     * family-specific execution path.  Legacy bundles may leave this null.
     */
    val modelFamily: String? = executionProfile?.family?.name
) {
    init {
        val mediatekRuntime = runtime == ImageEngineBundleRuntime.MEDIATEK_NEURON
        require((accelerator == ImageEngineAccelerator.MEDIATEK_NPU) == mediatekRuntime) {
            "MediaTek Neuron runtime and MediaTek NPU accelerator must be selected together."
        }
        require((mediatekAnimaManifestPath != null) == mediatekRuntime) {
            "MediaTek Neuron bundles must declare exactly one standalone Anima v3 manifest path."
        }
        if (mediatekRuntime) {
            require(task == ImageEngineTask.TEXT_TO_IMAGE && modelFamily == ImageEngineModelFamily.ANIMA.name) {
                "MediaTek Neuron bundles currently support only Anima text-to-image."
            }
            require(executionProfile == null) {
                "MediaTek Anima uses its standalone v3 contract, not the generic image execution profile."
            }
            val path = requireNotNull(mediatekAnimaManifestPath).trim().replace('\\', '/')
            require(path.isNotBlank() && !path.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(path) &&
                path.split('/').all { it.isNotBlank() && it != "." && it != ".." }
            ) { "MediaTek Anima v3 manifest path must be a safe relative bundle path." }
            val pins = components.filter { component ->
                component.required && component.role == ImageEngineBundleComponentRole.CONFIG &&
                    (component.relativePath.replace('\\', '/') == path || component.fileName.replace('\\', '/') == path)
            }
            require(pins.size == 1) {
                "MediaTek Anima v3 manifest must match exactly one required CONFIG component."
            }
            require(pins.single().expectedSizeBytes?.let { it > 0L } == true &&
                pins.single().sha256?.matches(Regex("^[0-9a-fA-F]{64}$")) == true
            ) { "MediaTek Anima v3 manifest component must pin source size and SHA-256." }
        }
    }

    init {
        executionProfile?.let { profile ->
            fun normalizedBundlePath(value: String): String = value
                .trim()
                .replace('\\', '/')
                .trimStart('/')

            fun ImageEngineBundleComponentSpec.matchesPath(path: String): Boolean {
                val expected = normalizedBundlePath(path)
                return sequenceOf(fileName, relativePath)
                    .map(::normalizedBundlePath)
                    .any { candidate ->
                        candidate == expected ||
                            ('/' !in expected && candidate.substringAfterLast('/') == expected)
                    }
            }

            require(recommendationId?.isNotBlank() == true) {
                "Catalog image bundles with an execution profile require a recommendation id."
            }
            require(profile.task == task) { "Image bundle task and execution profile task must match." }
            require(modelFamily == profile.family.name) { "Image bundle family and execution profile family must match." }
            require(smokeSpec.width == profile.defaults.width) { "Image smoke width and execution default width must match." }
            require(smokeSpec.height == profile.defaults.height) { "Image smoke height and execution default height must match." }
            require(smokeSpec.steps == profile.defaults.steps) { "Image smoke steps and execution default steps must match." }

            val required = components.filter(ImageEngineBundleComponentSpec::required)
            require(required.isNotEmpty()) { "Catalog image bundles must declare required components." }
            required.forEach { component ->
                val relativePath = component.relativePath.trim().replace('\\', '/')
                require(
                    relativePath.isNotBlank() &&
                        !relativePath.startsWith('/') &&
                        !Regex("^[A-Za-z]:").containsMatchIn(relativePath) &&
                        relativePath.split('/').all { segment ->
                            segment.isNotBlank() && segment != "." && segment != ".."
                        }
                ) {
                    "Required image component paths must stay inside the installed bundle."
                }
                require(component.expectedSizeBytes?.let { it > 0L } == true) {
                    "Required image component ${component.relativePath} must pin a positive source size."
                }
                require(component.sha256?.matches(Regex("^[0-9a-fA-F]{64}$")) == true) {
                    "Required image component ${component.relativePath} must pin a source SHA-256."
                }
            }
            require(
                required.map { normalizedBundlePath(it.relativePath).lowercase() }.distinct().size == required.size
            ) {
                "Required image components must use unique installed paths."
            }
            profile.textEncoderLanguage?.evidence?.let { evidence ->
                val anchoredAssets = evidence.promptToEncoderAssets
                    .map { asset -> asset.relativePath to asset.sha256 }
                    .ifEmpty {
                        buildList {
                            add(evidence.textEncoderAssetPath to evidence.textEncoderAssetSha256)
                            evidence.auxiliaryAssets.forEach { asset ->
                                add(asset.relativePath to asset.sha256)
                            }
                        }
                    }
                anchoredAssets.forEachIndexed { index, (path, sha256) ->
                    val expectedPath = normalizedBundlePath(path).lowercase()
                    val anchoredEncoder = required.singleOrNull { component ->
                        component.role in setOf(
                            ImageEngineBundleComponentRole.TEXT_ENCODER,
                            ImageEngineBundleComponentRole.TOKENIZER,
                            ImageEngineBundleComponentRole.CONDITIONING
                        ) &&
                            normalizedBundlePath(component.relativePath).lowercase() == expectedPath &&
                            component.sha256.equals(sha256, ignoreCase = true)
                    }
                    require(anchoredEncoder != null) {
                        "Text encoder language evidence asset $index must bind an exact required prompt-conditioning component."
                    }
                }
                if (profile.textEncoderLanguage.capability ==
                    ImageEngineTextEncoderLanguageCapability.NATIVE_MULTILINGUAL
                ) {
                    val closureRoles = evidence.promptToEncoderAssets.map { asset -> asset.role }
                    require(runtime == ImageEngineBundleRuntime.QNN_HTP &&
                        profile.graph.workerStrategy == ImageEngineWorkerStrategy.SHARED_TEXT_UNET_VAE &&
                        profile.tokenizer.backend == ImageEngineTokenizerBackend.TOKENIZERS_CPP &&
                        closureRoles.toSet() == setOf(
                            ImageEnginePromptToEncoderAssetRole.TEXT_ENCODER_GRAPH,
                            ImageEnginePromptToEncoderAssetRole.TOKENIZER_JSON
                        )
                    ) {
                        "Direct Chinese admission requires the complete QNN text-encoder and tokenizer JSON closure."
                    }
                }
            }

            val diffusionComponents = required.filter {
                it.role == ImageEngineBundleComponentRole.DIFFUSION
            }
            require(diffusionComponents.isNotEmpty()) {
                "Catalog image bundles must declare a required diffusion main component."
            }
            val archiveComponents = diffusionComponents.filter { component ->
                normalizedBundlePath(component.relativePath).endsWith(".zip", ignoreCase = true)
            }
            require(archiveComponents.size <= 1) {
                "Catalog image bundles cannot declare more than one required diffusion archive."
            }
            if (archiveComponents.isNotEmpty()) {
                require(diffusionComponents.size == 1) {
                    "A diffusion archive is the bundle's single primary model component."
                }
            } else {
                val graphArtifacts = listOf(
                    Triple("text encoder", profile.graph.textEncoder, ImageEngineBundleComponentRole.TEXT_ENCODER),
                    Triple("diffusion graph", profile.graph.unet, ImageEngineBundleComponentRole.DIFFUSION),
                    Triple("VAE decoder", profile.graph.vae, ImageEngineBundleComponentRole.VAE),
                    Triple("VAE encoder", profile.graph.vaeEncoder, ImageEngineBundleComponentRole.VAE_ENCODER)
                )
                graphArtifacts.forEach { (label, path, role) ->
                    path?.takeIf(String::isNotBlank)?.let { requiredPath ->
                        require(required.any { component ->
                            component.role == role && component.matchesPath(requiredPath)
                        }) {
                            "Image execution profile $label path $requiredPath has no matching required component."
                        }
                    }
                }
                (listOfNotNull(
                    profile.graph.controlNet,
                    profile.graph.schedulerSidecar,
                    profile.graph.tokenizerSidecar
                ) + profile.graph.configSidecars).forEach { requiredPath ->
                    require(required.any { component -> component.matchesPath(requiredPath) }) {
                        "Image execution profile path $requiredPath has no matching required component."
                    }
                }
                profile.graph.unet?.takeIf(String::isNotBlank)?.let { mainPath ->
                    require(diffusionComponents.count { it.matchesPath(mainPath) } == 1) {
                        "A direct image bundle must bind exactly one diffusion main graph."
                    }
                } ?: require(diffusionComponents.size == 1) {
                    "A runtime-native image bundle must declare exactly one diffusion main component."
                }
            }

            if (
                runtime == ImageEngineBundleRuntime.STABLE_DIFFUSION_CPP &&
                profile.family in setOf(
                    ImageEngineModelFamily.Z_IMAGE,
                    ImageEngineModelFamily.FLUX,
                    ImageEngineModelFamily.QWEN_IMAGE,
                    ImageEngineModelFamily.LONGCAT_IMAGE,
                    ImageEngineModelFamily.ANIMA
                )
            ) {
                val roles = required.mapTo(linkedSetOf(), ImageEngineBundleComponentSpec::role)
                require(
                    roles.containsAll(
                        setOf(
                            ImageEngineBundleComponentRole.DIFFUSION,
                            ImageEngineBundleComponentRole.VAE,
                            ImageEngineBundleComponentRole.TEXT_ENCODER
                        )
                    )
                ) {
                    "Split runtime image bundles require diffusion, VAE and text-encoder components."
                }
            }
            if (profile.task == ImageEngineTask.CONTROL_IMAGE) {
                require(
                    listOf(
                        profile.graph.textEncoder,
                        profile.graph.unet,
                        profile.graph.vae,
                        profile.graph.controlNet
                    ).all { !it.isNullOrBlank() }
                ) {
                    "Control-image profiles require text encoder, diffusion, VAE and ControlNet graphs."
                }
            }
            if (
                runtime == ImageEngineBundleRuntime.MNN_DIFFUSION &&
                profile.task == ImageEngineTask.IMAGE_EDIT
            ) {
                val vaeEncoderPath = requireNotNull(
                    profile.graph.vaeEncoder?.takeIf(String::isNotBlank)
                ) {
                    "MNN image-edit profiles must declare a VAE encoder graph."
                }
                require(required.any { component ->
                    component.role == ImageEngineBundleComponentRole.VAE_ENCODER &&
                        component.matchesPath(vaeEncoderPath)
                }) {
                    "MNN image-edit bundles must include the declared VAE encoder component."
                }
            }
        }
    }

    val requiredComponents: List<ImageEngineBundleComponentSpec>
        get() = components.filter { it.required }

    val runtimeSummary: String
        get() = "${runtime.label} · ${accelerator.label}"

    /** The least trustworthy required component determines the bundle label. */
    val integrityMetadataStatus: ImageEngineIntegrityMetadataStatus
        get() = requiredComponents
            .map { it.integrityMetadataStatus }
            .maxByOrNull { it.ordinal }
            ?: ImageEngineIntegrityMetadataStatus.UNKNOWN
}
