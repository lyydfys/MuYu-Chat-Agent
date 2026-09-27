package com.muyuchat.mca

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantMemoryRoomTest {
    @Test
    fun summaryCommitIsIdempotentAndPreservesOtherRolesAndManualMemory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, McaRoomDatabase::class.java).build()
        try {
            val dao = database.chatSessionDao()
            val manual = MemoryRecord(
                id = "manual-a", assistantId = "role-a", scope = "user_profile",
                content = "User likes tea", source = "manual", createdAt = 1L
            )
            val oldAutomatic = MemoryRecord(
                id = "old-auto-a", assistantId = "role-a", scope = "role_progress",
                content = "Earlier progress", source = "automatic", createdAt = 2L
            )
            val otherRole = MemoryRecord(
                id = "auto-b", assistantId = "role-b", scope = "role_progress",
                content = "Other role progress", source = "automatic", createdAt = 3L
            )
            dao.insertMemories(listOf(manual, oldAutomatic, otherRole).map {
                MemoryEntity(it.id, it.assistantId, it.scope, it.content, it.source, it.createdAt)
            })
            val turn = AssistantMemoryTurnEntity(
                id = "reply-a-1", assistantId = "role-a", sessionId = "session-a",
                userText = "I reached chapter two", assistantText = "We finished chapter two", createdAt = 4L
            )
            assertTrue(dao.insertMemoryTurn(turn) > 0L)
            assertEquals(-1L, dao.insertMemoryTurn(turn))
            assertFalse(dao.commitMemorySummary("role-b", listOf(turn.id),
                MemoryRecord(id = "wrong", assistantId = "role-b", scope = "role_progress",
                    content = "Wrong role", source = "automatic")))

            val summary = MemoryRecord(
                id = "new-auto-a", assistantId = "role-a", scope = "role_progress",
                content = "Chapter two completed", source = "automatic", createdAt = 5L
            )
            assertTrue(dao.commitMemorySummary("role-a", listOf(turn.id), summary))
            assertFalse(dao.commitMemorySummary("role-a", listOf(turn.id), summary))
            assertEquals(emptyList<AssistantMemoryTurnEntity>(), dao.pendingMemoryTurns("role-a", 100))
            database.openHelper.readableDatabase.query(
                "SELECT userText, assistantText FROM assistant_memory_turns WHERE id = ?",
                arrayOf(turn.id)
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("", cursor.getString(0))
                assertEquals("", cursor.getString(1))
            }
            assertEquals(-1L, dao.insertMemoryTurn(turn))
            assertEquals(setOf("manual-a", "new-auto-a"), dao.memories("role-a").map { it.id }.toSet())
            assertEquals(listOf("auto-b"), dao.memories("role-b").map { it.id })
            assertFalse(dao.upsertMemoryRecord(
                manual.copy(content = "Attempted overwrite", source = "automatic")
            ))
            assertEquals("User likes tea", dao.memoryById(manual.id)?.content)
            assertFalse(dao.replaceMemoriesForAssistant("role-a", listOf(otherRole)))
            assertEquals("Other role progress", dao.memoryById(otherRole.id)?.content)
            dao.deleteAssistantMemoryData("role-a")
            assertEquals(emptyList<MemoryEntity>(), dao.memories("role-a"))
            assertEquals(listOf("auto-b"), dao.memories("role-b").map { it.id })
        } finally {
            database.close()
        }
    }
}
