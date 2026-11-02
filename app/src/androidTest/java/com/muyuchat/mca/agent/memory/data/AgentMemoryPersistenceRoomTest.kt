package com.muyuchat.mca.agent.memory.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muyuchat.mca.McaRoomDatabase
import com.muyuchat.mca.agent.memory.AgentMemoryChunkDraft
import com.muyuchat.mca.agent.memory.AgentMemoryItemRecord
import com.muyuchat.mca.agent.memory.AgentMemorySourceKind
import com.muyuchat.mca.agent.memory.AgentMemorySpaceRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B06 / MEM-02: deleting an item must never touch another space's rows.
 *
 * The previous implementation deleted chunks by `memoryId` first and only then deleted the item row
 * with `(spaceId, memoryId)`. Calling it with space A and an item id from space B therefore deleted B's
 * chunks while leaving B's item row in place — silent data loss. These cases run the real Room
 * transaction with two populated spaces.
 */
@RunWith(AndroidJUnit4::class)
class AgentMemoryPersistenceRoomTest {
    private lateinit var database: McaRoomDatabase
    private lateinit var persistence: RoomAgentMemoryPersistence

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, McaRoomDatabase::class.java).build()
        persistence = RoomAgentMemoryPersistence(database = database, dao = database.agentMemoryDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun aMismatchedSpaceAndItemIdChangesNothing() = runBlocking {
        seedSpace("space-a")
        seedSpace("space-b")
        seedItem("space-a", "memory-a")
        seedItem("space-b", "memory-b")

        val removed = persistence.deleteItemWithChunks(spaceId = "space-a", memoryId = "memory-b")

        assertEquals(0, removed)
        assertNotNull(persistence.findItem("memory-a"))
        assertNotNull(persistence.findItem("memory-b"))
        assertEquals(1, persistence.liveChunkCount(listOf("space-a")))
        assertEquals(1, persistence.liveChunkCount(listOf("space-b")))
    }

    @Test
    fun theOwningSpaceDeleteRemovesTheItemAndItsChunksOnly() = runBlocking {
        seedSpace("space-a")
        seedSpace("space-b")
        seedItem("space-a", "memory-a")
        seedItem("space-b", "memory-b")

        val removed = persistence.deleteItemWithChunks(spaceId = "space-b", memoryId = "memory-b")

        assertEquals(1, removed)
        assertNull(persistence.findItem("memory-b"))
        assertEquals(0, persistence.liveChunkCount(listOf("space-b")))
        assertNotNull(persistence.findItem("memory-a"))
        assertEquals(1, persistence.liveChunkCount(listOf("space-a")))
    }

    @Test
    fun anUnknownItemIdHasNoSideEffect() = runBlocking {
        seedSpace("space-a")
        seedItem("space-a", "memory-a")

        val removed = persistence.deleteItemWithChunks(spaceId = "space-a", memoryId = "memory-missing")

        assertEquals(0, removed)
        assertNotNull(persistence.findItem("memory-a"))
        assertEquals(1, persistence.liveChunkCount(listOf("space-a")))
    }

    private suspend fun seedSpace(spaceId: String) {
        persistence.insertSpace(
            AgentMemorySpaceRecord(
                spaceId = spaceId,
                displayName = "Space $spaceId",
                createdAt = NOW,
                updatedAt = NOW,
                revision = 1L
            )
        )
    }

    private suspend fun seedItem(spaceId: String, memoryId: String) {
        persistence.insertItemWithChunks(
            item = AgentMemoryItemRecord(
                memoryId = memoryId,
                spaceId = spaceId,
                title = "title $memoryId",
                text = "text $memoryId",
                contentHash = hashOf("item:$memoryId"),
                sourceKind = AgentMemorySourceKind.USER_TEXT,
                sourceRef = null,
                tokenCount = 1,
                chunkCount = 1,
                createdAt = NOW,
                updatedAt = NOW,
                revoked = false,
                revokedAt = null
            ),
            chunks = listOf(
                AgentMemoryChunkDraft(
                    chunkId = "$memoryId:0",
                    memoryId = memoryId,
                    spaceId = spaceId,
                    position = 0,
                    text = "text $memoryId",
                    contentHash = hashOf("chunk:$memoryId"),
                    estimatedTokens = 1,
                    createdAt = NOW
                )
            )
        )
    }

    /** Deterministic 64-hex content hash; the storage only requires the shape and uniqueness. */
    private fun hashOf(value: String): String =
        value.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }.padEnd(64, '0').take(64)

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
