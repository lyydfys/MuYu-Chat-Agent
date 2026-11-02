package com.muyuchat.mca.agent.reliability

import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxEntry
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxStatus
import com.muyuchat.core.agent.runtime.journal.InMemoryAgentDispatchOutboxStore
import com.muyuchat.core.agent.runtime.run.AgentApprovedDispatch
import com.muyuchat.core.agent.runtime.run.AgentApprovedDispatchOffer
import com.muyuchat.core.agent.runtime.run.AgentLoopResume
import com.muyuchat.core.agent.runtime.run.AgentLoopTranscript
import com.muyuchat.core.agent.runtime.run.AgentResumeToolDecision
import com.muyuchat.core.agent.runtime.run.AgentToolAuthorizationBasis
import com.muyuchat.core.agent.runtime.run.AuthorizedToolCommand
import com.muyuchat.core.agent.runtime.run.InMemoryAgentApprovedDispatchHandoff
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N03 production bridge: the approval sink publishes the exact in-memory authorization to the single
 * consumer and wakes the loop; when no live loop exists it bypasses the outbox row so the owner's
 * inline fallback cannot be dispatched twice.
 */
class BackgroundLoopApprovedDispatchSinkOutboxTest {

    private val store = InMemoryAgentDispatchOutboxStore()
    private val ledger = TestDispatchLedger()
    private val handoff = InMemoryAgentApprovedDispatchHandoff()
    private var nowMillis: Long = 1_000L

    private fun sink(target: AgentOutboxWakeTarget) = BackgroundLoopApprovedDispatchSink(
        handoff = handoff,
        wakeTarget = target,
        outbox = store,
        clock = { nowMillis }
    )

    @Test
    fun aRunningLoopAcceptingTheWakeReceivesTheExactAuthorization() = runBlocking {
        val dispatch = dispatchFor("invocation-handoff")
        val target = FakeWakeTarget(running = true, wakeResults = listOf(AgentOutboxWakeResult.Accepted))

        val offer = sink(target).offer(dispatch)

        assertEquals(AgentApprovedDispatchOffer.HandedOff, offer)
        assertEquals(1, target.wakeCalls)
        assertEquals(dispatch, handoff.claim(dispatch.invocationId))
        assertEquals(
            "the dispatcher, not the approver, owns the outbox transition",
            AgentDispatchOutboxStatus.PENDING,
            store.read(AgentDispatchOutboxEntry.keyFor(dispatch.invocationId))!!.status
        )
    }

    @Test
    fun aCoalescedWakeStillHandsOffExactlyOnce() = runBlocking {
        val dispatch = dispatchFor("invocation-coalesced")
        val target = FakeWakeTarget(running = true, wakeResults = listOf(AgentOutboxWakeResult.Coalesced))

        val offer = sink(target).offer(dispatch)

        assertEquals(AgentApprovedDispatchOffer.HandedOff, offer)
        assertEquals(dispatch, handoff.claim(dispatch.invocationId))
    }

    @Test
    fun aStoppedLoopBypassesTheRowAndLeavesNothingClaimable() = runBlocking {
        val dispatch = dispatchFor("invocation-bypass")
        val target = FakeWakeTarget(running = false)

        val offer = sink(target).offer(dispatch)

        assertTrue(offer is AgentApprovedDispatchOffer.NotAccepted)
        assertEquals(0, target.wakeCalls)
        assertNull(handoff.claim(dispatch.invocationId))
        val outbox = store.read(AgentDispatchOutboxEntry.keyFor(dispatch.invocationId))!!
        assertEquals(AgentDispatchOutboxStatus.BYPASSED, outbox.status)
        assertEquals("in_process_dispatch_fallback", outbox.errorCode)

        val again = sink(target).offer(dispatch)
        assertTrue(again is AgentApprovedDispatchOffer.NotAccepted)
        assertEquals(
            "a bypassed row must never be re-opened",
            AgentDispatchOutboxStatus.BYPASSED,
            store.read(AgentDispatchOutboxEntry.keyFor(dispatch.invocationId))!!.status
        )
    }

    @Test
    fun aWakeThatFindsTheLoopStoppedDiscardsTheHandOffAndBypasses() = runBlocking {
        val dispatch = dispatchFor("invocation-race")
        val target = FakeWakeTarget(running = true, wakeResults = listOf(AgentOutboxWakeResult.NotRunning))

        val offer = sink(target).offer(dispatch)

        assertTrue(offer is AgentApprovedDispatchOffer.NotAccepted)
        assertNull(handoff.claim(dispatch.invocationId))
        assertEquals(
            AgentDispatchOutboxStatus.BYPASSED,
            store.read(AgentDispatchOutboxEntry.keyFor(dispatch.invocationId))!!.status
        )
    }

    private suspend fun dispatchFor(invocationId: String): AgentApprovedDispatch {
        val entry = ledger.seedApproved(invocationId = invocationId)
        store.enqueue(entry)
        val command = FrozenToolCommand(
            invocationId = invocationId,
            runId = entry.runId,
            stepId = entry.stepId,
            callOrdinal = entry.callOrdinal,
            toolKey = entry.toolKey,
            toolVersion = entry.toolVersion,
            schemaHash = "schema-hash",
            normalizedArgumentsJson = """{"path":"inputs/brief.txt"}""",
            argumentsHash = "a".repeat(64),
            resourceFingerprint = "resource-fingerprint",
            runEpoch = 1L,
            policyVersion = 1L,
            expiresAtMillis = 9_000_000L
        )
        return AgentApprovedDispatch(
            invocationId = invocationId,
            runId = entry.runId,
            runEpoch = 1L,
            resume = AgentLoopResume(
                transcript = AgentLoopTranscript(emptyList(), 0, 0),
                toolDecision = AgentResumeToolDecision.Authorized(
                    authorization = AuthorizedToolCommand(
                        command = command,
                        approvalId = "approval-$invocationId",
                        basis = AgentToolAuthorizationBasis.APPROVED_ONCE
                    ),
                    callHash = "call-hash"
                )
            )
        )
    }

    private class FakeWakeTarget(
        private val running: Boolean,
        private val wakeResults: List<AgentOutboxWakeResult> = emptyList()
    ) : AgentOutboxWakeTarget {
        var wakeCalls: Int = 0
            private set
        private val queued = ArrayDeque(wakeResults)

        override fun isRunning(): Boolean = running

        override fun wake(): AgentOutboxWakeResult {
            wakeCalls += 1
            return queued.removeFirstOrNull()
                ?: if (running) AgentOutboxWakeResult.Accepted else AgentOutboxWakeResult.NotRunning
        }
    }
}
