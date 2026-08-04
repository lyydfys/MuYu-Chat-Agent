package com.muyuchat.feature.chat

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationParameterImportTest {
    @Test
    fun `MCA raw JSON imports bounded portable fields`() {
        val raw = JSONObject()
            .put("schema", "mca.image.generation.parameters")
            .put("version", 1)
            .put("modelId", "cyberrealistic-sd15-qnn228-8gen2")
            .put("prompt", "ceramic lantern")
            .put("negativePromptMode", "explicit")
            .put("negativePrompt", "text, watermark")
            .put("width", 512)
            .put("height", 512)
            .put("steps", 20)
            .put("cfgScale", 7.0)
            .put("seed", 42)
            .put("sampleMethod", "dpmpp_2m")
            .put("batchCount", 1)
            .put("strength", 0.65)
            .put("controlStrength", 1.25)
            .put(
                "loras",
                JSONArray().put(
                    JSONObject()
                        .put("id", "5e792d38-5e10-4b82-9f19-a93d26421b52")
                        .put("name", "detail")
                        .put("multiplier", 0.75)
                )
            )
            .toString()

        val imported = ImageGenerationParameterImportCodec.decode(raw).getOrThrow()

        assertEquals(ImageGenerationParameterImportSource.MCA, imported.source)
        assertEquals("cyberrealistic-sd15-qnn228-8gen2", imported.sourceModelId)
        assertEquals("ceramic lantern", imported.preset.prompt)
        assertEquals("text, watermark", imported.preset.negativePrompt)
        assertEquals(512, imported.preset.width)
        assertEquals("dpmpp_2m", imported.preset.sampleMethod)
        assertEquals(0.75, imported.preset.loras.single().multiplier, 0.0)
        assertEquals(0.65, imported.strength ?: Double.NaN, 0.0)
        assertEquals(1.25, imported.controlStrength ?: Double.NaN, 0.0)
        assertTrue(ImageGenerationParameterImportField.SIZE in imported.fields)
        assertTrue(ImageGenerationParameterImportField.LORA in imported.fields)
        assertTrue(ImageGenerationParameterImportField.STRENGTH in imported.fields)
        assertTrue(ImageGenerationParameterImportField.CONTROL_STRENGTH in imported.fields)
    }

    @Test
    fun `MCA Base64 envelope round trips`() {
        val raw = JSONObject()
            .put("schema", "mca.image.generation.parameters")
            .put("version", 1)
            .put("prompt", "white robot")
            .put("negativePromptMode", "model_default")
            .put("strength", 0.55)
            .put("controlStrength", 1.1)
            .toString()

        val encoded = ImageGenerationParameterImportCodec.encodeMcaBase64(raw)
        val imported = ImageGenerationParameterImportCodec.decode(encoded).getOrThrow()

        assertTrue(encoded.startsWith("MCAPARAMS:"))
        assertEquals("white robot", imported.preset.prompt)
        assertEquals(null, imported.preset.negativePrompt)
        assertEquals(0.55, imported.strength ?: Double.NaN, 0.0)
        assertEquals(1.1, imported.controlStrength ?: Double.NaN, 0.0)
        assertTrue(ImageGenerationParameterImportField.NEGATIVE_PROMPT in imported.fields)
    }

    @Test
    fun `Local Dream raw and Base64 parameters interoperate`() {
        val raw = JSONObject()
            .put("_localdream_params", true)
            .put("v", 1)
            .put("model_id", "sd15")
            .put("prompt", "castle above clouds")
            .put("negative_prompt", "low quality")
            .put("steps", 24)
            .put("cfg", 6.5)
            .put("seed", "123")
            .put("scheduler", "dpmpp_2m")
            .put("denoise_strength", 0.7)
            .put("mode", "IMG2IMG")
            .toString()
        val encoded = "LDPARAMS:" + Base64.getEncoder()
            .encodeToString(raw.toByteArray(Charsets.UTF_8))

        listOf(raw, encoded).forEach { payload ->
            val imported = ImageGenerationParameterImportCodec.decode(payload).getOrThrow()
            assertEquals(ImageGenerationParameterImportSource.LOCAL_DREAM, imported.source)
            assertEquals("castle above clouds", imported.preset.prompt)
            assertEquals(24, imported.preset.steps)
            assertEquals(123, imported.preset.seed)
            assertEquals(0.7, imported.strength ?: Double.NaN, 0.0)
            assertEquals("IMG2IMG", imported.taskMode)
            assertTrue(ImageGenerationParameterImportField.STRENGTH in imported.fields)
        }
    }

    @Test
    fun `unidentified oversized and unsafe scalar payloads fail closed`() {
        assertTrue(ImageGenerationParameterImportCodec.decode("{\"prompt\":\"x\"}").isFailure)
        assertTrue(
            ImageGenerationParameterImportCodec.decode(
                JSONObject()
                    .put("schema", "mca.image.generation.parameters")
                    .put("version", 1)
                    .put("prompt", "x")
                    .put("negativePromptMode", "model_default")
                    .put("steps", 0)
                    .toString()
            ).isFailure
        )
        assertTrue(ImageGenerationParameterImportCodec.decode("x".repeat(64 * 1024 + 1)).isFailure)
    }

    @Test
    fun `fractional integer fields are rejected instead of truncated`() {
        val base = JSONObject()
            .put("schema", "mca.image.generation.parameters")
            .put("version", 1)
            .put("prompt", "x")
            .put("negativePromptMode", "model_default")

        listOf(
            JSONObject(base.toString()).put("steps", 4.5),
            JSONObject(base.toString()).put("seed", 42.25),
            JSONObject(base.toString()).put(
                "vaeTiling",
                JSONObject().put("tileSize", 512.5).put("overlap", 0.5)
            )
        ).forEach { payload ->
            assertTrue(ImageGenerationParameterImportCodec.decode(payload.toString()).isFailure)
        }

        val localDream = JSONObject()
            .put("_localdream_params", true)
            .put("v", 1)
            .put("prompt", "x")
            .put("seed", 8.75)
        assertTrue(ImageGenerationParameterImportCodec.decode(localDream.toString()).isFailure)
    }

    @Test
    fun `strength fields require strict numeric ranges`() {
        val mca = JSONObject()
            .put("schema", "mca.image.generation.parameters")
            .put("version", 1)
            .put("prompt", "x")
            .put("negativePromptMode", "model_default")

        listOf(
            JSONObject(mca.toString()).put("strength", -0.01),
            JSONObject(mca.toString()).put("strength", 1.01),
            JSONObject(mca.toString()).put("strength", "0.5"),
            JSONObject(mca.toString()).put("controlStrength", -0.01),
            JSONObject(mca.toString()).put("controlStrength", 2.01),
            JSONObject(mca.toString()).put("controlStrength", "1.0")
        ).forEach { payload ->
            assertTrue(ImageGenerationParameterImportCodec.decode(payload.toString()).isFailure)
        }

        val localDream = JSONObject()
            .put("_localdream_params", true)
            .put("v", 1)
            .put("prompt", "x")
        listOf(
            JSONObject(localDream.toString()).put("denoise_strength", -0.01),
            JSONObject(localDream.toString()).put("denoise_strength", 1.01),
            JSONObject(localDream.toString()).put("denoise_strength", "0.5")
        ).forEach { payload ->
            assertTrue(ImageGenerationParameterImportCodec.decode(payload.toString()).isFailure)
        }

        assertEquals(
            0.0,
            ImageGenerationParameterImportCodec.decode(
                JSONObject(localDream.toString()).put("denoise_strength", 0.0).toString()
            ).getOrThrow().strength ?: Double.NaN,
            0.0
        )
        assertEquals(
            0.0,
            ImageGenerationParameterImportCodec.decode(
                JSONObject(mca.toString()).put("strength", 0.0).toString()
            ).getOrThrow().strength ?: Double.NaN,
            0.0
        )
    }

    @Test
    fun `strength compatibility follows current mode without switching to source mode`() {
        val imported = ImageGenerationParameterImportCodec.decode(
            JSONObject()
                .put("schema", "mca.image.generation.parameters")
                .put("version", 1)
                .put("prompt", "keep current mode")
                .put("negativePromptMode", "model_default")
                .put("taskMode", "control")
                .put("strength", 0.6)
                .put("controlStrength", 1.4)
                .toString()
        ).getOrThrow()

        val img2img = compatibleFields(imported, ImageGenerationUiTaskMode.IMG2IMG)
        val control = compatibleFields(imported, ImageGenerationUiTaskMode.CONTROL)
        val textToImage = compatibleFields(imported, ImageGenerationUiTaskMode.TEXT_TO_IMAGE)

        assertTrue(ImageGenerationParameterImportField.STRENGTH in img2img)
        assertFalse(ImageGenerationParameterImportField.CONTROL_STRENGTH in img2img)
        assertTrue(ImageGenerationParameterImportField.CONTROL_STRENGTH in control)
        assertFalse(ImageGenerationParameterImportField.STRENGTH in control)
        assertFalse(ImageGenerationParameterImportField.STRENGTH in textToImage)
        assertFalse(ImageGenerationParameterImportField.CONTROL_STRENGTH in textToImage)
        assertEquals("control", imported.taskMode)
    }

    @Test
    fun `sampler import follows current task mode`() {
        val imported = ImageGenerationParameterImportCodec.decode(
            JSONObject()
                .put("schema", "mca.image.generation.parameters")
                .put("version", 1)
                .put("prompt", "keep task mode")
                .put("negativePromptMode", "model_default")
                .put("sampleMethod", "pndm")
                .toString()
        ).getOrThrow()

        assertFalse(
            ImageGenerationParameterImportField.SAMPLER in
                compatibleFields(imported, ImageGenerationUiTaskMode.IMG2IMG)
        )
        assertTrue(
            ImageGenerationParameterImportField.SAMPLER in
                compatibleFields(imported, ImageGenerationUiTaskMode.TEXT_TO_IMAGE)
        )
    }

    @Test
    fun `pending selection applies nothing until explicit nonempty confirmation`() {
        val imported = ImageGenerationParameterImportCodec.decode(
            JSONObject()
                .put("schema", "mca.image.generation.parameters")
                .put("version", 1)
                .put("prompt", "pending")
                .put("negativePromptMode", "model_default")
                .toString()
        ).getOrThrow()
        val compatible = compatibleFields(imported, ImageGenerationUiTaskMode.TEXT_TO_IMAGE)
        val pending = ImageGenerationParameterImportSelection(imported, compatible)

        assertNull(pending.applicationOrNull(confirm = false))
        assertNull(pending.copy(selectedFields = emptySet()).applicationOrNull(confirm = true))
        assertNotNull(pending.applicationOrNull(confirm = true))
        assertEquals(compatible, pending.selectedFields)
    }

    @Test
    fun `private source references and unrelated image fields are not imported`() {
        val imported = ImageGenerationParameterImportCodec.decode(
            JSONObject()
                .put("schema", "mca.image.generation.parameters")
                .put("version", 1)
                .put("modelId", "/data/user/0/private/model.gguf")
                .put("prompt", "portable")
                .put("negativePromptMode", "model_default")
                .put("inputImage", "content://private/image")
                .put("apiKey", "secret")
                .toString()
        ).getOrThrow()

        assertNull(imported.sourceModelId)
        assertEquals(
            setOf(
                ImageGenerationParameterImportField.PROMPT,
                ImageGenerationParameterImportField.NEGATIVE_PROMPT
            ),
            imported.fields
        )
    }

    private fun compatibleFields(
        imported: ImageGenerationParameterImport,
        taskMode: ImageGenerationUiTaskMode
    ): Set<ImageGenerationParameterImportField> = compatibleImageGenerationParameterImportFields(
        imported = imported,
        currentTaskMode = taskMode,
        selectedModel = ChatModelChoice(
            id = "current-model",
            displayName = "Current Model",
            imageMinWidth = 512,
            imageMaxWidth = 1024,
            imageMinHeight = 512,
            imageMaxHeight = 1024,
            imageWidthMultiple = 64,
            imageHeightMultiple = 64,
            imageSupportedSamplers = listOf("euler", "dpmpp_2m", "pndm"),
            imageImg2ImgSupportedSamplers = listOf("euler", "dpmpp_2m")
        ),
        selectedModelIsCloud = false,
        supportsNegativePrompt = true,
        supportsClipSkip = true,
        supportsVaeTiling = true,
        supportsLora = true,
        availableLoraIds = emptySet(),
        maxBatchCount = 8
    )
}
