package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnSharedImg2ImgProductWiringContractTest {
    @Test
    fun `shared qnn img2img provider uses generic schedule tensor verifier and commit cancellation gate`() {
        val provider = source("app/src/main/java/com/muyuchat/mca/LocalImageProvider.kt")

        listOf(
            ".withQnnProductSchedule(effectiveOptions)",
            "profile.hasExecutableQnnImg2ImgTopology()",
            "profile.hasSharedQnnImg2ImgTopology()",
            "QnnInputImageArtifact.prepare(",
            "verifyAndSanitizeSharedQnnImg2ImgProductInput(",
            "expectedVaeEncoderGraphName = params.getString(\"vaeEncoderGraphName\")"
        ).forEach { fragment ->
            assertTrue("Missing shared-QNN product wiring: $fragment", provider.contains(fragment))
        }
        assertFalse(provider.contains(".withSdxlProductSchedule(effectiveOptions)"))

        assertOrdered(
            provider,
            "qnnBridge.runImageSemanticGenerate(",
            "coroutineContext.ensureActive()",
            "if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()",
            "val json = JSONObject(raw)"
        )
    }

    @Test
    fun `vae encoder graph name follows resolved artifact then smoke then generic fallback`() {
        val provider = source("app/src/main/java/com/muyuchat/mca/LocalImageProvider.kt")
        val start = provider.indexOf("private fun JSONObject.putQnnSemanticDefaults(")
        assertTrue(start >= 0)
        val semanticDefaults = provider.substring(start)

        assertOrdered(
            semanticDefaults,
            "\"vaeEncoderGraphName\"",
            "profile.graph.vaeEncoder?.graphName?.trim()?.takeIf(String::isNotEmpty)",
            "?: smokeGraphName",
            "?: \"model\""
        )
    }

    @Test
    fun `img2img product admission is graph topology only and feeds ui capability resolution`() {
        val profileSource = source("app/src/main/java/com/muyuchat/mca/ImageExecutionProfile.kt")
        val helperStart = profileSource.indexOf(
            "internal fun ImageExecutionProfile.hasExecutableQnnImg2ImgTopology()"
        )
        val helperEnd = profileSource.indexOf(
            "internal fun ImageExecutionProfile.hasSharedQnnImg2ImgTopology()",
            helperStart
        )
        assertTrue(helperStart >= 0 && helperEnd > helperStart)
        val helper = profileSource.substring(helperStart, helperEnd)

        listOf(
            "runtime != LocalImageRuntime.QNN_HTP",
            "graph.vaeEncoder == null",
            "graph.unet == null",
            "graph.vae == null",
            "ImageWorkerStrategy.SPLIT_UNET_VAE",
            "ImageWorkerStrategy.SHARED_UNET_VAE",
            "ImageWorkerStrategy.SHARED_TEXT_UNET_VAE"
        ).forEach { fragment ->
            assertTrue("Missing topology condition: $fragment", helper.contains(fragment))
        }
        listOf("recommendationId", "device", "chipset", "profileId", "certif", "allowlist", "whitelist")
            .forEach { forbidden ->
                assertFalse("Topology admission contains forbidden gate: $forbidden", helper.contains(forbidden, true))
            }

        val uiCapabilities = source(
            "app/src/main/java/com/muyuchat/mca/LocalImageUiCapabilities.kt"
        )
        assertTrue(uiCapabilities.contains("internal fun ImageExecutionProfile.exposesQnnImg2ImgForUi(): Boolean"))
        assertTrue(uiCapabilities.contains("hasExecutableQnnImg2ImgTopology()"))
        assertTrue(uiCapabilities.contains("if (profile.exposesQnnImg2ImgForUi())"))
        assertFalse(uiCapabilities.contains("QNN_IMG2IMG_MODEL_IDS"))
    }

    private fun assertOrdered(source: String, vararg fragments: String) {
        var cursor = 0
        fragments.forEach { fragment ->
            val next = source.indexOf(fragment, cursor)
            assertTrue("Missing or out-of-order fragment: $fragment", next >= cursor)
            cursor = next + fragment.length
        }
    }

    private fun source(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate project source: $relativePath")
    }
}
