package com.muyuchat.core.dsh.runtime.service

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshRuntimeFailure
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshServiceStopPolicyTest {

    private data class Case(
        val label: String,
        val snapshot: DshRuntimeSnapshot,
        val expected: Boolean
    )

    private fun snapshot(
        phase: DshRuntimePhase,
        code: DshFailureCode? = null
    ) = DshRuntimeSnapshot(
        phase = phase,
        epoch = 3L,
        failure = code?.let {
            // markStopTimeout() publishes retryable=false; the service-exit rule must still be
            // driven only by the phase, never by retryability.
            DshRuntimeFailure(
                code = it,
                message = "test failure",
                retryable = it != DshFailureCode.STOP_TIMEOUT
            )
        },
        changedAtMillis = 100L
    )

    @Test
    fun onlyStoppedPhaseAllowsServiceExit() {
        val cases = listOf(
            Case("STOPPED", snapshot(DshRuntimePhase.STOPPED), true),
            Case(
                "FAILED/STOP_TIMEOUT",
                snapshot(DshRuntimePhase.FAILED, DshFailureCode.STOP_TIMEOUT),
                false
            ),
            Case(
                "FAILED/INTERNAL",
                snapshot(DshRuntimePhase.FAILED, DshFailureCode.INTERNAL),
                false
            ),
            Case("STARTING", snapshot(DshRuntimePhase.STARTING), false),
            Case("READY", snapshot(DshRuntimePhase.READY), false),
            Case("STOPPING", snapshot(DshRuntimePhase.STOPPING), false)
        )

        for (case in cases) {
            val actual = DshServiceStopPolicy.canStopService(case.snapshot)
            if (case.expected) {
                assertTrue(case.label, actual)
            } else {
                assertFalse(case.label, actual)
            }
        }
    }
}
