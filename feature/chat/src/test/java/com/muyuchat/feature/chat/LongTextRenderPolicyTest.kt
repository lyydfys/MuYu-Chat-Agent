package com.muyuchat.feature.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongTextRenderPolicyTest {
    @Test
    fun safePrefixDoesNotSplitUtf16SurrogatePairs() {
        val value = "a\uD83D\uDE00b"

        assertEquals("a", invokeSafePrefix(value, 2))
        assertEquals("a\uD83D\uDE00", invokeSafePrefix(value, 3))
        assertEquals(value, invokeSafePrefix(value, value.length))
        assertEquals("", invokeSafePrefix(value, 0))
    }

    @Test
    fun everyLongConversationTextSurfaceUsesTheSameBoundedPageSize() {
        val source = chatScreenSource()

        // P9/S3: MESSAGE_RENDER_PAGE_CHARS moved to ChatMarkdownRenderSupport.kt and had to widen
        // from private to internal there (Kotlin has no package-private scope; ChatChromeScreens.kt
        // still reads it). The assertion tracks the declaration, so it tracks the new modifier; the
        // value it pins, 16_384, is unchanged.
        assertTrue(source.contains("internal const val MESSAGE_RENDER_PAGE_CHARS = 16_384"))
        assertTrue(source.contains("private fun PagedPlainMessageText("))
        // P9/S8: PagedAssistantRichText moved to ChatMarkdownBlockRenderers.kt and had to widen from
        // private to internal there (cross-file entry). The assertion tracks the declaration; the
        // signature and the bound it enforces are unchanged.
        assertTrue(source.contains("internal fun PagedAssistantRichText(content: String, modifier: Modifier = Modifier)"))
        assertTrue(source.contains("private fun ReasoningPanel("))
        assertTrue(source.countOccurrences("safePrefix(visibleCharacters)") >= 3)
        assertTrue(source.countOccurrences("visibleCharacters + MESSAGE_RENDER_PAGE_CHARS") >= 3)
    }

    @Test
    fun streamingScrollUpdatesAreBucketedInsteadOfTriggeredPerToken() {
        val source = chatScreenSource()
        val start = source.indexOf("val streamingScrollBucket =")
        val end = source.indexOf("val historyBackEnabled", start)
        require(start >= 0 && end > start) { "Missing streaming scroll policy" }
        val policy = source.substring(start, end)

        // The declaration moved to ChatChromeScreens.kt and had to widen from private to internal
        // there, because Kotlin has no package-private scope and ChatScreen.kt still reads it. The
        // assertion tracks the declaration, so it tracks the new file and the new modifier; the
        // value it pins, 512, is unchanged.
        assertTrue(source.contains("internal const val STREAMING_SCROLL_CHAR_STEP = 512"))
        assertTrue(policy.contains("message.content.length + message.reasoningContent.length"))
        assertTrue(policy.contains("/ STREAMING_SCROLL_CHAR_STEP"))
        assertTrue(policy.contains("LaunchedEffect(state.messages.size, state.isGenerating, streamingScrollBucket,"))
        assertFalse(policy.contains("message.content,"))
        assertFalse(policy.contains("message.reasoningContent,"))
    }

    private fun invokeSafePrefix(value: String, maxCharacters: Int): String {
        // safePrefix moved with the markdown/reasoning helpers to ChatMarkdownRenderSupport.kt
        // (P9/S3), so the file facade class that holds it is ChatMarkdownRenderSupportKt now rather
        // than ChatChromeScreensKt. The method itself is unchanged, including its private visibility,
        // which is why this still needs isAccessible.
        val owner = Class.forName("com.muyuchat.feature.chat.ChatMarkdownRenderSupportKt")
        val method = owner.getDeclaredMethod(
            "safePrefix",
            String::class.java,
            Int::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(null, value, maxCharacters) as String
    }

    private fun chatScreenSource(): String {
        var root = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(6) {
            // The chat surface now spans ChatScreen.kt and ChatChromeScreens.kt: the chrome, the
            // markdown blocks and the pagination helpers moved out of the former. Both are read, so
            // the assertions below stay meaningful wherever each declaration now lives.
            val chatScreen = listOf(
                File(root, "src/main/java/com/muyuchat/feature/chat/ChatScreen.kt"),
                File(root, "feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreen.kt")
            ).firstOrNull(File::isFile)
            if (chatScreen != null) {
                val directory = chatScreen.parentFile
                // P9/S5+S6+S8: markdown 解析器/渲染器与提示栏簇已迁出（多源拼接随迁）。
                return listOf("ChatScreen.kt", "ChatChromeScreens.kt", "ChatMarkdownRenderSupport.kt", "ChatMarkdownBlockParser.kt", "ChatMarkdownBlockRenderers.kt", "ImagePromptBarScreens.kt").joinToString("\n") { name ->
                    File(directory, name).takeIf(File::isFile)?.readText(Charsets.UTF_8).orEmpty()
                }
            }
            val parent = root.parentFile ?: return@repeat
            root = parent
        }
        error("Unable to locate ChatScreen.kt from ${System.getProperty("user.dir")}")
    }

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var start = 0
        while (true) {
            val index = indexOf(needle, start)
            if (index < 0) return count
            count++
            start = index + needle.length
        }
    }
}
