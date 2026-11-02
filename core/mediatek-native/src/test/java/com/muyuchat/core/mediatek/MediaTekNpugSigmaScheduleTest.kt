package com.muyuchat.core.mediatek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTekNpugSigmaScheduleTest {
    @Test
    fun shiftedFlowMatchingStartsAtOneEndsAtZeroAndDescends() {
        val values = MediaTekNpugSigmaSchedules.values(
            MediaTekNpugSigmaSchedule.SHIFTED_FLOW_MATCHING_3T,
            steps = 6,
        )

        assertEquals(7, values.size)
        assertEquals(1.0, values.first(), 1e-12)
        assertEquals(0.0, values.last(), 1e-12)
        assertTrue(values.zipWithNext().all { (left, right) -> left > right })
        assertTrue(values.all { it.isFinite() })
    }

    @Test
    fun formulaMatchesTheNativeReceipt() {
        assertEquals(
            listOf(1.0, 0.75, 0.0),
            MediaTekNpugSigmaSchedules.values(MediaTekNpugSigmaSchedule.SHIFTED_FLOW_MATCHING_3T, 2),
        )
    }

    @Test
    fun invalidStepCountsAreRejected() {
        assertTrue(runCatching {
            MediaTekNpugSigmaSchedules.values(MediaTekNpugSigmaSchedule.SHIFTED_FLOW_MATCHING_3T, 0)
        }.isFailure)
        assertTrue(runCatching {
            MediaTekNpugSigmaSchedules.values(MediaTekNpugSigmaSchedule.SHIFTED_FLOW_MATCHING_3T, 65)
        }.isFailure)
    }
}
