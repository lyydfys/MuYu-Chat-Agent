package com.muyuchat.core.agent.runtime.workflow

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * A declared workflow wake schedule (N22 base).
 *
 * This is deliberately *not* a cron engine: interval and calendar times only, both with explicit
 * time-zone semantics. `WorkManager` may delay a wake; callers must show the real delay, never claim
 * exact execution.
 */
sealed interface WorkflowSchedule {
    /** Fixed-rate interval, aligned to the Unix epoch in whole minutes. */
    data class Interval(val everyMinutes: Int) : WorkflowSchedule {
        init {
            require(everyMinutes in MIN_MINUTES..MAX_MINUTES) {
                "interval minutes must be within $MIN_MINUTES..$MAX_MINUTES"
            }
        }
    }

    /** One local wall-clock time per day in [zone]. */
    data class Daily(val time: LocalTime, val zone: ZoneId) : WorkflowSchedule

    /** One local wall-clock time on each selected weekday in [zone]. */
    data class Weekly(
        val days: Set<DayOfWeek>,
        val time: LocalTime,
        val zone: ZoneId
    ) : WorkflowSchedule {
        init {
            require(days.isNotEmpty()) { "a weekly schedule needs at least one day" }
        }
    }

    companion object {
        const val MIN_MINUTES = 15
        const val MAX_MINUTES = 7 * 24 * 60

        /** Bounds the catch-up scan so a long downtime cannot cause an unbounded walk. */
        const val MAX_CATCH_UP_DAYS = 370L
    }
}

/** One computed occurrence. [delayMillis] is the honest wall-clock delay, not a guarantee. */
data class WorkflowWake(
    val atMillis: Long,
    val delayMillis: Long
)

/**
 * Deterministic schedule arithmetic with explicit DST policy:
 *
 * - a local time that does not exist (spring-forward gap) is skipped to the next valid day;
 * - an ambiguous local time (fall-back overlap) fires **once**, at the earlier offset;
 * - every result is strictly after the input instant, so a clock rollback cannot loop or double-fire.
 *
 * Missed-slot policy is "latest only": after downtime at most the most recent occurrence is
 * returned, matching the reminder contract (a long stop does not replay every slot).
 */
object WorkflowScheduleCalculator {

    fun nextOccurrence(schedule: WorkflowSchedule, afterMillis: Long): Long? = when (schedule) {
        is WorkflowSchedule.Interval -> nextInterval(schedule, afterMillis)
        is WorkflowSchedule.Daily -> nextDaily(schedule, afterMillis, days = null)
        is WorkflowSchedule.Weekly -> nextDaily(
            WorkflowSchedule.Daily(schedule.time, schedule.zone),
            afterMillis,
            days = schedule.days
        )
    }

    fun nextWake(schedule: WorkflowSchedule, nowMillis: Long): WorkflowWake? {
        val next = nextOccurrence(schedule, nowMillis) ?: return null
        return WorkflowWake(next, (next - nowMillis).coerceAtLeast(0L))
    }

    /**
     * The most recent occurrence in `(lastDeliveredMillis, nowMillis]`, or null when nothing was
     * missed. [lastDeliveredMillis] may be negative when nothing was ever delivered.
     */
    fun latestMissedOccurrence(
        schedule: WorkflowSchedule,
        lastDeliveredMillis: Long,
        nowMillis: Long
    ): Long? {
        if (nowMillis <= lastDeliveredMillis) return null
        return when (schedule) {
            is WorkflowSchedule.Interval -> {
                val step = schedule.everyMinutes * 60_000L
                val last = Math.floorDiv(nowMillis, step) * step
                if (last > lastDeliveredMillis) last else null
            }
            is WorkflowSchedule.Daily -> latestMissedDaily(
                WorkflowSchedule.Daily(schedule.time, schedule.zone),
                lastDeliveredMillis,
                nowMillis,
                days = null
            )
            is WorkflowSchedule.Weekly -> latestMissedDaily(
                WorkflowSchedule.Daily(schedule.time, schedule.zone),
                lastDeliveredMillis,
                nowMillis,
                days = schedule.days
            )
        }
    }

    private fun nextInterval(schedule: WorkflowSchedule.Interval, afterMillis: Long): Long {
        val step = schedule.everyMinutes * 60_000L
        val bucket = Math.floorDiv(afterMillis, step) * step
        val candidate = bucket + step
        return if (candidate > afterMillis) candidate else candidate + step
    }

    private fun nextDaily(
        schedule: WorkflowSchedule.Daily,
        afterMillis: Long,
        days: Set<DayOfWeek>?
    ): Long? {
        val after = Instant.ofEpochMilli(afterMillis).atZone(schedule.zone)
        var date: LocalDate = after.toLocalDate()
        repeat(WorkflowSchedule.MAX_CATCH_UP_DAYS.toInt()) {
            val at = resolveLocal(date, schedule.time, schedule.zone)
            if (at != null && at > afterMillis && (days == null || date.dayOfWeek in days)) {
                return at
            }
            date = date.plusDays(1)
        }
        return null
    }

    private fun latestMissedDaily(
        schedule: WorkflowSchedule.Daily,
        lastDeliveredMillis: Long,
        nowMillis: Long,
        days: Set<DayOfWeek>?
    ): Long? {
        val now = Instant.ofEpochMilli(nowMillis).atZone(schedule.zone)
        var date: LocalDate = now.toLocalDate()
        repeat(WorkflowSchedule.MAX_CATCH_UP_DAYS.toInt()) {
            if (days == null || date.dayOfWeek in days) {
                val at = resolveLocal(date, schedule.time, schedule.zone)
                if (at != null && at <= nowMillis && at > lastDeliveredMillis) return at
            }
            date = date.minusDays(1)
        }
        return null
    }

    /**
     * Resolves a local wall-clock time to an instant.
     *
     * Gap: the local time does not exist, so null (caller skips to the next day). Overlap: both
     * offsets are valid; the earlier instant wins so the schedule fires once.
     */
    private fun resolveLocal(date: LocalDate, time: LocalTime, zone: ZoneId): Long? {
        val resolved = ZonedDateTime.of(date, time, zone)
        if (resolved.toLocalTime() != time || resolved.toLocalDate() != date) return null
        val earlier = resolved.withEarlierOffsetAtOverlap()
        // Confirm the earlier offset still maps back to the same local wall time.
        val candidate = earlier.toInstant().toEpochMilli()
        val roundTrip = Instant.ofEpochMilli(candidate).atZone(zone)
        return if (roundTrip.toLocalDate() == date && roundTrip.toLocalTime() == time) {
            candidate
        } else {
            null
        }
    }
}
