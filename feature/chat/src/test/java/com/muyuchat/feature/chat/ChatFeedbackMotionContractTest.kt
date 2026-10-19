package com.muyuchat.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the feedback/motion pass: the reasoning panel animates size and rotates one arrow, the
 * error details can be copied, and menu/background rows share the press feedback.
 */
class ChatFeedbackMotionContractTest {

    @Test
    fun `reasoning panel animates growth and rotates a single arrow`() {
        val reasoning = source("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatReasoningPerformanceScreens.kt")
        assertTrue(reasoning.contains("animateContentSize(animationSpec = McaMotion.sizeChangePx)"))
        assertTrue(reasoning.contains("rotationZ = arrowRotation.value"))
        assertFalse(reasoning.contains("Icons.Rounded.KeyboardArrowUp"))
    }

    @Test
    fun `error details can be copied with the shared clipboard path`() {
        val guidance = source("feature/chat/src/main/java/com/muyuchat/feature/chat/UserGuidance.kt")
        assertTrue(guidance.contains("\"复制详情\""))
        assertTrue(guidance.contains("ClipEntry(ClipData.newPlainText(\"MCA\", message))"))
    }

    @Test
    fun `menu rows and background rows share press feedback`() {
        val menu = source("feature/chat/src/main/java/com/muyuchat/feature/chat/AppMenuCards.kt")
        assertTrue(menu.contains("mcaPressScale("))
        assertTrue(menu.contains("CompositionLocalProvider("))

        val background = source("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatBackgroundScreens.kt")
        assertTrue(background.contains("McaIconButton("))
        assertTrue(background.contains("mcaPressScale("))
    }

    @Test
    fun `image previews use the three-state loader and reset on retry`() {
        val loader = source("feature/chat/src/main/java/com/muyuchat/feature/chat/ImageAssetIoSupport.kt")
        assertTrue(loader.contains("value = ChatImageLoadState.Loading"))

        val views = source("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatGeneratedImageViews.kt")
        assertTrue(views.contains("rememberChatImageLoadState("))
        assertTrue(views.contains("ChatImageLoadState.Failed"))
        assertTrue(views.contains("imageRetryEpoch++"))
    }

    private fun source(relativePath: String): String =
        File(projectRoot(), relativePath).readText(Charsets.UTF_8)

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("project root not found")
    }
}
