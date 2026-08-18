package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatAppearanceTest {
    @Test
    fun resolvesSessionBeforeAssistantBeforeGlobal() {
        val global = ChatAppearance(backgroundImagePath = "global.jpg")
        val assistant = ChatAppearance(backgroundImagePath = "assistant.jpg")
        val session = ChatAppearance(backgroundImagePath = "session.jpg")

        assertEquals(session, resolveChatAppearance(session, assistant, global))
        assertEquals(assistant, resolveChatAppearance(null, assistant, global))
        assertEquals(global, resolveChatAppearance(null, ChatAppearance(), global))
    }

    @Test
    fun jsonRoundTripPreservesAppearance() {
        val source = ChatAppearance(
            backgroundImagePath = "/data/data/com.muyuchat.mca/files/background_images/a.png",
            backgroundAlpha = 0.47f,
            backgroundBlur = 12f,
            backgroundScaleMode = ChatBackgroundScaleMode.CENTER
        )
        assertEquals(source, ChatAppearance.fromJsonOrNull(source.toJsonString()))
    }

    @Test
    fun malformedJsonFallsBackToNull() {
        assertNull(ChatAppearance.fromJsonOrNull("{\"version\":99}"))
    }
}
