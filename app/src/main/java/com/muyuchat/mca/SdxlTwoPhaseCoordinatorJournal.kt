package com.muyuchat.mca

internal data class SdxlPhaseCompletion(
    val result: SdxlImagePhaseResult,
    val processDeathConfirmed: Boolean
)

internal object SdxlTwoPhaseJournal {
    fun merge(
        previous: List<String>,
        envelope: SdxlImagePhaseProgress
    ): List<String> {
        var merged = previous
        val prefix = "${envelope.phase.wireName}[pid=${envelope.workerPid},profile=${envelope.runtimeProfile}]"
        if (merged.none { it == "$prefix:worker_started" }) {
            merged = merged + "$prefix:worker_started"
        }
        envelope.progress.stageTrace.forEach { stage ->
            val tagged = "$prefix:$stage"
            if (tagged !in merged) merged = merged + tagged
        }
        return merged
    }

    fun appendBoundary(
        previous: List<String>,
        phase: SdxlImagePhase,
        pid: Int,
        profile: String,
        boundary: String
    ): List<String> {
        val tagged = "${phase.wireName}[pid=$pid,profile=$profile]:$boundary"
        return if (tagged in previous) previous else previous + tagged
    }
}
