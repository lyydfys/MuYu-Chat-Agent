package com.muyuchat.feature.agent.workspace

import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM contract checks for the gated schedule surface. They prove the page can only author a plan
 * and can never compute a next-run instant; device wake delivery is covered elsewhere.
 */
class AgentWorkflowScheduleContractsTest {

    @Test
    fun `interval boundaries are enforced locally`() {
        val minimum = AgentWorkflowScheduleDraftUi(everyMinutesText = "15")
        val maximum = AgentWorkflowScheduleDraftUi(everyMinutesText = "10080")
        assertNull(scheduleDraftValidationError(minimum))
        assertNull(scheduleDraftValidationError(maximum))

        assertTrue(
            scheduleDraftValidationError(AgentWorkflowScheduleDraftUi(everyMinutesText = "14"))
                .orEmpty().contains("15")
        )
        assertTrue(
            scheduleDraftValidationError(AgentWorkflowScheduleDraftUi(everyMinutesText = "10081"))
                .orEmpty().contains("10080")
        )
        assertEquals(
            "间隔必须是整数分钟。",
            scheduleDraftValidationError(AgentWorkflowScheduleDraftUi(everyMinutesText = ""))
        )
    }

    @Test
    fun `time and weekday validation only apply to clock plans`() {
        val daily = AgentWorkflowScheduleDraftUi(
            kind = AgentWorkflowScheduleKind.DAILY,
            timeText = "07:30"
        )
        assertNull(scheduleDraftValidationError(daily))

        val weeklyWithoutDays = AgentWorkflowScheduleDraftUi(
            kind = AgentWorkflowScheduleKind.WEEKLY,
            timeText = "07:30",
            weekdays = emptySet()
        )
        assertEquals(
            "请至少选择一天。",
            scheduleDraftValidationError(weeklyWithoutDays)
        )

        val intervalIgnoresTime = AgentWorkflowScheduleDraftUi(
            kind = AgentWorkflowScheduleKind.INTERVAL,
            everyMinutesText = "30",
            timeText = "not-a-time"
        )
        assertNull(scheduleDraftValidationError(intervalIgnoresTime))

        val missingZone = AgentWorkflowScheduleDraftUi(zoneId = " ")
        assertEquals("缺少时区，无法保存定时计划。", scheduleDraftValidationError(missingZone))
    }

    @Test
    fun `time parsing is strict 24 hour local wall clock`() {
        assertEquals(LocalTime.of(8, 0), parseScheduleTime("08:00"))
        assertEquals(LocalTime.of(9, 5), parseScheduleTime("9:05"))
        assertEquals(LocalTime.of(23, 59), parseScheduleTime(" 23:59 "))
        assertNull(parseScheduleTime("24:00"))
        assertNull(parseScheduleTime("8:5"))
        assertNull(parseScheduleTime("08:00:00"))
        assertNull(parseScheduleTime("0800"))
        assertEquals("09:05", formatScheduleTime(LocalTime.of(9, 5)))
    }

    @Test
    fun `weekdays are only sent for weekly plans`() {
        val draft = AgentWorkflowScheduleDraftUi(
            kind = AgentWorkflowScheduleKind.DAILY,
            timeText = "08:00",
            weekdays = setOf(AgentWorkflowWeekday.MONDAY, AgentWorkflowWeekday.FRIDAY),
            zoneId = "Asia/Shanghai"
        )
        val command = scheduleCommandFrom("wf-1", draft)
        assertEquals(AgentWorkflowScheduleKind.DAILY, command.kind)
        assertTrue(command.weekdays.isEmpty())
        assertEquals("Asia/Shanghai", command.zoneId)

        val weekly = scheduleCommandFrom(
            "wf-1",
            draft.copy(kind = AgentWorkflowScheduleKind.WEEKLY)
        )
        assertEquals(
            setOf(AgentWorkflowWeekday.MONDAY, AgentWorkflowWeekday.FRIDAY),
            weekly.weekdays
        )
    }

    @Test
    fun `summary describes the plan without promising an instant`() {
        val weekly = AgentWorkflowScheduleUi(
            recipeId = "wf-1",
            definitionVersion = 3,
            kind = AgentWorkflowScheduleKind.WEEKLY,
            everyMinutes = null,
            timeLabel = "08:00",
            weekdays = setOf(AgentWorkflowWeekday.FRIDAY, AgentWorkflowWeekday.MONDAY),
            zoneId = "Asia/Shanghai",
            enabled = true
        )
        val label = scheduleSummaryLabel(weekly)
        assertEquals("每周 一、五 08:00 · Asia/Shanghai · 已启用", label)
        assertFalse(label.contains("下次"))
        assertFalse(label.contains("next"))

        val disabled = weekly.copy(enabled = false)
        assertTrue(scheduleSummaryLabel(disabled).endsWith("已停用"))
    }

    @Test
    fun `draft round trips a saved row and keeps the pinned version`() {
        val saved = AgentWorkflowScheduleUi(
            recipeId = "wf-1",
            definitionVersion = 4,
            kind = AgentWorkflowScheduleKind.INTERVAL,
            everyMinutes = 240,
            timeLabel = null,
            weekdays = emptySet(),
            zoneId = "Europe/Berlin",
            enabled = false
        )
        val draft = scheduleDraftFrom(saved)
        assertEquals("240", draft.everyMinutesText)
        assertEquals("08:00", draft.timeText)
        assertEquals(setOf(AgentWorkflowWeekday.MONDAY), draft.weekdays)
        assertFalse(draft.enabled)
        assertEquals(4L, saved.definitionVersion)
    }

    @Test
    fun `unavailable port rejects list save and remove with one typed code`() = runBlocking {
        val port = AgentWorkflowSchedulePort.Unavailable
        val listed = port.list("wf-1")
        assertTrue(listed is AgentWorkflowScheduleListResult.Rejected)
        assertEquals(
            "WORKFLOW_SCHEDULE_UNAVAILABLE",
            (listed as AgentWorkflowScheduleListResult.Rejected).code
        )

        val saved = port.save(
            AgentWorkflowScheduleCommandUi(
                recipeId = "wf-1",
                kind = AgentWorkflowScheduleKind.INTERVAL,
                zoneId = "UTC"
            )
        )
        assertTrue(saved is AgentWorkflowScheduleOutcome.Rejected)

        val removed = port.remove("wf-1")
        assertTrue(removed is AgentWorkflowScheduleOutcome.Rejected)
        assertEquals(
            "WORKFLOW_SCHEDULE_UNAVAILABLE",
            (removed as AgentWorkflowScheduleOutcome.Rejected).code
        )
    }
}
