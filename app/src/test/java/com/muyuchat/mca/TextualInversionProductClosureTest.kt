package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TextualInversionProductClosureTest {
    @Test
    fun missingTriggerFailsBeforeNativeDispatch() {
        val artifact = artifact("<style>")

        val failure = assertThrows(LocalImageProductContractException::class.java) {
            requireTextualInversionPromptTriggers(
                prompt = "portrait",
                negativePrompt = "",
                ids = listOf(artifact.id),
                records = listOf(artifact)
            )
        }

        assertEquals("image_textual_inversion_trigger_missing", failure.code)
    }

    @Test
    fun triggerInNegativePromptIsAccepted() {
        val artifact = artifact("<style>")

        requireTextualInversionPromptTriggers(
            prompt = "portrait",
            negativePrompt = "low quality, <STYLE>",
            ids = listOf(artifact.id),
            records = listOf(artifact)
        )
    }

    @Test
    fun mixedExtensionFailuresKeepTheActualExtensionCode() {
        val lora = localImageExtensionResolutionFailure(
            error = LocalImageProductContractException(
                "image_lora_not_found",
                "missing lora"
            ),
            fallbackCode = "image_textual_inversion_not_found",
            fallbackMessage = "missing extension"
        )
        val textualInversion = localImageExtensionResolutionFailure(
            error = LocalImageProductContractException(
                "image_textual_inversion_not_found",
                "missing textual inversion"
            ),
            fallbackCode = "image_lora_not_found",
            fallbackMessage = "missing extension"
        )

        assertEquals("image_lora_not_found", lora.code)
        assertEquals(404, lora.httpStatus)
        assertEquals("image_textual_inversion_not_found", textualInversion.code)
        assertEquals(404, textualInversion.httpStatus)
    }

    private fun artifact(trigger: String): TextualInversionArtifact {
        val id = "00000000-0000-0000-0000-000000000001"
        return TextualInversionArtifact(
            id = id,
            name = "style",
            trigger = trigger,
            fileName = "$id.bin",
            path = "C:/private/$id.bin",
            sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            sizeBytes = 16,
            format = TextualInversionFormat.BINARY
        )
    }
}
