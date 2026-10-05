package com.muyuchat.mca

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Fixed package contract for the optional, explicit zh-Hans to English translator.
 *
 * This is intentionally separate from image-model profiles and from the V4/chat path. A bundle
 * that passes this verifier is only layout and integrity verified; it does not prove that the
 * separately packaged native runtime can load or execute on the current device.
 */
internal object OfflinePromptTranslationContract {
    const val MANIFEST_CONTRACT_VERSION = 1
    const val MANIFEST_KIND = "mca-offline-prompt-translation"

    const val TRANSLATION_DIRECTORY = "translation"
    const val MANIFEST_FILE_NAME = "translation_manifest.json"
    const val MANIFEST_RELATIVE_PATH = "$TRANSLATION_DIRECTORY/$MANIFEST_FILE_NAME"
    const val MODEL_FILE_NAME = "m2m100-418m-q4_k.gguf"
    const val MODEL_RELATIVE_PATH = "$TRANSLATION_DIRECTORY/$MODEL_FILE_NAME"
    const val MODEL_NOTICE_FILE_NAME = "NOTICE.facebook-m2m100-418m-MIT.txt"
    const val MODEL_NOTICE_RELATIVE_PATH = "$TRANSLATION_DIRECTORY/$MODEL_NOTICE_FILE_NAME"
    const val RUNTIME_NOTICE_FILE_NAME = "NOTICE.crispstrobe-crispasr-MIT.txt"
    const val RUNTIME_NOTICE_RELATIVE_PATH = "$TRANSLATION_DIRECTORY/$RUNTIME_NOTICE_FILE_NAME"

    const val MODEL_SOURCE_ID = "facebook/m2m100_418M"
    const val MODEL_SOURCE_REVISION = "55c2e61bbf05dfb8d7abccdc3fae6fc8512fd636"
    const val MODEL_LICENSE = "MIT"
    const val MODEL_ARCHITECTURE = "M2M100"
    const val MODEL_QUANTIZATION = "Q4_K"
    // These byte pins are the trust anchors; package manifests only bind the fixed layout to them.
    const val MODEL_ARTIFACT_SHA256 =
        "b3360f7a416f43f1631fd1888bf11d80ee3876d4683d3de99a00bcd238ed08e2"
    const val MODEL_ARTIFACT_SIZE_BYTES = 284_568_416L
    const val MODEL_NOTICE_ARTIFACT_SHA256 =
        "1fd660f130aedc5ecf1796b47ab47d43d3c4b36541f5387b7adb3df75cb5dfdb"
    const val MODEL_NOTICE_ARTIFACT_SIZE_BYTES = 4_603L

    const val RUNTIME_SOURCE_ID = "CrispStrobe/CrispASR"
    const val RUNTIME_SOURCE_REVISION = "7ed71ce78720650d362c202e43ce6a7ddfec71a8"
    const val RUNTIME_LICENSE = "MIT"
    const val RUNTIME_BACKEND = "CrispASR_M2M100_GGUF"
    const val NATIVE_LIBRARY_FILE_NAME = "libmca_translation_native.so"
    const val RUNTIME_NOTICE_ARTIFACT_SHA256 =
        "bcd8ec749126d45cb06737d0690295d73df4b6e7e194205bcf91190368f27285"
    const val RUNTIME_NOTICE_ARTIFACT_SIZE_BYTES = 1_099L

    const val SOURCE_LANGUAGE = "zh-Hans"
    const val TARGET_LANGUAGE = "en"
    const val SOURCE_M2M100_LANGUAGE_CODE = "zh"
    const val TARGET_M2M100_LANGUAGE_CODE = "en"

    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_NOTICE_BYTES = 1 * 1024 * 1024
    const val MAX_MODEL_BYTES = 8L * 1024L * 1024L * 1024L
    const val MAX_SOURCE_TEXT_CHARS = 4 * 1024
    const val MAX_OUTPUT_TEXT_CHARS = 8 * 1024

    val SHA256_PATTERN: Regex = Regex("[0-9a-f]{64}")
    private val SAFE_FILE_NAME_PATTERN: Regex = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    fun isSafeFileName(value: String): Boolean =
        SAFE_FILE_NAME_PATTERN.matches(value) && !value.contains("..")

    fun sha256Utf8(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .toLowercaseHex()
}

/**
 * Coordinates one verified runtime invocation. It provides timeout/cancellation boundaries and
 * a safe fallback without coupling to the chat model lifecycle. A CancellationException from the
 * caller is rethrown so stop/back navigation remains coroutine-owned; only a timeout is converted
 * to a user-visible fallback.
 */
internal class OfflinePromptTranslationService(
    private val runtimeProvider: OfflinePromptTranslationRuntimeProvider =
        DefaultOfflinePromptTranslationRuntimeProvider,
    private val timeoutMs: Long? = null
) {
    /**
     * Offline translation owns a heavyweight native session. Do not let two image requests race the same
     * bundle/runtime and exhaust the app's memory.  A busy translator is a normal, recoverable
     * state; the caller keeps the editable original prompt instead of waiting behind an
     * unrelated request.
     */
    private val invocationGate = Mutex()

    init {
        require(timeoutMs == null || timeoutMs > 0L) { "Offline prompt translation timeout must be positive." }
    }

    suspend fun translate(
        bundle: VerifiedOfflinePromptTranslationBundle?,
        request: OfflinePromptTranslationRequest
    ): OfflinePromptTranslationResolution {
        val positivePlan = runCatching { parseOfflinePromptTranslationSyntax(request.sourceText) }
        val negativePlan = runCatching { parseOfflinePromptTranslationSyntax(request.negativePrompt) }
        val protectedTokens = positivePlan.getOrNull()?.protectedTokens.orEmpty() +
            negativePlan.getOrNull()?.protectedTokens.orEmpty()
        if (bundle == null) {
            return fallback(
                request = request,
                protectedTokens = protectedTokens,
                reason = OfflinePromptTranslationFallbackReason.BUNDLE_UNAVAILABLE,
                message = OfflinePromptTranslationFallbackMessages.BUNDLE_UNAVAILABLE
            )
        }

        val syntaxFailure = positivePlan.exceptionOrNull() ?: negativePlan.exceptionOrNull()
        if (syntaxFailure != null) {
            return fallback(
                request = request,
                protectedTokens = protectedTokens,
                reason = OfflinePromptTranslationFallbackReason.PROTECTED_SYNTAX_LOST,
                message = "提示词结构无法安全解析，已保留原始提示词。",
                detail = syntaxFailure.message,
                stage = "syntax_validation",
                errorCode = (syntaxFailure as? OfflinePromptTranslationSyntaxException)?.errorCode
            )
        }

        val runtime = try {
            runtimeProvider.runtimeFor(bundle)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return fallback(
                request = request,
                protectedTokens = protectedTokens,
                reason = OfflinePromptTranslationFallbackReason.NATIVE_RUNTIME_NOT_INITIALIZED,
                message = OfflinePromptTranslationFallbackMessages.NATIVE_RUNTIME_NOT_INITIALIZED,
                detail = error.message,
                stage = "initialization",
                errorCode = "translation_runtime_initialization_failed"
            )
        }

        // Runtime adapters are injected so the contract module does not load arbitrary .so files.
        // Still bind the adapter to the verified bundle here: an adapter for another model family
        // must never be allowed to claim that the verified translation package was executed.
        if (runtime.nativeLibraryFileName != bundle.identity.nativeLibraryFileName) {
            return fallback(
                request = request,
                protectedTokens = protectedTokens,
                reason = OfflinePromptTranslationFallbackReason.NATIVE_RUNTIME_UNSUPPORTED,
                message = OfflinePromptTranslationFallbackMessages.NATIVE_RUNTIME_UNSUPPORTED,
                stage = "initialization",
                errorCode = "translation_runtime_identity_mismatch"
            )
        }

        if (!invocationGate.tryLock()) {
            return fallback(
                request = request,
                protectedTokens = protectedTokens,
                reason = OfflinePromptTranslationFallbackReason.RUNTIME_BUSY,
                message = OfflinePromptTranslationFallbackMessages.NATIVE_RUNTIME_BUSY,
                stage = "runtime",
                errorCode = "translation_runtime_busy"
            )
        }

        val outcome = try {
            val deadline = timeoutMs ?: if (bundle.identity.translatorFamily == OfflineTranslatorFamily.HY_MT2) {
                HyMt2PromptTranslationContract.TIMEOUT_MS
            } else DEFAULT_OFFLINE_PROMPT_TRANSLATION_TIMEOUT_MS
            withTimeout(deadline) { runtime.translate(bundle, request) }
        } catch (_: TimeoutCancellationException) {
            return fallback(
                request = request,
                protectedTokens = protectedTokens,
                reason = OfflinePromptTranslationFallbackReason.TIMEOUT,
                message = OfflinePromptTranslationFallbackMessages.TIMEOUT,
                stage = "runtime",
                errorCode = "translation_timeout"
            )
        } catch (error: CancellationException) {
            // Do not turn a user stop into a normal prompt result. The parent job owns cancel.
            throw error
        } catch (error: OfflinePromptTranslationCleanupException) {
            // A fallback may start a large image runtime, so it requires confirmed release.
            throw error
        } catch (error: Throwable) {
            return fallback(
                request = request,
                protectedTokens = protectedTokens,
                reason = OfflinePromptTranslationFallbackReason.RUNTIME_FAILED,
                message = OfflinePromptTranslationFallbackMessages.FAILED,
                detail = error.message,
                stage = "runtime",
                errorCode = "translation_runtime_exception"
            )
        } finally {
            invocationGate.unlock()
        }

        return when (outcome) {
            is OfflinePromptTranslationRuntimeOutcome.Unavailable -> fallback(
                request = request,
                protectedTokens = protectedTokens,
                reason = outcome.reason.toFallbackReason(),
                message = outcome.reason.toFallbackMessage(),
                detail = outcome.message,
                stage = "initialization",
                errorCode = "translation_" + outcome.reason.name.lowercase(java.util.Locale.ROOT)
            )

            is OfflinePromptTranslationRuntimeOutcome.Failed -> fallback(
                request = request,
                protectedTokens = protectedTokens,
                reason = OfflinePromptTranslationFallbackReason.RUNTIME_FAILED,
                message = OfflinePromptTranslationFallbackMessages.FAILED,
                detail = outcome.message,
                stage = outcome.stage,
                errorCode = outcome.errorCode,
                nativeCode = outcome.nativeCode
            )

            is OfflinePromptTranslationRuntimeOutcome.Translated ->
                validateTranslatedResult(bundle, request, outcome.result, protectedTokens)
        }
    }

    private fun validateTranslatedResult(
        bundle: VerifiedOfflinePromptTranslationBundle,
        request: OfflinePromptTranslationRequest,
        result: OfflinePromptTranslationResult,
        protectedTokens: List<String>
    ): OfflinePromptTranslationResolution {
        if (!result.matches(bundle, request)) {
            return fallback(
                request = request,
                protectedTokens = protectedTokens,
                reason = OfflinePromptTranslationFallbackReason.INVALID_OUTPUT,
                message = OfflinePromptTranslationFallbackMessages.INVALID_OUTPUT,
                stage = "output_validation",
                errorCode = "translation_result_identity_mismatch"
            )
        }
        val positiveMissing = !hasMatchingOfflinePromptStructure(request.sourceText, result.translatedText)
        val translatedNegative = result.translatedNegativePrompt
            ?: if (request.negativePrompt.containsHanForOfflineTranslation()) {
                return fallback(
                    request = request,
                    protectedTokens = protectedTokens,
                    reason = OfflinePromptTranslationFallbackReason.INVALID_OUTPUT,
                    message = OfflinePromptTranslationFallbackMessages.INVALID_OUTPUT,
                    stage = "output_validation",
                    errorCode = "translation_negative_output_missing"
                )
            } else {
                request.negativePrompt
            }
        val negativeMissing = !hasMatchingOfflinePromptStructure(request.negativePrompt, translatedNegative)
        if (!positiveMissing && !negativeMissing) {
            return OfflinePromptTranslationResolution.Translated(
                originalPrompt = request.sourceText,
                translatedPrompt = result.translatedText,
                originalNegativePrompt = request.negativePrompt,
                effectiveNegativePrompt = translatedNegative,
                result = result,
                protectedTokens = protectedTokens
            )
        }

        return fallback(
            request = request,
            protectedTokens = protectedTokens,
            reason = OfflinePromptTranslationFallbackReason.PROTECTED_SYNTAX_LOST,
            message = OfflinePromptTranslationFallbackMessages.PROTECTED_SYNTAX_LOST,
            stage = "output_validation",
            errorCode = when {
                positiveMissing && negativeMissing -> "translation_both_branches_structure_changed"
                positiveMissing -> "translation_positive_structure_changed"
                else -> "translation_negative_structure_changed"
            }
        )
    }

    private fun fallback(
        request: OfflinePromptTranslationRequest,
        protectedTokens: List<String>,
        reason: OfflinePromptTranslationFallbackReason,
        message: String,
        detail: String? = null,
        stage: String? = null,
        errorCode: String? = null,
        nativeCode: Int? = null
    ): OfflinePromptTranslationResolution.Fallback {
        val safeStage = stage?.takeIf { it.matches(Regex("[a-z0-9_]{1,48}")) }
        val safeCode = errorCode?.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,96}")) }
        val safeDetail = sanitizeOfflinePromptTranslationFailureDetail(
            detail, listOf(request.sourceText, request.negativePrompt)
        )
        val diagnostic = buildList {
            safeStage?.let { add("阶段=$it") }
            safeCode?.let { add("错误码=$it") }
            nativeCode?.let { add("native=$it") }
        }.joinToString("，")
        val fullMessage = buildString {
            append(message)
            if (diagnostic.isNotEmpty()) append(" ").append(diagnostic).append("。")
            if (safeDetail.isNotBlank()) append(" 原因：").append(safeDetail)
        }
        return OfflinePromptTranslationResolution.Fallback(
            originalPrompt = request.sourceText,
            originalNegativePrompt = request.negativePrompt,
            protectedTokens = protectedTokens,
            reason = reason,
            message = fullMessage,
            stage = safeStage,
            errorCode = safeCode,
            nativeCode = nativeCode
        )
    }

    private fun OfflinePromptTranslationUnavailableReason.toFallbackReason():
        OfflinePromptTranslationFallbackReason = when (this) {
        OfflinePromptTranslationUnavailableReason.NATIVE_LIBRARY_NOT_PACKAGED ->
            OfflinePromptTranslationFallbackReason.NATIVE_LIBRARY_NOT_PACKAGED
        OfflinePromptTranslationUnavailableReason.NATIVE_RUNTIME_NOT_INITIALIZED ->
            OfflinePromptTranslationFallbackReason.NATIVE_RUNTIME_NOT_INITIALIZED
        OfflinePromptTranslationUnavailableReason.NATIVE_RUNTIME_UNSUPPORTED ->
            OfflinePromptTranslationFallbackReason.NATIVE_RUNTIME_UNSUPPORTED
    }

    private fun OfflinePromptTranslationUnavailableReason.toFallbackMessage(): String = when (this) {
        OfflinePromptTranslationUnavailableReason.NATIVE_LIBRARY_NOT_PACKAGED ->
            OfflinePromptTranslationFallbackMessages.NATIVE_LIBRARY_NOT_PACKAGED
        OfflinePromptTranslationUnavailableReason.NATIVE_RUNTIME_NOT_INITIALIZED ->
            OfflinePromptTranslationFallbackMessages.NATIVE_RUNTIME_NOT_INITIALIZED
        OfflinePromptTranslationUnavailableReason.NATIVE_RUNTIME_UNSUPPORTED ->
            OfflinePromptTranslationFallbackMessages.NATIVE_RUNTIME_UNSUPPORTED
    }

    private companion object {
        const val DEFAULT_OFFLINE_PROMPT_TRANSLATION_TIMEOUT_MS = 15_000L
    }
}

/** Translation errors can echo a short language slot; even one-character sources are private. */
internal fun sanitizeOfflinePromptTranslationFailureDetail(detail: String?, sourceTexts: List<String>): String {
    val literals = sourceTexts + sourceTexts.flatMap { source ->
        runCatching { parseOfflinePromptTranslationSyntax(source).parts }.getOrDefault(emptyList())
            .filter { it.kind == null }.map { it.text.trim() }
    }
    val redacted = literals.asSequence().map(String::trim).filter(String::isNotEmpty).distinct()
        .sortedByDescending(String::length).fold(detail.orEmpty().take(8_192)) { value, literal ->
            value.replace(literal, "<prompt-redacted>")
        }
    return LocalDiagnosticRedactor.sanitize(redacted)
        .replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").take(600)
}

/**
 * The only runtime supplied by this contract file. It intentionally does not try to load a
 * library, so callers receive an explicit unavailable result until an isolated native adapter is
 * implemented and packaged.
 */
internal object UnavailableOfflinePromptTranslationRuntime : OfflinePromptTranslationRuntime {
    override val nativeLibraryFileName: String = OfflinePromptTranslationContract.NATIVE_LIBRARY_FILE_NAME

    override suspend fun translate(
        bundle: VerifiedOfflinePromptTranslationBundle,
        request: OfflinePromptTranslationRequest
    ): OfflinePromptTranslationRuntimeOutcome {
        // Touch the fixed identity so an adapter cannot accidentally use this stub for another bundle.
        require(bundle.identity.nativeLibraryFileName == nativeLibraryFileName)
        require(request.sourceLanguage == OfflinePromptTranslationLanguage.ZH_HANS)
        require(request.targetLanguage == OfflinePromptTranslationLanguage.ENGLISH)
        return OfflinePromptTranslationRuntimeOutcome.Unavailable(
            reason = OfflinePromptTranslationUnavailableReason.NATIVE_LIBRARY_NOT_PACKAGED,
            message = "Offline zh-Hans to English translation is not installed."
        )
    }
}

// P7/S18: shared with OfflinePromptTranslationBundleVerification.kt and
// OfflinePromptTranslationBundleInstaller.kt (its file-private duplicate was removed; identical output).
internal fun ByteArray.toLowercaseHex(): String = buildString(size * 2) {
    this@toLowercaseHex.forEach { byte ->
        append((byte.toInt() ushr 4).and(0x0f).toString(16))
        append((byte.toInt() and 0x0f).toString(16))
    }
}
