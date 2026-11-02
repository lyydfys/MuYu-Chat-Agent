package com.muyuchat.mca.video.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteComfyCancelPolicyTest {

    @Test
    fun targetedInterruptRequiresPromptAndServerCapability() {
        assertTrue(RemoteComfyCancelPlan.plan("p1", allowTargetedCancel = true).targetedInterrupt)
        assertFalse(RemoteComfyCancelPlan.plan("p1", allowTargetedCancel = false).targetedInterrupt)
        assertFalse(RemoteComfyCancelPlan.plan(null, allowTargetedCancel = true).targetedInterrupt)
    }

    @Test
    fun queueDeleteRequiresKnownPrompt() {
        assertTrue(RemoteComfyCancelPlan.plan("p1", allowTargetedCancel = false).deleteFromQueue)
        assertFalse(RemoteComfyCancelPlan.plan(null, allowTargetedCancel = true).deleteFromQueue)
    }

    @Test
    fun localStopDetailNeverClaimsRemoteCancellation() {
        assertTrue(
            RemoteComfyCancelPlan.plan("p1", allowTargetedCancel = false)
                .localStopDetail
                .contains("远端可能仍在运行"),
        )
        assertTrue(
            RemoteComfyCancelPlan.plan("p1", allowTargetedCancel = true)
                .localStopDetail
                .contains("远端停止未确认"),
        )
        assertTrue(
            RemoteComfyCancelPlan.plan(null, allowTargetedCancel = false)
                .localStopDetail
                .contains("提交结果未知"),
        )
    }
}
