package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownListFormattingTest {
    @Test
    fun `ordered marker accepts chinese punctuation and omitted spaces`() {
        listOf("1. first", "1.first", "1) first", "1)first", "1、第一项", "1、 第一项")
            .forEach { value ->
                assertEquals("expected ordered row: $value", true, value.isOrderedMarkdownLine())
            }
    }

    @Test
    fun `ordered marker does not classify decimals as list rows`() {
        listOf("1.23", "version 1.23", "1.2 继续", "12.3456").forEach { value ->
            assertEquals("expected prose: $value", false, value.isOrderedMarkdownLine())
        }
    }

    @Test
    fun `source numbers survive visual list blocks`() {
        assertEquals(1, parseOrderedMarkdownItem("1. first", 9)?.number)
        assertEquals(2, parseOrderedMarkdownItem("2) second", 9)?.number)
        assertEquals(3, parseOrderedMarkdownItem("3、third", 9)?.number)
        assertEquals(12, parseOrderedMarkdownItem("12.内容", 9)?.number)
        assertEquals("second", parseOrderedMarkdownItem("2) second", 9)?.text)
    }

    @Test
    fun `embedded rows split without changing decimals`() {
        assertEquals(
            "1、第一项\n2.第二项\n3) third",
            splitEmbeddedOrderedMarkdownRows("1、第一项 2.第二项 3) third")
        )
        assertEquals("version 1.23", splitEmbeddedOrderedMarkdownRows("version 1.23"))
    }

    @Test
    fun `strip marker preserves no space payload`() {
        assertEquals("第一项", stripOrderedMarkdownMarker("1、第一项"))
        assertEquals("second", stripOrderedMarkdownMarker("2.second"))
    }

    @Test
    fun `malformed ordered rows are ignored`() {
        assertNull(parseOrderedMarkdownItem("not a list item", 1))
        assertNull(parseOrderedMarkdownItem("version 1.23", 1))
    }
}
