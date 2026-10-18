package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHistorySearchTest {
    @Test
    fun `blank query keeps every item`() {
        val items = listOf(
            historyItem("first", title = "First"),
            historyItem("second", title = "Second")
        )

        val result = filterHistoryItems(items, " \t ")

        assertEquals(listOf("first", "second"), result.map(ChatHistoryItem::id))
    }

    @Test
    fun `query is trimmed before matching`() {
        val items = listOf(historyItem("match", title = "Needle"))

        val result = filterHistoryItems(items, "  Needle  ")

        assertEquals(listOf("match"), result.map(ChatHistoryItem::id))
    }

    @Test
    fun `matching ignores case`() {
        val items = listOf(historyItem("match", displayName = "Mixed Case Topic"))

        val result = filterHistoryItems(items, "mixed case topic")

        assertEquals(listOf("match"), result.map(ChatHistoryItem::id))
    }

    @Test
    fun `query matches title`() {
        val items = listOf(
            historyItem(
                "match",
                title = "Quarterly Archive",
                displayName = "unrelated display name"
            )
        )

        val result = filterHistoryItems(items, "quarterly archive")

        assertEquals(listOf("match"), result.map(ChatHistoryItem::id))
    }

    @Test
    fun `query matches display name`() {
        val items = listOf(
            historyItem(
                "match",
                title = "session-042",
                displayName = "Visible Project Name"
            )
        )

        val result = filterHistoryItems(items, "visible project")

        assertEquals(listOf("match"), result.map(ChatHistoryItem::id))
    }

    @Test
    fun `query matches summary`() {
        val items = listOf(historyItem("match", summary = "Roadmap notes"))

        val result = filterHistoryItems(items, "roadmap")

        assertEquals(listOf("match"), result.map(ChatHistoryItem::id))
    }

    @Test
    fun `non matching query returns no items`() {
        val items = listOf(historyItem("match", title = "Quarterly Archive"))

        val result = filterHistoryItems(items, "missing")

        assertEquals(emptyList<String>(), result.map(ChatHistoryItem::id))
    }

    @Test
    fun `query matches updated time text`() {
        val items = listOf(historyItem("match", updatedAtText = "昨天 12:00"))

        val result = filterHistoryItems(items, "昨天 12:00")

        assertEquals(listOf("match"), result.map(ChatHistoryItem::id))
    }

    private fun historyItem(
        id: String,
        title: String = id,
        displayName: String = title,
        summary: String = "",
        updatedAtText: String = "2026-10-18 10:00"
    ): ChatHistoryItem = ChatHistoryItem(
        id = id,
        title = title,
        displayName = displayName,
        summary = summary,
        updatedAtText = updatedAtText,
        updatedAtMillis = 0L,
        messageCount = 0,
        pinned = false,
        selected = false
    )
}
