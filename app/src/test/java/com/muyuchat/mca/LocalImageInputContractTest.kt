package com.muyuchat.mca

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalImageInputContractTest {
    @Test
    fun `native params use canonical role paths and strict controls`() {
        val input = prepared("/cache/worker/input.img", "a", width = 768, height = 512)
        val options = LocalImageGenerationOptions(
            taskMode = LocalImageTaskMode.IMG2IMG,
            inputImage = input,
            strength = 0.65,
            clipSkip = 1,
            batchCount = 1,
            vaeTiling = LocalImageVaeTilingOptions(512, 0.5)
        )

        val params = options.putProductInputNativeParams(JSONObject())

        assertEquals("img2img", params.getString("taskMode"))
        assertEquals(input.path, params.getString("inputImagePath"))
        assertEquals(input.sha256, params.getString("inputImageSha256"))
        assertEquals(0.65, params.getDouble("strength"), 0.0)
        assertEquals(1, params.getInt("clipSkip"))
        assertFalse(params.has("maskImagePath"))
    }

    @Test
    fun `invalid role combinations fail but runtime discovery never blocks a valid task`() {
        assertInvalid {
            LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.INPAINT,
                inputImage = prepared("/cache/input.img", "a")
            ).validateProductInputContract()
        }
        val control = LocalImageGenerationOptions(
            taskMode = LocalImageTaskMode.CONTROL,
            controlImage = prepared("/cache/control.img", "c"),
            controlStrength = 0.8
        )
        validateLocalImageRuntimeProductOptions(LocalImageRuntime.QNN_HTP, control)
        validateLocalImageRuntimeProductOptions(
            LocalImageRuntime.MNN_DIFFUSION,
            LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.EDIT,
                inputImage = prepared("/cache/edit.img", "e")
            )
        )
        try {
            validateLocalImageRuntimeProductOptions(
                LocalImageRuntime.MNN_DIFFUSION,
                LocalImageGenerationOptions(
                    taskMode = LocalImageTaskMode.EDIT,
                    inputImage = prepared("/cache/edit-strength.img", "f"),
                    strength = 0.7
                )
            )
            fail("Expected unsupported MNN edit strength")
        } catch (error: LocalImageProductContractException) {
            assertEquals("unsupported_edit_strength", error.code)
        }

        validateLocalImageRuntimeProductOptions(
            LocalImageRuntime.STABLE_DIFFUSION_CPP,
            LocalImageGenerationOptions(batchCount = 2)
        )
        val preview = LocalImageGenerationOptions(
            preview = LocalImagePreviewOptions(
                interval = 2,
                mode = LocalImagePreviewMode.PROJECTION
            )
        )
        validateLocalImageRuntimeProductOptions(LocalImageRuntime.STABLE_DIFFUSION_CPP, preview)
        try {
            validateLocalImageRuntimeProductOptions(LocalImageRuntime.MNN_DIFFUSION, preview)
            fail("Expected unsupported MNN preview")
        } catch (error: LocalImageProductContractException) {
            assertEquals("unsupported_preview", error.code)
        }
        try {
            validateLocalImageRuntimeProductOptions(
                LocalImageRuntime.MNN_DIFFUSION,
                LocalImageGenerationOptions(batchCount = 2)
            )
            fail("Expected unsupported MNN batch")
        } catch (error: LocalImageProductContractException) {
            assertEquals("unsupported_batch_count", error.code)
        }
        try {
            validateLocalImageRuntimeProductOptions(
                LocalImageRuntime.QNN_HTP,
                LocalImageGenerationOptions(distilledGuidance = 3.5)
            )
            fail("Expected unsupported QNN distilled guidance")
        } catch (error: LocalImageProductContractException) {
            assertEquals("unsupported_distilled_guidance", error.code)
        }
        try {
            validateLocalImageRuntimeProductOptions(
                LocalImageRuntime.MNN_DIFFUSION,
                LocalImageGenerationOptions(flowShift = 3.0)
            )
            fail("Expected unsupported MNN flow shift")
        } catch (error: LocalImageProductContractException) {
            assertEquals("unsupported_flow_shift", error.code)
        }
    }

    @Test
    fun `native input evidence is verified then private paths are replaced by hashes`() {
        val root = Files.createTempDirectory("native-input-evidence").toFile()
        try {
            val source = root.resolve("source.png").apply { writeText("source") }.canonicalFile
            val input = prepared(source.path, "d", width = 640, height = 384)
            val options = LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.EDIT,
                inputImage = input,
                strength = 0.8
            )
            val nativeEffective = nativeInputEvidence(options)
            val result = JSONObject(nativeEffective.toString())
                .put("nativeEffective", nativeEffective)

            val audit = verifyAndSanitizeStableDiffusionProductInput(result, options)

            assertFalse(result.has("inputImagePath"))
            assertFalse(result.getJSONObject("nativeEffective").has("inputImagePath"))
            assertEquals(input.sha256, result.getString("inputImageSha256"))
            assertEquals(input.sha256, audit.getJSONObject("inputImage").getString("sha256"))
            assertTrue(audit.getBoolean("nativeExecution"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `stable diffusion preview requires real native callback publication evidence`() {
        val options = LocalImageGenerationOptions(
            preview = LocalImagePreviewOptions(
                interval = 2,
                mode = LocalImagePreviewMode.PROJECTION
            )
        )
        val nativeEffective = nativeInputEvidence(options)
        val result = JSONObject(nativeEffective.toString())
            .put("nativeEffective", nativeEffective)

        val audit = verifyAndSanitizeStableDiffusionProductInput(result, options)

        assertEquals("projection", audit.getJSONObject("preview").getString("mode"))
        val missingPublication = JSONObject(result.toString())
            .put("previewPublicationCount", 0)
        assertInvalid {
            verifyAndSanitizeStableDiffusionProductInput(missingPublication, options)
        }
    }

    @Test
    fun `native float evidence accepts ordinary C float round trip`() {
        val root = Files.createTempDirectory("native-float-evidence").toFile()
        try {
            val source = root.resolve("source.png").apply { writeText("source") }.canonicalFile
            val options = LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.IMG2IMG,
                inputImage = prepared(source.path, "f"),
                strength = 0.65,
                vaeTiling = LocalImageVaeTilingOptions(512, 0.3)
            )
            val nativeEffective = nativeInputEvidence(options)
                .put("strength", 0.6499999761581421)
            nativeEffective.getJSONObject("vaeTiling")
                .put("overlap", 0.30000001192092896)
            val result = JSONObject(nativeEffective.toString())
                .put("nativeEffective", nativeEffective)

            val audit = verifyAndSanitizeStableDiffusionProductInput(result, options)

            assertTrue(audit.getBoolean("nativeExecution"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `stable diffusion input result rejects missing or request-synthesized digest`() {
        val root = Files.createTempDirectory("stable-native-input-digest").toFile()
        try {
            val source = root.resolve("source.png").apply { writeText("source") }.canonicalFile
            val input = prepared(source.path, "b", width = 640, height = 384)
            val options = LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.IMG2IMG,
                inputImage = input
            )

            val missing = nativeInputEvidence(options).let { nativeEffective ->
                JSONObject(nativeEffective.toString()).put("nativeEffective", nativeEffective)
            }
            missing.getJSONObject("nativeEffective").remove("inputImageSha256")
            assertInvalid { verifyAndSanitizeStableDiffusionProductInput(missing, options) }

            val mismatched = nativeInputEvidence(options).let { nativeEffective ->
                JSONObject(nativeEffective.toString()).put("nativeEffective", nativeEffective)
            }
            mismatched.getJSONObject("nativeEffective")
                .put("inputImageSha256", "0".repeat(64))
            assertInvalid { verifyAndSanitizeStableDiffusionProductInput(mismatched, options) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `MNN text result sanitizes empty role paths and publishes execution audit`() {
        val options = LocalImageGenerationOptions()
        val result = mnnResultEvidence(options, seed = 41L)

        val audit = verifyAndSanitizeMnnProductInput(result, options)

        assertFalse(result.has("inputImagePath"))
        assertFalse(result.has("maskImagePath"))
        assertFalse(result.has("controlImagePath"))
        val nativeEffective = result.getJSONObject("nativeEffective")
        assertFalse(nativeEffective.has("inputImagePath"))
        assertFalse(nativeEffective.has("maskImagePath"))
        assertFalse(nativeEffective.has("controlImagePath"))
        assertEquals("text_to_image", nativeEffective.getString("taskMode"))
        assertEquals(1, nativeEffective.getInt("batchCount"))
        assertTrue(audit.getBoolean("nativeExecution"))
        assertEquals(0, audit.getInt("inputImageExecutionCount"))
    }

    @Test
    fun `MNN edit result replaces private source path with content hash`() {
        val root = Files.createTempDirectory("mnn-edit-input-evidence").toFile()
        try {
            val source = root.resolve("source.png").apply { writeText("source") }.canonicalFile
            val input = prepared(source.path, "e", width = 640, height = 384)
            val options = LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.EDIT,
                inputImage = input
            )
            val result = mnnResultEvidence(options, seed = 42L)

            val audit = verifyAndSanitizeMnnProductInput(result, options)

            assertFalse(result.has("inputImagePath"))
            assertFalse(result.getJSONObject("nativeEffective").has("inputImagePath"))
            assertEquals(input.sha256, result.getString("inputImageSha256"))
            assertEquals(
                input.sha256,
                result.getJSONObject("nativeEffective").getString("inputImageSha256")
            )
            assertEquals(input.sha256, audit.getJSONObject("inputImage").getString("sha256"))
            assertEquals(1, audit.getInt("inputImageExecutionCount"))
            assertTrue(audit.getBoolean("nativeExecution"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `MNN text result rejects every missing nativeEffective product field`() {
        val options = LocalImageGenerationOptions()
        listOf(
            "taskMode",
            "batchCount",
            "inputImageExecutionCount",
            "maskImageExecutionCount",
            "controlImageExecutionCount",
            "inputImagePath",
            "maskImagePath",
            "controlImagePath"
        ).forEach { field ->
            val result = mnnResultEvidence(options, seed = 43L)
            result.getJSONObject("nativeEffective").remove(field)

            assertInvalid { verifyAndSanitizeMnnProductInput(result, options) }
        }
    }

    @Test
    fun `MNN edit result rejects missing or request-synthesized input digest`() {
        val root = Files.createTempDirectory("mnn-edit-native-digest").toFile()
        try {
            val source = root.resolve("source.png").apply { writeText("source") }.canonicalFile
            val input = prepared(source.path, "f", width = 640, height = 384)
            val options = LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.EDIT,
                inputImage = input
            )
            val missing = mnnResultEvidence(options, seed = 44L)
            missing.getJSONObject("nativeEffective").remove("inputImageSha256")
            assertInvalid { verifyAndSanitizeMnnProductInput(missing, options) }

            val mismatched = mnnResultEvidence(options, seed = 44L)
            mismatched.getJSONObject("nativeEffective")
                .put("inputImageSha256", "0".repeat(64))
            assertInvalid { verifyAndSanitizeMnnProductInput(mismatched, options) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `QNN ControlNet verifies per-step residual execution and removes private path`() {
        val root = Files.createTempDirectory("qnn-control-input-evidence").toFile()
        try {
            val source = root.resolve("control.png").apply { writeText("control") }.canonicalFile
            val input = prepared(source.path, "c", width = 512, height = 512)
            val options = LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.CONTROL,
                controlImage = input,
                controlStrength = 0.65
            )
            val result = qnnControlResultEvidence(options, timetableCount = 20)

            val audit = verifyAndSanitizeQnnProductInput(result, options)

            assertFalse(result.has("controlImagePath"))
            assertFalse(result.getJSONObject("nativeEffective").has("controlImagePath"))
            assertEquals(input.sha256, audit.getJSONObject("controlImage").getString("sha256"))
            assertEquals(20, audit.getInt("controlNetExecutionCount"))
            assertEquals(260, audit.getInt("controlNetResidualWriteCount"))
            assertEquals(20, audit.getInt("controlNetResidualUnetReuseCount"))
            assertEquals("positive", audit.getString("controlNetConditioningBranch"))
            assertTrue(audit.getBoolean("nativeExecution"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `QNN ControlNet rejects incomplete scheduler-step evidence`() {
        val root = Files.createTempDirectory("qnn-control-count-evidence").toFile()
        try {
            val source = root.resolve("control.png").apply { writeText("control") }.canonicalFile
            val options = LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.CONTROL,
                controlImage = prepared(source.path, "d"),
                controlStrength = 0.8
            )
            val result = qnnControlResultEvidence(options, timetableCount = 20)
            result.put("controlNetExecutionCount", 39)

            assertInvalid { verifyAndSanitizeQnnProductInput(result, options) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `QNN text result verifies and removes empty worker path slots`() {
        val options = LocalImageGenerationOptions()
        val nativeEffective = nativeInputEvidence(options)
        val result = JSONObject(nativeEffective.toString())
            .put("nativeEffective", nativeEffective)

        verifyAndSanitizeQnnTextToImagePrivatePaths(result, options)

        listOf("inputImagePath", "maskImagePath", "controlImagePath").forEach { field ->
            assertFalse(result.has(field))
            assertFalse(result.getJSONObject("nativeEffective").has(field))
        }
    }

    @Test
    fun `QNN text result rejects a non-empty or missing worker path slot`() {
        val options = LocalImageGenerationOptions()
        val nonEmptyNative = nativeInputEvidence(options)
            .put("inputImagePath", "/private/input.png")
        val nonEmpty = JSONObject(nonEmptyNative.toString())
            .put("nativeEffective", nonEmptyNative)
        assertInvalid { verifyAndSanitizeQnnTextToImagePrivatePaths(nonEmpty, options) }

        val missingNative = nativeInputEvidence(options).apply { remove("maskImagePath") }
        val missing = JSONObject(missingNative.toString()).put("nativeEffective", missingNative)
        assertInvalid { verifyAndSanitizeQnnTextToImagePrivatePaths(missing, options) }
    }

    private fun nativeInputEvidence(
        options: LocalImageGenerationOptions,
        includeUnusedHashes: Boolean = true
    ): JSONObject = JSONObject()
        .put("taskMode", options.taskMode.wireName)
        .put("inputImagePath", options.inputImage?.path.orEmpty())
        .put("maskImagePath", options.maskImage?.path.orEmpty())
        .put("controlImagePath", options.controlImage?.path.orEmpty())
        .put("inputImageExecutionCount", if (options.inputImage == null) 0 else 1)
        .put("maskImageExecutionCount", if (options.maskImage == null) 0 else 1)
        .put("controlImageExecutionCount", if (options.controlImage == null) 0 else 1)
        .put("strength", options.strength ?: 1.0)
        .put("controlStrength", options.controlStrength ?: 1.0)
        .put("clipSkip", options.clipSkip ?: -1)
        .put("batchCount", options.batchCount)
        .put("steps", 20)
        .put("previewRequested", options.preview != null)
        .put("previewMode", options.preview?.mode?.wireName ?: "none")
        .put("previewInterval", options.preview?.interval ?: 0)
        .put("previewPublicationCount", if (options.preview == null) 0 else 3)
        .put("previewLastStep", if (options.preview == null) 0 else 6)
        .put("previewLastRevision", if (options.preview == null) 0L else 9L)
        .put(
            "vaeTiling",
            JSONObject()
                .put("enabled", options.vaeTiling != null)
                .put("tileSize", options.vaeTiling?.tileSize ?: 0)
                .put("overlap", options.vaeTiling?.overlap ?: 0.5)
        )
        .apply {
            if (includeUnusedHashes) {
                put("inputImageSha256", options.inputImage?.sha256.orEmpty())
                put("maskImageSha256", options.maskImage?.sha256.orEmpty())
                put("controlImageSha256", options.controlImage?.sha256.orEmpty())
            } else {
                options.inputImage?.let { put("inputImageSha256", it.sha256) }
                options.maskImage?.let { put("maskImageSha256", it.sha256) }
                options.controlImage?.let { put("controlImageSha256", it.sha256) }
            }
        }

    private fun mnnResultEvidence(
        options: LocalImageGenerationOptions,
        seed: Long
    ): JSONObject {
        val nativeEffective = nativeInputEvidence(options, includeUnusedHashes = false)
            .put("seed", seed)
            .apply {
                options.inputImage?.let { put("inputImageSha256", it.sha256) }
                options.maskImage?.let { put("maskImageSha256", it.sha256) }
                options.controlImage?.let { put("controlImageSha256", it.sha256) }
            }
        return JSONObject(nativeEffective.toString())
            .put("nativeEffective", nativeEffective)
            .put(
                "outputs",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("path", "/cache/output.png")
                        .put("mimeType", "image/png")
                        .put("seed", seed)
                )
            )
    }

    private fun qnnControlResultEvidence(
        options: LocalImageGenerationOptions,
        timetableCount: Int
    ): JSONObject {
        val residualTensorCount = 13
        val nativeEffective = nativeInputEvidence(options)
            .put("useCfg", true)
            .put("unetExecutionCount", timetableCount * 2)
            .put("controlStrength", options.controlStrength ?: 1.0)
            .put("controlImageSha256", requireNotNull(options.controlImage).sha256)
            .put("controlStrengthApplied", true)
            .put("controlNetExecutionCount", timetableCount)
            .put("controlNetResidualTensorCount", residualTensorCount)
            .put("controlNetResidualWriteCount", timetableCount * residualTensorCount)
            .put("controlNetResidualUnetReuseCount", timetableCount)
            .put("controlNetConditioningBranch", "positive")
            .put("controlNetInputConsumed", true)
        return JSONObject(nativeEffective.toString())
            .put("timetableCount", timetableCount)
            .put("useCfg", true)
            .put("nativeEffective", nativeEffective)
    }

    private fun prepared(
        path: String,
        digestCharacter: String,
        width: Int = 512,
        height: Int = 512
    ): LocalImagePreparedInput = LocalImagePreparedInput(
        path = path,
        mimeType = "image/png",
        sha256 = digestCharacter.repeat(64),
        sizeBytes = 1024L,
        width = width,
        height = height
    )

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid image input contract")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
