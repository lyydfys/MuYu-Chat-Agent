package com.muyuchat.core.telemetry

import android.content.Context
import android.os.Debug
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque

class TelemetryLogger(private val context: Context) {
    private val logDir: File by lazy { File(context.filesDir, "logs").also { it.mkdirs() } }
    private val logFile: File by lazy { File(logDir, "mca-runtime.jsonl") }

    fun memorySnapshot(): Pair<Long, Long> {
        val snapshot = memorySnapshotDetailed()
        return snapshot.processPssKb to snapshot.availMemKb
    }

    fun memorySnapshotDetailed(): MemorySnapshot {
        val memoryInfo = runCatching {
            Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        }.getOrNull()
        val systemInfo = SystemMemoryReader.read(context)
        val runtime = Runtime.getRuntime()
        return MemorySnapshot(
            processPssKb = memoryInfo?.totalPss?.toLong() ?: 0L,
            processRssKb = readProcStatusKb("VmRSS"),
            nativeHeapKb = runCatching { Debug.getNativeHeapAllocatedSize() / 1024L }.getOrDefault(0L),
            nativeHeapSizeKb = runCatching { Debug.getNativeHeapSize() / 1024L }.getOrDefault(0L),
            javaHeapKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L,
            availMemKb = systemInfo.availableBytes / 1024L,
            totalMemKb = systemInfo.totalBytes / 1024L,
            advertisedMemKb = systemInfo.advertisedBytes / 1024L,
            memoryThresholdKb = systemInfo.thresholdBytes / 1024L,
            isLowMemory = systemInfo.lowMemory,
            procMemAvailableKb = systemInfo.procMemAvailableBytes / 1024L,
            procMemFreeKb = systemInfo.procMemFreeBytes / 1024L,
            cachedKb = systemInfo.procCachedBytes / 1024L,
            reclaimableKb = systemInfo.reclaimableBytes / 1024L,
            modelMemoryBudgetKb = systemInfo.modelBudgetBytes / 1024L
        )
    }

    fun append(metrics: RuntimeMetrics) {
        logDir.mkdirs()
        logFile.appendText(RuntimeMetricsJson.toJson(metrics).toString() + "\n", Charsets.UTF_8)
    }

    fun recent(limit: Int = 200): List<RuntimeMetrics> {
        if (!logFile.exists()) return emptyList()
        val queue = ArrayDeque<String>(limit)
        logFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                if (queue.size == limit) queue.removeFirst()
                queue.addLast(line)
            }
        }
        return queue.mapNotNull { line ->
            runCatching { RuntimeMetricsJson.fromJson(JSONObject(line)) }.getOrNull()
        }
    }

    fun exportFile(): File = logFile

    private fun readProcStatusKb(key: String): Long {
        return runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("$key:") }
                    ?.split(Regex("\\s+"))
                    ?.getOrNull(1)
                    ?.toLongOrNull()
            } ?: 0L
        }.getOrDefault(0L)
    }
}

data class MemorySnapshot(
    val processPssKb: Long = 0,
    val processRssKb: Long = 0,
    val nativeHeapKb: Long = 0,
    val nativeHeapSizeKb: Long = 0,
    val javaHeapKb: Long = 0,
    val availMemKb: Long = 0,
    val totalMemKb: Long = 0,
    val advertisedMemKb: Long = 0,
    val memoryThresholdKb: Long = 0,
    val isLowMemory: Boolean = false,
    val procMemAvailableKb: Long = 0,
    val procMemFreeKb: Long = 0,
    val cachedKb: Long = 0,
    val reclaimableKb: Long = 0,
    val modelMemoryBudgetKb: Long = 0
)

