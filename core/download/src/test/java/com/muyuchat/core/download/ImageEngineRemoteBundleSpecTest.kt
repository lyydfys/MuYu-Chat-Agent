package com.muyuchat.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageEngineRemoteBundleSpecTest {
    private val model = ModelScopeClient().recommendedModels()
        .first { it.id == "mediatek_anima_npu_np9" }
    private val spec = requireNotNull(model.remoteImageBundle)

    @Test
    fun pinsDescriptorAndBothVariants() {
        assertEquals("lyydfys/anima-npug-dla-mt6993-nc", model.repoId)
        assertEquals(model.id, spec.recommendationId)
        assertEquals("dynamic", spec.defaultVariantId)
        assertEquals(setOf("fixed", "dynamic"), spec.variants.map { it.id }.toSet())
        assertTrue(spec.descriptorUrl.startsWith("https://"))
        assertTrue(spec.descriptorSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(spec.license.isNotBlank())
        assertTrue(spec.licenseUrl.startsWith("https://"))
        assertTrue(spec.noticeUrl.startsWith("https://"))
        assertTrue(spec.variants.all { it.url.startsWith("https://") })
        assertTrue(spec.variants.all { it.file.endsWith(".zip") })
        assertTrue(spec.variants.all { it.sizeBytes > 0L })
        assertTrue(spec.variants.all { it.sha256.matches(Regex("^[0-9a-f]{64}$")) })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPlainHttpVariant() {
        spec.copy(variants = listOf(spec.variant().copy(url = "http://modelscope.cn/x.zip")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedSha() {
        spec.copy(variants = listOf(spec.variant().copy(sha256 = "not-a-sha")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateVariantIds() {
        val variant = spec.variant()
        spec.copy(variants = listOf(variant, variant))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingDefaultVariant() {
        spec.copy(defaultVariantId = "missing")
    }
}
