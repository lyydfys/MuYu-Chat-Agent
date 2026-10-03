package com.muyuchat.mca

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What translating an image prompt offline needs from its owner.
 *
 * Four members: the published state and its two routes, the translation service, and the verified
 * bundle lookup that the service is fed. The three user-visible strings this uses
 * (`LOCAL_IMAGE_PROMPT_TRANSLATION_*`) are top-level declarations in this package, so they stay
 * direct references and no port member is needed for them.
 */
internal interface ImagePromptTranslationPreviewPort {

    fun uiState(): MainUiState

    fun updateUiState(transform: (MainUiState) -> MainUiState)

    /** The offline translation service, lazily owned by the ViewModel. */
    val offlinePromptTranslationService: OfflinePromptTranslationService

    /** Resolves (and verifies) the installed offline translation bundle, or null when unusable. */
    suspend fun verifiedOfflineTranslationBundle(): VerifiedOfflinePromptTranslationBundle?
}

/** Positive-prompt preview. Same wording, same worker, same status messages as before the move. */
internal suspend fun translateImagePromptPreview(
    port: ImagePromptTranslationPreviewPort,
    prompt: String
): String = translateImagePromptTextPreview(port, prompt, "正向提示词")

/** Negative-prompt preview. */
internal suspend fun translateImageNegativePromptPreview(
    port: ImagePromptTranslationPreviewPort,
    prompt: String
): String = translateImagePromptTextPreview(port, prompt, "负面提示词")

/**
 * Translates one prompt field through the offline Hy-MT2 bundle and publishes the draft.
 *
 * Moved out of `MainViewModel` whole (59 lines). The body is the original, line for line: the same
 * `withContext(Dispatchers.IO)` hop, the same three `require` guards and their exact wording, the
 * same cleanup/cancellation handling (including rethrowing `CancellationException`), the same
 * translated/fallback branches and the same five status publications. Only the owner interactions
 * became [port] calls.
 */
internal suspend fun translateImagePromptTextPreview(
    port: ImagePromptTranslationPreviewPort,
    prompt: String,
    fieldLabel: String
): String = withContext(Dispatchers.IO) {
        val source = prompt.trim()
        require(source.isNotBlank()) { "请先输入图片提示词。" }
        require(source.containsHanScript()) { "当前提示词没有中文内容。" }
        require(source.length <= OfflinePromptTranslationContract.MAX_SOURCE_TEXT_CHARS) {
            "提示词超过离线翻译长度限制，请精简后重试。"
        }
        val request = try {
            offlineImagePromptTranslationRequest(prompt = source, finalNegativePrompt = "")
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("提示词格式不受支持，请检查内容后重试。", error)
        }
        port.updateUiState {
            it.copy(offlineTranslationStatus = "Hy-MT2 正在翻译$fieldLabel")
        }
        val translationResolution = try {
            port.offlinePromptTranslationService.translate(
                port.verifiedOfflineTranslationBundle(),
                request
            )
        } catch (error: OfflinePromptTranslationCleanupException) {
            port.updateUiState {
                it.copy(
                    offlineTranslationStatus = LOCAL_IMAGE_PROMPT_TRANSLATION_RELEASE_UNCONFIRMED_STATUS,
                    statusMessage = LOCAL_IMAGE_PROMPT_TRANSLATION_RELEASE_UNCONFIRMED_MESSAGE
                )
            }
            throw IllegalStateException(LOCAL_IMAGE_PROMPT_TRANSLATION_RELEASE_UNCONFIRMED_MESSAGE, error)
        } catch (error: CancellationException) {
            port.updateUiState {
                it.copy(
                    offlineTranslationStatus = LOCAL_IMAGE_PROMPT_TRANSLATION_CANCELLED_STATUS,
                    statusMessage = LOCAL_IMAGE_PROMPT_TRANSLATION_CANCELLED_STATUS
                )
            }
            throw error
        }
        when (val resolution = translationResolution) {
            is OfflinePromptTranslationResolution.Translated -> {
                val translated = resolution.translatedPrompt
                val preview = translated.replace('\n', ' ').replace('\r', ' ').trim().take(96)
                port.updateUiState {
                    it.copy(
                        offlineTranslationStatus = "$fieldLabel 英文译文：$preview${if (translated.length > 96) "..." else ""}",
                        statusMessage = "Hy-MT2 已完成$fieldLabel 离线翻译；英文草稿已生成，可编辑并确认使用。"
                    )
                }
                translated
            }
            is OfflinePromptTranslationResolution.Fallback -> {
                val failureMessage = "Hy-MT2 翻译失败：${resolution.message}；原始提示词已保留。"
                port.updateUiState {
                    it.copy(
                        offlineTranslationStatus = failureMessage,
                        statusMessage = failureMessage
                    )
                }
                throw IllegalStateException(failureMessage)
            }
        }
}
