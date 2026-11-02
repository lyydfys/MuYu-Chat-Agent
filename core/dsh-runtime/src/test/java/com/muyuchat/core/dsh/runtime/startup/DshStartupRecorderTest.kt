package com.muyuchat.core.dsh.runtime.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DshStartupRecorderTest {

    private var now = 1_000L
    private val recorder = DshStartupRecorder { now++ }

    @Test
    fun timelineAdvancesForwardOnlyWithinOneGeneration() {
        recorder.begin(7L)
        assertTrue(recorder.record(7L, DshStartupStage.NODE_PROCESS_ALIVE))
        assertTrue(recorder.record(7L, DshStartupStage.PORT_LISTENING))
        assertFalse("duplicate stage", recorder.record(7L, DshStartupStage.PORT_LISTENING))
        assertTrue(recorder.record(7L, DshStartupStage.HTTP_AUTHENTICATED))
        assertFalse("backwards stage", recorder.record(7L, DshStartupStage.PORT_LISTENING))
        assertTrue(recorder.record(7L, DshStartupStage.PLUGINS_LOADED))

        val progress = recorder.progress()
        assertEquals(7L, progress.epoch)
        assertEquals(DshStartupStage.PLUGINS_LOADED, progress.stage)
        assertEquals(
            listOf(
                DshStartupStage.NODE_PROCESS_ALIVE,
                DshStartupStage.PORT_LISTENING,
                DshStartupStage.HTTP_AUTHENTICATED,
                DshStartupStage.PLUGINS_LOADED
            ),
            progress.reachedStages
        )
        assertFalse(progress.hasReached(DshStartupStage.FIRST_FRAME_RENDERED))
    }

    @Test
    fun firstFrameIsNeverReachableBeforePluginsLoaded() {
        recorder.begin(1L)
        assertFalse(
            "a frame cannot precede the client plugin surface",
            recorder.record(1L, DshStartupStage.FIRST_FRAME_RENDERED)
        )
        assertTrue(recorder.record(1L, DshStartupStage.PORT_LISTENING))
        assertTrue(recorder.record(1L, DshStartupStage.HTTP_AUTHENTICATED))
        assertFalse(recorder.record(1L, DshStartupStage.FIRST_FRAME_RENDERED))

        assertTrue(recorder.record(1L, DshStartupStage.PLUGINS_LOADED))
        assertTrue(recorder.record(1L, DshStartupStage.FIRST_FRAME_RENDERED))
        assertEquals(DshStartupStage.FIRST_FRAME_RENDERED, recorder.progress().stage)
    }

    @Test
    fun staleGenerationCannotWriteIntoTheCurrentTimeline() {
        recorder.begin(2L)
        assertFalse(recorder.record(1L, DshStartupStage.PORT_LISTENING))
        assertNull(recorder.progress().stage)

        recorder.begin(3L)
        assertTrue(recorder.record(3L, DshStartupStage.NODE_PROCESS_ALIVE))
        assertFalse(recorder.record(2L, DshStartupStage.PLUGINS_LOADED))
        assertEquals(3L, recorder.progress().epoch)
        assertEquals(listOf(DshStartupStage.NODE_PROCESS_ALIVE), recorder.progress().reachedStages)
    }

    @Test
    fun everyAcceptedStageCarriesItsGenerationAndClockTime() {
        recorder.begin(9L)
        recorder.record(9L, DshStartupStage.NODE_PROCESS_ALIVE)
        recorder.record(9L, DshStartupStage.PORT_LISTENING)
        val events = recorder.progress().events
        assertEquals(listOf(9L, 9L), events.map { it.epoch })
        // begin() consumes 1_000 for changedAtMillis; the two events follow.
        assertEquals(listOf(1_001L, 1_002L), events.map { it.atMillis })
    }

    @Test
    fun aGenerationMustBePositive() {
        try {
            recorder.begin(0L)
            fail("begin(0) must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("epoch"))
        }
    }
}
