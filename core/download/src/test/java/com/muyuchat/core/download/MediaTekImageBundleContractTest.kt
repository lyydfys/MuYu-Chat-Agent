package com.muyuchat.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTekImageBundleContractTest {
    @Test
    fun mediatekBundleRequiresPairedRuntimeAcceleratorAndPinnedManifestComponent() {
        val bundle = validBundle()

        assertEquals(ImageEngineBundleRuntime.MEDIATEK_NEURON, bundle.runtime)
        assertEquals(ImageEngineAccelerator.MEDIATEK_NPU, bundle.accelerator)
        assertEquals("mediatek-anima-v3.json", bundle.mediatekAnimaManifestPath)
        assertEquals(RecommendedModelSection.NPU_IMAGE, ModelScopeRecommendedModel(
            id = "anima_preview_mediatek",
            title = "MediaTek Anima",
            repoId = "test/anima",
            description = "test bundle",
            recommendedFileName = "anima.tflite",
            parameterScale = "Anima",
            quant = "fp16",
            minRamGb = 8,
            tags = emptyList(),
            priority = 1,
            kind = ModelScopeRecommendedKind.IMAGE,
            status = RecommendedModelStatus.EXPERIMENTAL,
            provider = ModelRepositoryProvider.HUGGING_FACE,
            downloadable = true,
            downloadPolicy = RecommendedModelDownloadPolicy.ALL_DEVICES,
            downloadBlockReason = null,
            localImageEngineTier = LocalImageEngineTier.HEAVY_EXPERIMENTAL,
            imageEngineBundle = bundle
        ).section)
    }

    @Test
    fun mediatekRuntimeCannotBePairedWithCpuOrADeviceTierGate() {
        val cpuAccelerator = runCatching {
            validBundle().copy(accelerator = ImageEngineAccelerator.CPU)
        }.exceptionOrNull()
        val invalidPath = runCatching {
            validBundle().copy(mediatekAnimaManifestPath = "../manifest.json")
        }.exceptionOrNull()

        assertTrue(cpuAccelerator is IllegalArgumentException)
        assertTrue(invalidPath is IllegalArgumentException)
        assertEquals(ImageEngineMinDeviceTier.ANY, validBundle().minDeviceTier)
    }

    @Test
    fun mediatekManifestMustHavePublisherSizeAndShaPins() {
        val unpinned = runCatching {
            validBundle().copy(components = listOf(
                component(
                    ImageEngineBundleComponentRole.CONFIG,
                    "mediatek-anima-v3.json",
                    expectedSizeBytes = null,
                    sha256 = null
                )
            ))
        }.exceptionOrNull()

        assertTrue(unpinned is IllegalArgumentException)
    }

    private fun validBundle() = ImageEngineBundleSpec(
        id = "anima_preview_mediatek_bundle",
        title = "Anima Preview MediaTek Neuron",
        recommendationId = "anima_preview_mediatek",
        task = ImageEngineTask.TEXT_TO_IMAGE,
        runtime = ImageEngineBundleRuntime.MEDIATEK_NEURON,
        accelerator = ImageEngineAccelerator.MEDIATEK_NPU,
        minDeviceTier = ImageEngineMinDeviceTier.ANY,
        components = listOf(
            component(ImageEngineBundleComponentRole.DIFFUSION, "graphs/anima.tflite"),
            component(ImageEngineBundleComponentRole.CONFIG, "mediatek-anima-v3.json")
        ),
        mediatekAnimaManifestPath = "mediatek-anima-v3.json",
        modelFamily = ImageEngineModelFamily.ANIMA.name
    )

    private fun component(
        role: ImageEngineBundleComponentRole,
        path: String,
        expectedSizeBytes: Long? = 1024,
        sha256: String? = "a".repeat(64)
    ) = ImageEngineBundleComponentSpec(
        role = role,
        repoId = "test/anima-neuron",
        fileName = path,
        relativePath = path,
        provider = ModelRepositoryProvider.HUGGING_FACE,
        expectedSizeBytes = expectedSizeBytes,
        sha256 = sha256
    )
}
