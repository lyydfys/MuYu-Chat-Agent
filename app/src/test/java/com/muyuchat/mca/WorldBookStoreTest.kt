package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldBookStoreTest {
    @Test
    fun parsesObjectEntriesFromNestedCharacterBook() {
        val root = JSONObject(
            """
            {
              "character_book": {
                "title": "江湖设定",
                "entries": {
                  "fallback-id": {
                    "key": "龙门, 客栈",
                    "content": "龙门客栈位于大漠边缘。",
                    "order": 21
                  },
                  "constant-entry": {
                    "uid": 73,
                    "entry": "所有回答都使用简体中文。",
                    "constant": true,
                    "priority": 9
                  },
                  "ignored": {
                    "key": "unused",
                    "content": "   "
                  }
                }
              }
            }
            """.trimIndent()
        )

        val book = WorldBookCodec.parse(
            root = root,
            scope = WorldBookScope.ASSISTANT,
            assistantId = "assistant-1"
        )

        assertEquals("江湖设定", book.name)
        assertEquals(WorldBookScope.ASSISTANT, book.scope)
        assertEquals("assistant-1", book.assistantId)
        assertEquals(2, book.entries.size)

        val keyed = requireNotNull(book.entries.firstOrNull { it.id == "fallback-id" })
        assertEquals(listOf("龙门", "客栈"), keyed.keys)
        assertEquals("龙门客栈位于大漠边缘。", keyed.content)
        assertEquals(21, keyed.priority)
        assertFalse(keyed.constant)

        val constant = requireNotNull(book.entries.firstOrNull { it.id == "73" })
        assertTrue(constant.constant)
        assertTrue(constant.keys.isEmpty())
        assertEquals(9, constant.priority)
    }

    @Test
    fun parsesArrayEntriesAndKeyAliasesFromRawJson() {
        val result = WorldBookCodec.parse(
            rawJson = """
                {
                  "name": "城市资料",
                  "entries": [
                    {
                      "uid": "shanghai",
                      "keys": "上海, 魔都\n沪上",
                      "content": "上海简称沪。",
                      "priority": 12
                    },
                    {
                      "uid": "disabled",
                      "key": ["杭州", "西湖"],
                      "content": "杭州以西湖闻名。",
                      "enabled": false,
                      "order": 7
                    },
                    {
                      "uid": "constant",
                      "content": "保持回答简洁。",
                      "constant": true
                    }
                  ]
                }
            """.trimIndent(),
            scope = WorldBookScope.CHAT,
            chatSessionId = "chat-7"
        )

        assertTrue(result.isSuccess)
        val book = requireNotNull(result.book)
        assertEquals("城市资料", book.name)
        assertEquals(WorldBookScope.CHAT, book.scope)
        assertEquals("chat-7", book.chatSessionId)
        assertEquals(3, book.entries.size)

        val shanghai = requireNotNull(book.entries.firstOrNull { it.id == "shanghai" })
        assertEquals(listOf("上海", "魔都", "沪上"), shanghai.keys)
        assertEquals(12, shanghai.priority)

        val disabled = requireNotNull(book.entries.firstOrNull { it.id == "disabled" })
        assertFalse(disabled.enabled)
        assertTrue(disabled.keys.containsAll(listOf("杭州", "西湖")))
        assertEquals(7, disabled.priority)

        assertTrue(requireNotNull(book.entries.firstOrNull { it.id == "constant" }).constant)
    }

    @Test
    fun matchesChineseAndUnicodeNormalizedKeywords() {
        val book = worldBook(
            name = "Unicode",
            entries = listOf(
                entry("chinese", listOf("杭州西湖"), "西湖有苏堤与白堤。", priority = 3),
                entry("full-width", listOf("ＡＩ助手"), "AI 助手应先确认目标。", priority = 2),
                entry("combining", listOf("Cafe\u0301"), "Café 使用组合字符匹配。", priority = 1)
            )
        )

        val selection = WorldBookResolver.select(
            books = listOf(book),
            messages = listOf(
                ChatMessage(Role.USER, "请让ai助手介绍杭州西湖，并补充 CAFÉ 的写法。")
            ),
            assistantId = "assistant",
            chatSessionId = "chat",
            tokenBudget = 128
        )

        assertEquals(listOf("chinese", "full-width", "combining"), selection.selectedEntryIds)
        assertTrue(selection.context.contains("西湖有苏堤与白堤。"))
        assertTrue(selection.context.contains("AI 助手应先确认目标。"))
        assertTrue(selection.context.contains("Café 使用组合字符匹配。"))
    }

    @Test
    fun filtersByScopeAndSortsConstantsBeforePriority() {
        val trigger = listOf(ChatMessage(Role.USER, "trigger all matching entries"))
        val books = listOf(
            worldBook(
                name = "global",
                entries = listOf(
                    entry("global-high", listOf("trigger"), "global high", priority = 100),
                    entry("global-constant", emptyList(), "global constant", constant = true, priority = -100)
                )
            ),
            worldBook(
                name = "assistant-match",
                scope = WorldBookScope.ASSISTANT,
                assistantId = "assistant-1",
                entries = listOf(entry("assistant-match", listOf("trigger"), "assistant match", priority = 50))
            ),
            worldBook(
                name = "assistant-other",
                scope = WorldBookScope.ASSISTANT,
                assistantId = "assistant-2",
                entries = listOf(entry("assistant-other", listOf("trigger"), "must not appear", priority = 1000))
            ),
            worldBook(
                name = "chat-match",
                scope = WorldBookScope.CHAT,
                chatSessionId = "chat-1",
                entries = listOf(entry("chat-match", listOf("trigger"), "chat match", priority = 40))
            ),
            worldBook(
                name = "chat-other",
                scope = WorldBookScope.CHAT,
                chatSessionId = "chat-2",
                entries = listOf(entry("chat-other", listOf("trigger"), "must not appear", priority = 900))
            ),
            worldBook(
                name = "disabled",
                enabled = false,
                entries = listOf(entry("disabled", listOf("trigger"), "must not appear", priority = 800))
            )
        )

        val selection = WorldBookResolver.select(
            books = books,
            messages = trigger,
            assistantId = "assistant-1",
            chatSessionId = "chat-1",
            tokenBudget = 128
        )

        assertEquals(
            listOf("global-constant", "global-high", "assistant-match", "chat-match"),
            selection.selectedEntryIds
        )
        assertFalse(selection.context.contains("must not appear"))
        assertTrue(selection.context.indexOf("global constant") < selection.context.indexOf("global high"))
        assertTrue(selection.context.indexOf("global high") < selection.context.indexOf("assistant match"))
        assertTrue(selection.context.indexOf("assistant match") < selection.context.indexOf("chat match"))
    }

    @Test
    fun tokenBudgetSkipsOversizedPriorityEntryAndStillFitsLaterEntries() {
        val book = worldBook(
            name = "budget",
            entries = listOf(
                entry("too-large", listOf("budget"), "甲乙丙丁", priority = 30),
                entry("medium", listOf("budget"), "中文", priority = 20),
                entry("small", listOf("budget"), "ok", priority = 10)
            )
        )

        val selection = WorldBookResolver.select(
            books = listOf(book),
            messages = listOf(ChatMessage(Role.USER, "budget")),
            assistantId = "assistant",
            chatSessionId = null,
            tokenBudget = 3
        )

        assertEquals(listOf("medium", "small"), selection.selectedEntryIds)
        assertEquals(listOf("too-large"), selection.skippedEntryIds)
        assertEquals(3, selection.estimatedTokens)
        assertTrue(selection.context.contains("中文"))
        assertTrue(selection.context.contains("ok"))
        assertFalse(selection.context.contains("甲乙丙丁"))

        assertEquals(2, WorldBookResolver.estimateTokens("中文"))
        assertEquals(2, WorldBookResolver.estimateTokens("abcdef"))
        assertEquals(2, WorldBookResolver.estimateTokens("中abc"))
        assertEquals(0, WorldBookResolver.estimateTokens(" \n\t"))
    }

    @Test
    fun nonPositiveBudgetProducesNoSelection() {
        val selection = WorldBookResolver.select(
            books = listOf(
                worldBook(
                    name = "constant",
                    entries = listOf(entry("constant", emptyList(), "always", constant = true))
                )
            ),
            messages = listOf(ChatMessage(Role.USER, "anything")),
            assistantId = "assistant",
            chatSessionId = "chat",
            tokenBudget = 0
        )

        assertEquals(WorldBookSelection(), selection)
    }

    private fun entry(
        id: String,
        keys: List<String>,
        content: String,
        constant: Boolean = false,
        priority: Int = 0
    ): WorldBookEntry = WorldBookEntry(
        id = id,
        keys = keys,
        content = content,
        constant = constant,
        priority = priority
    )

    private fun worldBook(
        name: String,
        scope: WorldBookScope = WorldBookScope.GLOBAL,
        assistantId: String? = null,
        chatSessionId: String? = null,
        enabled: Boolean = true,
        entries: List<WorldBookEntry>
    ): WorldBookRecord = WorldBookRecord(
        id = name,
        name = name,
        scope = scope,
        assistantId = assistantId,
        chatSessionId = chatSessionId,
        enabled = enabled,
        entries = entries
    )
}
