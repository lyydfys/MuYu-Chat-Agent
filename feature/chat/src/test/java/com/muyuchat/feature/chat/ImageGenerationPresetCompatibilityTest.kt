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
        imageMinSteps = 10,
        imageMaxSteps = 50,
        imageSupportedSamplers = listOf("euler", "dpmpp_2m", "pndm"),
        imageImg2ImgSupportedSamplers = listOf("euler", "dpmpp_2m")
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

        assertEquals(
            setOf(
                ImageGenerationPresetField.PROMPT,
                ImageGenerationPresetField.NEGATIVE_PROMPT,
                ImageGenerationPresetField.SIZE,
                ImageGenerationPresetField.STEPS,
                ImageGenerationPresetField.CFG,
                ImageGenerationPresetField.SEED,
                ImageGenerationPresetField.SAMPLER,
                ImageGenerationPresetField.CLIP_SKIP,
                ImageGenerationPresetField.LORA,
                ImageGenerationPresetField.BATCH,
                ImageGenerationPresetField.VAE_TILING
            ),
            fields
        )
    }

    @Test
    fun `steps outside the selected profile range are not offered for reuse`() {
        val fields = compatibleImageGenerationPresetFields(
            preset = ImageGenerationUiPreset(prompt = "keep prompt", steps = 9),
            selectedModel = localModel,
            selectedModelIsCloud = false,
            supportsNegativePrompt = false,
            supportsClipSkip = false,
            supportsVaeTiling = false,
            supportsLora = false,
            availableLoraIds = emptySet(),
            maxBatchCount = 1
        )

        assertEquals(setOf(ImageGenerationPresetField.PROMPT), fields)
    }

    @Test
    fun `img2img does not reuse pndm while text to image still does`() {
        val preset = ImageGenerationUiPreset(
            prompt = "sampler mode",
            sampleMethod = "pndm"
        )

        val img2ImgFields = compatibleImageGenerationPresetFields(
            preset = preset,
            selectedModel = localModel,
            selectedModelIsCloud = false,
            supportsNegativePrompt = false,
            supportsClipSkip = false,
            supportsVaeTiling = false,
            supportsLora = false,
            availableLoraIds = emptySet(),
            maxBatchCount = 1,
            currentTaskMode = ImageGenerationUiTaskMode.IMG2IMG
        )
        val textToImageFields = compatibleImageGenerationPresetFields(
            preset = preset,
            selectedModel = localModel,
            selectedModelIsCloud = false,
            supportsNegativePrompt = false,
            supportsClipSkip = false,
            supportsVaeTiling = false,
            supportsLora = false,
            availableLoraIds = emptySet(),
            maxBatchCount = 1,
            currentTaskMode = ImageGenerationUiTaskMode.TEXT_TO_IMAGE
        )

        assertEquals(setOf(ImageGenerationPresetField.PROMPT), img2ImgFields)
        assertEquals(
            setOf(ImageGenerationPresetField.PROMPT, ImageGenerationPresetField.SAMPLER),
            textToImageFields
        )
    }

    @Test
    fun `UltraFix preset obeys the selected graph alignment instead of generic eight pixels`() {
        val ultraFixModel = localModel.copy(
            supportsImageUltraFix = true,
            imageUltraFixMinWidth = 512,
            imageUltraFixMaxWidth = 2048,
            imageUltraFixMinHeight = 512,
            imageUltraFixMaxHeight = 2048,
            imageUltraFixWidthMultiple = 64,
            imageUltraFixHeightMultiple = 64
        )
        fun reusableTile(tileSize: Int): Set<ImageGenerationPresetField> =
            compatibleImageGenerationPresetFields(
                preset = ImageGenerationUiPreset(
                    prompt = "refine",
                    width = 1024,
                    height = 768,
                    ultraFix = ImageGenerationUiUltraFixOptions(
                        targetWidth = 1024,
                        targetHeight = 768,
                        strength = 0.5,
                        inversionSteps = 5,
                        refinementSteps = 10,
                        tileSize = tileSize,
                        overlap = 0.25
                    )
                ),
                selectedModel = ultraFixModel,
                selectedModelIsCloud = false,
                supportsNegativePrompt = false,
                supportsClipSkip = false,
                supportsVaeTiling = true,
                supportsLora = false,
                availableLoraIds = emptySet(),
                maxBatchCount = 1,
                currentTaskMode = ImageGenerationUiTaskMode.IMG2IMG,
                supportsUltraFix = true
            )

        assertEquals(
            setOf(ImageGenerationPresetField.PROMPT),
            reusableTile(160)
        )
        assertEquals(
            setOf(
                ImageGenerationPresetField.PROMPT,
                ImageGenerationPresetField.ULTRAFIX
            ),
            reusableTile(192)
        )
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
