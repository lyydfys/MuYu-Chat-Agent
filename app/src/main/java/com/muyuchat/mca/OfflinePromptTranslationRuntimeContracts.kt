package com.muyuchat.mca

/**
 * Offline prompt-translation runtime contracts (P7/S19), moved verbatim from
 * OfflinePromptTranslation.kt: the isolated native boundary (`OfflinePromptTranslationRuntime`),
 * the provider hook, unavailable/fallback reasons, resolution outcomes and fallback messages.
 */

/**
 * Isolated native boundary. Each adapter must match the verified bundle's explicit model family
 * and runtime kind. It cannot borrow an active chat context or implicitly use cloud translation.
 */
internal interface OfflinePromptTranslationRuntime {
    val nativeLibraryFileName: String

    suspend fun translate(
        bundle: VerifiedOfflinePromptTranslationBundle,
        request: OfflinePromptTranslationRequest
    ): OfflinePromptTranslationRuntimeOutcome
}

internal enum class OfflinePromptTranslationUnavailableReason {
    NATIVE_LIBRARY_NOT_PACKAGED,
    NATIVE_RUNTIME_NOT_INITIALIZED,
    NATIVE_RUNTIME_UNSUPPORTED
}

internal sealed interface OfflinePromptTranslationRuntimeOutcome {
    data class Translated(val result: OfflinePromptTranslationResult) :
        OfflinePromptTranslationRuntimeOutcome

    data class Unavailable(
        val reason: OfflinePromptTranslationUnavailableReason,
        val message: String
    ) : OfflinePromptTranslationRuntimeOutcome

    data class Failed(
        val message: String,
        val stage: String? = null,
        val errorCode: String? = null,
        val nativeCode: Int? = null
    ) : OfflinePromptTranslationRuntimeOutcome
}

/**
 * Runtime lookup is deliberately injected. The contract file does not load a .so by itself and
 * therefore cannot accidentally claim that Hy-MT2, M2M100, or any other package is executable.
 */
internal fun interface OfflinePromptTranslationRuntimeProvider {
    fun runtimeFor(bundle: VerifiedOfflinePromptTranslationBundle): OfflinePromptTranslationRuntime
}

internal object DefaultOfflinePromptTranslationRuntimeProvider : OfflinePromptTranslationRuntimeProvider {
    override fun runtimeFor(bundle: VerifiedOfflinePromptTranslationBundle): OfflinePromptTranslationRuntime =
        UnavailableOfflinePromptTranslationRuntime
}

internal enum class OfflinePromptTranslationFallbackReason {
    BUNDLE_UNAVAILABLE,
    NATIVE_LIBRARY_NOT_PACKAGED,
    NATIVE_RUNTIME_NOT_INITIALIZED,
    NATIVE_RUNTIME_UNSUPPORTED,
    RUNTIME_BUSY,
    TIMEOUT,
    RUNTIME_FAILED,
    INVALID_OUTPUT,
    PROTECTED_SYNTAX_LOST
}

/**
 * Result consumed by the image prompt layer. A fallback is intentionally not represented as a
 * translated result: callers can then decide whether to invoke the existing chat-model bridge
 * or ask the user to edit the prompt, while retaining the exact original positive and negative
 * text. No fallback silently submits Chinese to an English-only encoder.
 */
internal sealed interface OfflinePromptTranslationResolution {
    data class Translated(
        val originalPrompt: String,
        val translatedPrompt: String,
        val originalNegativePrompt: String,
        val effectiveNegativePrompt: String,
        val result: OfflinePromptTranslationResult,
        val protectedTokens: List<String>
    ) : OfflinePromptTranslationResolution

    data class Fallback(
        val originalPrompt: String,
        val originalNegativePrompt: String,
        val protectedTokens: List<String>,
        val reason: OfflinePromptTranslationFallbackReason,
        /** Stable summary plus bounded, prompt/path/secret-redacted runtime diagnostics. */
        val message: String,
        val stage: String? = null,
        val errorCode: String? = null,
        val nativeCode: Int? = null
    ) : OfflinePromptTranslationResolution
}

internal object OfflinePromptTranslationFallbackMessages {
    const val BUNDLE_UNAVAILABLE = "没有可核验的本地翻译模型，已保留原始提示词。"
    const val NATIVE_LIBRARY_NOT_PACKAGED = "本地离线翻译运行时未随应用安装，已保留原始提示词。"
    const val NATIVE_RUNTIME_NOT_INITIALIZED = "本地离线翻译运行时尚未初始化，已保留原始提示词。"
    const val NATIVE_RUNTIME_UNSUPPORTED = "当前本地翻译运行时不支持此模型，已保留原始提示词。"
    const val NATIVE_RUNTIME_BUSY = "本地离线翻译正在处理另一项请求，已保留原始提示词。"
    /** Kept for callers that only need a generic label. */
    const val NATIVE_UNAVAILABLE = "本地离线翻译运行时不可用，已保留原始提示词。"
    const val TIMEOUT = "中文提示词转换超时，已保留原始提示词。"
    const val FAILED = "中文提示词转换失败，已保留原始提示词。"
    const val INVALID_OUTPUT = "翻译结果未通过格式校验，已保留原始提示词。"
    const val PROTECTED_SYNTAX_LOST = "翻译结果未完整保留控制语法、权重或数字，已保留原始提示词。"
}
