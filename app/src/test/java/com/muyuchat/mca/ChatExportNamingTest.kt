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
            "中文标题：导出 😀",
            "特殊 # 标题 `代码` [链接](https://example.com/a:b?c=d)",
            "a\u0000b\u001Fc\u007Fd",
            "CON",
            "con",
            "COM1",
            "LPT9",
            "fun 中文函数() = 1",
            "SELECT * FROM 用户",
            "😀".repeat(40),
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

        assertEquals(true, compared >= 90)
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
                    !originalIsUnsafeChatTitleLine(line) &&
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
    // ---- P02a: immutable patterns preserve the original derivation ---------------------

    @Test
    fun `export filename derivation matches the original rules across unicode and code titles`() {
        val samples = listOf(
            null,
            "",
            "中文标题：导出 😀 / 路径",
            "特殊 # 标题 `代码` [链接](https://example.com/a:b?c=d)",
            "a\u0000b\u001Fc\u007Fd",
            "```kotlin\nfun 中文函数() = 1\n```",
            "fun 中文函数() = 1",
            "SELECT * FROM 用户",
            "const value = 42",
            "curl https://example.com",
            "1×1=1 2×2=4 3×3=9",
            "1＋2＝3",
            "CON",
            "con",
            "PrN",
            "AUX",
            "NUL",
            "COM1",
            "LPT9",
            "CON.",
            "😀".repeat(40) + "\n" + "后续中文"
        )

        samples.forEach { sample ->
            assertEquals(
                "filename diverged for ${sample?.take(48)}",
                originalSafeChatExportTitle(sample),
                safeChatExportTitle(sample)
            )
        }
    }

    @Test
    fun `windows reserved names keep the original case and prefix behavior`() {
        assertEquals("chat-CON", safeChatExportTitle("CON"))
        assertEquals("chat-con", safeChatExportTitle("con"))
        assertEquals("chat-PrN", safeChatExportTitle("PrN"))
        assertEquals("chat-COM1", safeChatExportTitle("COM1"))
        assertEquals("chat-LPT9", safeChatExportTitle("LPT9"))
        assertEquals("chat-CON", safeChatExportTitle("CON."))
    }

    @Test
    fun `unicode title limits keep the original utf16 length behavior`() {
        val raw = "😀".repeat(40) + "中文"

        assertEquals(originalSafeChatExportTitle(raw), safeChatExportTitle(raw))
        assertEquals(48, safeChatExportTitle(raw).length)
    }

    /** Verbatim copy of the original filename derivation, including its per-call Regex creation. */
    private fun originalSafeChatExportTitle(raw: String?): String {
        val source = raw.orEmpty()
        val titleSource = source
            .lineSequence()
            .map(String::trim)
            .firstOrNull { line -> line.isNotBlank() && !originalIsUnsafeChatTitleLine(line) }
            ?: ""
        val cleaned = titleSource
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
            .replace(Regex("[\\\\/:*?\"<>|]"), " ")
            .replace(Regex("[`*_#>\\[\\]()]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.', ' ')
            .take(48)
            .trim('.', ' ')

        val value = cleaned.ifBlank { "chat" }
        return if (value.uppercase() in ORIGINAL_WINDOWS_RESERVED_NAMES) "chat-$value" else value
    }

    /** Verbatim copy of the original code-shaped-line predicate with per-call Regex creation. */
    private fun originalIsUnsafeChatTitleLine(value: String): Boolean {
        val line = value.trim()
        if (line.isBlank()) return true
        if (line.startsWith("```") || line.startsWith("<!DOCTYPE", ignoreCase = true)) return true
        if (line.startsWith("<html", ignoreCase = true) || line.startsWith("</html", ignoreCase = true)) return true
        if (line.startsWith("{") || line.startsWith("[") || line.endsWith("};")) return true
        if (line.startsWith("#include") || line.startsWith("import ") || line.startsWith("from ")) return true
        if (Regex("(?i)^(?:def|class|fun|function|const|let|var|val|public|private|protected|return|if|else|for|while|try|catch|SELECT|INSERT|UPDATE|CREATE)\\b")
                .containsMatchIn(line)
        ) return true
        if (Regex(
                "(?i)^(?:console\\.(?:log|warn|error|info)|System\\.out\\.println|println|print|printf)\\s*\\("
            ).containsMatchIn(line)
        ) return true
        if (Regex(
                "^(?:[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)\\s*(?:=|\\+=|-=|\\*=|/=|:=)\\s*.+"
            ).containsMatchIn(line)
        ) return true
        if (Regex(
                "(?i)^(?:#!|\\$\\s+|>\\s+(?:adb|curl|python|pip|npm|gradle|java)|(?:adb|curl|python|pip|npm|gradle)\\s+)"
            ).containsMatchIn(line)
        ) return true
        if (Regex("^<[/!]?\\s*[A-Za-z][^>]*>$").containsMatchIn(line)) return true
        if (Regex("^\\d+\\s*[×x*＋+−-]\\s*\\d+\\s*=\\s*\\d+(?:\\s+\\d+\\s*[×x*＋+−-]\\s*\\d+\\s*=\\s*\\d+)*$")
                .containsMatchIn(line)
        ) return true
        if (line.contains("</") || line.contains("=>") || line.contains("{ ") || line.contains(";")) return true
        return line.count { it == '{' || it == '}' || it == '(' || it == ')' } >= 2
    }

    private companion object {
        val ORIGINAL_WINDOWS_RESERVED_NAMES = setOf(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
        )
    }
}
