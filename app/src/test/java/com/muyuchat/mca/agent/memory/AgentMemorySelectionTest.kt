package com.muyuchat.mca.agent.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selection and grant behavior (plan §7.2): one frozen selection per task, no widening, terminal
 * revocation, and no representable wildcard for a write approval.
 */
class AgentMemorySelectionTest {
    private val persistence = InMemoryAgentMemoryPersistence()
    private val service = AgentMemorySelectionService(persistence, clock = { AgentMemoryTestFixtures.NOW })

    @Test
    fun `freezing stores a canonical sorted selection with its hash`() = runBlocking {
        persistence.seedSpace("space-b")
        persistence.seedSpace("space-a")
        val outcome = service.freezeForTask("task-1", listOf("space-b", "space-a", "space-a"))
        val frozen = (outcome as AgentMemorySelectionOutcome.Frozen).selection
        assertEquals(listOf("space-a", "space-b"), frozen.spaceIds)
        assertEquals("[\"space-a\",\"space-b\"]", frozen.toJson())
        assertEquals(AgentMemoryText.sha256(frozen.toJson()), frozen.hash())

        val stored = requireNotNull(persistence.findSelection("task-1"))
        assertEquals(frozen.toJson(), stored.spaceIdsJson)
        assertEquals(frozen.hash(), stored.selectionHash)
        assertFalse(stored.revoked)
    }

    @Test
    fun `a repeated freeze with the same ids is an idempotent replay`() = runBlocking {
        persistence.seedSpace("space-a")
        val first = service.freezeForTask("task-1", listOf("space-a"))
        val second = service.freezeForTask("task-1", listOf("space-a"))
        assertEquals(
            (first as AgentMemorySelectionOutcome.Frozen).selection.spaceIds,
            (second as AgentMemorySelectionOutcome.Frozen).selection.spaceIds
        )
    }

    @Test
    fun `an idempotent replay does not rewrite the frozen row`() = runBlocking {
        persistence.seedSpace("space-a")
        service.freezeForTask("task-1", listOf("space-a"))
        val stored = requireNotNull(persistence.findSelection("task-1"))

        service.freezeForTask("task-1", listOf("space-a"))

        assertEquals(stored, persistence.findSelection("task-1"))
    }

    @Test
    fun `widening a frozen selection is rejected`() = runBlocking {
        persistence.seedSpace("space-a")
        persistence.seedSpace("space-b")
        service.freezeForTask("task-1", listOf("space-a"))
        val widened = service.freezeForTask("task-1", listOf("space-a", "space-b"))
        assertEquals(
            AgentMemoryErrorCodes.SELECTION_ALREADY_FROZEN,
            (widened as AgentMemorySelectionOutcome.Rejected).error.code
        )
        assertEquals(listOf("space-a"), service.grantForTask("task-1")?.spaceIds)
    }

    @Test
    fun `empty, oversized and unknown selections are rejected`() = runBlocking {
        assertEquals(
            AgentMemoryErrorCodes.SELECTION_EMPTY,
            rejected(service.freezeForTask("task-1", emptyList()))
        )
        assertEquals(
            AgentMemoryErrorCodes.SELECTION_EMPTY,
            rejected(service.freezeForTask("task-blank", setOf(" ", "")))
        )
        val tooMany = (1..AgentMemoryLimits.MAX_SELECTED_SPACES + 1).map { "space-$it" }
        assertEquals(
            AgentMemoryErrorCodes.SELECTION_TOO_LARGE,
            rejected(service.freezeForTask("task-1", tooMany))
        )
        assertEquals(
            AgentMemoryErrorCodes.SPACE_NOT_FOUND,
            rejected(service.freezeForTask("task-1", listOf("space-missing")))
        )
        assertNull(persistence.findSelection("task-1"))
    }

    @Test
    fun `no selection means no grant`() = runBlocking {
        assertNull(service.grantForTask("task-with-no-selection"))
    }

    @Test
    fun `revocation is terminal and turns the grant into a revoked grant`() = runBlocking {
        persistence.seedSpace("space-a")
        service.freezeForTask("task-1", listOf("space-a"))
        assertTrue(service.revokeForTask("task-1"))
        val grant = requireNotNull(service.grantForTask("task-1"))
        assertTrue(grant.revoked)
        assertFalse(grant.covers(listOf("space-a")))
        // A second revoke is not a second success.
        assertFalse(service.revokeForTask("task-1"))
        // The frozen row may not be re-created for the same task.
        assertEquals(
            AgentMemoryErrorCodes.SELECTION_REVOKED,
            rejected(service.freezeForTask("task-1", listOf("space-a")))
        )
    }

    @Test
    fun `two tasks never share a selection`() = runBlocking {
        persistence.seedSpace("space-a")
        persistence.seedSpace("space-b")
        service.freezeForTask("task-a", listOf("space-a"))
        service.freezeForTask("task-b", listOf("space-b"))
        val grantA = requireNotNull(service.grantForTask("task-a"))
        val grantB = requireNotNull(service.grantForTask("task-b"))
        assertTrue(grantA.covers(listOf("space-a")))
        assertFalse(grantA.covers(listOf("space-b")))
        assertTrue(grantB.covers(listOf("space-b")))
        assertFalse(grantB.covers(listOf("space-a")))
        assertEquals("task-a", grantA.taskId)
    }

    @Test
    fun `a tampered selection row is not readable`() = runBlocking {
        persistence.seedSpace("space-a")
        service.freezeForTask("task-1", listOf("space-a"))
        val stored = requireNotNull(persistence.findSelection("task-1"))
        persistence.overwriteSelectionForTest(stored.copy(spaceIdsJson = "[\"space-a\",\"space-b\"]"))
        assertNull(service.grantForTask("task-1"))
        assertEquals(
            AgentMemoryErrorCodes.STORAGE_FAILED,
            rejected(service.freezeForTask("task-1", listOf("space-a")))
        )
    }

    @Test
    fun `selection value object has no wildcard and enforces its bounds`() {
        assertThrows(IllegalArgumentException::class.java) { AgentMemorySelection.of(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            AgentMemorySelection.of((1..AgentMemoryLimits.MAX_SELECTED_SPACES + 1).map { "space-$it" })
        }
        assertThrows(IllegalArgumentException::class.java) { AgentMemorySelection.of(listOf("*")) }
        assertThrows(IllegalArgumentException::class.java) { AgentMemorySelection.of(listOf("space id")) }
        val selection = AgentMemorySelection.of(listOf("space-a"))
        assertTrue(selection.covers(listOf("space-a")))
        assertFalse(selection.covers(listOf("space-b")))
        assertFalse(selection.covers(emptyList()))
    }

    @Test
    fun `write approvals are scoped and have no wildcard`() {
        val items = AgentMemoryWriteApproval.items(
            spaceId = "space-a",
            principal = "local-user",
            reason = AgentMemoryWriteReason.USER_IMPORT,
            approvedAtMillis = AgentMemoryTestFixtures.NOW
        )
        assertTrue(items.authorizesItems("space-a"))
        assertFalse(items.authorizesItems("space-b"))
        assertFalse(items.authorizesSpaceAdmin())

        val admin = AgentMemoryTestFixtures.adminApproval()
        assertTrue(admin.authorizesSpaceAdmin())
        assertFalse(admin.authorizesItems("space-a"))

        assertThrows(IllegalArgumentException::class.java) {
            AgentMemoryWriteApproval.items(
                spaceId = "*",
                principal = "local-user",
                reason = AgentMemoryWriteReason.USER_IMPORT,
                approvedAtMillis = AgentMemoryTestFixtures.NOW
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentMemoryWriteApproval.items(
                spaceId = "space-a",
                principal = " ",
                reason = AgentMemoryWriteReason.USER_IMPORT,
                approvedAtMillis = AgentMemoryTestFixtures.NOW
            )
        }
        assertNotNull(admin)
    }

    private fun rejected(outcome: AgentMemorySelectionOutcome): String =
        (outcome as AgentMemorySelectionOutcome.Rejected).error.code
}
