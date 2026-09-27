package com.muyuchat.core.telemetry

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import java.io.File

data class SystemLoadSnapshot(
    val systemCpuPercent: Int? = null,
    val processCpuPercent: Int? = null,
    val memoryUsedBytes: Long = 0L,
    val memoryTotalBytes: Long = 0L,
    val processPssKb: Int = 0,
    val gpuPercent: Int? = null,
    val npuPercent: Int? = null,
    val gpuSource: String? = null,
    val npuSource: String? = null,
    val gpuStatus: AcceleratorLoad.Status = if (gpuPercent != null) AcceleratorLoad.Status.AVAILABLE else AcceleratorLoad.Status.UNAVAILABLE,
    val npuStatus: AcceleratorLoad.Status = if (npuPercent != null) AcceleratorLoad.Status.AVAILABLE else AcceleratorLoad.Status.UNAVAILABLE,
    val gpuExecutionObserved: Boolean = false,
    val npuExecutionObserved: Boolean = false,
    val timestampMillis: Long = System.currentTimeMillis()
)

/**
 * Lightweight reader intended for an on-screen one-second poll. GPU counters are sampled as
 * deltas; the NPU is explicitly unavailable unless a trustworthy system counter is added.
 * Generic devfreq nodes are deliberately not treated as NPU counters because they may be CPU
 * or memory controllers on different devices.
 */
class SystemLoadReader(private val context: Context) {
    private val sampler = SystemLoadSampler(
        source = AndroidSystemLoadDataSource(context.applicationContext),
        elapsedRealtimeMillis = SystemClock::elapsedRealtime
    )

    fun read(): SystemLoadSnapshot {
        val result = sampler.sample()
        return SystemLoadSnapshot(
            systemCpuPercent = result.cpu.systemPercent,
            processCpuPercent = result.cpu.processPercent,
            memoryUsedBytes = result.memory.usedBytes,
            memoryTotalBytes = result.memory.totalBytes,
            processPssKb = result.memory.processPssKb ?: 0,
            gpuPercent = result.gpu.utilizationPercent,
            npuPercent = result.npu.utilizationPercent,
            gpuSource = result.gpu.source,
            npuSource = result.npu.source,
            gpuStatus = result.gpu.status,
            npuStatus = result.npu.status,
            gpuExecutionObserved = result.gpu.executionObserved,
            npuExecutionObserved = result.npu.executionObserved,
            timestampMillis = result.timestampMillis
        )
    }
}

private class AndroidSystemLoadDataSource(private val context: Context) : SystemLoadDataSource {
    private var previousGpu: CounterPair? = null

    override fun readCpuStat(): String? = readText("/proc/stat")

    override fun readProcessStat(): String? = readText("/proc/self/stat")

    override fun readProcessCpuTimeMillis(): Long = Process.getElapsedCpuTime().coerceAtLeast(0L)

    override fun readMemory(): MemoryLoad {
        val manager = context.getSystemService(ActivityManager::class.java)
        val systemInfo = ActivityManager.MemoryInfo()
        runCatching { manager?.getMemoryInfo(systemInfo) }
        val total = systemInfo.totalMem.coerceAtLeast(0L)
        val available = systemInfo.availMem.coerceAtLeast(0L).coerceAtMost(total)
        val pss = runCatching {
            Debug.MemoryInfo().let { info ->
                Debug.getMemoryInfo(info)
                info.totalPss.coerceAtLeast(0)
            }
        }.getOrNull()
        return MemoryLoad(
            usedBytes = (total - available).coerceAtLeast(0L),
            totalBytes = total,
            processPssKb = pss
        )
    }

    override fun readGpuLoad(): AcceleratorLoad {
        val current = readGpuCounters() ?: run {
            previousGpu = null
            return AcceleratorLoad.unavailable("GPU utilization counter unavailable or unreadable")
        }
        val old = previousGpu
        previousGpu = current
        if (old == null) return AcceleratorLoad.unavailable("waiting for GPU counter interval")
        val percent = SystemLoadParser.counterDeltaPercent(
            previousBusy = old.busy,
            previousTotal = old.total,
            currentBusy = current.busy,
            currentTotal = current.total
        ) ?: return AcceleratorLoad.unavailable("GPU counter did not advance monotonically")
        return AcceleratorLoad(
            utilizationPercent = percent,
            source = current.source,
            status = AcceleratorLoad.Status.AVAILABLE
        )
    }

    override fun readNpuLoad(): AcceleratorLoad =
        AcceleratorLoad.unavailable("Android does not expose a portable NPU utilization counter")

    private fun readGpuCounters(): CounterPair? {
        // KGSL gpubusy contains cumulative busy and total values on supported Qualcomm devices.
        readCounterPair("/sys/class/kgsl/kgsl-3d0/gpubusy", null)?.let { return it }

        // Some kernels expose the same cumulative counters as separate devfreq files. Restrict
        // this lookup to the explicit KGSL GPU node; arbitrary devfreq devices are ambiguous.
        return readCounterPair(
            "/sys/class/devfreq/kgsl-3d0/busy_time",
            "/sys/class/devfreq/kgsl-3d0/total_time"
        )
    }

    private fun readCounterPair(busyPath: String, totalPath: String?): CounterPair? {
        val busyFile = File(busyPath)
        if (!busyFile.isFile || !busyFile.canRead()) return null
        val busyValues = readText(busyPath)?.trim()?.split(Regex("\\s+"))?.mapNotNull(String::toLongOrNull)
            ?: return null
        val busy = busyValues.firstOrNull()?.takeIf { it >= 0L } ?: return null
        val total = busyValues.getOrNull(1)?.takeIf { it > 0L } ?: totalPath?.let { path ->
            readText(path)?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toLongOrNull()?.takeIf { it > 0L }
        } ?: return null
        if (busy > total) return null
        return CounterPair(busy, total, busyPath)
    }

    private fun readText(path: String): String? = runCatching {
        File(path).takeIf { it.isFile && it.canRead() }?.readText()
    }.getOrNull()

    private data class CounterPair(val busy: Long, val total: Long, val source: String)
}
