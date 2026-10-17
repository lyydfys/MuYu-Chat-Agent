package com.muyuchat.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistProgressTest {
    @Test
    fun isActiveTracksInFlightSerializationOnly() {
        assertTrue(PersistProgress(PersistStage.ENCODING, 0, 4096).isActive)
        assertTrue(PersistProgress(PersistStage.WRITING, 128, 4096).isActive)
        assertFalse(PersistProgress(PersistStage.IDLE, 0, 0).isActive)
        assertFalse(PersistProgress(PersistStage.DONE, 4096, 4096).isActive)
    }

    @Test
    fun persistEventCarriesStageAndByteCounts() {
        // Declared as the sealed supertype so the `is` discrimination below can actually fail
        // (typing it as GenerateEvent.Persist made the assertion always true).
        val event: GenerateEvent = GenerateEvent.Persist(
            PersistProgress(PersistStage.WRITING, 2L * 1024 * 1024, 8L * 1024 * 1024)
        )
        assertTrue("persist events must be reported as GenerateEvent.Persist", event is GenerateEvent.Persist)
        val persist = event as GenerateEvent.Persist
        assertEquals(PersistStage.WRITING, persist.progress.stage)
        assertEquals(2L * 1024 * 1024, persist.progress.writtenBytes)
        assertEquals(8L * 1024 * 1024, persist.progress.totalBytes)
        assertTrue(persist.progress.isActive)
    }
}
