package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentModelAttemptRef
import com.muyuchat.core.agent.contract.AgentModelPort
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentModelRequest
import com.muyuchat.core.agent.contract.AgentModelResult
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentStopDispatch
import com.muyuchat.core.agent.contract.CancelAgentRun
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.core.agent.contract.PauseAgentRun
import com.muyuchat.core.agent.contract.ResolveAgentApproval
import com.muyuchat.core.agent.contract.StartAgentRun
import com.muyuchat.core.agent.runtime.context.AgentContextAssembler
import com.muyuchat.core.agent.runtime.journal.InMemoryAgentJournal
import com.muyuchat.core.agent.runtime.protocol.AgentPromptedSystemPromptBuilder
import com.muyuchat.core.agent.runtime.protocol.AgentPromptedTranscriptSerializer
import com.muyuchat.core.agent.runtime.protocol.PromptedV1Decoder
import com.muyuchat.core.agent.runtime.protocol.PromptedV1TurnDecoder
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import com.muyuchat.core.agent.runtime.testing.AgentOwnerHarness
import com.muyuchat.core.agent.runtime.testing.AgentTestFixtures
import com.muyuchat.core.agent.runtime.testing.AgentRuntimeFixtures
import com.muyuchat.core.agent.runtime.testing.FakeArtifactVerifier
import com.muyuchat.core.agent.runtime.testing.FakeToolCatalog
import com.muyuchat.core.agent.runtime.testing.FixedRunSpecProvider
import com.muyuchat.core.agent.runtime.testing.RecordingFrozenCommandHook
import com.muyuchat.core.agent.runtime.testing.ScriptedRunExecutor
import com.muyuchat.core.agent.runtime.validation.AgentV1ToolSpecs
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K15 runtime fault injection: stop-proof, control/execution races, monotonic clock jumps,
 * approval decision replay and command replay after a simulated process restart.
 *
 * Test-only. Production behavior and assertions are unchanged; every scenario uses the existing
 * InMemoryAgentJournal and fakes.
 */
class AgentRuntimeFaultInjectionTest {

    // ---------------------------------------------------------------------------------------
    // Stop dispatch accepted != native stop proof.
    // ---------------------------------------------------------------------------------------

    @Test
    fun acceptedStopDispatchWithoutExecutionQuiescenceKeepsStoppingFence() = runTest {
        val executor = StubbornExecutor()
        val journal = InMemoryAgentJournal()
        val clock = ManualAgentMonotonicClock()
        val ids = SequentialAgentIdGenerator("stubborn")
        val owner = AgentRunOwner(
            journal = journal,
            ledger = journal,
            taskReader = journal,
            questionReader = journal,
            approvalReader = journal,
            specProvider = FixedRunSpecProvider(),
            executor = executor,
            processor = AgentCommandProcessor(backgroundScope),
            clock = clock,
            ids = ids,
            scope = backgroundScope,
            attemptRegistry = AgentActiveAttemptRegistry()
        )
        val create = owner.createTask(
            CreateAgentTask(
                commandId = "cmd-stubborn-create",
                goal = "stubborn stop",
                displayTitle = "stubborn stop",
                modelBinding = AgentRuntimeFixtures.binding(),
                policy = AgentRunPolicy()
            )
        )
        assertTrue(create is AgentCommandResult.Accepted)
        val task = journal.allTasks().single()
        val started = owner.startRun(
            StartAgentRun("cmd-stubborn-start", task.taskId, task.revision)
        ) as AgentCommandResult.Accepted
        val runId = started.runId!!
        runCurrent()
        assertTrue(executor.started.isCompleted)
        val running = journal.readRun(runId)!!.run
        assertEquals(AgentRunState.RUNNING, running.state)

        // The adapter reports that a stop was dispatched/accepted...
        val port = object : AgentModelPort {
            override suspend fun infer(
                request: AgentModelRequest,
                progress: AgentModelProgressSink
            ): AgentModelResult = throw UnsupportedOperationException("not used in this test")

            override suspend fun requestStop(attempt: AgentModelAttemptRef): AgentStopDispatch =
                AgentStopDispatch(accepted = true, detail = "dispatch accepted")
        }
        val dispatch = port.requestStop(
            AgentModelAttemptRef("attempt-fault-1", runId, 0, 1)
        )
        assertTrue(dispatch.accepted)
        // ...but that must not change the domain state by itself.
        assertEquals(AgentRunState.RUNNING, journal.readRun(runId)!!.run.state)

        val cancel = owner.requestCancel(
            CancelAgentRun(ids.nextId("cmd"), runId, running.revision)
        )
        assertTrue(cancel is AgentCommandResult.Accepted)
        runCurrent()
        assertEquals(AgentRunState.STOPPING, journal.readRun(runId)!!.run.state)
        assertFalse(journal.readRun(runId)!!.run.state.isTerminal)

        // Only actual execution quiescence allows the single CANCELLED settlement.
        executor.release.complete(Unit)
        runCurrent()
        assertEquals(AgentRunState.CANCELLED, journal.readRun(runId)!!.run.state)
        assertEquals(1, journal.allTasks().size)
    }

    // ---------------------------------------------------------------------------------------
    // Pause/cancel command racing execution completion.
    // ---------------------------------------------------------------------------------------

    @Test
    fun pauseRacingLateSuccessKeepsPauseAndDiscardsSuccess() = runTest {
        val executor = ScriptedRunExecutor()
        executor.gate = CompletableDeferred()
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        val runId = started.runId!!
        runCurrent()
        val running = harness.run(runId)!!
        assertEquals(AgentRunState.RUNNING, running.state)

        val pause = harness.owner.requestPause(
            PauseAgentRun(harness.ids.nextId("cmd"), runId, running.revision)
        )
        assertTrue(pause is AgentCommandResult.Accepted)
        // The model attempt completes "late", racing the pause settlement.
        executor.gate!!.complete(Unit)
        runCurrent()

        val final = harness.run(runId)!!
        assertEquals(AgentRunState.PAUSED, final.state)
        assertNotEquals(AgentRunState.SUCCEEDED, final.state)
        assertEquals(AgentPauseReason.USER_REQUESTED, final.reason)
        assertEquals(1, executor.executeCount)
    }

    @Test
    fun cancelRacingLateSuccessKeepsCancellationAndDiscardsSuccess() = runTest {
        val executor = ScriptedRunExecutor()
        executor.gate = CompletableDeferred()
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        val runId = started.runId!!
        runCurrent()
        val running = harness.run(runId)!!

        val cancel = harness.owner.requestCancel(
            CancelAgentRun(harness.ids.nextId("cmd"), runId, running.revision)
        )
        assertTrue(cancel is AgentCommandResult.Accepted)
        executor.gate!!.complete(Unit)
        runCurrent()

        val final = harness.run(runId)!!
        assertEquals(AgentRunState.CANCELLED, final.state)
        assertTrue(final.state.isTerminal)
        assertNotEquals(AgentRunState.SUCCEEDED, final.state)
        assertEquals(1, executor.executeCount)
    }

    @Test
    fun concurrentPauseAndCancelCommandsProduceOneAcceptanceAndOneSettledState() = runTest {
        val executor = ScriptedRunExecutor()
        executor.gate = CompletableDeferred()
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        val runId = started.runId!!
        runCurrent()
        val running = harness.run(runId)!!

        val pauseDeferred = async {
            harness.owner.requestPause(
                PauseAgentRun(harness.ids.nextId("cmd"), runId, running.revision)
            )
        }
        val cancelDeferred = async {
            harness.owner.requestCancel(
                CancelAgentRun(harness.ids.nextId("cmd"), runId, running.revision)
            )
        }
        val results = listOf(pauseDeferred.await(), cancelDeferred.await())
        assertEquals(
            "exactly one control command may win the expectedRevision race",
            1,
            results.count { it is AgentCommandResult.Accepted }
        )
        executor.gate!!.complete(Unit)
        runCurrent()
        val final = harness.run(runId)!!
        assertTrue(
            "control race must settle to PAUSED or CANCELLED, got ${final.state}",
            final.state == AgentRunState.PAUSED || final.state == AgentRunState.CANCELLED
        )
        assertFalse(final.state == AgentRunState.RUNNING)
        assertFalse(final.state == AgentRunState.SUCCEEDED)
        assertTrue(final.state == AgentRunState.PAUSED || final.state.isTerminal)
    }

    // ---------------------------------------------------------------------------------------
    // Budget clock jumps.
    // ---------------------------------------------------------------------------------------

    @Test
    fun forwardModelWallClockJumpDuringAttemptPausesBeforeDecoding() = runTest {
        val clock = ManualAgentMonotonicClock()
        val (journal, task, run) = journalWithRun()
        val port = JumpingModelPort(
            clock = clock,
            jumpMillis = 180_001L,
            result = AgentModelResult.Success(AgentTestFixtures.finalEnvelope("late final"))
        )
        val result = coordinator(journal, port, clock).execute(
            run,
            task,
            null,
            AgentModelProgressSink { }
        )
        assertTrue(result is AgentLoopResult.Paused)
        result as AgentLoopResult.Paused
        assertEquals(AgentPauseReason.BUDGET_LIMIT, result.reason)
        assertEquals(AgentErrorCodes.BUDGET_TIME_LIMIT, result.error?.code)
        assertEquals(1, port.inferCount)
        assertEquals(0, result.transcript.entries.size)
        assertNotEquals(AgentRunState.SUCCEEDED, result.run.state)
    }

    @Test
    fun forwardRunActiveClockJumpPausesAtTheNextStep() = runTest {
        val clock = ManualAgentMonotonicClock()
        val budget = com.muyuchat.core.agent.contract.AgentBudget(
            modelWallTimeMillis = 3_600_000L,
            runActiveTimeMillis = 900_000L
        )
        val (journal, task, run) = journalWithRun(budget = budget)
        val port = JumpingModelPort(
            clock = clock,
            jumpMillis = 900_001L,
            result = AgentModelResult.Success("not a control envelope")
        )
        val result = coordinator(journal, port, clock).execute(
            run,
            task,
            null,
            AgentModelProgressSink { }
        )
        assertTrue(result is AgentLoopResult.Paused)
        result as AgentLoopResult.Paused
        assertEquals(AgentPauseReason.BUDGET_LIMIT, result.reason)
        assertEquals(AgentErrorCodes.BUDGET_TIME_LIMIT, result.error?.code)
        assertEquals(1, port.inferCount)
        assertTrue(result.transcript.entries.first() is AgentTranscriptEntry.ProtocolError)
    }

    @Test
    fun backwardsClockJumpCannotCreateNegativeElapsedTimeOrBudgetCredit() {
        var now = 5_000_000L
        val clock = AgentMonotonicClock { now }
        val policy = AgentBudgetPolicy(clock)
        val budget = com.muyuchat.core.agent.contract.AgentBudget()
        val startedAt = now
        now = 1_000_000L // hostile backwards jump; elapsed is coerced to zero
        assertTrue(policy.checkModelWallTime(startedAt, budget) is AgentBudgetDecision.Allowed)
        assertTrue(policy.checkRunActiveTime(startedAt, budget) is AgentBudgetDecision.Allowed)
        assertTrue(policy.checkResourceWait(startedAt, budget) is AgentBudgetDecision.Allowed)
        val charged = policy.chargeModelAttempt(
            com.muyuchat.core.agent.contract.AgentSpentBudget(),
            startedAt,
            generatedTokens = 1
        )
        assertEquals(0L, charged.modelWallTimeMillis)
        assertEquals(0L, policy.chargeRunActiveTime(
            com.muyuchat.core.agent.contract.AgentSpentBudget(),
            startedAt
        ).runActiveTimeMillis)
    }

    // ---------------------------------------------------------------------------------------
    // Approval decision replay.
    // ---------------------------------------------------------------------------------------

    @Test
    fun approvalAllowResumesOnlyWithAuthorizationAndRejectsReplayAndDifferentDecision() = runTest {
        val executor = ScriptedRunExecutor()
        val commandTemplate = AgentRuntimeFixtures.frozenCommandFor()
        val approval = AgentRuntimeFixtures.pendingApprovalFor(commandTemplate)
        executor.enqueue(
            AgentRuntimeFixtures.waitingApproval(AgentRuntimeFixtures.run(), approval)
        )
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        val runId = started.runId!!
        val command = commandTemplate.copy(runId = runId)
        harness.seedFrozenInvocation(command)
        runCurrent()
        val waiting = harness.run(runId)!!
        assertEquals(AgentRunState.WAITING_APPROVAL, waiting.state)
        assertEquals(1, executor.executeCount)

        val allow = ResolveAgentApproval(
            commandId = "cmd-approval-allow",
            runId = runId,
            expectedRunRevision = waiting.revision,
            approvalId = approval.approvalId,
            decision = AgentApprovalDecision.ALLOW_ONCE
        )
        val first = harness.owner.resolveApproval(allow)
        assertTrue(first is AgentCommandResult.Accepted)
        runCurrent()
        assertEquals(AgentRunState.SUCCEEDED, harness.run(runId)!!.state)
        assertEquals(2, executor.executeCount)
        val decision = executor.resumes[1]?.toolDecision
        assertTrue(
            "expected a one-shot authorization, got $decision",
            decision is AgentResumeToolDecision.Authorized
        )
        assertEquals(
            command.invocationId,
            (decision as AgentResumeToolDecision.Authorized).authorization.command.invocationId
        )

        val replay = harness.owner.resolveApproval(allow)
        assertTrue(replay is AgentCommandResult.Accepted)
        assertTrue((replay as AgentCommandResult.Accepted).idempotentReplay)
        assertEquals(2, executor.executeCount)

        val differentDecision = harness.owner.resolveApproval(
            allow.copy(decision = AgentApprovalDecision.DENY)
        )
        assertTrue(differentDecision is AgentCommandResult.Rejected)
        assertEquals(
            AgentErrorCodes.COMMAND_IDEMPOTENCY_CONFLICT,
            (differentDecision as AgentCommandResult.Rejected).error.code
        )
        assertEquals(2, executor.executeCount)

        val secondCommand = harness.owner.resolveApproval(
            allow.copy(
                commandId = harness.ids.nextId("cmd"),
                expectedRunRevision = harness.run(runId)!!.revision,
                decision = AgentApprovalDecision.DENY
            )
        )
        assertTrue(secondCommand is AgentCommandResult.Rejected)
        assertEquals(
            AgentErrorCodes.COMMAND_INVALID_STATE,
            (secondCommand as AgentCommandResult.Rejected).error.code
        )
    }

    @Test
    fun approvalDenyIsPairedAndResumesTheSameRunWithoutExecuting() = runTest {
        val executor = ScriptedRunExecutor()
        val commandTemplate = AgentRuntimeFixtures.frozenCommandFor(
            invocationId = "invocation-deny-1"
        )
        val approval = AgentRuntimeFixtures.pendingApprovalFor(
            commandTemplate,
            approvalId = "approval-deny-1"
        )
        executor.enqueue(
            AgentRuntimeFixtures.waitingApproval(AgentRuntimeFixtures.run(), approval)
        )
        val harness = AgentOwnerHarness(backgroundScope, executor)
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        val runId = started.runId!!
        harness.seedFrozenInvocation(commandTemplate.copy(runId = runId))
        runCurrent()
        val waiting = harness.run(runId)!!
        val deny = ResolveAgentApproval(
            commandId = "cmd-approval-deny",
            runId = runId,
            expectedRunRevision = waiting.revision,
            approvalId = approval.approvalId,
            decision = AgentApprovalDecision.DENY
        )
        val result = harness.owner.resolveApproval(deny)
        assertTrue(result is AgentCommandResult.Accepted)
        runCurrent()
        assertEquals(AgentRunState.SUCCEEDED, harness.run(runId)!!.state)
        val decision = executor.resumes[1]?.toolDecision
        assertTrue(decision is AgentResumeToolDecision.NotExecuted)
        assertEquals(
            com.muyuchat.core.agent.contract.AgentToolResultStatus.DENIED,
            (decision as AgentResumeToolDecision.NotExecuted).status
        )
        assertEquals(
            com.muyuchat.core.agent.contract.ToolInvocationStatus.DENIED,
            harness.journal.readInvocation("invocation-deny-1")?.status
        )
    }

    // ---------------------------------------------------------------------------------------
    // Idempotent command replay after a simulated process restart.
    // ---------------------------------------------------------------------------------------

    @Test
    fun commandReplayAfterRuntimeRestartDoesNotExecuteAgain() = runTest {
        val firstHarness = AgentOwnerHarness(backgroundScope)
        val create = CreateAgentTask(
            commandId = "cmd-restart-create",
            goal = "restart replay goal",
            displayTitle = "restart replay",
            modelBinding = AgentRuntimeFixtures.binding(),
            policy = AgentRunPolicy()
        )
        val created = firstHarness.owner.createTask(create)
        assertTrue(created is AgentCommandResult.Accepted)
        val task = firstHarness.journal.allTasks().single()
        val start = StartAgentRun(
            commandId = "cmd-restart-start",
            taskId = task.taskId,
            expectedTaskRevision = task.revision
        )
        val started = firstHarness.owner.startRun(start)
        assertTrue(started is AgentCommandResult.Accepted)
        runCurrent()
        val runId = (started as AgentCommandResult.Accepted).runId!!
        assertEquals(AgentRunState.SUCCEEDED, firstHarness.run(runId)!!.state)
        val firstAppliedRevision = started.appliedRevision
        assertEquals(1, firstHarness.executor.executeCount)

        // Simulated process restart: fresh processor/owner/executor/ids, same durable journal.
        val restartedExecutor = ScriptedRunExecutor()
        val restartedOwner = AgentRunOwner(
            journal = firstHarness.journal,
            ledger = firstHarness.journal,
            taskReader = firstHarness.journal,
            questionReader = firstHarness.journal,
            approvalReader = firstHarness.journal,
            specProvider = FixedRunSpecProvider(),
            executor = restartedExecutor,
            processor = AgentCommandProcessor(backgroundScope),
            clock = firstHarness.clock,
            ids = SequentialAgentIdGenerator("restarted"),
            scope = backgroundScope,
            attemptRegistry = AgentActiveAttemptRegistry()
        )
        val createReplay = restartedOwner.createTask(create)
        assertTrue(createReplay is AgentCommandResult.Accepted)
        assertTrue((createReplay as AgentCommandResult.Accepted).idempotentReplay)
        assertEquals(1, firstHarness.journal.allTasks().size)

        val startReplay = restartedOwner.startRun(start)
        assertTrue(startReplay is AgentCommandResult.Accepted)
        startReplay as AgentCommandResult.Accepted
        assertTrue(startReplay.idempotentReplay)
        assertEquals(runId, startReplay.runId)
        assertEquals(firstAppliedRevision, startReplay.appliedRevision)
        assertEquals(0, restartedExecutor.executeCount)
        assertEquals(AgentRunState.SUCCEEDED, firstHarness.run(runId)!!.state)
        assertEquals(1, firstHarness.journal.allTasks().size)
    }

    // ---------------------------------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------------------------------

    private class StubbornExecutor : AgentRunExecutor {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var executeCount: Int = 0

        override suspend fun execute(
            run: AgentRun,
            task: com.muyuchat.core.agent.contract.AgentTask,
            resume: AgentLoopResume?,
            progress: AgentModelProgressSink
        ): AgentLoopResult {
            executeCount += 1
            started.complete(Unit)
            // Ignore cancellation and only finish when the test supplies real stop proof.
            withContext(NonCancellable) { release.await() }
            return AgentRuntimeFixtures.completed(run)
        }
    }

    private class JumpingModelPort(
        private val clock: ManualAgentMonotonicClock,
        private val jumpMillis: Long,
        private val result: AgentModelResult
    ) : AgentModelPort {
        var inferCount: Int = 0

        override suspend fun infer(
            request: AgentModelRequest,
            progress: AgentModelProgressSink
        ): AgentModelResult {
            inferCount += 1
            clock.advanceMillis(jumpMillis)
            return result
        }

        override suspend fun requestStop(attempt: AgentModelAttemptRef): AgentStopDispatch =
            AgentStopDispatch(accepted = true)
    }

    private suspend fun journalWithRun(
        budget: com.muyuchat.core.agent.contract.AgentBudget =
            com.muyuchat.core.agent.contract.AgentBudget()
    ): Triple<InMemoryAgentJournal, com.muyuchat.core.agent.contract.AgentTask, AgentRun> {
        val journal = InMemoryAgentJournal()
        val task = AgentRuntimeFixtures.task()
        journal.commit(
            com.muyuchat.core.agent.contract.CreateTaskTransition("setup-task-fault", task)
        )
        val queued = AgentRuntimeFixtures.run(state = AgentRunState.QUEUED, budget = budget)
        journal.commit(
            com.muyuchat.core.agent.contract.CreateRunTransition(
                "setup-run-fault",
                queued,
                taskRevision = task.revision,
                taskDisplay = com.muyuchat.core.agent.contract.AgentDisplayStatus.RUNNING
            )
        )
        val running = queued.copy(state = AgentRunState.RUNNING, revision = queued.revision + 1)
        journal.commit(
            com.muyuchat.core.agent.contract.UpdateRunTransition(
                "setup-run-running",
                running,
                com.muyuchat.core.agent.contract.AgentDisplayStatus.RUNNING,
                queued.revision
            )
        )
        return Triple(journal, task, running)
    }

    private fun coordinator(
        journal: InMemoryAgentJournal,
        modelPort: AgentModelPort,
        clock: ManualAgentMonotonicClock
    ): AgentPromptedLoopCoordinator {
        val codec = StrictAgentJsonCodec()
        val catalog = FakeToolCatalog(emptyMap())
        return AgentPromptedLoopCoordinator(
            decoderFactory = { keys -> PromptedV1TurnDecoder(PromptedV1Decoder({ keys }, codec)) },
            serializer = AgentPromptedTranscriptSerializer(codec),
            systemPromptBuilder = AgentPromptedSystemPromptBuilder(),
            catalogProvider = AgentRunToolCatalogProvider { _, _ -> catalog },
            schemaValidator = SupportedToolSchemaValidator(),
            journal = journal,
            toolLedger = journal,
            modelPort = modelPort,
            budgetPolicy = AgentBudgetPolicy(clock),
            contextAssembler = AgentContextAssembler(AgentPromptedTranscriptSerializer(codec), codec = codec),
            commandHook = RecordingFrozenCommandHook(ledger = journal),
            artifactVerifier = FakeArtifactVerifier(),
            ids = SequentialAgentIdGenerator("fault-loop"),
            clock = clock,
            attemptRegistry = AgentActiveAttemptRegistry()
        )
    }
}
