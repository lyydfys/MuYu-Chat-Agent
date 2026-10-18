package com.muyuchat.feature.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Page gestures must stay compositor-only and preserve the established motion timing. */
class ChatMotionContractTest {
    @Test
    fun `swipe and app menu pages translate through graphics layer`() {
        for (name in listOf("SwipeBackPage.kt", "AppMenuTransitions.kt")) {
            val source = source(name)
            assertTrue("$name must translate on the compositor", source.contains("graphicsLayer { translationX = offsetX.value }"))
            assertFalse("$name must not place the full page each animation frame", source.contains(".offset { IntOffset(offsetX"))
        }
    }

    @Test
    fun `established transition timings remain intact`() {
        val swipe = source("SwipeBackPage.kt")
        val menu = source("AppMenuTransitions.kt")
        assertTrue(swipe.contains("durationMillis = 260"))
        assertTrue(swipe.contains("durationMillis = 180"))
        // Predictive-back progress must not allocate a coroutine per frame, and both the cancel
        // snap-back and the dismiss completion settle through springs rather than fixed tweens.
        assertTrue(swipe.contains("Channel<Float>(Channel.CONFLATED)"))
        assertTrue(swipe.contains("spring("))
        assertTrue(menu.contains("durationMillis = 240"))
        assertTrue(menu.contains("durationMillis = 140"))
        assertTrue(menu.contains("durationMillis = 180"))
        assertTrue(menu.contains("durationMillis = 160"))
    }

    private fun source(name: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, "feature/chat/src/main/java/com/muyuchat/feature/chat/$name")
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate $name")
    }
}
