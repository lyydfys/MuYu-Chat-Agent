package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.engine.RuntimeStats
import java.io.File
import org.json.JSONObject

/**
 * App-private, crash-tolerant store for [PrefixCacheTelemetryState].
 *
 * Kept next to the app's own data (not the engine cache directory) because it aggregates evidence
 * for both the local prefix cache and provider-side prompt caching. Writes go through a temp file
 * and a rename, so a process death mid-write leaves the previous counters intact instead of a
 * truncated JSON document. Every read falls back to "no observations yet" rather than throwing:
 * telemetry must never be able to fail a chat turn.
 */
internal class PrefixCacheTelemetryStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    /** Serialises read-modify-write so two concurrent finished turns cannot drop one another. */
    private val lock = Any()

    fun load(): PrefixCacheTelemetryState = synchronized(lock) { read() }

    /** Folds [stats] into the stored aggregate, persists it and returns the new value. */
    fun record(
        stats: RuntimeStats,
        nowEpochMs: Long = System.currentTimeMillis()
    ): PrefixCacheTelemetryState = synchronized(lock) {
        val previous = read()
        val updated = previous.record(stats, nowEpochMs)
        if (updated != previous) write(updated)
        updated
    }

    fun reset(): PrefixCacheTelemetryState = synchronized(lock) {
        val cleared = PrefixCacheTelemetryState()
        write(cleared)
        cleared
    }

    private fun read(): PrefixCacheTelemetryState = runCatching {
        if (!file.isFile) return PrefixCacheTelemetryState()
        val text = file.readText(Charsets.UTF_8)
        if (text.isBlank()) return PrefixCacheTelemetryState()
        PrefixCacheTelemetryState.fromJson(JSONObject(text)) ?: PrefixCacheTelemetryState()
    }.getOrDefault(PrefixCacheTelemetryState())

    private fun write(state: PrefixCacheTelemetryState) {
        runCatching {
            val directory = file.parentFile ?: return
            directory.mkdirs()
            val json = state.toJson().toString()
            val temp = File(directory, "$FILE_NAME.tmp")
            temp.writeText(json, Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                // Rename can fail when the target exists on some filesystems; writing in place
                // keeps the document valid because it is a single small `writeText` call.
                file.writeText(json, Charsets.UTF_8)
                temp.delete()
            }
        }
    }

    private companion object {
        const val FILE_NAME = "prefix_cache_telemetry.json"
    }
}
