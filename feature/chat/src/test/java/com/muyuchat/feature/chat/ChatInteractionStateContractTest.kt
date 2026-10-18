package com.muyuchat.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatInteractionStateContractTest {
    @Test
    fun `app menu model status reuses the real model page callback`() {
        val source = sourceFile(APP_MENU_SCREENS)

        val statusRowStart = source.indexOf("title = displayModelName(state.selectedModelName)")
        assertTrue("Model status row not found", statusRowStart >= 0)
        val nextRowStart = source.indexOf("AppMenuRow(", statusRowStart)
        assertTrue("Following app-menu row not found", nextRowStart > statusRowStart)
        val statusRow = source.substring(statusRowStart, nextRowStart)

        assertTrue("Model status row must use the existing model page navigation", statusRow.contains("onClick = onOpenModels"))
        assertFalse("App menu must not keep a clickable empty action", source.contains("onClick = {}"))
    }

    @Test
    fun `disabled app menu row keeps its layout while showing disabled content`() {
        val source = sourceFile(APP_MENU_CARDS).replace(Regex("\\s+"), "")

        assertTrue(source.contains("APP_MENU_DISABLED_TITLE_ALPHA=0.58f"))
        assertTrue(source.contains("APP_MENU_DISABLED_SUBTITLE_ALPHA=0.62f"))
        assertTrue(
            source.contains(
                "titleColor=if(enabled){rowContentColor}else{rowContentColor.copy(alpha=APP_MENU_DISABLED_TITLE_ALPHA)}"
            )
        )
        assertTrue(
            source.contains(
                "subtitleColor=if(enabled){MaterialTheme.colorScheme.onSurfaceVariant}else{" +
                    "MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=APP_MENU_DISABLED_SUBTITLE_ALPHA)}"
            )
        )
        assertTrue(source.contains(".alpha(if(enabled)1felseAPP_MENU_DISABLED_TITLE_ALPHA)"))
        assertTrue(source.contains("detail,color=subtitleColor"))
        assertTrue(source.contains(".clickable(enabled=enabled,onClick=onClick)"))
        assertTrue(source.contains("if(enabled){Text(\"›\""))
        assertTrue(source.contains(".heightIn(min=52.dp)"))
        assertTrue(source.contains(".size(34.dp)"))
    }

    @Test
    fun `attachment preflight disables the send fab before dispatch and keeps loading`() {
        val source = sourceFile(CHAT_COMPOSER_SCREENS)
        val compact = source.replace(Regex("\\s+"), "")

        assertFalse(source.contains("else if (attachmentPreflightRunning) Unit"))
        assertTrue(compact.contains("valsendFabEnabled=isGenerating||!attachmentPreflightRunning"))
        assertTrue(compact.contains(".semantics{if(!sendFabEnabled)disabled()}"))
        assertTrue(
            compact.contains(
                "onClick={if(sendFabEnabled){when{" +
                    "isGenerating->onStop()imagePromptMode->onSendImagePrompt()else->onSend()}}}"
            )
        )
        assertTrue(compact.contains("if(attachmentPreflightRunning&&!isGenerating){CircularProgressIndicator("))
    }

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile
        }
        error("Unable to locate source file: $relativePath")
    }

    private companion object {
        const val APP_MENU_SCREENS = "feature/chat/src/main/java/com/muyuchat/feature/chat/AppMenuScreens.kt"
        const val APP_MENU_CARDS = "feature/chat/src/main/java/com/muyuchat/feature/chat/AppMenuCards.kt"
        const val CHAT_COMPOSER_SCREENS = "feature/chat/src/main/java/com/muyuchat/feature/chat/ChatComposerScreens.kt"
    }
}