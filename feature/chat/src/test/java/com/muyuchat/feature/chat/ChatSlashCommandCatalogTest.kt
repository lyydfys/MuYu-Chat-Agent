package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSlashCommandCatalogTest {
    @Test
    fun slashOpensOnlyImplementedCommandsWithChineseDescriptions() {
        val suggestions = chatSlashCommandSuggestions("/")

        assertEquals(listOf("/image"), suggestions.map(ChatSlashCommand::command))
        assertTrue(suggestions.single().title.isNotBlank())
        assertTrue(suggestions.single().description.contains("中文"))
    }

    @Test
    fun partialCommandFiltersCatalogAndUnimplementedSearchIsNotAdvertised() {
        assertEquals(listOf("/image"), chatSlashCommandSuggestions("/im").map(ChatSlashCommand::command))
        assertTrue(chatSlashCommandSuggestions("/search").isEmpty())
        assertTrue(chatSlashCommandSuggestions("帮我 /image").isEmpty())
    }

    @Test
    fun selectedImageCommandLeavesComposerReadyForChinesePrompt() {
        val image = CHAT_SLASH_COMMAND_CATALOG.single()

        assertEquals("/image ", insertChatSlashCommand("/i", image))
        assertEquals("/image 一只猫", insertChatSlashCommand("/im 一只猫", image))
        assertEquals("  /image 一只猫", insertChatSlashCommand("  /im 一只猫", image))
    }

    @Test
    fun selectionPreservesNewlineAndTextAfterCommandToken() {
        val image = CHAT_SLASH_COMMAND_CATALOG.single()

        assertEquals("/image 一只猫\n再加上蓝色背景", insertChatSlashCommand("/im 一只猫\n再加上蓝色背景", image))
    }
}
