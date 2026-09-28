package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

class AssistantMemoryPersistenceTest {
    @Test
    fun completedTurnInputsAreBoundedBeforePersistence() {
        val turn = AssistantMemoryTurnRecord(
            id = "reply-1", assistantId = "role-1", sessionId = "session-1",
            userText = "u".repeat(AssistantMemoryTurnRecord.MAX_TEXT_CHARS + 100),
            assistantText = "a".repeat(AssistantMemoryTurnRecord.MAX_TEXT_CHARS + 100),
            createdAt = 1L
        )

        val bounded = requireNotNull(turn.boundedForPersistence())
        assertEquals(AssistantMemoryTurnRecord.MAX_TEXT_CHARS, bounded.userText.length)
        assertEquals(AssistantMemoryTurnRecord.MAX_TEXT_CHARS, bounded.assistantText.length)
        assertEquals("reply-1", bounded.id)
        assertNull(turn.copy(id = "").boundedForPersistence())
        assertNull(turn.copy(userText = "", assistantText = "").boundedForPersistence())
    }

    @Test
    fun memoryContentIsBoundedWithoutChangingRoleOwnership() {
        val record = MemoryRecord(
            id = "memory-1", assistantId = "role-1", scope = "user_profile",
            content = "x".repeat(20_000), source = "manual", createdAt = 1L
        )

        val bounded = requireNotNull(record.boundedForPersistence())
        assertEquals(12_000, bounded.content.length)
        assertEquals("role-1", bounded.assistantId)
        assertNull(record.copy(content = "  ").boundedForPersistence())
    }

    @Test
    fun completedTurnMustStillBelongToTheSameRoleAndPersistedReply() {
        val turn = AssistantMemoryTurnRecord(
            id = "reply-1", assistantId = "role-1", sessionId = "session-1",
            userText = "Question", assistantText = "Answer", createdAt = 1L
        )
        val reply = ChatMessage(id = turn.id, role = Role.ASSISTANT, content = turn.assistantText)
        val session = ChatSessionRecord(
            id = turn.sessionId, title = "Chat", messages = listOf(reply), assistantId = turn.assistantId,
            assistantSnapshot = AssistantConversationSnapshot(
                assistantId = turn.assistantId, name = "Role 1", systemPrompt = "Persona",
                memoryEnabled = true, webSearchEnabled = false,
                fileContextEnabled = true, capturedAt = 1L
            )
        )

        assertTrue(listOf(session).containsCompletedMemoryTurn(turn))
        assertFalse(emptyList<ChatSessionRecord>().containsCompletedMemoryTurn(turn))
        assertFalse(listOf(session.copy(assistantId = "role-2")).containsCompletedMemoryTurn(turn))
        assertFalse(listOf(session.copy(mixedAssistantHistory = true)).containsCompletedMemoryTurn(turn))
        assertFalse(listOf(session.copy(messages = emptyList())).containsCompletedMemoryTurn(turn))
        assertFalse(listOf(session.copy(messages = listOf(reply.copy(role = Role.USER))))
            .containsCompletedMemoryTurn(turn))
    }

    @Test
    fun storageGateRejectsDeletedSessionWrongRoleAndRemovedReply() = withMemoryDatabase { database ->
        database.createStatement().use { statement ->
            statement.execute(
                "INSERT INTO chat_sessions(id, assistantId, assistantSnapshotJson) " +
                    "VALUES ('session-1', 'role-1', '{\"assistantId\":\"role-1\"}')"
            )
            statement.execute("INSERT INTO chat_messages VALUES ('session-1', 'reply-1', 'ASSISTANT')")
        }
        assertTrue(canAppendTurn(database, "session-1", "role-1", "reply-1"))
        assertFalse(canAppendTurn(database, "session-1", "role-2", "reply-1"))

        database.createStatement().use {
            it.execute("UPDATE chat_sessions SET mixedAssistantHistory = 1 WHERE id = 'session-1'")
        }
        assertFalse(canAppendTurn(database, "session-1", "role-1", "reply-1"))
        database.createStatement().use {
            it.execute("UPDATE chat_sessions SET mixedAssistantHistory = 0 WHERE id = 'session-1'")
        }

        database.createStatement().use { it.execute("DELETE FROM chat_messages WHERE messageId = 'reply-1'") }
        assertFalse(canAppendTurn(database, "session-1", "role-1", "reply-1"))
        database.createStatement().use {
            it.execute("INSERT INTO chat_messages VALUES ('session-1', 'reply-1', 'ASSISTANT')")
            it.execute("DELETE FROM chat_sessions WHERE id = 'session-1'")
        }
        assertFalse(canAppendTurn(database, "session-1", "role-1", "reply-1"))
    }

    @Test
    fun committedSummaryDeletesOnlyConsumedPendingTurns() = withMemoryDatabase { database ->
        insertTurn(database, "turn-1", "role-1", "session-1", summarized = false)
        insertTurn(database, "turn-2", "role-1", "session-1", summarized = false)
        insertTurn(database, "turn-3", "role-2", "session-2", summarized = false)
        insertTurn(database, "legacy", "role-1", "session-1", summarized = true)

        database.prepareStatement(DELETE_CONSUMED_MEMORY_TURNS_SQL).use { statement ->
            statement.setString(1, "role-1")
            statement.setString(2, "turn-1")
            assertEquals(1, statement.executeUpdate())
        }
        assertEquals(listOf("legacy", "turn-2", "turn-3"), turnIds(database))
    }

    @Test
    fun deletingSessionLeavesOtherSessionsAndSummariesAlone() = withMemoryDatabase { database ->
        insertTurn(database, "deleted-pending", "role-1", "session-1", summarized = false)
        insertTurn(database, "deleted-summarized", "role-1", "session-1", summarized = true)
        insertTurn(database, "other-pending", "role-1", "session-2", summarized = false)

        database.prepareStatement(DELETE_MEMORY_TURNS_FOR_SESSIONS_SQL).use { statement ->
            statement.setString(1, "session-1")
            assertEquals(2, statement.executeUpdate())
        }
        assertEquals(listOf("other-pending"), turnIds(database))
    }

    @Test
    fun persistedTailRemovalPrunesOnlyUnbackedPendingTurns() = withMemoryDatabase { database ->
        database.createStatement().use { statement ->
            statement.execute(
                "INSERT INTO chat_sessions(id, assistantId, assistantSnapshotJson) " +
                    "VALUES ('session-1', 'role-1', '{\"assistantId\":\"role-1\"}')"
            )
            statement.execute("INSERT INTO chat_messages VALUES ('session-1', 'kept', 'ASSISTANT')")
            statement.execute("INSERT INTO chat_messages VALUES ('session-1', 'removed', 'ASSISTANT')")
        }
        insertTurn(database, "kept", "role-1", "session-1", summarized = false)
        insertTurn(database, "removed", "role-1", "session-1", summarized = false)
        insertTurn(database, "legacy", "role-1", "session-1", summarized = true)

        database.createStatement().use { statement ->
            statement.execute("DELETE FROM chat_messages WHERE messageId = 'removed'")
            assertEquals(1, statement.executeUpdate(DELETE_STALE_PENDING_MEMORY_TURNS_SQL))
        }
        assertEquals(listOf("kept", "legacy"), turnIds(database))
    }

    @Test
    fun mixedHistoryKeepsPendingEvidenceWithoutAdmittingItToMemory() = withMemoryDatabase { database ->
        database.createStatement().use { statement ->
            statement.execute(
                "INSERT INTO chat_sessions(id, assistantId, assistantSnapshotJson, mixedAssistantHistory) " +
                    "VALUES ('session-1', 'role-a', '{\"assistantId\":\"role-a\"}', 1)"
            )
            statement.execute("INSERT INTO chat_messages VALUES ('session-1', 'reply-1', 'ASSISTANT')")
        }
        insertTurn(database, "reply-1", "role-b", "session-1", summarized = false)

        database.createStatement().use {
            assertEquals(0, it.executeUpdate(DELETE_STALE_PENDING_MEMORY_TURNS_SQL))
        }
        assertEquals(listOf("reply-1"), turnIds(database))
        assertFalse(canAppendTurn(database, "session-1", "role-a", "reply-1"))
        assertFalse(canAppendTurn(database, "session-1", "role-b", "reply-1"))
    }

    private fun withMemoryDatabase(block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { database ->
            database.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE chat_sessions (id TEXT PRIMARY KEY, assistantId TEXT, " +
                        "assistantSnapshotJson TEXT, " +
                        "mixedAssistantHistory INTEGER NOT NULL DEFAULT 0)"
                )
                statement.execute(
                    "CREATE TABLE chat_messages (sessionId TEXT, messageId TEXT, role TEXT)"
                )
                statement.execute(
                    "CREATE TABLE assistant_memory_turns (" +
                        "id TEXT PRIMARY KEY, assistantId TEXT, sessionId TEXT, " +
                        "userText TEXT, assistantText TEXT, createdAt INTEGER, isSummarized INTEGER)"
                )
            }
            block(database)
        }
    }

    private fun canAppendTurn(
        database: Connection,
        sessionId: String,
        assistantId: String,
        replyId: String
    ): Boolean {
        val owned = database.prepareStatement(MEMORY_SESSION_OWNER_EXISTS_SQL).use { statement ->
            statement.setString(1, sessionId)
            statement.setString(2, assistantId)
            statement.executeQuery().use { rows -> rows.next() && rows.getInt(1) != 0 }
        }
        if (!owned) return false
        return database.prepareStatement(MEMORY_ASSISTANT_REPLY_EXISTS_SQL).use { statement ->
            statement.setString(1, sessionId)
            statement.setString(2, replyId)
            statement.executeQuery().use { rows -> rows.next() && rows.getInt(1) != 0 }
        }
    }

    private fun insertTurn(
        database: Connection,
        id: String,
        assistantId: String,
        sessionId: String,
        summarized: Boolean
    ) {
        database.prepareStatement(
            "INSERT INTO assistant_memory_turns VALUES (?, ?, ?, 'user text', 'assistant text', 1, ?)"
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, assistantId)
            statement.setString(3, sessionId)
            statement.setInt(4, if (summarized) 1 else 0)
            statement.executeUpdate()
        }
    }

    private fun turnIds(database: Connection): List<String> = buildList {
        database.createStatement().use { statement ->
            statement.executeQuery("SELECT id FROM assistant_memory_turns ORDER BY id").use { rows ->
                while (rows.next()) add(rows.getString(1))
            }
        }
    }
}
