package com.muyuchat.feature.dsh.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the truthfulness rules of the host-side first-frame policy: no error page, no token
 * URL and no second report can become the generation's FIRST_FRAME_RENDERED observation. The
 * epoch/runtime-READY half of the gate is covered by `DshWorkspaceViewModelTest`.
 */
class DshFirstFrameGateTest {

    @Test
    fun commitVisibleReportsTheFirstFrameExactlyOncePerGeneration() {
        val gate = DshFirstFrameGate()
        gate.onPageStarted()

        assertTrue(gate.onPageCommitVisible())
        assertFalse(
            "a later navigation in the same generation must not report again",
            gate.onPageCommitVisible()
        )
        assertFalse(gate.onPageFinished(sameOrigin = true, containsLaunchToken = false))
    }

    @Test
    fun mainFrameFailureSuppressesTheVisibleErrorPage() {
        val gate = DshFirstFrameGate()
        gate.onPageStarted()
        gate.onMainFrameFailed()

        assertFalse("the visible surface is an error page, not the official page", gate.onPageCommitVisible())
        assertFalse(gate.onPageFinished(sameOrigin = true, containsLaunchToken = false))
    }

    @Test
    fun aRetryAfterFailureCanReportTheRecoveredPage() {
        val gate = DshFirstFrameGate()
        gate.onPageStarted()
        gate.onMainFrameFailed()
        assertFalse(gate.onPageCommitVisible())

        gate.onPageStarted()
        assertTrue(gate.onPageCommitVisible())
    }

    @Test
    fun cleanSameOriginFinishIsAFallbackOnlyWhenCommitVisibleDidNotReport() {
        val gate = DshFirstFrameGate()
        gate.onPageStarted()

        assertTrue(gate.onPageFinished(sameOrigin = true, containsLaunchToken = false))
        assertFalse(gate.onPageFinished(sameOrigin = true, containsLaunchToken = false))
    }

    @Test
    fun tokenOrForeignFinishIsNeverTheFirstFrame() {
        val gate = DshFirstFrameGate()
        gate.onPageStarted()

        assertFalse(gate.onPageFinished(sameOrigin = true, containsLaunchToken = true))
        assertFalse(gate.onPageFinished(sameOrigin = false, containsLaunchToken = false))
        assertTrue("a rejected finish does not consume the observation", gate.onPageFinished(true, false))
    }

    @Test
    fun aLaterFailureCannotRetractAnAcceptedFirstFrame() {
        val gate = DshFirstFrameGate()
        gate.onPageStarted()
        assertTrue(gate.onPageCommitVisible())

        gate.onMainFrameFailed()
        assertFalse(gate.onPageCommitVisible())
    }

    @Test
    fun resetAllowsTheNextGenerationOnTheSameWebViewToReportAgain() {
        val gate = DshFirstFrameGate()
        gate.onPageStarted()
        assertTrue(gate.onPageCommitVisible())
        assertFalse("the same generation is latched", gate.onPageCommitVisible())

        gate.reset()
        gate.onPageStarted()
        assertTrue(
            "a runtime restart reloads the composed WebView; the new generation must be observable",
            gate.onPageCommitVisible()
        )
    }

    @Test
    fun resetAlsoClearsAFailedMainFrame() {
        val gate = DshFirstFrameGate()
        gate.onPageStarted()
        gate.onMainFrameFailed()
        assertFalse(gate.onPageCommitVisible())

        gate.reset()
        gate.onPageStarted()
        assertTrue(gate.onPageCommitVisible())
    }
}
