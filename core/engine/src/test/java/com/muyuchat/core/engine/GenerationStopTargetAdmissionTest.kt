package com.muyuchat.core.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F3-1 regression cover: the engine's single-generation stop target must be reclaimed when the
 * stream that owns it can never release it, while two genuinely concurrent streams stay rejected.
 *
 * Device evidence (APK 0e4e8d30…): after a successful stop ("已停止生成", worker idle, generation FGS
 * released) every later send failed with "another native generation already owns the stop target",
 * still 3/3 five minutes later, until the target was cleared by a lifecycle transition.
 */
class GenerationStopTargetAdmissionTest {

    @Test
    fun `a live collector keeps the single-owner guarantee`() {
        val live = Job()
        try {
            val decision = decideGenerationStopTargetAdmission(
                hasStopTarget = true,
                stopTargetOwner = live,
                stopRequestedTokenPresent = false,
                stopRequestedTokenBelongsToTarget = false
            )
            assertTrue("a live stream must still be refused", decision.rejected)
            assertEquals(OWNED_TARGET_REJECTION, decision.rejection)
            assertFalse(decision.reclaimTarget)
        } finally {
            live.cancel()
        }
    }

    @Test
    fun `a cancelled collector's target is reclaimed instead of blocking every later send`() {
        val stopped = Job()
        stopped.cancel()
        val decision = decideGenerationStopTargetAdmission(
            hasStopTarget = true,
            stopTargetOwner = stopped,
            stopRequestedTokenPresent = true,
            stopRequestedTokenBelongsToTarget = true
        )
        assertFalse("the stopped stream can never clear its token: reclaim it", decision.rejected)
        assertTrue(decision.reclaimTarget)
        assertTrue("the paired pending stop is dropped with its own target", decision.clearStopRequestedToken)
        assertNull(decision.rejection)
    }

    @Test
    fun `a completed collector is dead as well`() = runBlocking {
        val finished = launch { }
        finished.join()
        val decision = decideGenerationStopTargetAdmission(
            hasStopTarget = true,
            stopTargetOwner = finished,
            stopRequestedTokenPresent = true,
            stopRequestedTokenBelongsToTarget = true
        )
        assertFalse(decision.rejected)
        assertTrue(decision.reclaimTarget)
    }

    @Test
    fun `an unproven owner is never reclaimed`() {
        val decision = decideGenerationStopTargetAdmission(
            hasStopTarget = true,
            stopTargetOwner = null,
            stopRequestedTokenPresent = false,
            stopRequestedTokenBelongsToTarget = false
        )
        assertTrue("admitting a second concurrent stream is the worse failure", decision.rejected)
        assertEquals(OWNED_TARGET_REJECTION, decision.rejection)
    }

    @Test
    fun `a stop in flight for another stream is never dropped`() {
        val stopped = Job()
        stopped.cancel()
        val decision = decideGenerationStopTargetAdmission(
            hasStopTarget = true,
            stopTargetOwner = stopped,
            stopRequestedTokenPresent = true,
            stopRequestedTokenBelongsToTarget = false
        )
        assertTrue(decision.rejected)
        assertEquals(UNWINDING_STOP_REJECTION, decision.rejection)
        assertFalse(decision.reclaimTarget)
    }

    @Test
    fun `an idle engine admits the next stream`() {
        val decision = decideGenerationStopTargetAdmission(
            hasStopTarget = false,
            stopTargetOwner = null,
            stopRequestedTokenPresent = false,
            stopRequestedTokenBelongsToTarget = false
        )
        assertFalse(decision.rejected)
        assertFalse(decision.reclaimTarget)
    }

    @Test
    fun `a pending stop without a target still reports the unwinding stream`() {
        val decision = decideGenerationStopTargetAdmission(
            hasStopTarget = false,
            stopTargetOwner = null,
            stopRequestedTokenPresent = true,
            stopRequestedTokenBelongsToTarget = false
        )
        assertTrue(decision.rejected)
        assertEquals(UNWINDING_STOP_REJECTION, decision.rejection)
    }

    @Test
    fun `a stopped stream never blocks the user forever`() = runBlocking {
        // Behavioural shape of the device failure: one stream owns the target, its collector is
        // cancelled by the user's stop, the target is never cleared, and the next send must still
        // be admitted.
        val owner = Job()
        var targetOwner: Job? = owner
        var stopRequestedForTarget = true
        var admitted = false

        owner.cancel()
        val decision = decideGenerationStopTargetAdmission(
            hasStopTarget = targetOwner != null,
            stopTargetOwner = targetOwner,
            stopRequestedTokenPresent = stopRequestedForTarget,
            stopRequestedTokenBelongsToTarget = true
        )
        if (!decision.rejected) {
            if (decision.reclaimTarget) {
                targetOwner = null
                if (decision.clearStopRequestedToken) stopRequestedForTarget = false
            }
            admitted = true
        }

        assertTrue("the send after a stop must be admitted", admitted)
        assertFalse(stopRequestedForTarget)
        val deferred = CompletableDeferred<Unit>()
        deferred.complete(Unit)
        assertTrue(deferred.isCompleted)
    }
}
