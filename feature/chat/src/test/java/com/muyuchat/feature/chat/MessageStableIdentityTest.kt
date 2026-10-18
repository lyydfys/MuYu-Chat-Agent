package com.muyuchat.feature.chat

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageStableIdentityTest {
    @Test
    fun `identity follows message id through prepend delete and reorder`() {
        val first = message("a")
        val second = message("b")
        val third = message("c")
        val initial = chatMessageRows(listOf(first, second, third), "conversation")
        val initialById = initial.associateBy { row -> row.message.id }

        val prepended = chatMessageRows(listOf(message("new"), first, second, third), "conversation")
        assertEquals(initialById.getValue("a").identity, prepended.first { it.message.id == "a" }.identity)
        assertEquals(initialById.getValue("b").identity, prepended.first { it.message.id == "b" }.identity)
        assertEquals(initialById.getValue("c").identity, prepended.first { it.message.id == "c" }.identity)

        val afterDelete = chatMessageRows(listOf(first, third), "conversation")
        assertEquals(initialById.getValue("a").identity, afterDelete.first { it.message.id == "a" }.identity)
        assertEquals(initialById.getValue("c").identity, afterDelete.first { it.message.id == "c" }.identity)

        val reordered = chatMessageRows(listOf(third, first, second), "conversation")
        assertEquals(initialById.getValue("a").identity, reordered.first { it.message.id == "a" }.identity)
        assertEquals(initialById.getValue("b").identity, reordered.first { it.message.id == "b" }.identity)
        assertEquals(initialById.getValue("c").identity, reordered.first { it.message.id == "c" }.identity)
    }

    @Test
    fun `same timestamp and body remain distinct when ids differ`() {
        val rows = chatMessageRows(
            listOf(message("first"), message("second")),
            "conversation"
        )

        assertNotEquals(rows[0].identity, rows[1].identity)
        assertEquals(1L, rows[0].message.createdAt)
        assertEquals(rows[0].message.content, rows[1].message.content)
    }

    @Test
    fun `duplicate and blank ids get deterministic unique identities`() {
        val messages = listOf(
            message("duplicate"),
            message("duplicate"),
            message(""),
            message("")
        )

        val firstPass = chatMessageRows(messages, "conversation")
        val secondPass = chatMessageRows(messages, "conversation")

        assertEquals(firstPass, secondPass)
        assertEquals(4, firstPass.map { row -> row.identity }.distinct().size)
        assertEquals(listOf(0, 1, 0, 1), firstPass.map { row -> row.identity.duplicateOrdinal })
    }

    @Test
    fun `conversation identity separates otherwise identical message ids`() {
        val messages = listOf(message("same"))

        val firstConversation = chatMessageRows(messages, "conversation-a").single().identity
        val secondConversation = chatMessageRows(messages, "conversation-b").single().identity

        assertNotEquals(firstConversation, secondConversation)
    }

    @Test
    fun `list uses row identity instead of index time or body`() {
        val source = sourceFile("ChatMessageListSection.kt")

        assertTrue(source.contains("chatMessageRows(state.messages, state.activeConversationId)"))
        assertTrue(source.contains("key = { _, row -> row.identity }"))
        assertFalse(source.contains("message.createdAt}:" + '$' + "index"))
        assertFalse(source.contains("randomUUID"))
    }

    private fun message(id: String): ChatMessage = ChatMessage(
        role = Role.USER,
        content = "same body",
        createdAt = 1L,
        id = id
    )

    private fun sourceFile(name: String): String {
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
