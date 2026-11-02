package com.muyuchat.mca

import com.muyuchat.core.mediatek.MediaTekAotManifestJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTekAotExecutionGatingTest {
    private val manifest = MediaTekAotManifestJson.parse(
        javaClass.getResourceAsStream("/npug/reference-aot-manifest.json")!!.bufferedReader().use { it.readText() },
    )

    @Test
    fun fixedContextBundleAcceptsAPromptButNeverAppliesIt() {
        val error = contractError {
            execute(
                bundleRoot = File("unused"),
                prompt = "a cat",
                options = LocalImageGenerationOptions(width = 512, height = 512, steps = 6, seed = 1),
            )
        }
        // Prompt gating no longer blocks the request; the next concrete failure is the
        // missing pinned asset. The prompt is recorded as suppressed in result metadata.
        assertEquals("mediatek_aot_asset_invalid", error.code)
    }

    @Test
    fun fixedContextFallbackStillRejectsNegativePromptsAndCfg() {
        val negative = contractError {
            execute(
                bundleRoot = File("unused"),
                prompt = "a cat",
                options = LocalImageGenerationOptions(
                    negativePrompt = "blurry",
                    width = 512,
                    height = 512,
                    steps = 6,
                    seed = 1,
                ),
            )
        }
        assertEquals("mediatek_aot_unsupported_request", negative.code)
        assertTrue(negative.message, negative.message.contains("negativePrompt"))
    }

    @Test
    fun unsupportedStepCountIsRejectedByTheProfileSelector() {
        val error = contractError {
            execute(
                bundleRoot = File("unused"),
                prompt = "",
                options = LocalImageGenerationOptions(width = 512, height = 512, steps = 4, seed = 1),
            )
        }
        assertEquals("mediatek_aot_profile_unsupported", error.code)
    }

    @Test
    fun unsupportedCfgRangeIsRejectedByTheParameterContract() {
        val error = contractError {
            execute(
                bundleRoot = File("unused"),
                prompt = "",
                options = LocalImageGenerationOptions(width = 512, height = 512, steps = 6, cfgScale = 7.0, seed = 1),
            )
        }
        assertEquals("mediatek_aot_unsupported_request", error.code)
        assertTrue(error.message, error.message.contains("cfgScale"))
    }

    @Test
    fun cancellationIsCheckedBeforeRouteLoading() {
        try {
            execute(bundleRoot = File("unused"), prompt = "", isCancelled = { true })
            throw AssertionError("expected cancellation")
        } catch (expected: LocalImageWorkerCancelledException) {
            assertTrue(expected.message.orEmpty().isNotEmpty())
        }
    }

    @Test
    fun missingRouteAssetIsReportedAsAContractError() {
        val root = kotlin.io.path.createTempDirectory("mca-aot-gate").toFile()
        try {
            val error = contractError { execute(bundleRoot = root, prompt = "") }
            assertEquals("mediatek_aot_asset_invalid", error.code)
            assertTrue(error.message, error.message.contains("asset is missing or unpinned"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun execute(
        bundleRoot: File,
        prompt: String,
        options: LocalImageGenerationOptions = LocalImageGenerationOptions(
            width = 512,
            height = 512,
            steps = 6,
            seed = 1,
        ),
        isCancelled: () -> Boolean = { false },
    ): LocalImageResult = executeMediaTekAotGeneration(
        bundleRoot = bundleRoot,
        manifest = manifest,
        prompt = prompt,
        options = options,
        isCancellationRequested = isCancelled,
        onProgress = {},
    )

    private fun contractError(action: () -> Unit): LocalImageProductContractException {
        try {
            action()
        } catch (error: LocalImageProductContractException) {
            return error
        }
        throw AssertionError("expected a LocalImageProductContractException")
    }
}
