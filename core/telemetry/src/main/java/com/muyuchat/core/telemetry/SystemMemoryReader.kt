package com.muyuchat.core.telemetry

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import kotlin.math.max
import kotlin.math.min

data class SystemMemorySnapshot(
    val totalBytes: Long,
    val advertisedBytes: Long,
    val availableBytes: Long,
    val thresholdBytes: Long,
    val lowMemory: Boolean,
    val procMemTotalBytes: Long,
    val procMemAvailableBytes: Long,
    val procMemFreeBytes: Long,
    val procCachedBytes: Long,
    val procSReclaimableBytes: Long,
    val procShmemBytes: Long,
    val procSwapFreeBytes: Long,
    val procSwapTotalBytes: Long
) {
    val displayTotalBytes: Long
        get() = advertisedBytes.takeIf { it > 0L } ?: totalBytes

    val reclaimableBytes: Long
        get() = max(0L, procCachedBytes - procShmemBytes) + procSReclaimableBytes

    val modelBudgetBytes: Long
        get() {
            if (lowMemory) return availableBytes
            val total = totalBytes.takeIf { it > 0L } ?: procMemTotalBytes
            if (total <= 0L) return availableBytes
            val cacheAssist = min(reclaimableBytes / 3L, total / 8L)
            val capacityFloor = (total * MODEL_BUDGET_FLOOR_RATIO).toLong()
            val capacityCeiling = (total * MODEL_BUDGET_CEILING_RATIO).toLong()
            return min(capacityCeiling, max(max(availableBytes, capacityFloor), availableBytes + cacheAssist))
        }

    private companion object {
        const val MODEL_BUDGET_FLOOR_RATIO = 0.55
        const val MODEL_BUDGET_CEILING_RATIO = 0.70
    }
}

object SystemMemoryReader {
    fun read(context: Context): SystemMemorySnapshot {
        val proc = readProcMemInfo()
        val memoryInfo = runCatching {
            val activityManager = context.applicationContext
                .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        }.getOrNull()
        val activityAvailable = memoryInfo?.availMem?.coerceAtLeast(0L) ?: 0L
        val procAvailable = proc["MemAvailable"].orZeroBytes()
        return SystemMemorySnapshot(
            totalBytes = memoryInfo?.totalMem?.takeIf { it > 0L } ?: proc["MemTotal"].orZeroBytes(),
            advertisedBytes = memoryInfo?.advertisedBytesCompat() ?: 0L,
            availableBytes = max(activityAvailable, procAvailable),
            thresholdBytes = memoryInfo?.threshold?.coerceAtLeast(0L) ?: 0L,
            lowMemory = memoryInfo?.lowMemory ?: false,
            procMemTotalBytes = proc["MemTotal"].orZeroBytes(),
            procMemAvailableBytes = procAvailable,
            procMemFreeBytes = proc["MemFree"].orZeroBytes(),
            procCachedBytes = proc["Cached"].orZeroBytes(),
            procSReclaimableBytes = proc["SReclaimable"].orZeroBytes(),
            procShmemBytes = proc["Shmem"].orZeroBytes(),
            procSwapFreeBytes = proc["SwapFree"].orZeroBytes(),
            procSwapTotalBytes = proc["SwapTotal"].orZeroBytes()
        )
    }

    private fun ActivityManager.MemoryInfo.advertisedBytesCompat(): Long =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                advertisedMem.coerceAtLeast(0L)
            } else {
                0L
            }
        }.getOrDefault(0L)

    private fun readProcMemInfo(): Map<String, Long> {
        return runCatching {
            File("/proc/meminfo").useLines { lines ->
                lines.mapNotNull { line ->
                    val parts = line.split(Regex("\\s+"))
                    val key = parts.getOrNull(0)?.removeSuffix(":") ?: return@mapNotNull null
                    val kb = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                    key to kb
                }.toMap()
            }
        }.getOrDefault(emptyMap())
    }

    private fun Long?.orZeroBytes(): Long = (this ?: 0L).coerceAtLeast(0L) * 1024L
}
