package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.core.agent.contract.AppendEventsTransition
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.contract.UpdateRunTransition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentJournalRulesTest {
    @Test
    fun `event sequences must be contiguous and unique`() {
        val first = AgentTestFixtures.event(sequence = 1L, eventId = "e1")
        val second = AgentTestFixtures.event(sequence = 2L, eventId = "e2")
        assertEquals(
            listOf(1L, 2L),
            AgentJournalRules.validateEventSequences(0L, listOf(first, second))
        )
        try {
            AgentJournalRules.validateEventSequences(0L, listOf(second))
            fail("gap must be rejected")
        } catch (error: AgentJournalException) {
            assertEquals(AgentDataErrorCodes.COMMAND_INVALID_STATE, error.code)
        }
        try {
            AgentJournalRules.validateEventSequences(
                0L,
                listOf(first, first.copy(sequence = 2L))
            )
            fail("duplicate eventId must be rejected")
        } catch (error: AgentJournalException) {
            assertEquals(AgentDataErrorCodes.COMMAND_INVALID_STATE, error.code)
        }
    }

    @Test
    fun `terminal runs may not leave their terminal state`() {
        AgentJournalRules.requireTransitionAllowed(AgentRunState.RUNNING, AgentRunState.SUCCEEDED)
        try {
            AgentJournalRules.requireTransitionAllowed(AgentRunState.CANCELLED, AgentRunState.RUNNING)
            fail("terminal transition must be rejected")
        } catch (error: AgentJournalException) {
            assertEquals(AgentDataErrorCodes.COMMAND_INVALID_STATE, error.code)
        }
    }

    @Test
    fun `idempotency lookup distinguishes replay conflict and execute`() {
        assertEquals(
            AgentJournalRules.CommandLookup.EXECUTE,
            AgentJournalRules.commandLookup(null, "hash")
        )
        assertEquals(
            AgentJournalRules.CommandLookup.REPLAY,
            AgentJournalRules.commandLookup("hash", "hash")
        )
        assertEquals(
            AgentJournalRules.CommandLookup.CONFLICT,
            AgentJournalRules.commandLookup("hash-a", "hash-b")
        )
    }

    @Test
    fun `same command content replays even after the revision advanced`() = runBlocking {
        val journal = InMemoryAgentJournal()
        val create = CreateTaskTransition("command-create", AgentTestFixtures.task())
        val created = journal.commit(create)
        val replay = journal.commit(create)
        assertTrue(replay.idempotentReplay)
        assertEquals(created.appliedRevision, replay.appliedRevision)
        val run = AgentTestFixtures.run(state = AgentRunState.QUEUED, revision = 0L)
        journal.commit(
            CreateRunTransition(
                commandId = "command-run",
                run = run,
                taskRevision = 2L,
                taskDisplay = AgentDisplayStatus.QUEUED
            )
        )
        val update = UpdateRunTransition(
            commandId = "command-update",
            run = run.copy(state = AgentRunState.RUNNING, revision = 0L),
            taskDisplay = AgentDisplayStatus.RUNNING,
            expectedRevision = 0L
        )
        val applied = journal.commit(update)
        val replayed = journal.commit(update)
        assertTrue(replayed.idempotentReplay)
        assertEquals(applied.appliedRevision, replayed.appliedRevision)
    }

    @Test
    fun `same commandId with different content is an idempotency conflict`() = runBlocking {
        val journal = InMemoryAgentJournal()
        journal.commit(CreateTaskTransition("command-1", AgentTestFixtures.task()))
        try {
            journal.commit(
                CreateTaskTransition(
                    "command-1",
                    AgentTestFixtures.task().copy(goal = "different goal")
                )
            )
            fail("expected idempotency conflict")
        } catch (error: AgentJournalException) {
            assertEquals(AgentDataErrorCodes.COMMAND_IDEMPOTENCY_CONFLICT, error.code)
        }
    }

    @Test
    fun `stale revision is rejected without a partial state change`() = runBlocking {
        val journal = InMemoryAgentJournal()
        journal.commit(CreateTaskTransition("command-create", AgentTestFixtures.task()))
        val run = AgentTestFixtures.run(state = AgentRunState.QUEUED, revision = 0L)
        journal.commit(
            CreateRunTransition(
                "command-run",
                run,
                taskRevision = 2L,
                taskDisplay = AgentDisplayStatus.QUEUED
            )
        )
        try {
            journal.commit(
                UpdateRunTransition(
                    "command-stale",
                    run.copy(state = AgentRunState.RUNNING),
                    AgentDisplayStatus.RUNNING,
                    expectedRevision = 5L
                )
            )
            fail("expected revision conflict")
        } catch (error: AgentJournalException) {
            assertEquals(AgentDataErrorCodes.COMMAND_REVISION_CONFLICT, error.code)
        }
        val current = journal.currentRun(run.runId)
        assertNotNull(current)
        assertEquals(AgentRunState.QUEUED, current!!.state)
        assertEquals(2, journal.commandCount())
    }

    @Test
    fun `event append advances lastSequence and paging is bounded`() = runBlocking {
        val journal = InMemoryAgentJournal()
        journal.commit(CreateTaskTransition("command-create", AgentTestFixtures.task()))
        val run = AgentTestFixtures.run(state = AgentRunState.QUEUED, revision = 0L)
        journal.commit(
            CreateRunTransition(
                "command-run",
                run,
                taskRevision = 2L,
                taskDisplay = AgentDisplayStatus.QUEUED
            )
        )
        val events = (1L..3L).map { sequence ->
            AgentTestFixtures.event(sequence = sequence, eventId = "event-$sequence")
        }
        val applied = journal.commit(
            AppendEventsTransition(
                commandId = "command-events",
                runIdValue = run.runId,
                events = events,
                expectedRevision = 0L
            )
        )
        assertEquals(listOf(1L, 2L, 3L), applied.eventSequences)
        assertEquals(3L, journal.currentRun(run.runId)!!.lastSequence)
        val page = journal.readEvents(run.runId, afterSequence = 1L, limit = 1)
        assertEquals(listOf(2L), page.events.map { it.sequence })
        assertEquals(2L, page.nextSequence)
        val empty = journal.readEvents(run.runId, afterSequence = 3L, limit = 5)
        assertTrue(empty.events.isEmpty())
        assertNull(empty.nextSequence)
    }

    @Test
    fun `recovery lists every non-terminal state but not archived tasks`() = runBlocking {
        val journal = InMemoryAgentJournal()
        val active = AgentTestFixtures.task(taskId = "task-active")
        journal.seed(
            active,
            AgentTestFixtures.run(
                runId = "run-stopping",
                taskId = active.taskId,
                state = AgentRunState.STOPPING
            )
        )
        journal.seed(
            active,
            AgentTestFixtures.run(
                runId = "run-pausing",
                taskId = active.taskId,
                state = AgentRunState.PAUSING
            )
        )
        journal.seed(
            active,
            AgentTestFixtures.run(
                runId = "run-interrupted",
                taskId = active.taskId,
                state = AgentRunState.INTERRUPTED
            )
        )
        journal.seed(
            active,
            AgentTestFixtures.run(
                runId = "run-succeeded",
                taskId = active.taskId,
                state = AgentRunState.SUCCEEDED
            )
        )
        val archived = AgentTestFixtures.task(
            taskId = "task-archived",
            lifecycle = AgentTaskLifecycle.IMPORTED_ARCHIVED
        )
        journal.seed(
            archived,
            AgentTestFixtures.run(
                runId = "run-archived",
                taskId = archived.taskId,
                state = AgentRunState.PAUSED,
                reason = AgentPauseReason.INTERRUPTED
            )
        )
        val list = journal.listNonTerminalRuns(50)
        val ids = list.map { it.run.runId }.toSet()
        assertTrue(ids.contains("run-stopping"))
        assertTrue(ids.contains("run-pausing"))
        assertTrue(ids.contains("run-interrupted"))
        assertFalse(ids.contains("run-succeeded"))
        assertFalse(ids.contains("run-archived"))
        assertTrue(list.all { it.task.lifecycle == AgentTaskLifecycle.ACTIVE })
    }

    @Test
    fun `one hundred concurrent commands with the same revision have exactly one winner`() =
        runBlocking {
            val journal = InMemoryAgentJournal()
            journal.commit(CreateTaskTransition("command-create", AgentTestFixtures.task()))
            val run = AgentTestFixtures.run(state = AgentRunState.QUEUED, revision = 0L)
            journal.commit(
                CreateRunTransition(
                    "command-run",
                    run,
                    taskRevision = 2L,
                    taskDisplay = AgentDisplayStatus.QUEUED
                )
            )
            val outcomes = coroutineScope {
                (1..100).map { index ->
                    async(Dispatchers.Default) {
                        try {
                            journal.commit(
                                UpdateRunTransition(
                                    commandId = "command-$index",
                                    run = run.copy(
                                        state = AgentRunState.RUNNING,
                                        revision = 0L
                                    ),
                                    taskDisplay = AgentDisplayStatus.RUNNING,
                                    expectedRevision = 0L
                                )
                            )
                            true
                        } catch (_: AgentJournalException) {
                            false
                        }
                    }
                }.awaitAll()
            }
            assertEquals(1, outcomes.count { it })
            assertEquals(AgentRunState.RUNNING, journal.currentRun(run.runId)!!.state)
        }
}
