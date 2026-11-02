package com.muyuchat.core.dsh.maintenance

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Startup recovery must return a typed verdict. A blocked sweep may not be rounded to "recovered",
 * because the caller would then start a host while an interrupted transaction may still own it.
 */
class StartupOutcomeTest {

    private val roots = ArrayList<File>()

    @After
    fun tearDown() {
        roots.forEach(TestSupport::deleteTree)
    }

    private fun newOwner(
        trialRecovery: () -> DshTrialRecovery = { DshTrialRecovery.None }
    ): DshMaintenanceOwner {
        val root = TestSupport.tempRoot("dsh-startup-outcome")
        roots.add(root)
        val delegate = TestSupport.FakeHost()
        val host = object : DshHostLifecyclePort by delegate {
            override fun recoverInterruptedTrial(): DshTrialRecovery = trialRecovery()
        }
        return DshMaintenanceOwner(
            dataRoot = root,
            admission = TestSupport.FakeAdmission(),
            probe = TestSupport.healthyProbe(),
            host = host,
            supportedAbis = setOf("arm64-v8a"),
            clock = { 1_000L }
        )
    }

    @Test
    fun aCleanSweepReturnsProceedWithNoFailure() {
        val outcome = newOwner().recoverOnStart()

        assertTrue(outcome is DshStartupOutcome.Proceed)
        assertNull(outcome.report.failure)
        assertTrue(outcome.report.phaseErrors.isEmpty())
    }

    @Test
    fun aThrowingTrialRecoveryBlocksTheStartWithATypedFailure() {
        val outcome = newOwner(trialRecovery = { throw IllegalStateException("journal unreadable") })
            .recoverOnStart()

        assertTrue(outcome is DshStartupOutcome.Blocked)
        val blocked = outcome as DshStartupOutcome.Blocked
        assertEquals(DshMaintenanceError.RECOVERY_REQUIRED, blocked.failure.code)
        assertTrue(blocked.report.phaseErrors.isNotEmpty())
        assertTrue(blocked.report.phaseErrors.first().detail.orEmpty().startsWith("trial="))
        // The exception type is recorded, never its message (which could carry a path).
        assertTrue(blocked.report.phaseErrors.first().detail.orEmpty().contains("IllegalStateException"))
    }

    @Test
    fun aTrialThatCannotBeReleasedBlocksTheStart() {
        val outcome = newOwner(trialRecovery = { DshTrialRecovery.RequiresAttention("survivors") })
            .recoverOnStart()

        assertTrue(outcome is DshStartupOutcome.Blocked)
        assertEquals(
            DshMaintenanceError.RECOVERY_REQUIRED,
            (outcome as DshStartupOutcome.Blocked).failure.code
        )
    }
}
