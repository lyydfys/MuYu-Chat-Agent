package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePromptTagAutocompleteUiTest {
    @Test
    fun `dismissal persists within focus and resets after text or refocus`() {
        var state = ImagePromptTagPanelDismissState("red", wasFocused = true, dismissed = false)
        state = state.dismiss()
        assertTrue(state.observe("red", focused = true).dismissed)

        state = state.observe("red hair", focused = true)
        assertFalse(state.dismissed)
        state = state.dismiss().observe("red hair", focused = false)
        assertTrue(state.dismissed)
        state = state.observe("red hair", focused = true)
        assertFalse(state.dismissed)
    }

    @Test
    fun `status and metadata formatting are concise deterministic and path free`() {
        assertEquals("未导入", imagePromptTagDictionaryStateText(ImagePromptTagDictionaryState.NotConfigured))
        assertEquals(
            "120000 条 · 2.0 MB",
            imagePromptTagDictionaryStateText(
                ImagePromptTagDictionaryState.Available(120_000, 2L * 1_024L * 1_024L)
            )
        )
        assertEquals("1.5 KB", formatImagePromptTagByteCount(1_536))
        assertEquals("1.2 万", formatImagePromptTagPopularity(12_345))
        assertEquals("别名", imagePromptTagMatchKindLabel(ImagePromptTagMatchKind.ALIAS_PREFIX))
        assertEquals("模糊标签", imagePromptTagMatchKindLabel(ImagePromptTagMatchKind.FUZZY_TAG))
        assertEquals("模糊别名", imagePromptTagMatchKindLabel(ImagePromptTagMatchKind.FUZZY_ALIAS))
        assertEquals("模糊翻译", imagePromptTagMatchKindLabel(ImagePromptTagMatchKind.FUZZY_TRANSLATION))

        ImagePromptTagStoreIssue.entries.forEach { issue ->
            val message = imagePromptTagStoreIssueMessage(issue)
            assertTrue(message.isNotBlank())
            assertFalse(message.contains("/"))
            assertFalse(message.contains("\\"))
            assertFalse(message.contains(":"))
        }
    }
}
