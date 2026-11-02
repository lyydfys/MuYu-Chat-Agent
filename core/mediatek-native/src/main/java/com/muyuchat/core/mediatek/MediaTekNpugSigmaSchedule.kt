package com.muyuchat.core.mediatek

/**
 * Sigma schedule recovered from the reference nativeGenerate loop:
 * `sigma_i = 3*t/(2*t+1)` with `t = 1 - i/steps` for `i = 0..steps`.
 * The value starts at 1 and ends at 0; it is not the v3 sigma table.
 */
enum class MediaTekNpugSigmaSchedule {
    SHIFTED_FLOW_MATCHING_3T,
}

object MediaTekNpugSigmaSchedules {
    fun values(schedule: MediaTekNpugSigmaSchedule, steps: Int): List<Double> {
        require(steps in 1..64) { "steps must be within 1..64" }
        return when (schedule) {
            MediaTekNpugSigmaSchedule.SHIFTED_FLOW_MATCHING_3T -> (0..steps).map { index ->
                val t = 1.0 - index.toDouble() / steps
                3.0 * t / (2.0 * t + 1.0)
            }
        }
    }
}
