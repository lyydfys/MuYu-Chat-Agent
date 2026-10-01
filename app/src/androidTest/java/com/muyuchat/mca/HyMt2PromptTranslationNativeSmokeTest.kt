package com.muyuchat.mca

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HyMt2PromptTranslationNativeSmokeTest {
    @Test
    fun fullWeightTranslatesChinesePromptThroughIsolatedNativeWorker() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageRoot = File(context.getExternalFilesDir(null), "translation_seed")
        val translationDirectory = File(packageRoot, OfflinePromptTranslationContract.TRANSLATION_DIRECTORY)
        val modelFile = File(translationDirectory, HyMt2PromptTranslationContract.MODEL_FILE)
        val modelNotice = File(translationDirectory, HyMt2PromptTranslationContract.MODEL_NOTICE)
        assumeTrue(
            "Stage the pinned Hy-MT2 weight and license under app external files/translation_seed to run this smoke test.",
            modelFile.isFile && modelFile.length() == HyMt2PromptTranslationContract.MODEL_BYTES &&
                modelNotice.isFile
        )

        val manifest = File(translationDirectory, OfflinePromptTranslationContract.MANIFEST_FILE_NAME)
        if (!manifest.isFile) {
            manifest.writeText(hyMt2PinnedManifest().toString(), Charsets.UTF_8)
        }
        val runtimeNotice = File(translationDirectory, HyMt2PromptTranslationContract.RUNTIME_NOTICE)
        if (!runtimeNotice.isFile) {
            val upstreamLicense = context.assets.open("offline_translation/llama.cpp-LICENSE.txt")
                .use { it.readBytes() }
            runtimeNotice.writeBytes(runtimeNoticeFromOfficialLicense(upstreamLicense))
        }

        val bundle = OfflinePromptTranslationBundleVerifier.requireVerified(packageRoot)
        val request = OfflinePromptTranslationRequest(
            sourceText = "一只蓝色的小鸟站在枝头，背景是清晨的天空。",
            negativePrompt = "模糊，低质量"
        )
        val resolution = OfflinePromptTranslationService(
            AppOfflinePromptTranslationRuntimeProvider(context)
        ).translate(bundle, request)
        val translated = resolution as? OfflinePromptTranslationResolution.Translated
            ?: error("Hy-MT2 native smoke test failed: $resolution")
        val resultFile = File(context.cacheDir, "hy-mt2-prompt-translation-smoke.txt")
        resultFile.writeText(
            "positive=${translated.translatedPrompt}\nnegative=${translated.effectiveNegativePrompt}",
            Charsets.UTF_8
        )

        assertTrue(translated.translatedPrompt.isNotBlank())
        assertFalse(translated.translatedPrompt.any { it in '\u3400'..'\u9fff' })
        assertFalse(translated.effectiveNegativePrompt.any { it in '\u3400'..'\u9fff' })
    }
}
