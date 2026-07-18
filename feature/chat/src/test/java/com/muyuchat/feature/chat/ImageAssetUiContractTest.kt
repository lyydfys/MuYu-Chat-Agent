package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageAssetUiContractTest {
    @Test
    fun `upscale badge uses typed scale instead of inherited display details`() {
        val image = imageAsset(
            source = "upscaled:ESRGAN",
            width = 1536,
            height = 1536,
            upscaleTargetScale = 3,
            generationDetails = "Local model · 文生图\n512×512 · 20 steps\nESRGAN 3x | upscaler"
        )

        assertEquals("ESRGAN 3×", imageAssetBadgeText(image))
        assertEquals(
            "高清放大",
            imageAssetBadgeText(image.copy(upscaleTargetScale = null))
        )
    }

    @Test
    fun `ordinary image badge remains its exact dimensions`() {
        assertEquals(
            "768×512",
            imageAssetBadgeText(
                imageAsset(
                    source = "generated:stable-diffusion-cpp",
                    width = 768,
                    height = 512
                )
            )
        )
    }

    private fun imageAsset(
        source: String,
        width: Int,
        height: Int,
        upscaleTargetScale: Int? = null,
        generationDetails: String = ""
    ): ImageAssetUiItem = ImageAssetUiItem(
        id = "11111111-1111-4111-8111-111111111111",
        name = "image.png",
        uriString = "content://images/image.png",
        source = source,
        prompt = "prompt",
        createdAtText = "07-18 18:00",
        sizeText = "1 MB",
        width = width,
        height = height,
        upscaleTargetScale = upscaleTargetScale,
        generationDetails = generationDetails
    )
}
