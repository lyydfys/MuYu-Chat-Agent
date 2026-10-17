package com.muyuchat.mca

import com.muyuchat.api.local.imagePromptExecutionSha256
import com.muyuchat.api.local.imagePromptTranslationProofFingerprint
import org.json.JSONObject
import com.muyuchat.core.engine.isNativeChineseHanCodePoint
import com.muyuchat.core.engine.isSafeAsciiDiffusionPromptCodePoint

internal const val CURRENT_LOCAL_IMAGE_PROMPT_TRANSLATION_CONTRACT_VERSION = 4
internal const val CURRENT_OFFLINE_IMAGE_PROMPT_TRANSLATION_CONTRACT_VERSION = 5
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
    OFFLINE_HY_MT2_ZH_TO_EN,
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
            message = "当前模型只接受英文提示词。请将正向和负向提示词改写为英文后重试；" +
                "本次尚未启动图片生成。"
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
        method == LocalImagePromptTransformationMethod.LOCAL_LLM_ZH_TO_EN ||
            method == LocalImagePromptTransformationMethod.OFFLINE_HY_MT2_ZH_TO_EN
    ) {
        if (method == LocalImagePromptTransformationMethod.OFFLINE_HY_MT2_ZH_TO_EN) {
            CURRENT_OFFLINE_IMAGE_PROMPT_TRANSLATION_CONTRACT_VERSION
        } else CURRENT_LOCAL_IMAGE_PROMPT_TRANSLATION_CONTRACT_VERSION
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
        } else if (method == LocalImagePromptTransformationMethod.OFFLINE_HY_MT2_ZH_TO_EN) {
            require(originalPrompt.containsHanScript() ||
                originalNegativePrompt?.containsHanScript() == true ||
                negativePromptSource == LocalImageNegativePromptSource.MODEL_DEFAULT
            )
            require(translationContractVersion == CURRENT_OFFLINE_IMAGE_PROMPT_TRANSLATION_CONTRACT_VERSION)
            require(translatorModelId == HyMt2PromptTranslationContract.SOURCE_ID)
            require(translatorModelName == "Hy-MT2 1.8B Q4_K_M")
            require(translatorRuntime == "llama.cpp/offline_translation")
            require(translatorModelSha256 == HyMt2PromptTranslationContract.MODEL_SHA256)
            require(translationPlanSha256?.let(SHA256_PATTERN::matches) == true)
            require(verificationReceiptSha256?.let(SHA256_PATTERN::matches) == true)
            require(translationPhaseSystemPromptSha256?.let(SHA256_PATTERN::matches) == true)
            require(verificationPhaseSystemPromptSha256?.let(SHA256_PATTERN::matches) == true)
            require(translationProofFingerprint?.let(SHA256_PATTERN::matches) == true)
            require(effectivePrompt.isSafeAsciiDiffusionPrompt() &&
                effectiveNegativePrompt.isSafeAsciiDiffusionPrompt() &&
                !effectivePrompt.containsHanScript() &&
                !effectiveNegativePrompt.containsHanScript()
            )
            require(!originalPrompt.containsHanScript() || effectivePrompt != originalPrompt)
            if (originalNegativePrompt != null && !originalNegativePrompt.containsHanScript()) {
                require(effectiveNegativePrompt == originalNegativePrompt)
            }
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
            ) { "Offline translated prompt proof fingerprint does not match its bound evidence." }
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
            LocalImagePromptTransformationMethod.OFFLINE_HY_MT2_ZH_TO_EN -> Unit
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
                method == LocalImagePromptTransformationMethod.OFFLINE_HY_MT2_ZH_TO_EN ||
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
    } else if (method == LocalImagePromptTransformationMethod.OFFLINE_HY_MT2_ZH_TO_EN) {
        LocalImageFinalNegativePrompt(
            value = effectiveNegativePrompt,
            source = finalNegativePrompt.source
        )
    } else {
        require(originalNegativePrompt == null &&
            finalNegativePrompt.source != LocalImageNegativePromptSource.USER
        ) { "Captured model negative prompt no longer matches the request." }
        finalNegativePrompt
    }
    val reboundProof = if (method == LocalImagePromptTransformationMethod.LOCAL_LLM_ZH_TO_EN ||
        method == LocalImagePromptTransformationMethod.OFFLINE_HY_MT2_ZH_TO_EN
    ) {
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
        LocalImagePromptTransformationMethod.DIRECT_UTF8_PASSTHROUGH -> error(
            "Legacy UTF-8 pass-through evidence cannot validate a current image execution."
        )
        LocalImagePromptTransformationMethod.LOCAL_LLM_ZH_TO_EN -> error(
            "Legacy chat-model translation evidence cannot validate a current image execution."
        )
        LocalImagePromptTransformationMethod.OFFLINE_HY_MT2_ZH_TO_EN -> require(
            expectedCapability == LocalImageTextEncoderLanguageCapability.ENGLISH_DOMINANT &&
                (promptExecution.originalPrompt.containsHanScript() ||
                    promptExecution.originalNegativePrompt?.containsHanScript() == true ||
                    promptExecution.negativePromptSource ==
                    LocalImageNegativePromptSource.MODEL_DEFAULT) &&
                promptExecution.effectivePrompt.isSafeAsciiDiffusionPrompt() &&
                promptExecution.effectiveNegativePrompt.isSafeAsciiDiffusionPrompt() &&
                !promptExecution.effectivePrompt.containsHanScript() &&
                !promptExecution.effectiveNegativePrompt.containsHanScript()
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
