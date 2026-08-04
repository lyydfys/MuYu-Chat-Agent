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

        assertTrue(source.contains("private const val MESSAGE_RENDER_PAGE_CHARS = 16_384"))
        assertTrue(source.contains("private fun PagedPlainMessageText("))
        assertTrue(source.contains("private fun PagedAssistantRichText(content: String)"))
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

        assertTrue(source.contains("private const val STREAMING_SCROLL_CHAR_STEP = 512"))
        assertTrue(policy.contains("message.content.length + message.reasoningContent.length"))
        assertTrue(policy.contains("/ STREAMING_SCROLL_CHAR_STEP"))
        assertTrue(policy.contains("LaunchedEffect(state.messages.size, state.isGenerating, streamingScrollBucket)"))
        assertFalse(policy.contains("message.content,"))
        assertFalse(policy.contains("message.reasoningContent,"))
    }

    private fun invokeSafePrefix(value: String, maxCharacters: Int): String {
        val owner = Class.forName("com.muyuchat.feature.chat.ChatScreenKt")
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
            listOf(
                File(root, "src/main/java/com/muyuchat/feature/chat/ChatScreen.kt"),
                File(root, "feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreen.kt")
            ).firstOrNull(File::isFile)?.let { return it.readText(Charsets.UTF_8) }
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
