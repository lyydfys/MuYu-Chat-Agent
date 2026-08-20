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
        val event = GenerateEvent.Persist(
            PersistProgress(PersistStage.WRITING, 2L * 1024 * 1024, 8L * 1024 * 1024)
        )
        assertTrue(event is GenerateEvent.Persist)
        assertEquals(PersistStage.WRITING, event.progress.stage)
        assertEquals(2L * 1024 * 1024, event.progress.writtenBytes)
        assertEquals(8L * 1024 * 1024, event.progress.totalBytes)
        assertTrue(event.progress.isActive)
    }
}
