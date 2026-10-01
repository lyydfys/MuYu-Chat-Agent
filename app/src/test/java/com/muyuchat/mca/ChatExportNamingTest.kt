package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatExportNamingTest {
    // ---- chatTitle: derivation -------------------------------------------------------

    @Test
    fun `a plain user turn becomes the title`() {
        assertEquals("Hello world", listOf(ChatMessage(Role.USER, "Hello world")).chatTitle())
    }

    @Test
    fun `the first user turn is used even when an assistant message comes first`() {
        val title = listOf(
            ChatMessage(Role.ASSISTANT, "greeting"),
            ChatMessage(Role.USER, "question"),
        ).chatTitle()
        assertEquals("question", title)
    }

    @Test
    fun `a conversation without a user turn falls back to a generic title`() {
        assertEquals("新对话", emptyList<ChatMessage>().chatTitle())
        assertEquals("新对话", listOf(ChatMessage(Role.ASSISTANT, "only a reply")).chatTitle())
        assertEquals("新对话", listOf(ChatMessage(Role.SYSTEM, "system prompt")).chatTitle())
    }

    @Test
    fun `a title is cut to twenty eight characters with an ellipsis`() {
        val exact = "a".repeat(28)
        assertEquals(exact, listOf(ChatMessage(Role.USER, exact)).chatTitle())

        val over = "a".repeat(29)
        val title = listOf(ChatMessage(Role.USER, over)).chatTitle()
        assertEquals("a".repeat(28) + "...", title)
        assertEquals(31, title.length)
    }

    @Test
    fun `markdown markers are stripped and whitespace is collapsed`() {
        assertEquals("Heading", listOf(ChatMessage(Role.USER, "# Heading")).chatTitle())
        assertEquals("bold", listOf(ChatMessage(Role.USER, "**bold**")).chatTitle())
        // A single line has its inner runs collapsed.
        assertEquals("a b", listOf(ChatMessage(Role.USER, "a   b")).chatTitle())
        assertEquals("a b", listOf(ChatMessage(Role.USER, "  a   b  ")).chatTitle())
    }

    @Test
    fun `only the first usable line is used, not the whole message`() {
        // Multi-line input contributes one line: the first one that is usable.
        assertEquals("a", listOf(ChatMessage(Role.USER, "  a\n  b  ")).chatTitle())
        assertEquals("first", listOf(ChatMessage(Role.USER, "first\nsecond\nthird")).chatTitle())
    }

    @Test
    fun `a title that compacts to nothing falls back to a generic title`() {
        assertEquals("新对话", listOf(ChatMessage(Role.USER, "***")).chatTitle())
    }

    @Test
    fun `code shaped first lines are skipped in favour of later prose`() {
        val title = listOf(
            ChatMessage(Role.USER, "```python\ndef hello():\n```\n请解释这段代码")
        ).chatTitle()
        assertEquals("请解释这段代码", title)
    }

    @Test
    fun `a conversation that is only code falls back to a generic title`() {
        assertEquals(
            "新对话",
            listOf(ChatMessage(Role.USER, "```python\ndef hello():\n    return 1\n```")).chatTitle()
        )
    }

    @Test
    fun `attachment placeholder lines are skipped`() {
        assertEquals(
            "请问这个文件讲了什么",
            listOf(ChatMessage(Role.USER, "【上传文件：doc.pdf】\n请问这个文件讲了什么")).chatTitle()
        )
        assertEquals(
            "实际内容",
            listOf(ChatMessage(Role.USER, "（文件较大，已省略）\n实际内容")).chatTitle()
        )
    }

    @Test
    fun `an attachment with no other prose is named after the attachment`() {
        assertEquals(
            "文件问答：doc.pdf",
            listOf(ChatMessage(Role.USER, "【上传文件：doc.pdf】")).chatTitle()
        )
        assertEquals(
            "图片：cat.png",
            listOf(ChatMessage(Role.USER, "【上传图片：cat.png】")).chatTitle()
        )
    }

    // ---- chatTitle: extraction equivalence -------------------------------------------

    @Test
    fun `the extraction matches the original derivation on every sample`() {
        val samples = listOf(
            "",
            "Hello world",
            "a".repeat(28),
            "a".repeat(29),
            "a".repeat(200),
            "# Heading",
            "**bold**",
            "***",
            "a   b",
            "  a\n  b  ",
            "```python\ndef hello():\n```",
            "```python\ndef hello():\n```\n请解释这段代码",
            "【上传文件：doc.pdf】",
            "【上传文件：doc.pdf】\n请问这个文件讲了什么",
            "【上传图片：cat.png】",
            "【上传图片：cat.png】\n这张图是什么",
            "（文件较大，已省略）\n实际内容",
            "console.log(\"hello\")\nreturn value;",
            "{\"prompt\":\"a cat\",\"steps\":20}",
            "1×1=1 2×2=4 3×3=9",
            "生成一个网页\n```html\n<div>Hello</div>\n```",
        )
        val roles = listOf(Role.USER, Role.ASSISTANT, Role.SYSTEM)
        var compared = 0

        samples.forEach { content ->
            roles.forEach { role ->
                val messages = listOf(ChatMessage(role, content))
                assertEquals(
                    "title diverged for role=$role content=${content.take(40)}",
                    originalChatTitle(messages),
                    messages.chatTitle()
                )
                compared++
            }
        }

        // A conversation with no messages at all.
        assertEquals(originalChatTitle(emptyList()), emptyList<ChatMessage>().chatTitle())
        compared++

        // Mixed conversations, including one whose first usable turn is not the first message.
        listOf(
            listOf(ChatMessage(Role.ASSISTANT, "reply"), ChatMessage(Role.USER, "question")),
            listOf(ChatMessage(Role.SYSTEM, "sys"), ChatMessage(Role.ASSISTANT, "reply")),
            listOf(
                ChatMessage(Role.USER, "```\ncode\n```"),
                ChatMessage(Role.ASSISTANT, "ok"),
                ChatMessage(Role.USER, "later prose"),
            ),
        ).forEach { messages ->
            assertEquals(
                "title diverged for a mixed conversation",
                originalChatTitle(messages),
                messages.chatTitle()
            )
            compared++
        }

        assertEquals(true, compared >= 66)
    }

    /** Verbatim copy of the pre-extraction `MainViewModel.List<ChatMessage>.chatTitle`. */
    private fun originalChatTitle(messages: List<ChatMessage>): String {
        val userText = messages.firstOrNull { it.role == Role.USER }?.content.orEmpty()
        val fileName = Regex("""【上传文件：([^】]+)】""").find(userText)?.groupValues?.getOrNull(1)
        val imageName = Regex("""【上传图片：([^】]+)】""").find(userText)?.groupValues?.getOrNull(1)
        val raw = userText
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line.isNotBlank() &&
                    !isUnsafeChatTitleLine(line) &&
                    !line.startsWith("【上传文件：") &&
                    !line.startsWith("【上传图片：") &&
                    !line.startsWith("（文件较大")
            }
            ?: fileName?.let { "文件问答：$it" }
            ?: imageName?.let { "图片：$it" }
            ?: "新对话"
        val compact = raw
            .replace(Regex("""[#>*`_\[\]()]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return compact.ifBlank { "新对话" }.let { if (it.length > 28) it.take(28) + "..." else it }
    }

    // ---- safeChatExportTitle ---------------------------------------------------------

    @Test
    fun `code fences and control characters never become export filename`() {
        val value = safeChatExportTitle("```html\n<!DOCTYPE html>\n<html>\n</html>```")

        assertFalse(value.contains('`'))
        assertFalse(value.contains('<'))
        assertFalse(value.contains('>'))
        assertFalse(value.contains('\n'))
        assertFalse(value.any { it.code < 0x20 })
        assertTrue(value.isNotBlank())
    }

    @Test
    fun `path separators and reserved names are made safe`() {
        assertEquals("chat", safeChatExportTitle("   ...   "))
        assertEquals("chat-CON", safeChatExportTitle("CON"))
        assertFalse(safeChatExportTitle("a/b:c\\d").contains('/'))
        assertFalse(safeChatExportTitle("a/b:c\\d").contains('\\'))
    }

    @Test
    fun `long titles are compacted without trailing punctuation`() {
        val value = safeChatExportTitle("a".repeat(200) + "...")

        assertTrue(value.length <= 48)
        assertFalse(value.endsWith('.'))
        assertFalse(value.endsWith(' '))
    }

    @Test
    fun `source code and markup never become export title`() {
        assertEquals("chat", safeChatExportTitle("```python\ndef hello():\n    return 1\n```"))
        assertEquals("chat", safeChatExportTitle("<!DOCTYPE html>\n<html><body>Hello</body></html>"))
        assertEquals("chat", safeChatExportTitle("{\"prompt\":\"a cat\",\"steps\":20}"))
    }

    @Test
    fun `prose before a code block remains a useful title`() {
        assertEquals("生成一个网页", safeChatExportTitle("生成一个网页\n```html\n<div>Hello</div>\n```"))
    }

    @Test
    fun `unfenced code calls and multiplication rows never become export title`() {
        assertEquals("chat", safeChatExportTitle("console.log(\"hello\")\nreturn value;"))
        assertEquals("chat", safeChatExportTitle("1×1=1 2×2=4 3×3=9"))
    }
}
