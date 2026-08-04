package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageTextualInversionPromptTest {
    @Test
    fun enablingAddsOneExactTriggerAndNeverDuplicatesIt() {
        assertEquals(
            "portrait, <paint-style>",
            imagePromptWithTextualInversionTrigger("portrait", "<paint-style>")
        )
        assertEquals(
            "portrait, <PAINT-STYLE>",
            imagePromptWithTextualInversionTrigger(
                "portrait, <PAINT-STYLE>",
                "<paint-style>"
            )
        )
    }

    @Test
    fun disablingRemovesOnlyTheCompleteTrigger() {
        assertEquals(
            "bobcat, catering",
            imagePromptWithoutTextualInversionTrigger("bobcat, cat, catering", "cat")
        )
        assertFalse(imagePromptContainsTextualInversionTrigger("bobcat", "cat"))
        assertTrue(imagePromptContainsTextualInversionTrigger("cat-style", "cat"))
    }

    @Test
    fun disablingCleansLeadingTrailingAndRepeatedSeparators() {
        assertEquals(
            "portrait, sharp focus",
            imagePromptWithoutTextualInversionTrigger(
                "portrait, <paint-style>, sharp focus",
                "<paint-style>"
            )
        )
        assertEquals(
            "portrait",
            imagePromptWithoutTextualInversionTrigger("<paint-style>, portrait", "<paint-style>")
        )
    }

    @Test
    fun asynchronousLibraryLoadNeverDropsRestoredSelection() {
        val restoredId = "00000000-0000-0000-0000-000000000001"

        val result = reconcileImageTextualInversionSelection(
            supportsTextualInversion = true,
            libraryBusy = true,
            currentIds = listOf(restoredId),
            prompt = "portrait, <style>",
            knownTriggersById = emptyMap(),
            available = emptyList()
        )

        assertEquals(listOf(restoredId), result.ids)
        assertEquals("portrait, <style>", result.prompt)
    }

    @Test
    fun modelSwitchRemovesOldManagedTriggerAndAddsRestoredSelection() {
        val oldId = "00000000-0000-0000-0000-000000000001"
        val newId = "00000000-0000-0000-0000-000000000002"

        val result = reconcileImageTextualInversionSelection(
            supportsTextualInversion = true,
            libraryBusy = false,
            currentIds = listOf(newId),
            prompt = "portrait, <old-style>",
            knownTriggersById = mapOf(oldId to "<old-style>"),
            available = listOf(item(newId, "<new-style>"))
        )

        assertEquals(listOf(newId), result.ids)
        assertEquals("portrait, <new-style>", result.prompt)
        assertEquals(mapOf(newId to "<new-style>"), result.triggersById)
    }

    @Test
    fun modelFormatChangeDropsOnlyIncompatibleSelectionAndManagedTrigger() {
        val safeId = "00000000-0000-0000-0000-000000000001"
        val torchId = "00000000-0000-0000-0000-000000000002"

        val result = reconcileImageTextualInversionSelection(
            supportsTextualInversion = true,
            libraryBusy = false,
            currentIds = listOf(safeId, torchId),
            prompt = "portrait, <safe-style>, <torch-style>",
            knownTriggersById = mapOf(
                safeId to "<safe-style>",
                torchId to "<torch-style>"
            ),
            available = listOf(
                item(safeId, "<safe-style>", "safetensors"),
                item(torchId, "<torch-style>", "pytorch")
            ),
            supportedFormats = setOf("safetensors")
        )

        assertEquals(listOf(safeId), result.ids)
        assertEquals("portrait, <safe-style>", result.prompt)
        assertEquals(mapOf(safeId to "<safe-style>"), result.triggersById)
    }

    @Test
    fun busyLibraryRetainsUnknownRestoredIdsButDropsKnownIncompatibleFormat() {
        val unknownId = "00000000-0000-0000-0000-000000000001"
        val torchId = "00000000-0000-0000-0000-000000000002"

        val result = reconcileImageTextualInversionSelection(
            supportsTextualInversion = true,
            libraryBusy = true,
            currentIds = listOf(unknownId, torchId),
            prompt = "portrait, <torch-style>",
            knownTriggersById = mapOf(torchId to "<torch-style>"),
            available = listOf(item(torchId, "<torch-style>", "pytorch")),
            supportedFormats = setOf("safetensors")
        )

        assertEquals(listOf(unknownId), result.ids)
        assertEquals("portrait", result.prompt)
        assertTrue(result.triggersById.isEmpty())
    }

    private fun item(
        id: String,
        trigger: String,
        format: String = "safetensors"
    ): ImageTextualInversionUiItem =
        ImageTextualInversionUiItem(
            id = id,
            name = trigger,
            trigger = trigger,
            format = format,
            sizeText = "16 B",
            sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        )
}
