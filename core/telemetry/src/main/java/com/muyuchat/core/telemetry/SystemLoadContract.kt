package com.muyuchat.core.telemetry

/** A value that is only present when a platform exposes a trustworthy counter. */
data class AcceleratorLoad(
    val utilizationPercent: Int? = null,
    val source: String? = null,
    val status: Status = if (utilizationPercent != null) Status.AVAILABLE else Status.UNAVAILABLE,
    /** True when native execution was observed even though no utilization counter exists. */
    val executionObserved: Boolean = false
) {
    enum class Status { AVAILABLE, UNAVAILABLE, ERROR }

    init {
        require(utilizationPercent == null || utilizationPercent in 0..100) {
            "utilizationPercent must be 0..100 or null"
        }
        require(status != Status.AVAILABLE || utilizationPercent != null) {
            "AVAILABLE accelerator load must include a percentage"
        }
        require(utilizationPercent == null || !source.isNullOrBlank()) {
            "an observed percentage must identify its source"
        }
    }

    companion object {
        fun unavailable(source: String? = null, executionObserved: Boolean = false): AcceleratorLoad = AcceleratorLoad(
            utilizationPercent = null,
            source = source,
            status = Status.UNAVAILABLE,
            executionObserved = executionObserved
        )

        fun error(source: String? = null): AcceleratorLoad = AcceleratorLoad(
            utilizationPercent = null,
            source = source,
            status = Status.ERROR
        )
    }
}

data class CpuLoad(
    val systemPercent: Int? = null,
    val processPercent: Int? = null
) {
    init {
        require(systemPercent == null || systemPercent in 0..100)
        require(processPercent == null || processPercent in 0..100)
    }
}

data class MemoryLoad(
    val usedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val processPssKb: Int? = null
) {
    init {
        require(usedBytes >= 0L)
        require(totalBytes >= 0L)
        require(totalBytes == 0L || usedBytes <= totalBytes)
        require(processPssKb == null || processPssKb >= 0)
    }
}

data class UnifiedSystemLoadSnapshot(
    val timestampMillis: Long,
    val cpu: CpuLoad,
    val memory: MemoryLoad,
    val gpu: AcceleratorLoad,
    val npu: AcceleratorLoad
)

data class CpuCounterSample(
    val totalTicks: Long,
    val idleTicks: Long,
    val processTicks: Long
) {
    init {
        require(totalTicks >= 0L)
        require(idleTicks >= 0L)
        require(processTicks >= 0L)
    }
}

object SystemLoadParser {
    /** Parse the aggregate `cpu` row from /proc/stat. */
    fun parseCpuStat(text: String): CpuCounterSample? {
        val row = text.lineSequence().firstOrNull { it.trimStart().startsWith("cpu ") } ?: return null
        val values = row.trim().split(Regex("\\s+")).drop(1).mapNotNull(String::toLongOrNull)
        if (values.size < 4) return null
        val total = values.take(8).sumOf { it.coerceAtLeast(0L) }
        val idle = (values.getOrNull(3).orZero() + values.getOrNull(4).orZero()).coerceAtLeast(0L)
        return CpuCounterSample(total, idle, processTicks = 0L)
    }

    /** Parse utime+stime from /proc/[pid]/stat without breaking on spaces in `(comm)`. */
    fun parseProcessTicks(text: String): Long? {
        val close = text.lastIndexOf(')')
        if (close < 0 || close + 2 >= text.length) return null
        val fields = text.substring(close + 2).trim().split(Regex("\\s+"))
        // Fields after comm start with state (field 3), so utime (14) is index 11.
        if (fields.size <= 12) return null
        val user = fields[11].toLongOrNull() ?: return null
        val system = fields[12].toLongOrNull() ?: return null
        return (user + system).coerceAtLeast(0L)
    }

    fun withProcessTicks(cpu: CpuCounterSample, processTicks: Long): CpuCounterSample =
        cpu.copy(processTicks = processTicks.coerceAtLeast(0L))

    fun delta(previous: CpuCounterSample, current: CpuCounterSample): CpuLoad? {
        val totalDelta = current.totalTicks - previous.totalTicks
        val idleDelta = current.idleTicks - previous.idleTicks
        val processDelta = current.processTicks - previous.processTicks
        if (totalDelta <= 0L || idleDelta < 0L || processDelta < 0L) return null
        fun percent(numerator: Long): Int = ((numerator.toDouble() / totalDelta) * 100.0)
            .toInt().coerceIn(0, 100)
        return CpuLoad(
            systemPercent = percent((totalDelta - idleDelta).coerceAtLeast(0L)),
            processPercent = percent(processDelta)
        )
    }

    fun systemPercent(previous: CpuCounterSample, current: CpuCounterSample): Int? {
        val totalDelta = current.totalTicks - previous.totalTicks
        val idleDelta = current.idleTicks - previous.idleTicks
        if (totalDelta <= 0L || idleDelta < 0L) return null
        return (((totalDelta - idleDelta).coerceAtLeast(0L).toDouble() / totalDelta) * 100.0)
            .toInt()
            .coerceIn(0, 100)
    }

    /** Parse a busy/total counter pair; return null when the kernel does not expose both. */
    fun parseBusyTotal(busyText: String?, totalText: String?): Int? {
        val busyValues = busyText?.trim()?.split(Regex("\\s+"))?.mapNotNull(String::toLongOrNull).orEmpty()
        val busy = busyValues.firstOrNull() ?: return null
        val total = busyValues.getOrNull(1)
            ?: totalText?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toLongOrNull()
            ?: return null
        if (busy < 0L || total <= 0L || busy > total) return null
        return ((busy.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    }

    /** Prefer deltas for cumulative kernel counters; raw lifetime totals are not a load sample. */
    fun counterDeltaPercent(
        previousBusy: Long,
        previousTotal: Long,
        currentBusy: Long,
        currentTotal: Long
    ): Int? {
        val busyDelta = currentBusy - previousBusy
        val totalDelta = currentTotal - previousTotal
        if (busyDelta < 0L || totalDelta <= 0L || busyDelta > totalDelta) return null
        return ((busyDelta.toDouble() / totalDelta.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    }

    private fun Long?.orZero(): Long = this ?: 0L
}

/**
 * Converts native stats into evidence without inventing an NPU/GPU percentage.
 * Native execution flags prove that work ran, not how busy the device was.
 */
object NativeAcceleratorEvidence {
    fun fromNativeStats(json: String?, accelerator: Accelerator): AcceleratorLoad {
        if (json.isNullOrBlank()) return AcceleratorLoad.unavailable("native stats unavailable")
        val parsed = runCatching { org.json.JSONObject(json) }.getOrNull()
            ?: return AcceleratorLoad.error("native stats malformed")
        return when (accelerator) {
            Accelerator.GPU -> {
                val observed = parsed.optBoolean("gpuOffloadExecutionObserved", false) ||
                    parsed.optBoolean("gpuOffloadActive", false)
                AcceleratorLoad.unavailable("native execution evidence; no utilization counter", observed)
            }
            Accelerator.NPU -> {
                val observed = parsed.optBoolean("npuExecutionProven", false) ||
                    parsed.optBoolean("provesNpuExecution", false) ||
                    sequenceOf("computeUnit", "geniexComputeUnit", "qairtComputeUnit")
                        .map { parsed.optString(it) }
                        .any { it.equals("htp", true) || it.equals("npu", true) }
                AcceleratorLoad.unavailable("native execution evidence; no utilization counter", observed)
            }
        }
    }

    enum class Accelerator { GPU, NPU }
}

interface SystemLoadDataSource {
    fun readCpuStat(): String?
    fun readProcessStat(): String?
    /** Process CPU time in milliseconds, independent of procfs permissions. */
    fun readProcessCpuTimeMillis(): Long? = null
    fun readMemory(): MemoryLoad
    fun readGpuLoad(): AcceleratorLoad
    fun readNpuLoad(): AcceleratorLoad
}

class SystemLoadSampler(
    private val source: SystemLoadDataSource,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val elapsedRealtimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val processorCount: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
) {
    private var previousCpu: CpuCounterSample? = null
    private var previousSystemCpu: CpuCounterSample? = null
    private var previousProcessCpuMillis: Long? = null
    private var previousElapsedRealtimeMillis: Long? = null

    fun sample(): UnifiedSystemLoadSnapshot {
        val aggregate = runCatching {
            source.readCpuStat()?.let(SystemLoadParser::parseCpuStat)
        }.getOrNull()
        val processTicks = runCatching {
            source.readProcessStat()?.let(SystemLoadParser::parseProcessTicks)
        }.getOrNull()
        val processCpuMillis = runCatching { source.readProcessCpuTimeMillis() }
            .getOrNull()
            ?.takeIf { it >= 0L }
        val elapsedNow = runCatching(elapsedRealtimeMillis).getOrNull()
        val cpu = if (aggregate != null && processTicks != null) {
            SystemLoadParser.withProcessTicks(aggregate, processTicks)
        } else {
            null
        }

        // `/proc/stat` may be hidden by device policy. `Process.getElapsedCpuTime()` is a
        // public Android API and can still provide MCA's own CPU usage. Keep system CPU
        // unavailable in this case instead of deriving it from the process sample.
        val processFallbackPercent = if (processCpuMillis != null && elapsedNow != null) {
            val oldCpuMillis = previousProcessCpuMillis
            val oldElapsedMillis = previousElapsedRealtimeMillis
            val cpuDelta = oldCpuMillis?.let { processCpuMillis - it }
            val elapsedDelta = oldElapsedMillis?.let { elapsedNow - it }
            if (cpuDelta != null && elapsedDelta != null && cpuDelta >= 0L && elapsedDelta > 0L) {
                ((cpuDelta.toDouble() / (elapsedDelta.toDouble() * processorCount.coerceAtLeast(1))) * 100.0)
                    .toInt()
                    .coerceIn(0, 100)
            } else {
                null
            }
        } else {
            null
        }
        previousProcessCpuMillis = processCpuMillis
        previousElapsedRealtimeMillis = elapsedNow

        val systemPercent = if (aggregate != null && previousSystemCpu != null) {
            SystemLoadParser.systemPercent(previousSystemCpu!!, aggregate)
        } else {
            null
        }
        previousSystemCpu = aggregate

        val procfsDelta = if (cpu != null && previousCpu != null) {
            SystemLoadParser.delta(previousCpu!!, cpu)
        } else {
            null
        }
        previousCpu = cpu
        val cpuLoad = CpuLoad(
            systemPercent = procfsDelta?.systemPercent ?: systemPercent,
            processPercent = procfsDelta?.processPercent ?: processFallbackPercent
        )
        return UnifiedSystemLoadSnapshot(
            timestampMillis = clockMillis(),
            cpu = cpuLoad,
            memory = runCatching { source.readMemory() }.getOrElse { MemoryLoad() },
            gpu = runCatching { source.readGpuLoad() }.getOrElse { AcceleratorLoad.error("gpu sampler") },
            npu = runCatching { source.readNpuLoad() }.getOrElse { AcceleratorLoad.error("npu sampler") }
        )
    }
}
