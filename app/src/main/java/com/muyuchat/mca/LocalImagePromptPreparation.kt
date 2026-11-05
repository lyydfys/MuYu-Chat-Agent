package com.muyuchat.mca

import com.muyuchat.api.local.imagePromptTranslationProofFingerprint
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * The three things the prompt-preparation decision needs from its owner.
 *
 * Deliberately small: the caller supplies the verified translation bundle, the translation call and
 * the status publication. Everything else this file does is a top-level function, which is why 314
 * lines could leave `MainViewModel` without dragging state ownership along.
 */
internal interface ImagePromptTranslationPort {
    /** The verified (and cached) Hy-MT2 bundle, or null when none is installed. */
    suspend fun verifiedBundle(): VerifiedOfflinePromptTranslationBundle?

    /** Runs the offline translation through the single shared service. */
    suspend fun translate(
        bundle: VerifiedOfflinePromptTranslationBundle?,
        request: OfflinePromptTranslationRequest
    ): OfflinePromptTranslationResolution

    /**
     * Publishes a translation status line.
     *
     * The implementation still reads the live active image-job id and patches that job's message, so
     * the job-updating behaviour is unchanged; only the state holder moved back to the caller.
     */
    fun publishTranslationStatus(status: String, taskMessage: String)
}

/**
 * Prepares the prompt pair a local image request will actually encode.
 *
 * Moved verbatim out of `MainViewModel` (314 lines, one state write, two member calls). The body is
 * the original logic: same admission checks, same Hy-MT2 decision, same exception codes and messages,
 * same call points for the status publication. Only the three owner interactions became [port] calls.
 *
 * Lives in the app package for now so the move needs no import churn; tightening the package is a
 * later step, recorded as this slice's exit condition.
 */
internal suspend fun prepareLocalImagePromptExecution(
    port: ImagePromptTranslationPort,
    model: LocalImageModelRecord,
    prompt: String,
    options: LocalImageGenerationOptions,
    captured: LocalImagePromptExecution?
): LocalImagePromptExecution {
        if (prompt.length > LocalImagePromptExecution.MAX_ORIGINAL_PROMPT_CHARS ||
            (options.negativePrompt?.length ?: 0) >
            LocalImagePromptExecution.MAX_ORIGINAL_PROMPT_CHARS
        ) {
            throw LocalImageProductContractException(
                code = "invalid_image_prompt",
                message = "图片提示词超过 ${LocalImagePromptExecution.MAX_ORIGINAL_PROMPT_CHARS} 字符上限。"
            )
        }
        // The Neuron runtime executes the bundle's own v3/v4 manifest and owns its tokenizer and
        // text-conditioning graph. It has no ImageExecutionProfile, so it must not be forced
        // through the stable-diffusion prompt/profile machinery.
        if (model.runtime == LocalImageRuntime.MEDIATEK_NEURON) {
            return prepareMediaTekImagePromptExecution(model, prompt, options)
        }
        val profileOptions = options.normalizedForPromptExecutionProfile(model.runtime)
        val bundleRoot = model.bundleRoot
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.takeIf(File::isDirectory)
            ?: File(model.path).parentFile?.takeIf(File::isDirectory)
        val profile = resolveLocalImageExecutionProfile(
            model = model,
            options = profileOptions,
            bundleRoot = bundleRoot
        ).profile
        val languageCapability = profile.textEncoderLanguageCapability()
        val resolvedFinalNegativePrompt = resolveLocalImageFinalNegativePrompt(
            userNegativePrompt = options.negativePrompt,
            modelDefaultNegativePrompt = profile.defaults.defaultNegativePrompt
        )
        val finalNegativePrompt = resolveLocalImageFinalNegativePromptForExecution(
            finalNegativePrompt = resolvedFinalNegativePrompt,
            useCfg = profile.defaults.useCfg
        )
        val positiveContainsHan = prompt.containsHanScript()
        val negativeContainsHan = finalNegativePrompt.value.containsHanScript()
        val shouldTranslateWithHyMt2 =
            languageCapability == LocalImageTextEncoderLanguageCapability.ENGLISH_DOMINANT &&
                (positiveContainsHan || negativeContainsHan)
        // Validate the exact pair the selected CFG topology will encode. In particular, a model
        // default negative prompt discarded by useCfg=false must not reject an otherwise valid
        // positive prompt before native execution begins.
        if (!shouldTranslateWithHyMt2) {
            requireLocalImagePromptLanguageAdmission(
                profile = profile,
                prompt = prompt.trim(),
                executedNegativePrompt = finalNegativePrompt.value
            )
        }
        // The default negative prompt is also native conditioning input. Classify the exact
        // final pair so any admitted non-ASCII native input cannot be recorded as DIRECT.
        val requiresNativeMultilingualPromptExecution =
            prompt.requiresNativeMultilingualPromptExecution() ||
                finalNegativePrompt.value.requiresNativeMultilingualPromptExecution()
        if (requiresNativeMultilingualPromptExecution &&
            languageCapability == LocalImageTextEncoderLanguageCapability.NATIVE_MULTILINGUAL
        ) {
            val evidenceRoot = bundleRoot ?: throw LocalImageProductContractException(
                code = "invalid_image_prompt_language_evidence",
                message = "原生中文文本编码器证据目录不可用，尚未启动图片生成。"
            )
            requireNativeMultilingualTextEncoderEvidenceAsset(
                bundleRoot = evidenceRoot,
                profile = profile
            )
        }
        fun effectiveExecutionProfile(effectiveNegativePrompt: String): ImageExecutionProfile {
            val effectiveProfile = resolveLocalImageExecutionProfile(
                model = model,
                options = profileOptions.copy(negativePrompt = effectiveNegativePrompt),
                bundleRoot = bundleRoot
            ).profile
            check(
                effectiveProfile.promptLanguageBindingFingerprint ==
                    profile.promptLanguageBindingFingerprint
            ) { "Effective prompt options changed the text-encoder language topology." }
            return effectiveProfile
        }
        if (finalNegativePrompt.value.length > LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS) {
            throw LocalImageProductContractException(
                code = "invalid_image_prompt",
                message = "图片模型的最终负向提示词超过 " +
                    "${LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS} 字符上限。"
            )
        }
        val requiredMethod = requiredLocalImagePromptTransformationMethod(
            containsChinese = requiresNativeMultilingualPromptExecution,
            languageCapability = languageCapability
        )
        val translationRequest = if (shouldTranslateWithHyMt2) {
            runCatching {
                offlineImagePromptTranslationRequest(
                    prompt = prompt.trim(),
                    finalNegativePrompt = finalNegativePrompt.value
                )
            }.getOrElse { error ->
                throw LocalImageProductContractException(
                    code = "image_prompt_translation_input_too_large",
                    message = error.message ?: "离线翻译输入无效，尚未启动图片生成。"
                )
            }
        } else {
            null
        }
        captured?.let { execution ->
            require(execution.originalPrompt == prompt &&
                execution.originalNegativePrompt == options.negativePrompt
            ) { "Captured image prompt execution does not match the request." }
            if (execution.promptLanguageBindingFingerprint ==
                profile.promptLanguageBindingFingerprint && execution.method == requiredMethod &&
                (execution.method != LocalImagePromptTransformationMethod.OFFLINE_HY_MT2_ZH_TO_EN ||
                    execution.translationPlanSha256 == translationRequest?.fingerprint)
            ) {
                val rebound = if (execution.method ==
                    LocalImagePromptTransformationMethod.OFFLINE_HY_MT2_ZH_TO_EN
                ) {
                    // The request fingerprint already binds the raw effective negative. Keep its
                    // translated value when replaying; the generic rebinder treats model defaults
                    // as untransformed native text.
                    execution.copy(imageProfileBindingFingerprint = profile.bindingFingerprint)
                } else {
                    execution.rebindToCurrentImageProfile(
                        finalNegativePrompt = finalNegativePrompt,
                        imageProfileBindingFingerprint = profile.bindingFingerprint,
                        promptLanguageBindingFingerprint = profile.promptLanguageBindingFingerprint
                    )
                }
                val effectiveProfile = effectiveExecutionProfile(rebound.effectiveNegativePrompt)
                return rebound.copy(
                    imageProfileBindingFingerprint = effectiveProfile.bindingFingerprint,
                    promptLanguageBindingFingerprint =
                        effectiveProfile.promptLanguageBindingFingerprint
                )
            }
        }

        if (requiredMethod in setOf(
                LocalImagePromptTransformationMethod.DIRECT,
                LocalImagePromptTransformationMethod.NATIVE_MULTILINGUAL
            )
        ) {
            val effectiveProfile = effectiveExecutionProfile(finalNegativePrompt.value)
            return LocalImagePromptExecution(
                originalPrompt = prompt,
                effectivePrompt = prompt,
                originalNegativePrompt = options.negativePrompt,
                effectiveNegativePrompt = finalNegativePrompt.value,
                negativePromptSource = finalNegativePrompt.source,
                method = requiredMethod,
                imageProfileBindingFingerprint = effectiveProfile.bindingFingerprint,
                promptLanguageBindingFingerprint = effectiveProfile.promptLanguageBindingFingerprint
            )
        }

        check(requiredMethod == LocalImagePromptTransformationMethod.OFFLINE_HY_MT2_ZH_TO_EN)
        val request = requireNotNull(translationRequest)
        port.publishTranslationStatus(
            status = "Hy-MT2 正在真实翻译中文提示词",
            taskMessage = "正在使用 Hy-MT2 将中文提示词翻译为英文；图片生成尚未开始。"
        )
        val translationResolution = try {
            port.translate(
                port.verifiedBundle(),
                request
            )
        } catch (error: OfflinePromptTranslationCleanupException) {
            port.publishTranslationStatus(
                status = LOCAL_IMAGE_PROMPT_TRANSLATION_RELEASE_UNCONFIRMED_STATUS,
                taskMessage = LOCAL_IMAGE_PROMPT_TRANSLATION_RELEASE_UNCONFIRMED_MESSAGE
            )
            throw LocalImageProductContractException(
                code = LOCAL_IMAGE_PROMPT_TRANSLATION_RELEASE_UNCONFIRMED_CODE,
                message = LOCAL_IMAGE_PROMPT_TRANSLATION_RELEASE_UNCONFIRMED_MESSAGE
            )
        } catch (error: CancellationException) {
            port.publishTranslationStatus(
                status = LOCAL_IMAGE_PROMPT_TRANSLATION_CANCELLED_STATUS,
                taskMessage = LOCAL_IMAGE_PROMPT_TRANSLATION_CANCELLED_STATUS
            )
            throw error
        }
        val translation = when (val resolution = translationResolution) {
            is OfflinePromptTranslationResolution.Translated -> resolution
            is OfflinePromptTranslationResolution.Fallback -> {
                val failureCode = when (resolution.reason) {
                    OfflinePromptTranslationFallbackReason.RUNTIME_BUSY ->
                        "image_prompt_translation_busy"
                    OfflinePromptTranslationFallbackReason.TIMEOUT ->
                        "image_prompt_translation_timeout"
                    OfflinePromptTranslationFallbackReason.INVALID_OUTPUT,
                    OfflinePromptTranslationFallbackReason.PROTECTED_SYNTAX_LOST ->
                        "image_prompt_translation_invalid"
                    OfflinePromptTranslationFallbackReason.BUNDLE_UNAVAILABLE,
                    OfflinePromptTranslationFallbackReason.NATIVE_LIBRARY_NOT_PACKAGED,
                    OfflinePromptTranslationFallbackReason.NATIVE_RUNTIME_NOT_INITIALIZED,
                    OfflinePromptTranslationFallbackReason.NATIVE_RUNTIME_UNSUPPORTED ->
                        "image_prompt_translation_unavailable"
                    OfflinePromptTranslationFallbackReason.RUNTIME_FAILED ->
                        "image_prompt_translation_failed"
                }
                val failureMessage = "Hy-MT2 离线翻译未能完成（${resolution.reason.name}），原始提示词已保留，" +
                    "尚未启动图片生成。请检查翻译模型后重试。"
                port.publishTranslationStatus(
                    status = "Hy-MT2 翻译失败：${resolution.reason.name}",
                    taskMessage = failureMessage
                )
                throw LocalImageProductContractException(
                    code = failureCode,
                    message = failureMessage
                )
            }
        }
        val effectivePrompt: String
        val effectiveNegativePrompt: String
        if (positiveContainsHan) {
            effectivePrompt = translation.translatedPrompt
            effectiveNegativePrompt = if (negativeContainsHan) {
                translation.effectiveNegativePrompt
            } else {
                finalNegativePrompt.value
            }
        } else {
            effectivePrompt = prompt.trim()
            effectiveNegativePrompt = translation.translatedPrompt
        }
        requireLocalImagePromptLanguageAdmission(
            profile = profile,
            prompt = effectivePrompt,
            executedNegativePrompt = effectiveNegativePrompt
        )
        if (effectivePrompt.length > LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS ||
            effectiveNegativePrompt.length > LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS
        ) {
            throw LocalImageProductContractException(
                code = "image_prompt_translation_invalid",
                message = "Hy-MT2 翻译结果超过图片模型长度限制，尚未启动图片生成。"
            )
        }
        val effectiveProfile = effectiveExecutionProfile(effectiveNegativePrompt)
        val translationProof = imagePromptTranslationProofFingerprint(
            contractVersion = 5,
            originalPrompt = prompt,
            effectivePrompt = effectivePrompt,
            originalNegativePrompt = options.negativePrompt,
            effectiveNegativePrompt = effectiveNegativePrompt,
            negativePromptSource = finalNegativePrompt.source.name,
            translationPlanSha256 = translation.result.requestFingerprint,
            verificationReceiptSha256 = translation.result.bundleFingerprint,
            translationPhaseSystemPromptSha256 = promptFingerprint(
                "mca-offline-hy-mt2-translation-phase-v1"
            ),
            verificationPhaseSystemPromptSha256 = promptFingerprint(
                "mca-offline-hy-mt2-output-validation-v1"
            ),
            translatorRuntime = "llama.cpp/offline_translation",
            translatorModelSha256 = HyMt2PromptTranslationContract.MODEL_SHA256,
            promptLanguageBindingFingerprint = effectiveProfile.promptLanguageBindingFingerprint
        )
        val execution = LocalImagePromptExecution(
            originalPrompt = prompt,
            effectivePrompt = effectivePrompt,
            originalNegativePrompt = options.negativePrompt,
            effectiveNegativePrompt = effectiveNegativePrompt,
            negativePromptSource = finalNegativePrompt.source,
            method = LocalImagePromptTransformationMethod.OFFLINE_HY_MT2_ZH_TO_EN,
            translationContractVersion = CURRENT_OFFLINE_IMAGE_PROMPT_TRANSLATION_CONTRACT_VERSION,
            imageProfileBindingFingerprint = effectiveProfile.bindingFingerprint,
            promptLanguageBindingFingerprint = effectiveProfile.promptLanguageBindingFingerprint,
            translatorModelId = HyMt2PromptTranslationContract.SOURCE_ID,
            translatorModelName = "Hy-MT2 1.8B Q4_K_M",
            translatorRuntime = "llama.cpp/offline_translation",
            translatorModelSha256 = HyMt2PromptTranslationContract.MODEL_SHA256,
            translationPlanSha256 = translation.result.requestFingerprint,
            verificationReceiptSha256 = translation.result.bundleFingerprint,
            translationPhaseSystemPromptSha256 = promptFingerprint(
                "mca-offline-hy-mt2-translation-phase-v1"
            ),
            verificationPhaseSystemPromptSha256 = promptFingerprint(
                "mca-offline-hy-mt2-output-validation-v1"
            ),
            translationProofFingerprint = translationProof
        )
        val translatedTextForDisplay = if (positiveContainsHan) effectivePrompt else effectiveNegativePrompt
        val translatedPreview = translatedTextForDisplay
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .take(96)
        val previewSuffix = if (translatedTextForDisplay.length > 96) "..." else ""
        port.publishTranslationStatus(
            status = "英文译文：$translatedPreview$previewSuffix",
            taskMessage = "中文提示词已转换为英文并用于本地生图：$translatedPreview$previewSuffix"
        )
        return execution
}
