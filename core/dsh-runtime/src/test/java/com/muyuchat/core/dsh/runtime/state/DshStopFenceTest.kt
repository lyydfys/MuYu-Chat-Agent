package com.muyuchat.core.dsh.runtime.state

import com.muyuchat.core.dsh.runtime.process.DshStopOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared stop fence: an unavailable scan is never a clean result. */
class DshStopFenceTest {

    private val logs = mutableListOf<String>()

    private fun fence(
        alive: Set<Int> = emptySet(),
        orphans: List<Int>? = emptyList()
    ) = DshStopFence(
        isAlive = { it in alive },
        scan = { orphans },
        log = { logs += it }
    )

    @Test
    fun clearStartNeedsAPositiveEmptyScan() {
        val fence = fence()
        assertEquals(DshStopFence.Decision.Clear, fence.checkBeforeStart(fenceRaised = false))
    }

    @Test
    fun anUnavailableScanBlocksAStart() {
        val fence = fence(orphans = null)
        val decision = fence.checkBeforeStart(fenceRaised = false)
        assertTrue(decision is DshStopFence.Decision.Blocked)
        assertTrue((decision as DshStopFence.Decision.Blocked).reason.contains("orphan scan unavailable"))
    }

    @Test
    fun survivingOrphansAreRetainedAndFenced() {
        val fence = fence(alive = setOf(31), orphans = listOf(31))
        val decision = fence.checkBeforeStart(fenceRaised = false)
        assertTrue(decision is DshStopFence.Decision.Blocked)
        assertEquals(listOf(31), fence.pids)
    }

    @Test
    fun aRaisedFenceClearsOnlyWhenEverythingIsGone() {
        val fence = fence(alive = emptySet(), orphans = emptyList())
        fence.retain(listOf(11, 12))
        assertEquals(DshStopFence.Decision.ClearedFence, fence.checkBeforeStart(fenceRaised = true))
        assertTrue(fence.pids.isEmpty())

        val surviving = fence(alive = setOf(11), orphans = emptyList())
        surviving.retain(listOf(11))
        val decision = surviving.checkBeforeStart(fenceRaised = true)
        assertTrue(decision is DshStopFence.Decision.Blocked)
        assertEquals(listOf(11), surviving.pids)
    }

    @Test
    fun aRaisedFenceCannotBeClearedWithoutAScan() {
        val fence = fence(orphans = null)
        fence.retain(listOf(11))
        val decision = fence.checkBeforeStart(fenceRaised = true)
        assertTrue(decision is DshStopFence.Decision.Blocked)
    }

    @Test
    fun survivorsAfterMergesTheHostOutcomeWithTheScan() {
        val outcome = DshStopOutcome(
            stopped = false,
            escalated = true,
            fenceRequired = true,
            detail = "survivors",
            trackedPids = listOf(7, 8),
            survivingPids = listOf(8)
        )
        assertEquals(listOf(8, 9), fence(orphans = listOf(9)).survivorsAfter(outcome))
        assertNull(fence(orphans = null).survivorsAfter(outcome))
    }
}
