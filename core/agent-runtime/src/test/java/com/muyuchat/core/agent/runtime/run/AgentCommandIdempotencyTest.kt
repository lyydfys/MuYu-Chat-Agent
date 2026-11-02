package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentModelResult
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.CancelAgentRun
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.core.agent.contract.PauseAgentRun
import com.muyuchat.core.agent.contract.StartAgentRun
import com.muyuchat.core.agent.runtime.testing.AgentOwnerHarness
import com.muyuchat.core.agent.runtime.testing.AgentRuntimeFixtures
import com.muyuchat.core.agent.runtime.testing.ScriptedRunExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** K03 command idempotency, revision order, stale callbacks and queue bounds (T02/T04/T10/T11/T12). */
class AgentCommandIdempotencyTest {

    @Test
    fun oneHundredConcurrentSameStartCommandCreatesExactlyOneRun() = runTest {
        val harness = AgentOwnerHarness(backgroundScope, maxPendingCommands = 128)
        val task = harness.createTask()
        val command = StartAgentRun(
            commandId = harness.ids.nextId("cmd"),
            taskId = task.taskId,
            expectedTaskRevision = task.revision
        )
        val results = coroutineScope {
            (1..100).map { async { harness.owner.startRun(command) } }.awaitAll()
        }
        runCurrent()
        val accepted = results.filterIsInstance<AgentCommandResult.Accepted>()
        assertEquals(100, accepted.size)
        assertEquals(1, accepted.mapNotNull { it.runId }.distinct().size)
        assertEquals(1, accepted.count { !it.idempotentReplay })
        assertEquals(99, accepted.count { it.idempotentReplay })
        assertEquals(1, harness.executor.executeCount)
        assertEquals(AgentRunState.SUCCEEDED, harness.run(accepted.first().runId!!)!!.state)
    }

    @Test
    fun oneHundredConcurrentSameCreateCommandCreatesExactlyOneTask() = runTest {
        val harness = AgentOwnerHarness(backgroundScope, maxPendingCommands = 128)
        val command = CreateAgentTask(
            commandId = harness.ids.nextId("cmd"),
            goal = "concurrent create",
            displayTitle = "title",
            modelBinding = AgentRuntimeFixtures.binding(),
            policy = com.muyuchat.core.agent.contract.AgentRunPolicy()
        )
        val results = coroutineScope {
            (1..100).map { async { harness.owner.createTask(command) } }.awaitAll()
        }
        assertEquals(1, harness.journal.allTasks().size)
        assertEquals(100, results.filterIsInstance<AgentCommandResult.Accepted>().size)
        assertEquals(1, results.count {
            it is AgentCommandResult.Accepted && !it.idempotentReplay
        })
    }

    @Test
    fun sameCommandIdWithDifferentContentIsAConflictBeforeRevisionValidation() = runTest {
        val harness = AgentOwnerHarness(backgroundScope)
        val task = harness.createTask()
        val replay = StartAgentRun(
            commandId = "cmd-fixed",
            taskId = task.taskId,
            expectedTaskRevision = task.revision
        )
        val first = harness.owner.startRun(replay)
        assertTrue(first is AgentCommandResult.Accepted)
        val conflict = harness.owner.startRun(replay.copy(taskId = "task-other"))
        assertTrue(conflict is AgentCommandResult.Rejected)
        assertEquals(
            AgentErrorCodes.COMMAND_IDEMPOTENCY_CONFLICT,
            (conflict as AgentCommandResult.Rejected).error.code
        )
    }

    @Test
    fun startAndRunCommandsUseTheirOwnExpectedRevisions() = runTest {
        val harness = AgentOwnerHarness(backgroundScope, ScriptedRunExecutor().apply { suspendForever = true })
        val task = harness.createTask()
        val wrongStart = harness.start(task, expectedTaskRevision = task.revision + 5)
        assertRejected(wrongStart, AgentErrorCodes.COMMAND_REVISION_CONFLICT)

        val started = harness.start(task)
        assertTrue(started is AgentCommandResult.Accepted)
        runCurrent()
        val runId = (started as AgentCommandResult.Accepted).runId!!
        val running = harness.run(runId)!!
        assertEquals(AgentRunState.RUNNING, running.state)

        val wrongPause = harness.owner.requestPause(
            PauseAgentRun(harness.ids.nextId("cmd"), runId, running.revision + 4)
        )
        assertRejected(wrongPause, AgentErrorCodes.COMMAND_REVISION_CONFLICT)
        val wrongCancel = harness.owner.requestCancel(
            CancelAgentRun(harness.ids.nextId("cmd"), runId, running.revision + 4)
        )
        assertRejected(wrongCancel, AgentErrorCodes.COMMAND_REVISION_CONFLICT)
    }

    @Test
    fun staleEpochAndAttemptCallbacksAreRejected() = runTest {
        val harness = AgentOwnerHarness(
            backgroundScope,
            ScriptedRunExecutor().apply { suspendForever = true }
        )
        val task = harness.createTask()
        val started = harness.start(task) as AgentCommandResult.Accepted
        runCurrent()
        val runId = started.runId!!
        val identity = AgentAttemptIdentity(
            runId = runId,
            epoch = 1L,
            stepIndex = 0,
            attemptOrdinal = 1,
            attemptId = "attempt-current"
        )
        harness.registry.start(identity)
        val accepted = harness.owner.acceptAttemptCallback(
            AgentAttemptResultCallback(identity, AgentModelResult.Success("ok"))
        )
        assertTrue(accepted is AgentAttemptCallbackDecision.Accepted)

        val staleEpoch = harness.owner.acceptAttemptCallback(
            AgentAttemptResultCallback(identity.copy(epoch = 2L), AgentModelResult.Success("late"))
        )
        assertTrue(staleEpoch is AgentAttemptCallbackDecision.Stale)
        assertEquals(
            AgentErrorCodes.RESOURCE_LEASE_LOST,
            (staleEpoch as AgentAttemptCallbackDecision.Stale).errorCode
        )
        val staleAttempt = harness.owner.acceptAttemptCallback(
            AgentAttemptResultCallback(
                identity.copy(attemptId = "attempt-old"),
                AgentModelResult.Success("late")
            )
        )
        assertTrue(staleAttempt is AgentAttemptCallbackDecision.Stale)
        val staleRun = harness.owner.acceptAttemptCallback(
            AgentAttemptResultCallback(
                identity.copy(runId = "run-other"),
                AgentModelResult.Success("late")
            )
        )
        assertTrue(staleRun is AgentAttemptCallbackDecision.Stale)

        val cancel = harness.owner.requestCancel(
            CancelAgentRun(harness.ids.nextId("cmd"), runId, harness.run(runId)!!.revision)
        )
        assertTrue(cancel is AgentCommandResult.Accepted)
        runCurrent()
        val afterTerminal = harness.owner.acceptAttemptCallback(
            AgentAttemptResultCallback(identity, AgentModelResult.Success("too late"))
        )
        assertTrue(afterTerminal is AgentAttemptCallbackDecision.Stale)
    }

    @Test
    fun commandProcessorBoundsPendingCommandsAndPrioritizesControl() = runTest {
        val processor = AgentCommandProcessor(
            backgroundScope,
            maxPendingNormal = 4,
            maxPendingControl = 2
        )
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val running = async {
            processor.submit(AgentCommandPriority.NORMAL) {
                gate.await()
                order += "first"
                1
            }
        }
        runCurrent()
        val normals = (1..4).map { index ->
            async {
                processor.submit(AgentCommandPriority.NORMAL) {
                    order += "normal-$index"
                    index
                }
            }
        }
        val control = async {
            processor.submit(AgentCommandPriority.CONTROL) {
                order += "control"
                0
            }
        }
        runCurrent()
        assertEquals(5, processor.pendingCommands())
        val fifth = processor.submit(AgentCommandPriority.NORMAL) { 5 }
        assertTrue(fifth is AgentProcessorResult.Rejected)
        assertEquals(
            AgentErrorCodes.COMMAND_BUSY,
            (fifth as AgentProcessorResult.Rejected).error.code
        )
        gate.complete(Unit)
        runCurrent()
        (listOf(running) + normals + listOf(control)).awaitAll()
        assertEquals("first", order.first())
        assertEquals("control", order[1])
        assertTrue(order.drop(2).all { it.startsWith("normal-") })
    }

    @Test
    fun pendingCommandCeilingIsSixtyFourNormals() = runTest {
        val processor = AgentCommandProcessor(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val running = async {
            processor.submit(AgentCommandPriority.NORMAL) {
                gate.await()
                0
            }
        }
        runCurrent()
        val pending = (1..64).map {
            async { processor.submit(AgentCommandPriority.NORMAL) { it } }
        }
        runCurrent()
        assertEquals(64, processor.pendingCommands())
        val overflow = processor.submit(AgentCommandPriority.NORMAL) { 65 }
        assertTrue(overflow is AgentProcessorResult.Rejected)
        assertEquals(
            AgentErrorCodes.COMMAND_BUSY,
            (overflow as AgentProcessorResult.Rejected).error.code
        )
        gate.complete(Unit)
        runCurrent()
        (listOf(running) + pending).awaitAll()
    }

    @Test
    fun callerCancellationRemovesTheQueuedCommandAndPropagates() = runTest {
        val processor = AgentCommandProcessor(backgroundScope, maxPendingNormal = 4)
        val gate = CompletableDeferred<Unit>()
        var executed = false
        val running = async {
            processor.submit(AgentCommandPriority.NORMAL) {
                gate.await()
                0
            }
        }
        runCurrent()
        val queued = launch {
            processor.submit(AgentCommandPriority.NORMAL) {
                executed = true
                1
            }
        }
        runCurrent()
        queued.cancel()
        queued.join()
        gate.complete(Unit)
        runCurrent()
        running.await()
        assertFalse(executed)
        assertEquals(0, processor.pendingCommands())
    }

    @Test
    fun closeRejectsNewCommands() = runTest {
        val processor = AgentCommandProcessor(backgroundScope)
        processor.close()
        assertTrue(processor.isClosed())
        val result = processor.submit(AgentCommandPriority.NORMAL) { 1 }
        assertTrue(result is AgentProcessorResult.Rejected)
        assertEquals(
            AgentErrorCodes.COMMAND_BUSY,
            (result as AgentProcessorResult.Rejected).error.code
        )
    }

    private fun assertRejected(result: AgentCommandResult, code: String) {
        assertTrue("expected rejection $code, got $result", result is AgentCommandResult.Rejected)
        assertEquals(code, (result as AgentCommandResult.Rejected).error.code)
    }
}
