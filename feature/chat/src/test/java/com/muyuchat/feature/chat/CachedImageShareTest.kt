package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CachedImageShareTest {
    @Test
    fun `default sharing omits prompt`() {
        assertNull(imageSharePromptOrNull(FULL_PROMPT))
    }

    @Test
    fun `explicit prompt opt in includes prompt`() {
        assertEquals(FULL_PROMPT, imageSharePromptOrNull(FULL_PROMPT, includePrompt = true))
    }

    @Test
    fun `explicit prompt opt out omits prompt`() {
        assertNull(imageSharePromptOrNull(FULL_PROMPT, includePrompt = false))
    }

    @Test
    fun `blank prompt remains omitted after opt in`() {
        assertNull(imageSharePromptOrNull("   ", includePrompt = true))
    }

    private companion object {
        const val FULL_PROMPT = "portrait, private prompt details"
    }
}
