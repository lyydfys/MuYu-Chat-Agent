package com.muyuchat.feature.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
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

    @Test
    fun equalContentKeepsParsedBlocksAndCompleteCodeCopy() {
        val source = "Intro\n```kotlin file=\"sample.kt\"\nval answer = 42\n```\nAfter"

        assertEquals(
            listOf(
                MarkdownBlock.Paragraph("Intro\n"),
                MarkdownBlock.Code(
                    language = "kotlin",
                    code = "val answer = 42\n",
                    fileName = "sample.kt",
                    copyCode = "val answer = 42\n"
                ),
                MarkdownBlock.Paragraph("After")
            ),
            parseMarkdownBlocks(source)
        )
    }

    @Test
    fun truncatedDisplayContentStillCopiesCompleteCode() {
        val visible = "Intro\n```kotlin\nval answer = 42"
        val full = visible + "\n```\nAfter\n```python\nprint('next')\n```\nEnd"
        val code = parseMarkdownBlocks(visible, full)
            .filterIsInstance<MarkdownBlock.Code>()
            .single()

        assertEquals("val answer = 42", code.code)
        assertEquals("val answer = 42\n", code.copyCode)
    }

    @Test
    fun multipleCodeBlocksRetainSourceOrder() {
        val source = "```kotlin filename=\"one.kt\"\nval one = 1\n```\n" +
            "Between\n" +
            "```python filename=\"two.py\"\nprint('two')\n```\n" +
            "Last\n" +
            "```json filename=\"three.json\"\n{\"three\": 3}\n```"
        val codes = parseMarkdownBlocks(source).filterIsInstance<MarkdownBlock.Code>()

        assertEquals(listOf("kotlin", "python", "json"), codes.map { it.language })
        assertEquals(listOf("one.kt", "two.py", "three.json"), codes.map { it.fileName })
        assertEquals(
            listOf("val one = 1\n", "print('two')\n", "{\"three\": 3}\n"),
            codes.map { it.copyCode }
        )
    }

    @Test
    fun syntaxHighlighterPreservesExactTextAndAppliesSpans() {
        val sample = "// comment\nval answer: String = \"42\"\nreturn answer.length + 1\n"
        val highlighted = buildHighlightedCode(sample, "kotlin")
        assertEquals(sample, highlighted.text)
        assertTrue(highlighted.spanStyles.isNotEmpty())
        highlighted.spanStyles.forEach { range ->
            assertTrue(range.start >= 0 && range.end <= sample.length && range.start < range.end)
        }
    }

    @Test
    fun markdownBlockKindKeysAreDistinctPerVariant() {
        val blocks = listOf(
            MarkdownBlock.Heading("H", 1),
            MarkdownBlock.Quote("Q"),
            MarkdownBlock.BulletList(listOf("a")),
            MarkdownBlock.NumberedList(listOf(OrderedMarkdownItem(1, "a"))),
            MarkdownBlock.Table(listOf(listOf("h", "c"))),
            MarkdownBlock.Code("kt", "val x = 1", ""),
            MarkdownBlock.Divider,
            MarkdownBlock.Paragraph("p")
        )
        assertEquals(blocks.size, blocks.map { it.blockKindKey }.distinct().size)
    }

    @Test
    fun streamingTailSettlesDuringANetworkPauseWithoutChangingTextSpans() {
        // Colour is now masked at draw time; no appended colour spans override inline code/links.
        val tracker = StreamingTailTracker()
        val tail = tracker.update("Streaming typography fade", true, 0L)
        assertTrue(tail.isNotEmpty())
        assertTrue(tail.last().alpha(0L) < 1f)
        assertEquals(1f, tail.last().alpha(160_000_000L), 0f)
    }
}
