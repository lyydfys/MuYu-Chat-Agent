package com.muyuchat.mca.agent.device.sensors

/**
 * Turns the platform's "steps since boot" counter into "today's steps".
 *
 * Android exposes [android.hardware.Sensor.TYPE_STEP_COUNTER] as a monotonic counter since boot; it
 * has no notion of a day. The honest bridge is a persisted baseline for the current day:
 *
 *  - same day: `today = counter - baseline`, clamped to `0..MAX_DAILY_STEPS`;
 *  - first read of a new day (or no baseline yet): `today = 0` and the caller stores the counter as
 *    the new baseline, so the next read counts from there;
 *  - a counter that went backwards (reboot, sensor reset) re-baselines instead of returning a
 *    negative number.
 *
 * The tint of this behaviour is reported in the reading's detail, so DSH never claims "today" for
 * a number that is really "since this app first read the counter".
 */
data class AgentStepBaseline(val epochDay: Long, val counter: Int)

data class AgentDailySteps(
    val steps: Int,
    /** Baseline that should be persisted for the next read; null when no write is needed. */
    val nextBaseline: AgentStepBaseline?,
    val detail: String
)

object AgentStepCounterPolicy {

    fun dailySteps(
        todayEpochDay: Long,
        counter: Int?,
        baseline: AgentStepBaseline?
    ): AgentDailySteps {
        if (counter == null || counter < 0) {
            return AgentDailySteps(
                steps = 0,
                nextBaseline = null,
                detail = "系统没有返回步数计数器"
            )
        }
        if (baseline == null) {
            return AgentDailySteps(
                steps = 0,
                nextBaseline = AgentStepBaseline(todayEpochDay, counter),
                detail = "从今天首次读取开始计数（已记录基线）"
            )
        }
        if (baseline.epochDay != todayEpochDay) {
            return AgentDailySteps(
                steps = 0,
                nextBaseline = AgentStepBaseline(todayEpochDay, counter),
                detail = "已跨日，今日步数从 0 重新累计"
            )
        }
        if (counter < baseline.counter) {
            return AgentDailySteps(
                steps = 0,
                nextBaseline = AgentStepBaseline(todayEpochDay, counter),
                detail = "步数计数器已重置，今日步数从 0 重新累计"
            )
        }
        val today = (counter - baseline.counter).coerceIn(0, AgentSensorReadings.MAX_DAILY_STEPS)
        return AgentDailySteps(
            steps = today,
            nextBaseline = null,
            detail = "今日累计（自本应用今日首次读取起）"
        )
    }
}
