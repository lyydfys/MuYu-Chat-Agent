package com.muyuchat.core.download

enum class RecommendedChatRuntime(val label: String) {
    MNN("MNN 高速引擎"),
    GGUF("GGUF 兼容引擎"),
    GENIEX_QAIRT("GenieX QAIRT / 骁龙 NPU"),
    LITERT_LM("LiteRT-LM 引擎")
}

enum class RecommendedComputeBackend(val label: String) {
    CPU("CPU"),
    GPU("GPU"),
    NPU("Qualcomm NPU")
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
    QWEN_IMAGE_21,
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
    /** Keep all constraints within the pinned CLIP tokenizer's 75 content-token budget. */
    const val ANIMAGINE_XL_NEGATIVE_PROMPT =
        "worst quality, low quality, blurry, distorted face, asymmetrical eyes, cross-eyed, " +
            "bad anatomy, bad hands, fused fingers, missing fingers, extra fingers, extra limbs, " +
            "duplicate, realistic photo, text, signature, watermark, username"
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
    // This is catalog-owned executable text, not a user prompt. Keep it in the canonical ASCII
    // prompt grammar because the current stable-diffusion.cpp Qwen path has no independently
    // verified native multilingual text-encoder receipt. Do not substitute an on-device or chat
    // translation for user-provided Chinese; that remains a separate capability.
    const val QWEN_IMAGE_2512_NEGATIVE_PROMPT =
        "low resolution, low quality, malformed limbs, malformed fingers, oversaturated, " +
            "waxy skin, lacking facial detail, over-smoothed, artificial appearance, " +
            "chaotic composition, blurry text, distorted text"
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
enum class ImageEngineTokenizerBackend { TOKENIZERS_CPP, MNN_MTOK, MNN_QWEN_IMAGE, SDCPP_NATIVE }
enum class ImageEngineClipPadRule { EOS, ZERO, MODEL_DECLARED }
enum class ImageEngineEmbeddingDataType { FP16, FP32, GRAPH_INTERNAL, RUNTIME_NATIVE }
enum class ImageEngineEmbeddingConversionStrategy {
    NONE,
    FP32_TO_FP16_STREAMING,
    GRAPH_EXECUTION,
    RUNTIME_NATIVE
}

/**
 * Prompt-language admission is deliberately evidence-bound bundle data, rather than a
 * model-family, recommendation identity, or tokenizer-transport heuristic. A missing contract
 * is conservatively English-dominant.
 */
enum class ImageEngineTextEncoderLanguageCapability {
    ENGLISH_DOMINANT,
    NATIVE_MULTILINGUAL
}

enum class ImageEngineTextEncoderLanguage {
    ENGLISH,
    CHINESE_SIMPLIFIED
}

/** Explicit role for an immutable file that turns prompt text into encoder input. */
enum class ImageEnginePromptToEncoderAssetRole {
    TEXT_ENCODER_GRAPH,
    TEXT_ENCODER_WEIGHT,
    TOKENIZER_JSON,
    TOKEN_EMBEDDING,
    POSITION_EMBEDDING
}

data class ImageEnginePromptToEncoderAssetSpec(
    val role: ImageEnginePromptToEncoderAssetRole,
    val relativePath: String,
    val sha256: String
) {
    init {
        val normalizedPath = relativePath.trim().replace('\\', '/')
        require(
            normalizedPath.isNotBlank() &&
                !normalizedPath.startsWith('/') &&
                !Regex("^[A-Za-z]:").containsMatchIn(normalizedPath) &&
                normalizedPath.split('/').all { segment ->
                    segment.isNotBlank() && segment != "." && segment != ".."
                }
        ) { "Prompt-to-encoder asset path must stay inside the bundle." }
        require(sha256.matches(Regex("^[0-9a-f]{64}$"))) {
            "Prompt-to-encoder asset must use a lowercase SHA-256 fingerprint."
        }
    }
}

/**
 * A secondary file whose bytes participate in the text encoder's semantics (for example an MNN
 * external `.weight` tensor file).  It is deliberately a catalog pin, never a file discovered at
 * request time.
 */
data class ImageEngineTextEncoderLanguageAuxiliaryAssetSpec(
    val relativePath: String,
    val sha256: String
) {
    init {
        val normalizedPath = relativePath.trim().replace('\\', '/')
        require(
            normalizedPath.isNotBlank() &&
                !normalizedPath.startsWith('/') &&
                !Regex("^[A-Za-z]:").containsMatchIn(normalizedPath) &&
                normalizedPath.split('/').all { segment ->
                    segment.isNotBlank() && segment != "." && segment != ".."
                }
        ) {
            "Text encoder language auxiliary asset path must stay inside the bundle."
        }
        require(sha256.matches(Regex("^[0-9a-f]{64}$"))) {
            "Text encoder language auxiliary asset must use a lowercase SHA-256 fingerprint."
        }
    }
}

/**
 * Publisher-signed semantic claim for a native text encoder. The catalog carries this opaque
 * envelope but never treats it as verified itself: the installed app verifies it against its
 * signing-certificate trust root after the complete execution profile has been materialized.
 *
 * The fields intentionally stay strings rather than optimistic enums. An unsupported future
 * algorithm or malformed envelope must conservatively downgrade to English-dominant instead of
 * making the package unavailable for its normal English path.
 */
data class ImageEngineTextEncoderLanguageSemanticProofSpec(
    val proofVersion: Int,
    val signerKeyId: String,
    val signerCertificateSha256: String,
    val signatureAlgorithm: String,
    val payloadSha256: String,
    val signatureBase64: String
)

/**
 * Immutable evidence for a text encoder's semantic language support. The referenced evidence is
 * expected to be a native tokenizer/encoder semantic proof, not a device observation or a model
 * family label. Every consumed encoder byte is pinned independently below.
 */
data class ImageEngineTextEncoderLanguageEvidenceSpec(
    val evidenceId: String,
    val evidenceSha256: String,
    val textEncoderAssetPath: String,
    val textEncoderAssetSha256: String,
    val auxiliaryAssets: List<ImageEngineTextEncoderLanguageAuxiliaryAssetSpec> = emptyList(),
    /**
     * Complete, role-aware prompt-to-encoder closure. Empty legacy closures remain usable for
     * English but can never authorize direct Chinese input.
     */
    val promptToEncoderAssets: List<ImageEnginePromptToEncoderAssetSpec> = emptyList(),
    /** Missing or untrusted proof means English-dominant at runtime, never package rejection. */
    val semanticProof: ImageEngineTextEncoderLanguageSemanticProofSpec? = null
) {
    init {
        require(evidenceId.matches(Regex("^[a-z0-9][a-z0-9._-]{2,127}$"))) {
            "Text encoder language evidence id must be a stable lower-case identifier."
        }
        require(evidenceSha256.matches(Regex("^[0-9a-f]{64}$"))) {
            "Text encoder language evidence must use a lowercase SHA-256 fingerprint."
        }
        val normalizedPath = textEncoderAssetPath.trim().replace('\\', '/')
        require(
            normalizedPath.isNotBlank() &&
                !normalizedPath.startsWith('/') &&
                !Regex("^[A-Za-z]:").containsMatchIn(normalizedPath) &&
                normalizedPath.split('/').all { segment ->
                    segment.isNotBlank() && segment != "." && segment != ".."
                }
        ) {
            "Text encoder language evidence asset path must stay inside the bundle."
        }
        require(textEncoderAssetSha256.matches(Regex("^[0-9a-f]{64}$"))) {
            "Text encoder language evidence asset must use a lowercase SHA-256 fingerprint."
        }
        val normalizedPrimaryPath = normalizedPath.lowercase()
        val normalizedAuxiliaryPaths = auxiliaryAssets.map { asset ->
            asset.relativePath.trim().replace('\\', '/').lowercase()
        }
        require(normalizedAuxiliaryPaths.distinct().size == normalizedAuxiliaryPaths.size) {
            "Text encoder language auxiliary evidence paths must be unique."
        }
        require(normalizedPrimaryPath !in normalizedAuxiliaryPaths) {
            "Text encoder language auxiliary evidence must not repeat the primary encoder asset."
        }
        val closureRoles = promptToEncoderAssets.map { asset -> asset.role }
        val closurePaths = promptToEncoderAssets.map { asset ->
            asset.relativePath.trim().replace('\\', '/').lowercase()
        }
        require(closureRoles.distinct().size == closureRoles.size) {
            "Prompt-to-encoder evidence roles must be unique."
        }
        require(closurePaths.distinct().size == closurePaths.size) {
            "Prompt-to-encoder evidence paths must be unique."
        }
    }
}

data class ImageEngineTextEncoderLanguageContractSpec(
    val capability: ImageEngineTextEncoderLanguageCapability,
    val supportedLanguages: Set<ImageEngineTextEncoderLanguage>,
    val evidence: ImageEngineTextEncoderLanguageEvidenceSpec? = null
) {
    init {
        require(supportedLanguages.isNotEmpty()) {
            "Text encoder language support must declare at least one language."
        }
        when (capability) {
            ImageEngineTextEncoderLanguageCapability.ENGLISH_DOMINANT -> {
                require(supportedLanguages == setOf(ImageEngineTextEncoderLanguage.ENGLISH)) {
                    "English-dominant text encoders must declare English only."
                }
                require(evidence == null) {
                    "English-dominant text encoders must not publish multilingual semantic evidence."
                }
            }
            ImageEngineTextEncoderLanguageCapability.NATIVE_MULTILINGUAL -> {
                require(ImageEngineTextEncoderLanguage.ENGLISH in supportedLanguages) {
                    "Native multilingual text encoders must retain English support."
                }
                require(ImageEngineTextEncoderLanguage.CHINESE_SIMPLIFIED in supportedLanguages) {
                    "Native multilingual admission requires explicit Simplified Chinese support."
                }
                require(evidence != null) {
                    "Native multilingual text encoders require immutable semantic evidence."
                }
            }
        }
    }
}
