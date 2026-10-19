package com.muyuchat.feature.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prevents small decorative glyphs from shrinking the actual chat interaction bounds. */
class ChatTouchTargetContractTest {
    @Test
    fun `primary chat actions use forty eight dp touch targets`() {
        val status = source("ChatStatusBarHeader.kt")
        assertTrue(status.contains("Modifier.size(48.dp)"))
        assertTrue(status.contains("compactForLargeText"))
        assertTrue(status.contains("contentDescription = \"打开应用菜单\""))
        assertFalse(status.contains("Modifier.size(38.dp)"))

        // The three small icon actions must share the press-scale + bounded-ripple primitive
        // instead of mixing native IconButton, a bare clickable and a text glyph.
        assertTrue(status.contains("McaIconButton("))
        assertTrue(status.contains("ripple(bounded = true)"))
        assertTrue(status.contains(".mcaPressScale(modelCapsuleInteraction)"))
        assertFalse(status.contains("import androidx.compose.material3.IconButton"))
        assertFalse(status.contains("LocalIndication"))

        val composer = source("ChatComposerScreens.kt")
        assertTrue(composer.contains("RoundedCornerShape(36.dp)"))
        assertTrue(composer.contains(".size(40.dp)"))
        assertTrue(composer.contains("if (!sendFabEnabled) disabled()"))

        assertTrue(source("ChatMessageBubbleScreens.kt").contains("Modifier.size(48.dp)"))
        assertTrue(source("ChatAssistantActionViews.kt").contains("Modifier.size(48.dp)"))
        assertTrue(source("ChatHistoryDrawerRows.kt").contains("Modifier.size(48.dp)"))
    }

    @Test
    fun `visible composer controls keep their previous compact geometry`() {
        val composer = source("ChatComposerScreens.kt")
        assertTrue(composer.contains("if (!sendFabEnabled) disabled()"))
        assertTrue(composer.contains("else -> \"发送\""))
        assertTrue(composer.contains("containerColor = McaPrimaryBlue"))
    }

    private fun source(name: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(
                directory,
                "feature/chat/src/main/java/com/muyuchat/feature/chat/$name"
            )
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate $name")
    }
}
