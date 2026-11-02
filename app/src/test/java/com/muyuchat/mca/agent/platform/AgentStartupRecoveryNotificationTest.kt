package com.muyuchat.mca.agent.platform

import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Notification/intent routing and foreground-lease policy. The class name intentionally carries
 * both the recovery and notification slices so the single `-Tests '*Agent*Recovery*'` filter used
 * by agent-verify (which accepts one Gradle pattern) executes this suite together with
 * [AgentStartupRecoveryTest].
 */
class AgentStartupRecoveryNotificationTest {

    @Test
    fun codecRoundTripsRunIdAndEpoch() {
        val action = AgentStopAction(runId = "run-1", epoch = 7L)
        val extras = AgentStopActionCodec.encode(action)
        assertEquals("run-1", extras.runId)
        assertEquals(7L, extras.epoch)
        val decoded = AgentStopActionCodec.decode(extras.runId, extras.epoch)
        assertEquals(AgentStopActionDecodeResult.Decoded(action), decoded)
    }

    @Test
    fun codecRejectsMalformedIntentPayloads() {
        assertRejected(AgentStopActionCodec.decode(null, 7L))
        assertRejected(AgentStopActionCodec.decode("", 7L))
        assertRejected(AgentStopActionCodec.decode("run 1", 7L))
        assertRejected(AgentStopActionCodec.decode("run-1", null))
        assertRejected(AgentStopActionCodec.decode("run-1", 0L))
        assertRejected(AgentStopActionCodec.decode("run-1", -1L))
    }

    @Test
    fun routerAcceptsOnlyTheExactRunAndEpoch() {
        val active = AgentStopAction("run-1", 7L)
        val accepted = AgentRunStopRouter.route(
            AgentStopActionDecodeResult.Decoded(AgentStopAction("run-1", 7L)),
            active
        )
        assertEquals(AgentStopRouteResult.Accepted(active), accepted)
    }

    @Test
    fun staleEpochCannotStopTheReplacementRun() {
        val stale = AgentStopActionDecodeResult.Decoded(AgentStopAction("run-1", 6L))
        val result = AgentRunStopRouter.route(stale, AgentStopAction("run-1", 7L))
        assertEquals(
            AgentStopRouteResult.Rejected(AgentStopRejectionReason.STALE_EPOCH),
            result
        )
    }

    @Test
    fun actionForAnotherRunIsRejected() {
        val other = AgentStopActionDecodeResult.Decoded(AgentStopAction("run-2", 7L))
        val result = AgentRunStopRouter.route(other, AgentStopAction("run-1", 7L))
        assertEquals(
            AgentStopRouteResult.Rejected(AgentStopRejectionReason.DIFFERENT_RUN),
            result
        )
    }

    @Test
    fun actionWithoutActiveRunIsRejected() {
        val action = AgentStopActionDecodeResult.Decoded(AgentStopAction("run-1", 7L))
        assertEquals(
            AgentStopRouteResult.Rejected(AgentStopRejectionReason.NO_ACTIVE_RUN),
            AgentRunStopRouter.route(action, null)
        )
    }

    @Test
    fun malformedDecodeIsRejectedBeforeRouting() {
        assertEquals(
            AgentStopRouteResult.Rejected(AgentStopRejectionReason.MALFORMED_ACTION),
            AgentRunStopRouter.route(
                AgentStopActionDecodeResult.Rejected(AgentStopRejectionReason.MALFORMED_ACTION),
                AgentStopAction("run-1", 7L)
            )
        )
    }

    @Test
    fun notificationCopyIsGenericForEveryState() {
        assertEquals("MCA Agent", AgentRunNotificationText.forState(AgentRunState.RUNNING).title)
        assertEquals("Agent 正在执行任务", AgentRunNotificationText.forState(AgentRunState.RUNNING).text)
        assertEquals("Agent 等待授权", AgentRunNotificationText.forState(AgentRunState.WAITING_APPROVAL).text)
        assertEquals("Agent 正在等待", AgentRunNotificationText.forState(AgentRunState.WAITING_RESOURCE).text)
        assertEquals("Agent 正在等待", AgentRunNotificationText.forState(AgentRunState.WAITING_USER).text)
        assertEquals("Agent 已暂停", AgentRunNotificationText.forState(AgentRunState.PAUSED).text)
        assertEquals("Agent 已完成", AgentRunNotificationText.forState(AgentRunState.SUCCEEDED).text)
        assertEquals("Agent 已结束", AgentRunNotificationText.forState(AgentRunState.FAILED).text)
        for (state in AgentRunState.entries) {
            val content = AgentRunNotificationText.forState(state)
            assertFalse(content.text.contains("goal", ignoreCase = true))
            assertFalse(content.text.contains("prompt", ignoreCase = true))
            assertFalse(content.text.contains("file", ignoreCase = true))
            assertFalse(content.text.contains("reasoning", ignoreCase = true))
            assertFalse(content.text.contains("key", ignoreCase = true))
        }
    }

    @Test
    fun foregroundLeaseReleasesExactlyOnce() {
        val delegate = CountingLease()
        val lease = AgentForegroundLease.acquire(
            correlationId = "run-1",
            starter = AgentProcessProtectionStarter { delegate }
        ) as AgentForegroundLeaseResult.Acquired
        assertEquals(AgentForegroundLeaseRelease.Released, lease.lease.release())
        assertEquals(AgentForegroundLeaseRelease.AlreadyReleased, lease.lease.release())
        assertEquals(1, delegate.releaseCount)
        assertTrue(lease.lease.isReleased())
    }

    @Test
    fun failedForegroundStartBecomesTypedUnavailable() {
        val result = AgentForegroundLease.acquire(
            correlationId = "run-1",
            starter = AgentProcessProtectionStarter { null }
        )
        val unavailable = result as AgentForegroundLeaseResult.Unavailable
        assertEquals(AgentPlatformErrorCodes.FOREGROUND_SERVICE_UNAVAILABLE, unavailable.error.code)
        assertEquals(AgentPauseReason.FOREGROUND_SERVICE_UNAVAILABLE, unavailable.pauseReason)
        assertEquals("run-1", unavailable.error.correlationId)
    }

    @Test
    fun throwingForegroundStarterNeverThrowsToTheRunOwner() {
        val result = AgentForegroundLease.acquire(
            correlationId = "run-1",
            starter = AgentProcessProtectionStarter { throw SecurityException("denied") }
        )
        val unavailable = result as AgentForegroundLeaseResult.Unavailable
        assertEquals(AgentPlatformErrorCodes.FOREGROUND_SERVICE_UNAVAILABLE, unavailable.error.code)
        assertTrue(unavailable.error.causeRef!!.contains("SecurityException"))
    }

    @Test
    fun throwingReleaseIsStillExactlyOnce() {
        val delegate = object : AgentProcessProtectionLease {
            var count = 0
            override fun release() {
                count++
                throw IllegalStateException("registry gone")
            }
        }
        val lease = AgentForegroundLease.acquire(
            correlationId = "run-1",
            starter = AgentProcessProtectionStarter { delegate }
        ) as AgentForegroundLeaseResult.Acquired
        assertTrue(lease.lease.release() is AgentForegroundLeaseRelease.Failed)
        assertEquals(AgentForegroundLeaseRelease.AlreadyReleased, lease.lease.release())
        assertEquals(1, delegate.count)
    }

    @Test
    fun waitingStatesHoldNoForegroundLease() {
        for (state in listOf(
            AgentRunState.WAITING_APPROVAL,
            AgentRunState.WAITING_RESOURCE,
            AgentRunState.WAITING_USER
        )) {
            assertFalse(AgentForegroundLeasePolicy.shouldAcquire(state))
            assertFalse(AgentForegroundLeasePolicy.shouldRetain(state))
            assertTrue(AgentForegroundLeasePolicy.mustNotHoldWhileWaiting(state))
        }
    }

    @Test
    fun runningAcquiresAndStoppingRetainsUntilProof() {
        assertTrue(AgentForegroundLeasePolicy.shouldAcquire(AgentRunState.RUNNING))
        assertTrue(AgentForegroundLeasePolicy.shouldRetain(AgentRunState.RUNNING))
        assertFalse(AgentForegroundLeasePolicy.shouldAcquire(AgentRunState.STOPPING))
        assertTrue(AgentForegroundLeasePolicy.shouldRetain(AgentRunState.STOPPING))
        assertFalse(AgentForegroundLeasePolicy.shouldAcquire(AgentRunState.PAUSED))
        assertFalse(AgentForegroundLeasePolicy.shouldRetain(AgentRunState.PAUSED))
    }

    @Test
    fun foregroundKindIsTypedAndNotReusedForChat() {
        assertEquals("agent", AGENT_FOREGROUND_KIND)
        assertNotNull(AgentPlatformErrorCodes.FOREGROUND_SERVICE_UNAVAILABLE)
    }

    private fun assertRejected(result: AgentStopActionDecodeResult) {
        val rejected = result as AgentStopActionDecodeResult.Rejected
        assertEquals(AgentStopRejectionReason.MALFORMED_ACTION, rejected.reason)
    }

    private class CountingLease : AgentProcessProtectionLease {
        var releaseCount = 0
        override fun release() {
            releaseCount++
        }
    }
}
