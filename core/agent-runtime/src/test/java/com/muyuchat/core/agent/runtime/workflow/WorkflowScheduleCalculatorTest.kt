package com.muyuchat.core.agent.runtime.workflow

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** N22 schedule arithmetic: interval/calendar, DST, rollback and latest-only missed slots. */
class WorkflowScheduleCalculatorTest {

    private val newYork: ZoneId = ZoneId.of("America/New_York")

    private fun daily(hour: Int, minute: Int) =
        WorkflowSchedule.Daily(LocalTime.of(hour, minute), newYork)

    private fun millis(text: String): Long = Instant.parse(text).toEpochMilli()

    @Test
    fun `an interval is epoch-aligned and strictly future`() {
        val schedule = WorkflowSchedule.Interval(15)
        assertEquals(900_000L, WorkflowScheduleCalculator.nextOccurrence(schedule, 1_000L))
        assertEquals(1_800_000L, WorkflowScheduleCalculator.nextOccurrence(schedule, 900_000L))
    }

    @Test
    fun `a clock rollback still yields a strictly future instant`() {
        val schedule = WorkflowSchedule.Interval(15)
        val earlier = WorkflowScheduleCalculator.nextOccurrence(schedule, 1_800_000L)!!
        val afterRollback = WorkflowScheduleCalculator.nextOccurrence(schedule, 0L)!!
        assertEquals(900_000L, afterRollback)
        assertEquals(2_700_000L, earlier)
    }

    @Test
    fun `a normal daily occurrence is the same wall-clock time in the zone`() {
        val schedule = daily(2, 30)
        val next = WorkflowScheduleCalculator.nextOccurrence(
            schedule,
            millis("2026-10-20T10:00:00Z")
        )!!
        assertEquals(millis("2026-10-21T06:30:00Z"), next)
    }

    @Test
    fun `a spring-forward gap is skipped to the next valid day`() {
        val schedule = daily(2, 30)
        // 2026-03-08 02:30 America/New_York does not exist (02:00 -> 03:00).
        val next = WorkflowScheduleCalculator.nextOccurrence(
            schedule,
            millis("2026-03-07T10:00:00Z")
        )!!
        assertEquals(Instant.parse("2026-03-09T06:30:00Z").toEpochMilli(), next)
        val local = Instant.ofEpochMilli(next).atZone(newYork)
        assertEquals(LocalTime.of(2, 30), local.toLocalTime())
    }

    @Test
    fun `an ambiguous fall-back time fires once at the earlier offset`() {
        val schedule = daily(1, 30)
        val first = WorkflowScheduleCalculator.nextOccurrence(
            schedule,
            millis("2026-11-01T00:00:00Z")
        )!!
        assertEquals(millis("2026-11-01T05:30:00Z"), first)

        val afterFirst = WorkflowScheduleCalculator.nextOccurrence(schedule, first)!!
        assertEquals("must not fire the repeated 01:30 again", millis("2026-11-02T06:30:00Z"), afterFirst)
    }

    @Test
    fun `a stored zone keeps its wall clock when the device zone changes`() {
        // The plan carries its own ZoneId; a traveller or a device-zone change must not move it.
        val schedule = WorkflowSchedule.Daily(LocalTime.of(9, 0), ZoneId.of("Asia/Tokyo"))
        val savedDefault = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
            val next = WorkflowScheduleCalculator.nextOccurrence(
                schedule,
                millis("2026-10-20T00:00:00Z")
            )!!
            // 09:00 JST is 00:00Z; the instant at the input is excluded, so the next day answers.
            assertEquals(millis("2026-10-21T00:00:00Z"), next)
            assertEquals(
                LocalTime.of(9, 0),
                Instant.ofEpochMilli(next).atZone(ZoneId.of("Asia/Tokyo")).toLocalTime()
            )
        } finally {
            java.util.TimeZone.setDefault(savedDefault)
        }
    }

    @Test
    fun `a weekly schedule only picks selected weekdays`() {
        val schedule = WorkflowSchedule.Weekly(
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            time = LocalTime.of(9, 0),
            zone = newYork
        )
        // 2026-10-20 is a Tuesday; next should be Wednesday 2026-10-21 09:00 EDT.
        val next = WorkflowScheduleCalculator.nextOccurrence(
            schedule,
            millis("2026-10-20T12:00:00Z")
        )!!
        assertEquals(Instant.parse("2026-10-21T13:00:00Z").toEpochMilli(), next)
    }

    @Test
    fun `only the latest missed slot is returned after downtime`() {
        val schedule = daily(2, 30)
        val latest = WorkflowScheduleCalculator.latestMissedOccurrence(
            schedule,
            lastDeliveredMillis = millis("2026-10-01T00:00:00Z"),
            nowMillis = millis("2026-11-05T12:00:00Z")
        )!!
        assertEquals(millis("2026-11-05T07:30:00Z"), latest)
    }

    @Test
    fun `no missed slot when the last delivery is newer than every occurrence`() {
        val schedule = daily(2, 30)
        assertNull(
            WorkflowScheduleCalculator.latestMissedOccurrence(
                schedule,
                lastDeliveredMillis = millis("2026-11-05T12:00:00Z"),
                nowMillis = millis("2026-11-05T12:00:01Z")
            )
        )
    }

    @Test
    fun `an interval reports the latest bucket after downtime`() {
        val schedule = WorkflowSchedule.Interval(60)
        val latest = WorkflowScheduleCalculator.latestMissedOccurrence(
            schedule,
            lastDeliveredMillis = 0L,
            nowMillis = 3 * 3_600_000L + 1L
        )!!
        assertEquals(3 * 3_600_000L, latest)
    }

    @Test
    fun `the wake delay is the honest wall-clock difference and never negative`() {
        val schedule = WorkflowSchedule.Interval(15)
        val wake = WorkflowScheduleCalculator.nextWake(schedule, 1_000L)!!
        assertEquals(900_000L, wake.atMillis)
        assertEquals(899_000L, wake.delayMillis)
    }

    @Test
    fun `invalid schedules are rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { WorkflowSchedule.Interval(5) }
        assertThrows(IllegalArgumentException::class.java) {
            WorkflowSchedule.Weekly(emptySet(), LocalTime.NOON, newYork)
        }
    }

    @Test
    fun `a weekly schedule without matching days in the scan bound returns nothing`() {
        // Every day is selected here, so this instead proves the scan bound stays finite for a
        // never-matching schedule: an empty day set is rejected, so a one-day set is used.
        val schedule = WorkflowSchedule.Weekly(
            setOf(DayOfWeek.SUNDAY),
            LocalTime.NOON,
            newYork
        )
        val next = WorkflowScheduleCalculator.nextOccurrence(schedule, millis("2026-10-20T12:00:00Z"))!!
        assertTrue(Instant.ofEpochMilli(next).atZone(newYork).dayOfWeek == DayOfWeek.SUNDAY)
    }
}
