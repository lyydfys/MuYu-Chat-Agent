package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeFormattingTest {
    @Test
    fun codeBodyPreservesIndentationLineEndingsAndTrailingWhitespace() {
        val body = "def f():\r\n\treturn 1  \r\n\r\n"
        val source = "Before\r\n```python filename=\"sample.py\"\r\n${body}```\r\nAfter"
        val parts = parseChatMarkdownSource(source)
        val code = parts.filterIsInstance<ChatMarkdownSource.Code>().single()
        assertEquals(body, code.code)
        assertEquals("python", code.language)
        assertEquals("sample.py", code.fileName)
        assertEquals(source, parts.joinToString("") { it.raw })
        assertTrue(code.closed)
    }

    @Test
    fun flatProgramsRemainUnmodified() {
        val body = "function f(){return true;}\n"
        assertEquals(body, parseChatMarkdownSource("```javascript\n${body}```")
            .filterIsInstance<ChatMarkdownSource.Code>().single().code)
    }

    @Test
    fun everyStreamingPrefixRetainsExactSourceWithoutInventingNewlines() {
        val source = "Hi\n```python file=app.py\nprint('hi')  \n```\nDone"
        source.indices.forEach { length ->
            val prefix = source.substring(0, length)
            assertEquals(prefix, parseChatMarkdownSource(prefix).joinToString("") { it.raw })
        }
        val openCode = parseChatMarkdownSource("```python\nreturn 1")
            .filterIsInstance<ChatMarkdownSource.Code>().single()
        assertEquals("return 1", openCode.code)
        assertFalse(openCode.closed)
    }

    @Test
    fun innerShorterFenceAndFenceLikeStringsRemainInCode() {
        val body = "```python\nprint('```')\n```\n"
        val code = parseChatMarkdownSource("````markdown\n${body}````")
            .filterIsInstance<ChatMarkdownSource.Code>().single()
        assertEquals(body, code.code)
    }

    @Test
    fun compactOpeningHeaderRetainsFirstCodeToken() {
        val code = parseChatMarkdownSource("```html<!DOCTYPE html>\n<p>Hello</p>\n```")
            .filterIsInstance<ChatMarkdownSource.Code>().single()
        assertEquals("html", code.language)
        assertEquals("<!DOCTYPE html>\n<p>Hello</p>\n", code.code)
    }

    @Test
    fun tildesAndQuotedFileMetadataAreParsedWithoutEditingBody() {
        val code = parseChatMarkdownSource("~~~text title='my file.txt'\n  null null null  \n~~~")
            .filterIsInstance<ChatMarkdownSource.Code>().single()
        assertEquals("my file.txt", code.fileName)
        assertEquals("  null null null  \n", code.code)
    }

    @Test
    fun inlineFenceSpellingIsNotRewrittenIntoABlock() {
        val source = "A literal ```pythonprint('hi') and 1*1=1 2*2=4"
        assertEquals(listOf(ChatMarkdownSource.Text(source)), parseChatMarkdownSource(source))
    }
}
