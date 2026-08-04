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
    val fingerprint: String,
    val sizeBytes: Long? = null
)

/**
 * Every byte that can change how a user prompt becomes a text-encoder input has a stable role.
 *
 * Roles are intentionally narrow. A future runtime must add a new explicit role and native
 * receipt before it can advertise direct multilingual input; it cannot smuggle a newly
 * discovered sidecar through an untyped auxiliary-file list.
 */
internal enum class ImagePromptToEncoderAssetRole {
    TEXT_ENCODER_GRAPH,
    TEXT_ENCODER_WEIGHT,
    TOKENIZER_JSON,
    TOKEN_EMBEDDING,
    POSITION_EMBEDDING
}

internal data class ImagePromptToEncoderAsset(
    val role: ImagePromptToEncoderAssetRole,
    val asset: ImageProfileAsset
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

/**
 * Profile-owned language semantics for the exact text encoder. This deliberately does not infer
 * language support from a model family, tokenizer transport, device, or recommendation id.
 */
internal enum class ImageTextEncoderLanguageCapability {
    ENGLISH_DOMINANT,
    NATIVE_MULTILINGUAL
}

internal enum class ImageTextEncoderLanguage {
    ENGLISH,
    CHINESE_SIMPLIFIED
}

internal data class ImageTextEncoderLanguageEvidence(
    val evidenceId: String,
    val evidenceSha256: String,
    val textEncoderAsset: ImageProfileAsset,
    /** Additional immutable files consumed by the same native text-encoder graph. */
    val auxiliaryAssets: List<ImageProfileAsset> = emptyList(),
    /**
     * Versioned, role-aware closure of every asset consumed from prompt text through encoder
     * input. Legacy primary/auxiliary fields remain readable for old profiles, but only this
     * closure can authorize direct Simplified Chinese input.
     */
    val promptToEncoderAssets: List<ImagePromptToEncoderAsset> = emptyList(),
    /**
     * Publisher signature over the complete semantic claim. It is deliberately optional so an
     * unsigned or legacy package remains usable for English prompts, but it can never authorize
     * direct Chinese input.
     */
    val semanticProof: ImageTextEncoderLanguageSemanticProof? = null
) {
    /**
     * Primary graph first for legacy readers. Direct multilingual execution always requires the
     * role-aware closure, so this fallback never grants Chinese admission.
     */
    fun consumedAssets(): List<ImageProfileAsset> = promptToEncoderAssets
        .map(ImagePromptToEncoderAsset::asset)
        .ifEmpty { listOf(textEncoderAsset) + auxiliaryAssets }
}

/** Stable cross-process identity for the complete signed prompt-to-encoder closure. */
internal fun ImageTextEncoderLanguageEvidence.promptToEncoderClosureSha256(): String {
    require(promptToEncoderAssets.isNotEmpty()) {
        "Prompt-to-encoder closure is required."
    }
    val payload = buildList {
        add("mca.image.prompt-to-encoder-assets.v1")
        promptToEncoderAssets.sortedBy { entry -> entry.role.ordinal }.forEach { entry ->
            add(entry.role.name)
            add(entry.asset.relativePath.replace('\\', '/').trim())
            add(entry.asset.sizeBytes?.toString() ?: "-1")
            add(entry.asset.fingerprint.lowercase())
        }
    }.joinToString("\u001f")
    return MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}

internal data class ImageTextEncoderLanguageContract(
    val capability: ImageTextEncoderLanguageCapability,
    val supportedLanguages: Set<ImageTextEncoderLanguage>,
    val evidence: ImageTextEncoderLanguageEvidence? = null
) {
    /** Stable, order-independent representation used by execution and prompt bindings. */
    fun bindingToken(): String = listOf(
        "promptLanguageContractVersion=$LOCAL_IMAGE_PROMPT_LANGUAGE_CONTRACT_VERSION",
        capability.name,
        supportedLanguages.sortedBy { it.ordinal }.joinToString(",") { it.name },
        evidence?.evidenceId?.lowercase().orEmpty(),
        evidence?.evidenceSha256?.lowercase().orEmpty(),
        evidence?.textEncoderAsset?.relativePath?.replace('\\', '/')?.lowercase().orEmpty(),
        evidence?.textEncoderAsset?.fingerprint?.lowercase().orEmpty(),
        evidence?.textEncoderAsset?.sizeBytes?.toString().orEmpty(),
            evidence?.auxiliaryAssets
                ?.sortedWith(compareBy<ImageProfileAsset> { asset ->
                    asset.relativePath.replace('\\', '/').lowercase()
            }.thenBy { asset -> asset.fingerprint.lowercase() })
            ?.joinToString("\u001d") { asset ->
                listOf(
                    asset.relativePath.replace('\\', '/').lowercase(),
                    asset.fingerprint.lowercase(),
                    asset.sizeBytes?.toString().orEmpty()
                ).joinToString("\u001c")
                }
            .orEmpty(),
        evidence?.promptToEncoderAssets
            ?.sortedBy { entry -> entry.role.ordinal }
            ?.joinToString("\u001d") { entry ->
                listOf(
                    entry.role.name,
                    entry.asset.relativePath.replace('\\', '/').lowercase(),
                    entry.asset.fingerprint.lowercase(),
                    entry.asset.sizeBytes?.toString().orEmpty()
                ).joinToString("\u001c")
            }
            .orEmpty(),
        evidence?.semanticProof?.bindingToken().orEmpty()
    ).joinToString("\u001e")
}

/**
 * Opaque signed envelope for a native text-encoder semantic claim. The proof is verified only by
 * [ImagePromptLanguageProofTrust] after the full execution profile is available, because its
 * canonical payload binds profile-owned model and graph fields as well as this evidence closure.
 */
internal data class ImageTextEncoderLanguageSemanticProof(
    val proofVersion: Int,
    val signerKeyId: String,
    val signerCertificateSha256: String,
    val signatureAlgorithm: String,
    val payloadSha256: String,
    val signatureBase64: String
) {
    fun bindingToken(): String = listOf(
        proofVersion.toString(),
        signerKeyId,
        signerCertificateSha256.lowercase(),
        signatureAlgorithm,
        payloadSha256.lowercase(),
        signatureBase64
    ).joinToString("\u001c")
}

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
    fun expectedTimestepCount(steps: Int): Int = when {
        algorithm != ImageSchedulerAlgorithm.PNDM_PLMS -> steps
        skipPrkSteps -> steps + 1
        else -> steps + 9 // 12 PRK warmup calls plus the remaining steps - 3 PLMS calls.
    }
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
    SHARED_UNET_VAE,
    SHARED_TEXT_UNET_VAE
}

internal data class ImageGraphContract(
    val textEncoder: ImageGraphArtifactContract? = null,
    val unet: ImageGraphArtifactContract? = null,
    val vae: ImageGraphArtifactContract? = null,
    val vaeEncoder: ImageGraphArtifactContract? = null,
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
    /** Zero means the runtime accepts any topology-aligned tile in the advertised range. */
    val ultraFixRequiredTileSize: Int = 0,
    val supportsLivePreview: Boolean = false,
    val supportsLora: Boolean = false,
    val maxBatchCount: Int = 1
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
    val capabilities: ImageGenerationCapabilities,
    /**
     * Optional positive admission proof for direct non-English text. Missing fields in legacy
     * manifests remain readable and conservatively resolve to English-dominant.
     */
    val textEncoderLanguage: ImageTextEncoderLanguageContract? = null,
    /** Request-scoped exact file snapshot. It is intentionally omitted from persisted JSON. */
    val textualInversionExecutionAssets: TextualInversionExecutionAssetBinding? = null
) {
    /** Stable binding over the model fingerprint and every executable contract. */
    val bindingFingerprint: String
        get() = sha256Hex(
            buildList {
                addAll(listOf(
                schemaVersion,
                profileId,
                profileRevision,
                modelFingerprint.lowercase(),
                runtime,
                family,
                variant,
                task,
                provenance.primarySource,
                provenance.sources.joinToString("\u001e") { source -> source.name },
                tokenizer,
                conditioning,
                scheduler,
                latent,
                vae,
                graph,
                defaults,
                capabilities
                ))
                // Keep the historical digest byte-for-byte stable when an old profile lacks the
                // optional contract. A positive declaration must invalidate stale prompt evidence.
                textEncoderLanguage?.let { add("textEncoderLanguage:${it.bindingToken()}") }
            }.joinToString("\u001f")
        )

    /**
     * Stable binding for prompt preparation and translation reuse. Generation defaults and
     * sampling/image topology deliberately stay out of this digest: changing a seed, scheduler,
     * size, or default negative prompt cannot change how captured text is tokenized and encoded.
     */
    val promptLanguageBindingFingerprint: String
        get() {
            val profileFingerprint = computedPromptLanguageBindingFingerprint()
            val executionAssets = textualInversionExecutionAssets ?: return profileFingerprint
            // The execution-asset snapshot is captured from this exact profile before the tokenizer
            // asset list is narrowed. Requiring equality here prevents that snapshot branch from
            // bypassing a subsequently declared text-encoder language contract.
            if (textEncoderLanguage != null) {
                require(executionAssets.profilePromptFingerprint == profileFingerprint) {
                    "Textual-inversion execution assets are bound to a different text-encoder language contract."
                }
            }
            return executionAssets.profilePromptFingerprint
        }

    private fun computedPromptLanguageBindingFingerprint(): String = sha256Hex(
            buildList {
                addAll(listOf(
                "prompt-language-binding-v2",
                "prompt-language-contract-version=$LOCAL_IMAGE_PROMPT_LANGUAGE_CONTRACT_VERSION",
                modelFingerprint.lowercase(),
                runtime,
                family,
                variant,
                task,
                provenance.primarySource,
                provenance.sources.joinToString("\u001e") { source -> source.name },
                tokenizer,
                conditioning,
                graph.textEncoder,
                graph.workerStrategy
                ))
                textEncoderLanguage?.let { add("textEncoderLanguage:${it.bindingToken()}") }
            }.joinToString("\u001f")
        )
}

/**
 * This is the sole profile-level admission predicate for direct Simplified Chinese prompts. It
 * intentionally excludes model family, tokenizer UTF-8 transport, device properties, and runtime
 * discovery. The asset check prevents copied metadata from granting a different encoder access.
 */
/**
 * Checks the executable prompt-to-encoder topology before signature verification. This is kept
 * separate from the proof so validators can explain malformed future profiles without turning a
 * missing proof into a package-level import failure.
 */
internal fun ImageExecutionProfile.hasCompleteNativeMultilingualPromptToEncoderTopology(): Boolean {
    val contract = textEncoderLanguage ?: return false
    val evidence = contract.evidence ?: return false
    val graphTextEncoder = graph.textEncoder ?: return false
    val closure = evidence.promptToEncoderAssets
    val roles = closure.map(ImagePromptToEncoderAsset::role)
    val closureAssets = closure.map(ImagePromptToEncoderAsset::asset)
    val normalizedClosurePaths = closureAssets.map { asset ->
        asset.relativePath.replace('\\', '/').trim()
    }
    val graphEntry = closure.singleOrNull {
        it.role == ImagePromptToEncoderAssetRole.TEXT_ENCODER_GRAPH
    } ?: return false
    fun sameAsset(left: ImageProfileAsset, right: ImageProfileAsset): Boolean =
        left.relativePath.replace('\\', '/').trim().equals(
            right.relativePath.replace('\\', '/').trim(),
            ignoreCase = true
        ) && left.fingerprint.equals(right.fingerprint, ignoreCase = true) &&
            left.sizeBytes == right.sizeBytes
    val closureMatchesTokenizerAssets = closureAssets.size == tokenizer.assets.size &&
        closureAssets.all { entry -> tokenizer.assets.any { candidate -> sameAsset(entry, candidate) } }
    if (
        contract.capability != ImageTextEncoderLanguageCapability.NATIVE_MULTILINGUAL ||
        ImageTextEncoderLanguage.ENGLISH !in contract.supportedLanguages ||
        ImageTextEncoderLanguage.CHINESE_SIMPLIFIED !in contract.supportedLanguages ||
        conditioning.dualEncoder ||
        !TEXT_ENCODER_LANGUAGE_EVIDENCE_ID.matches(evidence.evidenceId) ||
        !TEXT_ENCODER_LANGUAGE_SHA256.matches(evidence.evidenceSha256) ||
        closure.isEmpty() ||
        roles.distinct().size != roles.size ||
        normalizedClosurePaths.distinctBy { path -> path.lowercase() }.size !=
            normalizedClosurePaths.size ||
        closureAssets.any { asset ->
            !isSafeTextEncoderLanguageEvidencePath(
                asset.relativePath.replace('\\', '/').trim()
            ) ||
                !TEXT_ENCODER_LANGUAGE_SHA256.matches(asset.fingerprint) ||
                asset.sizeBytes == null || asset.sizeBytes <= 0L
        } ||
        !sameAsset(graphEntry.asset, evidence.textEncoderAsset) ||
        !graphTextEncoder.relativePath.replace('\\', '/').trim().equals(
            graphEntry.asset.relativePath.replace('\\', '/').trim(),
            ignoreCase = true
        ) ||
        graphTextEncoder.graphName.isBlank() ||
        !closureMatchesTokenizerAssets
    ) return false

    return when (runtime) {
        LocalImageRuntime.QNN_HTP ->
            graph.workerStrategy == ImageWorkerStrategy.SHARED_TEXT_UNET_VAE &&
                tokenizer.backend == ImageTokenizerBackend.TOKENIZERS_CPP &&
                graphEntry.asset.relativePath.endsWith(".bin", ignoreCase = true) &&
                roles.toSet() == setOf(
                    ImagePromptToEncoderAssetRole.TEXT_ENCODER_GRAPH,
                    ImagePromptToEncoderAssetRole.TOKENIZER_JSON
                )
        // MNN direct generation still has legacy discovery-based tokenizer and host-embedding
        // reads. It remains English-dominant until its descriptor-backed native receipt consumes
        // the complete role-aware closure rather than only graph/.weight.
        LocalImageRuntime.MNN_DIFFUSION,
        LocalImageRuntime.STABLE_DIFFUSION_CPP,
        LocalImageRuntime.ONNX_RUNTIME,
        LocalImageRuntime.CUSTOM -> false
    }
}

internal fun ImageExecutionProfile.hasVerifiedNativeSimplifiedChineseTextEncoder(): Boolean {
    val contract = textEncoderLanguage ?: return false
    val evidence = contract.evidence ?: return false
    return hasCompleteNativeMultilingualPromptToEncoderTopology() &&
        ImagePromptLanguageProofTrust.isVerified(
            profile = this,
            contract = contract,
            evidence = evidence
        )
}

/**
 * The digest is supplied to native only after Android has verified the publisher signature. Native
 * returns the same opaque value after consuming the exact encoder closure; it does not need an
 * additional copy of the public key or a JNI ABI change.
 */
internal fun ImageExecutionProfile.verifiedNativeSimplifiedChineseLanguageProofSha256(): String? {
    val proof = textEncoderLanguage?.evidence?.semanticProof ?: return null
    return proof.payloadSha256.lowercase().takeIf {
        hasVerifiedNativeSimplifiedChineseTextEncoder()
    }
}

private val TEXT_ENCODER_LANGUAGE_EVIDENCE_ID =
    Regex("^[a-z0-9][a-z0-9._-]{2,127}$")
private val TEXT_ENCODER_LANGUAGE_SHA256 = Regex("^[0-9a-f]{64}$")

private fun isSafeTextEncoderLanguageEvidencePath(value: String): Boolean =
    value.isNotBlank() &&
        !value.startsWith('/') &&
        !Regex("^[A-Za-z]:").containsMatchIn(value) &&
        value.split('/').all { segment ->
            segment.isNotBlank() && segment != "." && segment != ".."
        }

/**
 * The profile-facing Chinese admission result. Consumers should use this instead of inspecting
 * the raw declaration so a malformed or unpinned declaration cannot bypass the asset check.
 */
internal fun ImageExecutionProfile.chinesePromptLanguageCapability(): ImageTextEncoderLanguageCapability =
    if (hasVerifiedNativeSimplifiedChineseTextEncoder()) {
        ImageTextEncoderLanguageCapability.NATIVE_MULTILINGUAL
    } else {
        ImageTextEncoderLanguageCapability.ENGLISH_DOMINANT
    }

/**
 * Native live preview is an execution-topology property, not a recommendation or device allowlist.
 * Any QNN package that really keeps its UNet and VAE in one worker session can use the same path.
 */
internal fun ImageExecutionProfile.hasSharedQnnVaePreviewTopology(): Boolean =
    runtime == LocalImageRuntime.QNN_HTP &&
        graph.vae != null &&
        (graph.workerStrategy == ImageWorkerStrategy.SHARED_UNET_VAE ||
            graph.workerStrategy == ImageWorkerStrategy.SHARED_TEXT_UNET_VAE)

/**
 * Split SDXL workers are intentionally excluded from the first live-preview product contract.
 * Their UNet and VAE phases run in separate processes; a projection frame would be an
 * approximation until a resumable phase checkpoint exists. Keep the predicate for wire/schema
 * compatibility, but never advertise or admit it as a user-facing capability.
 */
internal fun ImageExecutionProfile.hasSplitQnnSdxlProjectionPreviewTopology(): Boolean =
    false

/**
 * Product img2img admission follows executable graph topology only. Recommendation ids, device
 * discovery, chipset profiles, and validation history deliberately do not participate.
 */
internal fun ImageExecutionProfile.hasExecutableQnnImg2ImgTopology(): Boolean {
    if (runtime != LocalImageRuntime.QNN_HTP ||
        graph.vaeEncoder == null || graph.unet == null || graph.vae == null
    ) {
        return false
    }
    return when (graph.workerStrategy) {
        ImageWorkerStrategy.SPLIT_UNET_VAE ->
            vae.inputShape == listOf(1, 4, 128, 128) &&
                vae.outputShape == listOf(1, 3, 1024, 1024)
        ImageWorkerStrategy.SHARED_UNET_VAE,
        ImageWorkerStrategy.SHARED_TEXT_UNET_VAE ->
            vae.inputShape == listOf(1, 4, 64, 64) &&
                vae.outputShape == listOf(1, 3, 512, 512)
        ImageWorkerStrategy.IN_PROCESS,
        ImageWorkerStrategy.DEDICATED_WORKER -> false
    }
}

internal fun ImageExecutionProfile.hasSharedQnnImg2ImgTopology(): Boolean =
    hasExecutableQnnImg2ImgTopology() &&
        graph.workerStrategy in setOf(
            ImageWorkerStrategy.SHARED_UNET_VAE,
            ImageWorkerStrategy.SHARED_TEXT_UNET_VAE
        )

/**
 * Textual inversion is executable only when native code owns the CLIP token-table lookup and
 * can replace exact input_embedding rows before the Transformer. Graph-internal token lookup
 * (for example Gen5 QNN text_encoder.bin and Sana MNN_MTOK) deliberately remains unsupported.
 * This predicate is topology-only: recommendation ids, devices and chipsets never participate.
 */
internal fun ImageExecutionProfile.hasHostWritableClipTextualInversionTopology(): Boolean {
    if (tokenizer.backend != ImageTokenizerBackend.TOKENIZERS_CPP ||
        tokenizer.maxLength != 77 ||
        tokenizer.bosId == null || tokenizer.eosId == null || tokenizer.padId == null ||
        conditioning.diskDataType !in setOf(
            ImageEmbeddingDiskDataType.FP16,
            ImageEmbeddingDiskDataType.FP32
        ) ||
        conditioning.textEncoderInputShape != listOf(1, 77) ||
        graph.unet == null || graph.vae == null
    ) {
        return false
    }
    val textEncoderName = graph.textEncoder
        ?.relativePath
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.lowercase()
        ?: return false
    return when (runtime) {
        LocalImageRuntime.QNN_HTP -> when (graph.workerStrategy) {
            ImageWorkerStrategy.SHARED_UNET_VAE ->
                family in setOf(LocalImageModelFamily.SD15, LocalImageModelFamily.SD21) &&
                    textEncoderName == "clip_v2.mnn" && !conditioning.dualEncoder &&
                    !conditioning.pooledOutput &&
                    conditioning.textEncoderOutputShapes == listOf(listOf(1, 77, 768))
            ImageWorkerStrategy.SPLIT_UNET_VAE ->
                family == LocalImageModelFamily.SDXL && textEncoderName == "clip.mnn" &&
                    conditioning.dualEncoder && conditioning.pooledOutput &&
                    conditioning.textEncoderOutputShapes == listOf(
                        listOf(1, 77, 768),
                        listOf(1, 77, 1_280)
                    )
            else -> false
        }
        LocalImageRuntime.MNN_DIFFUSION ->
            family == LocalImageModelFamily.SD15 &&
                graph.workerStrategy == ImageWorkerStrategy.IN_PROCESS &&
                textEncoderName == "clip_v2.mnn" && !conditioning.dualEncoder &&
                !conditioning.pooledOutput &&
                conditioning.textEncoderOutputShapes == listOf(listOf(1, 77, 768))
        LocalImageRuntime.STABLE_DIFFUSION_CPP,
        LocalImageRuntime.ONNX_RUNTIME,
        LocalImageRuntime.CUSTOM -> false
    }
}

/**
 * QNN/MNN textual inversion is derived from the executable graph contract, never from a
 * recommendation id or hardware profile. This also strips stale manifest capability bits from
 * graph-internal text encoders while allowing an imported host-writable CLIP topology.
 */
internal fun ImageExecutionProfile.withTopologyDerivedTextualInversionCapability(): ImageExecutionProfile {
    if (runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP) return this
    val supported = hasHostWritableClipTextualInversionTopology()
    return copy(
        tokenizer = tokenizer.copy(supportsTextualInversion = supported),
        capabilities = capabilities.copy(supportsTextualInversion = supported)
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
                if (asset.sizeBytes != null && asset.sizeBytes <= 0L) {
                    issue("ASSET_SIZE_INVALID", "tokenizer.assets[$index].sizeBytes", "Tokenizer asset size must be positive.")
                }
            }
            profile.textEncoderLanguage?.let { contract ->
                val languages = contract.supportedLanguages
                if (languages.isEmpty()) {
                    issue(
                        "TEXT_ENCODER_LANGUAGE_CONTRACT_INVALID",
                        "textEncoderLanguage.supportedLanguages",
                        "Text encoder language support must declare at least one language."
                    )
                }
                when (contract.capability) {
                    ImageTextEncoderLanguageCapability.ENGLISH_DOMINANT -> {
                        if (languages != setOf(ImageTextEncoderLanguage.ENGLISH)) {
                            issue(
                                "TEXT_ENCODER_LANGUAGE_CONTRACT_INVALID",
                                "textEncoderLanguage.supportedLanguages",
                                "English-dominant text encoders must declare English only."
                            )
                        }
                        if (contract.evidence != null) {
                            issue(
                                "TEXT_ENCODER_LANGUAGE_CONTRACT_INVALID",
                                "textEncoderLanguage.evidence",
                                "English-dominant text encoders must not publish multilingual semantic evidence."
                            )
                        }
                    }
                    ImageTextEncoderLanguageCapability.NATIVE_MULTILINGUAL -> {
                        if (ImageTextEncoderLanguage.ENGLISH !in languages ||
                            ImageTextEncoderLanguage.CHINESE_SIMPLIFIED !in languages
                        ) {
                            issue(
                                "TEXT_ENCODER_LANGUAGE_CONTRACT_INVALID",
                                "textEncoderLanguage.supportedLanguages",
                                "Native multilingual admission requires explicit English and Simplified Chinese support."
                            )
                        }
                        val evidence = contract.evidence
                        if (evidence == null) {
                            issue(
                                "TEXT_ENCODER_LANGUAGE_EVIDENCE_MISSING",
                                "textEncoderLanguage.evidence",
                                "Native multilingual admission requires immutable text-encoder semantic evidence."
                            )
                        } else {
                            if (!PROFILE_ID.matches(evidence.evidenceId)) {
                                issue(
                                    "TEXT_ENCODER_LANGUAGE_EVIDENCE_INVALID",
                                    "textEncoderLanguage.evidence.evidenceId",
                                    "Text encoder language evidence id must be a stable lower-case identifier."
                                )
                            }
                            if (!SHA256.matches(evidence.evidenceSha256)) {
                                issue(
                                    "TEXT_ENCODER_LANGUAGE_EVIDENCE_INVALID",
                                    "textEncoderLanguage.evidence.evidenceSha256",
                                    "Text encoder language evidence must use a SHA-256 fingerprint."
                                )
                            }
                            val evidenceAssets = evidence.consumedAssets()
                            val normalizedEvidencePaths = evidenceAssets.map { asset ->
                                asset.relativePath.replace('\\', '/').trim().lowercase()
                            }
                            if (normalizedEvidencePaths.distinct().size != normalizedEvidencePaths.size) {
                                issue(
                                    "TEXT_ENCODER_LANGUAGE_EVIDENCE_INVALID",
                                    "textEncoderLanguage.evidence.auxiliaryAssets",
                                    "Text encoder language evidence assets must use unique bundle-relative paths."
                                )
                            }
                            evidenceAssets.forEachIndexed { index, asset ->
                                val assetField = if (index == 0) {
                                    "textEncoderLanguage.evidence.textEncoderAsset"
                                } else {
                                    "textEncoderLanguage.evidence.auxiliaryAssets[${index - 1}]"
                                }
                                if (!safeRelativePath(asset.relativePath)) {
                                    issue(
                                        "PROFILE_PATH_INVALID",
                                        "$assetField.relativePath",
                                        "Text encoder language evidence asset must stay inside the bundle."
                                    )
                                }
                                if (!SHA256.matches(asset.fingerprint)) {
                                    issue(
                                        "ASSET_FINGERPRINT_INVALID",
                                        "$assetField.fingerprint",
                                        "Text encoder language evidence asset must use a SHA-256 fingerprint."
                                    )
                                }
                                if (asset.sizeBytes == null || asset.sizeBytes <= 0L) {
                                    issue(
                                        "ASSET_SIZE_INVALID",
                                        "$assetField.sizeBytes",
                                        "Text encoder language evidence asset must pin a positive source size."
                                    )
                                }
                                val assetPinnedByProfile = profile.tokenizer.assets.any { candidate ->
                                    candidate.relativePath.replace('\\', '/').trim().equals(
                                        asset.relativePath.replace('\\', '/').trim(),
                                        ignoreCase = true
                                    ) && candidate.fingerprint.equals(asset.fingerprint, ignoreCase = true) &&
                                        candidate.sizeBytes == asset.sizeBytes
                                }
                                if (!assetPinnedByProfile) {
                                    issue(
                                        "TEXT_ENCODER_LANGUAGE_EVIDENCE_INVALID",
                                        assetField,
                                        "Text encoder language evidence must bind an exact profile text-encoder asset."
                                    )
                                }
                            }
                            if (profile.conditioning.dualEncoder) {
                                issue(
                                    "TEXT_ENCODER_LANGUAGE_TOPOLOGY_UNSUPPORTED",
                                    "conditioning.dualEncoder",
                                    "Direct Chinese admission requires every native text encoder to be explicitly evidence-bound; dual-encoder topology is not represented by this contract."
                                )
                            }
                            val graphTextEncoder = profile.graph.textEncoder
                            if (graphTextEncoder == null ||
                                !graphTextEncoder.relativePath.replace('\\', '/').equals(
                                    evidence.textEncoderAsset.relativePath.replace('\\', '/'),
                                    ignoreCase = true
                                )
                            ) {
                                issue(
                                    "TEXT_ENCODER_LANGUAGE_EVIDENCE_INVALID",
                                    "textEncoderLanguage.evidence.textEncoderAsset",
                                    "Text encoder language evidence must bind the graph text encoder consumed at runtime."
                                )
                            }
                            if (!profile.hasCompleteNativeMultilingualPromptToEncoderTopology()) {
                                issue(
                                    "TEXT_ENCODER_LANGUAGE_TOPOLOGY_UNSUPPORTED",
                                    "textEncoderLanguage.evidence.promptToEncoderAssets",
                                    "Direct Chinese admission requires the complete role-aware prompt-to-encoder closure for a descriptor-backed QNN text encoder."
                                )
                            }
                        }
                    }
                }
            }
            if (profile.tokenizer.maxLength <= 0) {
                issue("TOKENIZER_CONTRACT_INVALID", "tokenizer.maxLength", "Tokenizer max length must be positive.")
            }
            if (
                profile.capabilities.supportsPromptWeighting &&
                !profile.tokenizer.supportsPromptWeighting
            ) {
                issue(
                    "CAPABILITY_CONTRACT_INVALID",
                    "supportsPromptWeighting",
                    "Image generation cannot advertise prompt weighting without tokenizer support."
                )
            }
            if (profile.capabilities.supportsTextualInversion !=
                profile.tokenizer.supportsTextualInversion
            ) {
                issue(
                    "CAPABILITY_CONTRACT_INVALID",
                    "supportsTextualInversion",
                    "Tokenizer and generation textual-inversion capabilities must agree."
                )
            }
            if (profile.runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP &&
                profile.capabilities.supportsTextualInversion &&
                !profile.hasHostWritableClipTextualInversionTopology()
            ) {
                issue(
                    "CAPABILITY_CONTRACT_INVALID",
                    "supportsTextualInversion",
                    "QNN/MNN textual inversion requires a host-writable CLIP input_embedding topology."
                )
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
            if (profile.capabilities.supportsLivePreview &&
                profile.runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP &&
                !profile.hasSharedQnnVaePreviewTopology() &&
                !profile.hasSplitQnnSdxlProjectionPreviewTopology()
            ) {
                issue(
                    "CAPABILITY_CONTRACT_INVALID",
                    "capabilities.supportsLivePreview",
                    "Live preview requires stable-diffusion.cpp or a shared QNN VAE topology."
                )
            }
            if (profile.capabilities.supportsUltraFix &&
                ((profile.runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP &&
                    !profile.capabilities.supportsVaeTiling) ||
                    (profile.runtime == LocalImageRuntime.QNN_HTP &&
                        !profile.hasExecutableQnnUltraFixTopology()) ||
                    (profile.runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP &&
                        profile.runtime != LocalImageRuntime.QNN_HTP) ||
                    profile.family !in setOf(
                        LocalImageModelFamily.SD15,
                        LocalImageModelFamily.SD21,
                        LocalImageModelFamily.SDXL,
                        LocalImageModelFamily.SD_TURBO
                    ) ||
                    profile.scheduler.predictionType == ImagePredictionType.FLOW)
            ) {
                issue(
                    "CAPABILITY_CONTRACT_INVALID",
                    "capabilities.supportsUltraFix",
                    "UltraFix requires a standard native UNet, VAE encode/decode, and topology-aligned tiling."
                )
            }
            val ultraFixDimensions = listOf(
                profile.capabilities.ultraFixMinWidth,
                profile.capabilities.ultraFixMaxWidth,
                profile.capabilities.ultraFixMinHeight,
                profile.capabilities.ultraFixMaxHeight,
                profile.capabilities.ultraFixWidthMultiple,
                profile.capabilities.ultraFixHeightMultiple
            )
            if (profile.capabilities.supportsUltraFix) {
                if (profile.capabilities.ultraFixMinWidth !in 64..profile.capabilities.ultraFixMaxWidth ||
                    profile.capabilities.ultraFixMinHeight !in 64..profile.capabilities.ultraFixMaxHeight ||
                    profile.capabilities.ultraFixMaxWidth > 8_192 ||
                    profile.capabilities.ultraFixMaxHeight > 8_192 ||
                    profile.capabilities.ultraFixWidthMultiple <= 0 ||
                    profile.capabilities.ultraFixHeightMultiple <= 0 ||
                    profile.capabilities.ultraFixMinWidth %
                        profile.capabilities.ultraFixWidthMultiple != 0 ||
                    profile.capabilities.ultraFixMinHeight %
                        profile.capabilities.ultraFixHeightMultiple != 0 ||
                    profile.capabilities.ultraFixMaxWidth %
                        profile.capabilities.ultraFixWidthMultiple != 0 ||
                    profile.capabilities.ultraFixMaxHeight %
                        profile.capabilities.ultraFixHeightMultiple != 0
                ) {
                    issue(
                        "CAPABILITY_CONTRACT_INVALID",
                        "capabilities.ultraFixDimensions",
                        "UltraFix dimensions must form a bounded, topology-aligned 64..8192 range."
                    )
                }
                if (profile.capabilities.ultraFixRequiredTileSize != 0 &&
                    (profile.capabilities.ultraFixRequiredTileSize !in
                        profile.capabilities.ultraFixMinWidth..profile.capabilities.ultraFixMaxWidth ||
                        profile.capabilities.ultraFixRequiredTileSize !in
                            profile.capabilities.ultraFixMinHeight..profile.capabilities.ultraFixMaxHeight ||
                        profile.capabilities.ultraFixRequiredTileSize %
                            profile.capabilities.ultraFixWidthMultiple != 0 ||
                        profile.capabilities.ultraFixRequiredTileSize %
                            profile.capabilities.ultraFixHeightMultiple != 0)
                ) {
                    issue(
                        "CAPABILITY_CONTRACT_INVALID",
                        "capabilities.ultraFixRequiredTileSize",
                        "A fixed UltraFix graph tile must be zero or lie inside the topology-aligned bounds."
                    )
                }
            } else if (ultraFixDimensions.any { it != 0 } ||
                profile.capabilities.ultraFixRequiredTileSize != 0
            ) {
                issue(
                    "CAPABILITY_CONTRACT_INVALID",
                    "capabilities.ultraFixDimensions",
                    "A profile without UltraFix support must not publish UltraFix dimension bounds."
                )
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
            if (capabilities.maxBatchCount !in 1..8) {
                issue(
                    "CAPABILITY_CONTRACT_INVALID",
                    "capabilities.maxBatchCount",
                    "Maximum image batch count must be between 1 and 8."
                )
            }
        }
        return ImageProfileValidationReport(issues)
    }

    private fun positiveShape(shape: List<Int>): Boolean = shape.isNotEmpty() && shape.all { it > 0 }

    private fun graphPaths(graph: ImageGraphContract): List<Pair<String, String>> = buildList {
        graph.textEncoder?.relativePath?.let { add("graph.textEncoder" to it) }
        graph.unet?.relativePath?.let { add("graph.unet" to it) }
        graph.vae?.relativePath?.let { add("graph.vae" to it) }
        graph.vaeEncoder?.relativePath?.let { add("graph.vaeEncoder" to it) }
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
    val promptWeightingSupported: Boolean,
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
    val promptWeightingSupported: Boolean,
    val promptWeightingApplied: Boolean,
    val positiveWeightedTokenCount: Int,
    val negativeWeightedTokenCount: Int,
    val promptWeightFingerprint: String,
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
            if (resolved.runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
                if (native.tokenCount <= 0) {
                    add(
                        ImageExecutionMismatch(
                            "tokenCount",
                            "positive native conditioning count",
                            native.tokenCount.toString()
                        )
                    )
                }
            } else {
                compare("tokenCount", resolved.tokenCount, native.tokenCount)
            }
            compare(
                "promptWeightingSupported",
                resolved.promptWeightingSupported,
                native.promptWeightingSupported
            )
            if (!native.promptWeightingSupported) {
                compare("promptWeightingApplied", false, native.promptWeightingApplied)
                compare(
                    "positiveWeightedTokenCount",
                    0,
                    native.positiveWeightedTokenCount
                )
                compare(
                    "negativeWeightedTokenCount",
                    0,
                    native.negativeWeightedTokenCount
                )
            }
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
