package com.muyuchat.mca

import com.muyuchat.api.local.ImageGenerationApiContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageApiRequestMappingTest {
    @Test
    fun `API execution sanitizer removes nested locators and preserves physical batch evidence`() {
        val sanitized = sanitizedLocalImageApiExecution(
            JSONObject()
                .put("batchCount", 2)
                .put("actualSamplingPassCount", 2)
                .put("path", "/data/user/0/private/output.png")
                .put(
                    "outputs",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("index", 0)
                            .put("path", "/data/user/0/private/output-0.png")
                    )
                )
                .toString()
        )

        assertEquals(2, sanitized.getInt("batchCount"))
        assertEquals(2, sanitized.getInt("actualSamplingPassCount"))
        assertFalse(sanitized.has("path"))
        assertFalse(sanitized.getJSONArray("outputs").getJSONObject(0).has("path"))
        assertTrue(sanitized.getJSONArray("outputs").getJSONObject(0).has("index"))
    }

    @Test
    fun `all API controls map exactly into worker options and input draft`() {
        val input = "data:image/png;base64,AAAA"
        val mask = "data:image/png;base64,BBBB"
        val request = ImageGenerationApiContract.parseRequest(
            JSONObject()
                .put("model", "image-model")
                .put("prompt", "replace the object")
                .put("negative_prompt", "")
                .put("size", "768x512")
                .put("n", 8)
                .put("response_format", "b64_json")
                .put("seed", 20260717)
                .put("steps", 24)
                .put("cfg_scale", 6.5)
                .put("sampler", "dpmpp_2m")
                .put("task_mode", "inpaint")
                .put("input_image", input)
                .put("mask_image", mask)
                .put("strength", 0.72)
                .put("clip_skip", 2)
                .put(
                    "vae_tiling",
                    JSONObject().put("tile_size", 512).put("overlap", 0.5)
                )
                .put(
                    "preview",
                    JSONObject().put("interval", 3).put("mode", "vae")
                )
                .toString()
        )

        val dispatch = request.toLocalImageApiDispatch()
        assertEquals("", dispatch.options.negativePrompt)
        assertEquals(768, dispatch.options.width)
        assertEquals(512, dispatch.options.height)
        assertEquals(24, dispatch.options.steps)
        assertEquals(20260717, dispatch.options.seed)
        assertEquals(6.5, dispatch.options.cfgScale ?: -1.0, 0.0)
        assertEquals("dpmpp_2m", dispatch.options.sampleMethod)
        assertEquals(2, dispatch.options.clipSkip)
        assertEquals(8, dispatch.options.batchCount)
        assertEquals(512, dispatch.options.vaeTiling?.tileSize)
        assertEquals(0.5, dispatch.options.vaeTiling?.overlap ?: -1.0, 0.0)
        assertEquals(3, dispatch.options.preview?.interval)
        assertEquals(LocalImagePreviewMode.VAE, dispatch.options.preview?.mode)
        assertEquals(LocalImageTaskMode.INPAINT, dispatch.inputDraft.taskMode)
        assertEquals(input, dispatch.inputDraft.inputImageReference)
        assertEquals(mask, dispatch.inputDraft.maskImageReference)
        assertNull(dispatch.inputDraft.controlImageReference)
        assertEquals(0.72, dispatch.inputDraft.strength ?: -1.0, 0.0)
        assertNull(dispatch.inputDraft.controlStrength)
    }

    @Test
    fun `control image and control strength map without inventing img2img inputs`() {
        val request = ImageGenerationApiContract.parseRequest(
            """{"prompt":"edges","task_mode":"control","control_image":"/data/local/tmp/control.png","control_strength":1.25}"""
        )

        val draft = request.toLocalImageApiDispatch().inputDraft
        assertEquals(LocalImageTaskMode.CONTROL, draft.taskMode)
        assertEquals("/data/local/tmp/control.png", draft.controlImageReference)
        assertEquals(1.25, draft.controlStrength ?: -1.0, 0.0)
        assertNull(draft.inputImageReference)
        assertNull(draft.maskImageReference)
        assertNull(draft.strength)
    }
}
