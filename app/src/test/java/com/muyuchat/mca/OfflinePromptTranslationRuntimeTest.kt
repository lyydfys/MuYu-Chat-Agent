package com.muyuchat.mca

import java.io.File

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePromptTranslationRuntimeTest {
    @Test
    fun `missing bundle falls back without changing positive negative or protected syntax`() = runBlocking {
        val request = OfflinePromptTranslationRequest(
            sourceText = "一位女孩 <lora:portrait:0.8>",
            negativePrompt = "低质量, blurry"
        )

        val result = OfflinePromptTranslationService().translate(null, request)

        assertTrue(result is OfflinePromptTranslationResolution.Fallback)
        val fallback = result as OfflinePromptTranslationResolution.Fallback
        assertEquals(request.sourceText, fallback.originalPrompt)
        assertEquals(request.negativePrompt, fallback.originalNegativePrompt)
        assertEquals(OfflinePromptTranslationFallbackReason.BUNDLE_UNAVAILABLE, fallback.reason)
        assertTrue(fallback.protectedTokens.contains("<lora:portrait:0.8>"))
    }

    @Test
    fun `protected token extraction removes nested duplicates and keeps weighted syntax`() {
        val tokens = extractOfflinePromptProtectedTokens(
            "<lora:portrait:0.8> lora:portrait (masterpiece:1.2)"
        )

        assertTrue(tokens.contains("<lora:portrait:0.8>"))
        assertTrue(tokens.contains("(masterpiece:1.2)"))
        assertTrue(tokens.none { it == "lora:portrait" })
    }

    @Test
    fun `negative prompt participates in request identity`() {
        val positive = "一位女孩"
        val first = OfflinePromptTranslationRequest(sourceText = positive, negativePrompt = "blurry")
        val second = OfflinePromptTranslationRequest(sourceText = positive, negativePrompt = "low quality")

        assertTrue(first.fingerprint != second.fingerprint)
    }

    @Test
    fun `default provider remains explicit unavailable and does not claim native execution`() {
        assertEquals(
            OfflinePromptTranslationContract.NATIVE_LIBRARY_FILE_NAME,
            UnavailableOfflinePromptTranslationRuntime.nativeLibraryFileName
        )
        assertEquals(
            UnavailableOfflinePromptTranslationRuntime,
            DefaultOfflinePromptTranslationRuntimeProvider.runtimeFor(verifiedBundle())
        )
    }

    @Test
    fun `runtime adapter must match the verified bundle identity`() = runBlocking {
        val runtime = object : OfflinePromptTranslationRuntime {
            override val nativeLibraryFileName = "lib-wrong-model.so"
            override suspend fun translate(
                bundle: VerifiedOfflinePromptTranslationBundle,
                request: OfflinePromptTranslationRequest
            ) = error("must not be called")
        }
        val result = OfflinePromptTranslationService(
            runtimeProvider = OfflinePromptTranslationRuntimeProvider { runtime }
        ).translate(verifiedBundle(), OfflinePromptTranslationRequest("一只蓝色小鸟"))

        assertTrue(result is OfflinePromptTranslationResolution.Fallback)
        assertEquals(
            OfflinePromptTranslationFallbackReason.NATIVE_RUNTIME_UNSUPPORTED,
            (result as OfflinePromptTranslationResolution.Fallback).reason
        )
    }

    @Test
    fun `concurrent translation is rejected without racing native runtime`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runtime = object : OfflinePromptTranslationRuntime {
            override val nativeLibraryFileName = OfflinePromptTranslationContract.NATIVE_LIBRARY_FILE_NAME
            override suspend fun translate(
                bundle: VerifiedOfflinePromptTranslationBundle,
                request: OfflinePromptTranslationRequest
            ): OfflinePromptTranslationRuntimeOutcome {
                started.complete(Unit)
                release.await()
                return OfflinePromptTranslationRuntimeOutcome.Failed("controlled test failure")
            }
        }
        val service = OfflinePromptTranslationService(
            runtimeProvider = OfflinePromptTranslationRuntimeProvider { runtime },
            timeoutMs = 2_000
        )
        val first = launch {
            service.translate(verifiedBundle(), OfflinePromptTranslationRequest("一只蓝色小鸟"))
        }
        started.await()
        val second = service.translate(verifiedBundle(), OfflinePromptTranslationRequest("一只红色小鸟"))
        assertTrue(second is OfflinePromptTranslationResolution.Fallback)
        assertEquals(
            OfflinePromptTranslationFallbackReason.RUNTIME_BUSY,
            (second as OfflinePromptTranslationResolution.Fallback).reason
        )
        release.complete(Unit)
        first.join()
    }

    @Test
    fun `translated output restores lora and weight tokens and preserves negative text`() = runBlocking {
        val bundle = verifiedBundle()
        val request = OfflinePromptTranslationRequest(
            sourceText = "一位女孩 <lora:portrait:0.8> (masterpiece:1.2)",
            negativePrompt = "blurry, low quality"
        )
        val runtime = object : OfflinePromptTranslationRuntime {
            override val nativeLibraryFileName = OfflinePromptTranslationContract.NATIVE_LIBRARY_FILE_NAME
            override suspend fun translate(
                bundle: VerifiedOfflinePromptTranslationBundle,
                request: OfflinePromptTranslationRequest
            ) = OfflinePromptTranslationRuntimeOutcome.Translated(
                bundle.createResult(request, "A portrait of a young woman")
            )
        }
        val service = OfflinePromptTranslationService(
            runtimeProvider = OfflinePromptTranslationRuntimeProvider { runtime }
        )

        val result = service.translate(bundle, request)

        assertTrue(result is OfflinePromptTranslationResolution.Translated)
        val translated = result as OfflinePromptTranslationResolution.Translated
        assertTrue(translated.translatedPrompt.contains("<lora:portrait:0.8>"))
        assertTrue(translated.translatedPrompt.contains("(masterpiece:1.2)"))
        assertEquals(request.sourceText, translated.originalPrompt)
        assertEquals(request.negativePrompt, translated.originalNegativePrompt)
        assertEquals(request.negativePrompt, translated.effectiveNegativePrompt)
    }

    @Test
    fun `timeout returns fallback and leaves both original prompts intact`() = runBlocking {
        val request = OfflinePromptTranslationRequest("一只蓝色小鸟", negativePrompt = "模糊")
        val runtime = object : OfflinePromptTranslationRuntime {
            override val nativeLibraryFileName = OfflinePromptTranslationContract.NATIVE_LIBRARY_FILE_NAME
            override suspend fun translate(
                bundle: VerifiedOfflinePromptTranslationBundle,
                request: OfflinePromptTranslationRequest
            ): OfflinePromptTranslationRuntimeOutcome {
                delay(500)
                error("timeout test runtime should not finish")
            }
        }
        val service = OfflinePromptTranslationService(
            runtimeProvider = OfflinePromptTranslationRuntimeProvider { runtime },
            timeoutMs = 10
        )

        val result = service.translate(verifiedBundle(), request)

        assertTrue(result is OfflinePromptTranslationResolution.Fallback)
        val fallback = result as OfflinePromptTranslationResolution.Fallback
        assertEquals(OfflinePromptTranslationFallbackReason.TIMEOUT, fallback.reason)
        assertEquals(request.sourceText, fallback.originalPrompt)
        assertEquals(request.negativePrompt, fallback.originalNegativePrompt)
    }

    @Test
    fun `caller cancellation propagates instead of being converted to fallback`() = runBlocking {
        val runtime = object : OfflinePromptTranslationRuntime {
            override val nativeLibraryFileName = OfflinePromptTranslationContract.NATIVE_LIBRARY_FILE_NAME
            override suspend fun translate(
                bundle: VerifiedOfflinePromptTranslationBundle,
                request: OfflinePromptTranslationRequest
            ): OfflinePromptTranslationRuntimeOutcome {
                delay(30_000)
                error("cancel test runtime should not finish")
            }
        }
        val service = OfflinePromptTranslationService(
            runtimeProvider = OfflinePromptTranslationRuntimeProvider { runtime },
            timeoutMs = 60_000
        )
        val job = launch { service.translate(verifiedBundle(), OfflinePromptTranslationRequest("一只蓝色小鸟")) }
        delay(10)
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }

    private fun verifiedBundle(): VerifiedOfflinePromptTranslationBundle {
        val model = OfflinePromptTranslationProvenance(
            OfflinePromptTranslationContract.MODEL_SOURCE_ID,
            OfflinePromptTranslationContract.MODEL_SOURCE_REVISION,
            OfflinePromptTranslationContract.MODEL_LICENSE
        )
        val runtime = OfflinePromptTranslationProvenance(
            OfflinePromptTranslationContract.RUNTIME_SOURCE_ID,
            OfflinePromptTranslationContract.RUNTIME_SOURCE_REVISION,
            OfflinePromptTranslationContract.RUNTIME_LICENSE
        )
        val notices = listOf(
            OfflinePromptTranslationNotice(
                OfflinePromptTranslationContract.MODEL_NOTICE_RELATIVE_PATH,
                File(OfflinePromptTranslationContract.MODEL_NOTICE_FILE_NAME),
                model,
                OfflinePromptTranslationContract.MODEL_NOTICE_ARTIFACT_SHA256,
                OfflinePromptTranslationContract.MODEL_NOTICE_ARTIFACT_SIZE_BYTES
            ),
            OfflinePromptTranslationNotice(
                OfflinePromptTranslationContract.RUNTIME_NOTICE_RELATIVE_PATH,
                File(OfflinePromptTranslationContract.RUNTIME_NOTICE_FILE_NAME),
                runtime,
                OfflinePromptTranslationContract.RUNTIME_NOTICE_ARTIFACT_SHA256,
                OfflinePromptTranslationContract.RUNTIME_NOTICE_ARTIFACT_SIZE_BYTES
            )
        )
        val identity = OfflinePromptTranslationBundleIdentity(
            model = model,
            runtime = runtime,
            modelArchitecture = OfflinePromptTranslationContract.MODEL_ARCHITECTURE,
            modelQuantization = OfflinePromptTranslationContract.MODEL_QUANTIZATION,
            sourceLanguage = OfflinePromptTranslationLanguage.ZH_HANS,
            targetLanguage = OfflinePromptTranslationLanguage.ENGLISH,
            sourceM2m100LanguageCode = OfflinePromptTranslationContract.SOURCE_M2M100_LANGUAGE_CODE,
            targetM2m100LanguageCode = OfflinePromptTranslationContract.TARGET_M2M100_LANGUAGE_CODE,
            modelSha256 = OfflinePromptTranslationContract.MODEL_ARTIFACT_SHA256,
            modelSizeBytes = OfflinePromptTranslationContract.MODEL_ARTIFACT_SIZE_BYTES,
            nativeLibraryFileName = OfflinePromptTranslationContract.NATIVE_LIBRARY_FILE_NAME,
            notices = notices
        )
        return VerifiedOfflinePromptTranslationBundle(
            rootDirectory = File("verified-translation-bundle"),
            manifestFile = File("translation_manifest.json"),
            modelFile = File(OfflinePromptTranslationContract.MODEL_FILE_NAME),
            identity = identity
        )
    }
}
