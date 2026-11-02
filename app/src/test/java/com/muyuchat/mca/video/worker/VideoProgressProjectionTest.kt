package com.muyuchat.mca.video.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression guard for the run-card defect observed on the f9d device at 13:40 on 2026-10-26:
 * a freshly submitted 1-step run showed `SAMPLE 825/1263` from the shared sd.cpp progress channel
 * before its own sampler started. Text-encoder/VAE progress must never be projected as SAMPLE.
 */
class VideoProgressProjectionTest {

    @Test
    fun acceptsRealSamplerStepsForTheFrozenStepCount() {
        assertEquals(0L, VideoProgressProjection.samplerStep("sampling", 0L, 1L, 1))
        assertEquals(1L, VideoProgressProjection.samplerStep("sampling", 1L, 1L, 1))
        assertEquals(0L, VideoProgressProjection.samplerStep("sampling", 0L, 2L, 2))
        assertEquals(2L, VideoProgressProjection.samplerStep("sampling", 2L, 2L, 2))
        assertEquals(33L, VideoProgressProjection.samplerStep("sampling", 33L, 33L, 33))
    }

    @Test
    fun rejectsTextEncoderProgressWithADifferentTotal() {
        // Real observation: 825/1263 appeared while the new run was still conditioning/encoding.
        assertNull(VideoProgressProjection.samplerStep("sampling", 825L, 1263L, 1))
        assertNull(VideoProgressProjection.samplerStep("sampling", 1263L, 1263L, 20))
    }

    @Test
    fun rejectsNonSamplingPhases() {
        for (phase in listOf("loading", "conditioning", "spooling", "done", "cancelling", "", null)) {
            assertNull(phase, VideoProgressProjection.samplerStep(phase, 0L, 1L, 1))
        }
    }

    @Test
    fun rejectsOutOfRangeSteps() {
        assertNull(VideoProgressProjection.samplerStep("sampling", -1L, 1L, 1))
        assertNull(VideoProgressProjection.samplerStep("sampling", 2L, 1L, 1))
    }

    @Test
    fun rejectsNonPositiveRequestedSteps() {
        assertNull(VideoProgressProjection.samplerStep("sampling", 0L, 0L, 0))
        assertNull(VideoProgressProjection.samplerStep("sampling", 0L, 1L, -1))
    }
}
