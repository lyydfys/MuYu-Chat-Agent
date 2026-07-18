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
}
