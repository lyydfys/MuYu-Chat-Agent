package com.muyuchat.mca.composition

import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessRuntimeSettlementTest {

    private var liveActivities = false
    private var closes = 0
    private val settlement = ProcessRuntimeSettlement(
        hasLiveActivities = { liveActivities },
        closeRoot = { closes += 1 }
    )

    @Test
    fun `overlapping workers keep the root until the last one finishes`() {
        settlement.beginBackgroundWork()
        settlement.beginBackgroundWork()

        settlement.finishBackgroundWork()
        assertEquals("the still-running worker keeps the root", 0, closes)

        settlement.finishBackgroundWork()
        assertEquals("the root settles once the last worker finishes", 1, closes)
    }

    @Test
    fun `a backgrounded Activity keeps the root after a worker finishes`() {
        liveActivities = true
        settlement.beginBackgroundWork()

        settlement.finishBackgroundWork()
        assertEquals("an existing Activity owns the root", 0, closes)

        liveActivities = false
        settlement.settleIfIdle()
        assertEquals("the last explicit Activity finish settles the root", 1, closes)
    }

    @Test
    fun `a finish without a matching begin never closes the root`() {
        settlement.finishBackgroundWork()
        assertEquals(0, closes)
    }

    @Test
    fun `a worker that starts after an idle settle obtains a fresh root`() {
        settlement.beginBackgroundWork()
        settlement.finishBackgroundWork()
        assertEquals(1, closes)

        settlement.beginBackgroundWork()
        settlement.finishBackgroundWork()
        assertEquals("the second isolated worker settles its own root", 2, closes)
    }

    @Test
    fun `explicit process exit settles even with active workers`() {
        liveActivities = true
        settlement.beginBackgroundWork()

        settlement.forceClose()

        assertEquals(1, closes)
    }
}
