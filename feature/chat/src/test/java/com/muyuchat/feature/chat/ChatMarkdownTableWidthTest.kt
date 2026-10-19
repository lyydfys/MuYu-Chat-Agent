package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The table grid must line up: every row uses the same per-column width, the width comes from the
 * widest cell in that column, and the result is clamped so one huge cell cannot swallow the table.
 */
class ChatMarkdownTableWidthTest {

    @Test
    fun `every row shares the column width of the widest cell`() {
        val rows = listOf(
            listOf("A", "B"),
            listOf("a much longer first cell", "x"),
        )

        val widths = tableColumnWidthsDp(rows)

        assertEquals(2, widths.size)
        assertTrue("first column must widen for the long cell", widths[0] > widths[1])
        // The invariant the renderer relies on: one width per column, not per row.
        assertEquals(widths, tableColumnWidthsDp(rows))
    }

    @Test
    fun `ragged rows keep shared widths and never crash`() {
        val rows = listOf(
            listOf("one", "two", "three"),
            listOf("only-one", "only-two"),
        )

        val widths = tableColumnWidthsDp(rows)

        assertEquals(3, widths.size)
        assertTrue(widths[0] >= MIN_TABLE_COLUMN_DP)
        assertTrue(widths[2] >= MIN_TABLE_COLUMN_DP)
    }

    @Test
    fun `tiny cells use the minimum and huge cells the maximum`() {
        val narrow = tableColumnWidthsDp(listOf(listOf("i")))
        val wide = tableColumnWidthsDp(listOf(listOf("x".repeat(400))))

        assertEquals(MIN_TABLE_COLUMN_DP, narrow.single(), 0.01f)
        assertEquals(MAX_TABLE_COLUMN_DP, wide.single(), 0.01f)
    }

    @Test
    fun `cjk cells are estimated wider than latin cells of the same length`() {
        val cjk = estimatedTableCellWidthDp("中文字")
        val latin = estimatedTableCellWidthDp("abc")

        assertTrue("CJK glyphs take about a full em", cjk > latin)
    }

    @Test
    fun `empty table yields no columns`() {
        assertTrue(tableColumnWidthsDp(emptyList()).isEmpty())
    }
}
