package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AnswerAgentQuestion
import com.muyuchat.core.agent.contract.CancelAgentRun
import com.muyuchat.core.agent.contract.ContinueAgentTask
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.core.agent.contract.PauseAgentRun
import com.muyuchat.core.agent.contract.ResolveAgentApproval
import com.muyuchat.core.agent.contract.StartAgentRun
import com.muyuchat.core.agent.runtime.testing.AgentOwnerHarness
import com.muyuchat.core.agent.runtime.testing.AgentRuntimeFixtures
import com.muyuchat.core.agent.runtime.testing.ScriptedRunExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** K03 run-owner lifecycle tests (T05/T10/T13 memory level). */
class AgentRunOwnerTest {

    @Test
    fun completedRunStoresVisibleFinalTextAsTerminalSummary() = runTest {
        val executor = ScriptedRunExecutor().apply {
            gate = kotlinx.coroutines.CompletableDeferred()
        }
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        runCurrent()
        val runId = started.runId!!
        executor.enqueue(
            AgentRuntimeFixtures.completed(harness.run(runId)!!, text = "这是模型最终回答")
        )
        executor.gate!!.complete(Unit)
        runCurrent()

        val terminal = harness.journal.allEvents(runId)
            .single { it.type == com.muyuchat.core.agent.contract.AgentEventTypes.RUN_TERMINAL }
        assertEquals("这是模型最终回答", terminal.summary)
        assertEquals(AgentRunState.SUCCEEDED, harness.run(runId)!!.state)
    }

    @Test
    fun terminalSummaryIsBoundedForOversizedAnswers() {
        val long = "汉".repeat(2_000)
        val bounded = boundedTerminalSummary(long)!!
        assertTrue(bounded.length <= 1_200)
        assertTrue(bounded.toByteArray(Charsets.UTF_8).size <= 4 * 1024)
    }

    @Test
    fun unknownEffectParksTheRunInNeedsReviewAndReleasesTheSlot() = runTest {
        val executor = ScriptedRunExecutor().apply {
            gate = kotlinx.coroutines.CompletableDeferred()
        }
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        runCurrent()
        val runId = started.runId!!
        val run = harness.run(runId)!!

        executor.enqueue(
            AgentLoopResult.NeedsReview(
                run = run,
                transcript = AgentLoopTranscript(emptyList(), 0, 0),
                error = AgentError(
                    code = "WF_TOOL_UNKNOWN",
                    category = AgentErrorCategory.TOOL,
                    retryability = AgentRetryability.AFTER_USER_ACTION,
                    safeMessage = "The external effect could not be proven.",
                    correlationId = runId,
                    possibleSideEffects = true
                )
            )
        )
        executor.gate!!.complete(Unit)
        runCurrent()

        val review = harness.run(runId)!!
        assertEquals(AgentRunState.NEEDS_REVIEW, review.state)
        assertEquals(AgentPauseReason.UNKNOWN, review.reason)
        assertTrue("the active slot must be released", harness.owner.activeRunId() == null)
    }

    @Test
    fun pauseSettlesThenContinueCreatesSuccessorRun() = runTest {
        val executor = ScriptedRunExecutor().apply { suspendForever = true }
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        runCurrent()
        val runId = started.runId!!
        val running = harness.run(runId)!!
        assertEquals(AgentRunState.RUNNING, running.state)
        assertEquals(1, executor.executeCount)

        val prematureContinue = harness.owner.continueTask(
            ContinueAgentTask(
                harness.ids.nextId("cmd"),
                task.taskId,
                harness.journal.readTask(task.taskId)!!.revision
            )
        )
        assertRejected(prematureContinue, AgentErrorCodes.COMMAND_INVALID_STATE)

        val pause = harness.owner.requestPause(
            PauseAgentRun(harness.ids.nextId("cmd"), runId, running.revision)
        )
        assertTrue(pause is AgentCommandResult.Accepted)
        runCurrent()
        val paused = harness.run(runId)!!
        assertEquals(AgentRunState.PAUSED, paused.state)
        assertEquals(AgentPauseReason.USER_REQUESTED, paused.reason)
        assertEquals(1, executor.cancellationCount)

        executor.suspendForever = false
        val taskNow = harness.journal.readTask(task.taskId)!!
        val continued = harness.owner.continueTask(
            ContinueAgentTask(harness.ids.nextId("cmd"), task.taskId, taskNow.revision)
        )
        assertTrue(continued is AgentCommandResult.Accepted)
        runCurrent()
        val superseded = harness.run(runId)!!
        assertEquals(AgentRunState.SUPERSEDED, superseded.state)
        val successorId = (continued as AgentCommandResult.Accepted).runId!!
        val successor = harness.run(successorId)!!
        assertEquals(2L, successor.runOrdinal)
        assertEquals(2L, successor.epoch)
        assertEquals(AgentRunState.SUCCEEDED, successor.state)
        assertEquals(2, executor.executeCount)
    }

    @Test
    fun cancelSettlesToCancelledAndDuplicateCancelCoalesces() = runTest {
        val executor = ScriptedRunExecutor().apply { suspendForever = true }
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        runCurrent()
        val runId = started.runId!!
        val command = CancelAgentRun(
            harness.ids.nextId("cmd"),
            runId,
            harness.run(runId)!!.revision
        )
        val cancelled = harness.owner.requestCancel(command)
        assertTrue(cancelled is AgentCommandResult.Accepted)
        runCurrent()
        val run = harness.run(runId)!!
        assertEquals(AgentRunState.CANCELLED, run.state)
        assertTrue(run.state.isTerminal)
        assertEquals(1, executor.cancellationCount)

        val replay = harness.owner.requestCancel(command)
        assertTrue(replay is AgentCommandResult.Accepted)
        assertTrue((replay as AgentCommandResult.Accepted).idempotentReplay)

        val coalesced = harness.owner.requestCancel(
            CancelAgentRun(harness.ids.nextId("cmd"), runId, 1L)
        )
        assertTrue(coalesced is AgentCommandResult.Accepted)
        assertEquals(AgentRunState.CANCELLED, harness.run(runId)!!.state)
    }

    @Test
    fun cancellingAQueuedRunNeverStartsItsModel() = runTest {
        val executor = ScriptedRunExecutor().apply { suspendForever = true }
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val firstTask = harness.createTask(goal = "first")
        val secondTask = harness.createTask(goal = "second")
        val first = harness.start(firstTask) as AgentCommandResult.Accepted
        val second = harness.start(secondTask) as AgentCommandResult.Accepted
        runCurrent()
        val secondRunId = second.runId!!
        val queued = harness.run(secondRunId)!!
        assertEquals(AgentRunState.QUEUED, queued.state)
        assertEquals(1, executor.executeCount)

        val cancelled = harness.owner.requestCancel(
            CancelAgentRun(harness.ids.nextId("cmd"), secondRunId, queued.revision)
        )
        assertTrue(cancelled is AgentCommandResult.Accepted)
        runCurrent()
        assertEquals(AgentRunState.CANCELLED, harness.run(secondRunId)!!.state)
        assertEquals(1, executor.executeCount)
        assertTrue(harness.owner.activeRunId() == first.runId)
    }

    @Test
    fun oneActiveAndFiveWaitingRunsThenResourceBusy() = runTest {
        val executor = ScriptedRunExecutor().apply { suspendForever = true }
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val tasks = (1..7).map { index -> harness.createTask(goal = "task $index") }
        val starts = tasks.map { task -> harness.start(task) }
        assertEquals(6, starts.count { it is AgentCommandResult.Accepted })
        val rejected = starts.last()
        assertRejected(rejected, AgentErrorCodes.RESOURCE_BUSY)
        runCurrent()
        assertEquals(1, executor.executeCount)

        val queuedRunIds = starts.filterIsInstance<AgentCommandResult.Accepted>()
            .drop(1)
            .mapNotNull { it.runId }
        assertEquals(5, queuedRunIds.size)
        val firstQueued = harness.run(queuedRunIds.first())!!
        assertEquals(AgentRunState.QUEUED, firstQueued.state)
        harness.owner.requestCancel(
            CancelAgentRun(harness.ids.nextId("cmd"), firstQueued.runId, firstQueued.revision)
        )
        runCurrent()
        val retried = harness.start(tasks.last())
        assertTrue(retried is AgentCommandResult.Accepted)
        runCurrent()
        assertEquals(1, executor.executeCount)
    }

    @Test
    fun answerQuestionResumesTheSameRunExactlyOnce() = runTest {
        val executor = ScriptedRunExecutor()
        executor.enqueue(AgentRuntimeFixtures.waitingUser(AgentRuntimeFixtures.run()))
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        runCurrent()
        val runId = started.runId!!
        val waiting = harness.run(runId)!!
        assertEquals(AgentRunState.WAITING_USER, waiting.state)
        val question = harness.journal.readRun(runId)!!.activeQuestion
        assertNotNull(question)
        question!!
        // S2 contract change: a WAITING_USER park releases the single admission slot (WF-17); the
        // durable question keeps the run resumable instead of holding the owner's active slot.
        assertNull("a parked run must not hold the active slot", harness.owner.activeRunId())

        val answer = AnswerAgentQuestion(
            commandId = harness.ids.nextId("cmd"),
            runId = runId,
            expectedRunRevision = waiting.revision,
            questionId = question.questionId,
            answer = "开发者"
        )
        val accepted = harness.owner.answerQuestion(answer)
        assertTrue(accepted is AgentCommandResult.Accepted)
        runCurrent()
        val finished = harness.run(runId)!!
        assertEquals(AgentRunState.SUCCEEDED, finished.state)
        assertEquals(2, executor.executeCount)
        val resume = executor.resumes[1]
        assertNotNull(resume)
        assertEquals(question.questionId, resume!!.questionId)
        assertEquals("开发者", resume.userAnswer)

        val duplicate = harness.owner.answerQuestion(
            answer.copy(commandId = harness.ids.nextId("cmd"), expectedRunRevision = finished.revision)
        )
        assertRejected(duplicate, AgentErrorCodes.COMMAND_INVALID_STATE)
    }

    @Test
    fun closeRejectsEveryNewCommand() = runTest {
        val harness = AgentOwnerHarness(backgroundScope)
        harness.owner.close()
        val rejected = harness.owner.createTask(
            CreateAgentTask(
                commandId = harness.ids.nextId("cmd"),
                goal = "closed",
                displayTitle = "closed",
                modelBinding = AgentRuntimeFixtures.binding(),
                policy = com.muyuchat.core.agent.contract.AgentRunPolicy()
            )
        )
        assertRejected(rejected, AgentErrorCodes.COMMAND_BUSY)
    }

    // ---------------------------------------------------------------------------------------
    // S2 / WF-17: a park releases the single admission slot; a later resume is queued behind any
    // new active run and can never execute beside it.
    // ---------------------------------------------------------------------------------------

    @Test
    fun parkingAWaitingRunReleasesTheSlotAndPromotesTheQueuedRun() = runTest {
        val executor = ParkQueueExecutor()
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val firstTask = harness.createTask(goal = "first")
        val secondTask = harness.createTask(goal = "second")
        executor.enqueueFor(
            firstTask.taskId,
            AgentRuntimeFixtures.waitingUser(AgentRuntimeFixtures.run())
        )
        val firstGate = executor.gateFor(firstTask.taskId)
        val first = harness.start(firstTask) as AgentCommandResult.Accepted
        val second = harness.start(secondTask) as AgentCommandResult.Accepted
        runCurrent()
        val firstRunId = first.runId!!
        val secondRunId = second.runId!!
        assertEquals(
            "the second run must queue behind the still-executing first run",
            AgentRunState.QUEUED,
            harness.run(secondRunId)!!.state
        )
        assertEquals(firstRunId, harness.owner.activeRunId())

        firstGate.complete(Unit)
        runCurrent()

        assertEquals(AgentRunState.WAITING_USER, harness.run(firstRunId)!!.state)
        assertEquals(AgentRunState.SUCCEEDED, harness.run(secondRunId)!!.state)
        assertEquals(
            "the parked run released the slot, so the queued run started",
            listOf(firstRunId, secondRunId),
            executor.parkCalls.map { it.first }
        )
        assertEquals("neither run may keep the slot after settle/park", 1, executor.maxInFlight)
        assertNull(harness.owner.activeRunId())
    }

    @Test
    fun approvalResumeQueuesBehindTheActiveRunAndStartsAfterItSettles() = runTest {
        val executor = ParkQueueExecutor()
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val firstTask = harness.createTask(goal = "approval-first")
        val secondTask = harness.createTask(goal = "second")
        val (firstRunId, approval) = parkWaitingApproval(harness, executor, firstTask)
        val waiting = harness.run(firstRunId)!!
        assertNull("a parked approval run must not hold the admission slot", harness.owner.activeRunId())

        val secondGate = executor.gateFor(secondTask.taskId)
        val second = harness.start(secondTask) as AgentCommandResult.Accepted
        runCurrent()
        val secondRunId = second.runId!!
        assertEquals(secondRunId, harness.owner.activeRunId())

        val allowed = harness.owner.resolveApproval(
            ResolveAgentApproval(
                commandId = harness.ids.nextId("cmd"),
                runId = firstRunId,
                expectedRunRevision = waiting.revision,
                approvalId = approval.approvalId,
                decision = AgentApprovalDecision.ALLOW_ONCE
            )
        )
        assertTrue("the approval decision must be accepted, got $allowed", allowed is AgentCommandResult.Accepted)
        runCurrent()
        assertEquals(
            "the approved resume must not execute beside the active run",
            2,
            executor.parkExecuteCount
        )
        assertEquals(secondRunId, harness.owner.activeRunId())
        assertEquals(
            "the durable RUNNING transition is committed while the resume waits for the slot",
            AgentRunState.RUNNING,
            harness.run(firstRunId)!!.state
        )

        secondGate.complete(Unit)
        runCurrent()

        assertEquals(AgentRunState.SUCCEEDED, harness.run(secondRunId)!!.state)
        assertEquals(AgentRunState.SUCCEEDED, harness.run(firstRunId)!!.state)
        assertEquals("only one run may ever execute at a time", 1, executor.maxInFlight)
        assertEquals(firstRunId, executor.parkCalls.last().first)
        assertTrue(
            "the queued resume must carry the one-shot authorization",
            executor.parkCalls.last().second!!.toolDecision is AgentResumeToolDecision.Authorized
        )
        assertNull(harness.owner.activeRunId())
    }

    @Test
    fun duplicateApprovalResolutionOfAQueuedResumeExecutesOnce() = runTest {
        val executor = ParkQueueExecutor()
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val firstTask = harness.createTask(goal = "approval-first")
        val secondTask = harness.createTask(goal = "second")
        val (firstRunId, approval) = parkWaitingApproval(harness, executor, firstTask)
        val waiting = harness.run(firstRunId)!!
        val secondGate = executor.gateFor(secondTask.taskId)
        harness.start(secondTask)
        runCurrent()

        val allowCommand = ResolveAgentApproval(
            commandId = harness.ids.nextId("cmd"),
            runId = firstRunId,
            expectedRunRevision = waiting.revision,
            approvalId = approval.approvalId,
            decision = AgentApprovalDecision.ALLOW_ONCE
        )
        assertTrue(harness.owner.resolveApproval(allowCommand) is AgentCommandResult.Accepted)
        runCurrent()
        assertEquals(2, executor.parkExecuteCount)

        val duplicate = harness.owner.resolveApproval(
            allowCommand.copy(
                commandId = harness.ids.nextId("cmd"),
                expectedRunRevision = harness.run(firstRunId)!!.revision
            )
        )
        assertRejected(duplicate, AgentErrorCodes.COMMAND_INVALID_STATE)
        assertEquals("a duplicate decision must not start a second execution", 2, executor.parkExecuteCount)

        secondGate.complete(Unit)
        runCurrent()
        assertEquals(3, executor.parkExecuteCount)
        assertEquals(
            "the parked run executes its authorization exactly once",
            1,
            executor.parkCalls.count { it.first == firstRunId && it.second != null }
        )
        assertEquals(AgentRunState.SUCCEEDED, harness.run(firstRunId)!!.state)
    }

    @Test
    fun answerQuestionResumeAfterSlotHandoverResumesTheSameRun() = runTest {
        val executor = ParkQueueExecutor()
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val firstTask = harness.createTask(goal = "answer-first")
        val secondTask = harness.createTask(goal = "second")
        val firstRunId = parkWaitingUser(harness, executor, firstTask)
        assertNull(harness.owner.activeRunId())

        val secondGate = executor.gateFor(secondTask.taskId)
        val second = harness.start(secondTask) as AgentCommandResult.Accepted
        runCurrent()
        val secondRunId = second.runId!!
        val waiting = harness.run(firstRunId)!!
        val question = harness.journal.readRun(firstRunId)!!.activeQuestion!!
        val answer = harness.owner.answerQuestion(
            AnswerAgentQuestion(
                commandId = harness.ids.nextId("cmd"),
                runId = firstRunId,
                expectedRunRevision = waiting.revision,
                questionId = question.questionId,
                answer = "开发者"
            )
        )
        assertTrue("the answer must be accepted, got $answer", answer is AgentCommandResult.Accepted)
        runCurrent()
        assertEquals("the answer resume must wait for the slot", 2, executor.parkExecuteCount)
        assertEquals(secondRunId, harness.owner.activeRunId())

        secondGate.complete(Unit)
        runCurrent()

        assertEquals(AgentRunState.SUCCEEDED, harness.run(firstRunId)!!.state)
        assertEquals("the answer resumed the same run, not a successor", 1L, harness.run(firstRunId)!!.runOrdinal)
        val resume = executor.parkCalls.last().second
        assertNotNull(resume)
        assertEquals(question.questionId, resume!!.questionId)
        assertEquals("开发者", resume.userAnswer)
        assertEquals("only one run may ever execute at a time", 1, executor.maxInFlight)
        assertNull(harness.owner.activeRunId())
    }

    @Test
    fun queuedResumeWhoseEpochOrStateChangedFailsClosedWithoutExecuting() = runTest {
        // Epoch mismatch: the queued material no longer belongs to the durable run identity.
        val staleExecutor = ParkQueueExecutor()
        val staleHarness = AgentOwnerHarness(backgroundScope, staleExecutor)
        val staleFirst = staleHarness.createTask(goal = "stale-first")
        val staleSecond = staleHarness.createTask(goal = "stale-second")
        val staleRunId = parkWaitingUser(staleHarness, staleExecutor, staleFirst)
        val staleSecondGate = staleExecutor.gateFor(staleSecond.taskId)
        staleHarness.start(staleSecond)
        runCurrent()
        assertTrue(
            staleHarness.owner.answerQuestion(
                AnswerAgentQuestion(
                    commandId = staleHarness.ids.nextId("cmd"),
                    runId = staleRunId,
                    expectedRunRevision = staleHarness.run(staleRunId)!!.revision,
                    questionId = staleHarness.journal.readRun(staleRunId)!!.activeQuestion!!.questionId,
                    answer = "开发者"
                )
            ) is AgentCommandResult.Accepted
        )
        runCurrent()
        assertEquals(2, staleExecutor.parkExecuteCount)
        // Fault injection: the queued resume was captured for another epoch before promotion.
        val pending = staleHarness.owner.pendingResumes.getValue(staleRunId)
        staleHarness.owner.pendingResumes[staleRunId] = pending.copy(epoch = pending.epoch + 1L)

        staleSecondGate.complete(Unit)
        runCurrent()

        val paused = staleHarness.run(staleRunId)!!
        assertEquals("a stale resume must fail closed into a recoverable pause", AgentRunState.PAUSED, paused.state)
        assertEquals(AgentPauseReason.INTERRUPTED, paused.reason)
        assertTrue("the discarded resume must not stay queued", staleHarness.owner.pendingResumes.isEmpty())
        assertEquals("a stale resume must never execute", 2, staleExecutor.parkExecuteCount)
        assertEquals(
            0,
            staleExecutor.parkCalls.count { it.first == staleRunId && it.second != null }
        )
        assertNull(staleHarness.owner.activeRunId())

        // State mismatch: the queued run is cancelled before its resume reaches the slot.
        val cancelledExecutor = ParkQueueExecutor()
        val cancelledHarness = AgentOwnerHarness(backgroundScope, cancelledExecutor)
        val cancelledFirst = cancelledHarness.createTask(goal = "cancel-first")
        val cancelledSecond = cancelledHarness.createTask(goal = "cancel-second")
        val cancelledRunId = parkWaitingUser(cancelledHarness, cancelledExecutor, cancelledFirst)
        val cancelledGate = cancelledExecutor.gateFor(cancelledSecond.taskId)
        val cancelledSecondRun = cancelledHarness.start(cancelledSecond) as AgentCommandResult.Accepted
        runCurrent()
        assertTrue(
            cancelledHarness.owner.answerQuestion(
                AnswerAgentQuestion(
                    commandId = cancelledHarness.ids.nextId("cmd"),
                    runId = cancelledRunId,
                    expectedRunRevision = cancelledHarness.run(cancelledRunId)!!.revision,
                    questionId = cancelledHarness.journal.readRun(cancelledRunId)!!.activeQuestion!!.questionId,
                    answer = "管理者"
                )
            ) is AgentCommandResult.Accepted
        )
        runCurrent()
        assertEquals(2, cancelledExecutor.parkExecuteCount)
        assertTrue(
            cancelledHarness.owner.requestCancel(
                CancelAgentRun(
                    cancelledHarness.ids.nextId("cmd"),
                    cancelledRunId,
                    cancelledHarness.run(cancelledRunId)!!.revision
                )
            ) is AgentCommandResult.Accepted
        )
        runCurrent()
        assertEquals(AgentRunState.CANCELLED, cancelledHarness.run(cancelledRunId)!!.state)

        cancelledGate.complete(Unit)
        runCurrent()

        assertEquals(
            AgentRunState.SUCCEEDED,
            cancelledHarness.run(cancelledSecondRun.runId!!)!!.state
        )
        assertEquals("a cancelled queued resume must never execute", 2, cancelledExecutor.parkExecuteCount)
        assertEquals(
            0,
            cancelledExecutor.parkCalls.count { it.first == cancelledRunId && it.second != null }
        )
        assertNull(cancelledHarness.owner.activeRunId())
    }

    /**
     * Parks [task]'s run at WAITING_USER: the executor is gated above the result so the test can seed
     * and observe the durable park, then the gate is released and the park settles.
     */
    private suspend fun kotlinx.coroutines.test.TestScope.parkWaitingUser(
        harness: AgentOwnerHarness,
        executor: ParkQueueExecutor,
        task: AgentTask
    ): String {
        executor.enqueueFor(task.taskId, AgentRuntimeFixtures.waitingUser(AgentRuntimeFixtures.run()))
        val gate = executor.gateFor(task.taskId)
        val started = harness.start(task) as AgentCommandResult.Accepted
        val runId = started.runId!!
        gate.complete(Unit)
        runCurrent()
        check(harness.run(runId)!!.state == AgentRunState.WAITING_USER) {
            "fixture must park the run at WAITING_USER"
        }
        return runId
    }

    /** Parks [task]'s run at WAITING_APPROVAL with the durable invocation bound to its approval. */
    private suspend fun kotlinx.coroutines.test.TestScope.parkWaitingApproval(
        harness: AgentOwnerHarness,
        executor: ParkQueueExecutor,
        task: AgentTask
    ): Pair<String, com.muyuchat.core.agent.contract.AgentApproval> {
        val template = AgentRuntimeFixtures.frozenCommandFor()
        val approval = AgentRuntimeFixtures.pendingApprovalFor(template)
        executor.enqueueFor(
            task.taskId,
            AgentRuntimeFixtures.waitingApproval(AgentRuntimeFixtures.run(), approval)
        )
        val gate = executor.gateFor(task.taskId)
        val started = harness.start(task) as AgentCommandResult.Accepted
        val runId = started.runId!!
        harness.seedFrozenInvocation(template.copy(runId = runId))
        gate.complete(Unit)
        runCurrent()
        check(harness.run(runId)!!.state == AgentRunState.WAITING_APPROVAL) {
            "fixture must park the run at WAITING_APPROVAL"
        }
        return runId to approval
    }

    /**
     * S2 queue test executor: scripted result per task, an optional per-task gate and an in-flight
     * counter so a resume that starts beside the active run fails the concurrency assertion.
     */
    private class ParkQueueExecutor : ScriptedRunExecutor() {
        private val results = HashMap<String, ArrayDeque<AgentLoopResult>>()
        private val gates = HashMap<String, CompletableDeferred<Unit>>()
        val parkCalls = mutableListOf<Pair<String, AgentLoopResume?>>()
        var parkExecuteCount: Int = 0
            private set
        private var inFlight: Int = 0
        var maxInFlight: Int = 0
            private set

        fun enqueueFor(taskId: String, result: AgentLoopResult) {
            results.getOrPut(taskId) { ArrayDeque() }.addLast(result)
        }

        fun gateFor(taskId: String): CompletableDeferred<Unit> =
            gates.getOrPut(taskId) { CompletableDeferred() }

        override suspend fun execute(
            run: AgentRun,
            task: AgentTask,
            resume: AgentLoopResume?,
            progress: AgentModelProgressSink
        ): AgentLoopResult {
            parkExecuteCount += 1
            parkCalls += run.runId to resume
            inFlight += 1
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                gates[task.taskId]?.await()
                return results[task.taskId]?.removeFirstOrNull()
                    ?: AgentRuntimeFixtures.completed(run)
            } finally {
                inFlight -= 1
            }
        }
    }

    private fun assertRejected(result: AgentCommandResult, code: String) {
        assertTrue("expected rejection $code, got $result", result is AgentCommandResult.Rejected)
        assertEquals(code, (result as AgentCommandResult.Rejected).error.code)
    }
}
