package com.muyuchat.mca

import com.muyuchat.api.local.imagePromptExecutionSha256
import com.muyuchat.api.local.imagePromptTranslationProofFingerprint
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

internal const val CURRENT_LOCAL_IMAGE_PROMPT_TRANSLATION_CONTRACT_VERSION = 4
/**
 * Versioned alongside the native `native_prompt_language_contract.hpp` grammar. A bump invalidates
 * cached prompt preparation through the profile prompt-language fingerprint.
 */
internal const val LOCAL_IMAGE_PROMPT_LANGUAGE_CONTRACT_VERSION = 1
internal const val LOCAL_IMAGE_PROMPT_TRANSLATION_CANARY_PROMPT =
    "一只红色杯子放在蓝色桌子上，杯子左侧有两个绿色苹果"
internal const val LOCAL_IMAGE_PROMPT_TRANSLATION_CANARY_NEGATIVE_PROMPT =
    "不要人物，不要文字，不要多余水果"

internal enum class LocalImageTextEncoderLanguageCapability {
    NATIVE_MULTILINGUAL,
    ENGLISH_DOMINANT
}

enum class LocalImagePromptTransformationMethod {
    DIRECT,
    NATIVE_MULTILINGUAL,
    LOCAL_LLM_ZH_TO_EN,
    /** Original UTF-8 text reached the exact native tokenizer without an LLM translation. */
    DIRECT_UTF8_PASSTHROUGH
}

enum class LocalImageNegativePromptSource {
    USER,
    MODEL_DEFAULT,
    EMPTY
}

internal data class LocalImageFinalNegativePrompt(
    val value: String,
    val source: LocalImageNegativePromptSource
)

internal fun resolveLocalImageFinalNegativePrompt(
    userNegativePrompt: String?,
    modelDefaultNegativePrompt: String?
): LocalImageFinalNegativePrompt = when {
    userNegativePrompt != null -> LocalImageFinalNegativePrompt(
        value = userNegativePrompt,
        source = LocalImageNegativePromptSource.USER
    )
    modelDefaultNegativePrompt != null -> LocalImageFinalNegativePrompt(
        value = modelDefaultNegativePrompt,
        source = LocalImageNegativePromptSource.MODEL_DEFAULT
    )
    else -> LocalImageFinalNegativePrompt(
        value = "",
        source = LocalImageNegativePromptSource.EMPTY
    )
}

/**
 * Direct Chinese is admitted only by the profile's evidence-bound text-encoder declaration.
 * Family names, tokenizer UTF-8 transport, recommendation IDs, and device properties are never
 * semantic evidence. DIRECT_UTF8_PASSTHROUGH remains a parser-only legacy value and is never
 * selected for a new Chinese generation request.
 */
internal fun ImageExecutionProfile.textEncoderLanguageCapability(): LocalImageTextEncoderLanguageCapability =
    when (chinesePromptLanguageCapability()) {
        ImageTextEncoderLanguageCapability.NATIVE_MULTILINGUAL ->
            LocalImageTextEncoderLanguageCapability.NATIVE_MULTILINGUAL
        ImageTextEncoderLanguageCapability.ENGLISH_DOMINANT ->
            LocalImageTextEncoderLanguageCapability.ENGLISH_DOMINANT
    }

/**
 * The QNN standard-tokenizer path receives a Kotlin string as strict UTF-8 and uses that same
 * text to produce conditioning token ids. This is a runtime/topology predicate only: missing or
 * corrupt tokenizer assets still fail at the concrete native tokenizer boundary, while unknown
 * devices and unrecognized profile provenance never block this path.
 */
internal fun ImageExecutionProfile.hasNativeUtf8PromptPassThroughTopology(): Boolean =
    runtime == LocalImageRuntime.QNN_HTP &&
        tokenizer.backend == ImageTokenizerBackend.TOKENIZERS_CPP &&
        tokenizer.maxLength >= 2 &&
        tokenizer.bosId != null &&
        tokenizer.eosId != null &&
        tokenizer.padId != null

internal fun String.containsHanScript(): Boolean = codePoints().anyMatch { codePoint ->
    Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
}

/**
 * Native multilingual admission is deliberately narrower than arbitrary UTF-8 transport.
 * A verified encoder receives Chinese Han text, the CJK punctuation already supported by the
 * diffusion prompt grammar, and the same safe ASCII grammar used by English-only profiles.
 */
internal fun String.isSupportedNativeChineseHanDiffusionPrompt(): Boolean =
    codePoints().allMatch { codePoint ->
        isSafeAsciiDiffusionPromptCodePoint(codePoint) ||
            isNativeChineseHanCodePoint(codePoint) ||
            codePoint in SUPPORTED_CHINESE_DIFFUSION_PROMPT_PUNCTUATION
    }

/**
 * Keep the Kotlin/native contract identical rather than relying on platform Unicode tables.
 *
 * This deliberately includes actual CJK unified ideographs and extensions only. Radicals,
 * ideographic-description characters, and compatibility ideographs are not normal prompt text
 * and must not become an accidental Unicode bypass. Unicode cannot distinguish Simplified Chinese
 * from Traditional Chinese or Japanese kanji; the evidence-bound encoder declaration makes that
 * product capability decision.
 */
private fun isNativeChineseHanCodePoint(codePoint: Int): Boolean =
    codePoint == 0x3007 ||
        codePoint in 0x3400..0x4DBF ||
        codePoint in 0x4E00..0x9FFF ||
        codePoint in 0x20000..0x2A6DF ||
        codePoint in 0x2A700..0x2B73F ||
        codePoint in 0x2B740..0x2B81F ||
        codePoint in 0x2B820..0x2CEAF ||
        codePoint in 0x2CEB0..0x2EBEF ||
        codePoint in 0x2EBF0..0x2EE5D ||
        codePoint in 0x30000..0x3134A ||
        codePoint in 0x31350..0x323AF

/**
 * Call only after language admission. A non-ASCII prompt that passed admission must retain the
 * evidence-bound native multilingual method in execution history.
 */
internal fun String.requiresNativeMultilingualPromptExecution(): Boolean =
    !isSafeAsciiDiffusionPrompt()

internal fun requiresLocalImagePromptTranslation(
    profile: ImageExecutionProfile,
    prompt: String,
    negativePrompt: String?
): Boolean = profile.textEncoderLanguageCapability() ==
    LocalImageTextEncoderLanguageCapability.ENGLISH_DOMINANT &&
    (!prompt.isSafeAsciiDiffusionPrompt() ||
        negativePrompt?.isSafeAsciiDiffusionPrompt() == false)

/**
 * Product boundary for every local generation entry point. English-dominant encoders never get
 * residual Han text: users must select the dictionary's canonical ASCII tags first. This is kept
 * separate from the optional whole-sentence offline translator, and cannot fall back to a chat
 * model.
 */
internal fun requireLocalImagePromptLanguageAdmission(
    profile: ImageExecutionProfile,
    prompt: String,
    executedNegativePrompt: String
) {
    if (requiresLocalImagePromptTranslation(profile, prompt, executedNegativePrompt)) {
        throw LocalImageProductContractException(
            code = "image_prompt_requires_canonical_english_tags",
            message = "当前图片模型使用英文主导文本编码器。请先导入主标签词典和中文翻译词典，" +
                "用中文检索并点选候选，将英文规范标签插入正向或负向提示词后再生成；" +
                "整句中文自动翻译是独立能力，目前未配置已许可且可验证的离线翻译模型，" +
                "不会自动翻译。"
        )
    }
    if (profile.textEncoderLanguageCapability() ==
        LocalImageTextEncoderLanguageCapability.NATIVE_MULTILINGUAL &&
        (!prompt.isSupportedNativeChineseHanDiffusionPrompt() ||
            !executedNegativePrompt.isSupportedNativeChineseHanDiffusionPrompt())
    ) {
        throw LocalImageProductContractException(
            code = "image_prompt_unsupported_native_language",
            message = "The verified native multilingual text encoder accepts only Chinese Han text, " +
                "supported CJK prompt punctuation, and safe ASCII diffusion prompt syntax."
        )
    }
}

/**
 * Static profile diagnostic only. Request admission intentionally evaluates the resolved negative
 * prompt after CFG/default resolution, because an unused default must not block a generation.
 */
internal fun ImageExecutionProfile.hasCompatibleDefaultNegativePromptLanguage(): Boolean =
    if (!defaults.useCfg) {
        true
    } else {
        when (textEncoderLanguageCapability()) {
            LocalImageTextEncoderLanguageCapability.ENGLISH_DOMINANT ->
                defaults.defaultNegativePrompt?.isSafeAsciiDiffusionPrompt() != false
            LocalImageTextEncoderLanguageCapability.NATIVE_MULTILINGUAL ->
                defaults.defaultNegativePrompt?.isSupportedNativeChineseHanDiffusionPrompt() != false
        }
    }

internal fun LocalImageGenerationOptions.normalizedForPromptExecutionProfile(
    runtime: LocalImageRuntime
): LocalImageGenerationOptions {
    if (runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP || useCfg != null) return this
    val inferredUseCfg = cfgScale?.let { scale -> kotlin.math.abs(scale - 1.0) > 1e-12 }
    return if (inferredUseCfg == null) this else copy(useCfg = inferredUseCfg)
}

data class LocalImagePromptExecution(
    val originalPrompt: String,
    val effectivePrompt: String,
    val originalNegativePrompt: String?,
    val effectiveNegativePrompt: String,
    val negativePromptSource: LocalImageNegativePromptSource,
    val method: LocalImagePromptTransformationMethod,
    val translationContractVersion: Int? = if (
        method == LocalImagePromptTransformationMethod.LOCAL_LLM_ZH_TO_EN
    ) {
        CURRENT_LOCAL_IMAGE_PROMPT_TRANSLATION_CONTRACT_VERSION
    } else {
        null
    },
    val imageProfileBindingFingerprint: String,
    val promptLanguageBindingFingerprint: String,
    val translatorModelId: String? = null,
    val translatorModelName: String? = null,
    val translatorRuntime: String? = null,
    val translatorModelSha256: String? = null,
    val translationPlanSha256: String? = null,
    val verificationReceiptSha256: String? = null,
    val translationPhaseSystemPromptSha256: String? = null,
    val verificationPhaseSystemPromptSha256: String? = null,
    val translationProofFingerprint: String? = null
) {
    init {
        require(originalPrompt.isNotBlank() && originalPrompt.length <= MAX_ORIGINAL_PROMPT_CHARS)
        require(effectivePrompt.isNotBlank() && effectivePrompt.length <= MAX_EFFECTIVE_PROMPT_CHARS)
        require(originalNegativePrompt == null || originalNegativePrompt.length <= MAX_ORIGINAL_PROMPT_CHARS)
        require(effectiveNegativePrompt.length <= MAX_EFFECTIVE_PROMPT_CHARS)
        require(SHA256_PATTERN.matches(imageProfileBindingFingerprint))
        require(SHA256_PATTERN.matches(promptLanguageBindingFingerprint))
        when (negativePromptSource) {
            LocalImageNegativePromptSource.USER -> require(originalNegativePrompt != null)
            LocalImageNegativePromptSource.MODEL_DEFAULT -> require(originalNegativePrompt == null)
            LocalImageNegativePromptSource.EMPTY -> require(
                originalNegativePrompt == null && effectiveNegativePrompt.isEmpty()
            )
        }
        if (method == LocalImagePromptTransformationMethod.LOCAL_LLM_ZH_TO_EN) {
            require(originalPrompt.containsHanScript() || originalNegativePrompt?.containsHanScript() == true)
            require(translationContractVersion == CURRENT_LOCAL_IMAGE_PROMPT_TRANSLATION_CONTRACT_VERSION)
            require(!translatorModelId.isNullOrBlank())
            require(!translatorModelName.isNullOrBlank())
            require(!translatorRuntime.isNullOrBlank())
            require(translatorModelSha256?.let(SHA256_PATTERN::matches) == true)
            require(translationPlanSha256?.let(SHA256_PATTERN::matches) == true)
            require(verificationReceiptSha256?.let(SHA256_PATTERN::matches) == true)
            require(translationPhaseSystemPromptSha256?.let(SHA256_PATTERN::matches) == true)
            require(verificationPhaseSystemPromptSha256?.let(SHA256_PATTERN::matches) == true)
            require(translationProofFingerprint?.let(SHA256_PATTERN::matches) == true)
            require(effectiveNegativePrompt.isSafeAsciiDiffusionPrompt() &&
                !effectiveNegativePrompt.containsHanScript()
            )
            validateLocalImagePromptTranslationHardContractsV4(
                originalPrompt = originalPrompt,
                effectivePrompt = effectivePrompt,
                originalNegativePrompt = originalNegativePrompt,
                effectiveNegativePrompt = effectiveNegativePrompt.takeIf {
                    negativePromptSource == LocalImageNegativePromptSource.USER
                }
            )
            require(
                translationProofFingerprint == imagePromptTranslationProofFingerprint(
                    contractVersion = requireNotNull(translationContractVersion),
                    originalPrompt = originalPrompt,
                    effectivePrompt = effectivePrompt,
                    originalNegativePrompt = originalNegativePrompt,
                    effectiveNegativePrompt = effectiveNegativePrompt,
                    negativePromptSource = negativePromptSource.name,
                    translationPlanSha256 = requireNotNull(translationPlanSha256),
                    verificationReceiptSha256 = requireNotNull(verificationReceiptSha256),
                    translationPhaseSystemPromptSha256 =
                        requireNotNull(translationPhaseSystemPromptSha256),
                    verificationPhaseSystemPromptSha256 =
                        requireNotNull(verificationPhaseSystemPromptSha256),
                    translatorRuntime = requireNotNull(translatorRuntime),
                    translatorModelSha256 = requireNotNull(translatorModelSha256),
                    promptLanguageBindingFingerprint = promptLanguageBindingFingerprint
                )
            ) { "Translated prompt proof fingerprint does not match its bound evidence." }
        } else {
            require(translationContractVersion == null)
            require(effectivePrompt == originalPrompt)
            if (negativePromptSource == LocalImageNegativePromptSource.USER) {
                require(effectiveNegativePrompt == originalNegativePrompt)
            }
            require(translatorModelId == null)
            require(translatorModelName == null)
            require(translatorRuntime == null)
            require(translatorModelSha256 == null)
            require(translationPlanSha256 == null)
            require(verificationReceiptSha256 == null)
            require(translationPhaseSystemPromptSha256 == null)
            require(verificationPhaseSystemPromptSha256 == null)
            require(translationProofFingerprint == null)
        }
        when (method) {
            LocalImagePromptTransformationMethod.NATIVE_MULTILINGUAL -> require(
                effectivePrompt.isSupportedNativeChineseHanDiffusionPrompt() &&
                    effectiveNegativePrompt.isSupportedNativeChineseHanDiffusionPrompt() &&
                    (effectivePrompt.requiresNativeMultilingualPromptExecution() ||
                        effectiveNegativePrompt.requiresNativeMultilingualPromptExecution())
            )
            LocalImagePromptTransformationMethod.DIRECT -> require(
                effectivePrompt.isSafeAsciiDiffusionPrompt() &&
                    effectiveNegativePrompt.isSafeAsciiDiffusionPrompt()
            )
            else -> Unit
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("originalPrompt", originalPrompt)
        .put("effectivePrompt", effectivePrompt)
        .put("originalNegativePrompt", originalNegativePrompt ?: JSONObject.NULL)
        .put("effectiveNegativePrompt", effectiveNegativePrompt)
        .put("negativePromptSource", negativePromptSource.name)
        .put("method", method.name)
        .put("imageProfileBindingFingerprint", imageProfileBindingFingerprint)
        .put("promptLanguageBindingFingerprint", promptLanguageBindingFingerprint)
        .apply {
            translationContractVersion?.let { put("translationContractVersion", it) }
            translatorModelId?.let { put("translatorModelId", it) }
            translatorModelName?.let { put("translatorModelName", it) }
            translatorRuntime?.let { put("translatorRuntime", it) }
            translatorModelSha256?.let { put("translatorModelSha256", it) }
            translationPlanSha256?.let { put("translationPlanSha256", it) }
            verificationReceiptSha256?.let { put("verificationReceiptSha256", it) }
            translationPhaseSystemPromptSha256?.let {
                put("translationPhaseSystemPromptSha256", it)
            }
            verificationPhaseSystemPromptSha256?.let {
                put("verificationPhaseSystemPromptSha256", it)
            }
            translationProofFingerprint?.let { put("translationProofFingerprint", it) }
        }

    companion object {
        private const val VERSION = 4
        private const val EXPLICIT_NEGATIVE_STATE_VERSION = 3
        private const val LEGACY_VERSION = 1
        internal const val MAX_ORIGINAL_PROMPT_CHARS = 16_384
        internal const val MAX_EFFECTIVE_PROMPT_CHARS = 4_096
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

        fun fromJsonOrNull(json: JSONObject?): LocalImagePromptExecution? = runCatching {
            json ?: return null
            val version = json.getInt("version")
            require(version in LEGACY_VERSION..VERSION)
            fun nullableString(field: String): String? =
                if (!json.has(field) || json.isNull(field)) null else json.getString(field)
            val method = LocalImagePromptTransformationMethod.valueOf(json.getString("method"))
            if (method == LocalImagePromptTransformationMethod.LOCAL_LLM_ZH_TO_EN ||
                method == LocalImagePromptTransformationMethod.DIRECT_UTF8_PASSTHROUGH
            ) {
                require(version == VERSION) {
                    "Legacy prompt evidence cannot bypass the current execution contract."
                }
            }
            val originalNegativePrompt = nullableString("originalNegativePrompt")
            val legacyEffectiveNegativePrompt = nullableString("effectiveNegativePrompt")
            val negativePromptSource = if (version >= EXPLICIT_NEGATIVE_STATE_VERSION) {
                LocalImageNegativePromptSource.valueOf(json.getString("negativePromptSource"))
            } else if (originalNegativePrompt != null) {
                LocalImageNegativePromptSource.USER
            } else {
                LocalImageNegativePromptSource.EMPTY
            }
            val effectiveNegativePrompt = if (version >= EXPLICIT_NEGATIVE_STATE_VERSION) {
                json.getString("effectiveNegativePrompt")
            } else {
                legacyEffectiveNegativePrompt.orEmpty()
            }
            val imageProfileBindingFingerprint = json.getString("imageProfileBindingFingerprint")
            LocalImagePromptExecution(
                originalPrompt = json.getString("originalPrompt"),
                effectivePrompt = json.getString("effectivePrompt"),
                originalNegativePrompt = originalNegativePrompt,
                effectiveNegativePrompt = effectiveNegativePrompt,
                negativePromptSource = negativePromptSource,
                method = method,
                translationContractVersion = if (json.has("translationContractVersion")) {
                    json.getInt("translationContractVersion")
                } else {
                    null
                },
                imageProfileBindingFingerprint = imageProfileBindingFingerprint,
                promptLanguageBindingFingerprint = if (version >= EXPLICIT_NEGATIVE_STATE_VERSION) {
                    json.getString("promptLanguageBindingFingerprint")
                } else {
                    imageProfileBindingFingerprint
                },
                translatorModelId = nullableString("translatorModelId"),
                translatorModelName = nullableString("translatorModelName"),
                translatorRuntime = nullableString("translatorRuntime"),
                translatorModelSha256 = nullableString("translatorModelSha256"),
                translationPlanSha256 = nullableString("translationPlanSha256"),
                verificationReceiptSha256 = nullableString("verificationReceiptSha256"),
                translationPhaseSystemPromptSha256 =
                    nullableString("translationPhaseSystemPromptSha256"),
                verificationPhaseSystemPromptSha256 =
                    nullableString("verificationPhaseSystemPromptSha256"),
                translationProofFingerprint = nullableString("translationProofFingerprint")
            )
        }.getOrNull()
    }
}

internal fun LocalImagePromptExecution.rebindToCurrentImageProfile(
    finalNegativePrompt: LocalImageFinalNegativePrompt,
    imageProfileBindingFingerprint: String,
    promptLanguageBindingFingerprint: String
): LocalImagePromptExecution {
    val reboundNegativePrompt = if (negativePromptSource == LocalImageNegativePromptSource.USER) {
        require(finalNegativePrompt.source == LocalImageNegativePromptSource.USER &&
            originalNegativePrompt != null
        ) { "Captured user negative prompt no longer matches the request." }
        LocalImageFinalNegativePrompt(
            value = effectiveNegativePrompt,
            source = LocalImageNegativePromptSource.USER
        )
    } else {
        require(originalNegativePrompt == null &&
            finalNegativePrompt.source != LocalImageNegativePromptSource.USER
        ) { "Captured model negative prompt no longer matches the request." }
        finalNegativePrompt
    }
    val reboundProof = if (method == LocalImagePromptTransformationMethod.LOCAL_LLM_ZH_TO_EN) {
        imagePromptTranslationProofFingerprint(
            contractVersion = requireNotNull(translationContractVersion),
            originalPrompt = originalPrompt,
            effectivePrompt = effectivePrompt,
            originalNegativePrompt = originalNegativePrompt,
            effectiveNegativePrompt = reboundNegativePrompt.value,
            negativePromptSource = reboundNegativePrompt.source.name,
            translationPlanSha256 = requireNotNull(translationPlanSha256),
            verificationReceiptSha256 = requireNotNull(verificationReceiptSha256),
            translationPhaseSystemPromptSha256 =
                requireNotNull(translationPhaseSystemPromptSha256),
            verificationPhaseSystemPromptSha256 =
                requireNotNull(verificationPhaseSystemPromptSha256),
            translatorRuntime = requireNotNull(translatorRuntime),
            translatorModelSha256 = requireNotNull(translatorModelSha256),
            promptLanguageBindingFingerprint = promptLanguageBindingFingerprint
        )
    } else {
        null
    }
    return copy(
        effectiveNegativePrompt = reboundNegativePrompt.value,
        negativePromptSource = reboundNegativePrompt.source,
        imageProfileBindingFingerprint = imageProfileBindingFingerprint,
        promptLanguageBindingFingerprint = promptLanguageBindingFingerprint,
        translationProofFingerprint = reboundProof
    )
}

internal fun LocalImagePromptExecution.bindToEffectiveExecutionProfile(
    profile: ImageExecutionProfile
): LocalImagePromptExecution {
    require(promptLanguageBindingFingerprint == profile.promptLanguageBindingFingerprint) {
        "Effective image profile changed the prepared prompt language topology."
    }
    return copy(
        imageProfileBindingFingerprint = profile.bindingFingerprint,
        promptLanguageBindingFingerprint = profile.promptLanguageBindingFingerprint
    )
}

internal fun validateLocalImagePromptExecutionBinding(
    promptExecution: LocalImagePromptExecution,
    expectedProfile: ImageExecutionProfile,
    executionMetadataJson: String
) {
    require(promptExecution.imageProfileBindingFingerprint == expectedProfile.bindingFingerprint)
    require(
        promptExecution.promptLanguageBindingFingerprint ==
            expectedProfile.promptLanguageBindingFingerprint
    )
    val execution = JSONObject(executionMetadataJson)
    fun requiredString(field: String): String {
        require(execution.has(field) && !execution.isNull(field) && execution.get(field) is String) {
            "Image execution metadata must contain string field $field."
        }
        return execution.getString(field)
    }
    val expectedCapability = expectedProfile.textEncoderLanguageCapability()
    require(requiredString("imageProfileBindingFingerprint") ==
        promptExecution.imageProfileBindingFingerprint
    )
    require(requiredString("promptLanguageBindingFingerprint") ==
        promptExecution.promptLanguageBindingFingerprint
    )
    require(requiredString("textEncoderLanguageCapability") == expectedCapability.name)
    require(
        requiredString("promptExecutionSha256") == imagePromptExecutionSha256(
            promptExecution.effectivePrompt,
            promptExecution.effectiveNegativePrompt
        )
    )
    val expectedNativePromptSha256 = imagePromptExecutionSha256(
        promptExecution.effectivePrompt,
        promptExecution.effectiveNegativePrompt
    )
    val nativeEffective = execution.optJSONObject("nativeEffective")
        ?: error("Image execution metadata must contain nativeEffective prompt evidence.")
    expectedProfile.verifiedNativeSimplifiedChineseLanguageProofSha256()?.let { expectedProof ->
        require(requiredString("languageProofSha256") == expectedProof) {
            "Image execution metadata lost the verified native text-encoder semantic proof."
        }
        val nativeProof = nativeEffective.opt("languageProofSha256") as? String
            ?: error("Native effective prompt evidence is missing languageProofSha256.")
        require(nativeProof == expectedProof) {
            "Native effective prompt evidence differs from the verified text-encoder semantic proof."
        }
    }
    fun requireConsumedPromptEvidence(source: JSONObject, layer: String) {
        fun strictLayerString(field: String): String {
            require(source.has(field) && !source.isNull(field) && source.get(field) is String) {
                "Image $layer execution metadata must contain string field $field."
            }
            return source.getString(field)
        }
        require(strictLayerString("nativePromptExecutionSha256") == expectedNativePromptSha256)
        require(strictLayerString("nativePromptBindingStage") == "conditioning_consumed")
    }
    requireConsumedPromptEvidence(execution, "outer")
    requireConsumedPromptEvidence(nativeEffective, "nativeEffective")
    require(
        execution.getString("nativePromptExecutionSha256") ==
            nativeEffective.getString("nativePromptExecutionSha256") &&
            execution.getString("nativePromptBindingStage") ==
            nativeEffective.getString("nativePromptBindingStage")
    )
    when (promptExecution.method) {
        LocalImagePromptTransformationMethod.LOCAL_LLM_ZH_TO_EN,
        LocalImagePromptTransformationMethod.DIRECT_UTF8_PASSTHROUGH -> error(
            "Legacy prompt translation and UTF-8 pass-through evidence cannot validate a current image execution."
        )
        LocalImagePromptTransformationMethod.NATIVE_MULTILINGUAL -> require(
            expectedCapability == LocalImageTextEncoderLanguageCapability.NATIVE_MULTILINGUAL &&
                promptExecution.effectivePrompt
                    .isSupportedNativeChineseHanDiffusionPrompt() &&
                promptExecution.effectiveNegativePrompt
                    .isSupportedNativeChineseHanDiffusionPrompt() &&
                (promptExecution.effectivePrompt.requiresNativeMultilingualPromptExecution() ||
                    promptExecution.effectiveNegativePrompt
                        .requiresNativeMultilingualPromptExecution())
        )
        LocalImagePromptTransformationMethod.DIRECT -> require(
            promptExecution.effectivePrompt.isSafeAsciiDiffusionPrompt() &&
                promptExecution.effectiveNegativePrompt.isSafeAsciiDiffusionPrompt()
        )
    }
}

internal data class LocalImagePromptTranslation(
    val prompt: String,
    val negativePrompt: String?
)

internal fun parseLocalImagePromptTranslation(
    raw: String,
    originalPrompt: String,
    originalNegativePrompt: String?
): LocalImagePromptTranslation {
    val normalized = raw.trim().removeSingleJsonFence()
    require(normalized.length <= LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS * 2) {
        "本地翻译模型返回内容过长。"
    }
    val root = JSONObject(normalized)
    require(
        root.keys().asSequence().toSet() == setOf(
            "prompt",
            "negative_prompt",
            "contract_canary_prompt",
            "contract_canary_negative_prompt"
        )
    ) {
        "本地翻译模型没有返回严格的提示词 JSON。"
    }
    val rawTranslatedPrompt = root.getString("prompt").trim()
    val rawTranslatedNegative = if (root.isNull("negative_prompt")) {
        null
    } else {
        root.getString("negative_prompt").trim()
    }
    val rawTranslatedCanaryPrompt = root.getString("contract_canary_prompt").trim()
    val rawTranslatedCanaryNegativePrompt =
        root.getString("contract_canary_negative_prompt").trim()
    val translatedPrompt = normalizeControlledPositiveTranslation(
        source = originalPrompt,
        translated = rawTranslatedPrompt
    )
    val translatedNegative = rawTranslatedNegative?.let { translated ->
        normalizeControlledNegativeTranslation(
            source = originalNegativePrompt.orEmpty(),
            translated = translated
        )
    }
    val translatedCanaryPrompt = normalizeControlledPositiveTranslation(
        source = LOCAL_IMAGE_PROMPT_TRANSLATION_CANARY_PROMPT,
        translated = rawTranslatedCanaryPrompt
    )
    val translatedCanaryNegativePrompt = normalizeControlledNegativeTranslation(
        source = LOCAL_IMAGE_PROMPT_TRANSLATION_CANARY_NEGATIVE_PROMPT,
        translated = rawTranslatedCanaryNegativePrompt
    )
    require(translatedPrompt.isNotBlank() &&
        translatedPrompt.length <= LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS
    ) { "本地翻译模型返回的正向提示词无效。" }
    require(translatedNegative == null ||
        translatedNegative.length <= LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS
    ) { "本地翻译模型返回的负向提示词无效。" }
    require(translatedCanaryPrompt.isNotBlank() &&
        translatedCanaryPrompt.length <= LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS &&
        translatedCanaryNegativePrompt.isNotBlank() &&
        translatedCanaryNegativePrompt.length <= LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS
    ) { "本地翻译模型没有完成语义能力校验。" }
    validateLocalImagePromptTranslationSemantics(
        originalPrompt = LOCAL_IMAGE_PROMPT_TRANSLATION_CANARY_PROMPT,
        effectivePrompt = translatedCanaryPrompt,
        originalNegativePrompt = LOCAL_IMAGE_PROMPT_TRANSLATION_CANARY_NEGATIVE_PROMPT,
        effectiveNegativePrompt = translatedCanaryNegativePrompt
    )
    validateLocalImagePromptTranslationSemantics(
        originalPrompt = originalPrompt,
        effectivePrompt = translatedPrompt,
        originalNegativePrompt = originalNegativePrompt,
        effectiveNegativePrompt = translatedNegative
    )
    return LocalImagePromptTranslation(translatedPrompt, translatedNegative)
}

/**
 * Small local translators often render an explicit Chinese singular classifier as an English
 * indefinite article. Diffusion conditioning is more reliable when the requested quantity stays
 * explicit, so canonicalize only a uniquely bound `a/an + attributes + entity` occurrence. A
 * repeated target entity in the same clause is deliberately ambiguous and remains fail-closed in
 * the semantic validator below.
 */
private fun normalizeControlledPositiveTranslation(source: String, translated: String): String {
    if (!source.containsHanScript()) return translated
    val sourceFragments = source.protectedPromptFragments()
    val translatedFragments = translated.protectedPromptFragments()
    val nestedNormalized = if (sourceFragments.size == translatedFragments.size) {
        sourceFragments.zip(translatedFragments)
            .sortedByDescending { (_, translatedFragment) -> translatedFragment.range.first }
            .fold(translated) { result, (sourceFragment, translatedFragment) ->
                if (!sourceFragment.value.containsHanScript()) return@fold result
                val sourceInner = sourceFragment.innerPromptStructureValueOrNull()
                    ?: return@fold result
                val translatedInner = translatedFragment.innerPromptStructureValueOrNull()
                    ?: return@fold result
                val normalizedInner = normalizeControlledPositiveTranslation(
                    source = sourceInner,
                    translated = translatedInner
                )
                if (normalizedInner == translatedInner) {
                    result
                } else {
                    result.replaceRange(
                        translatedFragment.range.first + 1,
                        translatedFragment.range.last,
                        normalizedInner
                    )
                }
            }
    } else {
        translated
    }
    return normalizeControlledPositiveTranslationTopLevel(source, nestedNormalized)
}

private fun normalizeControlledPositiveTranslationTopLevel(
    source: String,
    translated: String
): String {
    if (!source.containsHanScript()) return translated
    val sourceClauses = source.promptClauses()
    val translatedClauses = translated.promptClauseSpans()
    if (sourceClauses.size != translatedClauses.size) return translated
    val singularAnchor = CANONICAL_TRANSLATION_ANCHORS.single { it.label == "一个" }
    val replacements = mutableSetOf<IntRange>()

    sourceClauses.zip(translatedClauses).forEach { (sourceClause, translatedClause) ->
        val sourceEntities = canonicalSourceOccurrences(sourceClause) { it.entity }
            .distinctBy { it.anchor.label to it.range }
        if (sourceEntities.isEmpty()) return@forEach
        val translatedTokens = translatedClause.value
            .maskProtectedPromptStructures()
            .englishPromptTokens()
        canonicalSourceOccurrences(sourceClause) { it == singularAnchor }
            .distinctBy(CanonicalSourceOccurrence::range)
            .forEach quantity@{ quantityOccurrence ->
                val nearestDistance = sourceEntities.minOf { entity ->
                    sourceOccurrenceDistance(quantityOccurrence, entity)
                }
                val nearestEntities = sourceEntities.filter { entity ->
                    sourceOccurrenceDistance(quantityOccurrence, entity) == nearestDistance
                }
                if (nearestEntities.size != 1) return@quantity
                val boundEntity = nearestEntities.single().anchor
                val translatedEntityIndexes = translatedTokens.indices.filter { index ->
                    translatedTokens[index].matchesSingleWordTarget(boundEntity)
                }
                if (translatedEntityIndexes.size != 1) return@quantity
                val entityIndex = translatedEntityIndexes.single()
                val explicitQuantities = translatedTokens.precedingQuantityIndexes(
                    entityIndex = entityIndex,
                    accepted = singularAnchor.targets
                )
                if (explicitQuantities.size == 1) return@quantity
                if (explicitQuantities.isNotEmpty()) return@quantity
                val articles = translatedTokens.precedingQuantityIndexes(
                    entityIndex = entityIndex,
                    accepted = setOf("a", "an")
                )
                if (articles.size != 1) return@quantity
                val articleRange = translatedTokens[articles.single()].range
                val globalRange =
                    (translatedClause.range.first + articleRange.first)..
                        (translatedClause.range.first + articleRange.last)
                if (translated.isInsideProtectedPromptStructure(globalRange)) return@quantity
                if (replacements.none { it == globalRange }) replacements += globalRange
            }
    }

    return replacements.sortedByDescending(IntRange::first).fold(translated) { result, range ->
        result.replaceRange(range.first, range.last + 1, "one")
    }
}

/**
 * A diffusion negative prompt is a concept list, not a natural-language prohibition. Remove one
 * tightly controlled negation prefix only when the matching Chinese source clause explicitly uses
 * a negative operator. The complete semantic validator still runs afterwards and rejects extra,
 * missing, reordered, or ambiguous concepts.
 */
private fun normalizeControlledNegativeTranslation(source: String, translated: String): String {
    if (!source.containsHanScript()) return translated
    val sourceClauses = source.promptClauses()
    val translatedClauses = translated.promptClauseSpans()
    if (sourceClauses.size != translatedClauses.size) return translated
    val replacements = mutableListOf<Pair<IntRange, String>>()

    sourceClauses.zip(translatedClauses).forEach { (sourceClause, translatedClause) ->
        if (CANONICAL_NEGATION_ANCHOR.sources.none(sourceClause::contains)) return@forEach
        val prefix = CONTROLLED_NEGATIVE_CONDITIONING_PREFIX.find(translatedClause.value)
            ?: return@forEach
        val normalizedClause = translatedClause.value
            .substring(prefix.range.last + 1)
            .trimStart()
        if (normalizedClause.isEmpty()) return@forEach
        replacements += translatedClause.range to normalizedClause
    }

    return replacements.sortedByDescending { it.first.first }.fold(translated) { result, item ->
        val (range, replacement) = item
        result.replaceRange(range.first, range.last + 1, replacement)
    }
}

internal fun normalizeLocalImagePromptTranslationClauseV4(
    source: String,
    translated: String,
    negativeConditioning: Boolean
): String = if (negativeConditioning) {
    normalizeControlledNegativeTranslation(source, translated)
} else {
    normalizeControlledPositiveTranslation(source, translated)
}

private data class PromptClauseSpan(
    val value: String,
    val range: IntRange
)

internal class LocalImagePromptClauseLayoutV4 private constructor(
    private val source: String,
    val clauses: List<String>,
    private val clauseRanges: List<IntRange>
) {
    init {
        require(clauses.size == clauseRanges.size)
    }

    fun render(translatedClauses: List<String>): String {
        require(translatedClauses.size == clauses.size) {
            "Translated prompt clause count does not match the source layout."
        }
        if (clauseRanges.isEmpty()) return canonicalizePromptLayoutSliceV4(source)
        return buildString(source.length) {
            var cursor = 0
            clauseRanges.forEachIndexed { index, range ->
                append(canonicalizePromptLayoutSliceV4(source.substring(cursor, range.first)))
                append(translatedClauses[index])
                cursor = range.last + 1
            }
            append(canonicalizePromptLayoutSliceV4(source.substring(cursor)))
        }
    }

    companion object {
        fun parse(source: String): LocalImagePromptClauseLayoutV4 {
            val spans = source.promptClauseSpans()
            return LocalImagePromptClauseLayoutV4(
                source = source,
                clauses = spans.map(PromptClauseSpan::value),
                clauseRanges = spans.map(PromptClauseSpan::range)
            )
        }
    }
}

internal fun localImagePromptClauseLayoutV4(source: String): LocalImagePromptClauseLayoutV4 =
    LocalImagePromptClauseLayoutV4.parse(source)

private fun canonicalizePromptLayoutSliceV4(value: String): String = buildString(value.length) {
    value.forEach { character ->
        append(
            when (character) {
                '，' -> ','
                '；' -> ';'
                '。' -> '.'
                '！' -> '!'
                '？' -> '?'
                else -> character
            }
        )
    }
}

private enum class PromptSeparatorKind {
    COMMA,
    SEMICOLON,
    PERIOD,
    EXCLAMATION,
    QUESTION,
    LINE_FEED,
    CARRIAGE_RETURN
}

private data class EnglishPromptToken(
    val value: String,
    val range: IntRange
)

private fun String.promptClauses(): List<String> = promptClauseSpans().map(PromptClauseSpan::value)

private fun String.promptClauseSpans(): List<PromptClauseSpan> {
    val spans = mutableListOf<PromptClauseSpan>()
    var clauseStart = 0
    val delimiterStack = mutableListOf<Char>()
    fun appendClause(endExclusive: Int) {
        var contentStart = clauseStart
        var contentEnd = endExclusive
        while (contentStart < contentEnd && this[contentStart].isWhitespace()) contentStart += 1
        while (contentEnd > contentStart && this[contentEnd - 1].isWhitespace()) contentEnd -= 1
        if (contentStart < contentEnd) {
            spans += PromptClauseSpan(
                value = substring(contentStart, contentEnd),
                range = contentStart until contentEnd
            )
        }
    }
    forEachIndexed { index, character ->
        val expectedClose = PROMPT_STRUCTURE_OPEN_TO_CLOSE[character]
        when {
            isEscapedAt(index) -> Unit
            expectedClose != null -> delimiterStack += expectedClose
            delimiterStack.lastOrNull() == character -> delimiterStack.removeAt(delimiterStack.lastIndex)
            delimiterStack.isEmpty() && character.isPromptClauseSeparatorAt(this, index) -> {
                appendClause(index)
                clauseStart = index + 1
            }
        }
    }
    appendClause(length)
    return spans
}

private fun Char.isPromptClauseSeparatorAt(source: String, index: Int): Boolean {
    if (this !in PROMPT_CLAUSE_SEPARATOR_CHARACTERS) return false
    if (source.isEscapedAt(index)) return false
    if (this == '.' && index > 0 && index + 1 < source.length &&
        source[index - 1].isDigit() && source[index + 1].isDigit()
    ) return false
    return true
}

private fun Char.promptSeparatorKindOrNull(): PromptSeparatorKind? = when (this) {
    ',', '，' -> PromptSeparatorKind.COMMA
    ';', '；' -> PromptSeparatorKind.SEMICOLON
    '.', '。' -> PromptSeparatorKind.PERIOD
    '!', '！' -> PromptSeparatorKind.EXCLAMATION
    '?', '？' -> PromptSeparatorKind.QUESTION
    '\n' -> PromptSeparatorKind.LINE_FEED
    '\r' -> PromptSeparatorKind.CARRIAGE_RETURN
    else -> null
}

/**
 * Keep the punctuation program stable while still allowing the translator to replace Chinese
 * punctuation with its ASCII counterpart. Decimal points and escaped punctuation are literals,
 * not clause boundaries. The all-depth signature also covers intentionally unclosed attention
 * syntax that Local Dream accepts as literal prompt text.
 */
private fun String.promptSeparatorSignature(topLevelOnly: Boolean): List<PromptSeparatorKind> {
    val signature = mutableListOf<PromptSeparatorKind>()
    val delimiterStack = mutableListOf<Char>()
    forEachIndexed { index, character ->
        if (isEscapedAt(index)) return@forEachIndexed
        val expectedClose = PROMPT_STRUCTURE_OPEN_TO_CLOSE[character]
        when {
            expectedClose != null -> delimiterStack += expectedClose
            delimiterStack.lastOrNull() == character ->
                delimiterStack.removeAt(delimiterStack.lastIndex)
            !topLevelOnly || delimiterStack.isEmpty() -> {
                if (character.isPromptClauseSeparatorAt(this, index)) {
                    character.promptSeparatorKindOrNull()?.let(signature::add)
                }
            }
        }
    }
    return signature
}

private fun String.promptStructureEscapeSignature(): List<String> = buildList {
    this@promptStructureEscapeSignature.forEachIndexed { index, character ->
        if (character in PROMPT_STRUCTURE_CHARACTERS) {
            val canonical = character.canonicalPromptStructureCharacter()
            add(if (isEscapedAt(index)) "escaped:$canonical" else "syntax:$canonical")
        }
    }
}

private fun Char.canonicalPromptStructureCharacter(): Char = when (this) {
    '（' -> '('
    '）' -> ')'
    '［' -> '['
    '］' -> ']'
    '｛' -> '{'
    '｝' -> '}'
    '＜' -> '<'
    '＞' -> '>'
    else -> this
}

private fun String.isEscapedAt(index: Int): Boolean {
    var backslashCount = 0
    var cursor = index - 1
    while (cursor >= 0 && this[cursor] == '\\') {
        backslashCount += 1
        cursor -= 1
    }
    return backslashCount % 2 == 1
}

private fun String.englishPromptTokens(): List<EnglishPromptToken> =
    ENGLISH_PROMPT_TOKEN.findAll(lowercase(Locale.ROOT))
        .map { match -> EnglishPromptToken(match.value, match.range) }
        .toList()

private fun EnglishPromptToken.matchesSingleWordTarget(
    anchor: CanonicalTranslationAnchor
): Boolean = anchor.targets.any { target ->
    target.trim().equals(value, ignoreCase = true) &&
        ENGLISH_PROMPT_TOKEN.findAll(target).count() == 1
}

private fun List<EnglishPromptToken>.precedingQuantityIndexes(
    entityIndex: Int,
    accepted: Set<String>
): List<Int> {
    val normalizedAccepted = accepted.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
    return (maxOf(0, entityIndex - MAX_CONTROLLED_ENTITY_PHRASE_TOKENS) until entityIndex)
        .filter { quantityIndex ->
        this[quantityIndex].value in normalizedAccepted &&
            subList(quantityIndex + 1, entityIndex).all { token ->
                token.value in CANONICAL_ATTRIBUTE_TARGET_WORDS
            }
        }
}

private data class PromptProtectedFragment(
    val value: String,
    val range: IntRange
)

private fun String.asciiLiteralFragmentsOutsideProtectedStructures(): List<PromptProtectedFragment> {
    val protected = protectedPromptFragments()
    return ASCII_LITERAL_RUN.findAll(this).mapNotNull { match ->
        var start = match.range.first
        var end = match.range.last
        while (start <= end && this[start].isWhitespace()) start += 1
        while (end >= start && this[end].isWhitespace()) end -= 1
        if (start > end) return@mapNotNull null
        val range = start..end
        if (protected.any { it.range.overlaps(range) }) return@mapNotNull null
        PromptProtectedFragment(substring(start, end + 1), range)
    }.toList()
}

private fun String.protectedPromptFragments(): List<PromptProtectedFragment> {
    val balanced = mutableListOf<PromptProtectedFragment>()
    val delimiterStack = mutableListOf<Char>()
    var outerStart = -1
    forEachIndexed { index, character ->
        val expectedClose = PROMPT_STRUCTURE_OPEN_TO_CLOSE[character]
        when {
            isEscapedAt(index) -> Unit
            expectedClose != null -> {
                if (delimiterStack.isEmpty()) outerStart = index
                delimiterStack += expectedClose
            }
            delimiterStack.lastOrNull() == character -> {
                delimiterStack.removeAt(delimiterStack.lastIndex)
                if (delimiterStack.isEmpty() && outerStart >= 0) {
                    balanced += PromptProtectedFragment(
                        value = substring(outerStart, index + 1),
                        range = outerStart..index
                    )
                    outerStart = -1
                }
            }
        }
    }
    val bareDirectives = BARE_PROMPT_DIRECTIVE.findAll(this)
        .filter { match -> balanced.none { fragment -> match.range.overlaps(fragment.range) } }
        .map { match -> PromptProtectedFragment(match.value, match.range) }
        .toList()
    return (balanced + bareDirectives).sortedBy { it.range.first }
}

private data class OpenPromptStructure(
    val start: Int,
    val expectedClose: Char
)

/** Every balanced scope, including nested attention structures, in deterministic pre-order. */
private fun String.balancedPromptStructureFragments(): List<PromptProtectedFragment> {
    val stack = mutableListOf<OpenPromptStructure>()
    val fragments = mutableListOf<PromptProtectedFragment>()
    forEachIndexed { index, character ->
        if (isEscapedAt(index)) return@forEachIndexed
        val expectedClose = PROMPT_STRUCTURE_OPEN_TO_CLOSE[character]
        when {
            expectedClose != null -> stack += OpenPromptStructure(index, expectedClose)
            stack.lastOrNull()?.expectedClose == character -> {
                val open = stack.removeAt(stack.lastIndex)
                fragments += PromptProtectedFragment(
                    value = substring(open.start, index + 1),
                    range = open.start..index
                )
            }
        }
    }
    return fragments.sortedWith(
        compareBy<PromptProtectedFragment> { it.range.first }
            .thenByDescending { it.range.last }
    )
}

private fun PromptProtectedFragment.innerPromptStructureValueOrNull(): String? {
    if (value.length < 2) return null
    val expectedClose = PROMPT_STRUCTURE_OPEN_TO_CLOSE[value.first()] ?: return null
    if (value.last() != expectedClose) return null
    return value.substring(1, value.lastIndex)
}

private fun String.hasBalancedPromptStructures(): Boolean {
    val delimiterStack = mutableListOf<Char>()
    forEachIndexed { index, character ->
        if (isEscapedAt(index)) return@forEachIndexed
        val expectedClose = PROMPT_STRUCTURE_OPEN_TO_CLOSE[character]
        when {
            expectedClose != null -> delimiterStack += expectedClose
            character in PROMPT_STRUCTURE_OPEN_TO_CLOSE.values -> {
                if (delimiterStack.lastOrNull() != character) return false
                delimiterStack.removeAt(delimiterStack.lastIndex)
            }
        }
    }
    return delimiterStack.isEmpty()
}

private fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last

private fun String.isInsideProtectedPromptStructure(range: IntRange): Boolean =
    protectedPromptFragments().any { it.range.overlaps(range) }

private fun String.maskProtectedPromptStructures(): String {
    val masked = toCharArray()
    protectedPromptFragments().forEach { fragment ->
        fragment.range.forEach { index -> if (index in masked.indices) masked[index] = ' ' }
    }
    return String(masked)
}

private fun String.maskAsciiOnlyProtectedPromptStructures(): String {
    val masked = toCharArray()
    protectedPromptFragments().filterNot { it.value.containsHanScript() }.forEach { fragment ->
        fragment.range.forEach { index -> if (index in masked.indices) masked[index] = ' ' }
    }
    return String(masked)
}

private fun String.maskLiteralProtectedFragmentsDeclaredBy(source: String): String {
    val sourceFragments = source.protectedPromptFragments()
    val targetFragments = protectedPromptFragments()
    if (sourceFragments.size != targetFragments.size) return this
    val masked = toCharArray()
    sourceFragments.zip(targetFragments).forEach { (sourceFragment, targetFragment) ->
        if (!sourceFragment.value.containsHanScript()) {
            targetFragment.range.forEach { index -> if (index in masked.indices) masked[index] = ' ' }
        }
    }
    return String(masked)
}

/**
 * Relation operators inside attention syntax must not satisfy an outer spatial relation, but an
 * entity wrapped by attention is still the same relation endpoint. Positive negation is also an
 * executable operator when it is intentionally weighted. Restore only those canonical tokens at
 * their original offsets after masking the protected fragment.
 */
private fun String.maskProtectedPromptStructuresPreservingEntities(
    source: String? = null
): String {
    val masked = maskProtectedPromptStructures().toCharArray()
    val lower = lowercase(Locale.ROOT)
    val sourceFragments = source?.protectedPromptFragments()
    protectedPromptFragments().forEachIndexed { fragmentIndex, fragment ->
        if (sourceFragments != null &&
            sourceFragments.getOrNull(fragmentIndex)?.value?.containsHanScript() != true
        ) return@forEachIndexed
        CANONICAL_TRANSLATION_ANCHORS.asSequence()
            .filter { it.entity || it.negativeOperator }
            .flatMap { anchor -> anchor.targets.asSequence() }
            .forEach { target ->
                val pattern = Regex(
                    "(?<![a-z0-9_.])${Regex.escape(target.lowercase(Locale.ROOT))}" +
                        "(?![a-z0-9_.])",
                    RegexOption.IGNORE_CASE
                )
                pattern.findAll(lower, fragment.range.first).forEach { match ->
                    if (!fragment.range.contains(match.range)) return@forEach
                    match.range.forEach { index -> masked[index] = this[index] }
                }
            }
        val sourceFragment = sourceFragments?.getOrNull(fragmentIndex)
        if (sourceFragment != null &&
            conjunctionSourceRelations(sourceFragment.value).isNotEmpty()
        ) {
            ENGLISH_CONJUNCTION_RELATION.findAll(lower, fragment.range.first).forEach { match ->
                if (fragment.range.contains(match.range)) {
                    match.range.forEach { index -> masked[index] = this[index] }
                }
            }
        }
    }
    return String(masked)
}

private fun String.maskProtectedPromptStructuresPreservingSourceEntities(): String {
    val masked = maskProtectedPromptStructures().toCharArray()
    val entities = canonicalSourceOccurrences(this) { anchor -> anchor.entity }
        .distinctBy { occurrence -> occurrence.anchor.label to occurrence.range }
    // The target-side outer scope restores conjunctions so repeated entity bindings remain
    // countable. Mirror only those markers here; spatial operators stay inside the nested scope
    // and are validated when that nested scope is visited below.
    val relationMarkers =
        conjunctionSourceRelations(this@maskProtectedPromptStructuresPreservingSourceEntities)
            .flatMap { relation -> relation.markerRanges }
            .distinct()
    protectedPromptFragments().forEach { fragment ->
        entities.filter { occurrence -> fragment.range.contains(occurrence.range) }
            .forEach { occurrence ->
                occurrence.range.forEach { index -> masked[index] = this[index] }
            }
        relationMarkers.filter { range ->
            range.first >= fragment.range.first && range.last <= fragment.range.last
        }.forEach { range ->
            range.forEach { index -> masked[index] = this[index] }
        }
    }
    return String(masked)
}

internal fun validateLocalImagePromptTranslationSemantics(
    originalPrompt: String,
    effectivePrompt: String,
    originalNegativePrompt: String?,
    effectiveNegativePrompt: String?
) = validateLocalImagePromptTranslationSemanticsInternal(
    originalPrompt = originalPrompt,
    effectivePrompt = effectivePrompt,
    originalNegativePrompt = originalNegativePrompt,
    effectiveNegativePrompt = effectiveNegativePrompt,
    requireControlledChineseVocabulary = true
)

internal fun validateLocalImagePromptTranslationHardContractsV4(
    originalPrompt: String,
    effectivePrompt: String,
    originalNegativePrompt: String?,
    effectiveNegativePrompt: String?
) = validateLocalImagePromptTranslationSemanticsInternal(
    originalPrompt = originalPrompt,
    effectivePrompt = effectivePrompt,
    originalNegativePrompt = originalNegativePrompt,
    effectiveNegativePrompt = effectiveNegativePrompt,
    requireControlledChineseVocabulary = false
)

private fun validateLocalImagePromptTranslationSemanticsInternal(
    originalPrompt: String,
    effectivePrompt: String,
    originalNegativePrompt: String?,
    effectiveNegativePrompt: String?,
    requireControlledChineseVocabulary: Boolean
) {
    require(effectivePrompt.isSafeAsciiDiffusionPrompt() &&
        effectiveNegativePrompt?.isSafeAsciiDiffusionPrompt() != false
    ) {
        "本地翻译模型返回了不安全的非 ASCII 提示词字符。"
    }
    require(!effectivePrompt.containsHanScript() && effectiveNegativePrompt?.containsHanScript() != true) {
        "本地翻译模型未完成中文提示词转换。"
    }
    require((originalNegativePrompt == null) == (effectiveNegativePrompt == null)) {
        "本地翻译模型改变了负向提示词的存在状态。"
    }
    if (!originalPrompt.containsHanScript()) {
        require(effectivePrompt == originalPrompt) {
            "本地翻译模型改写了无需翻译的正向提示词。"
        }
    }
    if (originalNegativePrompt != null && !originalNegativePrompt.containsHanScript()) {
        require(effectiveNegativePrompt == originalNegativePrompt) {
            "本地翻译模型改写了无需翻译的负向提示词。"
        }
    }
    val missingSemantics = buildList {
        addAll(unverifiedSourceUnicodeCharacters(originalPrompt))
        if (requireControlledChineseVocabulary) {
            addAll(unverifiedChinesePromptFragments(originalPrompt))
        }
        addAll(missingCanonicalTranslationAnchors(originalPrompt, effectivePrompt))
        if (requireControlledChineseVocabulary) {
            addAll(unexpectedCanonicalTranslationAnchors(originalPrompt, effectivePrompt))
        }
        if (requireControlledChineseVocabulary) {
            addAll(unexpectedControlledEnglishTokens(originalPrompt, effectivePrompt))
        }
        addAll(
            missingCanonicalClauseBindings(
                originalPrompt,
                effectivePrompt,
                requireControlledVocabulary = requireControlledChineseVocabulary
            )
        )
        addAll(
            missingProtectedPromptFragments(
                source = originalPrompt,
                translated = effectivePrompt,
                enforceAsciiLiteralEntityNeighborhoods = requireControlledChineseVocabulary
            )
        )
        if (originalNegativePrompt != null && effectiveNegativePrompt != null) {
            addAll(unverifiedSourceUnicodeCharacters(originalNegativePrompt))
            if (requireControlledChineseVocabulary) {
                addAll(unverifiedChinesePromptFragments(originalNegativePrompt))
            }
            addAll(
                missingCanonicalTranslationAnchors(
                    originalNegativePrompt,
                    effectiveNegativePrompt,
                    negativeConditioning = true
                )
            )
            addAll(
                missingCanonicalClauseBindings(
                    originalNegativePrompt,
                    effectiveNegativePrompt,
                    negativeConditioning = true,
                    requireControlledVocabulary = requireControlledChineseVocabulary
                )
            )
            if (requireControlledChineseVocabulary) {
                addAll(
                    unexpectedCanonicalTranslationAnchors(
                        originalNegativePrompt,
                        effectiveNegativePrompt,
                        negativeConditioning = true
                    )
                )
            }
            if (requireControlledChineseVocabulary) {
                addAll(unexpectedControlledEnglishTokens(originalNegativePrompt, effectiveNegativePrompt))
            }
            addAll(
                missingProtectedPromptFragments(
                    source = originalNegativePrompt,
                    translated = effectiveNegativePrompt,
                    enforceAsciiLiteralEntityNeighborhoods = requireControlledChineseVocabulary
                )
            )
        }
    }.distinct()
    require(missingSemantics.isEmpty()) {
        "本地翻译模型遗漏或改写了关键语义：${missingSemantics.joinToString()}。"
    }
}

private fun unverifiedSourceUnicodeCharacters(source: String): List<String> {
    val unsupported = mutableListOf<Int>()
    source.codePoints().forEach { codePoint ->
        val allowed = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN ||
            codePoint == '\n'.code || codePoint == '\r'.code ||
            codePoint in 0x20..0x7e ||
            codePoint in SUPPORTED_CHINESE_DIFFUSION_PROMPT_PUNCTUATION
        if (!allowed) unsupported += codePoint
    }
    return unsupported.distinct().map { codePoint ->
        "未覆盖源字符:U+${codePoint.toString(16).uppercase(Locale.ROOT).padStart(4, '0')}"
    }
}

internal fun localImagePromptTranslationPayload(prompt: String, negativePrompt: String?): String =
    JSONObject()
        .put("prompt", prompt)
        .put("negative_prompt", negativePrompt ?: JSONObject.NULL)
        .put("contract_canary_prompt", LOCAL_IMAGE_PROMPT_TRANSLATION_CANARY_PROMPT)
        .put(
            "contract_canary_negative_prompt",
            LOCAL_IMAGE_PROMPT_TRANSLATION_CANARY_NEGATIVE_PROMPT
        )
        .toString()

internal fun promptFingerprint(prompt: String): String = MessageDigest.getInstance("SHA-256")
    .digest(prompt.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun String.removeSingleJsonFence(): String {
    if (!startsWith("```") || !endsWith("```")) return this
    val firstLineEnd = indexOf('\n')
    require(firstLineEnd >= 0) { "本地翻译模型返回的 JSON 代码块无效。" }
    val language = substring(3, firstLineEnd).trim()
    require(language.isEmpty() || language.equals("json", ignoreCase = true)) {
        "本地翻译模型返回了非 JSON 代码块。"
    }
    return substring(firstLineEnd + 1, length - 3).trim()
}

private fun missingCanonicalTranslationAnchors(
    source: String,
    translated: String,
    negativeConditioning: Boolean = false
): List<String> {
    val lower = translated.lowercase(Locale.ROOT)
    val missing = CANONICAL_TRANSLATION_ANCHORS.mapNotNull { anchor ->
        if (anchor.sources.none(source::contains)) return@mapNotNull null
        if (negativeConditioning && anchor.negativeOperator) return@mapNotNull null
        anchor.label.takeUnless { anchor.targets.any { lower.containsCanonicalTarget(it) } }
    }.toMutableList()
    if (negativeConditioning && containsNegativeConditioningOperator(lower)) {
        missing += "负向条件包含否定操作词"
    }
    return missing
}

/**
 * A global word bag cannot detect swapped bindings such as "left red cat/right blue dog" becoming
 * "left blue dog/right red cat". Keep every verifiable source clause bound to one translated clause.
 */
private fun missingCanonicalClauseBindings(
    source: String,
    translated: String,
    negativeConditioning: Boolean = false,
    requireControlledVocabulary: Boolean = true
): List<String> {
    if (!source.containsHanScript()) return emptyList()
    val sourceClauses = source.promptClauses()
    val translatedClauses = translated.promptClauses()
        .map { it.lowercase(Locale.ROOT) }
    if (sourceClauses.size != translatedClauses.size) {
        return listOf("子句数量:${sourceClauses.size}->${translatedClauses.size}")
    }
    return buildList {
        sourceClauses.zip(translatedClauses).forEachIndexed { index, (sourceClause, translatedClause) ->
            val semanticTranslatedClause =
                translatedClause.maskLiteralProtectedFragmentsDeclaredBy(sourceClause)
            val comparableAnchors = CANONICAL_TRANSLATION_ANCHORS.filterNot { anchor ->
                negativeConditioning && anchor.negativeOperator
            }
            val sourceBindings = comparableAnchors.associate { anchor ->
                anchor.label to if (anchor.negativeOperator && !negativeConditioning) {
                    positiveNegationSourceEntities(sourceClause).size
                } else {
                    canonicalSourceCount(sourceClause, anchor)
                }
            }.filterValues { it > 0 }
            val translatedBindings = comparableAnchors.associate { anchor ->
                anchor.label to canonicalTargetCount(semanticTranslatedClause, anchor)
            }.filterValues { it > 0 }
            val strictKnownClause = requireControlledVocabulary ||
                unverifiedChinesePromptFragments(sourceClause).isEmpty()
            if (strictKnownClause && !requireControlledVocabulary) {
                addAll(
                    unexpectedControlledEnglishTokensInClause(
                        sourceClause = sourceClause,
                        translatedClause = translatedClause,
                        clauseNumber = index + 1
                    )
                )
            }
            val bindingMismatch = if (strictKnownClause) {
                sourceBindings != translatedBindings
            } else {
                sourceBindings.any { (label, count) -> translatedBindings[label] != count }
            }
            if (bindingMismatch) {
                add(
                    "子句${index + 1}:" +
                        sourceBindings.entries.joinToString("+") { "${it.key}x${it.value}" } +
                        "->" +
                        translatedBindings.entries.joinToString("+") { "${it.key}x${it.value}" }
                )
            }
            addAll(missingCanonicalAttributeBindings(sourceClause, translatedClause, index + 1))
            addAll(
                missingCanonicalRelationTuples(
                    sourceClause = sourceClause,
                    translatedClause = translatedClause,
                    clauseNumber = index + 1,
                    rejectUnexpectedRelations = strictKnownClause
                )
            )
            if (!negativeConditioning) {
                addAll(missingPositiveNegationBindings(sourceClause, translatedClause, index + 1))
            }
        }
    }
}

private fun positiveNegationSourceEntities(
    sourceClause: String
): List<CanonicalSourceOccurrence> {
    val operators = canonicalSourceOccurrences(sourceClause) { it.negativeOperator }
        .distinctBy(CanonicalSourceOccurrence::range)
        .sortedBy { it.range.first }
    val entities = canonicalSourceOccurrences(sourceClause) { it.entity }
        .distinctBy { it.anchor.label to it.range }
        .sortedBy { it.range.first }
    return operators.flatMapIndexed { index, operator ->
        val nextOperatorStart = operators.getOrNull(index + 1)?.range?.first ?: sourceClause.length
        val containingFragmentEnd = sourceClause.protectedPromptFragments()
            .singleOrNull { fragment -> fragment.range.contains(operator.range) }
            ?.range
            ?.last
            ?.plus(1)
            ?: sourceClause.length
        val endExclusive = minOf(nextOperatorStart, containingFragmentEnd)
        entities.filter { entity ->
            entity.range.first > operator.range.last && entity.range.last < endExclusive
        }
    }
}

private fun missingPositiveNegationBindings(
    sourceClause: String,
    translatedClause: String,
    clauseNumber: Int
): List<String> {
    val sourceEntities = canonicalSourceOccurrences(sourceClause) { it.entity }
        .distinctBy { it.anchor.label to it.range }
        .sortedBy { it.range.first }
    val expected = positiveNegationSourceEntities(sourceClause).map { entity ->
        val ordinal = sourceEntities
            .filter { it.anchor.label == entity.anchor.label }
            .indexOf(entity)
        "${entity.anchor.label}#$ordinal"
    }
    if (CANONICAL_NEGATION_ANCHOR.sources.any(sourceClause::contains) && expected.isEmpty()) {
        return listOf("子句$clauseNumber:正向否定缺少作用实体")
    }
    if (expected.isEmpty()) return emptyList()
    val relationText = translatedClause.maskProtectedPromptStructuresPreservingEntities(sourceClause)
    val operators = NEGATIVE_CONDITIONING_OPERATOR.findAll(relationText).toList()
    val entities = canonicalEnglishEntityOccurrences(relationText)
    val actual = operators.mapNotNull { operator ->
        entities.firstOrNull { it.range.first > operator.range.last }?.let { entity ->
            val ordinal = entities
                .filter { it.anchor.label == entity.anchor.label }
                .indexOf(entity)
            "${entity.anchor.label}#$ordinal"
        }
    }
    return if (actual == expected && operators.size == expected.size) {
        emptyList()
    } else {
        listOf(
            "子句$clauseNumber:正向否定作用域:" +
                "${expected.joinToString("+")}->${actual.joinToString("+")}"
        )
    }
}

private fun canonicalSourceCount(
    source: String,
    anchor: CanonicalTranslationAnchor
): Int = countNonOverlappingCanonicalMatches(
    source = source.maskAsciiOnlyProtectedPromptStructures(),
    patterns = anchor.sources.map { Regex(Regex.escape(it)) } +
        anchor.targets.map { target ->
            Regex(
                "(?<![A-Za-z0-9_.])${Regex.escape(target.trim())}(?![A-Za-z0-9_.])",
                RegexOption.IGNORE_CASE
            )
        }
)

private fun canonicalTargetCount(
    translated: String,
    anchor: CanonicalTranslationAnchor
): Int = countNonOverlappingCanonicalMatches(
    source = translated.lowercase(Locale.ROOT),
    patterns = anchor.targets.map { target ->
        Regex(
            "(?<![a-z0-9_.])${Regex.escape(target.trim().lowercase(Locale.ROOT))}(?![a-z0-9_.])",
            RegexOption.IGNORE_CASE
        )
    }
)

private fun countNonOverlappingCanonicalMatches(
    source: String,
    patterns: List<Regex>
): Int {
    val occupied = BooleanArray(source.length)
    var count = 0
    patterns.sortedByDescending { it.pattern.length }.forEach { pattern ->
        pattern.findAll(source).forEach { match ->
            if (match.range.none { occupied[it] }) {
                match.range.forEach { occupied[it] = true }
                count += 1
            }
        }
    }
    return count
}

private fun unverifiedChinesePromptFragments(source: String): List<String> {
    if (!source.containsHanScript()) return emptyList()
    val consumed = BooleanArray(source.length)
    fun consume(range: IntRange) {
        range.forEach { index -> if (index in consumed.indices) consumed[index] = true }
    }
    val knownTokens = CANONICAL_TRANSLATION_ANCHORS
        .flatMap { it.sources }
        .distinct()
        .sortedByDescending(String::length)
    knownTokens.forEach { token ->
        Regex(Regex.escape(token)).findAll(source).forEach { match -> consume(match.range) }
    }
    source.promptClauseSpans().forEach { clause ->
        val offset = clause.range.first
        fun consumeLocal(range: IntRange) = consume(
            (offset + range.first)..(offset + range.last)
        )
        consumedSemanticRelationMarkerRanges(clause.value).forEach(::consumeLocal)
        conjunctionSourceRelations(clause.value)
            .flatMap(CanonicalSourceBinaryRelation::markerRanges)
            .forEach(::consumeLocal)
        consumedAttributiveParticleRanges(clause.value).forEach(::consumeLocal)
    }
    val remainder = buildString(source.length) {
        source.forEachIndexed { index, character ->
            append(if (consumed[index]) ' ' else character)
        }
    }
    val unknown = buildString {
        remainder.codePoints().forEach { codePoint ->
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                appendCodePoint(codePoint)
            }
        }
    }
    return if (unknown.isEmpty()) emptyList() else listOf("未覆盖中文:${unknown.take(24)}")
}

private fun unexpectedControlledEnglishTokens(source: String, translated: String): List<String> {
    if (!source.containsHanScript()) return emptyList()
    val sourceClauses = source.promptClauses()
    val translatedClauses = translated.promptClauses()
    if (sourceClauses.size != translatedClauses.size) return emptyList()
    return sourceClauses.zip(translatedClauses).flatMapIndexed { clauseIndex, (sourceClause, translatedClause) ->
        unexpectedControlledEnglishTokensInClause(
            sourceClause = sourceClause,
            translatedClause = translatedClause,
            clauseNumber = clauseIndex + 1
        )
    }.distinct()
}

private fun unexpectedControlledEnglishTokensInClause(
    sourceClause: String,
    translatedClause: String,
    clauseNumber: Int
): List<String> {
    if (!sourceClause.containsHanScript()) return emptyList()
    val allowed = buildSet {
        addAll(CONTROLLED_ENGLISH_GRAMMAR_TOKENS)
        addAll(controlledRelationTargetWords(sourceClause))
        CANONICAL_TRANSLATION_ANCHORS.forEach { anchor ->
            if (anchor.sources.any(sourceClause::contains)) {
                anchor.targets.forEach { target ->
                    ENGLISH_PROMPT_TOKEN.findAll(target.lowercase(Locale.ROOT))
                        .mapTo(this) { it.value }
                }
            }
        }
        ENGLISH_PROMPT_TOKEN.findAll(sourceClause.lowercase(Locale.ROOT)).mapTo(this) { it.value }
    }
    return ENGLISH_PROMPT_TOKEN.findAll(translatedClause.lowercase(Locale.ROOT))
        .map { it.value }
        .filterNot(allowed::contains)
        .distinct()
        .map { "子句$clauseNumber:未授权英文:$it" }
        .toList()
}

private fun missingCanonicalAttributeBindings(
    sourceClause: String,
    translatedClause: String,
    clauseNumber: Int
): List<String> {
    val entityOccurrences = canonicalSourceOccurrences(sourceClause) { it.entity }
        .distinctBy { it.anchor.label to it.range }
    if (entityOccurrences.isEmpty()) return emptyList()
    val predicativeRelations = predicativeAttributeSourceRelations(sourceClause)
    val sourceProtectedFragments = sourceClause.protectedPromptFragments()
    val translatedProtectedFragments = translatedClause.protectedPromptFragments()
    val translatedSemanticText = translatedClause.maskProtectedPromptStructures()
    val translatedEntityOccurrences = canonicalEnglishEntityOccurrences(translatedSemanticText)
    val semanticRelationMarkerRanges = consumedSemanticRelationMarkerRanges(sourceClause)
    return buildList {
        val orderedBindings = mutableListOf<Triple<CanonicalSourceOccurrence, CanonicalSourceOccurrence, Int>>()
        canonicalSourceOccurrences(sourceClause) { it.bindingKind != null }
            .distinctBy { it.anchor.label to it.range }
            .forEach { attributeOccurrence ->
            if (semanticRelationMarkerRanges.any { marker -> marker.contains(attributeOccurrence.range) }) {
                return@forEach
            }
            val candidateEntities = when (attributeOccurrence.anchor.bindingKind) {
                CanonicalAttributeBindingKind.QUANTITY -> entityOccurrences.filter { entity ->
                    entity.range.first > attributeOccurrence.range.last
                }
                else -> entityOccurrences
            }
            if (candidateEntities.isEmpty()) {
                add("子句$clauseNumber:属性未绑定:${attributeOccurrence.anchor.label}")
                return@forEach
            }
            val nearestDistance = candidateEntities.minOf { entity ->
                sourceOccurrenceDistance(attributeOccurrence, entity)
            }
            val nearestEntities = candidateEntities.filter { entity ->
                sourceOccurrenceDistance(attributeOccurrence, entity) == nearestDistance
            }
            if (nearestEntities.size != 1) {
                add("子句$clauseNumber:属性歧义:${attributeOccurrence.anchor.label}")
                return@forEach
            }
            val entityOccurrence = nearestEntities.single()
            val predicative = predicativeRelations.any { relation ->
                relation.entity.range == entityOccurrence.range &&
                    relation.attribute.range == attributeOccurrence.range
            }
            val mixedProtectedIndex = sourceProtectedFragments.indexOfFirst { fragment ->
                fragment.value.containsHanScript() &&
                    fragment.range.contains(attributeOccurrence.range) &&
                    fragment.range.contains(entityOccurrence.range)
            }
            val targetAttributeIndex = if (predicative) {
                englishPredicativeAttributeBindingIndex(
                    translatedClause = translatedClause,
                    attribute = attributeOccurrence.anchor,
                    entity = entityOccurrence.anchor
                )
            } else {
                val protectedTarget = translatedProtectedFragments.getOrNull(mixedProtectedIndex)
                if (protectedTarget != null) {
                    val protectedSource = sourceProtectedFragments[mixedProtectedIndex]
                    val sourceOrdinal = entityOccurrences
                        .filter { occurrence ->
                            protectedSource.range.contains(occurrence.range) &&
                                occurrence.anchor.label == entityOccurrence.anchor.label
                        }
                        .sortedBy { it.range.first }
                        .indexOf(entityOccurrence)
                    val targetTokens = protectedTarget.value.englishPromptTokens()
                    val targetEntity = canonicalEnglishEntityOccurrences(protectedTarget.value)
                        .filter { occurrence ->
                            occurrence.anchor.label == entityOccurrence.anchor.label
                        }
                        .sortedBy { it.range.first }
                        .getOrNull(sourceOrdinal)
                    targetEntity?.let { occurrence ->
                        englishConceptBindingIndex(
                            translatedClause = protectedTarget.value,
                            first = attributeOccurrence.anchor,
                            targetEntity = occurrence,
                            attributePrecedesEntity =
                                attributeOccurrence.range.first <= entityOccurrence.range.first
                        )?.let { tokenIndex ->
                            protectedTarget.range.first + targetTokens[tokenIndex].range.first
                        }
                    }
                } else {
                    val sourceOrdinal = entityOccurrences
                        .filter { it.anchor.label == entityOccurrence.anchor.label }
                        .sortedBy { it.range.first }
                        .indexOf(entityOccurrence)
                    val targetEntity = translatedEntityOccurrences
                        .filter { it.anchor.label == entityOccurrence.anchor.label }
                        .sortedBy { it.range.first }
                        .getOrNull(sourceOrdinal)
                    targetEntity?.let { occurrence ->
                        englishConceptBindingIndex(
                            translatedClause = translatedSemanticText,
                            first = attributeOccurrence.anchor,
                            targetEntity = occurrence,
                            attributePrecedesEntity =
                                attributeOccurrence.range.first <= entityOccurrence.range.first
                        )
                    }
                }
            }
            if (targetAttributeIndex == null) {
                add(
                    "子句$clauseNumber:属性:" +
                        "${attributeOccurrence.anchor.label}-${entityOccurrence.anchor.label}"
                )
            } else if (!predicative &&
                attributeOccurrence.anchor.bindingKind != CanonicalAttributeBindingKind.DIRECTION
            ) {
                orderedBindings += Triple(
                    attributeOccurrence,
                    entityOccurrence,
                    targetAttributeIndex
                )
            }
        }
        orderedBindings.groupBy { it.second.range }.values.forEach { entityBindings ->
            val sourceOrdered = entityBindings.sortedBy { it.first.range.first }
            val translatedIndexes = sourceOrdered.map { it.third }
            if (translatedIndexes.zipWithNext().any { (first, second) -> first >= second }) {
                val entity = sourceOrdered.first().second.anchor.label
                add("子句$clauseNumber:多属性顺序:$entity")
            }
        }
    }.distinct()
}

private data class CanonicalSourceOccurrence(
    val anchor: CanonicalTranslationAnchor,
    val range: IntRange
)

private fun canonicalSourceOccurrences(
    source: String,
    include: (CanonicalTranslationAnchor) -> Boolean
): List<CanonicalSourceOccurrence> {
    val semanticAsciiSource = source.maskAsciiOnlyProtectedPromptStructures()
    return CANONICAL_TRANSLATION_ANCHORS
        .filter(include)
        .flatMap { anchor ->
            val occupied = BooleanArray(source.length)
            buildList {
                anchor.sources.sortedByDescending(String::length).forEach { token ->
                    Regex(Regex.escape(token)).findAll(source).forEach { match ->
                        if (match.range.none { occupied[it] }) {
                            match.range.forEach { occupied[it] = true }
                            add(CanonicalSourceOccurrence(anchor, match.range))
                        }
                    }
                }
                anchor.targets.sortedByDescending(String::length).forEach { target ->
                    Regex(
                        "(?<![A-Za-z0-9_.])${Regex.escape(target.trim())}(?![A-Za-z0-9_.])",
                        RegexOption.IGNORE_CASE
                    ).findAll(semanticAsciiSource).forEach { match ->
                        if (match.range.none { occupied[it] }) {
                            match.range.forEach { occupied[it] = true }
                            add(CanonicalSourceOccurrence(anchor, match.range))
                        }
                    }
                }
            }
        }.sortedBy { it.range.first }
}

private fun sourceOccurrenceDistance(
    first: CanonicalSourceOccurrence,
    second: CanonicalSourceOccurrence
): Int = when {
    first.range.last < second.range.first -> second.range.first - first.range.last - 1
    second.range.last < first.range.first -> first.range.first - second.range.last - 1
    else -> 0
}

private fun IntRange.contains(other: IntRange): Boolean = first <= other.first && last >= other.last

private fun englishConceptsNear(
    translatedClause: String,
    first: CanonicalTranslationAnchor,
    second: CanonicalTranslationAnchor,
    attributePrecedesEntity: Boolean,
    maskProtectedStructures: Boolean = true
): Boolean {
    val semanticText = if (maskProtectedStructures) {
        translatedClause.maskProtectedPromptStructures()
    } else {
        translatedClause
    }
    val tokens = ENGLISH_PROMPT_TOKEN.findAll(
        semanticText.lowercase(Locale.ROOT)
    )
        .map { it.value }
        .toList()
    val firstIndexes = tokens.indices.filter { index ->
        first.targets.any { target ->
            ENGLISH_PROMPT_TOKEN.findAll(target.lowercase(Locale.ROOT)).any { it.value == tokens[index] }
        }
    }
    val secondIndexes = tokens.indices.filter { index ->
        second.targets.any { target ->
            ENGLISH_PROMPT_TOKEN.findAll(target.lowercase(Locale.ROOT)).any { it.value == tokens[index] }
        }
    }
    return firstIndexes.any { firstIndex ->
        secondIndexes.any { secondIndex ->
            when (first.bindingKind) {
                CanonicalAttributeBindingKind.ADJACENT ->
                    if (attributePrecedesEntity) {
                        firstIndex < secondIndex &&
                            tokens.subList(firstIndex + 1, secondIndex)
                                .all(CANONICAL_ATTRIBUTE_TARGET_WORDS::contains)
                    } else {
                        secondIndex < firstIndex &&
                            tokens.subList(secondIndex + 1, firstIndex)
                                .all(CANONICAL_ATTRIBUTE_TARGET_WORDS::contains)
                    }
                CanonicalAttributeBindingKind.QUANTITY ->
                    firstIndex < secondIndex &&
                        secondIndex - firstIndex <= MAX_CONTROLLED_ENTITY_PHRASE_TOKENS &&
                        tokens.subList(firstIndex + 1, secondIndex)
                            .all(CANONICAL_ATTRIBUTE_TARGET_WORDS::contains) &&
                        quantityMatchesEntityNumber(first, tokens[secondIndex])
                CanonicalAttributeBindingKind.DIRECTION ->
                    kotlin.math.abs(firstIndex - secondIndex) <= 4 &&
                        tokens.subList(
                            minOf(firstIndex, secondIndex) + 1,
                            maxOf(firstIndex, secondIndex)
                        ).all(CANONICAL_DIRECTION_LINK_WORDS::contains)
                null -> false
            }
        }
    }
}

private fun englishConceptBindingIndex(
    translatedClause: String,
    first: CanonicalTranslationAnchor,
    targetEntity: CanonicalEnglishEntityOccurrence,
    attributePrecedesEntity: Boolean
): Int? {
    val tokens = translatedClause.englishPromptTokens()
    val entityIndex = tokens.indexOfFirst { token ->
        token.range == targetEntity.range && token.matchesSingleWordTarget(targetEntity.anchor)
    }
    if (entityIndex < 0) return null
    val candidates = tokens.indices.filter { firstIndex ->
        if (!tokens[firstIndex].matchesSingleWordTarget(first)) return@filter false
        when (first.bindingKind) {
            CanonicalAttributeBindingKind.ADJACENT ->
                if (attributePrecedesEntity) {
                    firstIndex < entityIndex &&
                        tokens.subList(firstIndex + 1, entityIndex)
                            .all { it.value in CANONICAL_ATTRIBUTE_TARGET_WORDS }
                } else {
                    entityIndex < firstIndex &&
                        tokens.subList(entityIndex + 1, firstIndex)
                            .all { it.value in CANONICAL_ATTRIBUTE_TARGET_WORDS }
                }
            CanonicalAttributeBindingKind.QUANTITY ->
                firstIndex < entityIndex &&
                    entityIndex - firstIndex <= MAX_CONTROLLED_ENTITY_PHRASE_TOKENS &&
                    tokens.subList(firstIndex + 1, entityIndex)
                        .all { it.value in CANONICAL_ATTRIBUTE_TARGET_WORDS } &&
                    quantityMatchesEntityNumber(first, tokens[entityIndex].value)
            CanonicalAttributeBindingKind.DIRECTION ->
                kotlin.math.abs(firstIndex - entityIndex) <= 4 &&
                    tokens.subList(
                        minOf(firstIndex, entityIndex) + 1,
                        maxOf(firstIndex, entityIndex)
                    ).all { it.value in CANONICAL_DIRECTION_LINK_WORDS }
            null -> false
        }
    }
    val minimumDistance = candidates.minOfOrNull { kotlin.math.abs(it - entityIndex) } ?: return null
    return candidates.singleOrNull { kotlin.math.abs(it - entityIndex) == minimumDistance }
}

private fun quantityMatchesEntityNumber(
    quantity: CanonicalTranslationAnchor,
    entityWord: String
): Boolean = if (quantity.label == "一个") {
    entityWord in CANONICAL_SINGULAR_ENTITY_TARGET_WORDS
} else {
    entityWord in CANONICAL_PLURAL_ENTITY_TARGET_WORDS
}

private fun englishPredicativeAttributeBindingIndex(
    translatedClause: String,
    attribute: CanonicalTranslationAnchor,
    entity: CanonicalTranslationAnchor
): Int? {
    val tokens = translatedClause.maskProtectedPromptStructures().englishPromptTokens()
    val attributeIndexes = tokens.indices.filter { tokens[it].matchesSingleWordTarget(attribute) }
    val entityIndexes = tokens.indices.filter { tokens[it].matchesSingleWordTarget(entity) }
    val candidates = attributeIndexes.filter { attributeIndex ->
        entityIndexes.any { entityIndex ->
            attributeIndex + 1 == entityIndex ||
                (entityIndex + 2 == attributeIndex &&
                    tokens[entityIndex + 1].value in setOf("is", "are"))
        }
    }
    return candidates.singleOrNull()
}

private enum class CanonicalSourceRelationKind {
    ADJACENCY,
    POSSESSION,
    COPULA,
    CONJUNCTION
}

private data class CanonicalSourceBinaryRelation(
    val before: CanonicalTranslationAnchor,
    val after: CanonicalTranslationAnchor,
    val kind: CanonicalSourceRelationKind,
    val markerRanges: List<IntRange>
)

private data class CanonicalEnglishEntityOccurrence(
    val anchor: CanonicalTranslationAnchor,
    val range: IntRange
)

private data class CanonicalPredicativeAttributeRelation(
    val entity: CanonicalSourceOccurrence,
    val attribute: CanonicalSourceOccurrence,
    val markerRange: IntRange
)

private fun sourceEntityOccurrencesBetween(
    source: String,
    startInclusive: Int,
    endExclusive: Int
): List<CanonicalSourceOccurrence> {
    if (startInclusive >= endExclusive) return emptyList()
    return canonicalSourceOccurrences(source) { it.entity }
        .filter { occurrence ->
            occurrence.range.first >= startInclusive && occurrence.range.last < endExclusive
        }
        .distinctBy { it.anchor.label to it.range }
        .sortedBy { it.range.first }
}

private fun uniqueSourceEntityBetween(
    source: String,
    startInclusive: Int,
    endExclusive: Int
): CanonicalTranslationAnchor? = sourceEntityOccurrencesBetween(
    source = source,
    startInclusive = startInclusive,
    endExclusive = endExclusive
).singleOrNull()?.anchor

private fun adjacencySourceRelations(source: String): List<CanonicalSourceBinaryRelation> =
    buildList {
        ADJACENCY_EXISTENCE_RELATION.findAll(source).forEach { match ->
            val adjacency = match.groups[1] ?: return@forEach
            val operator = match.groups[2] ?: return@forEach
            val reference = uniqueSourceEntityBetween(source, 0, adjacency.range.first)
                ?: return@forEach
            val subject = uniqueSourceEntityBetween(source, operator.range.last + 1, source.length)
                ?: return@forEach
            add(
                CanonicalSourceBinaryRelation(
                    before = reference,
                    after = subject,
                    kind = CanonicalSourceRelationKind.ADJACENCY,
                    markerRanges = listOf(adjacency.range, operator.range)
                )
            )
        }
        ADJACENCY_PLACEMENT_RELATION.findAll(source).forEach { match ->
            val connector = match.groups[1] ?: return@forEach
            val adjacency = match.groups[2] ?: return@forEach
            val subject = uniqueSourceEntityBetween(source, 0, connector.range.first)
                ?: return@forEach
            val reference = uniqueSourceEntityBetween(
                source,
                connector.range.last + 1,
                adjacency.range.first
            ) ?: return@forEach
            add(
                CanonicalSourceBinaryRelation(
                    before = subject,
                    after = reference,
                    kind = CanonicalSourceRelationKind.ADJACENCY,
                    markerRanges = listOf(connector.range, adjacency.range)
                )
            )
        }
    }

private fun String.hasSpatialExistencePrefix(index: Int): Boolean {
    val prefix = substring(0, index)
    return SPATIAL_EXISTENCE_PREFIXES.any(prefix::endsWith)
}

private fun possessionSourceRelations(source: String): List<CanonicalSourceBinaryRelation> =
    GENERIC_HAS_RELATION.findAll(source).mapNotNull { match ->
        if (source.hasSpatialExistencePrefix(match.range.first)) return@mapNotNull null
        val owner = uniqueSourceEntityBetween(source, 0, match.range.first)
            ?: return@mapNotNull null
        val possessed = uniqueSourceEntityBetween(source, match.range.last + 1, source.length)
            ?: return@mapNotNull null
        CanonicalSourceBinaryRelation(
            before = owner,
            after = possessed,
            kind = CanonicalSourceRelationKind.POSSESSION,
            markerRanges = listOf(match.range)
        )
    }.toList()

private fun copulaSourceRelations(source: String): List<CanonicalSourceBinaryRelation> =
    GENERIC_COPULA_RELATION.findAll(source).mapNotNull { match ->
        if (source.hasSpatialExistencePrefix(match.range.first)) return@mapNotNull null
        val subject = uniqueSourceEntityBetween(source, 0, match.range.first)
            ?: return@mapNotNull null
        val complement = uniqueSourceEntityBetween(source, match.range.last + 1, source.length)
            ?: return@mapNotNull null
        CanonicalSourceBinaryRelation(
            before = subject,
            after = complement,
            kind = CanonicalSourceRelationKind.COPULA,
            markerRanges = listOf(match.range)
        )
    }.toList()

private fun conjunctionSourceRelations(source: String): List<CanonicalSourceBinaryRelation> {
    val entities = canonicalSourceOccurrences(source) { it.entity }
        .distinctBy { it.anchor.label to it.range }
        .sortedBy { it.range.first }
    return CHINESE_CONJUNCTION.findAll(source).mapNotNull { match ->
        val before = entities.lastOrNull { it.range.last < match.range.first }?.anchor
            ?: return@mapNotNull null
        val after = entities.firstOrNull { it.range.first > match.range.last }?.anchor
            ?: return@mapNotNull null
        CanonicalSourceBinaryRelation(
            before = before,
            after = after,
            kind = CanonicalSourceRelationKind.CONJUNCTION,
            markerRanges = listOf(match.range)
        )
    }.toList()
}

private fun predicativeAttributeSourceRelations(
    source: String
): List<CanonicalPredicativeAttributeRelation> = GENERIC_COPULA_RELATION.findAll(source)
    .mapNotNull { match ->
        if (source.hasSpatialExistencePrefix(match.range.first)) return@mapNotNull null
        val entity = sourceEntityOccurrencesBetween(source, 0, match.range.first).singleOrNull()
            ?: return@mapNotNull null
        val attributes = canonicalSourceOccurrences(source) {
            it.bindingKind == CanonicalAttributeBindingKind.ADJACENT
        }.filter { occurrence -> occurrence.range.first > match.range.last }
        val attribute = attributes.singleOrNull() ?: return@mapNotNull null
        if (sourceEntityOccurrencesBetween(source, match.range.last + 1, source.length).isNotEmpty()) {
            return@mapNotNull null
        }
        if (source.substring(entity.range.last + 1, match.range.first).any { !it.isWhitespace() } ||
            source.substring(match.range.last + 1, attribute.range.first).any { !it.isWhitespace() }
        ) return@mapNotNull null
        CanonicalPredicativeAttributeRelation(entity, attribute, match.range)
    }
    .toList()

private fun consumedAttributiveParticleRanges(source: String): List<IntRange> =
    CHINESE_ATTRIBUTIVE_PARTICLE.findAll(source).mapNotNull { match ->
        val attribute = canonicalSourceOccurrences(source) {
            it.bindingKind == CanonicalAttributeBindingKind.ADJACENT
        }.filter { occurrence ->
            occurrence.range.last < match.range.first &&
                source.substring(occurrence.range.last + 1, match.range.first).all(Char::isWhitespace)
        }.maxByOrNull { it.range.last } ?: return@mapNotNull null
        val entity = sourceEntityOccurrencesBetween(source, match.range.last + 1, source.length)
            .filter { occurrence ->
                source.substring(match.range.last + 1, occurrence.range.first).all(Char::isWhitespace)
            }
            .minByOrNull { it.range.first } ?: return@mapNotNull null
        match.range.takeIf { attribute.range.last < it.first && entity.range.first > it.last }
    }.toList()

private fun canonicalEnglishEntityOccurrences(
    translated: String
): List<CanonicalEnglishEntityOccurrence> = translated.englishPromptTokens()
    .mapNotNull { token ->
        val anchors = CANONICAL_TRANSLATION_ANCHORS.filter { anchor ->
            anchor.entity && token.matchesSingleWordTarget(anchor)
        }
        anchors.singleOrNull()?.let { anchor ->
            CanonicalEnglishEntityOccurrence(anchor, token.range)
        }
    }

private fun translatedRelationBindingMatch(
    translated: String,
    relation: Regex,
    expectedBefore: CanonicalTranslationAnchor,
    expectedAfter: CanonicalTranslationAnchor,
    symmetric: Boolean,
    excludedMatches: Set<IntRange>
): IntRange? {
    val relationText = translated
    val entities = canonicalEnglishEntityOccurrences(relationText)
    return relation.findAll(relationText).firstOrNull { match ->
        if (match.range in excludedMatches) return@firstOrNull false
        val before = entities.lastOrNull { it.range.last < match.range.first }?.anchor
        val after = entities.firstOrNull { it.range.first > match.range.last }?.anchor
        (before == expectedBefore && after == expectedAfter) ||
            (symmetric && before == expectedAfter && after == expectedBefore)
    }?.range
}

private fun consumeSpatialAuxiliaryCopulaBefore(
    translated: String,
    operatorRange: IntRange,
    consumedTargetRelationMatches: MutableSet<IntRange>
) {
    val precedingEntity = canonicalEnglishEntityOccurrences(translated)
        .lastOrNull { entity -> entity.range.last < operatorRange.first }
        ?: return
    val gapStart = precedingEntity.range.last + 1
    if (gapStart >= operatorRange.first) return
    val gap = translated.substring(gapStart, operatorRange.first)
    val copulas = ENGLISH_COPULA_RELATION.findAll(gap).toList()
    if (copulas.size != 1) return
    val copula = copulas.single()
    if (gap.substring(0, copula.range.first).any { character -> !character.isWhitespace() }) return
    val bridge = gap.substring(copula.range.last + 1).trim().lowercase(Locale.ROOT)
    if (bridge.isNotEmpty() && bridge !in setOf("placed", "sitting", "located")) return
    consumedTargetRelationMatches +=
        (gapStart + copula.range.first)..(gapStart + copula.range.last)
}

private fun consumeInvertedSpatialAuxiliaryCopula(
    translated: String,
    relationMatchRange: IntRange,
    operatorRange: IntRange,
    expectedReference: CanonicalTranslationAnchor,
    expectedSubject: CanonicalTranslationAnchor,
    consumedTargetRelationMatches: MutableSet<IntRange>
) {
    val entities = canonicalEnglishEntityOccurrences(translated)
        .filter { entity ->
            entity.range.first >= relationMatchRange.first &&
                entity.range.last <= relationMatchRange.last
        }
    val reference = entities.firstOrNull { entity ->
        entity.range.first > operatorRange.last && entity.anchor == expectedReference
    } ?: return
    val subject = entities.firstOrNull { entity ->
        entity.range.first > reference.range.last && entity.anchor == expectedSubject
    } ?: return
    val gapStart = reference.range.last + 1
    if (gapStart >= subject.range.first) return
    val gap = translated.substring(gapStart, subject.range.first)
    val copulas = ENGLISH_COPULA_RELATION.findAll(gap).toList()
    if (copulas.size != 1) return
    val copula = copulas.single()
    val prefix = gap.substring(0, copula.range.first).trim().lowercase(Locale.ROOT)
    if (prefix.isNotEmpty() && prefix != "there") return
    val suffix = gap.substring(copula.range.last + 1)
    val suffixTokens = suffix.englishPromptTokens()
    val hasUnsupportedPrefixToken = suffixTokens.any { token ->
        token.value !in CONTROLLED_INVERTED_SPATIAL_SUBJECT_PREFIX_WORDS
    }
    if (hasUnsupportedPrefixToken) return
    val suffixResidue = suffix.toCharArray()
    suffixTokens.forEach { token ->
        token.range.forEach { index -> suffixResidue[index] = ' ' }
    }
    if (suffixResidue.any { character -> !character.isWhitespace() }) return
    consumedTargetRelationMatches +=
        (gapStart + copula.range.first)..(gapStart + copula.range.last)
}

private fun consumeSpatialTargetRelation(
    translated: String,
    subjectFirstMatch: MatchResult?,
    invertedMatch: MatchResult?,
    expectedReference: CanonicalTranslationAnchor,
    expectedSubject: CanonicalTranslationAnchor,
    consumedTargetRelationMatches: MutableSet<IntRange>
): Boolean {
    val subjectFirstOperator = subjectFirstMatch?.groups?.get(1)?.range
    val invertedOperator = invertedMatch?.groups?.get(1)?.range
    val operatorRange = subjectFirstOperator ?: invertedOperator ?: return false
    consumedTargetRelationMatches += operatorRange
    if (subjectFirstOperator != null) {
        consumeSpatialAuxiliaryCopulaBefore(
            translated = translated,
            operatorRange = subjectFirstOperator,
            consumedTargetRelationMatches = consumedTargetRelationMatches
        )
    } else if (invertedMatch != null) {
        consumeInvertedSpatialAuxiliaryCopula(
            translated = translated,
            relationMatchRange = invertedMatch.range,
            operatorRange = operatorRange,
            expectedReference = expectedReference,
            expectedSubject = expectedSubject,
            consumedTargetRelationMatches = consumedTargetRelationMatches
        )
    }
    return true
}

private fun consumedSemanticRelationMarkerRanges(source: String): List<IntRange> = buildList {
    DIRECTIONAL_EXISTENCE_RELATION.findAll(source).forEach { match ->
        val direction = match.groups[1] ?: return@forEach
        val operator = match.groups[2] ?: return@forEach
        if (uniqueSourceEntityBetween(source, 0, match.range.first) != null &&
            uniqueSourceEntityBetween(source, match.range.last + 1, source.length) != null
        ) {
            add(direction.range)
            add(operator.range)
        }
    }
    PLACED_ON_RELATION.findAll(source).forEach { match ->
        val connector = match.groups[1] ?: return@forEach
        val locative = match.groups[2] ?: return@forEach
        if (uniqueSourceEntityBetween(source, 0, connector.range.first) != null &&
            uniqueSourceEntityBetween(
                source,
                connector.range.last + 1,
                locative.range.first
            ) != null
        ) {
            add(connector.range)
            add(locative.range)
        }
    }
    SUBJECT_RELATIVE_DIRECTION.findAll(source).forEach { match ->
        val connector = match.groups[1] ?: return@forEach
        val direction = match.groups[2] ?: return@forEach
        if (uniqueSourceEntityBetween(source, 0, connector.range.first) != null &&
            uniqueSourceEntityBetween(
                source,
                connector.range.last + 1,
                direction.range.first
            ) != null
        ) {
            add(connector.range)
            add(direction.range)
        }
    }
    SIMPLE_LOCATIVE_RELATION.findAll(source).forEach { match ->
        val connector = match.groups[1] ?: return@forEach
        val locative = match.groups[2] ?: return@forEach
        if (uniqueSourceEntityBetween(source, 0, connector.range.first) != null &&
            uniqueSourceEntityBetween(
                source,
                connector.range.last + 1,
                locative.range.first
            ) != null
        ) {
            add(connector.range)
            add(locative.range)
        }
    }
    adjacencySourceRelations(source).flatMapTo(this) { it.markerRanges }
    possessionSourceRelations(source).flatMapTo(this) { it.markerRanges }
    copulaSourceRelations(source).flatMapTo(this) { it.markerRanges }
    predicativeAttributeSourceRelations(source).mapTo(this) { it.markerRange }
}

private fun englishDirectionTargets(sourceDirection: String): Set<String> = when (sourceDirection) {
    "左侧", "左边" -> setOf("left")
    "右侧", "右边" -> setOf("right")
    "上方", "上面" -> setOf("above", "over", "on top")
    else -> setOf("below", "under", "beneath")
}

private fun englishDirectionRelationPattern(targets: Set<String>): String =
    targets.joinToString(prefix = "(?:", postfix = ")", separator = "|") { target ->
        when (target) {
            "left", "right" -> "(?:to\\s+the\\s+)?$target\\s+of"
            else -> target.split(' ').joinToString("\\s+") { Regex.escape(it) }
        }
    }

private fun missingCanonicalRelationTuples(
    sourceClause: String,
    translatedClause: String,
    clauseNumber: Int,
    rejectUnexpectedRelations: Boolean
): List<String> = buildList {
    fun validateScope(sourceScope: String, translatedScope: String) {
        val scopedSource = sourceScope.maskProtectedPromptStructuresPreservingSourceEntities()
        val scopedTarget = translatedScope
            .maskAsciiLiteralFragmentsDeclaredBy(sourceScope)
            .maskProtectedPromptStructuresPreservingEntities(sourceScope)
        addAll(
            missingCanonicalRelationTuplesInScope(
                sourceClause = scopedSource,
                translatedClause = scopedTarget,
                clauseNumber = clauseNumber,
                rejectUnexpectedRelations = rejectUnexpectedRelations
            )
        )
    }

    validateScope(sourceClause, translatedClause)
    val sourceScopes = sourceClause.balancedPromptStructureFragments()
    val translatedScopes = translatedClause.balancedPromptStructureFragments()
    if (sourceScopes.size == translatedScopes.size) {
        sourceScopes.zip(translatedScopes).forEach { (sourceScope, translatedScope) ->
            if (!sourceScope.value.containsHanScript()) return@forEach
            val sourceInner = sourceScope.innerPromptStructureValueOrNull() ?: return@forEach
            val translatedInner = translatedScope.innerPromptStructureValueOrNull()
                ?: return@forEach
            validateScope(sourceInner, translatedInner)
        }
    }
}

private fun missingCanonicalRelationTuplesInScope(
    sourceClause: String,
    translatedClause: String,
    clauseNumber: Int,
    rejectUnexpectedRelations: Boolean
): List<String> = buildList {
    val relationText = translatedClause.maskProtectedPromptStructuresPreservingEntities(sourceClause)
    val consumedTargetRelationMatches = mutableSetOf<IntRange>()
    val binaryRelations = adjacencySourceRelations(sourceClause) +
        possessionSourceRelations(sourceClause) +
        copulaSourceRelations(sourceClause) +
        conjunctionSourceRelations(sourceClause)
    binaryRelations.forEach { relation ->
        val (targetRelation, symmetric, label) = when (relation.kind) {
            CanonicalSourceRelationKind.ADJACENCY ->
                Triple(ENGLISH_ADJACENCY_RELATION, true, "adjacent")
            CanonicalSourceRelationKind.POSSESSION ->
                Triple(ENGLISH_POSSESSION_RELATION, false, "has")
            CanonicalSourceRelationKind.COPULA ->
                Triple(ENGLISH_COPULA_RELATION, false, "is")
            CanonicalSourceRelationKind.CONJUNCTION ->
                Triple(ENGLISH_CONJUNCTION_RELATION, true, "and")
        }
        val targetMatch = translatedRelationBindingMatch(
            translated = relationText,
            relation = targetRelation,
            expectedBefore = relation.before,
            expectedAfter = relation.after,
            symmetric = symmetric,
            excludedMatches = consumedTargetRelationMatches
        )
        if (targetMatch == null) {
            add(
                "子句$clauseNumber:关系:${relation.before.label}-$label-${relation.after.label}"
            )
        } else {
            consumedTargetRelationMatches += targetMatch
            if (relation.kind == CanonicalSourceRelationKind.ADJACENCY) {
                consumeSpatialAuxiliaryCopulaBefore(
                    translated = relationText,
                    operatorRange = targetMatch,
                    consumedTargetRelationMatches = consumedTargetRelationMatches
                )
            }
        }
    }

    DIRECTIONAL_EXISTENCE_RELATION.findAll(sourceClause).forEach { match ->
        val direction = match.groupValues[1]
        val markerStart = match.range.first
        val markerEnd = match.range.last + 1
        val reference = sourceEntityBefore(sourceClause, markerStart) ?: return@forEach
        val subject = sourceEntityAfter(sourceClause, markerEnd) ?: return@forEach
        val englishDirections = englishDirectionTargets(direction)
        val subjectPattern = subject.englishTargetPattern()
        val referencePattern = reference.englishTargetPattern()
        val directionPattern = englishDirectionRelationPattern(englishDirections)
        val subjectFirst = Regex(
            "$subjectPattern.{0,120}?($directionPattern).{0,120}$referencePattern",
            RegexOption.IGNORE_CASE
        )
        val invertedGrammar = Regex(
            "($directionPattern).{0,120}$referencePattern.{0,120}$subjectPattern",
            RegexOption.IGNORE_CASE
        )
        val subjectFirstMatch = subjectFirst.find(relationText)
        val invertedMatch = invertedGrammar.find(relationText)
        if (!consumeSpatialTargetRelation(
                translated = relationText,
                subjectFirstMatch = subjectFirstMatch,
                invertedMatch = invertedMatch,
                expectedReference = reference,
                expectedSubject = subject,
                consumedTargetRelationMatches = consumedTargetRelationMatches
            )
        ) {
            add(
                "子句$clauseNumber:关系:${subject.label}-" +
                    "${englishDirections.sorted().joinToString("/")}-${reference.label}"
            )
        }
    }

    PLACED_ON_RELATION.findAll(sourceClause).forEach { match ->
        val connector = match.groups[1] ?: return@forEach
        val locative = match.groups[2] ?: return@forEach
        val subject = uniqueSourceEntityBetween(sourceClause, 0, connector.range.first)
            ?: return@forEach
        val reference = uniqueSourceEntityBetween(
            sourceClause,
            connector.range.last + 1,
            locative.range.first
        ) ?: return@forEach
        val subjectPattern = subject.englishTargetPattern()
        val referencePattern = reference.englishTargetPattern()
        val subjectFirst = Regex(
            "$subjectPattern.{0,120}?((?<![a-z])on(?![a-z])).{0,120}$referencePattern",
            RegexOption.IGNORE_CASE
        )
        val invertedGrammar = Regex(
            "((?<![a-z])on(?![a-z])).{0,120}$referencePattern.{0,120}$subjectPattern",
            RegexOption.IGNORE_CASE
        )
        val subjectFirstMatch = subjectFirst.find(relationText)
        val invertedMatch = invertedGrammar.find(relationText)
        if (!consumeSpatialTargetRelation(
                translated = relationText,
                subjectFirstMatch = subjectFirstMatch,
                invertedMatch = invertedMatch,
                expectedReference = reference,
                expectedSubject = subject,
                consumedTargetRelationMatches = consumedTargetRelationMatches
            )
        ) {
            add("子句$clauseNumber:关系:${subject.label}-on-${reference.label}")
        }
    }

    SUBJECT_RELATIVE_DIRECTION.findAll(sourceClause).forEach { match ->
        val connector = match.groups[1] ?: return@forEach
        val directionGroup = match.groups[2] ?: return@forEach
        val subject = uniqueSourceEntityBetween(sourceClause, 0, connector.range.first)
            ?: return@forEach
        val reference = uniqueSourceEntityBetween(
            sourceClause,
            connector.range.last + 1,
            directionGroup.range.first
        ) ?: return@forEach
        val englishDirections = englishDirectionTargets(directionGroup.value)
        val directionPattern = englishDirectionRelationPattern(englishDirections)
        val subjectPattern = subject.englishTargetPattern()
        val referencePattern = reference.englishTargetPattern()
        val subjectFirst = Regex(
            "$subjectPattern.{0,120}?($directionPattern).{0,120}$referencePattern",
            RegexOption.IGNORE_CASE
        )
        val invertedGrammar = Regex(
            "($directionPattern).{0,120}$referencePattern.{0,120}$subjectPattern",
            RegexOption.IGNORE_CASE
        )
        val subjectFirstMatch = subjectFirst.find(relationText)
        val invertedMatch = invertedGrammar.find(relationText)
        if (!consumeSpatialTargetRelation(
                translated = relationText,
                subjectFirstMatch = subjectFirstMatch,
                invertedMatch = invertedMatch,
                expectedReference = reference,
                expectedSubject = subject,
                consumedTargetRelationMatches = consumedTargetRelationMatches
            )
        ) {
            add(
                "子句$clauseNumber:关系:${subject.label}-" +
                    "${englishDirections.sorted().joinToString("/")}-${reference.label}"
            )
        }
    }

    SIMPLE_LOCATIVE_RELATION.findAll(sourceClause).forEach { match ->
        val connector = match.groups[1] ?: return@forEach
        val directionGroup = match.groups[2] ?: return@forEach
        val subject = uniqueSourceEntityBetween(sourceClause, 0, connector.range.first)
            ?: return@forEach
        val reference = uniqueSourceEntityBetween(
            sourceClause,
            connector.range.last + 1,
            directionGroup.range.first
        ) ?: return@forEach
        val targetWords = when (directionGroup.value) {
            "上" -> setOf("on")
            "下" -> setOf("under", "below")
            "里", "内" -> setOf("in", "inside")
            else -> setOf("outside")
        }
        val relationPattern = targetWords.joinToString("|") { Regex.escape(it) }
        val subjectPattern = subject.englishTargetPattern()
        val referencePattern = reference.englishTargetPattern()
        val subjectFirst = Regex(
            "$subjectPattern.{0,120}?((?<![a-z])(?:$relationPattern)(?![a-z])).{0,120}$referencePattern",
            RegexOption.IGNORE_CASE
        )
        val invertedGrammar = Regex(
            "((?<![a-z])(?:$relationPattern)(?![a-z])).{0,120}$referencePattern.{0,120}$subjectPattern",
            RegexOption.IGNORE_CASE
        )
        val subjectFirstMatch = subjectFirst.find(relationText)
        val invertedMatch = invertedGrammar.find(relationText)
        if (!consumeSpatialTargetRelation(
                translated = relationText,
                subjectFirstMatch = subjectFirstMatch,
                invertedMatch = invertedMatch,
                expectedReference = reference,
                expectedSubject = subject,
                consumedTargetRelationMatches = consumedTargetRelationMatches
            )
        ) {
            add("子句$clauseNumber:关系:${subject.label}-${directionGroup.value}-${reference.label}")
        }
    }

    val relationEntities = canonicalEnglishEntityOccurrences(relationText)
    if (rejectUnexpectedRelations) {
        CanonicalSourceRelationKind.entries.forEach { kind ->
            val (targetRelation, label) = when (kind) {
                CanonicalSourceRelationKind.ADJACENCY -> ENGLISH_ADJACENCY_RELATION to "adjacent"
                CanonicalSourceRelationKind.POSSESSION -> ENGLISH_POSSESSION_RELATION to "has"
                CanonicalSourceRelationKind.COPULA -> ENGLISH_COPULA_RELATION to "is"
                CanonicalSourceRelationKind.CONJUNCTION -> ENGLISH_CONJUNCTION_RELATION to "and"
            }
            val leftovers = targetRelation.findAll(relationText).count { match ->
                match.range !in consumedTargetRelationMatches &&
                    relationEntities.any { entity -> entity.range.last < match.range.first } &&
                    relationEntities.any { entity -> entity.range.first > match.range.last }
            }
            if (leftovers > 0) {
                add("子句$clauseNumber:新增关系:$label x$leftovers")
            }
        }
        val spatialLeftovers = ENGLISH_SIMPLE_LOCATIVE_RELATION.findAll(relationText).count { match ->
            consumedTargetRelationMatches.none { consumed ->
                consumed.first <= match.range.first && consumed.last >= match.range.last
            } && relationEntities.size >= 2
        }
        if (spatialLeftovers > 0) {
            add("子句$clauseNumber:新增关系:spatial x$spatialLeftovers")
        }
    }
}

private fun controlledRelationTargetWords(source: String): Set<String> = buildSet {
    if (PLACED_ON_RELATION.containsMatchIn(source) ||
        SIMPLE_LOCATIVE_RELATION.findAll(source).any { it.groupValues[2] == "上" }
    ) addAll(setOf("on", "is", "are", "placed", "sitting", "located"))
    SIMPLE_LOCATIVE_RELATION.findAll(source).forEach { match ->
        when (match.groupValues[2]) {
            "下" -> addAll(setOf("under", "below"))
            "里", "内" -> addAll(setOf("in", "inside"))
            "外" -> add("outside")
        }
    }
    if (CANONICAL_TRANSLATION_ANCHORS.any { anchor ->
            anchor.label in setOf("左侧", "右侧") && anchor.sources.any(source::contains)
        }
    ) add("on")
    if (adjacencySourceRelations(source).isNotEmpty()) {
        addAll(setOf("next", "to", "beside", "near", "is", "are"))
    }
    if (possessionSourceRelations(source).isNotEmpty()) {
        addAll(setOf("with", "has", "have"))
    }
    if (copulaSourceRelations(source).isNotEmpty()) {
        addAll(setOf("is", "are"))
    }
    if (predicativeAttributeSourceRelations(source).isNotEmpty()) {
        addAll(setOf("is", "are"))
    }
    if (conjunctionSourceRelations(source).isNotEmpty()) {
        add("and")
    }
    if (DIRECTIONAL_EXISTENCE_RELATION.containsMatchIn(source) ||
        SUBJECT_RELATIVE_DIRECTION.containsMatchIn(source) ||
        SIMPLE_LOCATIVE_RELATION.containsMatchIn(source)
    ) {
        addAll(setOf("is", "are", "to", "of", "there", "its", "side"))
    }
}

private fun sourceEntityBefore(source: String, endExclusive: Int): CanonicalTranslationAnchor? =
    CANONICAL_TRANSLATION_ANCHORS.asSequence()
        .filter { it.entity }
        .flatMap { anchor ->
            anchor.sources.asSequence().mapNotNull { token ->
                val index = source.lastIndexOf(token, startIndex = (endExclusive - 1).coerceAtLeast(0))
                anchor.takeIf { index >= 0 && index + token.length <= endExclusive }?.let { index to it }
            }
        }
        .maxByOrNull { it.first }
        ?.second

private fun sourceEntityAfter(source: String, startIndex: Int): CanonicalTranslationAnchor? =
    CANONICAL_TRANSLATION_ANCHORS.asSequence()
        .filter { it.entity }
        .flatMap { anchor ->
            anchor.sources.asSequence().mapNotNull { token ->
                source.indexOf(token, startIndex = startIndex)
                    .takeIf { it >= 0 }
                    ?.let { it to anchor }
            }
        }
        .minByOrNull { it.first }
        ?.second

private fun CanonicalTranslationAnchor.englishTargetPattern(): String = targets
    .sortedByDescending(String::length)
    .joinToString(prefix = "(?:", postfix = ")", separator = "|") { target ->
        "(?<![a-z0-9_])${Regex.escape(target.trim())}(?![a-z0-9_])"
    }

private fun unexpectedCanonicalTranslationAnchors(
    source: String,
    translated: String,
    negativeConditioning: Boolean = false
): List<String> {
    if (!source.containsHanScript()) return emptyList()
    val semanticSource = source.maskAsciiOnlyProtectedPromptStructures()
    val lower = translated.maskLiteralProtectedFragmentsDeclaredBy(source)
        .lowercase(Locale.ROOT)
    return CANONICAL_TRANSLATION_ANCHORS.mapNotNull { anchor ->
        if (negativeConditioning && anchor.negativeOperator) return@mapNotNull null
        if (anchor.sources.any(semanticSource::contains) ||
            anchor.targets.any { semanticSource.lowercase(Locale.ROOT).containsCanonicalTarget(it) }
        ) return@mapNotNull null
        "新增:${anchor.label}".takeIf {
            anchor.targets.any { lower.containsCanonicalTarget(it) }
        }
    }
}

private fun String.containsCanonicalTarget(target: String): Boolean {
    val normalized = target.trim().lowercase(Locale.ROOT)
    if (normalized.isEmpty()) return false
    return Regex(
        "(?<![a-z0-9_.])${Regex.escape(normalized)}(?![a-z0-9_.])",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(this)
}

private fun missingProtectedPromptFragments(
    source: String,
    translated: String,
    enforceAsciiLiteralEntityNeighborhoods: Boolean
): List<String> {
    if (!source.containsHanScript()) return emptyList()
    val missing = mutableListOf<String>()
    if (source.promptStructureEscapeSignature() != translated.promptStructureEscapeSignature()) {
        missing += "保护结构或转义位置"
    }
    if (source.promptSeparatorSignature(topLevelOnly = true) !=
        translated.promptSeparatorSignature(topLevelOnly = true)
    ) {
        missing += "子句分隔符类型或顺序"
    }
    if (source.promptSeparatorSignature(topLevelOnly = false) !=
        translated.promptSeparatorSignature(topLevelOnly = false)
    ) {
        missing += "保护结构内分隔符类型或顺序"
    }
    fun requireMultiset(
        label: String,
        regex: Regex,
        ignoreCase: Boolean = false,
        rejectUnexpectedValues: Boolean = false
    ) {
        val expected = regex.findAll(source)
            .map { match -> if (ignoreCase) match.value.lowercase(Locale.ROOT) else match.value }
            .groupingBy { it }
            .eachCount()
        val actual = regex.findAll(translated)
            .map { match -> if (ignoreCase) match.value.lowercase(Locale.ROOT) else match.value }
            .groupingBy { it }
            .eachCount()
        expected.forEach { (value, count) ->
            if ((actual[value] ?: 0) != count) missing += "$label:$value"
        }
        if (rejectUnexpectedValues) {
            (actual.keys - expected.keys).forEach { value ->
                missing += "$label:新增:$value"
            }
        }
    }
    requireMultiset(
        "数字",
        Regex("(?<![A-Za-z0-9_])[+-]?\\d+(?:\\.\\d+)?"),
        rejectUnexpectedValues = true
    )
    requireMultiset(
        "控制标点",
        Regex("[|=@#%&*/\\\\\"+_-]"),
        rejectUnexpectedValues = true
    )
    val expectedColonCount = source.count { it == ':' || it == '：' }
    if (translated.count { it == ':' } != expectedColonCount) {
        missing += "控制标点::"
    }
    val sourceNonContractionApostrophes = source.count { it == '\'' } -
        CONTROLLED_APOSTROPHE_CONTRACTION.findAll(source).count()
    val translatedNonContractionApostrophes = translated.count { it == '\'' } -
        CONTROLLED_APOSTROPHE_CONTRACTION.findAll(translated).count()
    if (sourceNonContractionApostrophes != translatedNonContractionApostrophes) {
        missing += "控制标点:'"
    }
    val sourceClauses = source.promptClauses()
    val translatedClauses = translated.promptClauses()
    if (sourceClauses.size == translatedClauses.size) {
        sourceClauses.zip(translatedClauses).forEachIndexed { index, (sourceClause, translatedClause) ->
            if (!sourceClause.containsHanScript() && sourceClause != translatedClause) {
                missing += "子句${index + 1}:纯ASCII片段未逐字保留"
            } else if (!translatedClause.containsAsciiLiteralRunsInOrder(
                    sourceClause.asciiLiteralRuns()
                )
            ) {
                missing += "子句${index + 1}:ASCII片段顺序或大小写"
            }
            if (enforceAsciiLiteralEntityNeighborhoods) {
                missing += missingAsciiLiteralEntityNeighborhoods(
                    sourceClause = sourceClause,
                    translatedClause = translatedClause,
                    clauseNumber = index + 1
                )
            }
            val expected = sourceClause.protectedPromptFragments()
            val actual = translatedClause.protectedPromptFragments()
            if (expected.size != actual.size) {
                missing += "子句${index + 1}:保护片段数量"
                return@forEachIndexed
            }
            expected.zip(actual).forEach { (expectedFragment, actualFragment) ->
                if (expectedFragment.value.containsHanScript()) {
                    if (actualFragment.value.containsHanScript() ||
                        expectedFragment.value.promptLiteralPunctuationSignature() !=
                        actualFragment.value.promptLiteralPunctuationSignature()
                    ) {
                        missing += "子句${index + 1}:中文保护片段结构:${expectedFragment.value.take(32)}"
                    }
                    val expectedBindings = CANONICAL_TRANSLATION_ANCHORS
                        .associate { anchor ->
                            anchor.label to canonicalSourceCount(expectedFragment.value, anchor)
                        }
                        .filterValues { it > 0 }
                    val actualBindings = CANONICAL_TRANSLATION_ANCHORS
                        .associate { anchor ->
                            anchor.label to canonicalTargetCount(actualFragment.value, anchor)
                        }
                        .filterValues { it > 0 }
                    if (expectedBindings != actualBindings) {
                        missing += "子句${index + 1}:中文保护片段语义:${expectedFragment.value.take(32)}"
                    }
                    return@forEach
                }
                if (expectedFragment.value != actualFragment.value) {
                    missing += "子句${index + 1}:ASCII保护片段:${expectedFragment.value.take(32)}"
                    return@forEach
                }
                val expectedBinding = sourceClause.protectedFragmentEntityBinding(
                    expectedFragment,
                    sourceEntityOccurrencesBetween(sourceClause, 0, sourceClause.length)
                        .map { occurrence -> PromptEntityOccurrence(occurrence.anchor.label, occurrence.range) }
                )
                val actualBinding = translatedClause.protectedFragmentEntityBinding(
                    actualFragment,
                    canonicalEnglishEntityOccurrences(
                        translatedClause.maskProtectedPromptStructuresPreservingEntities(sourceClause)
                    )
                        .map { occurrence -> PromptEntityOccurrence(occurrence.anchor.label, occurrence.range) }
                )
                if (expectedBinding != actualBinding) {
                    missing += "子句${index + 1}:ASCII保护片段实体绑定:${expectedFragment.value.take(32)}"
                }
            }

            val expectedScopes = sourceClause.balancedPromptStructureFragments()
            val actualScopes = translatedClause.balancedPromptStructureFragments()
            val sourceScopeEntities = sourceEntityOccurrencesBetween(
                sourceClause,
                0,
                sourceClause.length
            ).map { occurrence ->
                PromptEntityOccurrence(occurrence.anchor.label, occurrence.range)
            }
            val targetScopeEntities = canonicalEnglishEntityOccurrences(translatedClause)
                .map { occurrence ->
                    PromptEntityOccurrence(occurrence.anchor.label, occurrence.range)
                }
            if (expectedScopes.size != actualScopes.size) {
                missing += "子句${index + 1}:嵌套保护片段数量"
            } else {
                expectedScopes.zip(actualScopes).forEachIndexed { scopeIndex, (expectedScope, actualScope) ->
                    if (!expectedScope.value.containsHanScript()) return@forEachIndexed
                    val expectedBindings = CANONICAL_TRANSLATION_ANCHORS.associate { anchor ->
                        anchor.label to canonicalSourceCount(expectedScope.value, anchor)
                    }.filterValues { count -> count > 0 }
                    val actualBindings = CANONICAL_TRANSLATION_ANCHORS.associate { anchor ->
                        anchor.label to canonicalTargetCount(actualScope.value, anchor)
                    }.filterValues { count -> count > 0 }
                    if (expectedBindings != actualBindings) {
                        missing += "子句${index + 1}:嵌套保护语义${scopeIndex + 1}"
                    }
                    val expectedEntityIdentities = sourceScopeEntities
                        .filter { entity -> expectedScope.range.contains(entity.range) }
                        .map { entity -> entity.identityIn(sourceScopeEntities) }
                    val actualEntityIdentities = targetScopeEntities
                        .filter { entity -> actualScope.range.contains(entity.range) }
                        .map { entity -> entity.identityIn(targetScopeEntities) }
                    if (expectedEntityIdentities != actualEntityIdentities) {
                        missing += "子句${index + 1}:嵌套保护实体${scopeIndex + 1}"
                    }
                    if (expectedScope.value.controlledPunctuationEntityNeighborhoods(
                            sourceLanguage = true
                        ) != actualScope.value.controlledPunctuationEntityNeighborhoods(
                            sourceLanguage = false
                        )
                    ) {
                        missing += "子句${index + 1}:嵌套控制标点位置${scopeIndex + 1}"
                    }
                }
            }
        }
    }
    return missing
}

private data class PromptEntityOccurrence(
    val label: String,
    val range: IntRange
)

private fun String.controlledPunctuationEntityNeighborhoods(
    sourceLanguage: Boolean
): List<String> {
    val entities = if (sourceLanguage) {
        canonicalSourceOccurrences(this) { anchor -> anchor.entity }
            .distinctBy { occurrence -> occurrence.anchor.label to occurrence.range }
            .map { occurrence -> PromptEntityOccurrence(occurrence.anchor.label, occurrence.range) }
    } else {
        canonicalEnglishEntityOccurrences(this)
            .map { occurrence -> PromptEntityOccurrence(occurrence.anchor.label, occurrence.range) }
    }.sortedBy { occurrence -> occurrence.range.first }
    val ordinals = mutableMapOf<Char, Int>()
    return buildList {
        this@controlledPunctuationEntityNeighborhoods.forEachIndexed { index, character ->
            if (character !in CONTROLLED_POSITION_PUNCTUATION) return@forEachIndexed
            if (character in setOf('+', '-') &&
                this@controlledPunctuationEntityNeighborhoods.getOrNull(index + 1)?.isDigit() == true
            ) return@forEachIndexed
            val ordinal = ordinals.getOrDefault(character, 0)
            ordinals[character] = ordinal + 1
            val occurrence = PromptProtectedFragment(character.toString(), index..index)
            add("$character#$ordinal:${promptFragmentEntityNeighborhood(occurrence, entities)}")
        }
    }
}

private fun PromptEntityOccurrence.identityIn(
    entities: List<PromptEntityOccurrence>
): String {
    val ordinal = entities.filter { it.label == label }
        .sortedBy { it.range.first }
        .indexOf(this)
    return "$label#$ordinal"
}

private fun promptFragmentEntityNeighborhood(
    fragment: PromptProtectedFragment,
    entities: List<PromptEntityOccurrence>
): String {
    val before = entities.lastOrNull { it.range.last < fragment.range.first }
        ?.identityIn(entities)
        ?: "none"
    val overlapping = entities.singleOrNull { it.range.overlaps(fragment.range) }
        ?.identityIn(entities)
        ?: "none"
    val after = entities.firstOrNull { it.range.first > fragment.range.last }
        ?.identityIn(entities)
        ?: "none"
    return "$before|$overlapping|$after"
}

private fun missingAsciiLiteralEntityNeighborhoods(
    sourceClause: String,
    translatedClause: String,
    clauseNumber: Int
): List<String> {
    if (!sourceClause.containsHanScript()) return emptyList()
    val expected = sourceClause.asciiLiteralFragmentsOutsideProtectedStructures()
    if (expected.isEmpty()) return emptyList()
    val sourceEntities = sourceEntityOccurrencesBetween(sourceClause, 0, sourceClause.length)
        .map { PromptEntityOccurrence(it.anchor.label, it.range) }
    val translatedEntities = canonicalEnglishEntityOccurrences(
        translatedClause.maskLiteralProtectedFragmentsDeclaredBy(sourceClause)
    )
        .map { PromptEntityOccurrence(it.anchor.label, it.range) }
    var cursor = 0
    return buildList {
        expected.forEach { fragment ->
            val expectedNeighborhood = promptFragmentEntityNeighborhood(fragment, sourceEntities)
            var candidateIndex = translatedClause.indexOf(fragment.value, startIndex = cursor)
            var accepted: PromptProtectedFragment? = null
            while (candidateIndex >= 0) {
                val candidate = PromptProtectedFragment(
                    value = fragment.value,
                    range = candidateIndex until (candidateIndex + fragment.value.length)
                )
                if (promptFragmentEntityNeighborhood(candidate, translatedEntities) ==
                    expectedNeighborhood
                ) {
                    accepted = candidate
                    break
                }
                candidateIndex = translatedClause.indexOf(
                    fragment.value,
                    startIndex = candidateIndex + 1
                )
            }
            if (accepted == null) {
                add("子句$clauseNumber:ASCII片段实体位置:${fragment.value.take(32)}")
            } else {
                cursor = accepted.range.last + 1
            }
        }
    }
}

private fun String.maskAsciiLiteralFragmentsDeclaredBy(source: String): String {
    val expected = source.asciiLiteralFragmentsOutsideProtectedStructures()
    if (expected.isEmpty()) return this
    val sourceEntities = sourceEntityOccurrencesBetween(source, 0, source.length)
        .map { occurrence -> PromptEntityOccurrence(occurrence.anchor.label, occurrence.range) }
    val translatedEntities = canonicalEnglishEntityOccurrences(this)
        .map { occurrence -> PromptEntityOccurrence(occurrence.anchor.label, occurrence.range) }
    val masked = toCharArray()
    var cursor = 0
    expected.forEach { fragment ->
        val expectedNeighborhood = promptFragmentEntityNeighborhood(fragment, sourceEntities)
        var candidateIndex = indexOf(fragment.value, startIndex = cursor)
        var accepted: PromptProtectedFragment? = null
        while (candidateIndex >= 0) {
            val candidate = PromptProtectedFragment(
                value = fragment.value,
                range = candidateIndex until (candidateIndex + fragment.value.length)
            )
            if (promptFragmentEntityNeighborhood(candidate, translatedEntities) ==
                expectedNeighborhood
            ) {
                accepted = candidate
                break
            }
            candidateIndex = indexOf(fragment.value, startIndex = candidateIndex + 1)
        }
        if (accepted != null) {
            accepted.range.forEach { index -> masked[index] = ' ' }
            val declaredEntityCounts = sourceEntities
                .filter { entity -> entity.range.overlaps(fragment.range) }
                .groupingBy { entity -> entity.label }
                .eachCount()
                .toMutableMap()
            translatedEntities
                .asSequence()
                .filter { entity -> entity.range.overlaps(accepted.range) }
                .sortedBy { entity -> entity.range.first }
                .forEach { entity ->
                    val remaining = declaredEntityCounts[entity.label] ?: 0
                    if (remaining > 0) {
                        entity.range
                            .filter { index -> index in accepted.range }
                            .forEach { index -> masked[index] = this[index] }
                        declaredEntityCounts[entity.label] = remaining - 1
                    }
                }
            cursor = accepted.range.last + 1
        }
    }
    return String(masked)
}

private fun String.protectedFragmentEntityBinding(
    fragment: PromptProtectedFragment,
    entities: List<PromptEntityOccurrence>
): String {
    if (entities.isEmpty()) return "none"
    val minimumDistance = entities.minOf { entity -> rangeDistance(fragment.range, entity.range) }
    val nearest = entities.filter { entity ->
        rangeDistance(fragment.range, entity.range) == minimumDistance
    }
    if (nearest.size != 1) return "ambiguous"
    val entity = nearest.single()
    val ordinal = entities.filter { it.label == entity.label }
        .sortedBy { it.range.first }
        .indexOf(entity)
    val relativePosition = when {
        fragment.range.last < entity.range.first -> "before"
        entity.range.last < fragment.range.first -> "after"
        else -> "overlap"
    }
    return "${entity.label}#$ordinal:$relativePosition"
}

private fun rangeDistance(first: IntRange, second: IntRange): Int = when {
    first.last < second.first -> second.first - first.last - 1
    second.last < first.first -> first.first - second.last - 1
    else -> 0
}

private fun String.asciiLiteralRuns(): List<String> = ASCII_LITERAL_RUN.findAll(this)
    .map { it.value.trim() }
    .filter { it.isNotEmpty() }
    .toList()

private fun String.containsAsciiLiteralRunsInOrder(expected: List<String>): Boolean {
    var cursor = 0
    return expected.all { literal ->
        val index = indexOf(literal, startIndex = cursor)
        if (index < 0) return@all false
        cursor = index + literal.length
        true
    }
}

private fun String.promptLiteralPunctuationSignature(): String = filter { character ->
    !character.isWhitespace() && !character.isLetter()
}.map { character ->
    when (character) {
        '（' -> '('
        '）' -> ')'
        '［' -> '['
        '］' -> ']'
        '｛' -> '{'
        '｝' -> '}'
        '＜' -> '<'
        '＞' -> '>'
        '：' -> ':'
        '，' -> ','
        '；' -> ';'
        '。' -> '.'
        '！' -> '!'
        '？' -> '?'
        else -> character
    }
}.joinToString("")

private data class CanonicalTranslationAnchor(
    val label: String,
    val sources: Set<String>,
    val targets: Set<String>,
    val negativeOperator: Boolean = false,
    val entity: Boolean = false,
    val bindingKind: CanonicalAttributeBindingKind? = null
)

private enum class CanonicalAttributeBindingKind {
    ADJACENT,
    QUANTITY,
    DIRECTION
}

private val CANONICAL_NEGATION_ANCHOR = CanonicalTranslationAnchor(
    "否定",
    setOf("不要", "避免", "不能有", "不包含"),
    setOf("no", "not", "without", "avoid", "exclude", "do not", "don't"),
    negativeOperator = true
)

private val CONTROLLED_NEGATIVE_CONDITIONING_PREFIX = Regex(
    "^(?:do\\s+not\\s+include|do\\s+not|don't\\s+include|don't|without|avoid|exclude|not|no)" +
        "\\b(?:[\\s,:;\\-]+|$)",
    RegexOption.IGNORE_CASE
)

private val CONTROLLED_APOSTROPHE_CONTRACTION = Regex(
    "(?<![a-z0-9_])don't(?:\\s+include)?(?![a-z0-9_])",
    RegexOption.IGNORE_CASE
)

private val NEGATIVE_CONDITIONING_OPERATOR = Regex(
    "(?<![a-z0-9_])(?:do\\s+not(?:\\s+include)?|don't(?:\\s+include)?|" +
        "no|not|without|avoid|exclude)(?![a-z0-9_])",
    RegexOption.IGNORE_CASE
)

private fun containsNegativeConditioningOperator(value: String): Boolean =
    NEGATIVE_CONDITIONING_OPERATOR.containsMatchIn(value)

private val PROMPT_CLAUSE_SEPARATOR = Regex("[，,；;。.!！？?\\n\\r]+")
private val PROMPT_CLAUSE_SEPARATOR_CHARACTERS =
    setOf('，', ',', '；', ';', '。', '.', '!', '！', '？', '?', '\n', '\r')
private val PROMPT_STRUCTURE_OPEN_TO_CLOSE: Map<Char, Char> = mapOf(
    '(' to ')',
    '[' to ']',
    '{' to '}',
    '<' to '>',
    '（' to '）',
    '［' to '］',
    '｛' to '｝',
    '＜' to '＞'
)
private val PROMPT_STRUCTURE_CHARACTERS: Set<Char> =
    (PROMPT_STRUCTURE_OPEN_TO_CLOSE.keys + PROMPT_STRUCTURE_OPEN_TO_CLOSE.values).toSet()
private val ENGLISH_PROMPT_TOKEN = Regex("[a-z][a-z0-9_+.-]*")
private val ASCII_LITERAL_RUN = Regex("[\\x20-\\x7e]+")
private val BARE_PROMPT_DIRECTIVE = Regex(
    "(?<![A-Za-z0-9_<>])(?:embedding|lora):[A-Za-z0-9_+./-]{1,120}" +
        "(?::[+-]?\\d+(?:\\.\\d+)?)?(?![A-Za-z0-9_])"
)
private const val SAFE_ASCII_PROMPT_PUNCTUATION = "_,.;:!?\"'+-*/\\()[]{}<>|=@#%&"
private val CONTROLLED_POSITION_PUNCTUATION =
    setOf('|', '=', '@', '#', '%', '&', '*', '/', '\\', '"', '+', '_', '-')
private val SUPPORTED_CHINESE_DIFFUSION_PROMPT_PUNCTUATION = setOf(
    '，'.code,
    '；'.code,
    '。'.code,
    '！'.code,
    '？'.code,
    '（'.code,
    '）'.code,
    '［'.code,
    '］'.code,
    '｛'.code,
    '｝'.code,
    '＜'.code,
    '＞'.code,
    '：'.code
)

internal fun String.isSafeAsciiDiffusionPrompt(): Boolean =
    codePoints().allMatch { codePoint -> isSafeAsciiDiffusionPromptCodePoint(codePoint) }

private fun isSafeAsciiDiffusionPromptCodePoint(codePoint: Int): Boolean =
    codePoint == ' '.code || codePoint == '\n'.code || codePoint == '\r'.code ||
        codePoint in 'A'.code..'Z'.code ||
        codePoint in 'a'.code..'z'.code ||
        codePoint in '0'.code..'9'.code ||
        (codePoint <= Char.MAX_VALUE.code &&
            codePoint.toChar() in SAFE_ASCII_PROMPT_PUNCTUATION)

private val CONTROLLED_ENGLISH_GRAMMAR_TOKENS = setOf(
    "a", "an", "the"
)
private const val MAX_CONTROLLED_ENTITY_PHRASE_TOKENS = 12
private val CANONICAL_ATTRIBUTE_TARGET_WORDS = setOf(
    "red", "blue", "green", "yellow", "black", "white",
    "ceramic", "porcelain", "wood", "wooden", "extra", "additional", "surplus"
)
private val CONTROLLED_INVERTED_SPATIAL_SUBJECT_PREFIX_WORDS =
    CONTROLLED_ENGLISH_GRAMMAR_TOKENS + CANONICAL_ATTRIBUTE_TARGET_WORDS +
        setOf("one", "single", "two", "three", "four")
private val CANONICAL_SINGULAR_ENTITY_TARGET_WORDS = setOf(
    "cat", "feline", "dog", "canine", "cup", "mug", "table", "desk", "tabletop",
    "apple", "person", "human", "character", "text", "word", "lettering", "writing",
    "typography", "fruit", "watermark"
)
private val CANONICAL_PLURAL_ENTITY_TARGET_WORDS = setOf(
    "cats", "felines", "dogs", "canines", "cups", "mugs", "tables", "desks", "tabletops",
    "apples", "people", "humans", "characters", "texts", "words", "fruits", "watermarks"
)
private val CANONICAL_DIRECTION_LINK_WORDS =
    CANONICAL_ATTRIBUTE_TARGET_WORDS + setOf("to", "the", "of", "on", "its")
private val DIRECTIONAL_EXISTENCE_RELATION =
    Regex("(左侧|左边|右侧|右边|上方|上面|下方|下面)(有|是)")
// These relations are evaluated after promptClauseSpans() has already split only on top-level
// separators. Their operands may therefore contain protected diffusion syntax such as
// `(blue dog:1.1)` or escaped punctuation, which must not be mistaken for another clause.
private const val CLAUSE_LOCAL_RELATION_BODY = "[\\s\\S]{0,80}?"
private val PLACED_ON_RELATION =
    Regex("(放在)$CLAUSE_LOCAL_RELATION_BODY(上面|上方|上)")
private val SUBJECT_RELATIVE_DIRECTION = Regex(
    "(在|位于)$CLAUSE_LOCAL_RELATION_BODY(左侧|左边|右侧|右边|上方|上面|下方|下面)"
)
private val SIMPLE_LOCATIVE_RELATION = Regex(
    "(在|位于)[\\s\\S]{1,80}?(上|下|里|内|外)(?:$|[，,；;。.!！？?\\n\\r])"
)
private val ADJACENCY_EXISTENCE_RELATION = Regex("(旁边)(有|是)")
private val ADJACENCY_PLACEMENT_RELATION = Regex(
    "(坐落在|放置于|位于|放在|在)[\\s\\S]{1,80}?(旁边)"
)
private val GENERIC_HAS_RELATION = Regex("有")
private val GENERIC_COPULA_RELATION = Regex("是|为")
private val CHINESE_CONJUNCTION = Regex("以及|和|与|及")
private val CHINESE_ATTRIBUTIVE_PARTICLE = Regex("的")
private val SPATIAL_EXISTENCE_PREFIXES = setOf(
    "左侧", "左边", "右侧", "右边", "上方", "上面", "下方", "下面", "旁边",
    "上", "下", "里", "内", "外"
)
private val ENGLISH_ADJACENCY_RELATION =
    Regex("(?<![a-z0-9_])(?:next\\s+to|beside|near)(?![a-z0-9_])", RegexOption.IGNORE_CASE)
private val ENGLISH_POSSESSION_RELATION =
    Regex("(?<![a-z0-9_])(?:has|have|with)(?![a-z0-9_])", RegexOption.IGNORE_CASE)
private val ENGLISH_COPULA_RELATION =
    Regex("(?<![a-z0-9_])(?:is|are)(?![a-z0-9_])", RegexOption.IGNORE_CASE)
private val ENGLISH_CONJUNCTION_RELATION =
    Regex("(?<![a-z0-9_])and(?![a-z0-9_])", RegexOption.IGNORE_CASE)
private val ENGLISH_SIMPLE_LOCATIVE_RELATION =
    Regex("(?<![a-z0-9_])(?:inside|outside|on|in)(?![a-z0-9_])", RegexOption.IGNORE_CASE)

private val CANONICAL_TRANSLATION_ANCHORS = listOf(
    CanonicalTranslationAnchor("红色", setOf("红色", "红的", "红色的", "红"), setOf("red"), bindingKind = CanonicalAttributeBindingKind.ADJACENT),
    CanonicalTranslationAnchor("蓝色", setOf("蓝色", "蓝的", "蓝色的", "蓝"), setOf("blue"), bindingKind = CanonicalAttributeBindingKind.ADJACENT),
    CanonicalTranslationAnchor("绿色", setOf("绿色", "绿的", "绿色的", "绿"), setOf("green"), bindingKind = CanonicalAttributeBindingKind.ADJACENT),
    CanonicalTranslationAnchor("黄色", setOf("黄色", "黄的", "黄色的", "黄"), setOf("yellow"), bindingKind = CanonicalAttributeBindingKind.ADJACENT),
    CanonicalTranslationAnchor("黑色", setOf("黑色", "黑的", "黑色的", "黑"), setOf("black"), bindingKind = CanonicalAttributeBindingKind.ADJACENT),
    CanonicalTranslationAnchor("白色", setOf("白色", "白的", "白色的", "白"), setOf("white"), bindingKind = CanonicalAttributeBindingKind.ADJACENT),
    CanonicalTranslationAnchor("左侧", setOf("左侧", "左边"), setOf("left"), bindingKind = CanonicalAttributeBindingKind.DIRECTION),
    CanonicalTranslationAnchor("右侧", setOf("右侧", "右边"), setOf("right"), bindingKind = CanonicalAttributeBindingKind.DIRECTION),
    CanonicalTranslationAnchor("上方", setOf("上方", "上面"), setOf("above", "over", "on top"), bindingKind = CanonicalAttributeBindingKind.DIRECTION),
    CanonicalTranslationAnchor("下方", setOf("下方", "下面"), setOf("below", "under", "beneath"), bindingKind = CanonicalAttributeBindingKind.DIRECTION),
    CanonicalTranslationAnchor("两个", setOf("两个", "两只", "两颗", "两张", "二个"), setOf("two"), bindingKind = CanonicalAttributeBindingKind.QUANTITY),
    CanonicalTranslationAnchor("三个", setOf("三个", "三只", "三颗", "三张"), setOf("three"), bindingKind = CanonicalAttributeBindingKind.QUANTITY),
    CanonicalTranslationAnchor("四个", setOf("四个", "四只", "四颗", "四张"), setOf("four"), bindingKind = CanonicalAttributeBindingKind.QUANTITY),
    CanonicalTranslationAnchor("一个", setOf("一个", "一只", "一颗", "一张"), setOf("one", "single"), bindingKind = CanonicalAttributeBindingKind.QUANTITY),
    CanonicalTranslationAnchor(
        "猫",
        setOf("猫", "猫咪"),
        setOf("cat", "cats", "feline", "felines"),
        entity = true
    ),
    CanonicalTranslationAnchor(
        "狗",
        setOf("狗", "狗狗"),
        setOf("dog", "dogs", "canine", "canines"),
        entity = true
    ),
    CanonicalTranslationAnchor(
        "杯子",
        setOf("杯子", "水杯"),
        setOf("cup", "cups", "mug", "mugs"),
        entity = true
    ),
    CanonicalTranslationAnchor(
        "桌子",
        setOf("桌子", "桌面", "木桌"),
        setOf("table", "tables", "desk", "desks", "tabletop", "tabletops"),
        entity = true
    ),
    CanonicalTranslationAnchor("苹果", setOf("苹果"), setOf("apple", "apples"), entity = true),
    CanonicalTranslationAnchor(
        "人物",
        setOf("人物", "人像", "人类"),
        setOf("person", "people", "human", "humans", "character", "characters"),
        entity = true
    ),
    CanonicalTranslationAnchor(
        "文字",
        setOf("文字", "文本", "字样"),
        setOf("text", "texts", "word", "words", "lettering", "writing", "typography"),
        entity = true
    ),
    CanonicalTranslationAnchor("水果", setOf("水果"), setOf("fruit", "fruits"), entity = true),
    CanonicalTranslationAnchor("水印", setOf("水印"), setOf("watermark", "watermarks"), entity = true),
    CanonicalTranslationAnchor("模糊", setOf("模糊"), setOf("blur", "blurry", "blurred")),
    CanonicalTranslationAnchor("低质量", setOf("低质量", "低品质"), setOf("low quality", "poor quality")),
    CanonicalTranslationAnchor("陶瓷", setOf("陶瓷"), setOf("ceramic", "porcelain"), bindingKind = CanonicalAttributeBindingKind.ADJACENT),
    CanonicalTranslationAnchor("木质", setOf("木质", "木制", "木桌"), setOf("wood", "wooden"), bindingKind = CanonicalAttributeBindingKind.ADJACENT),
    CanonicalTranslationAnchor(
        "多余",
        setOf("多余"),
        setOf("extra", "additional", "surplus"),
        bindingKind = CanonicalAttributeBindingKind.ADJACENT
    ),
    CANONICAL_NEGATION_ANCHOR
)
