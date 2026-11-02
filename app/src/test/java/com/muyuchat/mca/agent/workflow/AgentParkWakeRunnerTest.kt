package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.runtime.run.AgentParkKind
import com.muyuchat.core.agent.runtime.run.AgentParkWakeResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentParkWakeRunnerTest {

    @Test
    fun aCompletedWakeSettlesExactlyOnce() = runTest {
        var settles = 0
        val runner = AgentParkWakeRunner(
            wake = { AgentParkWakeResult.StillParked("run-1", AgentParkKind.APPROVAL) },
            settle = { settles += 1 }
        )

        val result = runner.run()

        assertTrue(result is AgentParkWakeRunResult.Completed)
        assertEquals(
            AgentParkWakeResult.StillParked("run-1", AgentParkKind.APPROVAL),
            (result as AgentParkWakeRunResult.Completed).outcome
        )
        assertEquals(1, settles)
    }

    @Test
    fun aFailingWakeIsTypedAndStillSettlesOnce() = runTest {
        var settles = 0
        val failure = IllegalStateException("store unavailable")
        val runner = AgentParkWakeRunner(
            wake = { throw failure },
            settle = { settles += 1 }
        )

        val result = runner.run()

        assertTrue(result is AgentParkWakeRunResult.Failed)
        assertEquals(failure, (result as AgentParkWakeRunResult.Failed).error)
        assertEquals(1, settles)
    }

    @Test
    fun cancellationPropagatesAndSettlesOnce() = runTest {
        var settles = 0
        val started = CompletableDeferred<Unit>()
        val runner = AgentParkWakeRunner(
            wake = {
                started.complete(Unit)
                throw kotlinx.coroutines.CancellationException("stopped")
            },
            settle = { settles += 1 }
        )

        var cancelled = false
        try {
            runner.run()
        } catch (_: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }

        assertTrue("cancellation must not become a typed failure", cancelled)
        assertEquals(1, settles)
    }

    @Test
    fun theUniqueWorkNameIsStablePerRunAndDoesNotLeakTheRawId() {
        val first = AgentParkWakePlan.uniqueName("run-1")
        val second = AgentParkWakePlan.uniqueName("run-1")
        val other = AgentParkWakePlan.uniqueName("run-2")

        assertEquals(first, second)
        assertTrue(first != other)
        assertTrue(first.startsWith("agent-park-"))
        assertTrue("the raw run id must not be the work name", !first.contains("run-1"))
    }
}
