package com.muyuchat.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Qwen35Mnn36RecommendationContractTest {
    private val standard = DEFAULT_RECOMMENDED_MODELS.single { it.id == "qwen35_08b_mnn36" }
    private val legacy = DEFAULT_RECOMMENDED_MODELS.single { it.id == "qwen35_08b_uncensored_mnn" }

    @Test
    fun standardRecommendationAndBundleShareImmutableModelScopeSource() {
        val bundle = requireNotNull(standard.mnnModelBundle)
        assertEquals("qwen35_08b_mnn36_bundle", bundle.id)
        assertEquals("cix/Qwen3.5-0.8B-mnn", standard.repoId)
        assertEquals("47f681e8e87d8b2f57ea697a9fe78136d1ec0951", standard.revision)
        assertEquals(standard.repoId, bundle.repoId)
        assertEquals(standard.revision, bundle.revision)
        assertEquals(ModelRepositoryProvider.MODELSCOPE, standard.provider)
        assertEquals(standard.provider, bundle.provider)
        assertEquals(RecommendedChatRuntime.MNN, standard.chatRuntime)
        assertEquals("config.json", standard.recommendedFileName)
        assertTrue(standard.title.contains("标准版"))
        assertFalse((standard.title + standard.description).contains("低拒答"))
    }

    @Test
    fun fullVisionBundleRequiresAllEightOriginalComponentPaths() {
        val bundle = requireNotNull(standard.mnnModelBundle)
        val expected = setOf(
            "config.json", "llm_config.json", "llm.mnn", "llm.mnn.weight",
            "tokenizer.mtok", "llm.mnn.json", "visual.mnn", "visual.mnn.weight"
        )
        assertEquals(MnnModelBundleInstallProfile.STANDARD, bundle.installProfile)
        assertEquals(8, bundle.components.size)
        assertEquals(expected, bundle.components.map { it.fileName }.toSet())
        assertEquals(expected, bundle.requiredComponents.map { it.relativePath }.toSet())
        assertTrue(bundle.components.all { it.required && it.relativePath == it.fileName })
        assertEquals(MnnModelBundleComponentRole.MODEL, bundle.components.single { it.fileName == "visual.mnn" }.role)
        assertEquals(MnnModelBundleComponentRole.WEIGHT, bundle.components.single { it.fileName == "visual.mnn.weight" }.role)
        assertFalse(bundle.components.any { it.fileName.startsWith("embeddings_") })
    }

    @Test
    fun newBundleIdentityCannotReusePreviouslyInstalledLegacySource() {
        val currentBundle = requireNotNull(standard.mnnModelBundle)
        val oldBundle = requireNotNull(legacy.mnnModelBundle)
        assertNotEquals(standard.id, legacy.id)
        assertNotEquals(currentBundle.id, oldBundle.id)
        val currentFiles = sourceFiles(currentBundle)
        val oldFiles = sourceFiles(oldBundle)
        assertNotEquals(
            modelBundleSourceIdentity(oldFiles, "mnn:${oldBundle.installProfile.name}"),
            modelBundleSourceIdentity(currentFiles, "mnn:${currentBundle.installProfile.name}")
        )
        currentFiles.forEach { current ->
            assertNotEquals(oldFiles.single { it.path == current.path }.sourceIdentity(), current.sourceIdentity())
        }
    }

    @Test
    fun originalDownloadRemainsVisibleAndAvailableWithItsOriginalPin() {
        val bundle = requireNotNull(legacy.mnnModelBundle)
        assertEquals("qwen35_08b_uncensored_mnn_bundle", bundle.id)
        assertEquals("darkmaniac7/Qwen3.5-0.8B-uncensored-MNN", legacy.repoId)
        assertEquals("7ad2802ed360a3066112bd973506a6bb3820df2c", legacy.revision)
        assertEquals(legacy.repoId, bundle.repoId)
        assertEquals(legacy.revision, bundle.revision)
        assertEquals(ModelRepositoryProvider.HUGGING_FACE, legacy.provider)
        assertEquals(legacy.provider, bundle.provider)
        assertTrue(legacy.visibleInRecommendations && legacy.downloadable)
        assertNull(legacy.downloadBlockReason)
        assertTrue(legacy.priority > standard.priority)
        assertEquals(RecommendedModelStatus.EXPERIMENTAL, legacy.status)
        assertTrue(legacy.description.contains("3.5.0"))
        listOf(standard, legacy).forEach {
            assertEquals(RecommendedModelDownloadPolicy.ALL_DEVICES, it.downloadPolicy)
            assertTrue(it.downloadable && it.visibleInRecommendations)
            assertNull(it.downloadBlockReason)
        }
    }

    private fun sourceFiles(bundle: MnnModelBundleSpec): List<RemoteModelFile> =
        bundle.components.map { component ->
            RemoteModelFile(
                repoId = bundle.repoId,
                revision = bundle.revision,
                path = component.fileName,
                name = component.fileName,
                downloadUrl = "https://example.test/${component.fileName}",
                provider = bundle.provider,
                mnnBundleRole = component.role,
                relativePath = component.relativePath
            )
        }
}
