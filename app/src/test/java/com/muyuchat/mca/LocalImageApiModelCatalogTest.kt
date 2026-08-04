package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageApiModelCatalogTest {
    @Test
    fun stableTurboCatalogPublishesExecutableExtensionBounds() {
        val root = Files.createTempDirectory("mca-stable-turbo-api-catalog").toFile()
        val modelFile = root.resolve("sd_turbo.safetensors").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        try {
            val model = LocalImageModelRecord(
                id = "stable-turbo",
                displayName = "Stable Diffusion Turbo",
                path = modelFile.absolutePath,
                fileName = modelFile.name,
                sizeBytes = modelFile.length(),
                sha256 = "3".repeat(64),
                runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                family = LocalImageModelFamily.CUSTOM,
                source = "modelscope:AI-ModelScope/sd-turbo",
                bundleRoot = root.absolutePath
            )

            val payload = localImageApiCatalogEntries(emptyList(), listOf(model)).single().payload
            val capabilities = payload.getJSONObject("capabilities")
            val ultraFixDimensions = capabilities.getJSONObject("ultrafix_dimensions")

            assertTrue(capabilities.getBoolean("textual_inversion"))
            assertTrue(capabilities.getBoolean("ultrafix"))
            assertEquals(128, ultraFixDimensions.getInt("min_width"))
            assertEquals(8_192, ultraFixDimensions.getInt("max_width"))
            assertEquals(128, ultraFixDimensions.getInt("min_height"))
            assertEquals(8_192, ultraFixDimensions.getInt("max_height"))
            assertEquals(64, ultraFixDimensions.getInt("width_multiple"))
            assertEquals(64, ultraFixDimensions.getInt("height_multiple"))
            assertEquals(1_536, model.imageCapabilitiesForUi().executionDefaults.maxWidth)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun configuredImageModelIsDiscoverableWithCollisionSafeIdAndSafeCapabilities() {
        val root = Files.createTempDirectory("mca-image-api-catalog").toFile()
        val modelFile = root.resolve("model.task").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        try {
            val configured = LocalImageModelRecord(
                id = "image-a",
                displayName = "Local image A",
                path = modelFile.absolutePath,
                fileName = modelFile.name,
                sizeBytes = modelFile.length(),
                sha256 = "private-artifact-fingerprint",
                runtime = LocalImageRuntime.CUSTOM,
                family = LocalImageModelFamily.CUSTOM,
                imageSize = "512x512"
            )
            val missing = configured.copy(
                id = "missing-image",
                displayName = "Missing image",
                path = root.resolve("missing.task").absolutePath
            )

            val entries = localImageApiCatalogEntries(
                chatModelIds = listOf("chat-a", "image:image-a"),
                imageModels = listOf(configured, missing)
            )

            assertEquals(1, entries.size)
            val entry = entries.single()
            val payload = entry.payload
            assertEquals("image:image-a:2", entry.apiId)
            assertEquals(entry.apiId, payload.getString("id"))
            assertEquals("model", payload.getString("object"))
            assertEquals("local", payload.getString("owned_by"))
            assertEquals("image_generation", payload.getString("type"))
            assertTrue(payload.getBoolean("configured"))
            assertEquals("CUSTOM", payload.getString("runtime"))
            assertEquals("CUSTOM", payload.getString("family"))
            assertEquals("text_to_image", payload.getString("task"))
            assertEquals(listOf("text_to_image"), payload.getJSONArray("task_modes").strings())
            assertEquals(1, payload.getInt("max_batch_count"))

            val capabilities = payload.getJSONObject("capabilities")
            assertTrue(capabilities.getBoolean("image_generation"))
            assertEquals(listOf("text_to_image"), capabilities.getJSONArray("task_modes").strings())
            assertEquals(1, capabilities.getInt("max_batch_count"))

            val defaults = payload.getJSONObject("defaults")
            assertEquals("512x512", defaults.getString("size"))
            assertEquals(512, defaults.getInt("width"))
            assertEquals(512, defaults.getInt("height"))
            assertEquals(20, defaults.getInt("steps"))
            assertEquals(7.0, defaults.getDouble("cfg_scale"), 0.0)

            val serialized = payload.toString()
            assertFalse(serialized.contains(modelFile.absolutePath))
            assertFalse(serialized.contains(root.absolutePath))
            assertFalse(serialized.contains("private-artifact-fingerprint"))
            assertFalse(payload.has("path"))
            assertFalse(payload.has("bundleRoot"))
            assertFalse(payload.has("sha256"))

            assertSame(
                configured,
                resolveLocalImageApiModel(entry.apiId, listOf("chat-a", "image:image-a"), listOf(configured, missing))
            )
            assertSame(
                configured,
                resolveLocalImageApiModel(configured.id, listOf("chat-a"), listOf(configured, missing))
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun splitSdxlCatalogPublishesImg2ImgAndInpaintAtNativeBatchOneWithoutPrivateProofs() {
        val root = Files.createTempDirectory("mca-split-sdxl-api-catalog").toFile()
        val ids = listOf(
            "sdxl_base_qnn228",
            "realismsdxl_dmd2_alt_qnn228",
            "animagine_xl_v4_qnn228",
            "cyberrealisticxl_qnn228"
        )
        try {
            val models = ids.map { recommendationId ->
                val bundleRoot = root.resolve(recommendationId).apply { mkdirs() }
                val modelFile = bundleRoot.resolve("unet.bin").apply { writeBytes(byteArrayOf(1)) }
                File(bundleRoot, "manifest.json").writeText(
                    JSONObject()
                        .put("schema", "mca.image_engine.bundle.v1")
                        .put("recommendationId", recommendationId)
                        .toString(),
                    Charsets.UTF_8
                )
                LocalImageModelRecord(
                    id = recommendationId,
                    displayName = recommendationId,
                    path = modelFile.absolutePath,
                    fileName = modelFile.name,
                    sizeBytes = modelFile.length(),
                    sha256 = "0".repeat(64),
                    runtime = LocalImageRuntime.QNN_HTP,
                    family = LocalImageModelFamily.SDXL,
                    source = "app-private:${bundleRoot.absolutePath}",
                    bundleRoot = bundleRoot.absolutePath,
                    verificationMessage = "sdxlPhaseProof=private-$recommendationId",
                    qnnVerificationStamp = "private-phase-proof-$recommendationId"
                )
            }

            val entries = localImageApiCatalogEntries(
                chatModelIds = listOf("chat-a"),
                imageModels = models
            )

            assertEquals(ids.size, entries.size)
            ids.forEach { recommendationId ->
                val entry = entries.single { it.model.id == recommendationId }
                val payload = entry.payload
                val expectedTaskModes = listOf("text_to_image", "img2img", "inpaint")

                assertEquals("image:$recommendationId", entry.apiId)
                assertEquals("text_to_image", payload.getString("task"))
                assertEquals(expectedTaskModes, payload.getJSONArray("task_modes").strings())
                assertEquals(1, payload.getInt("max_batch_count"))
                val capabilities = payload.getJSONObject("capabilities")
                assertTrue(capabilities.getBoolean("image_generation"))
                assertEquals(expectedTaskModes, capabilities.getJSONArray("task_modes").strings())
                assertEquals(1, capabilities.getInt("max_batch_count"))
                assertEquals(
                    listOf("dpmpp_2m", "euler"),
                    capabilities.getJSONArray("supported_samplers").strings()
                )
                val samplersByTask = capabilities.getJSONObject("samplers_by_task")
                assertEquals(
                    listOf("dpmpp_2m", "euler"),
                    samplersByTask.getJSONArray("text_to_image").strings()
                )
                assertEquals(
                    listOf("dpmpp_2m", "euler"),
                    samplersByTask.getJSONArray("img2img").strings()
                )
                assertEquals(
                    listOf("dpmpp_2m", "euler"),
                    samplersByTask.getJSONArray("inpaint").strings()
                )
                assertEquals(1, entry.model.imageCapabilitiesForUi().nativeMaxBatchCount)

                val serialized = payload.toString()
                val deescapedSerialized = serialized.replace("\\\\", "\\")
                assertFalse(deescapedSerialized.contains(root.absolutePath))
                assertFalse(deescapedSerialized.contains(entry.model.path))
                assertFalse(deescapedSerialized.contains(entry.model.bundleRoot.orEmpty()))
                assertFalse(serialized.contains(entry.model.verificationMessage))
                assertFalse(serialized.contains(entry.model.qnnVerificationStamp))
                assertFalse(serialized.contains("sdxlPhaseProof"))
                assertFalse(payload.has("path"))
                assertFalse(payload.has("bundleRoot"))
                assertFalse(payload.has("sha256"))
                assertFalse(payload.has("source"))
                assertFalse(payload.has("verificationMessage"))
                assertFalse(payload.has("qnnVerificationStamp"))
                assertFalse(payload.has("sdxlPhaseProof"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sharedSd15CatalogPublishesImg2ImgAndInpaintAtNativeBatchOneWithoutPrivateProofs() {
        val root = Files.createTempDirectory("mca-shared-sd15-api-catalog").toFile()
        val ids = listOf(
            "cyberrealistic_sd15_qnn228",
            "realisticvisionhyper_sd15_qnn228",
            "dreamshaper_sd15_qnn228",
            "meinamix_sd15_qnn228"
        )
        try {
            val models = ids.map { recommendationId ->
                val bundleRoot = root.resolve(recommendationId).apply { mkdirs() }
                val modelFile = bundleRoot.resolve("unet.bin").apply { writeBytes(byteArrayOf(1)) }
                File(bundleRoot, "manifest.json").writeText(
                    JSONObject()
                        .put("schema", "mca.image_engine.bundle.v1")
                        .put("recommendationId", recommendationId)
                        .toString(),
                    Charsets.UTF_8
                )
                LocalImageModelRecord(
                    id = recommendationId,
                    displayName = recommendationId,
                    path = modelFile.absolutePath,
                    fileName = modelFile.name,
                    sizeBytes = modelFile.length(),
                    sha256 = "1".repeat(64),
                    runtime = LocalImageRuntime.QNN_HTP,
                    family = LocalImageModelFamily.SD15,
                    source = "app-private:${bundleRoot.absolutePath}",
                    bundleRoot = bundleRoot.absolutePath,
                    verificationMessage = "encoderLatentSha256=private-$recommendationId",
                    qnnVerificationStamp = "private-shared-proof-$recommendationId"
                )
            }

            val entries = localImageApiCatalogEntries(
                chatModelIds = listOf("chat-a"),
                imageModels = models
            )

            assertEquals(ids.size, entries.size)
            ids.forEach { recommendationId ->
                val entry = entries.single { it.model.id == recommendationId }
                val payload = entry.payload
                val expectedTaskModes = listOf("text_to_image", "img2img", "inpaint")

                assertEquals("image:$recommendationId", entry.apiId)
                assertEquals("text_to_image", payload.getString("task"))
                assertEquals(expectedTaskModes, payload.getJSONArray("task_modes").strings())
                assertEquals(1, payload.getInt("max_batch_count"))
                val capabilities = payload.getJSONObject("capabilities")
                assertTrue(capabilities.getBoolean("image_generation"))
                assertEquals(expectedTaskModes, capabilities.getJSONArray("task_modes").strings())
                assertEquals(1, capabilities.getInt("max_batch_count"))
                assertEquals(1, entry.model.imageCapabilitiesForUi().nativeMaxBatchCount)

                val serialized = payload.toString()
                val deescapedSerialized = serialized.replace("\\\\", "\\")
                assertFalse(deescapedSerialized.contains(root.absolutePath))
                assertFalse(deescapedSerialized.contains(entry.model.path))
                assertFalse(deescapedSerialized.contains(entry.model.bundleRoot.orEmpty()))
                assertFalse(serialized.contains(entry.model.sha256))
                assertFalse(serialized.contains(entry.model.verificationMessage))
                assertFalse(serialized.contains(entry.model.qnnVerificationStamp))
                assertFalse(serialized.contains("encoderLatentSha256"))
                assertFalse(payload.has("path"))
                assertFalse(payload.has("bundleRoot"))
                assertFalse(payload.has("sha256"))
                assertFalse(payload.has("source"))
                assertFalse(payload.has("verificationMessage"))
                assertFalse(payload.has("qnnVerificationStamp"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun genericSharedQnnCatalogKeepsTextPndmButPublishesImageInputFallbacks() {
        val root = Files.createTempDirectory("mca-generic-shared-qnn-api-catalog").toFile()
        try {
            val primary = root.resolve("unet.bin").apply { writeBytes(byteArrayOf(1)) }
            listOf("text_encoder.bin", "vae.bin", "vae_encoder.bin").forEach { name ->
                root.resolve(name).writeBytes(byteArrayOf(1))
            }
            val model = LocalImageModelRecord(
                id = "generic-compatible-shared-qnn",
                displayName = "Generic shared QNN",
                path = primary.absolutePath,
                fileName = primary.name,
                sizeBytes = primary.length(),
                sha256 = "2".repeat(64),
                runtime = LocalImageRuntime.QNN_HTP,
                family = LocalImageModelFamily.SD15,
                bundleRoot = root.absolutePath
            )

            val payload = localImageApiCatalogEntries(emptyList(), listOf(model)).single().payload
            val capabilities = payload.getJSONObject("capabilities")
            val samplersByTask = capabilities.getJSONObject("samplers_by_task")
            val global = capabilities.getJSONArray("supported_samplers").strings()
            val textToImage = samplersByTask.getJSONArray("text_to_image").strings()
            val img2img = samplersByTask.getJSONArray("img2img").strings()
            val inpaint = samplersByTask.getJSONArray("inpaint").strings()

            assertEquals(
                listOf("text_to_image", "img2img", "inpaint"),
                payload.getJSONArray("task_modes").strings()
            )
            assertEquals("pndm", payload.getJSONObject("defaults").getString("sampler"))
            assertTrue("pndm" in global)
            assertTrue("pndm" in textToImage)
            assertFalse("pndm" in img2img)
            assertFalse("pndm" in inpaint)
            assertEquals("dpmpp_2m", img2img.first())
            assertEquals(img2img, inpaint)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unconfiguredImageModelsAreNotAdvertised() {
        val missing = LocalImageModelRecord(
            id = "missing-image",
            displayName = "Missing image",
            path = "Z:\\missing\\private-model.task",
            fileName = "private-model.task",
            sizeBytes = 123L,
            sha256 = "not-public",
            runtime = LocalImageRuntime.QNN_HTP,
            family = LocalImageModelFamily.SD15
        )

        val entries = localImageApiCatalogEntries(
            chatModelIds = listOf("chat-a"),
            imageModels = listOf(missing)
        )

        assertTrue(entries.isEmpty())
    }

    private fun JSONArray.strings(): List<String> =
        (0 until length()).map(::getString)
}
