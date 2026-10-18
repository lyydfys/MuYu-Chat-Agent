package com.muyuchat.feature.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住 inline 解析的渲染语义：URL 单调游标优化与候选顺序无关，
 * 任何改动都必须保持“文本内容、下划线范围、代码/加粗分支”等价。
 */
class InlineMarkdownStabilityTest {

    private val codeColor = Color(0xFF112233)
    private val codeBackground = Color(0xFFEEEEEE)
    private val linkColor = Color(0xFF0000FF)

    private fun build(value: String): AnnotatedString =
        buildInlineMarkdown(value, codeColor, codeBackground, linkColor)

    private fun AnnotatedString.styled(predicate: (SpanStyle) -> Boolean): List<String> =
        spanStyles.filter { predicate(it.item) }.map { range ->
            text.substring(range.start, range.end)
        }

    @Test
    fun `plain text with multiple urls keeps every character`() {
        val value = "first https://a.example/1 then https://b.example/2 end"
        val result = build(value)
        assertEquals(value, result.text)
        val underlined = result.styled { it.textDecoration != null }
        assertEquals(2, underlined.size)
        assertEquals("https://a.example/1", underlined[0])
        assertEquals("https://b.example/2", underlined[1])
    }

    @Test
    fun `url at the very start and very end is underlined exactly`() {
        val value = "https://start.example/x middle https://end.example/y"
        val result = build(value)
        assertEquals(value, result.text)
        val underlined = result.styled { it.textDecoration != null }
        assertEquals(2, underlined.size)
        assertEquals("https://start.example/x", underlined[0])
        assertEquals("https://end.example/y", underlined[1])
    }

    @Test
    fun `markdown link renders its label and stays underlined`() {
        val value = "see [docs](https://example.com/doc) now"
        val result = build(value)
        assertEquals("see docs now", result.text)
        val underlined = result.styled { it.textDecoration != null }
        assertEquals(1, underlined.size)
        assertEquals("docs", underlined[0])
    }

    @Test
    fun `inline code and bold after url still take their own branches`() {
        val value = "https://a.example/1 **bold** `code` tail"
        val result = build(value)
        // 标记字符在渲染文本中被移除，只剩样式范围；URL 原样保留。
        assertEquals("https://a.example/1 bold code tail", result.text)
        val mono = result.styled { it.fontFamily != null }
        assertEquals(1, mono.size)
        assertEquals("code", mono[0])
        val bold = result.styled { it.fontWeight != null }
        assertEquals(1, bold.size)
        assertEquals("bold", bold[0])
        val underlined = result.styled { it.textDecoration != null }
        assertEquals(1, underlined.size)
        assertEquals("https://a.example/1", underlined[0])
    }

    @Test
    fun `text without any marker stays a single plain run`() {
        val value = "just a normal Chinese sentence，没有任何标记。"
        val result = build(value)
        assertEquals(value, result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun `detectCodeLanguage keeps its existing ordering`() {
        assertEquals("python", detectCodeLanguage(listOf("def main():")))
        assertEquals("kotlin", detectCodeLanguage(listOf("fun main() {}")))
        assertEquals("typescript", detectCodeLanguage(listOf("const x = 1")))
        assertEquals("sql", detectCodeLanguage(listOf("select * from t")))
    }
}
