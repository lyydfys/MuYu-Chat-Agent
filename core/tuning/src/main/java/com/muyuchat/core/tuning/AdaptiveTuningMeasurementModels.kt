package com.muyuchat.core.tuning

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.ThermalStatus
import kotlin.math.max

data class MeasurementPoint(
    val timeMs: Long = System.currentTimeMillis(),
    val thermalStatus: ThermalStatus = ThermalStatus.Unknown,
    val batteryPercent: Int = -1,
    val isCharging: Boolean = false,
    val availableMemoryBytes: Long = 0,
    val pssBytes: Long = 0,
    val rssBytes: Long = 0,
    val lowMemoryTriggered: Boolean = false,
    val appInForeground: Boolean = true,
    val cpuSet: String? = null,
    val systemPressure: String? = null
)

data class MeasurementEnvelope(
    val start: MeasurementPoint,
    val end: MeasurementPoint,
    val coolingTimeMs: Long = 0,
    val samples: List<PerformanceSample> = emptyList()
) {
    val peakPssBytes: Long
        get() = max(max(start.pssBytes, end.pssBytes), samples.maxOfOrNull { it.pssBytes } ?: 0)
    val peakRssBytes: Long
        get() = max(max(start.rssBytes, end.rssBytes), samples.maxOfOrNull { it.rssBytes } ?: 0)
    val minimumAvailableMemoryBytes: Long
        get() = listOf(start.availableMemoryBytes, end.availableMemoryBytes)
            .filter { it > 0 }
            .minOrNull() ?: 0
}

data class PerformanceSample(
    val ttftMs: Long,
    val decodeTps: Double,
    val pssBytes: Long = 0,
    val rssBytes: Long = 0,
    val availableMemoryBytes: Long = 0
)

data class SafetyEnvelope(
    val version: String = "safety-v1",
    val minimumAvailableMemoryBytes: Long,
    val maximumPssBytes: Long,
    val maximumRssBytes: Long,
    val maximumThermalStatus: ThermalStatus = ThermalStatus.Severe,
    val minimumBatteryPercent: Int = 15,
    val requireCharging: Boolean = false,
    val requireForeground: Boolean = true,
    val perCandidateTimeoutMs: Long = 120_000,
    val totalTuningTimeoutMs: Long = 30 * 60_000L,
    val maximumCandidates: Int = 24,
    val maximumReloads: Int = 12
) {
    init {
        require(perCandidateTimeoutMs > 0)
        require(totalTuningTimeoutMs >= perCandidateTimeoutMs)
        require(maximumCandidates > 0)
        require(maximumReloads >= 0)
    }

    fun assess(point: MeasurementPoint): SafetyAssessment = assess(
        MeasurementEnvelope(start = point, end = point)
    )

    fun assess(measurement: MeasurementEnvelope): SafetyAssessment {
        val violations = buildList {
            if (measurement.start.lowMemoryTriggered || measurement.end.lowMemoryTriggered) {
                add(SafetyViolation("low_memory", "系统触发 low-memory"))
            }
            if (measurement.minimumAvailableMemoryBytes in 1 until minimumAvailableMemoryBytes) {
                add(SafetyViolation("memory_headroom", "可用内存低于安全下限"))
            }
            if (measurement.peakPssBytes > maximumPssBytes) {
                add(SafetyViolation("pss_limit", "PSS 超过安全上限"))
            }
            if (measurement.peakRssBytes > maximumRssBytes) {
                add(SafetyViolation("rss_limit", "RSS 超过安全上限"))
            }
            if (thermalSeverity(measurement.start.thermalStatus) > thermalSeverity(maximumThermalStatus) ||
                thermalSeverity(measurement.end.thermalStatus) > thermalSeverity(maximumThermalStatus)
            ) {
                add(SafetyViolation("thermal_limit", "温控状态超过安全上限"))
            }
            val battery = listOf(measurement.start.batteryPercent, measurement.end.batteryPercent)
                .filter { it >= 0 }
                .minOrNull()
            if (battery != null && battery < minimumBatteryPercent &&
                !measurement.start.isCharging && !measurement.end.isCharging
            ) {
                add(SafetyViolation("battery_limit", "电量低于安全下限"))
            }
            if (requireCharging && !measurement.start.isCharging) {
                add(SafetyViolation("charging_required", "当前调优模式要求充电"))
            }
            if (requireForeground && (!measurement.start.appInForeground || !measurement.end.appInForeground)) {
                add(SafetyViolation("background", "App 已进入后台"))
            }
        }
        return SafetyAssessment(passed = violations.isEmpty(), violations = violations)
    }

    companion object {
        fun forDevice(device: DeviceProfile): SafetyEnvelope {
            val total = device.displayTotalRamBytes.coerceAtLeast(1)
            val threshold = device.memoryThresholdBytes.takeIf { it > 0 } ?: 256L * MB
            return SafetyEnvelope(
                minimumAvailableMemoryBytes = max(threshold * 2, total / 12),
                maximumPssBytes = (total * 0.78).toLong(),
                maximumRssBytes = (total * 0.84).toLong()
            )
        }

        private const val MB = 1024L * 1024L
    }
}

data class SafetyViolation(val code: String, val message: String)

data class SafetyAssessment(
    val passed: Boolean,
    val violations: List<SafetyViolation>
)

enum class CorrectnessCaseCategory {
    DETERMINISTIC_FORMAT,
    CHINESE_INSTRUCTION,
    ROLE_TEMPLATE,
    MULTI_TURN_CONTEXT,
    OUTPUT_HYGIENE,
    LONG_CONTEXT,
    VISION_COUNTERFACTUAL,
    MODEL_FAMILY
}

data class CorrectnessCaseDefinition(
    val id: String,
    val category: CorrectnessCaseCategory,
    val promptVersion: String,
    val description: String
)

data class CanaryCaseResult(
    val caseId: String,
    val passed: Boolean,
    val detail: String? = null
)
