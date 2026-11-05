package com.muyuchat.core.dsh.runtime.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The emergency-recovery decision must be provable without a device: it is the only place that
 * decides whether the app moves a user's DSH home aside, and a wrong answer destroys state.
 */
class DshEmergencyRecoveryTest {

    private fun facts(
        code: String? = null,
        packaged: Boolean = true,
        installed: Boolean = true,
        seeded: Boolean = true,
        closure: Boolean = false,
        requested: Boolean = false
    ) = DshRecoveryFacts(
        failureCode = code,
        packagedPayloadUsable = packaged,
        installedPayloadUsable = installed,
        homeSeeded = seeded,
        closureCommitted = closure,
        userRequestedRecovery = requested
    )

    @Test
    fun `a config failure resets the home instead of reinstalling the payload`() {
        val plan = DshEmergencyRecovery.plan(facts(code = "CONFIG_INVALID"))
        assertEquals(listOf(DshRecoveryStep.RESET_HOME), plan.steps)
        assertTrue(plan.actionable)
        assertTrue("the reason must name the failure", plan.reason.contains("CONFIG_INVALID"))
        assertTrue(DshEmergencyRecovery.requiresConfirmation(plan))
    }

    @Test
    fun `a payload failure reinstalls the packaged payload and leaves the home alone`() {
        val plan = DshEmergencyRecovery.plan(facts(code = "PAYLOAD_HASH_MISMATCH"))
        assertEquals(listOf(DshRecoveryStep.REINSTALL_PAYLOAD), plan.steps)
        assertFalse(DshEmergencyRecovery.requiresConfirmation(plan))
    }

    @Test
    fun `an unseeded home resets only when the user asked for recovery`() {
        assertEquals(
            listOf(DshRecoveryStep.REPORT_ONLY),
            DshEmergencyRecovery.plan(facts(seeded = false)).steps
        )
        assertEquals(
            listOf(DshRecoveryStep.RESET_HOME),
            DshEmergencyRecovery.plan(facts(seeded = false, requested = true)).steps
        )
    }

    @Test
    fun `a committed closure is abandoned only as a last resort`() {
        // No other failure: falling back is the single sensible step.
        assertEquals(
            listOf(DshRecoveryStep.FALL_BACK_TO_PACKAGED_PAYLOAD),
            DshEmergencyRecovery.plan(facts(closure = true)).steps
        )
        // A concrete payload failure is repaired first; the closure step is not stacked on top.
        assertEquals(
            listOf(DshRecoveryStep.REINSTALL_PAYLOAD),
            DshEmergencyRecovery.plan(facts(code = "PAYLOAD_MISSING", closure = true)).steps
        )
    }

    @Test
    fun `a home failure is repaired even when a closure is committed`() {
        val plan = DshEmergencyRecovery.plan(facts(code = "HOME_UNWRITABLE", closure = true))
        assertEquals(listOf(DshRecoveryStep.RESET_HOME), plan.steps)
    }

    @Test
    fun `without a packaged payload nothing destructive is proposed`() {
        val plan = DshEmergencyRecovery.plan(
            facts(code = "PAYLOAD_MISSING", packaged = false, installed = false)
        )
        assertEquals(listOf(DshRecoveryStep.REPORT_ONLY), plan.steps)
        assertFalse(plan.actionable)
        assertTrue("the reason must say the build has no payload", plan.reason.contains("没有可用的内置负载"))
    }

    @Test
    fun `an unknown failure is reported rather than guessed`() {
        val plan = DshEmergencyRecovery.plan(facts(code = "SOMETHING_NEW"))
        assertEquals(listOf(DshRecoveryStep.REPORT_ONLY), plan.steps)
        assertTrue(plan.reason.contains("SOMETHING_NEW"))
    }

    @Test
    fun `a missing failure code still yields an actionable plan when facts are concrete`() {
        val plan = DshEmergencyRecovery.plan(facts(code = null, installed = false))
        assertEquals(listOf(DshRecoveryStep.REINSTALL_PAYLOAD), plan.steps)
    }

    @Test
    fun `no plan is ever empty`() {
        val combinations = listOf(
            facts(),
            facts(code = "CONFIG_INVALID"),
            facts(code = "PAYLOAD_MISSING"),
            facts(code = "PAYLOAD_MISSING", packaged = false),
            facts(code = "X", installed = false, packaged = false),
            facts(closure = true, packaged = false),
            facts(seeded = false, requested = true)
        )
        combinations.forEach { candidate ->
            val plan = DshEmergencyRecovery.plan(candidate)
            assertTrue("plan for $candidate must not be empty", plan.steps.isNotEmpty())
            assertTrue("plan for $candidate must carry a reason", plan.reason.isNotBlank())
        }
    }
}
