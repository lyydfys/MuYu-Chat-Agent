package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageGenerationPresetCompatibilityTest {
    private val localModel = ChatModelChoice(
        id = "local-model",
        displayName = "Local Model",
        imageMinWidth = 512,
        imageMaxWidth = 1024,
        imageMinHeight = 512,
        imageMaxHeight = 1024,
        imageWidthMultiple = 64,
        imageHeightMultiple = 64,
        imageSupportedSamplers = listOf("euler", "dpmpp_2m")
    )

    @Test
    fun `incompatible current model parameters are not offered for reuse`() {
        val preset = ImageGenerationUiPreset(
            prompt = "keep the prompt",
            negativePrompt = "low quality",
            width = 768,
            height = 512,
            steps = 20,
            cfgScale = 7.0,
            seed = 42,
            sampleMethod = "unsupported_sampler",
            clipSkip = 2,
            batchCount = 4,
            vaeTileSize = 256,
            vaeTileOverlap = 0.25,
            loras = listOf(
                ImageGenerationUiLoraSelection("11111111-1111-4111-8111-111111111111", 1.0)
            )
        )

        val fields = compatibleImageGenerationPresetFields(
            preset = preset,
            selectedModel = localModel,
            selectedModelIsCloud = false,
            supportsNegativePrompt = false,
            supportsClipSkip = false,
            supportsVaeTiling = true,
            supportsLora = false,
            availableLoraIds = emptySet(),
            maxBatchCount = 2
        )

        assertEquals(
            setOf(
                ImageGenerationPresetField.PROMPT,
                ImageGenerationPresetField.SIZE,
                ImageGenerationPresetField.STEPS,
                ImageGenerationPresetField.CFG,
                ImageGenerationPresetField.SEED
            ),
            fields
        )
    }

    @Test
    fun `compatible local parameters remain selectable`() {
        val preset = ImageGenerationUiPreset(
            prompt = "reuse everything",
            negativePrompt = "",
            width = 768,
            height = 512,
            steps = 20,
            cfgScale = 7.0,
            seed = 42,
            sampleMethod = "dpmpp_2m",
            clipSkip = 2,
            batchCount = 4,
            vaeTileSize = 512,
            vaeTileOverlap = 0.5,
            loras = listOf(
                ImageGenerationUiLoraSelection("11111111-1111-4111-8111-111111111111", 0.75)
            )
        )

        val fields = compatibleImageGenerationPresetFields(
            preset = preset,
            selectedModel = localModel,
            selectedModelIsCloud = false,
            supportsNegativePrompt = true,
            supportsClipSkip = true,
            supportsVaeTiling = true,
            supportsLora = true,
            availableLoraIds = setOf("11111111-1111-4111-8111-111111111111"),
            maxBatchCount = 8
        )

        assertEquals(ImageGenerationPresetField.entries.toSet(), fields)
    }

    @Test
    fun `cloud reuse exposes only prompt controls declared by the connector`() {
        val preset = ImageGenerationUiPreset(
            prompt = "cloud prompt",
            negativePrompt = "cloud negative",
            width = 1024,
            height = 1024,
            steps = 30,
            seed = 7,
            sampleMethod = "euler",
            batchCount = 1
        )

        val fields = compatibleImageGenerationPresetFields(
            preset = preset,
            selectedModel = localModel.copy(cloud = true),
            selectedModelIsCloud = true,
            supportsNegativePrompt = true,
            supportsClipSkip = false,
            supportsVaeTiling = false,
            supportsLora = false,
            availableLoraIds = emptySet(),
            maxBatchCount = 1
        )

        assertEquals(
            setOf(
                ImageGenerationPresetField.PROMPT,
                ImageGenerationPresetField.NEGATIVE_PROMPT
            ),
            fields
        )
    }
}
