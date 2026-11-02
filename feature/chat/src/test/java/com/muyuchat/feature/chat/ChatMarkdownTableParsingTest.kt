package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R02 table parsing behaviour.
 *
 * The old splitter (`trim().trim('|').split('|').filter { it.isNotBlank() }`) deleted real empty
 * cells, so every later column shifted left, and it split on escaped `\|` and on `|` inside inline
 * code spans. These fixtures pin the corrected cell model: outer boundary pipes are structure,
 * empty cells are content, and ragged rows are padded instead of losing cells.
 */
class ChatMarkdownTableParsingTest {

    private fun rowsOf(content: String): List<List<String>> =
        parseMarkdownBlocks(content)
            .filterIsInstance<MarkdownBlock.Table>()
            .single()
            .rows

    @Test
    fun `first middle and last empty columns stay in place`() {
        val content = """
            | | B | |
            | --- | --- | --- |
            | a | | c |
        """.trimIndent()

        val rows = rowsOf(content)

        assertEquals(
            listOf(
                listOf("", "B", ""),
                listOf("a", "", "c")
            ),
            rows
        )
        // Shared column geometry: the empty columns still occupy a column instead of collapsing.
        assertEquals(3, tableColumnWidthsDp(rows).size)
    }

    @Test
    fun `a leading empty cell does not shift the later columns left`() {
        val content = """
            | Name | Value |
            | --- | --- |
            |  | 42
        """.trimIndent()

        assertEquals(
            listOf(
                listOf("Name", "Value"),
                listOf("", "42")
            ),
            rowsOf(content)
        )
    }

    @Test
    fun `escaped pipes stay inside their cell`() {
        val content = """
            | Pattern | Meaning |
            | --- | --- |
            | a \| b | literal pipe |
        """.trimIndent()

        assertEquals(
            listOf(
                listOf("Pattern", "Meaning"),
                listOf("a | b", "literal pipe")
            ),
            rowsOf(content)
        )
    }

    @Test
    fun `pipes inside inline code of different fence lengths do not split cells`() {
        val content = """
            | Single | Double |
            | --- | --- |
            | `a|b` | ``c|d`` |
            | `x||y` | plain |
        """.trimIndent()

        assertEquals(
            listOf(
                listOf("Single", "Double"),
                listOf("`a|b`", "``c|d``"),
                listOf("`x||y`", "plain")
            ),
            rowsOf(content)
        )
    }

    @Test
    fun `ragged rows are padded to the widest row without losing cells`() {
        val content = """
            | A | B | C |
            | --- | --- | --- |
            | 1 |
            | 2 | 3 | 4 | 5 |
        """.trimIndent()

        val rows = rowsOf(content)

        assertEquals(4, rows.maxOf { it.size })
        assertTrue(rows.all { it.size == 4 })
        assertEquals(listOf("A", "B", "C", ""), rows[0])
        assertEquals(listOf("1", "", "", ""), rows[1])
        assertEquals(listOf("2", "3", "4", "5"), rows[2])
    }

    @Test
    fun `long chinese and english cells are preserved verbatim`() {
        val chinese = "这是一个需要完整保留的长中文单元格内容，用于验证不会被截断或合并"
        val english = "A rather long English cell that must be kept verbatim, with spaces and punctuation"
        val content = """
            | 指标 | 说明 |
            | --- | --- |
            | $chinese | $english |
        """.trimIndent()

        assertEquals(
            listOf(
                listOf("指标", "说明"),
                listOf(chinese, english)
            ),
            rowsOf(content)
        )
    }

    @Test
    fun `an unfinished last row without a trailing pipe is still a table row`() {
        val content = """
            | Name | Value |
            | --- | --- |
            | total | 42
        """.trimIndent()

        assertEquals(
            listOf(
                listOf("Name", "Value"),
                listOf("total", "42")
            ),
            rowsOf(content)
        )
    }

    @Test
    fun `data rows that merely contain dashes are never treated as the separator`() {
        val content = """
            | A | B |
            | --- | --- |
            | --- | x |
            | a \| b | --- |
        """.trimIndent()

        assertEquals(
            listOf(
                listOf("A", "B"),
                listOf("---", "x"),
                listOf("a | b", "---")
            ),
            rowsOf(content)
        )
    }

    @Test
    fun `a table directly followed by code and a list keeps its order and content`() {
        val content = "Intro\n" +
            "| A | B |\n" +
            "| --- | --- |\n" +
            "| 1 | 2 |\n" +
            "```kotlin\n" +
            "val x = 1\n" +
            "```\n" +
            "- one\n" +
            "- two"

        val blocks = parseMarkdownBlocks(content)
        val table = blocks.filterIsInstance<MarkdownBlock.Table>().single()
        assertEquals(listOf(listOf("A", "B"), listOf("1", "2")), table.rows)
        assertEquals(listOf("kotlin"), blocks.filterIsInstance<MarkdownBlock.Code>().map { it.language })
        assertEquals(listOf("one", "two"), blocks.filterIsInstance<MarkdownBlock.BulletList>().single().items)

        val tableIndex = blocks.indexOfFirst { it is MarkdownBlock.Table }
        val codeIndex = blocks.indexOfFirst { it is MarkdownBlock.Code }
        val listIndex = blocks.indexOfFirst { it is MarkdownBlock.BulletList }
        assertTrue(tableIndex in 0 until codeIndex)
        assertTrue(codeIndex < listIndex)
    }

    @Test
    fun `a table without outer trailing pipe keeps every cell`() {
        val content = "| A | B | C\n" +
            "| --- | --- | --- |\n" +
            "| 1 | 2 | 3"

        assertEquals(
            listOf(
                listOf("A", "B", "C"),
                listOf("1", "2", "3")
            ),
            rowsOf(content)
        )
    }
}
