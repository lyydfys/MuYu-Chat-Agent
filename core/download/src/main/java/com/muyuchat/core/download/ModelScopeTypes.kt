package com.muyuchat.core.download

import java.io.File
import java.net.URLEncoder
import java.util.Locale

enum class RecommendedChatRuntime(val label: String) {
    MNN("MNN 高速引擎"),
    GGUF("GGUF 兼容引擎"),
    GENIEX_QAIRT("GenieX QAIRT / 骁龙 NPU")
}

enum class MnnModelBundleComponentRole(val label: String) {
    LLM_CONFIG("LLM config"),
    CONFIG("MNN 配置"),
    MODEL("MNN 主模型"),
    WEIGHT("MNN 权重"),
    TOKENIZER("Tokenizer"),
    OPTIONAL("可选组件")
}

/**
 * Declares how a published MNN bundle is materialized before it becomes a
 * managed product bundle. This is explicit repository metadata rather than a
 * model-id heuristic.
 */
enum class MnnModelBundleInstallProfile {
    STANDARD,
    TEXT_ONLY
}

data class MnnModelBundleComponentSpec(
    val role: MnnModelBundleComponentRole,
    val fileName: String,
    val required: Boolean = true,
    val relativePath: String = fileName.substringAfterLast('/').substringAfterLast('\\')
)

data class MnnModelBundleSpec(
    val id: String,
    val title: String,
    val repoId: String,
    val revision: String = "master",
    val provider: ModelRepositoryProvider = ModelRepositoryProvider.MODELSCOPE,
    val installProfile: MnnModelBundleInstallProfile = MnnModelBundleInstallProfile.STANDARD,
    val components: List<MnnModelBundleComponentSpec> = DEFAULT_COMPONENTS
) {
    val requiredComponents: List<MnnModelBundleComponentSpec>
        get() = components.filter { it.required }

    companion object {
        val DEFAULT_COMPONENTS: List<MnnModelBundleComponentSpec> = listOf(
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.CONFIG, "config.json"),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.MODEL, "llm.mnn"),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.WEIGHT, "llm.mnn.weight"),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.TOKENIZER, "tokenizer.txt"),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.LLM_CONFIG, "llm_config.json"),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.OPTIONAL, "llm.mnn.json", required = false),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.OPTIONAL, "embeddings_bf16.bin", required = false),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.OPTIONAL, "visual.mnn", required = false),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.OPTIONAL, "visual.mnn.weight", required = false)
        )
    }
}

enum class ImageEngineBundleComponentRole(val label: String) {
    CONFIG("运行配置"),
    DIFFUSION("diffusion 主模型"),
    VAE("VAE / AE"),
    VAE_ENCODER("VAE encoder"),
    TEXT_ENCODER("文本编码器 / LLM"),
    TOKENIZER("Tokenizer"),
    CONDITIONING("条件连接 / 投影"),
    OPTIONAL("可选组件")
}

/**
 * Provenance of integrity metadata supplied before a component is downloaded.
 * A locally observed digest is intentionally not represented here: it cannot
 * establish what the publisher originally released.
 */
enum class ImageEngineIntegrityMetadataStatus(val label: String) {
    SOURCE_SHA256("已提供源端 SHA-256"),
    SOURCE_SIZE_ONLY("仅提供源端大小"),
    UNKNOWN("校验元数据未知")
}

data class ImageEngineBundleComponentSpec(
    val role: ImageEngineBundleComponentRole,
    val repoId: String,
    val fileName: String,
    val revision: String = "main",
    val provider: ModelRepositoryProvider = ModelRepositoryProvider.HUGGING_FACE,
    val required: Boolean = true,
    val expectedSizeBytes: Long? = null,
    val sha256: String? = null,
    val relativePath: String = fileName.substringAfterLast('/').substringAfterLast('\\')
) {
    val integrityMetadataStatus: ImageEngineIntegrityMetadataStatus
        get() = integrityMetadataStatus(expectedSizeBytes, sha256)
}

enum class ImageEngineTask(val label: String) {
    TEXT_TO_IMAGE("文生图"),
    CONTROL_IMAGE("控制图生图"),
    IMAGE_EDIT("图像编辑")
}

/**
 * Catalog-owned execution metadata persisted with a downloaded image bundle.
 *
 * The names intentionally match the app execution-profile JSON contract. The
 * model fingerprint and provenance are install-time values, while every field
 * below is immutable publisher/catalog metadata. Keeping these values beside
 * the downloadable components prevents the UI, smoke test, sidecars and native
 * request from silently choosing different model defaults.
 */
enum class ImageEngineModelFamily {
    SD15,
    SD21,
    SDXL,
    SD_TURBO,
    Z_IMAGE,
    FLUX,
    QWEN_IMAGE,
    LONGCAT_IMAGE,
    SANA
}

enum class ImageEngineModelVariant {
    STANDARD,
    HYPER,
    LEGACY_FP32,
    SD21,
    SDXL_BASE,
    DMD2_ALT,
    SD_TURBO,
    Z_IMAGE_TURBO,
    FLUX2_KLEIN,
    QWEN_IMAGE,
    LONGCAT_IMAGE,
    CONTROLNET_CANNY,
    SANA_EDIT
}

/**
 * Catalog-owned generation defaults shared by download manifests and the
 * app's built-in fallback profiles. Keep these concise enough for the pinned
 * tokenizer budgets; package sidecars and explicit user values still win.
 */
object RecommendedImageDefaults {
    const val SD15_NEGATIVE_PROMPT =
        "worst quality, low quality, lowres, blurry, bad anatomy, bad hands, extra fingers, " +
            "missing fingers, malformed limbs, text, signature, watermark"
    const val PHOTO_NEGATIVE_PROMPT =
        "worst quality, low quality, lowres, blurry, out of focus, bad anatomy, bad hands, " +
            "extra fingers, deformed, unrealistic, cartoon, anime, cgi, text, signature, watermark"
    const val ANIME_NEGATIVE_PROMPT =
        "worst quality, low quality, lowres, bad anatomy, bad hands, missing fingers, extra fingers, " +
            "malformed limbs, realistic photo, text, signature, watermark, username, blurry"
    const val SDXL_NEGATIVE_PROMPT =
        "worst quality, low quality, lowres, blurry, bad anatomy, bad hands, extra fingers, " +
            "missing fingers, deformed, text, signature, watermark"
    const val CYBERREALISTIC_XL_NEGATIVE_PROMPT =
        "lowres, bad anatomy, bad hands, text, error, missing fingers, extra digit, fewer digits, " +
            "cropped, worst quality, low quality, normal quality, jpeg artifacts, signature, " +
            "watermark, username, blurry"
    const val EDIT_NEGATIVE_PROMPT =
        "low quality, blurry, distorted, deformed, artifacts, text, watermark"
    const val LANGUAGE_CONDITIONED_NEGATIVE_PROMPT =
        "blurry, low quality, distorted, deformed, text, watermark"
    const val QWEN_IMAGE_2512_NEGATIVE_PROMPT =
        "低分辨率，低画质，肢体畸形，手指畸形，画面过饱和，蜡像感，人脸无细节，" +
            "过度光滑，画面具有AI感。构图混乱。文字模糊，扭曲。"
    const val LONGCAT_IMAGE_NEGATIVE_PROMPT = ""

    const val ANIMAGINE_XL_STEPS = 28
    const val ANIMAGINE_XL_CFG = 5.0
    const val CYBERREALISTIC_XL_STEPS = 20
    const val CYBERREALISTIC_XL_CFG = 7.0
}

enum class ImageEngineSchedulerAlgorithm {
    DPMPP_2M,
    EULER,
    EULER_A,
    DDIM,
    PNDM_PLMS,
    LCM,
    FLOW_MATCH
}

enum class ImageEnginePredictionType { EPSILON, V_PREDICTION, FLOW }
enum class ImageEngineTimestepSpacing { LEADING, TRAILING, LINSPACE }
enum class ImageEngineNoiseSchedule { SCALED_LINEAR, SIGMA }
enum class ImageEngineFinalSigmaType { ZERO, SIGMA_MIN }
enum class ImageEngineTokenizerBackend { TOKENIZERS_CPP, MNN_MTOK, SDCPP_NATIVE }
enum class ImageEngineClipPadRule { EOS, ZERO, MODEL_DECLARED }
enum class ImageEngineEmbeddingDataType { FP16, FP32, GRAPH_INTERNAL, RUNTIME_NATIVE }
enum class ImageEngineEmbeddingConversionStrategy {
    NONE,
    FP32_TO_FP16_STREAMING,
    GRAPH_EXECUTION,
    RUNTIME_NATIVE
}

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
    val workerStrategy: ImageEngineWorkerStrategy
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
    val supportsLivePreview: Boolean = false,
    val supportsLora: Boolean = false,
    val maxBatchCount: Int = 1
) {
    init {
        require(supportedSchedulers.isNotEmpty()) { "At least one image scheduler must be supported." }
        require(minWidth in 1..maxWidth && minHeight in 1..maxHeight) { "Image capability bounds are invalid." }
        require(widthMultiple > 0 && heightMultiple > 0) { "Image dimension multiples must be positive." }
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
    val capabilities: ImageEngineGenerationCapabilitiesSpec
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
    QNN_HTP("骁龙 NPU")
}

enum class ImageEngineAccelerator(val label: String) {
    CPU("CPU"),
    OPENCL_GPU("OpenCL GPU"),
    QNN_HTP("骁龙 NPU")
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
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 1,
    val timeoutSeconds: Int = 180,
    val prompt: String = "a small ceramic cup on a bright wooden desk",
    val inputs: List<ImageEngineQnnSmokeTensorSpec>,
    val outputs: List<ImageEngineQnnSmokeTensorSpec>
)

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
    /**
     * Explicit runtime family identifier persisted into the installed bundle
     * manifest.  It avoids relying on a checkpoint filename to choose a
     * family-specific execution path.  Legacy bundles may leave this null.
     */
    val modelFamily: String? = executionProfile?.family?.name
) {
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
                    ImageEngineModelFamily.LONGCAT_IMAGE
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

enum class VisionModelBundleComponentRole(val label: String) {
    MAIN_MODEL("多模态主模型"),
    PROJECTOR("视觉投影器 / mmproj"),
    TOKENIZER("Tokenizer"),
    PREPROCESSOR("图像预处理配置"),
    OPTIONAL("可选组件")
}

data class VisionModelBundleComponentSpec(
    val role: VisionModelBundleComponentRole,
    val repoId: String,
    val fileName: String,
    val revision: String = "master",
    val provider: ModelRepositoryProvider = ModelRepositoryProvider.MODELSCOPE,
    val required: Boolean = true,
    val relativePath: String = fileName.substringAfterLast('/').substringAfterLast('\\')
)

data class VisionModelBundleSpec(
    val id: String,
    val title: String,
    val components: List<VisionModelBundleComponentSpec>,
    val runtime: VisionModelBundleRuntime = VisionModelBundleRuntime.GGUF_MMPROJ,
    val accelerator: VisionModelAccelerator = VisionModelAccelerator.CPU,
    val minDeviceTier: ImageEngineMinDeviceTier = ImageEngineMinDeviceTier.ANY,
    val requiresQnnRuntime: Boolean = false,
    val requiresSmokeTest: Boolean = true,
    /**
     * Whether the recommendation card's primary download action installs every
     * required vision component. Large GGUF models may keep their projector in
     * this bundle for discovery while downloading only the chat model by
     * default; the projector remains available from the file list for an
     * explicit download-and-bind action.
     */
    val downloadProjectorByDefault: Boolean = true,
    val smokeSpec: VisionModelSmokeSpec = VisionModelSmokeSpec()
) {
    val requiredComponents: List<VisionModelBundleComponentSpec>
        get() = components.filter { it.required }

    val runtimeSummary: String
        get() = "${runtime.label} · ${accelerator.label}"
}

enum class VisionModelBundleRuntime(val label: String) {
    GGUF_MMPROJ("GGUF + mmproj"),
    MNN_MULTIMODAL("MNN 多模态"),
    LITERT_QNN("LiteRT / QNN")
}

enum class VisionModelAccelerator(val label: String) {
    CPU("CPU"),
    MNN_CPU("MNN CPU"),
    QNN_HTP("骁龙 NPU")
}

data class VisionModelSmokeSpec(
    val imageWidth: Int = 448,
    val imageHeight: Int = 448,
    val prompt: String = "请用中文描述这张图片",
    val timeoutSeconds: Int = 60
)

data class RemoteModelFile(
    val repoId: String,
    val revision: String,
    val path: String,
    val name: String,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val license: String? = null,
    val downloadUrl: String,
    val provider: ModelRepositoryProvider = ModelRepositoryProvider.MODELSCOPE,
    val mnnBundleRole: MnnModelBundleComponentRole? = null,
    val bundleRole: ImageEngineBundleComponentRole? = null,
    val visionBundleRole: VisionModelBundleComponentRole? = null,
    val relativePath: String = name
) {
    val integrityMetadataStatus: ImageEngineIntegrityMetadataStatus
        get() = integrityMetadataStatus(sizeBytes, sha256)

    val modelPageUrl: String
        get() = when (provider) {
            ModelRepositoryProvider.MODELSCOPE -> modelScopeModelPageUrl(repoId)
            ModelRepositoryProvider.HUGGING_FACE -> huggingFaceModelPageUrl(repoId)
        }
}

enum class ModelRepositoryProvider(val label: String) {
    MODELSCOPE("ModelScope"),
    HUGGING_FACE("Hugging Face")
}

enum class RemoteModelFileKind {
    CHAT_MODEL,
    VISION_MODEL,
    IMAGE_MODEL,
    IMAGE_VAE,
    IMAGE_TEXT_ENCODER,
    IMAGE_TOKENIZER,
    MNN_COMPONENT,
    PROJECTOR,
    IMATRIX,
    SPECULATIVE,
    SPLIT_PART,
    OTHER
}

fun RemoteModelFile.fileKind(): RemoteModelFileKind {
    val lowerPath = path.lowercase()
    val lowerName = name.lowercase()
    return when {
        bundleRole == ImageEngineBundleComponentRole.DIFFUSION -> RemoteModelFileKind.IMAGE_MODEL
        bundleRole == ImageEngineBundleComponentRole.VAE ||
            bundleRole == ImageEngineBundleComponentRole.VAE_ENCODER -> RemoteModelFileKind.IMAGE_VAE
        bundleRole == ImageEngineBundleComponentRole.TEXT_ENCODER -> RemoteModelFileKind.IMAGE_TEXT_ENCODER
        bundleRole == ImageEngineBundleComponentRole.TOKENIZER -> RemoteModelFileKind.IMAGE_TOKENIZER
        bundleRole == ImageEngineBundleComponentRole.CONFIG ||
            bundleRole == ImageEngineBundleComponentRole.CONDITIONING -> RemoteModelFileKind.MNN_COMPONENT
        mnnBundleRole != null -> RemoteModelFileKind.MNN_COMPONENT
        visionBundleRole == VisionModelBundleComponentRole.MAIN_MODEL -> RemoteModelFileKind.VISION_MODEL
        visionBundleRole == VisionModelBundleComponentRole.PROJECTOR -> RemoteModelFileKind.PROJECTOR
        !lowerName.hasModelFileExtension() -> RemoteModelFileKind.OTHER
        "mmproj" in lowerPath || "projector" in lowerPath -> RemoteModelFileKind.PROJECTOR
        "imatrix" in lowerPath -> RemoteModelFileKind.IMATRIX
        lowerName.startsWith("mtp-") -> RemoteModelFileKind.SPECULATIVE
        Regex("""-\d{5}-of-\d{5}\.gguf$""").containsMatchIn(lowerName) -> RemoteModelFileKind.SPLIT_PART
        looksLikeImageGenerationModel(lowerPath, lowerName) -> RemoteModelFileKind.IMAGE_MODEL
        else -> RemoteModelFileKind.CHAT_MODEL
    }
}

fun RemoteModelFile.isChatModelCandidate(): Boolean = fileKind() == RemoteModelFileKind.CHAT_MODEL

fun RemoteModelFile.isVisionModelCandidate(): Boolean = fileKind() == RemoteModelFileKind.VISION_MODEL

fun RemoteModelFile.isImageModelCandidate(): Boolean = fileKind() == RemoteModelFileKind.IMAGE_MODEL

fun RemoteModelFile.kindLabel(): String = when (fileKind()) {
    RemoteModelFileKind.CHAT_MODEL -> "聊天主模型"
    RemoteModelFileKind.VISION_MODEL -> "本地识图主模型"
    RemoteModelFileKind.IMAGE_MODEL -> "图像生成模型"
    RemoteModelFileKind.IMAGE_VAE -> "VAE / AE"
    RemoteModelFileKind.IMAGE_TEXT_ENCODER -> "文本编码器 / LLM"
    RemoteModelFileKind.IMAGE_TOKENIZER -> "Tokenizer"
    RemoteModelFileKind.PROJECTOR -> "视觉投影辅助文件"
    RemoteModelFileKind.IMATRIX -> "量化校准辅助文件"
    RemoteModelFileKind.SPECULATIVE -> "投机解码辅助文件"
    RemoteModelFileKind.SPLIT_PART -> "分片文件"
    RemoteModelFileKind.OTHER -> "其他文件"
    else -> "MNN 模型组件"
}

private fun looksLikeImageGenerationModel(lowerPath: String, lowerName: String): Boolean {
    val value = "$lowerPath/$lowerName"
    return listOf(
        "z-image",
        "z_image",
        "zimage",
        "qwen-image",
        "qwen_image",
        "flux",
        "glm-image",
        "glm_image",
        "longcat-image",
        "longcat_image",
        "dreamlite",
        "dreamshaper",
        "meinamix",
        "majicmix",
        "sdxl",
        "stable-diffusion",
        "stable_diffusion",
        "diffusion",
        "text-to-image",
        "image-generation"
    ).any { it in value } &&
        listOf("clip", "t5", "text_encoder", "vae", "mmproj", "projector").none { it in lowerName }
}

private fun String.hasModelFileExtension(): Boolean =
    endsWith(".gguf") ||
        endsWith(".safetensors") ||
        endsWith(".sft") ||
        endsWith(".ckpt") ||
        endsWith(".pth") ||
        endsWith(".pt") ||
        endsWith(".onnx") ||
        endsWith(".mnn") ||
        endsWith(".litertlm") ||
        endsWith(".tflite") ||
        endsWith(".ctx") ||
        endsWith(".zip")

enum class ModelScopeRecommendedKind {
    CHAT,
    VISION,
    IMAGE
}

private fun integrityMetadataStatus(
    sizeBytes: Long?,
    sha256: String?
): ImageEngineIntegrityMetadataStatus = when {
    sha256?.matches(SHA256_HEX) == true -> ImageEngineIntegrityMetadataStatus.SOURCE_SHA256
    sizeBytes != null && sizeBytes > 0L -> ImageEngineIntegrityMetadataStatus.SOURCE_SIZE_ONLY
    else -> ImageEngineIntegrityMetadataStatus.UNKNOWN
}

private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")

enum class LocalImageEngineTier(val label: String) {
    QUICK("离线基线"),
    STANDARD("写实基线"),
    COMPACT_QUALITY("画质优先"),
    LARGE_QUALITY("高端备用"),
    HEAVY_EXPERIMENTAL("前沿画质")
}

enum class ModelScopeRecommendedGroup(val label: String) {
    LIGHT_CHAT("轻量聊天"),
    MAIN_CHAT("主力聊天"),
    QUALITY_CHAT("高质量聊天"),
    LOCAL_VISION("本地识图"),
    LOCAL_IMAGE("本地生图")
}

enum class RecommendedModelStatus(val label: String) {
    /** Product-level acceptance exists for the capability advertised by the catalog card. */
    RECOMMENDED("已验证"),
    /** Downloadable candidate whose advertised text/vision/image scope is not fully accepted yet. */
    EXPERIMENTAL("实验"),
    /** Execution integration is incomplete; configured component packages may still be downloaded for testing. */
    PENDING_INTEGRATION("待接入"),
    /** Tested below the recommendation bar; it must not become a managed default. */
    NOT_RECOMMENDED("实验")
}

enum class RecommendedModelDownloadPolicy {
    /** CPU and other portable packages can be downloaded on every device. */
    ALL_DEVICES,
    /** Listed chipset codes are recommendation hints only; unmatched devices keep the download action. */
    LISTED_CHIPSETS,
    /** Snapdragon detection ranks QNN packages; unknown and non-matching devices remain downloadable. */
    ANY_SNAPDRAGON
}

enum class RecommendedModelSection {
    CPU_CHAT,
    NPU_CHAT,
    CPU_IMAGE,
    NPU_IMAGE
}

data class ModelScopeRecommendedModel(
    val id: String,
    val title: String,
    val repoId: String,
    val revision: String = "master",
    val description: String,
    val recommendedFileName: String,
    val parameterScale: String,
    val quant: String,
    val minRamGb: Int,
    val tags: List<String> = emptyList(),
    val priority: Int = 0,
    val kind: ModelScopeRecommendedKind = ModelScopeRecommendedKind.CHAT,
    val status: RecommendedModelStatus = RecommendedModelStatus.RECOMMENDED,
    val visibleInRecommendations: Boolean = true,
    val supportedChipsetCodes: Set<String> = emptySet(),
    val downloadPolicy: RecommendedModelDownloadPolicy = RecommendedModelDownloadPolicy.LISTED_CHIPSETS,
    val group: ModelScopeRecommendedGroup = when (kind) {
        ModelScopeRecommendedKind.CHAT -> ModelScopeRecommendedGroup.MAIN_CHAT
        ModelScopeRecommendedKind.VISION -> ModelScopeRecommendedGroup.LOCAL_VISION
        ModelScopeRecommendedKind.IMAGE -> ModelScopeRecommendedGroup.LOCAL_IMAGE
    },
    val provider: ModelRepositoryProvider = ModelRepositoryProvider.MODELSCOPE,
    val downloadable: Boolean = true,
    val downloadBlockReason: String? = null,
    val chatRuntime: RecommendedChatRuntime = RecommendedChatRuntime.GGUF,
    val mnnModelBundle: MnnModelBundleSpec? = null,
    val localImageEngineTier: LocalImageEngineTier? = null,
    val imageEngineBundle: ImageEngineBundleSpec? = null,
    val visionModelBundle: VisionModelBundleSpec? = null
) {
    init {
        if (kind == ModelScopeRecommendedKind.IMAGE) {
            val bundle = requireNotNull(imageEngineBundle) {
                "Image recommendations require an executable image bundle."
            }
            require(bundle.recommendationId == id) {
                "Image recommendation and bundle identities must match."
            }
            require(bundle.executionProfile != null) {
                "Image recommendations require a catalog execution profile."
            }
            val expected = recommendedFileName.trim().replace('\\', '/').substringAfterLast('/')
            val diffusionComponents = bundle.requiredComponents.filter {
                it.role == ImageEngineBundleComponentRole.DIFFUSION
            }
            val primaryMatches = diffusionComponents.filter { component ->
                sequenceOf(component.fileName, component.relativePath)
                    .map { it.trim().replace('\\', '/').substringAfterLast('/') }
                    .any { it.equals(expected, ignoreCase = true) }
            }
            require(primaryMatches.size == 1) {
                "Image recommendation $id must bind its recommended file to exactly one diffusion main component."
            }
            require(diffusionComponents.firstOrNull() == primaryMatches.single()) {
                "Image recommendation $id must place its declared diffusion main component first."
            }
        }
    }

    val section: RecommendedModelSection
        get() = when {
            kind == ModelScopeRecommendedKind.IMAGE &&
                imageEngineBundle?.accelerator == ImageEngineAccelerator.QNN_HTP -> RecommendedModelSection.NPU_IMAGE
            kind == ModelScopeRecommendedKind.IMAGE -> RecommendedModelSection.CPU_IMAGE
            chatRuntime == RecommendedChatRuntime.GENIEX_QAIRT ||
                visionModelBundle?.accelerator == VisionModelAccelerator.QNN_HTP -> RecommendedModelSection.NPU_CHAT
            else -> RecommendedModelSection.CPU_CHAT
        }

    val modelPageUrl: String
        get() = when (provider) {
            ModelRepositoryProvider.MODELSCOPE -> modelScopeModelPageUrl(repoId)
            ModelRepositoryProvider.HUGGING_FACE -> huggingFaceModelPageUrl(repoId)
        }
}

/**
 * Download access is deliberately separate from the device-fit hint shown in
 * the UI. RAM and chipset matching are advisory only: hardware discovery may
 * recommend a better package, but an unknown or unmatched device must never
 * lose the download/import/load/run path. Integration state is displayed
 * separately: a fully configured component package may be downloaded for
 * user testing even while its execution path is still marked pending.
 */
data class RecommendedModelDownloadEligibility(
    val chipsetMatched: Boolean,
    val canDownload: Boolean,
    val blockedReason: String? = null
)

fun ModelScopeRecommendedModel.downloadEligibilityFor(
    deviceChipsetCode: String,
    deviceIsSnapdragon: Boolean = false
): RecommendedModelDownloadEligibility {
    val normalizedDevice = deviceChipsetCode.trim().uppercase(Locale.ROOT)
    val chipsetMatched = when (downloadPolicy) {
        RecommendedModelDownloadPolicy.ALL_DEVICES -> true
        RecommendedModelDownloadPolicy.LISTED_CHIPSETS -> supportedChipsetCodes.isEmpty() ||
            (normalizedDevice.isNotEmpty() && supportedChipsetCodes.any {
                it.trim().uppercase(Locale.ROOT) == normalizedDevice
            })
        RecommendedModelDownloadPolicy.ANY_SNAPDRAGON ->
            deviceIsSnapdragon || normalizedDevice.isSnapdragonChipsetCode()
    }
    if (!downloadable) {
        return RecommendedModelDownloadEligibility(
            chipsetMatched = chipsetMatched,
            canDownload = false,
            blockedReason = downloadBlockReason ?: "该模型暂不提供下载。"
        )
    }
    return RecommendedModelDownloadEligibility(
        chipsetMatched = chipsetMatched,
        canDownload = true
    )
}

private fun String.isSnapdragonChipsetCode(): Boolean {
    if (isBlank()) return false
    if (contains("SNAPDRAGON") || contains("骁龙")) return true
    return matches(Regex("^(?:SM|SDM|MSM|APQ|QCS|QCM)[A-Z0-9_-]+$"))
}

data class ModelScopeHubModel(
    val id: String,
    val displayName: String,
    val description: String = "",
    val downloads: Long = 0,
    val likes: Long = 0,
    val license: String? = null,
    val tasks: List<String> = emptyList(),
    val fileSizeBytes: Long = 0,
    val params: Long = 0,
    val tags: List<String> = emptyList(),
    val private: Boolean = false,
    val gated: Boolean = false
) {
    val modelPageUrl: String
        get() = modelScopeModelPageUrl(id)
}

data class ModelScopeModelSearchResult(
    val query: String,
    val pageNumber: Int,
    val pageSize: Int,
    val totalCount: Int,
    val models: List<ModelScopeHubModel>
)

data class DownloadTaskSnapshot(
    val repoId: String,
    val revision: String,
    val fileName: String,
    val url: String,
    val etag: String? = null,
    val expectedLength: Long = 0,
    val downloadedBytes: Long = 0,
    val speedBytesPerSecond: Long = 0,
    val remainingSeconds: Long? = null,
    val errorMessage: String? = null,
    val tempFile: File,
    val finalFile: File,
    val status: DownloadStatus
)

enum class DownloadStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    FAILED,
    DONE
}

fun modelScopeModelPageUrl(repoId: String): String =
    "https://www.modelscope.cn/models/${repoId.modelScopePath()}/summary"

fun huggingFaceModelPageUrl(repoId: String): String =
    "https://hf-mirror.com/${repoId.modelScopePath()}"

private fun String.modelScopePath(): String =
    trim('/')
        .split('/')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
