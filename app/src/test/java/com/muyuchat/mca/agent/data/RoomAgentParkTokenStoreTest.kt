package com.muyuchat.mca.agent.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [RoomAgentParkTokenStore] over an in-memory [AgentParkTokenDao] fake.
 *
 * The transactional/CAS SQL is Room's; this test fixes the store contract the core bridge depends
 * on: a newer park replaces the row, a stale wake id never consumes it, and a matching consume
 * removes it exactly once.
 */
class RoomAgentParkTokenStoreTest {
    @Test
    fun `record then load returns the durable token and a newer park replaces it`() = runBlocking {
        val dao = FakeAgentParkTokenDao()
        val store = RoomAgentParkTokenStore(dao)
        assertNull(store.load("run-1"))

        val first = token(runId = "run-1", wakeId = "wake-1", createdAtMillis = 10L)
        store.record(first)
        assertEquals(first, store.load("run-1"))

        val second = first.copy(
            wakeId = "wake-2",
            checkpointRevision = 8L,
            kind = "USER",
            questionId = "question-1",
            createdAtMillis = 11L
        )
        store.record(second)
        assertEquals(second, store.load("run-1"))
        assertEquals(1, dao.size)
        assertEquals(2, dao.upserts)
    }

    @Test
    fun `consume with the matching wake removes and returns the row`() = runBlocking {
        val dao = FakeAgentParkTokenDao()
        val store = RoomAgentParkTokenStore(dao)
        val token = token(runId = "run-1", wakeId = "wake-1")
        store.record(token)

        assertEquals(token, store.consume("run-1", "wake-1"))
        assertNull(store.load("run-1"))

        assertNull("a second consume is a no-op", store.consume("run-1", "wake-1"))
    }

    @Test
    fun `consume with a stale wake leaves the durable row untouched`() = runBlocking {
        val dao = FakeAgentParkTokenDao()
        val store = RoomAgentParkTokenStore(dao)
        val token = token(runId = "run-1", wakeId = "wake-2")
        store.record(token)

        assertNull(store.consume("run-1", "wake-1"))
        assertEquals(token, store.load("run-1"))
        assertNull("an unknown run is never created", store.consume("run-unknown", "wake-2"))
    }

    @Test
    fun `delete reports whether a row was removed`() = runBlocking {
        val dao = FakeAgentParkTokenDao()
        val store = RoomAgentParkTokenStore(dao)
        store.record(token(runId = "run-1", wakeId = "wake-1"))

        assertTrue(store.delete("run-1"))
        assertFalse(store.delete("run-1"))
    }

    @Test
    fun `list returns every parked run oldest first`() = runBlocking {
        val dao = FakeAgentParkTokenDao()
        val store = RoomAgentParkTokenStore(dao)
        store.record(token(runId = "run-2", wakeId = "wake-2", createdAtMillis = 20L))
        store.record(token(runId = "run-1", wakeId = "wake-1", createdAtMillis = 10L))

        assertEquals(
            listOf("run-1", "run-2"),
            store.list().map { it.runId }
        )
    }

    private fun token(
        runId: String,
        wakeId: String,
        createdAtMillis: Long = 1L
    ): AgentParkTokenEntity = AgentParkTokenEntity(
        runId = runId,
        epoch = 3L,
        checkpointRevision = 5L,
        wakeId = wakeId,
        kind = "APPROVAL",
        approvalId = "approval-1",
        invocationId = "invocation-1",
        questionId = null,
        transcriptJson = """{"turns":[]}""",
        createdAtMillis = createdAtMillis
    )

    /** In-memory DAO; only [AgentParkTokenDao.consume]'s default body stays untouched. */
    private class FakeAgentParkTokenDao : AgentParkTokenDao {
        private val rows = linkedMapOf<String, AgentParkTokenEntity>()
        var upserts: Int = 0
            private set

        val size: Int get() = rows.size

        override suspend fun upsert(token: AgentParkTokenEntity) {
            rows[token.runId] = token
            upserts += 1
        }

        override suspend fun find(runId: String): AgentParkTokenEntity? = rows[runId]

        override suspend fun delete(runId: String): Int = if (rows.remove(runId) != null) 1 else 0

        override suspend fun listAll(): List<AgentParkTokenEntity> =
            rows.values.sortedBy { it.createdAtMillis }
    }
}
