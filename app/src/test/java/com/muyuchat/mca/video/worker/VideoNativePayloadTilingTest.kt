package com.muyuchat.mca.video.worker

import com.muyuchat.core.video.contract.VideoLocalEngine
import com.muyuchat.core.video.contract.VideoNodeValue
import com.muyuchat.core.video.contract.VideoResolvedParams
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decode-quality switch must change exactly two payload fields and nothing else: TAE stays the
 * default (byte-compatible), and full VAE asks the engine for spatial tiling at latent tile 16 —
 * the only setting that actually splits a 480x832 latent (60x104) into more than one tile.
 */
class VideoNativePayloadTilingTest {

    private fun params() = VideoResolvedParams(
        width = 480,
        height = 832,
        frames = 5,
        fps = 24,
        steps = 4,
        sampler = "euler",
        guidance = VideoNodeValue.DecimalValue(60, 1),
        flowShift = VideoNodeValue.DecimalValue(30, 1),
        seed = 42,
        promptSha256 = "0".repeat(64),
        negativePromptSha256 = null,
        imageRef = null,
        engine = VideoLocalEngine.SDCPP_EXTERNAL,
    )

    // Mirrors the adapter: the TAE head is only staged/sent for the TAE profile.
    private fun build(fullVae: Boolean): JSONObject =
        videoNativeRequestPayload(
            runDir = "/tmp/run",
            stagingRoot = "/tmp",
            modelRoot = "/tmp/models",
            modelFingerprint = "fp",
            ditPath = "/tmp/dit.gguf",
            encoderPath = "/tmp/te.gguf",
            vaePath = "/tmp/vae.safetensors",
            taePath = if (fullVae) "" else "/tmp/tae.safetensors",
            prompt = "a cat",
            negativePrompt = "",
            imagePath = "",
            params = params(),
            fullVaeDecode = fullVae,
        )

    @Test
    fun `default payload keeps the TAE head and sends no tiling`() {
        val json = build(fullVae = false)
        assertEquals("tae", json.getString("vaeProfile"))
        assertFalse(json.has("tiling"))
        assertTrue(json.has("taePath"))
    }

    @Test
    fun `full vae asks for spatial tiling with latent tile 16`() {
        val json = build(fullVae = true)
        assertEquals("default", json.getString("vaeProfile"))
        val tiling = json.getJSONObject("tiling")
        assertTrue(tiling.getBoolean("spatial"))
        assertTrue(tiling.getBoolean("temporal"))
        assertEquals(16, tiling.getInt("tileSize"))
        assertEquals(1, tiling.getInt("tileFrames"))
        assertFalse("the TAE head is not sent when decoding with the full VAE", json.has("taePath"))
    }
}
