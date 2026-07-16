package com.muyuchat.mca

import java.security.MessageDigest
import kotlin.math.abs

internal const val IMAGE_EXECUTION_PROFILE_SCHEMA_VERSION = 1
internal const val EXECUTION_CONTRACT_MISMATCH = "EXECUTION_CONTRACT_MISMATCH"

internal enum class ImageModelVariant {
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
    SANA_EDIT,
    GENERIC_COMPATIBLE
}

internal enum class ImageTask { TEXT_TO_IMAGE, CONTROL_IMAGE, IMAGE_EDIT }

internal enum class ImageProfileSource {
    MANIFEST,
    SIDECAR,
    BUILT_IN,
    CAPABILITY_DISCOVERY,
    GENERIC_FALLBACK,
    USER_OVERRIDE
}

internal data class ImageProfileProvenance(
    val primarySource: ImageProfileSource,
    val sources: List<ImageProfileSource>,
    val recommendationId: String? = null,
    val recommendationRevision: String? = null,
    val notes: List<String> = emptyList()
)

internal enum class ImageTokenizerBackend { TOKENIZERS_CPP, MNN_MTOK, SDCPP_NATIVE }
internal enum class ImageUnicodeNormalization { NONE, NFC, NFKC }
internal enum class ImageClipPadRule { EOS, ZERO, MODEL_DECLARED }

internal data class ImageProfileAsset(
    val relativePath: String,
    val fingerprint: String
)

internal data class ImageTokenizerContract(
    val backend: ImageTokenizerBackend,
    val assets: List<ImageProfileAsset> = emptyList(),
    val bosId: Int? = null,
    val eosId: Int? = null,
    val padId: Int? = null,
    val maxLength: Int,
    val unicodeNormalization: ImageUnicodeNormalization = ImageUnicodeNormalization.NFC,
    val lowercase: Boolean = true,
    val preTokenizer: String = "model_declared",
    val postProcessor: String = "model_declared",
    val clip1PadRule: ImageClipPadRule = ImageClipPadRule.EOS,
    val clip2PadRule: ImageClipPadRule? = null,
    val supportsPromptWeighting: Boolean = false,
    val supportsTextualInversion: Boolean = false,
    val separateNegativePrompt: Boolean = true
)

internal enum class ImageEmbeddingDiskDataType { FP16, FP32, BF16, GRAPH_INTERNAL, RUNTIME_NATIVE }
internal enum class ImageEmbeddingConversionStrategy {
    NONE,
    FP32_TO_FP16_STREAMING,
    GRAPH_EXECUTION,
    RUNTIME_NATIVE
}

internal data class ImageConditioningContract(
    val diskDataType: ImageEmbeddingDiskDataType,
    val exactByteSize: Long? = null,
    val elementCount: Long? = null,
    val tokenTableShape: List<Int> = emptyList(),
    val positionTableShape: List<Int> = emptyList(),
    val textEncoderInputShape: List<Int> = emptyList(),
    val textEncoderOutputShapes: List<List<Int>> = emptyList(),
    val conversionStrategy: ImageEmbeddingConversionStrategy,
    val dualEncoder: Boolean = false,
    val pooledOutput: Boolean = false,
    val concatenationOrder: List<String> = emptyList()
)

internal enum class ImageSchedulerAlgorithm {
    DPMPP_2M,
    EULER,
    EULER_A,
    DDIM,
    PNDM_PLMS,
    LCM,
    FLOW_MATCH
}

internal enum class ImagePredictionType { EPSILON, V_PREDICTION, SAMPLE, FLOW }
internal enum class ImageTimestepSpacing { LEADING, TRAILING, LINSPACE }
internal enum class ImageNoiseSchedule { LINEAR, SCALED_LINEAR, SIGMA, MODEL_DECLARED }
internal enum class ImageFinalSigmaType { ZERO, SIGMA_MIN }
internal enum class ImageRngContract { MT19937, RUNTIME_NATIVE }

internal data class ImageSchedulerContract(
    val algorithm: ImageSchedulerAlgorithm,
    val predictionType: ImagePredictionType,
    val numTrainTimesteps: Int = 1_000,
    val noiseSchedule: ImageNoiseSchedule = ImageNoiseSchedule.MODEL_DECLARED,
    val betaStart: Double? = null,
    val betaEnd: Double? = null,
    val timestepSpacing: ImageTimestepSpacing = ImageTimestepSpacing.LEADING,
    val stepsOffset: Int = 0,
    val setAlphaToOne: Boolean = true,
    val skipPrkSteps: Boolean = false,
    val finalSigmaType: ImageFinalSigmaType = ImageFinalSigmaType.ZERO,
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
    val rng: ImageRngContract = ImageRngContract.MT19937,
    val seedBits: Int = 32
) {
    fun expectedTimestepCount(steps: Int): Int =
        if (algorithm == ImageSchedulerAlgorithm.PNDM_PLMS && skipPrkSteps) steps + 1 else steps
}

internal enum class ImageTensorLayout { NCHW, NHWC, BSH, BCHW, RUNTIME_NATIVE }

internal data class ImageLatentContract(
    val channels: Int,
    val downsampleFactor: Int,
    val schedulerLayout: ImageTensorLayout,
    val graphLayout: ImageTensorLayout,
    val initialShape: List<Int>,
    val dataType: ImageEmbeddingDiskDataType = ImageEmbeddingDiskDataType.FP32
)

internal enum class ImageVaeScalingLocation { HOST_BEFORE_GRAPH, GRAPH_INTERNAL, NONE, RUNTIME_NATIVE }
internal enum class ImagePixelRange { NEGATIVE_ONE_TO_ONE, ZERO_TO_ONE, ZERO_TO_255, RUNTIME_NATIVE }
internal enum class ImageChannelOrder { RGB, BGR, RUNTIME_NATIVE }

internal data class ImageVaeContract(
    val scalingLocation: ImageVaeScalingLocation,
    val scalingFactor: Double,
    val inputShape: List<Int>,
    val outputShape: List<Int>,
    val inputLayout: ImageTensorLayout,
    val outputLayout: ImageTensorLayout,
    val outputRange: ImagePixelRange,
    val channelOrder: ImageChannelOrder
)

internal enum class ImageTensorDataType { INT32, UINT16, FP16, FP32, BF16, RUNTIME_NATIVE }

internal data class ImageTensorContract(
    val role: String,
    val name: String,
    val shape: List<Int>,
    val dataType: ImageTensorDataType,
    val scale: Double? = null,
    val zeroPoint: Int? = null
)

internal data class ImageGraphArtifactContract(
    val relativePath: String,
    val graphName: String = "model",
    val inputs: List<ImageTensorContract> = emptyList(),
    val outputs: List<ImageTensorContract> = emptyList()
)

internal enum class ImageWorkerStrategy {
    IN_PROCESS,
    DEDICATED_WORKER,
    SPLIT_UNET_VAE,
    SHARED_TEXT_UNET_VAE
}

internal data class ImageGraphContract(
    val textEncoder: ImageGraphArtifactContract? = null,
    val unet: ImageGraphArtifactContract? = null,
    val vae: ImageGraphArtifactContract? = null,
    val controlNet: ImageGraphArtifactContract? = null,
    val schedulerSidecar: String? = null,
    val tokenizerSidecar: String? = null,
    val configSidecars: List<String> = emptyList(),
    val qnnSdk: String? = null,
    val htpArch: Int? = null,
    val contextMetadataFingerprint: String? = null,
    val workerStrategy: ImageWorkerStrategy
)

internal data class ImageGenerationDefaults(
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfgScale: Double,
    val seed: Long,
    val useCfg: Boolean,
    val defaultPrompt: String? = null,
    val defaultNegativePrompt: String? = null
)

internal data class ImageGenerationCapabilities(
    val supportedSchedulers: Set<ImageSchedulerAlgorithm>,
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
    val supportsMask: Boolean = false
)

internal data class ImageExecutionProfile(
    val schemaVersion: Int = IMAGE_EXECUTION_PROFILE_SCHEMA_VERSION,
    val profileId: String,
    val profileRevision: Int,
    val modelFingerprint: String,
    val runtime: LocalImageRuntime,
    val family: LocalImageModelFamily,
    val variant: ImageModelVariant,
    val task: ImageTask,
    val provenance: ImageProfileProvenance,
    val tokenizer: ImageTokenizerContract,
    val conditioning: ImageConditioningContract,
    val scheduler: ImageSchedulerContract,
    val latent: ImageLatentContract,
    val vae: ImageVaeContract,
    val graph: ImageGraphContract,
    val defaults: ImageGenerationDefaults,
    val capabilities: ImageGenerationCapabilities
) {
    /** Stable binding over the model fingerprint and every executable contract. */
    val bindingFingerprint: String
        get() = sha256Hex(
            listOf(
                schemaVersion,
                profileId,
                profileRevision,
                modelFingerprint.lowercase(),
                runtime,
                family,
                variant,
                task,
                tokenizer,
                conditioning,
                scheduler,
                latent,
                vae,
                graph,
                defaults,
                capabilities
            ).joinToString("\u001f")
        )
}

internal data class ImageProfileValidationIssue(
    val code: String,
    val field: String,
    val message: String
)

internal data class ImageProfileValidationReport(
    val issues: List<ImageProfileValidationIssue>
) {
    val valid: Boolean get() = issues.isEmpty()
}

internal object ImageExecutionProfileValidator {
    fun validate(
        profile: ImageExecutionProfile,
        expectedModelFingerprint: String? = null
    ): ImageProfileValidationReport {
        val issues = buildList {
            fun issue(code: String, field: String, message: String) {
                add(ImageProfileValidationIssue(code, field, message))
            }

            if (profile.schemaVersion != IMAGE_EXECUTION_PROFILE_SCHEMA_VERSION) {
                issue("PROFILE_SCHEMA_UNSUPPORTED", "schemaVersion", "Unsupported image profile schema.")
            }
            if (!PROFILE_ID.matches(profile.profileId)) {
                issue("PROFILE_ID_INVALID", "profileId", "Profile ID must be a stable lower-case identifier.")
            }
            if (profile.profileRevision <= 0) {
                issue("PROFILE_REVISION_INVALID", "profileRevision", "Profile revision must be positive.")
            }
            if (!SHA256.matches(profile.modelFingerprint)) {
                issue("MODEL_FINGERPRINT_INVALID", "modelFingerprint", "Model fingerprint must be a SHA-256 value.")
            }
            if (
                expectedModelFingerprint != null &&
                !profile.modelFingerprint.equals(expectedModelFingerprint, ignoreCase = true)
            ) {
                issue("MODEL_FINGERPRINT_MISMATCH", "modelFingerprint", "Profile is bound to different model bytes.")
            }
            profile.tokenizer.assets.forEachIndexed { index, asset ->
                if (!safeRelativePath(asset.relativePath)) {
                    issue("PROFILE_PATH_INVALID", "tokenizer.assets[$index].relativePath", "Tokenizer path must stay inside the bundle.")
                }
                if (!SHA256.matches(asset.fingerprint)) {
                    issue("ASSET_FINGERPRINT_INVALID", "tokenizer.assets[$index].fingerprint", "Tokenizer asset fingerprint must be SHA-256.")
                }
            }
            if (profile.tokenizer.maxLength <= 0) {
                issue("TOKENIZER_CONTRACT_INVALID", "tokenizer.maxLength", "Tokenizer max length must be positive.")
            }
            if ((profile.conditioning.exactByteSize ?: 1L) <= 0L) {
                issue("CONDITIONING_CONTRACT_INVALID", "conditioning.exactByteSize", "Embedding byte size must be positive when declared.")
            }
            if ((profile.conditioning.elementCount ?: 1L) <= 0L) {
                issue("CONDITIONING_CONTRACT_INVALID", "conditioning.elementCount", "Embedding element count must be positive when declared.")
            }
            val scheduler = profile.scheduler
            if (
                scheduler.numTrainTimesteps <= 0 ||
                scheduler.minSteps <= 0 ||
                scheduler.maxSteps < scheduler.minSteps ||
                scheduler.defaultSteps !in scheduler.minSteps..scheduler.maxSteps ||
                scheduler.order <= 0 ||
                scheduler.seedBits !in setOf(32, 64) ||
                !scheduler.initNoiseSigma.isFinite() || scheduler.initNoiseSigma <= 0.0 ||
                scheduler.betaStart?.let { !it.isFinite() || it <= 0.0 || it >= 1.0 } == true ||
                scheduler.betaEnd?.let { !it.isFinite() || it <= 0.0 || it >= 1.0 } == true ||
                (scheduler.betaStart != null && scheduler.betaEnd != null && scheduler.betaStart >= scheduler.betaEnd) ||
                !scheduler.clipSampleRange.isFinite() || scheduler.clipSampleRange <= 0.0 ||
                !scheduler.eta.isFinite() || scheduler.eta < 0.0
            ) {
                issue("SCHEDULER_CONTRACT_INVALID", "scheduler", "Scheduler bounds and numerical constants must be coherent.")
            }
            if (scheduler.algorithm !in profile.capabilities.supportedSchedulers) {
                issue("SCHEDULER_UNSUPPORTED", "scheduler.algorithm", "Profile capabilities do not include the selected scheduler.")
            }
            if (profile.latent.channels <= 0 || profile.latent.downsampleFactor <= 0 || !positiveShape(profile.latent.initialShape)) {
                issue("LATENT_CONTRACT_INVALID", "latent", "Latent channels, factor, and shape must be positive.")
            }
            if (
                !profile.vae.scalingFactor.isFinite() || profile.vae.scalingFactor <= 0.0 ||
                !positiveShape(profile.vae.inputShape) || !positiveShape(profile.vae.outputShape)
            ) {
                issue("VAE_CONTRACT_INVALID", "vae", "VAE scaling and shapes must be positive.")
            }
            graphPaths(profile.graph).forEach { (field, path) ->
                if (!safeRelativePath(path)) {
                    issue("PROFILE_PATH_INVALID", field, "Graph and sidecar paths must stay inside the bundle.")
                }
            }
            if (profile.graph.htpArch != null && profile.graph.htpArch <= 0) {
                issue("GRAPH_CONTRACT_INVALID", "graph.htpArch", "HTP architecture must be positive when declared.")
            }
            if (profile.runtime == LocalImageRuntime.QNN_HTP && profile.graph.unet == null) {
                issue("GRAPH_CONTRACT_INVALID", "graph.unet", "QNN image profiles require a UNet or diffusion graph.")
            }
            if (profile.task == ImageTask.CONTROL_IMAGE && profile.graph.controlNet == null) {
                issue("GRAPH_CONTRACT_INVALID", "graph.controlNet", "Control-image profiles require a control graph.")
            }
            val defaults = profile.defaults
            val capabilities = profile.capabilities
            if (
                defaults.width !in capabilities.minWidth..capabilities.maxWidth ||
                defaults.height !in capabilities.minHeight..capabilities.maxHeight ||
                defaults.width % capabilities.widthMultiple != 0 ||
                defaults.height % capabilities.heightMultiple != 0 ||
                defaults.steps !in scheduler.minSteps..scheduler.maxSteps ||
                !defaults.cfgScale.isFinite() || defaults.cfgScale !in 0.0..30.0
            ) {
                issue("GENERATION_DEFAULTS_INVALID", "defaults", "Generation defaults must fit profile capabilities and scheduler bounds.")
            }
            if (profile.task == ImageTask.CONTROL_IMAGE && !capabilities.requiresControlImage) {
                issue("CAPABILITY_CONTRACT_INVALID", "capabilities.requiresControlImage", "Control-image task must require a control image.")
            }
            if (profile.task == ImageTask.IMAGE_EDIT && !capabilities.requiresInputImage) {
                issue("CAPABILITY_CONTRACT_INVALID", "capabilities.requiresInputImage", "Image-edit task must require an input image.")
            }
        }
        return ImageProfileValidationReport(issues)
    }

    private fun positiveShape(shape: List<Int>): Boolean = shape.isNotEmpty() && shape.all { it > 0 }

    private fun graphPaths(graph: ImageGraphContract): List<Pair<String, String>> = buildList {
        graph.textEncoder?.relativePath?.let { add("graph.textEncoder" to it) }
        graph.unet?.relativePath?.let { add("graph.unet" to it) }
        graph.vae?.relativePath?.let { add("graph.vae" to it) }
        graph.controlNet?.relativePath?.let { add("graph.controlNet" to it) }
        graph.schedulerSidecar?.let { add("graph.schedulerSidecar" to it) }
        graph.tokenizerSidecar?.let { add("graph.tokenizerSidecar" to it) }
        graph.configSidecars.forEachIndexed { index, path -> add("graph.configSidecars[$index]" to path) }
    }

    private fun safeRelativePath(value: String): Boolean {
        val normalized = value.trim().replace('\\', '/')
        if (normalized.isBlank() || normalized.startsWith('/') || normalized.startsWith("./")) return false
        if (WINDOWS_DRIVE.containsMatchIn(normalized)) return false
        return normalized.split('/').all { it.isNotBlank() && it != "." && it != ".." }
    }

    private val PROFILE_ID = Regex("^[a-z0-9][a-z0-9._-]{2,127}$")
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:")
}

internal data class ImageRequestedExecution(
    val profileId: String? = null,
    val profileRevision: Int? = null,
    val modelFingerprint: String? = null,
    val scheduler: ImageSchedulerAlgorithm? = null,
    val predictionType: ImagePredictionType? = null,
    val steps: Int? = null,
    val cfgScale: Double? = null,
    val useCfg: Boolean? = null,
    val width: Int? = null,
    val height: Int? = null,
    val seed: Long? = null
)

internal data class ImageResolvedExecution(
    val profileId: String,
    val profileRevision: Int,
    val modelFingerprint: String,
    val runtime: LocalImageRuntime,
    val scheduler: ImageSchedulerAlgorithm,
    val predictionType: ImagePredictionType,
    val steps: Int,
    val timetableCount: Int,
    val unetExecutionCount: Int,
    val cfgScale: Double,
    val useCfg: Boolean,
    val unconditionalBranch: Boolean,
    val tokenizerBackend: ImageTokenizerBackend,
    val tokenCount: Int,
    val embeddingDiskDataType: ImageEmbeddingDiskDataType,
    val vaeScalingLocation: ImageVaeScalingLocation,
    val vaeScalingFactor: Double,
    val width: Int,
    val height: Int,
    val seed: Long,
    val graphName: String,
    val fallback: Boolean
)

internal data class ImageNativeEffectiveExecution(
    val profileId: String,
    val profileRevision: Int,
    val modelFingerprint: String,
    val runtime: LocalImageRuntime,
    val scheduler: ImageSchedulerAlgorithm,
    val predictionType: ImagePredictionType,
    val steps: Int,
    val timetableCount: Int,
    val unetExecutionCount: Int,
    val cfgScale: Double,
    val useCfg: Boolean,
    val unconditionalBranch: Boolean,
    val tokenizerBackend: ImageTokenizerBackend,
    val tokenCount: Int,
    val embeddingDiskDataType: ImageEmbeddingDiskDataType,
    val vaeScalingLocation: ImageVaeScalingLocation,
    val vaeScalingFactor: Double,
    val width: Int,
    val height: Int,
    val seed: Long,
    val graphName: String,
    val fallback: Boolean
)

internal data class ImageExecutionLayers(
    val requested: ImageRequestedExecution,
    val resolved: ImageResolvedExecution,
    val nativeEffective: ImageNativeEffectiveExecution? = null
)

internal data class ImageExecutionMismatch(
    val field: String,
    val resolved: String,
    val nativeEffective: String
)

internal data class ImageExecutionContractValidation(
    val mismatches: List<ImageExecutionMismatch>
) {
    val valid: Boolean get() = mismatches.isEmpty()
    val errorCode: String? get() = if (valid) null else EXECUTION_CONTRACT_MISMATCH
}

internal object ImageExecutionContractValidator {
    fun validate(layers: ImageExecutionLayers): ImageExecutionContractValidation {
        val native = layers.nativeEffective
            ?: return ImageExecutionContractValidation(
                listOf(ImageExecutionMismatch("nativeEffective", "required", "missing"))
            )
        val resolved = layers.resolved
        val mismatches = buildList {
            fun compare(field: String, expected: Any?, actual: Any?) {
                if (expected != actual) add(ImageExecutionMismatch(field, expected.toString(), actual.toString()))
            }
            fun compareDouble(field: String, expected: Double, actual: Double) {
                if (abs(expected - actual) > 1e-6) add(ImageExecutionMismatch(field, expected.toString(), actual.toString()))
            }
            compare("profileId", resolved.profileId, native.profileId)
            compare("profileRevision", resolved.profileRevision, native.profileRevision)
            compare("modelFingerprint", resolved.modelFingerprint.lowercase(), native.modelFingerprint.lowercase())
            compare("runtime", resolved.runtime, native.runtime)
            compare("scheduler", resolved.scheduler, native.scheduler)
            compare("predictionType", resolved.predictionType, native.predictionType)
            compare("steps", resolved.steps, native.steps)
            compare("timetableCount", resolved.timetableCount, native.timetableCount)
            compare("unetExecutionCount", resolved.unetExecutionCount, native.unetExecutionCount)
            compareDouble("cfgScale", resolved.cfgScale, native.cfgScale)
            compare("useCfg", resolved.useCfg, native.useCfg)
            compare("unconditionalBranch", resolved.unconditionalBranch, native.unconditionalBranch)
            compare("tokenizerBackend", resolved.tokenizerBackend, native.tokenizerBackend)
            compare("tokenCount", resolved.tokenCount, native.tokenCount)
            compare("embeddingDiskDataType", resolved.embeddingDiskDataType, native.embeddingDiskDataType)
            compare("vaeScalingLocation", resolved.vaeScalingLocation, native.vaeScalingLocation)
            compareDouble("vaeScalingFactor", resolved.vaeScalingFactor, native.vaeScalingFactor)
            compare("width", resolved.width, native.width)
            compare("height", resolved.height, native.height)
            compare("seed", resolved.seed, native.seed)
            compare("graphName", resolved.graphName, native.graphName)
            compare("fallback", resolved.fallback, native.fallback)
        }
        return ImageExecutionContractValidation(mismatches)
    }
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
