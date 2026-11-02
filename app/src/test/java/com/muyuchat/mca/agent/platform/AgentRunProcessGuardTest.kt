package com.muyuchat.mca.agent.platform

import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunProcessGuardTest {

    @Test
    fun runningAcquiresExactlyOnce() {
        val source = FakeLeaseSource()
        val guard = AgentRunProcessGuard(source)
        val run = run("run-1", 7L)

        val first = guard.update(run, AgentRunState.RUNNING)
        assertEquals(1, source.acquireCalls)
        assertEquals(1, first.events.filterIsInstance<AgentRunProcessGuardEvent.Acquired>().size)
        assertTrue(guard.snapshot().hasLease)

        val second = guard.update(run, AgentRunState.RUNNING)
        assertTrue(second.events.isEmpty())
        assertEquals(1, source.acquireCalls)
    }

    @Test
    fun waitingApprovalReleasesAndDoesNotAcquireAgain() {
        val source = FakeLeaseSource()
        val guard = AgentRunProcessGuard(source)
        val run = run()
        guard.update(run, AgentRunState.RUNNING)
        val lease = source.leases.single()

        val outcome = guard.update(run, AgentRunState.WAITING_APPROVAL)
        assertEquals(1, outcome.events.filterIsInstance<AgentRunProcessGuardEvent.Released>().size)
        assertTrue(lease.isReleased())
        assertEquals(1, source.delegates.single().releaseCount)
        assertEquals(1, source.acquireCalls)
        assertEquals(run, guard.activeStopAction())
    }

    @Test
    fun waitingApprovalResourceAndUserNeverAcquire() {
        for (state in listOf(
            AgentRunState.WAITING_APPROVAL,
            AgentRunState.WAITING_RESOURCE,
            AgentRunState.WAITING_USER
        )) {
            val source = FakeLeaseSource()
            val guard = AgentRunProcessGuard(source)
            val outcome = guard.update(run(), state)
            assertTrue(outcome.events.isEmpty())
            assertEquals(0, source.acquireCalls)
            assertFalse(guard.snapshot().hasLease)
        }
    }

    @Test
    fun queuedDoesNotAcquire() {
        val source = FakeLeaseSource()
        val guard = AgentRunProcessGuard(source)
        assertTrue(guard.update(run(), AgentRunState.QUEUED).events.isEmpty())
        assertEquals(0, source.acquireCalls)
    }

    @Test
    fun pausingAndStoppingRetainUntilTerminalThenReleaseExactlyOnce() {
        val source = FakeLeaseSource()
        val guard = AgentRunProcessGuard(source)
        val run = run()
        guard.update(run, AgentRunState.RUNNING)
        val lease = source.leases.single()

        val pausing = guard.update(run, AgentRunState.PAUSING)
        assertEquals(1, pausing.events.filterIsInstance<AgentRunProcessGuardEvent.Retained>().size)
        assertFalse(lease.isReleased())

        val stopping = guard.update(run, AgentRunState.STOPPING)
        assertEquals(1, stopping.events.filterIsInstance<AgentRunProcessGuardEvent.Retained>().size)
        assertFalse(lease.isReleased())

        val terminal = guard.update(run, AgentRunState.CANCELLED)
        assertEquals(1, terminal.events.filterIsInstance<AgentRunProcessGuardEvent.Released>().size)
        assertTrue(lease.isReleased())
        assertEquals(1, source.delegates.single().releaseCount)

        assertTrue(guard.update(run, AgentRunState.CANCELLED).events.isEmpty())
        assertEquals(1, source.delegates.single().releaseCount)
        assertNull(guard.activeStopAction())
    }

    @Test
    fun terminalFromRunningReleases() {
        val source = FakeLeaseSource()
        val guard = AgentRunProcessGuard(source)
        val run = run()
        guard.update(run, AgentRunState.RUNNING)
        val outcome = guard.update(run, AgentRunState.SUCCEEDED)
        assertEquals(1, outcome.events.filterIsInstance<AgentRunProcessGuardEvent.Released>().size)
        assertTrue(source.leases.single().isReleased())
    }

    @Test
    fun pausedAndReviewReleaseWithoutAcquiring() {
        for (state in listOf(
            AgentRunState.PAUSED,
            AgentRunState.NEEDS_REVIEW,
            AgentRunState.INTERRUPTED
        )) {
            val source = FakeLeaseSource()
            val guard = AgentRunProcessGuard(source)
            val run = run()
            guard.update(run, AgentRunState.RUNNING)
            val outcome = guard.update(run, state)
            assertEquals(1, outcome.events.filterIsInstance<AgentRunProcessGuardEvent.Released>().size)
            assertEquals(0, source.acquireCalls - 1)
            assertNull(guard.activeStopAction())
        }
    }

    @Test
    fun newRunningRunReleasesOldLeaseAndAcquiresNew() {
        val source = FakeLeaseSource()
        val guard = AgentRunProcessGuard(source)
        val first = run("run-1", 7L)
        val second = run("run-2", 8L)
        guard.update(first, AgentRunState.RUNNING)
        val firstLease = source.leases.single()

        val outcome = guard.update(second, AgentRunState.RUNNING)
        assertEquals(2, source.acquireCalls)
        assertTrue(firstLease.isReleased())
        assertEquals(1, source.delegates.first().releaseCount)
        assertEquals(1, outcome.events.filterIsInstance<AgentRunProcessGuardEvent.Released>().size)
        assertEquals(1, outcome.events.filterIsInstance<AgentRunProcessGuardEvent.Acquired>().size)
        assertEquals(second, guard.activeStopAction())
    }

    @Test
    fun newWaitingRunReleasesOldLeaseAndKeepsStopAction() {
        val source = FakeLeaseSource()
        val guard = AgentRunProcessGuard(source)
        val first = run("run-1", 7L)
        val second = run("run-2", 8L)
        guard.update(first, AgentRunState.RUNNING)
        val firstLease = source.leases.single()

        val outcome = guard.update(second, AgentRunState.WAITING_APPROVAL)
        assertEquals(1, source.acquireCalls)
        assertTrue(firstLease.isReleased())
        assertEquals(1, outcome.events.filterIsInstance<AgentRunProcessGuardEvent.Released>().size)
        assertEquals(second, guard.activeStopAction())
    }

    @Test
    fun failedAcquireIsTypedAndRetriable() {
        val source = FakeLeaseSource().apply { failNext = true }
        val guard = AgentRunProcessGuard(source)
        val outcome = guard.update(run(), AgentRunState.RUNNING)
        val unavailable = outcome.foregroundUnavailable
        assertTrue(unavailable != null)
        assertEquals(
            AgentPlatformErrorCodes.FOREGROUND_SERVICE_UNAVAILABLE,
            unavailable!!.error.code
        )
        assertEquals(AgentPauseReason.FOREGROUND_SERVICE_UNAVAILABLE, unavailable.pauseReason)
        assertFalse(guard.snapshot().hasLease)

        val retry = guard.update(run(), AgentRunState.RUNNING)
        assertEquals(1, retry.events.filterIsInstance<AgentRunProcessGuardEvent.Acquired>().size)
        assertEquals(2, source.acquireCalls)
    }

    @Test
    fun throwingLeaseSourceNeverAbortsTheRun() {
        val source = FakeLeaseSource().apply { throwNext = true }
        val guard = AgentRunProcessGuard(source)
        val outcome = guard.update(run(), AgentRunState.RUNNING)
        assertTrue(outcome.foregroundUnavailable != null)
        assertFalse(guard.snapshot().hasLease)
    }

    @Test
    fun releaseFailureIsTypedAndLeaseIsDroppedExactlyOnce() {
        val source = FakeLeaseSource().apply { failReleaseNext = true }
        val guard = AgentRunProcessGuard(source)
        guard.update(run(), AgentRunState.RUNNING)
        val outcome = guard.update(run(), AgentRunState.CANCELLED)
        assertEquals(1, outcome.events.filterIsInstance<AgentRunProcessGuardEvent.ReleaseFailed>().size)
        assertFalse(guard.snapshot().hasLease)
        assertEquals(1, source.delegates.single().releaseCount)
        assertTrue(guard.update(run(), AgentRunState.CANCELLED).events.isEmpty())
    }

    @Test
    fun releaseNowIsExactlyOnceAndClearsActiveKey() {
        val source = FakeLeaseSource()
        val guard = AgentRunProcessGuard(source)
        val run = run()
        guard.update(run, AgentRunState.RUNNING)

        assertEquals(AgentForegroundLeaseRelease.Released, guard.releaseNow())
        assertEquals(1, source.delegates.single().releaseCount)
        assertNull(guard.releaseNow())
        assertNull(guard.activeStopAction())
        assertNull(guard.snapshot().state)
    }

    @Test
    fun activeStopActionExistsForWaitingAndStoppingButNotForStablePaused() {
        val source = FakeLeaseSource()
        val guard = AgentRunProcessGuard(source)
        val run = run()
        guard.update(run, AgentRunState.WAITING_RESOURCE)
        assertEquals(run, guard.activeStopAction())
        guard.update(run, AgentRunState.PAUSING)
        assertEquals(run, guard.activeStopAction())
        guard.update(run, AgentRunState.PAUSED)
        assertNull(guard.activeStopAction())
    }

    private fun run(runId: String = "run-1", epoch: Long = 7L): AgentStopAction =
        AgentStopAction(runId = runId, epoch = epoch)

    private class CountingLease(
        private val throwOnRelease: Boolean = false
    ) : AgentProcessProtectionLease {
        var releaseCount = 0

        override fun release() {
            releaseCount++
            if (throwOnRelease) throw IllegalStateException("fake registry failure")
        }
    }

    private class FakeLeaseSource : AgentForegroundLeaseSource {
        var acquireCalls = 0
        var failNext = false
        var throwNext = false
        var failReleaseNext = false
        val leases = mutableListOf<AgentForegroundLease>()
        val delegates = mutableListOf<CountingLease>()

        override fun acquire(correlationId: String): AgentForegroundLeaseResult {
            acquireCalls++
            if (throwNext) {
                throwNext = false
                throw SecurityException("foreground denied")
            }
            if (failNext) {
                failNext = false
                return AgentForegroundLeaseResult.Unavailable(
                    platformError(
                        code = AgentPlatformErrorCodes.FOREGROUND_SERVICE_UNAVAILABLE,
                        correlationId = correlationId,
                        causeRef = "fake_start_failed"
                    )
                )
            }
            val delegate = CountingLease(throwOnRelease = failReleaseNext)
            failReleaseNext = false
            val lease = AgentForegroundLease.acquire(
                correlationId = correlationId,
                starter = AgentProcessProtectionStarter { delegate }
            ) as AgentForegroundLeaseResult.Acquired
            delegates += delegate
            leases += lease.lease
            return lease
        }
    }
}
