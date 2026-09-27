package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatExportNamingTest {
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
