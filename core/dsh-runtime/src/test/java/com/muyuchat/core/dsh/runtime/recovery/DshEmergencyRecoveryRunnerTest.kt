package com.muyuchat.core.dsh.runtime.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The runner is the only place that decides *what actually ran*. A wrong ordering or a missing
 * short-circuit would either repair the wrong thing or run a step on top of a failed one, so every
 * rule is pinned here with an ordering log.
 */
class DshEmergencyRecoveryRunnerTest {

    private class Recorder {
        val calls = CopyOnWriteArrayList<String>()
        var stopFailure: String? = null
        var resetFailure: String? = null
        var reinstallFailure: String? = null
        var fallbackFailure: String? = null

        fun runner() = DshEmergencyRecoveryRunner(
            stopHost = {
                calls += "stop"
                stopFailure
            },
            resetHome = {
                calls += "reset"
                resetFailure
            },
            reinstallPayload = {
                calls += "reinstall"
                reinstallFailure
            },
            fallBackToPackagedPayload = {
                calls += "fallback"
                fallbackFailure
            }
        )
    }

    private fun plan(vararg steps: DshRecoveryStep, reason: String = "test") =
        DshRecoveryPlan(steps = steps.toList(), reason = reason)

    @Test
    fun `steps run in plan order after the host is stopped`() {
        val recorder = Recorder()
        val outcome = recorder.runner().run(
            plan(DshRecoveryStep.RESET_HOME, DshRecoveryStep.REINSTALL_PAYLOAD)
        )

        assertEquals(listOf("stop", "reset", "reinstall"), recorder.calls)
        assertTrue(outcome.succeeded)
        assertNull(outcome.stoppedAt)
        assertEquals(2, outcome.steps.size)
    }

    @Test
    fun `the first failure stops the plan`() {
        val recorder = Recorder().apply { resetFailure = "home busy" }
        val outcome = recorder.runner().run(
            plan(DshRecoveryStep.RESET_HOME, DshRecoveryStep.REINSTALL_PAYLOAD)
        )

        assertEquals(listOf("stop", "reset"), recorder.calls)
        assertFalse(outcome.succeeded)
        assertEquals(DshRecoveryStep.RESET_HOME, outcome.stoppedAt)
        assertTrue(outcome.summary().contains("home busy"))
    }

    @Test
    fun `a host that will not stop means no file is touched`() {
        val recorder = Recorder().apply { stopFailure = "still running" }
        val outcome = recorder.runner().run(plan(DshRecoveryStep.REINSTALL_PAYLOAD))

        assertEquals(listOf("stop"), recorder.calls)
        assertFalse(outcome.succeeded)
        assertTrue(outcome.summary().contains("still running"))
    }

    @Test
    fun `a report-only plan executes nothing and is not a success`() {
        val recorder = Recorder()
        val outcome = recorder.runner().run(plan(DshRecoveryStep.REPORT_ONLY))

        assertTrue("no stop is needed when nothing is repaired", recorder.calls.isEmpty())
        assertFalse(outcome.succeeded)
        assertEquals(1, outcome.steps.size)
    }

    @Test
    fun `a throwing effect is reported instead of crashing the caller`() {
        val runner = DshEmergencyRecoveryRunner(
            stopHost = { null },
            resetHome = { error("boom") },
            reinstallPayload = { null },
            fallBackToPackagedPayload = { null }
        )
        val outcome = runner.run(plan(DshRecoveryStep.RESET_HOME))

        assertFalse(outcome.succeeded)
        assertEquals(DshRecoveryStep.RESET_HOME, outcome.stoppedAt)
        assertTrue(outcome.summary().contains("boom"))
    }

    @Test
    fun `planner and runner agree on a real failure code`() {
        val plan = DshEmergencyRecovery.plan(
            DshRecoveryFacts(failureCode = "PAYLOAD_HASH_MISMATCH")
        )
        val recorder = Recorder()
        val outcome = recorder.runner().run(plan)

        assertEquals(listOf("stop", "reinstall"), recorder.calls)
        assertTrue(outcome.succeeded)
    }

    @Test
    fun `the summary never leaks a path`() {
        val recorder = Recorder().apply { reinstallFailure = "ENOSPC" }
        val outcome = recorder.runner().run(plan(DshRecoveryStep.REINSTALL_PAYLOAD))
        val summary = outcome.summary()
        assertTrue(summary.contains("REINSTALL_PAYLOAD"))
        assertFalse(summary.contains("/"))
    }
}
