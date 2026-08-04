package com.muyuchat.mca

import com.muyuchat.feature.chat.ImageGenerationUiOptions
import com.muyuchat.feature.chat.ImageGenerationUiPreviewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageUiPreviewMappingTest {
    @Test
    fun projectionModeAndIntervalRoundTripTogether() {
        val preview = ImageGenerationUiOptions(
            previewMode = ImageGenerationUiPreviewMode.PROJECTION,
            previewInterval = 3
        ).toLocalImagePreviewOptions()

        assertEquals(LocalImagePreviewMode.PROJECTION, preview?.mode)
        assertEquals(3, preview?.interval)
    }

    @Test
    fun vaeModeAndIntervalRoundTripTogether() {
        val preview = ImageGenerationUiOptions(
            previewMode = ImageGenerationUiPreviewMode.VAE,
            previewInterval = 5
        ).toLocalImagePreviewOptions()

        assertEquals(LocalImagePreviewMode.VAE, preview?.mode)
        assertEquals(5, preview?.interval)
    }

    @Test
    fun absentIntervalDisablesPreviewEvenWhenModeIsKnown() {
        assertNull(
            ImageGenerationUiOptions(
                previewMode = ImageGenerationUiPreviewMode.VAE,
                previewInterval = null
            ).toLocalImagePreviewOptions()
        )
    }

    @Test
    fun intervalWithoutModeIsRejectedInsteadOfFallingBackToProjection() {
        val error = runCatching {
            ImageGenerationUiOptions(previewInterval = 4).toLocalImagePreviewOptions()
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("explicit mode"))
    }
}
