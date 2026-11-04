package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the consolidated message-action menu:
 * - one delete entry only, the full "本轮对话" rollback;
 * - both roles expose "引用回复";
 * - a user message exposes "编辑并重发", disabled with an explanation while generating.
 */
class ChatMessageActionMenuContractTest {

    @Test
    fun `assistant menu keeps exactly one delete entry`() {
        val source = source("ChatAssistantActionViews.kt")
        assertTrue(source.contains("onDeleteLastTurn()"))
        assertEquals(
            "only the whole-turn delete may render a delete icon",
            1,
            Regex("McaIcons\\.Delete").findAll(source).count()
        )
        assertFalse("the single-message delete menu item must stay removed", source.contains("删除本条"))
    }

    @Test
    fun `assistant menu offers quote reply`() {
        val source = source("ChatAssistantActionViews.kt")
        assertTrue(source.contains("Text(\"引用回复\")"))
        assertTrue(source.contains("onQuote()"))
    }

    @Test
    fun `user message menu offers edit and quote but no delete`() {
        val source = source("ChatMessageActionMenus.kt")
        assertTrue(source.contains("编辑并重发"))
        assertTrue(source.contains("生成中不可用"))
        assertTrue(source.contains("Text(\"引用回复\")"))
        assertFalse(source.contains("删除本条"))
        assertFalse("the user menu must not duplicate the turn delete", source.contains("McaIcons.Delete"))
    }

    @Test
    fun `a generating edit entry is disabled and explains why`() {
        val source = source("ChatMessageActionMenus.kt")
        assertTrue(source.contains("!canEdit -> \"编辑并重发（生成中不可用）\""))
        assertTrue(source.contains("enabled = editEnabled"))
        assertTrue(source.contains("val editEnabled = !readOnly && canEdit"))
    }

    private fun source(name: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(
                directory,
                "feature/chat/src/main/java/com/muyuchat/feature/chat/$name"
            )
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate $name")
    }
}
