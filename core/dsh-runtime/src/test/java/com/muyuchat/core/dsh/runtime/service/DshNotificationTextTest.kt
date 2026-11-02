package com.muyuchat.core.dsh.runtime.service

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshRuntimeFailure
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshNotificationTextTest {

    private fun snapshot(
        phase: DshRuntimePhase,
        code: DshFailureCode? = null,
        retryable: Boolean = true
    ) = DshRuntimeSnapshot(
        phase = phase,
        epoch = 3L,
        origin = if (phase == DshRuntimePhase.READY) "http://127.0.0.1:4321" else null,
        failure = code?.let {
            DshRuntimeFailure(code = it, message = "test failure", retryable = retryable)
        },
        changedAtMillis = 100L
    )

    @Test
    fun stopTimeoutDoesNotClaimStoppedAndAsksRetry() {
        // The owner's markStopTimeout() publishes retryable=false, but the notification still
        // offers a stop retry: the fence is visible and the user may call stop again.
        val text = DshNotificationText.forSnapshot(
            snapshot(
                DshRuntimePhase.FAILED,
                DshFailureCode.STOP_TIMEOUT,
                retryable = false
            )
        )
        assertFalse(text.contains("已停止"))
        assertTrue(text.contains("未确认"))
        assertTrue(text.contains("重试"))
        assertEquals("停止未确认，请重试", text)
    }

    @Test
    fun stoppedStillSaysStopped() {
        val text = DshNotificationText.forSnapshot(snapshot(DshRuntimePhase.STOPPED))
        assertTrue(text.contains("已停止"))
        assertEquals("DSH 运行时已停止", text)
    }

    @Test
    fun otherFailureShowsUnavailableWithCodeAndNeverStopped() {
        val text = DshNotificationText.forSnapshot(
            snapshot(DshRuntimePhase.FAILED, DshFailureCode.INTERNAL)
        )
        assertTrue(text.contains("不可用"))
        assertTrue(text.contains("INTERNAL"))
        assertFalse(text.contains("已停止"))
        assertEquals("DSH 运行时不可用（INTERNAL）", text)
    }

    @Test
    fun failedWithoutFailureCodeFallsBackToFailedLabel() {
        val text = DshNotificationText.forSnapshot(snapshot(DshRuntimePhase.FAILED))
        assertTrue(text.contains("不可用"))
        assertEquals("DSH 运行时不可用（FAILED）", text)
    }

    @Test
    fun startingReadyAndStoppingKeepExistingSemantics() {
        assertEquals(
            "正在启动本机 DSH 运行时…",
            DshNotificationText.forSnapshot(snapshot(DshRuntimePhase.STARTING))
        )
        assertEquals(
            "DSH 运行时正在运行",
            DshNotificationText.forSnapshot(snapshot(DshRuntimePhase.READY))
        )
        assertEquals(
            "正在停止 DSH 运行时…",
            DshNotificationText.forSnapshot(snapshot(DshRuntimePhase.STOPPING))
        )
    }
}
