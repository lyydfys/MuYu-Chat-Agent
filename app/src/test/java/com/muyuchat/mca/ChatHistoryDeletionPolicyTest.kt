package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHistoryDeletionPolicyTest {
    @Test
    fun missingSessionsAreNeverDeletedWithoutAnExplicitDelete() {
        val persisted = setOf("a", "b", "c")

        assertEquals(
            emptySet<String>(),
            sessionsEligibleForDeletion(persisted, requestedIds = setOf("a"), explicitlyDeletedSessionIds = emptySet())
        )
        assertEquals(
            emptySet<String>(),
            sessionsEligibleForDeletion(persisted, requestedIds = emptySet(), explicitlyDeletedSessionIds = emptySet())
        )
    }

    @Test
    fun explicitDeleteOnlyRemovesPersistedSessionsThatAreStillMissingFromTheSnapshot() {
        val persisted = setOf("a", "b", "c")

        assertEquals(
            setOf("b"),
            sessionsEligibleForDeletion(
                persisted,
                requestedIds = setOf("a", "c"),
                explicitlyDeletedSessionIds = setOf("b", "unknown")
            )
        )
        assertEquals(
            emptySet<String>(),
            sessionsEligibleForDeletion(
                persisted,
                requestedIds = setOf("a", "b", "c"),
                explicitlyDeletedSessionIds = setOf("b")
            )
        )
    }

    @Test
    fun persistenceBoundsCannotTurnIntoAnImplicitDelete() {
        val oversized = ChatSessionRecord(
            id = "oversized",
            title = "x".repeat(8_192),
            messages = emptyList()
        )
        val bounded = ChatHistoryPersistenceBounds.bound(
            listOf(oversized),
            ChatHistoryPersistenceLimits(maxSerializedBytes = 512L)
        )

        assertEquals(emptyList<ChatSessionRecord>(), bounded)
        assertEquals(
            emptySet<String>(),
            sessionsEligibleForDeletion(
                persistedIds = setOf("oversized"),
                requestedIds = bounded.mapTo(hashSetOf()) { it.id },
                explicitlyDeletedSessionIds = emptySet()
            )
        )
    }
}
