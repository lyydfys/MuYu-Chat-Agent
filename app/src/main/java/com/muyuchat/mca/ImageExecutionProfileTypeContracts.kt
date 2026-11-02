package com.muyuchat.mca

import java.security.MessageDigest

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
    QWEN_IMAGE_21,
    LONGCAT_IMAGE,
    CONTROLNET_CANNY,
    SANA_EDIT,
    ANIMA_PREVIEW,
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

internal enum class ImageTokenizerBackend { TOKENIZERS_CPP, MNN_MTOK, MNN_QWEN_IMAGE, SDCPP_NATIVE }
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
