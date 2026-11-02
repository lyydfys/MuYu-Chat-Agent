package com.muyuchat.core.dsh.vscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grant bookkeeping of the screen-capture projection: ttl, single owner, revoke. */
class DshVscreenCaptureLedgerTest {

    @Test
    fun anUnusedGrantExpires() {
        val ledger = DshVscreenCaptureLedger(ttlMillis = 1_000L)
        assertEquals(1L, ledger.grant(nowMillis = 10_000L))
        assertTrue(ledger.isGranted(10_500L))
        assertFalse("an unused consent must not be replayable much later", ledger.isGranted(12_000L))
        assertEquals(1L, ledger.revoke())
        assertNull(ledger.revoke())
        assertFalse(ledger.isGranted(10_000L))
        assertEquals(2L, ledger.grant(20_000L))
    }

    @Test
    fun aCreatedDisplayKeepsTheGrantValidPastTheTtl() {
        val ledger = DshVscreenCaptureLedger(ttlMillis = 1_000L)
        ledger.grant(nowMillis = 0L)
        assertEquals(1, ledger.recordDisplayCreated())
        assertEquals(2, ledger.recordDisplayCreated())
        assertTrue("a display in use must not lose its projection to the idle ttl", ledger.isGranted(10_000_000L))
        ledger.revoke()
        assertEquals(0, ledger.createdDisplayCount())
        assertFalse(ledger.isGranted(1L))
    }

    @Test
    fun recordingACreationWithoutAGrantIsIgnored() {
        val ledger = DshVscreenCaptureLedger()
        assertEquals(0, ledger.recordDisplayCreated())
        assertNull(ledger.activeGeneration())
        assertEquals(0, ledger.createdDisplayCount())
    }
}
