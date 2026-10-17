package com.muyuchat.mca

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import com.muyuchat.core.engine.RuntimeStats
import org.json.JSONObject

internal fun TuningProbeWorkerService.readMemoryPoint(stats: RuntimeStats? = null): ProbeMemoryPoint {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        val processPssBytes = Debug.getPss().coerceAtLeast(0L) * 1_024L
        val statsPss = stats?.nativePssKb?.coerceAtLeast(0L)?.times(1_024L) ?: 0L
        val statsRss = stats?.processRssKb?.coerceAtLeast(0L)?.times(1_024L) ?: 0L
        return ProbeMemoryPoint(
            availableBytes = info.availMem.coerceAtLeast(0L),
            pssBytes = maxOf(processPssBytes, statsPss),
            rssBytes = maxOf(processPssBytes, statsRss),
            lowMemory = info.lowMemory || stats?.isLowMemory == true
        )
    }

internal fun TuningProbeWorkerService.peakMemoryPoint(points: List<ProbeMemoryPoint>): ProbeMemoryPoint {
        require(points.isNotEmpty())
        return ProbeMemoryPoint(
            availableBytes = points.map { it.availableBytes }.filter { it > 0L }.minOrNull() ?: 0L,
            pssBytes = points.maxOf { it.pssBytes },
            rssBytes = points.maxOf { it.rssBytes },
            lowMemory = points.any { it.lowMemory }
        )
    }

internal data class ProbeMemoryPoint(
    val availableBytes: Long,
    val pssBytes: Long,
    val rssBytes: Long,
    val lowMemory: Boolean
) {
    fun toJson(): JSONObject = JSONObject()
        .put("availableBytes", availableBytes)
        .put("pssBytes", pssBytes)
        .put("rssBytes", rssBytes)
        .put("lowMemory", lowMemory)
}
