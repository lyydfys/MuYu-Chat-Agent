package com.muyuchat.mca

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationHistoryMetadataTest {
    @Test
    fun roundTripPreservesExactReproductionParametersWithoutTransientPreparedPaths() {
        val metadata = ImageGenerationHistoryMetadata(
            backend = ImageBackend.LOCAL,
            modelId = "model-1",
            modelName = "Model One",
            requestPrompt = "paint the same scene",
            options = LocalImageGenerationOptions(
                negativePrompt = "",
                width = 768,
                height = 512,
                steps = 20,
                seed = 42,
                cfgScale = 7.5,
                sampleMethod = "dpmpp_2m",
                taskMode = LocalImageTaskMode.IMG2IMG,
                strength = 0.65,
                clipSkip = 2,
                batchCount = 2,
                vaeTiling = LocalImageVaeTilingOptions(512, 0.5),
                preview = LocalImagePreviewOptions(1, LocalImagePreviewMode.PROJECTION)
            ),
            inputDraft = LocalImageInputDraft(
                taskMode = LocalImageTaskMode.IMG2IMG,
                inputImageReference = "content://documents/original",
                strength = 0.65
            ),
            nativeExecutionJson = "{\"nativeExecution\":true}"
        )

        val raw = metadata.toJsonString()
        val restored = ImageGenerationHistoryMetadata.fromJsonOrNull(raw)
        assertNotNull(restored)
        val required = requireNotNull(restored)

        assertEquals(metadata.backend, required.backend)
        assertEquals(metadata.modelId, required.modelId)
        assertEquals(metadata.requestPrompt, required.requestPrompt)
        assertEquals(metadata.options.copy(preview = null), required.options)
        assertEquals(metadata.inputDraft, required.inputDraft)
        assertEquals(
            setOf("content://documents/original"),
            required.requiredContentInputReferences()
        )
        assertTrue(required.nativeExecutionJson.contains("nativeExecution"))
        assertTrue(required.displayDetails().contains("Model One · 图生图"))
        assertTrue(required.displayDetails().contains("无负向提示词"))

        val options = JSONObject(raw).getJSONObject("options")
        assertFalse(options.has("preview"))
        assertFalse(options.has("inputImage"))
    }

    @Test
    fun malformedOrUnknownHistoryFailsClosed() {
        assertNull(ImageGenerationHistoryMetadata.fromJsonOrNull(""))
        assertNull(
            ImageGenerationHistoryMetadata.fromJsonOrNull(
                "{\"version\":2,\"backend\":\"LOCAL\"}"
            )
        )
        assertTrue(
            runCatching {
                ImageGenerationLoraSelection(
                    id = "11111111-1111-4111-8111-111111111111",
                    name = "x".repeat(129),
                    multiplier = 1.0
                )
            }.isFailure
        )
    }

    @Test
    fun nativeEffectiveValuesReplaceDefaultsBeforeHistoryIsPersisted() {
        val metadata = ImageGenerationHistoryMetadata(
            backend = ImageBackend.LOCAL,
            modelId = "model-1",
            modelName = "Model One",
            requestPrompt = "same image",
            options = LocalImageGenerationOptions(seed = null),
            inputDraft = LocalImageInputDraft()
        )

        val effective = metadata.withNativeExecution(
            """{"nativeEffective":{"width":512,"height":768,"steps":9,"seed":77,"cfgScale":1.0,"sampleMethod":"flow_match","batchCount":1}}"""
        )

        assertEquals(512, effective.options.width)
        assertEquals(768, effective.options.height)
        assertEquals(9, effective.options.steps)
        assertEquals(77, effective.options.seed)
        assertEquals("flow_match", effective.options.sampleMethod)
        assertTrue(effective.displayDetails().contains("seed 77"))
    }

    @Test
    fun shareJsonIsPortableAndPreservesExplicitEmptyNegativePrompt() {
        val metadata = ImageGenerationHistoryMetadata(
            backend = ImageBackend.LOCAL,
            modelId = "portable-model-id",
            modelName = "Portable Model",
            requestPrompt = "reuse this prompt",
            options = LocalImageGenerationOptions(
                negativePrompt = "",
                width = 768,
                height = 512,
                steps = 18,
                seed = 123,
                cfgScale = 6.5,
                sampleMethod = "euler_a",
                taskMode = LocalImageTaskMode.IMG2IMG,
                strength = 0.7,
                clipSkip = 2,
                batchCount = 2,
                vaeTiling = LocalImageVaeTilingOptions(512, 0.5)
            ),
            inputDraft = LocalImageInputDraft(
                taskMode = LocalImageTaskMode.IMG2IMG,
                inputImageReference = "content://private.provider/document/secret-path",
                strength = 0.7
            ),
            nativeExecutionJson = """{"modelPath":"/data/user/0/private/model.gguf"}"""
        )

        val raw = metadata.toShareJson()
        val shared = JSONObject(raw)

        assertEquals("mca.image.generation.parameters", shared.getString("schema"))
        assertEquals("explicit", shared.getString("negativePromptMode"))
        assertEquals("", shared.getString("negativePrompt"))
        assertEquals("img2img", shared.getString("taskMode"))
        assertEquals(0.7, shared.getDouble("strength"), 0.0)
        assertEquals(512, shared.getJSONObject("vaeTiling").getInt("tileSize"))
        assertFalse(raw.contains("content://"))
        assertFalse(raw.contains("/data/user/"))
        assertFalse(shared.has("nativeExecution"))
        assertFalse(shared.has("inputImageReference"))
    }

    @Test
    fun shareJsonKeepsModelDefaultNegativePromptDistinctFromExplicitEmpty() {
        val metadata = ImageGenerationHistoryMetadata(
            backend = ImageBackend.LOCAL,
            modelId = "model-default-negative",
            modelName = "Default Negative Model",
            requestPrompt = "a prompt",
            options = LocalImageGenerationOptions(negativePrompt = null),
            inputDraft = LocalImageInputDraft()
        )

        val shared = JSONObject(metadata.toShareJson())

        assertEquals("model_default", shared.getString("negativePromptMode"))
        assertFalse(shared.has("negativePrompt"))
    }

    @Test
    fun requiredHistoryInputsRetainOnlyCurrentModeContentReferences() {
        val metadata = ImageGenerationHistoryMetadata(
            backend = ImageBackend.LOCAL,
            modelId = "inpaint-model",
            modelName = "Inpaint Model",
            requestPrompt = "repair the selected area",
            options = LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.INPAINT,
                strength = 0.6
            ),
            inputDraft = LocalImageInputDraft(
                taskMode = LocalImageTaskMode.INPAINT,
                inputImageReference = "content://documents/source",
                maskImageReference = "content://documents/mask",
                strength = 0.6
            )
        )

        assertEquals(
            setOf("content://documents/source", "content://documents/mask"),
            metadata.requiredContentInputReferences()
        )
        assertTrue(metadata.canRecreate())
    }

    @Test
    fun LoRAHistoryPreservesIdentityAndMultiplierWithoutDeviceLocalPath() {
        val adapter = LocalImagePreparedLora(
            id = "11111111-1111-4111-8111-111111111111",
            name = "Portrait",
            path = "/data/user/0/com.muyuchat.mca/files/image_loras/private.safetensors",
            sha256 = "d".repeat(64),
            sizeBytes = 1_024L,
            multiplier = 0.75
        )
        val metadata = ImageGenerationHistoryMetadata(
            backend = ImageBackend.LOCAL,
            modelId = "model-lora",
            modelName = "LoRA Model",
            requestPrompt = "portrait",
            options = LocalImageGenerationOptions(loras = listOf(adapter)),
            inputDraft = LocalImageInputDraft()
        )

        val raw = metadata.toJsonString()
        val restored = requireNotNull(ImageGenerationHistoryMetadata.fromJsonOrNull(raw))
        val shared = JSONObject(metadata.toShareJson())

        assertFalse(raw.contains(adapter.path))
        assertTrue(restored.options.loras.isEmpty())
        assertEquals(adapter.id, restored.loras.single().id)
        assertEquals(adapter.multiplier, restored.loras.single().multiplier, 0.0)
        assertEquals(adapter.id, shared.getJSONArray("loras").getJSONObject(0).getString("id"))
        assertFalse(metadata.toPortableBackupJsonString().contains(adapter.path))

        val overlongName = JSONObject(raw)
        overlongName.getJSONArray("loras").getJSONObject(0).put("name", "x".repeat(129))
        assertNull(ImageGenerationHistoryMetadata.fromJsonOrNull(overlongName.toString()))

        val injectedPath = JSONObject(raw)
        injectedPath.getJSONArray("loras").getJSONObject(0)
            .put("path", "/data/user/0/private/adapter.safetensors")
        assertNull(ImageGenerationHistoryMetadata.fromJsonOrNull(injectedPath.toString()))

        val pathLikeName = JSONObject(raw)
        pathLikeName.getJSONArray("loras").getJSONObject(0)
            .put("name", "content://private.provider/adapter")
        assertNull(ImageGenerationHistoryMetadata.fromJsonOrNull(pathLikeName.toString()))
    }

    @Test
    fun portableBackupMetadataDropsDeviceLocalInputsAndDisablesImmediateRecreate() {
        val metadata = ImageGenerationHistoryMetadata(
            backend = ImageBackend.LOCAL,
            modelId = "portable-history-model",
            modelName = "Portable History Model",
            requestPrompt = "restore the parameters",
            options = LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.INPAINT,
                strength = 0.6,
                steps = 20
            ),
            inputDraft = LocalImageInputDraft(
                taskMode = LocalImageTaskMode.INPAINT,
                inputImageReference = "content://documents/private-input",
                maskImageReference = "file:///data/user/0/private-mask.png",
                strength = 0.6
            ),
            nativeExecutionJson = """{"modelPath":"/data/user/0/private-model"}"""
        )

        val localRaw = metadata.toJsonString()
        assertFalse(localRaw.contains("file:///"))
        assertFalse(localRaw.contains("/data/user/"))
        assertFalse(localRaw.contains("modelPath"))

        val raw = metadata.toPortableBackupJsonString()
        val restored = requireNotNull(ImageGenerationHistoryMetadata.fromJsonOrNull(raw))

        assertFalse(raw.contains("content://"))
        assertFalse(raw.contains("file:///"))
        assertFalse(raw.contains("nativeExecution"))
        assertEquals(LocalImageTaskMode.INPAINT, restored.inputDraft.taskMode)
        assertEquals(0.6, restored.inputDraft.strength ?: 0.0, 0.0)
        assertNull(restored.inputDraft.inputImageReference)
        assertNull(restored.inputDraft.maskImageReference)
        assertFalse(restored.canRecreate())
        assertTrue(restored.requiredContentInputReferences().isEmpty())
    }

    @Test
    fun upscaleLineagePreservesSourcePresetAndNeverPersistsNativePaths() {
        val upscaler = LocalImagePreparedUpscaler(
            id = "22222222-2222-4222-8222-222222222222",
            name = "Photo 4x",
            path = "/data/user/0/com.muyuchat.mca/files/image_upscalers/private.pth",
            sha256 = "a".repeat(64),
            sizeBytes = 1_024L
        )
        val lineage = ImageUpscaleHistoryMetadata.fromNativeExecution(
            sourceImageId = "11111111-1111-4111-8111-111111111111",
            sourceWidthHint = 512,
            sourceHeightHint = 384,
            upscaler = upscaler,
            targetScale = 3,
            tileSize = 128,
            threads = 4,
            outputWidth = 1_536,
            outputHeight = 1_152,
            nativeExecutionJson = validUpscaleExecution(upscaler)
        )
        val source = ImageGenerationHistoryMetadata(
            backend = ImageBackend.LOCAL,
            modelId = "source-model",
            modelName = "Source Model",
            requestPrompt = "keep every source parameter",
            options = LocalImageGenerationOptions(
                negativePrompt = "",
                width = 512,
                height = 384,
                steps = 23,
                seed = 99,
                cfgScale = 6.5,
                sampleMethod = "euler_a"
            ),
            inputDraft = LocalImageInputDraft(),
            nativeExecutionJson = JSONObject()
                .put("modelPath", "/data/user/0/private/model.gguf")
                .put("filesDir", "/data/user/0/private/files")
                .put("elapsedMs", 1234)
                .put("nested", JSONObject()
                    .put("outputPath", "/data/user/0/private/output.png")
                    .put("nativeStageMask", 247))
                .toString()
        )

        val raw = source.withUpscale(lineage).toJsonString()
        val restored = requireNotNull(ImageGenerationHistoryMetadata.fromJsonOrNull(raw))

        assertEquals(source.options, restored.options)
        assertEquals(source.requestPrompt, restored.requestPrompt)
        assertEquals(lineage, restored.upscaleHistory.single())
        assertTrue(restored.displayDetails().contains("ESRGAN 3x"))
        listOf(
            upscaler.path,
            "/data/user/",
            "modelPath",
            "outputPath",
            "filesDir",
            "cacheDir"
        ).forEach { secret -> assertFalse("Leaked private path evidence: $secret", raw.contains(secret)) }
        assertTrue(raw.contains("nativeStageMask"))
        assertTrue(raw.contains("nativeGenerationSequence"))

        val forged = JSONObject(raw)
        forged.getJSONArray("upscaleHistory").getJSONObject(0)
            .put("sourceWidth", 1_500)
            .put("sourceHeight", 1_000)
            .put("outputWidth", 4_500)
            .put("outputHeight", 3_000)
        assertNull(ImageGenerationHistoryMetadata.fromJsonOrNull(forged.toString()))

        val overflowing = JSONObject(raw)
        overflowing.getJSONArray("upscaleHistory").getJSONObject(0)
            .put("sourceWidth", Int.MAX_VALUE)
        assertNull(
            ImageUpscaleHistoryMetadata.fromJsonOrNull(
                overflowing.getJSONArray("upscaleHistory").getJSONObject(0)
            )
        )
        assertNull(ImageGenerationHistoryMetadata.fromJsonOrNull(overflowing.toString()))

        val tooManyPixels = JSONObject(raw)
        tooManyPixels.getJSONArray("upscaleHistory").getJSONObject(0)
            .put("sourceWidth", 2_000)
            .put("sourceHeight", 2_001)
            .put("outputWidth", 4_000)
            .put("outputHeight", 4_002)
            .put("targetScale", 2)
            .put("nativeScale", 2)
            .put("postResizeApplied", false)
        assertNull(ImageGenerationHistoryMetadata.fromJsonOrNull(tooManyPixels.toString()))

        val pathInjected = JSONObject(raw)
        pathInjected.getJSONArray("upscaleHistory").getJSONObject(0)
            .put("outputPath", "/data/user/0/private/injected.png")
        assertNull(ImageGenerationHistoryMetadata.fromJsonOrNull(pathInjected.toString()))

        val pathLikeUpscalerName = JSONObject(raw)
        pathLikeUpscalerName.getJSONArray("upscaleHistory").getJSONObject(0)
            .put("upscalerName", "file:///data/user/0/private/upscaler.pth")
        assertNull(ImageGenerationHistoryMetadata.fromJsonOrNull(pathLikeUpscalerName.toString()))
    }

    @Test
    fun recursiveNativeSanitizerDropsLocatorsButKeepsNonPathEvidence() {
        val raw = JSONObject()
            .put("nativeExecution", true)
            .put("bundleRoot", "/data/user/0/private/bundle")
            .put("inputUri", "content://documents/private-input")
            .put("resource", "android.resource://com.example/private")
            .put("networkFile", "\\\\private-host\\share\\output.png")
            .put("outputs", org.json.JSONArray().put(JSONObject()
                .put("index", 0)
                .put("path", "/data/user/0/private/output.png")
                .put("mimeType", "image/png")))
            .put("nested", JSONObject()
                .put("modelPath", "C:\\private\\model.bin")
                .put("tileCount", 12)
                .put("stages", org.json.JSONArray()
                    .put("compute")
                    .put("/data/user/0/private/output.png")))
            .toString()

        val sanitized = sanitizeNativeExecutionJson(raw)
        val json = JSONObject(sanitized)

        assertTrue(json.getBoolean("nativeExecution"))
        assertEquals(12, json.getJSONObject("nested").getInt("tileCount"))
        assertFalse(json.getJSONArray("outputs").getJSONObject(0).has("path"))
        assertEquals("compute", json.getJSONObject("nested").getJSONArray("stages").getString(0))
        listOf(
            "bundleRoot",
            "modelPath",
            "content://",
            "android.resource:",
            "/data/user/",
            "C:\\",
            "\\\\private-host"
        )
            .forEach { secret -> assertFalse(sanitized.contains(secret)) }
    }

    @Test
    fun upscaleEvidenceEnforcesMasksDimensionsAndSeparateNativeIntermediateBounds() {
        val invalidMask = runCatching {
            ImageUpscaleHistoryMetadata(
                sourceImageId = "11111111-1111-4111-8111-111111111111",
                upscalerId = "22222222-2222-4222-8222-222222222222",
                upscalerName = "Photo 4x",
                upscalerSha256 = "a".repeat(64),
                inputImageSha256 = "b".repeat(64),
                targetScale = 2,
                nativeScale = 4,
                tileSize = 128,
                threads = 4,
                sourceWidth = 512,
                sourceHeight = 384,
                outputWidth = 1_024,
                outputHeight = 768,
                postResizeApplied = true,
                physicalComputeCount = 6,
                physicalComputeSuccessCount = 6,
                physicalTileComputeCount = 6,
                physicalTileComputeSuccessCount = 6,
                tiledExecution = true,
                executionCompleted = true,
                nativeGenerationSequence = 7,
                nativeStageMask = -1,
                nativeDetailStageMask = -1,
                contextReleased = true
            )
        }
        assertTrue(invalidMask.isFailure)
        assertTrue(
            runCatching {
                ImageUpscaleHistoryMetadata(
                    sourceImageId = "11111111-1111-4111-8111-111111111111",
                    upscalerId = "22222222-2222-4222-8222-222222222222",
                    upscalerName = "Photo 4x",
                    upscalerSha256 = "a".repeat(64),
                    inputImageSha256 = "b".repeat(64),
                    targetScale = 2,
                    nativeScale = 2,
                    tileSize = 128,
                    threads = 4,
                    sourceWidth = 2_049,
                    sourceHeight = 1,
                    outputWidth = 4_098,
                    outputHeight = 2,
                    postResizeApplied = false,
                    physicalComputeCount = 17,
                    physicalComputeSuccessCount = 17,
                    physicalTileComputeCount = 17,
                    physicalTileComputeSuccessCount = 17,
                    tiledExecution = true,
                    executionCompleted = true,
                    nativeGenerationSequence = 7,
                    nativeStageMask = 255,
                    nativeDetailStageMask = 255,
                    contextReleased = true
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                ImageUpscaleHistoryMetadata(
                    sourceImageId = "not-an-image-uuid",
                    upscalerId = "22222222-2222-4222-8222-222222222222",
                    upscalerName = "x".repeat(129),
                    upscalerSha256 = "a".repeat(64),
                    inputImageSha256 = "b".repeat(64),
                    targetScale = 2,
                    nativeScale = 4,
                    tileSize = 128,
                    threads = 4,
                    sourceWidth = 512,
                    sourceHeight = 384,
                    outputWidth = 1_024,
                    outputHeight = 768,
                    postResizeApplied = true,
                    physicalComputeCount = 6,
                    physicalComputeSuccessCount = 6,
                    physicalTileComputeCount = 6,
                    physicalTileComputeSuccessCount = 6,
                    tiledExecution = true,
                    executionCompleted = true,
                    nativeGenerationSequence = 7,
                    nativeStageMask = 255,
                    nativeDetailStageMask = 255,
                    contextReleased = true
                )
            }.isFailure
        )

        val upscaler = LocalImagePreparedUpscaler(
            id = "22222222-2222-4222-8222-222222222222",
            name = "Photo 4x",
            path = "/data/user/0/private.pth",
            sha256 = "a".repeat(64),
            sizeBytes = 1_024L
        )
        val mismatched = JSONObject(validUpscaleExecution(upscaler))
        mismatched.getJSONObject("nativeEffective").put("width", 1_999)
        assertTrue(
            runCatching {
                ImageUpscaleHistoryMetadata.fromNativeExecution(
                    sourceImageId = "11111111-1111-4111-8111-111111111111",
                    sourceWidthHint = 512,
                    sourceHeightHint = 384,
                    upscaler = upscaler,
                    targetScale = 3,
                    tileSize = 128,
                    threads = 4,
                    outputWidth = 1_536,
                    outputHeight = 1_152,
                    nativeExecutionJson = mismatched.toString()
                )
            }.isFailure
        )

        val legalLargeIntermediate = JSONObject(validUpscaleExecution(upscaler))
        legalLargeIntermediate.getJSONObject("nativeEffective")
            .put("sourceWidth", 2_000)
            .put("sourceHeight", 2_000)
            .put("requestedTargetScale", 2)
            .put("width", 8_000)
            .put("height", 8_000)
        legalLargeIntermediate.getJSONObject("productOutput")
            .put("targetScale", 2)
            .put("sourceWidth", 2_000)
            .put("sourceHeight", 2_000)
            .put("nativeWidth", 8_000)
            .put("nativeHeight", 8_000)
            .put("width", 4_000)
            .put("height", 4_000)
        val largeIntermediateMetadata = ImageUpscaleHistoryMetadata.fromNativeExecution(
            sourceImageId = "11111111-1111-4111-8111-111111111111",
            sourceWidthHint = 2_000,
            sourceHeightHint = 2_000,
            upscaler = upscaler,
            targetScale = 2,
            tileSize = 128,
            threads = 4,
            outputWidth = 4_000,
            outputHeight = 4_000,
            nativeExecutionJson = legalLargeIntermediate.toString()
        )
        assertEquals(4_000, largeIntermediateMetadata.outputWidth)
        assertEquals(4_000, largeIntermediateMetadata.outputHeight)

        val oversizedNativeSide = JSONObject(validUpscaleExecution(upscaler))
        oversizedNativeSide.getJSONObject("nativeEffective")
            .put("sourceWidth", 1_025)
            .put("nativeScale", 8)
            .put("width", 8_200)
            .put("height", 3_072)
        oversizedNativeSide.getJSONObject("productOutput")
            .put("nativeFixedScale", 8)
            .put("sourceWidth", 1_025)
            .put("nativeWidth", 8_200)
            .put("nativeHeight", 3_072)
            .put("width", 3_075)
        assertTrue(
            runCatching {
                ImageUpscaleHistoryMetadata.fromNativeExecution(
                    sourceImageId = "11111111-1111-4111-8111-111111111111",
                    sourceWidthHint = 1_025,
                    sourceHeightHint = 384,
                    upscaler = upscaler,
                    targetScale = 3,
                    tileSize = 128,
                    threads = 4,
                    outputWidth = 3_075,
                    outputHeight = 1_152,
                    nativeExecutionJson = oversizedNativeSide.toString()
                )
            }.isFailure
        )

        val oversizedNativePixels = JSONObject(validUpscaleExecution(upscaler))
        oversizedNativePixels.getJSONObject("nativeEffective")
            .put("sourceWidth", 1_024)
            .put("sourceHeight", 1_024)
            .put("nativeScale", 8)
            .put("width", 8_192)
            .put("height", 8_192)
        oversizedNativePixels.getJSONObject("productOutput")
            .put("nativeFixedScale", 8)
            .put("sourceWidth", 1_024)
            .put("sourceHeight", 1_024)
            .put("nativeWidth", 8_192)
            .put("nativeHeight", 8_192)
            .put("width", 3_072)
            .put("height", 3_072)
        assertTrue(
            runCatching {
                ImageUpscaleHistoryMetadata.fromNativeExecution(
                    sourceImageId = "11111111-1111-4111-8111-111111111111",
                    sourceWidthHint = 1_024,
                    sourceHeightHint = 1_024,
                    upscaler = upscaler,
                    targetScale = 3,
                    tileSize = 128,
                    threads = 4,
                    outputWidth = 3_072,
                    outputHeight = 3_072,
                    nativeExecutionJson = oversizedNativePixels.toString()
                )
            }.isFailure
        )
    }

    private fun validUpscaleExecution(upscaler: LocalImagePreparedUpscaler): String =
        JSONObject()
            .put("ok", true)
            .put("nativeExecution", true)
            .put("contextReleased", true)
            .put("path", "/data/user/0/private/cache/output.png")
            .put("nativeGenerationSequence", 7L)
            .put("nativeStageMask", 255L)
            .put("nativeDetailStageMask", 255L)
            .put(
                "nativeEffective",
                JSONObject()
                    .put("operation", "ESRGAN_UPSCALE")
                    .put("runtime", LocalImageRuntime.STABLE_DIFFUSION_CPP.name)
                    .put("backendMode", "cpu")
                    .put("fallback", false)
                    .put("upscalerId", upscaler.id)
                    .put("upscalerSha256", upscaler.sha256)
                    .put("upscalerSizeBytes", upscaler.sizeBytes)
                    .put("modelHashVerified", true)
                    .put("modelFileIdentityStable", true)
                    .put("inputImageSha256", "b".repeat(64))
                    .put("sourceWidth", 512)
                    .put("sourceHeight", 384)
                    .put("nativeScale", 4)
                    .put("requestedTargetScale", 3)
                    .put("width", 2_048)
                    .put("height", 1_536)
                    .put("tileSize", 128)
                    .put("threads", 4)
                    .put("physicalComputeCount", 6L)
                    .put("physicalComputeSuccessCount", 6L)
                    .put("physicalTileComputeCount", 6L)
                    .put("physicalTileComputeSuccessCount", 6L)
                    .put("tiledExecution", true)
                    .put("executionCompleted", true)
                    .put("nativeGenerationSequence", 7L)
            )
            .put(
                "productOutput",
                JSONObject()
                    .put("targetScale", 3)
                    .put("nativeFixedScale", 4)
                    .put("postResizeApplied", true)
                    .put("postResizeMethod", "android_bitmap_filtered")
                    .put("sourceWidth", 512)
                    .put("sourceHeight", 384)
                    .put("nativeWidth", 2_048)
                    .put("nativeHeight", 1_536)
                    .put("width", 1_536)
                    .put("height", 1_152)
                    .put("mimeType", "image/png")
            )
            .toString()
}
