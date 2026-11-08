package com.muyuchat.mca.agent.projection

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The task -> conversation binding that makes an Agent turn summary addressable.
 *
 * Two promises matter beyond the plain map behaviour:
 *  - a crash during a write must leave a readable document (never a truncated one), because the
 *    binding is what stops a summary from being attached to the wrong conversation;
 *  - a corrupt document must degrade to "no binding", never throw into the run projection.
 */
class AgentTaskChatBindingStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(
        directory: File = folder.root,
        maxBindings: Int = FileAgentTaskChatBindingStore.MAX_BINDINGS
    ) = FileAgentTaskChatBindingStore(directory, maxBindings)

    @Test
    fun `bind then chatFor returns the conversation`() {
        val store = store()
        val binding = store.bind("task-1", "session-a", 1_000L)

        assertEquals("task-1", binding.taskId)
        assertEquals("session-a", binding.chatSessionId)
        assertEquals(1_000L, binding.createdAt)
        assertEquals("session-a", store.chatFor("task-1"))
        assertNull("an unknown task has no conversation", store.chatFor("task-2"))
    }

    @Test
    fun `a binding survives a new store over the same directory`() {
        store().bind("task-1", "session-a", 1_000L)

        val reopened = store()

        assertEquals("session-a", reopened.chatFor("task-1"))
        assertEquals(mapOf("task-1" to "session-a"), reopened.all())
    }

    @Test
    fun `rebinding to the same conversation keeps the original createdAt`() {
        val store = store()
        store.bind("task-1", "session-a", 1_000L)
        val again = store.bind("task-1", "session-a", 9_000L)

        assertEquals("a retried create is not a new association", 1_000L, again.createdAt)
        assertEquals("session-a", store.chatFor("task-1"))
    }

    @Test
    fun `rebinding to another conversation replaces it`() {
        val store = store()
        store.bind("task-1", "session-a", 1_000L)
        store.bind("task-1", "session-b", 2_000L)

        assertEquals("session-b", store.chatFor("task-1"))
        assertEquals("session-b", store().chatFor("task-1"))
    }

    @Test
    fun `unbind removes the association and reports whether anything was removed`() {
        val store = store()
        store.bind("task-1", "session-a", 1_000L)

        assertTrue(store.unbind("task-1"))
        assertFalse("already gone", store.unbind("task-1"))
        assertNull(store.chatFor("task-1"))
        assertNull("the removal is durable", store().chatFor("task-1"))
    }

    @Test
    fun `all returns every live association ordered by creation`() {
        val store = store()
        store.bind("task-b", "session-2", 2_000L)
        store.bind("task-a", "session-1", 1_000L)

        assertEquals(
            listOf("task-a" to "session-1", "task-b" to "session-2"),
            store.all().entries.map { it.key to it.value }
        )
    }

    @Test
    fun `the cap evicts the oldest association, never the newest`() {
        val store = store(maxBindings = 3)
        store.bind("task-1", "session-1", 1_000L)
        store.bind("task-2", "session-2", 2_000L)
        store.bind("task-3", "session-3", 3_000L)
        store.bind("task-4", "session-4", 4_000L)

        assertEquals(3, store.all().size)
        assertNull("the oldest row is the one dropped", store.chatFor("task-1"))
        assertEquals("session-4", store.chatFor("task-4"))
        assertEquals("the eviction is durable", 3, store().all().size)
    }

    // ---------------------------------------------------------------- atomicity / corruption

    @Test
    fun `a leftover temp file is ignored and the live document still wins`() {
        val store = store()
        store.bind("task-1", "session-a", 1_000L)
        // A crash between write and rename leaves a temp file whose content is deliberately
        // different; readers must never adopt it.
        File(folder.root, "${FileAgentTaskChatBindingStore.FILE_NAME}.tmp")
            .writeText("""{"version":1,"bindings":[{"taskId":"ghost","chatSessionId":"x","createdAt":1}]}""")

        val reopened = store()

        assertEquals("session-a", reopened.chatFor("task-1"))
        assertNull("the temp file is not data", reopened.chatFor("ghost"))
    }

    @Test
    fun `a truncated document returns an empty table instead of throwing`() {
        val store = store()
        store.bind("task-1", "session-a", 1_000L)
        File(folder.root, FileAgentTaskChatBindingStore.FILE_NAME)
            .writeText("""{"version":1,"bindings":[{"taskId":"task-1","chat""")

        val reopened = store()

        assertTrue("corruption must not throw", reopened.all().isEmpty())
        assertNull(reopened.chatFor("task-1"))
    }

    @Test
    fun `an oversized or wrong version document is rejected safely`() {
        val oversizedRoot = folder.newFolder("oversized")
        store(oversizedRoot).bind("task-1", "session-a", 1_000L)
        File(oversizedRoot, FileAgentTaskChatBindingStore.FILE_NAME)
            .writeBytes(ByteArray((FileAgentTaskChatBindingStore.MAX_BYTES + 1L).toInt()) { ' '.code.toByte() })

        assertTrue("an oversized document is not parsed", store(oversizedRoot).all().isEmpty())

        val wrongVersion = folder.newFolder("v2")
        File(wrongVersion, FileAgentTaskChatBindingStore.FILE_NAME)
            .writeText("""{"version":99,"bindings":[{"taskId":"task-1","chatSessionId":"session-a","createdAt":1}]}""")
        assertTrue("an unknown schema is not guessed at", store(wrongVersion).all().isEmpty())
    }

    @Test
    fun `a write failure keeps the in-memory binding usable`() {
        val store = store()
        store.bind("task-1", "session-a", 1_000L)
        // Replace the live file with a directory so the atomic move can never succeed.
        val live = File(folder.root, FileAgentTaskChatBindingStore.FILE_NAME)
        live.delete()
        live.mkdirs()

        store.bind("task-2", "session-b", 2_000L)

        assertEquals("session-a", store.chatFor("task-1"))
        assertEquals(
            "a failed persist degrades to memory, it does not lose the mapping",
            "session-b",
            store.chatFor("task-2")
        )
    }

    // ---------------------------------------------------------------- concurrency

    @Test
    fun `concurrent binds from many threads all land`() {
        val store = store()
        val threads = 8
        val perThread = 25
        val pool = Executors.newFixedThreadPool(threads)
        val gate = CountDownLatch(1)
        try {
            val futures = (0 until threads).map { thread ->
                pool.submit {
                    gate.await()
                    repeat(perThread) { index ->
                        val ordinal = thread * perThread + index
                        store.bind("task-$ordinal", "session-$ordinal", ordinal.toLong())
                    }
                }
            }
            gate.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(threads * perThread, store.all().size)
        // The durable copy must be parseable and complete after the concurrent writes.
        val reopened = store()
        assertEquals(threads * perThread, reopened.all().size)
        repeat(threads * perThread) { ordinal ->
            assertEquals("session-$ordinal", reopened.chatFor("task-$ordinal"))
        }
    }

    @Test
    fun `a binding rejects blank ids`() {
        listOf("" to "session-a", "task-1" to "").forEach { (taskId, sessionId) ->
            val failure = runCatching { AgentTaskChatBinding(taskId, sessionId, 1_000L) }.exceptionOrNull()
            assertTrue("blank ids must be rejected", failure is IllegalArgumentException)
        }
    }

    @Test
    fun `the in memory store follows the same rules`() {
        val store = InMemoryAgentTaskChatBindingStore(maxBindings = 2)
        store.bind("task-1", "session-1", 1_000L)
        store.bind("task-2", "session-2", 2_000L)
        store.bind("task-3", "session-3", 3_000L)

        assertEquals(2, store.all().size)
        assertNull(store.chatFor("task-1"))
        assertTrue(store.unbind("task-3"))
        assertEquals(mapOf("task-2" to "session-2"), store.all())
    }
}
