package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AnswerAgentQuestion
import com.muyuchat.core.agent.contract.CancelAgentRun
import com.muyuchat.core.agent.runtime.testing.AgentOwnerHarness
import com.muyuchat.core.agent.runtime.testing.AgentRuntimeFixtures
import com.muyuchat.core.agent.runtime.testing.ScriptedRunExecutor
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4 contract: a WAITING_USER park writes one durable token, the wake is idempotent, and a
 * restarted owner (in-memory transcript lost) resumes the same run from the persisted transcript.
 */
class AgentParkTokenWakeTest {

    @Test
    fun aWaitingUserParkPersistsTheExactResumeToken() = runTest {
        val executor = ScriptedRunExecutor()
        executor.enqueue(AgentRuntimeFixtures.waitingUser(AgentRuntimeFixtures.run()))
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        runCurrent()
        val runId = started.runId!!
        val waiting = harness.run(runId)!!
        assertEquals(AgentRunState.WAITING_USER, waiting.state)
        val question = requireNotNull(harness.journal.readRun(runId)!!.activeQuestion)

        val token = requireNotNull(harness.parkTokens.load(runId))
        assertEquals(AgentParkKind.USER, token.kind)
        assertEquals(waiting.epoch, token.epoch)
        assertEquals(waiting.revision, token.checkpointRevision)
        assertEquals(agentParkWakeId(runId, waiting.revision, AgentParkKind.USER), token.wakeId)
        assertEquals(question.questionId, token.questionId)
        assertNull(token.approvalId)
        assertNull(token.invocationId)
        assertEquals(
            "the token must carry the exact transcript the owner parked with",
            harness.owner.suspended[runId],
            token.transcript()
        )
    }

    @Test
    fun aWakeOnAStillParkedRunIsSideEffectFreeAndRepeatable() = runTest {
        val executor = ScriptedRunExecutor()
        executor.enqueue(AgentRuntimeFixtures.waitingUser(AgentRuntimeFixtures.run()))
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        runCurrent()
        val runId = started.runId!!
        val token = requireNotNull(harness.parkTokens.load(runId))

        assertTrue(harness.owner.wakeParked(runId, token.wakeId) is AgentParkWakeResult.StillParked)
        assertTrue(harness.owner.wakeParked(runId, token.wakeId) is AgentParkWakeResult.StillParked)
        assertNotNull("a parked wake must not consume the token", harness.parkTokens.load(runId))
        assertEquals("one model attempt only", 1, executor.executeCount)

        val foreign = harness.owner.wakeParked(runId, "wake-foreign")
        assertTrue(foreign is AgentParkWakeResult.AlreadyConsumed)
        assertNotNull("a foreign wake id must not delete the token", harness.parkTokens.load(runId))
    }

    @Test
    fun aRestartedOwnerResumesTheAnswerFromTheDurableToken() = runTest {
        val executor = ScriptedRunExecutor()
        executor.enqueue(AgentRuntimeFixtures.waitingUser(AgentRuntimeFixtures.run()))
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        runCurrent()
        val runId = started.runId!!
        val waiting = harness.run(runId)!!
        val question = requireNotNull(harness.journal.readRun(runId)!!.activeQuestion)
        val parkedTranscript = requireNotNull(harness.parkTokens.load(runId)).transcript()

        // Simulate process death: the owner's in-memory park material is gone, the token is not.
        harness.owner.suspended.clear()
        harness.owner.pendingApprovals.clear()
        harness.owner.pendingResumes.clear()

        val accepted = harness.owner.answerQuestion(
            AnswerAgentQuestion(
                commandId = harness.ids.nextId("cmd"),
                runId = runId,
                expectedRunRevision = waiting.revision,
                questionId = question.questionId,
                answer = "开发者"
            )
        )
        assertTrue("the durable token must allow the answer to resume", accepted is AgentCommandResult.Accepted)
        runCurrent()

        assertEquals(AgentRunState.SUCCEEDED, harness.run(runId)!!.state)
        assertEquals(2, executor.executeCount)
        val resume = requireNotNull(executor.resumes[1])
        assertEquals(question.questionId, resume.questionId)
        assertEquals("开发者", resume.userAnswer)
        assertEquals(
            "the resumed loop must receive the persisted transcript, not the lost in-memory one",
            parkedTranscript,
            resume.transcript
        )
        assertNull("a committed resume retires its park token", harness.parkTokens.load(runId))
    }

    @Test
    fun aWakeResumesTheDurableAnswerWhenTheProcessDiedBeforeLaunch() = runTest {
        val executor = ScriptedRunExecutor()
        executor.enqueue(AgentRuntimeFixtures.waitingUser(AgentRuntimeFixtures.run()))
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        runCurrent()
        val runId = started.runId!!
        val waiting = harness.run(runId)!!
        val question = requireNotNull(harness.journal.readRun(runId)!!.activeQuestion)
        val token = requireNotNull(harness.parkTokens.load(runId))

        // Crash window: the answer transition committed RUNNING, but the in-memory resume/launch
        // never happened (the process died between the commit and the queue promotion).
        val answered = waiting.copy(
            state = AgentRunState.RUNNING,
            revision = waiting.revision + 1,
            updatedAtMillis = harness.clock.nowMillis()
        )
        harness.journal.commit(
            com.muyuchat.core.agent.contract.AnswerQuestionTransition(
                commandId = harness.ids.nextId("cmd"),
                questionId = question.questionId,
                answer = "开发者",
                answeredAtMillis = harness.clock.nowMillis(),
                nextRun = answered,
                taskDisplay = com.muyuchat.core.agent.contract.AgentDisplayStatus.RUNNING,
                expectedRevision = waiting.revision
            )
        )
        harness.owner.suspended.clear()
        harness.owner.pendingResumes.clear()
        assertEquals(AgentRunState.RUNNING, harness.run(runId)!!.state)

        val wake = harness.owner.wakeParked(runId, token.wakeId)
        assertTrue("the durable answer must be requeued by the wake", wake is AgentParkWakeResult.Resumed)
        runCurrent()

        assertEquals(AgentRunState.SUCCEEDED, harness.run(runId)!!.state)
        assertEquals(2, executor.executeCount)
        val resume = requireNotNull(executor.resumes[1])
        assertEquals(question.questionId, resume.questionId)
        assertEquals("开发者", resume.userAnswer)
        assertEquals(token.transcript(), resume.transcript)
        assertNull("a resumed wake consumes its token", harness.parkTokens.load(runId))
        assertTrue(harness.owner.wakeParked(runId, token.wakeId) is AgentParkWakeResult.AlreadyConsumed)
    }

    @Test
    fun aRunningTokenWithoutUsableResumeFailsClosedToPaused() = runTest {
        val executor = ScriptedRunExecutor()
        executor.enqueue(AgentRuntimeFixtures.waitingUser(AgentRuntimeFixtures.run()))
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        runCurrent()
        val runId = started.runId!!
        val waiting = harness.run(runId)!!
        val token = requireNotNull(harness.parkTokens.load(runId))

        // RUNNING without a durable answer (the question is still OPEN).
        val running = waiting.copy(
            state = AgentRunState.RUNNING,
            revision = waiting.revision + 1,
            updatedAtMillis = harness.clock.nowMillis()
        )
        harness.journal.commit(
            com.muyuchat.core.agent.contract.UpdateRunTransition(
                commandId = harness.ids.nextId("cmd"),
                run = running,
                taskDisplay = com.muyuchat.core.agent.contract.AgentDisplayStatus.RUNNING,
                expectedRevision = waiting.revision
            )
        )

        val wake = harness.owner.wakeParked(runId, token.wakeId)

        assertTrue("an unusable resume must not launch", wake is AgentParkWakeResult.Stale)
        assertEquals("the stranded run is paused", AgentRunState.PAUSED, harness.run(runId)!!.state)
        assertNull(harness.parkTokens.load(runId))
        assertEquals("no executor ran", 1, executor.executeCount)
    }

    @Test
    fun aTokenForAnOlderRevisionIsRetiredWithoutResuming() = runTest {
        val executor = ScriptedRunExecutor()
        executor.enqueue(AgentRuntimeFixtures.waitingUser(AgentRuntimeFixtures.run()))
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        runCurrent()
        val runId = started.runId!!
        val token = requireNotNull(harness.parkTokens.load(runId))
        harness.parkTokens.record(token.copy(checkpointRevision = token.checkpointRevision + 1))

        val result = harness.owner.wakeParked(runId, token.wakeId)

        assertTrue("a stale revision must never resume", result is AgentParkWakeResult.Stale)
        assertEquals(
            com.muyuchat.core.agent.contract.AgentErrorCodes.RESOURCE_LEASE_LOST,
            (result as AgentParkWakeResult.Stale).errorCode
        )
        assertNull("the stale token is retired", harness.parkTokens.load(runId))
        assertEquals("no executor ran for a stale token", 1, executor.executeCount)
    }

    @Test
    fun aWakeThatRacesACancelIsStaleAndNeverResumesTheExecutor() = runTest {
        val executor = ScriptedRunExecutor()
        executor.enqueue(AgentRuntimeFixtures.waitingUser(AgentRuntimeFixtures.run()))
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        runCurrent()
        val runId = started.runId!!
        val token = requireNotNull(harness.parkTokens.load(runId))

        val cancelled = harness.owner.requestCancel(
            CancelAgentRun(harness.ids.nextId("cmd"), runId, harness.run(runId)!!.revision)
        )
        assertTrue("cancel must be accepted, got $cancelled", cancelled is AgentCommandResult.Accepted)
        runCurrent()
        assertEquals(AgentRunState.CANCELLED, harness.run(runId)!!.state)

        val wake = harness.owner.wakeParked(runId, token.wakeId)

        // Depending on which side wins, the token is either retired as stale by the wake (STOPPING)
        // or already gone after the settlement (CANCELLED). Neither may ever resume the executor.
        assertTrue(
            "a wake racing a cancel must never resume, got $wake",
            wake is AgentParkWakeResult.Stale || wake is AgentParkWakeResult.AlreadyConsumed
        )
        if (wake is AgentParkWakeResult.Stale) {
            assertEquals(
                "a cancelling/cancelled run must not resume through a one-shot authorization",
                AgentErrorCodes.PERMISSION_EXPIRED,
                wake.errorCode
            )
        }
        assertNull("the cancelled park token is retired", harness.parkTokens.load(runId))
        assertEquals("no executor ran after the cancel", 1, executor.executeCount)
    }
}
