package com.muyuchat.feature.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageGenerationUiParameterSnapshotTest {
    @Test
    fun `version one remains readable without persisted image inputs`() {
        val raw = JSONObject()
            .put("version", 1)
            .put("taskModeName", ImageGenerationUiTaskMode.TEXT_TO_IMAGE.name)
            .put("strengthText", "0.75")
            .put("controlStrengthText", "1.0")
            .put("negativePrompt", "")
            .put("disableModelNegativePrompt", false)
            .put("clipSkipText", "")
            .put("vaeTilingEnabled", false)
            .put("batchCount", 1)
            .put("widthText", "512")
            .put("heightText", "512")
            .put("stepsText", "20")
            .put("cfgScaleText", "7")
            .put("seedText", "")
            .put("sampler", "euler")
            .toString()

        val snapshot = requireNotNull(ImageGenerationUiParameterSnapshot.fromJsonOrNull(raw))

        assertNull(snapshot.inputImageUri)
        assertNull(snapshot.maskImageUri)
        assertNull(snapshot.controlImageUri)
    }

    @Test
    fun `version two round trip preserves all image input uris`() {
        val expected = ImageGenerationUiParameterSnapshot(
            taskModeName = ImageGenerationUiTaskMode.INPAINT.name,
            strengthText = "0.65",
            controlStrengthText = "1.0",
            negativePrompt = "",
            disableModelNegativePrompt = true,
            clipSkipText = "2",
            vaeTilingEnabled = true,
            batchCount = 2,
            widthText = "768",
            heightText = "512",
            stepsText = "24",
            cfgScaleText = "6.5",
            seedText = "42",
            sampler = "dpmpp_2m",
            inputImageUri = "content://documents/input",
            maskImageUri = "content://documents/mask",
            controlImageUri = "content://documents/control"
        )

        val actual = ImageGenerationUiParameterSnapshot.fromJsonOrNull(
            expected.toJson().toString()
        )

        assertEquals(expected, actual)
        assertEquals(2, expected.toJson().getInt("version"))
    }
}
