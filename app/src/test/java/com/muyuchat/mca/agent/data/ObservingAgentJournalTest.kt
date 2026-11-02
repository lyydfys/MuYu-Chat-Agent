package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentCommitResult
import com.muyuchat.core.agent.contract.AgentEventPage
import com.muyuchat.core.agent.contract.AgentJournal
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentRunSnapshot
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentTransition
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.UpdateRunTransition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservingAgentJournalTest {
    private val run = AgentRun(
        runId = "run-1",
        taskId = "task-1",
        runOrdinal = 1L,
        state = AgentRunState.RUNNING,
        reason = AgentPauseReason.NONE,
        epoch = 3L,
        modelBinding = AgentModelBinding(
            providerId = "local",
            modelId = "mnn-qwen",
            runtime = "MNN_CPU",
            assetFingerprint = "asset",
            protocolMode = AgentProtocolMode.PROMPTED_V1,
            modelDisplayName = "Qwen"
        ),
        policy = AgentRunPolicy(),
        budget = AgentBudget(),
        spentBudget = AgentSpentBudget(),
        lastSequence = 0L,
        revision = 2L,
        createdAtMillis = 1_000L,
        updatedAtMillis = 2_000L
    )

    @Test
    fun reportsTheCommittedRunAfterTheDelegateSucceeds() = runBlocking {
        var observed: AgentRun? = null
        val journal = ObservingAgentJournal(FakeJournal()) { observed = it }
        journal.commit(
            UpdateRunTransition(
                commandId = "cmd-1",
                run = run,
                taskDisplay = com.muyuchat.core.agent.contract.AgentDisplayStatus.RUNNING,
                expectedRevision = 1L
            )
        )
        assertEquals(run, observed)
    }

    @Test
    fun failedCommitNeverObservesAState() = runBlocking {
        var observed: AgentRun? = null
        val journal = ObservingAgentJournal(FakeJournal(failure = IllegalStateException("boom"))) {
            observed = it
        }
        val result = runCatching {
            journal.commit(
                CreateRunTransition(
                    commandId = "cmd-2",
                    run = run,
                    taskRevision = 0L,
                    taskDisplay = com.muyuchat.core.agent.contract.AgentDisplayStatus.RUNNING
                )
            )
        }
        assertTrue(result.isFailure)
        assertNull(observed)
    }

    @Test
    fun observerFailureDoesNotTurnACommittedTransitionIntoAFailure() = runBlocking {
        val journal = ObservingAgentJournal(FakeJournal()) {
            throw IllegalStateException("platform bookkeeping failed")
        }
        val result = journal.commit(
            UpdateRunTransition(
                commandId = "cmd-3",
                run = run,
                taskDisplay = com.muyuchat.core.agent.contract.AgentDisplayStatus.RUNNING,
                expectedRevision = 1L
            )
        )
        assertEquals(9L, result.appliedRevision)
    }

    @Test
    fun nonRunTransitionsDoNotNotifyTheObserver() = runBlocking {
        var calls = 0
        val journal = ObservingAgentJournal(FakeJournal()) { calls += 1 }
        journal.commit(
            com.muyuchat.core.agent.contract.AppendEventsTransition(
                commandId = "cmd-4",
                runIdValue = "run-1",
                events = emptyList(),
                expectedRevision = null
            )
        )
        assertEquals(0, calls)
    }

    private class FakeJournal(
        private val failure: Throwable? = null
    ) : AgentJournal {
        override suspend fun readRun(runId: String): AgentRunSnapshot? = null

        override suspend fun listNonTerminalRuns(limit: Int): List<AgentRunSnapshot> = emptyList()

        override suspend fun readEvents(
            runId: String,
            afterSequence: Long,
            limit: Int
        ): AgentEventPage = AgentEventPage(events = emptyList())

        override suspend fun commit(command: AgentTransition): AgentCommitResult {
            failure?.let { throw it }
            return AgentCommitResult(appliedRevision = 9L)
        }
    }
}
