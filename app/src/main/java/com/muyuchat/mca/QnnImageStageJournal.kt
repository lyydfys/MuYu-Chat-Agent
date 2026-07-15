package com.muyuchat.mca

import java.io.File
import org.json.JSONObject

/**
 * Reads the native QNN progress sidecar without entering JNI.  This remains
 * observable when the generation thread is blocked in graph/context teardown
 * or dlclose, which is exactly when a diagnostic trace is most valuable.
 */
internal object QnnImageStageJournal {
    fun readOrPrevious(
        file: File,
        previous: LocalImageProgress?,
        threads: Int,
        width: Int,
        height: Int
    ): LocalImageProgress? = runCatching {
        if (!file.isFile || file.length() <= 0L) return@runCatching previous
        val json = JSONObject(file.readText())
        val trace = json.optJSONArray("stageTrace")?.let { stages ->
            buildList {
                for (index in 0 until stages.length()) {
                    stages.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.orEmpty()
        if (trace.isEmpty() && !json.optBoolean("active") && !json.optBoolean("cancelRequested")) {
            return@runCatching previous
        }
        LocalImageProgress(
            phase = json.optString("phase").ifBlank { previous?.phase.orEmpty() },
            message = json.optString("message").ifBlank { previous?.message.orEmpty() },
            step = json.optInt("step", previous?.step ?: 0),
            steps = json.optInt("steps", previous?.steps ?: 0),
            elapsedMs = json.optLong("elapsedMs", previous?.elapsedMs ?: 0L),
            secondsPerStep = 0.0,
            threads = threads,
            width = width,
            height = height,
            cancelRequested = json.optBoolean("cancelRequested"),
            stageTrace = if (trace.size >= previous?.stageTrace.orEmpty().size) {
                trace
            } else {
                previous?.stageTrace.orEmpty()
            }
        )
    }.getOrDefault(previous)
}
