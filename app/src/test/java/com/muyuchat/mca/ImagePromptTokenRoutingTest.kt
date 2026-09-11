package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImagePromptTokenRoutingTest {
    @Test
    fun `stable diffusion clip families use native sdcpp tokenizer`() {
        listOf(
            LocalImageModelFamily.SD15,
            LocalImageModelFamily.SD21,
            LocalImageModelFamily.SDXL,
            LocalImageModelFamily.SD_TURBO,
        ).forEach { family ->
            assertEquals(
                ImagePromptTokenMeasurementRoute.SDCPP_CLIP,
                imagePromptTokenMeasurementRoute(
                    LocalImageRuntime.STABLE_DIFFUSION_CPP,
                    family,
                    ImageTokenizerBackend.SDCPP_NATIVE,
                ),
            )
        }
    }

    @Test
    fun `non clip sdcpp families use an explicitly estimated count`() {
        listOf(
            LocalImageModelFamily.FLUX,
            LocalImageModelFamily.Z_IMAGE,
            LocalImageModelFamily.QWEN_IMAGE,
            LocalImageModelFamily.LONGCAT_IMAGE,
            LocalImageModelFamily.CUSTOM,
        ).forEach { family ->
            assertEquals(
                ImagePromptTokenMeasurementRoute.ESTIMATE,
                imagePromptTokenMeasurementRoute(
                    LocalImageRuntime.STABLE_DIFFUSION_CPP,
                    family,
                    ImageTokenizerBackend.SDCPP_NATIVE,
                ),
            )
        }
    }

    @Test
    fun `mnn and tokenizers cpp routes remain on existing bridge`() {
        listOf(
            ImageTokenizerBackend.MNN_MTOK,
            ImageTokenizerBackend.TOKENIZERS_CPP,
        ).forEach { backend ->
            assertEquals(
                ImagePromptTokenMeasurementRoute.MNN_BRIDGE,
                imagePromptTokenMeasurementRoute(
                    LocalImageRuntime.MNN_DIFFUSION,
                    LocalImageModelFamily.SD15,
                    backend,
                ),
            )
        }
    }

    @Test
    fun `inconsistent runtime and tokenizer backend is unavailable`() {
        assertNull(
            imagePromptTokenMeasurementRoute(
                LocalImageRuntime.QNN_HTP,
                LocalImageModelFamily.SDXL,
                ImageTokenizerBackend.SDCPP_NATIVE,
            ),
        )
    }
}
