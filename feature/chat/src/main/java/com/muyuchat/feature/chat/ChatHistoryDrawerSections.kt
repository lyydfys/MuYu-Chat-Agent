package com.muyuchat.feature.chat

import androidx.compose.foundation.lazy.items
import java.util.Calendar

internal data class HistorySection(
    val title: String,
    val items: List<ChatHistoryItem>
)

internal fun filterHistoryItems(
    items: List<ChatHistoryItem>,
    query: String
): List<ChatHistoryItem> {
    val keyword = query.trim()
    return if (keyword.isBlank()) {
        items
    } else {
        items.filter { item ->
            item.title.contains(keyword, ignoreCase = true) ||
                item.displayName.contains(keyword, ignoreCase = true) ||
                item.summary.contains(keyword, ignoreCase = true) ||
                item.updatedAtText.contains(keyword, ignoreCase = true)
        }
    }
}

internal fun historySections(items: List<ChatHistoryItem>, query: String): List<HistorySection> {
    if (query.trim().isNotEmpty()) {
        return listOf(HistorySection("搜索结果", items))
    }
    val order = listOf("置顶", "今天", "昨天", "7 天内", "更早")
    return items
        .groupBy { historySectionTitle(it) }
        .map { (title, sectionItems) -> HistorySection(title, sectionItems) }
        .sortedBy { section -> order.indexOf(section.title).let { if (it < 0) Int.MAX_VALUE else it } }
}

private fun historySectionTitle(item: ChatHistoryItem): String {
    if (item.pinned) return "置顶"
    val now = Calendar.getInstance()
    val updated = Calendar.getInstance().apply { timeInMillis = item.updatedAtMillis }
    if (sameDay(now, updated)) return "今天"

    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (sameDay(yesterday, updated)) return "昨天"

    val sevenDaysAgo = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }
    return if (updated.after(sevenDaysAgo)) "7 天内" else "更早"
}

private fun sameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
