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
        // P9/S9: PagedPlainMessageText moved to ChatMessageTextRenderers.kt and had to widen from
        // private to internal there (cross-file entry). The assertion tracks the declaration; the
        // page-bound behaviour it pins is unchanged.
        assertTrue(source.contains("internal fun PagedPlainMessageText("))
        // P9/S8: PagedAssistantRichText moved to ChatMarkdownBlockRenderers.kt and had to widen from
        // private to internal there (cross-file entry). R00 then added the explicit
        // contentIdentity parameter (the message-row callers pass it) while keeping the previous
        // positional call shape usable. The assertion tracks the declaration; the bound it enforces
        // (MESSAGE_RENDER_PAGE_CHARS, safePrefix, "display more" accumulation) is unchanged below.
        assertTrue(
            source.contains(
                "internal fun PagedAssistantRichText(content: String, modifier: Modifier = Modifier, contentIdentity: ChatMessageIdentity? = null)"
            )
        )
        assertTrue(source.contains("rememberSaveable(contentIdentity)"))
        // P9/S15: ReasoningPanel moved to ChatReasoningPerformanceScreens.kt and had to widen from
        // private to internal there (cross-file entry from the message block). The assertion tracks
        // the declaration; the paged reasoning text it renders is unchanged.
        assertTrue(source.contains("internal fun ReasoningPanel("))
        assertTrue(source.countOccurrences("safePrefix(visibleCharacters)") >= 3)
        assertTrue(source.countOccurrences("visibleCharacters + MESSAGE_RENDER_PAGE_CHARS") >= 3)
    }

    @Test
    fun streamingScrollNoLongerUsesCharacterBucketedForcedScroll() {
        val source = chatScreenOnlySource()

        // P05a replaces the 512-character bucket and its forced scrollToItem call with the page-local
        // ChatScrollCoordinator. The rendering page-size constants above stay untouched.
        assertFalse(source.contains("STREAMING_SCROLL_CHAR_STEP"))
        assertFalse(source.contains("streamingScrollBucket"))
        assertFalse(source.contains("listState.scrollToItem"))
        assertTrue(source.contains("toChatScrollLayoutObservation()"))
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

    private fun chatScreenOnlySource(): String {
        var root = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(6) {
            val chatScreen = listOf(
                File(root, "src/main/java/com/muyuchat/feature/chat/ChatScreen.kt"),
                File(root, "feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreen.kt")
            ).firstOrNull(File::isFile)
            if (chatScreen != null) return chatScreen.readText(Charsets.UTF_8)
            root = root.parentFile ?: return@repeat
        }
        error("Unable to locate ChatScreen.kt from ${System.getProperty("user.dir")}")
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
                // P9/S5–S15: markdown 解析器/渲染器、提示栏、消息文本与推理/性能簇已迁出（多源拼接随迁）。
                return listOf("ChatScreen.kt", "ChatChromeScreens.kt", "ChatMarkdownRenderSupport.kt", "ChatMarkdownBlockParser.kt", "ChatMarkdownBlockRenderers.kt", "ImagePromptBarScreens.kt", "ChatMessageTextRenderers.kt", "ChatReasoningPerformanceScreens.kt").joinToString("\n") { name ->
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
