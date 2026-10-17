package com.muyuchat.core.tuning

import kotlin.math.max

data class CanaryRunResult(
    val cases: List<CanaryCaseResult>,
    val outputVisible: Boolean,
    val templateValid: Boolean,
    val crashCount: Int = 0,
    val anrCount: Int = 0,
    val nativeFatalSignalCount: Int = 0,
    val lowMemoryTriggered: Boolean = false,
    val samples: List<PerformanceSample> = emptyList()
)

data class CorrectnessSuite(
    val id: String,
    val version: String,
    val cases: List<CorrectnessCaseDefinition>
) {
    init {
        require(cases.isNotEmpty()) { "CorrectnessSuite must contain cases" }
        require(cases.map { it.id }.distinct().size == cases.size) { "Correctness case ids must be unique" }
    }

    fun evaluate(run: CanaryRunResult): CorrectnessAssessment {
        val byId = run.cases.associateBy { it.caseId }
        val missing = cases.map { it.id }.filterNot(byId::containsKey)
        val failed = cases.mapNotNull { definition ->
            byId[definition.id]?.takeUnless { it.passed }
        }
        val passed = missing.isEmpty() && failed.isEmpty() && run.outputVisible && run.templateValid
        return CorrectnessAssessment(
            passed = passed,
            missingCaseIds = missing,
            failedCases = failed,
            outputVisible = run.outputVisible,
            templateValid = run.templateValid
        )
    }

    companion object {
        const val MINIMUM_TEXT_SUITE_ID = "minimum-text-v1"

        fun minimumText(): CorrectnessSuite = CorrectnessSuite(
            id = MINIMUM_TEXT_SUITE_ID,
            version = "1",
            cases = listOf(
                CorrectnessCaseDefinition("deterministic-format", CorrectnessCaseCategory.DETERMINISTIC_FORMAT, "v1", "固定数字和格式输出"),
                CorrectnessCaseDefinition("chinese-instruction", CorrectnessCaseCategory.CHINESE_INSTRUCTION, "v1", "中文指令遵循"),
                CorrectnessCaseDefinition("role-template", CorrectnessCaseCategory.ROLE_TEMPLATE, "v1", "role 与模板顺序"),
                CorrectnessCaseDefinition("two-turn-context", CorrectnessCaseCategory.MULTI_TURN_CONTEXT, "v1", "两轮上下文"),
                CorrectnessCaseDefinition("output-hygiene", CorrectnessCaseCategory.OUTPUT_HYGIENE, "v1", "空输出、乱码、重复和特殊 token 检测")
            )
        )
    }
}

data class CorrectnessAssessment(
    val passed: Boolean,
    val missingCaseIds: List<String>,
    val failedCases: List<CanaryCaseResult>,
    val outputVisible: Boolean,
    val templateValid: Boolean
)

data class CandidateHardGate(
    val correctnessPassed: Boolean,
    val crashCount: Int,
    val anrCount: Int,
    val nativeFatalSignalCount: Int,
    val lowMemoryTriggered: Boolean,
    val outputVisible: Boolean,
    val templateValid: Boolean,
    val safetyPassed: Boolean,
    val signaturesMatch: Boolean
) {
    val passed: Boolean
        get() = correctnessPassed && crashCount == 0 && anrCount == 0 &&
            nativeFatalSignalCount == 0 && !lowMemoryTriggered && outputVisible &&
            templateValid && safetyPassed && signaturesMatch
}

data class CandidateScore(
    val eligible: Boolean,
    val value: Double?,
    val medianDecodeTps: Double,
    val medianTtftMs: Double,
    val decodeVariance: Double,
    val reason: String? = null
)

object CandidateScorer {
    fun score(
        hardGate: CandidateHardGate,
        measurement: MeasurementEnvelope
    ): CandidateScore {
        if (!hardGate.passed) {
            return CandidateScore(
                eligible = false,
                value = null,
                medianDecodeTps = median(measurement.samples.map { it.decodeTps }),
                medianTtftMs = median(measurement.samples.map { it.ttftMs.toDouble() }),
                decodeVariance = variance(measurement.samples.map { it.decodeTps }),
                reason = "候选未通过正确性/安全硬门槛"
            )
        }
        val decode = measurement.samples.map { it.decodeTps }.filter { it.isFinite() && it >= 0.0 }
        val ttft = measurement.samples.map { it.ttftMs.toDouble() }.filter { it >= 0.0 }
        val medianDecode = median(decode)
        val medianTtft = median(ttft)
        val decodeVariance = variance(decode)
        val memoryHeadroomGiB = measurement.minimumAvailableMemoryBytes / GIB.toDouble()
        val thermalPenalty = max(
            thermalSeverity(measurement.end.thermalStatus) - thermalSeverity(measurement.start.thermalStatus),
            0
        ) * 0.75
        val repeatabilityPenalty = decodeVariance * 0.25
        val score = medianDecode - medianTtft / 5_000.0 + memoryHeadroomGiB.coerceAtMost(4.0) * 0.15 -
            thermalPenalty - repeatabilityPenalty
        return CandidateScore(
            eligible = true,
            value = score,
            medianDecodeTps = medianDecode,
            medianTtftMs = medianTtft,
            decodeVariance = decodeVariance
        )
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private fun variance(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val average = values.average()
        return values.sumOf { value -> (value - average) * (value - average) } / values.size
    }

    private const val GIB = 1024L * 1024L * 1024L
}
