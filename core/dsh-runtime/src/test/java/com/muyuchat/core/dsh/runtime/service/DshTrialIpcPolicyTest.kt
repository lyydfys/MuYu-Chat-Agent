package com.muyuchat.core.dsh.runtime.service

import com.muyuchat.core.dsh.contract.DshTrialPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The app/runtime-owner trial protocol rules that must hold without a Binder round-trip:
 * a missing or foreign phase is never STOPPED, and a detail is bounded, printable and path-shaped
 * only when the owner put a path there (it never does).
 */
class DshTrialIpcPolicyTest {

    @Test
    fun aMissingOrUnknownPhaseIsUnknownNeverStopped() {
        assertEquals(DshTrialPhase.UNKNOWN, DshRuntimeIpc.trialPhaseFrom(null))
        assertEquals(DshTrialPhase.UNKNOWN, DshRuntimeIpc.trialPhaseFrom(""))
        assertEquals(DshTrialPhase.UNKNOWN, DshRuntimeIpc.trialPhaseFrom("stopped"))
        assertEquals(DshTrialPhase.UNKNOWN, DshRuntimeIpc.trialPhaseFrom("STOPPED "))
        assertEquals(DshTrialPhase.UNKNOWN, DshRuntimeIpc.trialPhaseFrom("SOMETHING_ELSE"))
        assertEquals(DshTrialPhase.RUNNING, DshRuntimeIpc.trialPhaseFrom("RUNNING"))
        assertEquals(DshTrialPhase.FAILED, DshRuntimeIpc.trialPhaseFrom("FAILED"))
        assertEquals(DshTrialPhase.STOPPED, DshRuntimeIpc.trialPhaseFrom("STOPPED"))
    }

    @Test
    fun aDetailIsBoundedAndPrintable() {
        val long = "x".repeat(500)
        val sanitized = DshRuntimeIpc.sanitizeTrialDetail(long)
        assertEquals(DshRuntimeIpc.TRIAL_DETAIL_MAX_CHARS, sanitized.length)

        val control = DshRuntimeIpc.sanitizeTrialDetail("a\u0000b\nc\td")
        assertFalse(control.contains('\u0000'))
        assertFalse(control.contains('\n'))
        assertFalse(control.contains('\t'))
        assertEquals("abcd", control)

        assertEquals("", DshRuntimeIpc.sanitizeTrialDetail(null))
    }

    @Test
    fun theClientNeverTurnsAFailureIntoStopped() {
        assertEquals(DshTrialPhase.UNKNOWN, DshTrialClientPolicy.unavailable().phase)
        assertEquals(DshTrialPhase.UNKNOWN, DshTrialClientPolicy.unknown("timeout").phase)
        // A null reply (no messenger, Binder loss, timeout) is UNKNOWN, not STOPPED.
        assertEquals(DshTrialPhase.UNKNOWN, DshTrialClientPolicy.fromReply(null).phase)
    }
}
