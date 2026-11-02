package com.muyuchat.feature.agent.workspace

import java.time.LocalTime

/**
 * Explicitly gated schedule surface for one published workflow recipe version.
 *
 * This is a separate contract from [AgentWorkflowAdminPort] and [AgentWorkflowLaunchPort]: the
 * manual page stays manual, while this port alone may persist a wake plan. The host remains the only
 * writer of schedule rows and the at-most-once slot claim; the page can only describe a plan, ask
 * the host to save/remove it, and render what the host reports back.
 *
 * A saved row pins the recipe version it was created for. Editing the recipe later does not move
 * the schedule; the page shows the frozen version instead of implying an automatic upgrade.
 */

enum class AgentWorkflowScheduleKind { INTERVAL, DAILY, WEEKLY }

enum class AgentWorkflowWeekday { MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY }

object AgentWorkflowScheduleLimits {
    const val MIN_INTERVAL_MINUTES = 15
    const val MAX_INTERVAL_MINUTES = 10_080
}

/** One host-reported schedule row, already projected for display. */
data class AgentWorkflowScheduleUi(
    val recipeId: String,
    val definitionVersion: Long,
    val kind: AgentWorkflowScheduleKind,
    val everyMinutes: Int?,
    val timeLabel: String?,
    val weekdays: Set<AgentWorkflowWeekday>,
    val zoneId: String,
    val enabled: Boolean,
    /** Host-provided last-run label; null means the row has never delivered a slot. */
    val lastDeliveredLabel: String? = null
) {
    init {
        require(recipeId.isNotBlank()) { "schedule recipeId must not be blank" }
        require(definitionVersion >= 1L) { "schedule definitionVersion must be positive" }
        require(zoneId.isNotBlank()) { "schedule zoneId must not be blank" }
    }
}

/** One user-authored plan; the host validates it again and remains authoritative. */
data class AgentWorkflowScheduleCommandUi(
    val recipeId: String,
    val kind: AgentWorkflowScheduleKind,
    val everyMinutes: Int = 60,
    /** Local wall-clock `HH:mm`; ignored for [AgentWorkflowScheduleKind.INTERVAL]. */
    val timeLabel: String = "08:00",
    /** Empty for interval/daily plans; weekly plans need at least one day. */
    val weekdays: Set<AgentWorkflowWeekday> = emptySet(),
    val zoneId: String,
    val enabled: Boolean = true
) {
    init {
        require(recipeId.isNotBlank()) { "schedule command recipeId must not be blank" }
        require(zoneId.isNotBlank()) { "schedule command zoneId must not be blank" }
    }
}

/** Editable form state; a rejected save keeps the draft and shows only the safe message. */
data class AgentWorkflowScheduleDraftUi(
    val kind: AgentWorkflowScheduleKind = AgentWorkflowScheduleKind.INTERVAL,
    val everyMinutesText: String = DEFAULT_INTERVAL_MINUTES.toString(),
    val timeText: String = DEFAULT_TIME_LABEL,
    val weekdays: Set<AgentWorkflowWeekday> = setOf(AgentWorkflowWeekday.MONDAY),
    val zoneId: String = "UTC",
    val enabled: Boolean = true,
    val validationMessage: String? = null
)

sealed interface AgentWorkflowScheduleListResult {
    data class Loaded(val schedules: List<AgentWorkflowScheduleUi>) : AgentWorkflowScheduleListResult

    data class Rejected(
        val code: String,
        val safeMessage: String
    ) : AgentWorkflowScheduleListResult {
        init {
            require(code.isNotBlank()) { "schedule list rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "schedule list rejection message must not be blank" }
        }
    }
}

sealed interface AgentWorkflowScheduleOutcome {
    /**
     * The row is durable. [wakeArmed] false means the host persisted the plan but could not install
     * the platform wake, so the page must not claim unattended execution is armed.
     */
    data class Saved(
        val schedule: AgentWorkflowScheduleUi,
        val wakeArmed: Boolean
    ) : AgentWorkflowScheduleOutcome

    data class Removed(val recipeId: String, val removed: Boolean) : AgentWorkflowScheduleOutcome {
        init {
            require(recipeId.isNotBlank()) { "removed recipeId must not be blank" }
        }
    }

    data class Rejected(val code: String, val safeMessage: String) : AgentWorkflowScheduleOutcome {
        init {
            require(code.isNotBlank()) { "schedule rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "schedule rejection message must not be blank" }
        }
    }
}

/**
 * Narrow schedule port. There is no run-now, no trigger string and no task/run id parameter: a
 * schedule only describes when the existing entry facade may start the pinned recipe version.
 */
interface AgentWorkflowSchedulePort {
    suspend fun list(recipeId: String): AgentWorkflowScheduleListResult

    suspend fun save(command: AgentWorkflowScheduleCommandUi): AgentWorkflowScheduleOutcome

    suspend fun remove(recipeId: String): AgentWorkflowScheduleOutcome

    companion object {
        /** Preview/test fallback only; production wiring must provide the real host adapter. */
        val Unavailable: AgentWorkflowSchedulePort = object : AgentWorkflowSchedulePort {
            override suspend fun list(recipeId: String): AgentWorkflowScheduleListResult =
                AgentWorkflowScheduleListResult.Rejected(
                    code = "WORKFLOW_SCHEDULE_UNAVAILABLE",
                    safeMessage = "定时计划尚未接入。"
                )

            override suspend fun save(
                command: AgentWorkflowScheduleCommandUi
            ): AgentWorkflowScheduleOutcome = AgentWorkflowScheduleOutcome.Rejected(
                code = "WORKFLOW_SCHEDULE_UNAVAILABLE",
                safeMessage = "定时计划尚未接入。"
            )

            override suspend fun remove(recipeId: String): AgentWorkflowScheduleOutcome =
                AgentWorkflowScheduleOutcome.Rejected(
                    code = "WORKFLOW_SCHEDULE_UNAVAILABLE",
                    safeMessage = "定时计划尚未接入。"
                )
        }
    }
}

/** Page state of the one schedule dialog; [visible] is the dialog, not the whole page. */
data class AgentWorkflowScheduleUiState(
    val visible: Boolean = false,
    val recipeId: String? = null,
    val recipeName: String? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val schedule: AgentWorkflowScheduleUi? = null,
    val draft: AgentWorkflowScheduleDraftUi? = null,
    val error: AgentWorkflowErrorUi? = null,
    val notice: String? = null
) {
    val canSave: Boolean get() = !busy && !loading && draft != null

    val canDelete: Boolean get() = !busy && !loading && schedule != null

    val canRetry: Boolean get() = !busy && !loading && recipeId != null
}

internal fun scheduleDraftFrom(schedule: AgentWorkflowScheduleUi): AgentWorkflowScheduleDraftUi =
    AgentWorkflowScheduleDraftUi(
        kind = schedule.kind,
        everyMinutesText = (schedule.everyMinutes ?: DEFAULT_INTERVAL_MINUTES).toString(),
        timeText = schedule.timeLabel ?: DEFAULT_TIME_LABEL,
        weekdays = schedule.weekdays.ifEmpty { setOf(AgentWorkflowWeekday.MONDAY) },
        zoneId = schedule.zoneId,
        enabled = schedule.enabled
    )

/**
 * Local form validation only; the host re-validates and may still reject. The message is shown
 * inline and never replaces a host rejection.
 */
internal fun scheduleDraftValidationError(draft: AgentWorkflowScheduleDraftUi): String? {
    if (draft.zoneId.isBlank()) return "缺少时区，无法保存定时计划。"
    when (draft.kind) {
        AgentWorkflowScheduleKind.INTERVAL -> {
            val minutes = draft.everyMinutesText.trim().toIntOrNull()
                ?: return "间隔必须是整数分钟。"
            if (minutes !in AgentWorkflowScheduleLimits.MIN_INTERVAL_MINUTES..
                AgentWorkflowScheduleLimits.MAX_INTERVAL_MINUTES
            ) {
                return "间隔需在 ${AgentWorkflowScheduleLimits.MIN_INTERVAL_MINUTES}–" +
                    "${AgentWorkflowScheduleLimits.MAX_INTERVAL_MINUTES} 分钟之间。"
            }
        }

        AgentWorkflowScheduleKind.DAILY, AgentWorkflowScheduleKind.WEEKLY -> {
            if (parseScheduleTime(draft.timeText) == null) return "时间需为 24 小时制 HH:mm。"
            if (draft.kind == AgentWorkflowScheduleKind.WEEKLY && draft.weekdays.isEmpty()) {
                return "请至少选择一天。"
            }
        }
    }
    return null
}

internal fun scheduleCommandFrom(
    recipeId: String,
    draft: AgentWorkflowScheduleDraftUi
): AgentWorkflowScheduleCommandUi = AgentWorkflowScheduleCommandUi(
    recipeId = recipeId,
    kind = draft.kind,
    everyMinutes = draft.everyMinutesText.trim().toIntOrNull() ?: DEFAULT_INTERVAL_MINUTES,
    timeLabel = draft.timeText.trim(),
    weekdays = if (draft.kind == AgentWorkflowScheduleKind.WEEKLY) draft.weekdays else emptySet(),
    zoneId = draft.zoneId,
    enabled = draft.enabled
)

/** One-line description of a saved row; never a promised next-run instant. */
internal fun scheduleSummaryLabel(schedule: AgentWorkflowScheduleUi): String {
    val plan = when (schedule.kind) {
        AgentWorkflowScheduleKind.INTERVAL ->
            "每 ${schedule.everyMinutes ?: DEFAULT_INTERVAL_MINUTES} 分钟"

        AgentWorkflowScheduleKind.DAILY -> "每天 ${schedule.timeLabel ?: DEFAULT_TIME_LABEL}"

        AgentWorkflowScheduleKind.WEEKLY ->
            "每周 " + schedule.weekdays.sorted().joinToString("、", transform = ::weekdayLabel) +
                " " + (schedule.timeLabel ?: DEFAULT_TIME_LABEL)
    }
    val state = if (schedule.enabled) "已启用" else "已停用"
    return "$plan · ${schedule.zoneId} · $state"
}

internal fun weekdayLabel(day: AgentWorkflowWeekday): String = when (day) {
    AgentWorkflowWeekday.MONDAY -> "一"
    AgentWorkflowWeekday.TUESDAY -> "二"
    AgentWorkflowWeekday.WEDNESDAY -> "三"
    AgentWorkflowWeekday.THURSDAY -> "四"
    AgentWorkflowWeekday.FRIDAY -> "五"
    AgentWorkflowWeekday.SATURDAY -> "六"
    AgentWorkflowWeekday.SUNDAY -> "日"
}

internal fun parseScheduleTime(text: String): LocalTime? = runCatching {
    val parts = text.trim().split(':')
    if (parts.size != 2) return null
    if (parts[0].length !in 1..2 || parts[1].length != 2) return null
    LocalTime.of(parts[0].toInt(), parts[1].toInt())
}.getOrNull()

internal fun formatScheduleTime(time: LocalTime): String =
    time.hour.toString().padStart(2, '0') + ":" + time.minute.toString().padStart(2, '0')

internal const val DEFAULT_INTERVAL_MINUTES = 60
internal const val DEFAULT_TIME_LABEL = "08:00"
