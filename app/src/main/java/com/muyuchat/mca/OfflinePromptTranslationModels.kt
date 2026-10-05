package com.muyuchat.mca

import java.io.File

/**
 * Offline prompt-translation domain model types (P7/S19), moved verbatim from
 * OfflinePromptTranslation.kt: language/provenance/notice declarations, bundle identity, the
 * verified-bundle handle, and the request/result carriers. Pure declarations only.
 */

/** The only language pair currently supported by the isolated offline translator. */
internal enum class OfflinePromptTranslationLanguage(
    val wireName: String,
    val m2m100LanguageCode: String
) {
    ZH_HANS(OfflinePromptTranslationContract.SOURCE_LANGUAGE, "zh"),
    ENGLISH(OfflinePromptTranslationContract.TARGET_LANGUAGE, "en");

    companion object {
        fun fromWireName(value: String): OfflinePromptTranslationLanguage? =
            entries.firstOrNull { it.wireName == value }
    }
}

internal data class OfflinePromptTranslationProvenance(
    val sourceId: String,
    val sourceRevision: String,
    val license: String
) {
    init {
        require(sourceId.isNotBlank()) { "Translation provenance source id is required." }
        require(sourceRevision.matches(Regex("[0-9a-f]{40}"))) {
            "Translation provenance revision must be a lowercase Git SHA-1."
        }
        require(license in setOf("MIT", "Apache-2.0")) {
            "Translation provenance license must be MIT or Apache-2.0."
        }
    }
}

internal data class OfflinePromptTranslationNotice(
    val relativePath: String,
    val file: File,
    val provenance: OfflinePromptTranslationProvenance,
    val sha256: String,
    val sizeBytes: Long
) {
    init {
        require(OfflinePromptTranslationContract.SHA256_PATTERN.matches(sha256)) {
            "Translation notice SHA-256 is invalid."
        }
        require(sizeBytes > 0L) { "Translation notice must not be empty." }
    }
}

/** Identity pinned by compiled artifact anchors and by the verified local bytes. */
internal enum class OfflineTranslatorFamily { M2M100, HY_MT2 }

internal enum class OfflineTranslationRuntimeKind { CRISP_ASR_M2M100, LLAMA_CPP }

internal data class OfflinePromptTranslationBundleIdentity(
    val model: OfflinePromptTranslationProvenance,
    val runtime: OfflinePromptTranslationProvenance,
    val modelArchitecture: String,
    val modelQuantization: String,
    val sourceLanguage: OfflinePromptTranslationLanguage,
    val targetLanguage: OfflinePromptTranslationLanguage,
    val sourceM2m100LanguageCode: String,
    val targetM2m100LanguageCode: String,
    val modelSha256: String,
    val modelSizeBytes: Long,
    val nativeLibraryFileName: String,
    val notices: List<OfflinePromptTranslationNotice>,
    val translatorFamily: OfflineTranslatorFamily = OfflineTranslatorFamily.M2M100,
    val runtimeKind: OfflineTranslationRuntimeKind = OfflineTranslationRuntimeKind.CRISP_ASR_M2M100
) {
    init {
        if (translatorFamily == OfflineTranslatorFamily.HY_MT2) {
            require(runtimeKind == OfflineTranslationRuntimeKind.LLAMA_CPP)
            require(model == HyMt2PromptTranslationContract.modelProvenance)
            require(runtime == HyMt2PromptTranslationContract.runtimeProvenance)
            require(modelArchitecture == HyMt2PromptTranslationContract.ARCHITECTURE)
            require(modelQuantization == "Q4_K_M")
            require(modelSha256 == HyMt2PromptTranslationContract.MODEL_SHA256)
            require(modelSizeBytes == HyMt2PromptTranslationContract.MODEL_BYTES)
            require(nativeLibraryFileName == HyMt2PromptTranslationContract.NATIVE_LIBRARY)
            require(notices.map { it.sha256 } == listOf(
                HyMt2PromptTranslationContract.MODEL_NOTICE_SHA256,
                OfflinePromptTranslationContract.RUNTIME_NOTICE_ARTIFACT_SHA256
            ))
            require(sourceLanguage == OfflinePromptTranslationLanguage.ZH_HANS)
            require(targetLanguage == OfflinePromptTranslationLanguage.ENGLISH)
            require(sourceM2m100LanguageCode == "zh" && targetM2m100LanguageCode == "en")
        } else {
        require(runtimeKind == OfflineTranslationRuntimeKind.CRISP_ASR_M2M100)
        require(model.sourceId == OfflinePromptTranslationContract.MODEL_SOURCE_ID)
        require(model.sourceRevision == OfflinePromptTranslationContract.MODEL_SOURCE_REVISION)
        require(model.license == OfflinePromptTranslationContract.MODEL_LICENSE)
        require(runtime.sourceId == OfflinePromptTranslationContract.RUNTIME_SOURCE_ID)
        require(runtime.sourceRevision == OfflinePromptTranslationContract.RUNTIME_SOURCE_REVISION)
        require(runtime.license == OfflinePromptTranslationContract.RUNTIME_LICENSE)
        require(modelArchitecture == OfflinePromptTranslationContract.MODEL_ARCHITECTURE)
        require(modelQuantization == OfflinePromptTranslationContract.MODEL_QUANTIZATION)
        require(sourceLanguage == OfflinePromptTranslationLanguage.ZH_HANS)
        require(targetLanguage == OfflinePromptTranslationLanguage.ENGLISH)
        require(sourceM2m100LanguageCode == OfflinePromptTranslationContract.SOURCE_M2M100_LANGUAGE_CODE)
        require(targetM2m100LanguageCode == OfflinePromptTranslationContract.TARGET_M2M100_LANGUAGE_CODE)
        require(modelSha256 == OfflinePromptTranslationContract.MODEL_ARTIFACT_SHA256)
        require(modelSizeBytes == OfflinePromptTranslationContract.MODEL_ARTIFACT_SIZE_BYTES)
        require(nativeLibraryFileName == OfflinePromptTranslationContract.NATIVE_LIBRARY_FILE_NAME)
        require(notices.size == NOTICE_EXPECTATIONS.size)
        notices.zip(NOTICE_EXPECTATIONS).forEach { (notice, expectation) ->
            require(notice.relativePath == expectation.relativePath)
            require(notice.provenance == expectation.provenance)
            require(notice.sha256 == expectation.sha256)
            require(notice.sizeBytes == expectation.sizeBytes)
        }
        }
    }

    val fingerprint: String
        get() = OfflinePromptTranslationContract.sha256Utf8(
            (
                listOf(
                    "mca-offline-prompt-translation-bundle-v1",
                    model.sourceId,
                    model.sourceRevision,
                    model.license,
                    runtime.sourceId,
                    runtime.sourceRevision,
                    runtime.license,
                    modelArchitecture,
                    modelQuantization,
                    sourceLanguage.wireName,
                    targetLanguage.wireName,
                    sourceM2m100LanguageCode,
                    targetM2m100LanguageCode,
                    modelSha256,
                    modelSizeBytes.toString(),
                    nativeLibraryFileName
                ) + (if (translatorFamily == OfflineTranslatorFamily.HY_MT2) {
                    listOf(translatorFamily.name, runtimeKind.name)
                } else emptyList()) + notices.flatMap { notice ->
                    listOf(
                        notice.relativePath,
                        notice.provenance.sourceId,
                        notice.provenance.sourceRevision,
                        notice.provenance.license,
                        notice.sha256,
                        notice.sizeBytes.toString()
                    )
                }
            )
                .joinToString("\u001f")
        )
}

/**
 * Immutable output of a successful package verification. The File values are canonical paths
 * captured during verification. A future native adapter must re-run verification immediately
 * before loading if a package can change concurrently.
 */
internal class VerifiedOfflinePromptTranslationBundle internal constructor(
    val rootDirectory: File,
    val manifestFile: File,
    val modelFile: File,
    val identity: OfflinePromptTranslationBundleIdentity
) {
    private val installationSnapshot = OfflinePromptTranslationFileSnapshot.capture(
        listOf(rootDirectory, requireNotNull(manifestFile.parentFile), manifestFile, modelFile) +
            identity.notices.map(OfflinePromptTranslationNotice::file)
    )

    /** Rechecks presence, file identity and layout without repeatedly reading a large model. */
    fun matchesVerifiedFiles(): Boolean = installationSnapshot?.isCurrent() == true

    fun createResult(
        request: OfflinePromptTranslationRequest,
        translatedText: String,
        translatedNegativePrompt: String? = null
    ): OfflinePromptTranslationResult {
        require(translatedText.length <= request.maxOutputChars) {
            "Offline prompt translation output exceeds the request limit."
        }
        return OfflinePromptTranslationResult(
            requestFingerprint = request.fingerprint,
            bundleFingerprint = identity.fingerprint,
            sourceLanguage = request.sourceLanguage,
            targetLanguage = request.targetLanguage,
            translatedText = translatedText,
            translatedNegativePrompt = translatedNegativePrompt,
            maxOutputChars = request.maxOutputChars
        )
    }
}

/** A request has no implicit fallback: only zh-Hans to English is permitted. */
internal data class OfflinePromptTranslationRequest(
    val sourceText: String,
    val sourceLanguage: OfflinePromptTranslationLanguage = OfflinePromptTranslationLanguage.ZH_HANS,
    val targetLanguage: OfflinePromptTranslationLanguage = OfflinePromptTranslationLanguage.ENGLISH,
    val maxOutputChars: Int = OfflinePromptTranslationContract.MAX_OUTPUT_TEXT_CHARS,
    /** A separate branch; any translation failure retains this exact original text. */
    val negativePrompt: String = ""
) {
    init {
        require(sourceLanguage == OfflinePromptTranslationLanguage.ZH_HANS &&
            targetLanguage == OfflinePromptTranslationLanguage.ENGLISH
        ) { "Offline prompt translation only supports zh-Hans to English." }
        require(sourceText.isNotBlank() && sourceText.length <= OfflinePromptTranslationContract.MAX_SOURCE_TEXT_CHARS) {
            "Offline prompt translation source text is invalid."
        }
        require(sourceText.containsHanForOfflineTranslation()) {
            "Offline prompt translation source text must contain Han script."
        }
        require(!sourceText.containsUnsafeTranslationCharacters()) {
            "Offline prompt translation source text contains unsafe control characters."
        }
        require(negativePrompt.length <= OfflinePromptTranslationContract.MAX_SOURCE_TEXT_CHARS) {
            "Offline prompt translation negative prompt is invalid."
        }
        require(!negativePrompt.containsUnsafeTranslationCharacters()) {
            "Offline prompt translation negative prompt contains unsafe control characters."
        }
        require(maxOutputChars in 1..OfflinePromptTranslationContract.MAX_OUTPUT_TEXT_CHARS) {
            "Offline prompt translation output limit is invalid."
        }
    }

    val fingerprint: String
        get() = OfflinePromptTranslationContract.sha256Utf8(
            listOf(
                "mca-offline-prompt-translation-request-v1",
                sourceLanguage.wireName,
                targetLanguage.wireName,
                maxOutputChars.toString(),
                sourceText,
                negativePrompt
            ).joinToString("\u001f")
        )
}

/**
 * A native adapter must construct this through [VerifiedOfflinePromptTranslationBundle.createResult].
 * The constructor enforces its request-bound character limit and rejects Han script or control data.
 */
internal data class OfflinePromptTranslationResult internal constructor(
    val requestFingerprint: String,
    val bundleFingerprint: String,
    val sourceLanguage: OfflinePromptTranslationLanguage,
    val targetLanguage: OfflinePromptTranslationLanguage,
    val translatedText: String,
    val translatedNegativePrompt: String? = null,
    private val maxOutputChars: Int
) {
    init {
        require(OfflinePromptTranslationContract.SHA256_PATTERN.matches(requestFingerprint)) {
            "Offline prompt translation request fingerprint is invalid."
        }
        require(OfflinePromptTranslationContract.SHA256_PATTERN.matches(bundleFingerprint)) {
            "Offline prompt translation bundle fingerprint is invalid."
        }
        require(sourceLanguage == OfflinePromptTranslationLanguage.ZH_HANS &&
            targetLanguage == OfflinePromptTranslationLanguage.ENGLISH
        ) { "Offline prompt translation result has an unsupported language pair." }
        require(maxOutputChars in 1..OfflinePromptTranslationContract.MAX_OUTPUT_TEXT_CHARS) {
            "Offline prompt translation result output limit is invalid."
        }
        require(translatedText.isNotBlank() &&
            translatedText.length <= maxOutputChars
        ) { "Offline prompt translation output is invalid." }
        require(!translatedText.containsHanForOfflineTranslation()) {
            "Offline prompt translation output must not contain Han script."
        }
        require(!translatedText.containsUnsafeTranslationCharacters()) {
            "Offline prompt translation output contains unsafe control characters."
        }
        require(translatedText.isSafeAsciiDiffusionPrompt()) {
            "Offline prompt translation output must use safe ASCII diffusion prompt syntax."
        }
        require(translatedNegativePrompt == null ||
            (translatedNegativePrompt.isNotBlank() &&
                translatedNegativePrompt.length <= maxOutputChars &&
                !translatedNegativePrompt.containsHanForOfflineTranslation() &&
                !translatedNegativePrompt.containsUnsafeTranslationCharacters() &&
                translatedNegativePrompt.isSafeAsciiDiffusionPrompt())
        ) { "Offline prompt translation negative output is invalid." }
    }

    fun matches(
        bundle: VerifiedOfflinePromptTranslationBundle,
        request: OfflinePromptTranslationRequest
    ): Boolean = requestFingerprint == request.fingerprint &&
        bundleFingerprint == bundle.identity.fingerprint &&
        sourceLanguage == request.sourceLanguage &&
        targetLanguage == request.targetLanguage &&
        maxOutputChars == request.maxOutputChars &&
        translatedText.length <= request.maxOutputChars

    /**
     * Rebuilds a result without bypassing request binding and ASCII/output limits. Missing user
     * control syntax is never appended or guessed by the service.
     */
    internal fun withTranslatedText(value: String): OfflinePromptTranslationResult =
        OfflinePromptTranslationResult(
            requestFingerprint = requestFingerprint,
            bundleFingerprint = bundleFingerprint,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            translatedText = value,
            translatedNegativePrompt = translatedNegativePrompt,
            maxOutputChars = maxOutputChars
        )

    internal fun withTranslatedNegativePrompt(value: String): OfflinePromptTranslationResult =
        OfflinePromptTranslationResult(
            requestFingerprint = requestFingerprint,
            bundleFingerprint = bundleFingerprint,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            translatedText = translatedText,
            translatedNegativePrompt = value,
            maxOutputChars = maxOutputChars
        )
}
