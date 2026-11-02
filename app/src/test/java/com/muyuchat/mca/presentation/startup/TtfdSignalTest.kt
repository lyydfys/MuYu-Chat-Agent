package com.muyuchat.mca.presentation.startup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM contract for the TTFD signal: the state machine, the time arithmetic and — importantly —
 * what happens when one of the two observations is missing.
 *
 * No Android class is touched here: `TtfdSignal` takes an explicit origin, explicit timestamps and
 * an explicit log sink, so this test proves the same code the Activity runs.
 */
class TtfdSignalTest {
    private val origin = 10_000L
    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        TtfdSignal.reset()
        TtfdSignal.begin(origin) { line -> lines.add(line) }
    }

    @After
    fun tearDown() {
        TtfdSignal.reset()
    }

    @Test
    fun `measurement reports both moments as offsets from the process start`() {
        val measurement = TtfdMeasurement(origin)

        assertTrue(measurement.markFirstFrame(origin + 240L))
        assertTrue(measurement.markInputReady(actionable = true, atUptimeMs = origin + 1_530L))

        val sample = measurement.sample()
        assertEquals(240L, sample.firstFrameMs!!)
        assertEquals(1_530L, sample.inputReadyMs!!)
        assertEquals(1_530L, sample.ttfdMs!!)
        assertTrue(sample.drawnAndActionable)
    }

    @Test
    fun `the first mark of each moment wins and later marks never move it`() {
        val measurement = TtfdMeasurement(origin)

        assertTrue(measurement.markFirstFrame(origin + 100L))
        assertFalse(measurement.markFirstFrame(origin + 900L))
        assertTrue(measurement.markInputReady(actionable = true, atUptimeMs = origin + 700L))
        assertFalse(measurement.markInputReady(actionable = true, atUptimeMs = origin + 2_000L))

        assertEquals(100L, measurement.sample().firstFrameMs!!)
        assertEquals(700L, measurement.sample().inputReadyMs!!)
    }

    @Test
    fun `marks before the origin are rejected instead of becoming negative deltas`() {
        val measurement = TtfdMeasurement(origin)

        assertFalse(measurement.markFirstFrame(origin - 1L))
        assertFalse(measurement.markInputReady(actionable = true, atUptimeMs = origin - 1L))

        val sample = measurement.sample()
        assertNull(sample.firstFrameMs)
        assertNull(sample.inputReadyMs)
        assertNull(sample.ttfdMs)
        assertFalse(sample.drawnAndActionable)
    }

    @Test
    fun `a composer that is not actionable is not latched`() {
        val measurement = TtfdMeasurement(origin)

        assertFalse(measurement.markInputReady(actionable = false, atUptimeMs = origin + 50L))
        assertNull(measurement.sample().inputReadyMs)

        assertTrue(measurement.markInputReady(actionable = true, atUptimeMs = origin + 80L))
        assertEquals(80L, measurement.sample().inputReadyMs!!)
    }

    @Test
    fun `input readiness without a drawn frame is never a ttfd`() {
        val withoutFrame = TtfdMeasurement(origin)

        assertTrue(withoutFrame.markInputReady(actionable = true, atUptimeMs = origin + 400L))

        val sample = withoutFrame.sample()
        assertEquals(400L, sample.inputReadyMs!!)
        assertNull(sample.firstFrameMs)
        assertFalse(sample.drawnAndActionable)
        assertNull(sample.ttfdMs)
    }

    @Test
    fun `a drawn frame without input readiness is never a ttfd`() {
        val measurement = TtfdMeasurement(origin)

        assertTrue(measurement.markFirstFrame(origin + 300L))

        val sample = measurement.sample()
        assertEquals(300L, sample.firstFrameMs!!)
        assertNull(sample.inputReadyMs)
        assertFalse(sample.drawnAndActionable)
        assertNull(sample.ttfdMs)
    }

    @Test
    fun `the two moments may arrive in either order and still complete`() {
        val inputFirst = TtfdMeasurement(origin)
        inputFirst.markInputReady(actionable = true, atUptimeMs = origin + 900L)
        assertFalse(inputFirst.sample().drawnAndActionable)
        inputFirst.markFirstFrame(origin + 200L)
        assertEquals(900L, inputFirst.sample().ttfdMs!!)
        assertTrue(inputFirst.sample().drawnAndActionable)
    }

    @Test
    fun `a zero-elapsed actionable composer at the origin stays a valid measurement`() {
        val measurement = TtfdMeasurement(origin)

        assertTrue(measurement.markFirstFrame(origin))
        assertTrue(measurement.markInputReady(actionable = true, atUptimeMs = origin))

        assertEquals(0L, measurement.sample().ttfdMs!!)
        assertTrue(measurement.sample().drawnAndActionable)
    }

    @Test
    fun `the signal emits one first-frame line and one ttfd line at most`() {
        TtfdSignal.noteFirstFrame(origin + 120L)
        TtfdSignal.noteFirstFrame(origin + 140L)
        TtfdSignal.noteInputReady(actionable = true, atUptimeMs = origin + 800L)
        TtfdSignal.noteInputReady(actionable = true, atUptimeMs = origin + 900L)

        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("event=first-frame"))
        assertTrue(lines[1].startsWith("event=ttfd"))
        assertTrue(TtfdSignal.isComplete())
        assertEquals(800L, TtfdSignal.sample()?.ttfdMs!!)
    }

    @Test
    fun `the ttfd line carries every key the measurement script parses`() {
        TtfdSignal.noteFirstFrame(origin + 120L)
        TtfdSignal.noteInputReady(actionable = true, atUptimeMs = origin + 800L)

        val fields = fields(lines.last())
        assertEquals(
            setOf(
                "event",
                "ttfdMs",
                "firstFrameMs",
                "inputReadyMs",
                "drawnAndActionable",
                "origin",
            ),
            fields.keys,
        )
        assertEquals("ttfd", fields["event"])
        // TTFD is input-ready elapsed from the process start (800), not from the first frame (120).
        assertEquals("800", fields["ttfdMs"])
        assertEquals("120", fields["firstFrameMs"])
        assertEquals("800", fields["inputReadyMs"])
        assertEquals("true", fields["drawnAndActionable"])
        assertEquals("process-start", fields["origin"])
    }

    @Test
    fun `a composer without a drawn frame logs incomplete evidence and no ttfd`() {
        TtfdSignal.noteInputReady(actionable = true, atUptimeMs = origin + 500L)

        assertEquals(1, lines.size)
        val fields = fields(lines.single())
        assertEquals("input-ready", fields["event"])
        assertEquals("false", fields["drawnAndActionable"])
        assertFalse(lines.any { it.contains("event=ttfd") })
        assertNull(TtfdSignal.sample()?.ttfdMs)
    }

    @Test
    fun `a probe timeout never invents a ttfd`() {
        TtfdSignal.noteFirstFrame(origin + 250L)
        TtfdSignal.noteProbeTimeout()

        val fields = fields(lines.last())
        assertEquals("probe-timeout", fields["event"])
        assertEquals("250", fields["firstFrameMs"])
        assertEquals("-1", fields["inputReadyMs"])
        assertEquals("false", fields["drawnAndActionable"])
        assertFalse(lines.any { it.contains("event=ttfd") })
    }

    @Test
    fun `after a terminal outcome later observations cannot append a second result`() {
        TtfdSignal.noteFirstFrame(origin + 250L)
        TtfdSignal.noteInputReady(actionable = true, atUptimeMs = origin + 900L)
        val afterCompletion = lines.size

        TtfdSignal.noteFirstFrame(origin + 950L)
        TtfdSignal.noteProbeTimeout()

        assertEquals(afterCompletion, lines.size)
    }

    @Test
    fun `begin is idempotent so a recreated activity cannot move the origin`() {
        TtfdSignal.begin(origin + 5_000L) { lines.add("second sink") }
        TtfdSignal.noteFirstFrame(origin + 20L)

        assertEquals("20", fields(lines.single())["firstFrameMs"])
        assertFalse(lines.any { it.contains("second sink") })
    }

    @Test
    fun `log helpers never fabricate a ttfd for an incomplete sample`() {
        assertNull(TtfdLog.ttfdLine(TtfdSample(firstFrameMs = null, inputReadyMs = null)))
        assertNull(TtfdLog.ttfdLine(TtfdSample(firstFrameMs = 10L, inputReadyMs = null)))
        assertNull(TtfdLog.ttfdLine(TtfdSample(firstFrameMs = null, inputReadyMs = 10L)))

        val complete = TtfdLog.ttfdLine(TtfdSample(firstFrameMs = 10L, inputReadyMs = 90L))
        assertEquals("90", fields(complete ?: "missing")["ttfdMs"])
    }

    private fun fields(line: String): Map<String, String> =
        Regex("([A-Za-z][A-Za-z0-9]*)=(\\S+)")
            .findAll(line)
            .associate { match -> match.groupValues[1] to match.groupValues[2] }
}
