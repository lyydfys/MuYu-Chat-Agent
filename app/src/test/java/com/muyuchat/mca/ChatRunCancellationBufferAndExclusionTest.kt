package com.muyuchat.mca

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1 contracts that were not covered yet: the buffer's fate around cancellation, exactly-once
 * foreground release (asserted by CALL COUNT, not by final state), and the mutual-exclusion
 * boundary of the run-identity mechanism.
 *
 * Every assertion is observable behaviour: return values, resulting ownership, call counts,
 * idempotence, side-effect presence. Nothing reads source text and nothing asserts that one
 * function called another.
 */
class ChatRunCancellationBufferAndExclusionTest {

    private class Buffer(
        runIdSource: () -> Long,
        initialSessionId: String? = "session-1",
        initialMessagePresent: Boolean = true
    ) {
        var clockMs = 0L
        val commits = mutableListOf<ChatStreamOutputBatch>()
        private val runId = runIdSource
        private val sessionId = initialSessionId
        private val messagePresent = initialMessagePresent

        fun accumulator(scope: CoroutineScope) = ChatStreamAccumulator(
            scope = scope,
            currentRunId = runId,
            activeSessionId = { this.sessionId },
            hasAssistantMessage = { messagePresent },
            commit = { batch, _ -> commits += batch },
            now = { clockMs }
        )
    }

    // ================================================= 1. buffer fate around cancellation

    @Test
    fun `flush after discard returns false and commits nothing`() = runBlocking {
        var runId = 7L
        val buffer = Buffer({ runId })
        val accumulator = buffer.accumulator(this)
        accumulator.append("dropped", generationRunId = runId)

        accumulator.discard(runId)

        assertFalse("a discarded buffer has nothing to publish", accumulator.flush(runId))
        assertTrue(buffer.commits.isEmpty())
    }

    @Test
    fun `discard then a bare flush also commits nothing`() = runBlocking {
        var runId = 7L
        val buffer = Buffer({ runId })
        val accumulator = buffer.accumulator(this)
        accumulator.append("dropped", generationRunId = runId)
        accumulator.discard(runId)

        assertFalse("discard emptied the buffer, so even an unscoped flush has nothing",
            accumulator.flush())
        assertTrue(buffer.commits.isEmpty())
    }

    @Test
    fun `a stale flush preserves the buffer instead of consuming it`() = runBlocking {
        var runId = 7L
        val buffer = Buffer({ runId })
        val accumulator = buffer.accumulator(this)
        accumulator.append("kept", generationRunId = 7L)

        runId = 8L
        assertFalse("a flush scoped to a superseded run must refuse", accumulator.flush(7L))
        assertTrue("and it must not have drained the buffer", buffer.commits.isEmpty())

        runId = 7L
        assertTrue("the buffer is still there once the run is current again",
            accumulator.flush(7L))
        assertEquals("kept", buffer.commits.single().content)
    }

    @Test
    fun `a bare flush ignores the generation entirely, even after a cancellation`() = runBlocking {
        // Observable contract (not a claim): flush() carries no run id, so it has no staleness
        // test at all. Callers that must not publish cancelled text use flush(runId) or discard.
        val ownership = ChatRunOwnership()
        val reservation = requireNotNull(ownership.reserveStartAndCancelSuperseded())
        assertTrue(ownership.activate(reservation, Job()))
        val buffer = Buffer({ ownership.currentRunId() })
        val accumulator = buffer.accumulator(this)
        accumulator.append("cancelled-text", generationRunId = reservation.runId)

        ownership.cancelCurrentAndCancelOwner()

        assertTrue("an unscoped flush publishes regardless of the epoch", accumulator.flush())
        assertEquals("cancelled-text", buffer.commits.single().content)
        delay(CHAT_STREAM_PUBLISH_INTERVAL_MS * 3)
        assertEquals("and the run's own scheduled publication stays silent", 1, buffer.commits.size)
    }

    // ================================================= 2. exactly-once release, by call count

    @Test
    fun `across repeated cancels exactly one owner is ever handed out`() {
        val ownership = ChatRunOwnership()
        val reservation = requireNotNull(ownership.reserveStartAndCancelSuperseded())
        val owner = Job()
        assertTrue(ownership.activate(reservation, owner))

        val handedOut = (1..5).count { ownership.cancelCurrentAndCancelOwner().owner != null }

        assertEquals("the resource must be released exactly once", 1, handedOut)
    }

    @Test
    fun `across repeated cancels exactly one local runtime stop is ever requested`() {
        val ownership = ChatRunOwnership()
        val reservation = requireNotNull(ownership.reserveStartAndCancelSuperseded())
        val owner = Job()
        assertTrue(ownership.activate(reservation, owner))
        assertTrue(ownership.markPhase(reservation.runId, owner, UiGenerationRuntimePhase.LOCAL_ACTIVE))

        val stops = (1..5).count { ownership.cancelCurrentAndCancelOwner().stopLocalRuntime }

        assertEquals("the local runtime must be asked to stop exactly once", 1, stops)
        assertTrue("and the owner really was cancelled", owner.isCancelled)
    }

    @Test
    fun `no local runtime stop is ever requested from a non-local phase`() {
        for (phase in enumValues<UiGenerationRuntimePhase>()) {
            if (phase == UiGenerationRuntimePhase.LOCAL_ACTIVE) continue
            val ownership = ChatRunOwnership()
            val reservation = requireNotNull(ownership.reserveStartAndCancelSuperseded())
            val owner = Job()
            assertTrue(ownership.activate(reservation, owner))
            assertTrue(ownership.markPhase(reservation.runId, owner, phase))

            val stops = (1..3).count { ownership.cancelCurrentAndCancelOwner().stopLocalRuntime }

            assertEquals("phase $phase owns no local runtime", 0, stops)
        }
    }

    @Test
    fun `the owner is cancelled by the cancelling call`() {
        val ownership = ChatRunOwnership()
        val reservation = requireNotNull(ownership.reserveStartAndCancelSuperseded())
        val owner = Job()
        assertTrue(ownership.activate(reservation, owner))

        val cancellation = ownership.cancelCurrentAndCancelOwner()

        assertTrue(owner.isCancelled)
        assertSame("the cancelled resource is still reported to the caller",
            owner, cancellation.owner)
    }

    // ================================================= 3. mutual exclusion boundary

    @Test
    fun `one instance never holds two owners at once`() {
        val ownership = ChatRunOwnership()
        val first = requireNotNull(ownership.reserveStartAndCancelSuperseded())
        val firstOwner = Job()
        assertTrue(ownership.activate(first, firstOwner))

        val second = requireNotNull(ownership.reserveStartAndCancelSuperseded())
        val secondOwner = Job()
        assertTrue(ownership.activate(second, secondOwner))

        assertTrue("superseding cancels the previous run's job", firstOwner.isCancelled)
        assertFalse("the superseded owner can no longer drive the run",
            ownership.markPhase(first.runId, firstOwner, UiGenerationRuntimePhase.LOCAL_ACTIVE))
        assertFalse(ownership.isCurrent(first.runId))
        assertSame("only the newer owner is handed out",
            secondOwner, ownership.cancelCurrentAndCancelOwner().owner)
    }

    @Test
    fun `a cancel from one caller invalidates the run another caller activated`() {
        // Same instance == the same identity mechanism: whoever cancels, the run is invalidated.
        val shared = ChatRunOwnership()
        val uiReservation = requireNotNull(shared.reserveStartAndCancelSuperseded())
        val uiOwner = Job()
        assertTrue(shared.activate(uiReservation, uiOwner))

        val apiCancellation = shared.cancelCurrentAndCancelOwner()

        assertSame(uiOwner, apiCancellation.owner)
        assertTrue(uiOwner.isCancelled)
        assertFalse("the UI run is no longer current after the other caller cancelled",
            shared.isCurrent(uiReservation.runId))
        // A later reservation must get a fresh run id, never the cancelled one.
        val next = requireNotNull(shared.reserveStart())
        assertTrue(shared.activate(next, Job()))
        assertFalse("the cancelled run id is never handed out again",
            next.runId == uiReservation.runId)
    }

    @Test
    fun `separate instances do not exclude each other`() {
        // Reports the boundary: exclusion comes from SHARING one instance, not from the class.
        // Two instances each grant an owner at the same time -- by design (the class is a
        // per-owner lease), so any UI/API exclusion must come from the caller using one instance.
        val ui = ChatRunOwnership()
        val api = ChatRunOwnership()
        val uiOwner = Job()
        val apiOwner = Job()

        assertTrue(ui.activate(requireNotNull(ui.reserveStartAndCancelSuperseded()), uiOwner))
        assertTrue("a second instance grants its own owner", 
            api.activate(requireNotNull(api.reserveStartAndCancelSuperseded()), apiOwner))

        assertFalse(uiOwner.isCancelled)
        assertFalse(apiOwner.isCancelled)
        ui.cancelCurrentAndCancelOwner()
        assertTrue(uiOwner.isCancelled)
        assertFalse("cancelling one instance must not touch the other's run", apiOwner.isCancelled)
        assertTrue(api.isCurrent(api.currentRunId()))
    }
}
