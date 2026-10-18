package com.muyuchat.mca

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantMemoryAsyncSaveContractTest {
    @Test
    fun saveCompletesOnlyAfterPersistenceAndTheSingleStateCommit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val persisted = CompletableDeferred<Boolean>()
        var commits = 0
        var invalidations = 0
        try {
            val save = launchAssistantMemoryPersistence(
                scope = scope,
                upsertMemory = { persisted.await() },
                loadMemories = { listOf(memory()) },
                commitMemories = { records ->
                    assertEquals(listOf(memory()), records)
                    commits++
                },
                markLocalConversationContextInvalid = { invalidations++ },
                showStatus = { error("Successful save must not report an error: $it") }
            )

            assertFalse(save.isCompleted)
            assertEquals(0, commits)
            persisted.complete(true)

            assertTrue(save.await())
            assertEquals(1, commits)
            assertEquals(1, invalidations)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun rejectedPersistenceReturnsFalseWithoutStateCommit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val statuses = mutableListOf<String>()
        var loads = 0
        var commits = 0
        try {
            val save = launchAssistantMemoryPersistence(
                scope = scope,
                upsertMemory = { false },
                loadMemories = {
                    loads++
                    listOf(memory())
                },
                commitMemories = { commits++ },
                markLocalConversationContextInvalid = {},
                showStatus = statuses::add
            )

            assertFalse(save.await())
            assertEquals(0, loads)
            assertEquals(0, commits)
            assertTrue(statuses.single().contains("保存角色记忆失败"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun retryUsesTheSameRequestAgainAndSucceedsAfterFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var attempts = 0
        var commits = 0
        val statuses = mutableListOf<String>()
        try {
            fun save() = launchAssistantMemoryPersistence(
                scope = scope,
                upsertMemory = { ++attempts > 1 },
                loadMemories = { listOf(memory()) },
                commitMemories = { commits++ },
                markLocalConversationContextInvalid = {},
                showStatus = statuses::add
            )

            assertFalse(save().await())
            assertEquals(0, commits)
            assertEquals(1, statuses.size)

            assertTrue(save().await())
            assertEquals(2, attempts)
            assertEquals(1, commits)
            assertEquals(1, statuses.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellationPropagatesWithoutCommittingOrReportingAFalseFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Boolean>()
        var commits = 0
        val statuses = mutableListOf<String>()
        try {
            val save = launchAssistantMemoryPersistence(
                scope = scope,
                upsertMemory = {
                    started.complete(Unit)
                    never.await()
                },
                loadMemories = { listOf(memory()) },
                commitMemories = { commits++ },
                markLocalConversationContextInvalid = {},
                showStatus = statuses::add
            )
            started.await()

            save.cancelAndJoin()

            assertTrue(save.isCancelled)
            assertEquals(0, commits)
            assertTrue(statuses.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun exceptionReturnsFailureWithoutCommittingAndKeepsTheCauseDiagnosable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val statuses = mutableListOf<String>()
        var commits = 0
        try {
            val save = launchAssistantMemoryPersistence(
                scope = scope,
                upsertMemory = { throw IOException("disk full") },
                loadMemories = { listOf(memory()) },
                commitMemories = { commits++ },
                markLocalConversationContextInvalid = {},
                showStatus = statuses::add
            )

            assertFalse(save.await())
            assertEquals(0, commits)
            assertTrue(statuses.single().contains("disk full"))
        } finally {
            scope.cancel()
        }
    }

    private fun memory() = MemoryRecord(
        id = "memory-1",
        assistantId = "assistant-1",
        scope = "user_profile",
        content = "remember this",
        createdAt = 1L
    )
}
