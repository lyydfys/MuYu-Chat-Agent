package com.muyuchat.core.tuning

import org.json.JSONObject

data class RepeatedCandidateCanaryObservation(
    val requestId: String,
    val hardGate: CandidateHardGate,
    val sample: PerformanceSample,
    val nativeStatsJson: String
)

private data class NativeBatchKvStats(
    val generationSequence: Long,
    val nBatch: Int?,
    val nUbatch: Int?,
    val cacheTypeK: String?,
    val cacheTypeV: String?,
    val flashAttention: String?,
    val lastError: String
) {
    companion object {
        fun parse(nativeStatsJson: String): NativeBatchKvStats? = runCatching {
            val root = JSONObject(nativeStatsJson)
            NativeBatchKvStats(
                generationSequence = root.optLong("generationSequence", -1L),
                nBatch = root.optionalInt("nBatch"),
                nUbatch = root.optionalInt("nUbatch"),
                cacheTypeK = root.optionalString("cacheTypeK"),
                cacheTypeV = root.optionalString("cacheTypeV"),
                flashAttention = root.optionalString("flashAttn"),
                lastError = root.optString("lastError", "")
            )
        }.getOrNull()
    }
}

/** Requires independent, safe executions and exact native effective batch/KV values. */
object BatchKvCanaryPolicy {
    fun assess(
        candidate: TuningExecutionProfile,
        observations: List<RepeatedCandidateCanaryObservation>,
        requiredRuns: Int = TuningCandidateCanaryPlanner.DEFAULT_REPEATED_RUNS
    ): SpecializedCanaryAssessment {
        require(requiredRuns >= 2) { "Batch/KV tuning requires at least two independent runs" }
        val expected = candidate.engineProfile.resolvedLoadBoundValues
        val expectedFields = expected.fields.intersect(TuningCandidateCanaryPlanner.batchKvFields)
        val parsed = observations.map { NativeBatchKvStats.parse(it.nativeStatsJson) }
        val violations = buildList {
            if (expectedFields.isEmpty()) {
                add(
                    SpecializedCanaryViolation(
                        "batch_kv_profile_missing",
                        "Candidate does not expose authoritative batch/KV values"
                    )
                )
            }
            if (observations.size < requiredRuns) {
                add(
                    SpecializedCanaryViolation(
                        "repeated_runs_missing",
                        "Batch/KV candidate requires $requiredRuns independent successful runs"
                    )
                )
            }
            val requestIds = observations.map { it.requestId }
            if (requestIds.any(String::isBlank) || requestIds.distinct().size != requestIds.size) {
                add(
                    SpecializedCanaryViolation(
                        "request_ids_not_independent",
                        "Repeated canaries must use distinct non-blank request ids"
                    )
                )
            }
            if (parsed.any { it == null }) {
                add(
                    SpecializedCanaryViolation(
                        "batch_kv_stats_missing",
                        "Every repeated run must expose native batch/KV statistics"
                    )
                )
            }
            val sequences = parsed.mapNotNull { it?.generationSequence }
            if (sequences.any { it <= 0L } || sequences.distinct().size != sequences.size ||
                sequences.zipWithNext().any { (left, right) -> right <= left }
            ) {
                add(
                    SpecializedCanaryViolation(
                        "generation_sequences_not_independent",
                        "Repeated canaries must advance the native generation sequence"
                    )
                )
            }

            observations.forEachIndexed { index, observation ->
                val label = "run_${index + 1}"
                val gate = observation.hardGate
                if (!gate.correctnessPassed || !gate.outputVisible || !gate.templateValid) {
                    add(SpecializedCanaryViolation("${label}_correctness", "Repeated run failed correctness"))
                }
                if (!gate.safetyPassed || gate.lowMemoryTriggered) {
                    add(SpecializedCanaryViolation("${label}_memory_safety", "Repeated run failed memory safety"))
                }
                if (!gate.signaturesMatch) {
                    add(SpecializedCanaryViolation("${label}_signature", "Repeated run signature mismatched"))
                }
                if (gate.crashCount != 0 || gate.anrCount != 0 || gate.nativeFatalSignalCount != 0) {
                    add(SpecializedCanaryViolation("${label}_stability", "Repeated run reported a crash, ANR, or fatal signal"))
                }
                if (!observation.sample.decodeTps.isFinite() || observation.sample.decodeTps <= 0.0 ||
                    observation.sample.ttftMs < 0L
                ) {
                    add(SpecializedCanaryViolation("${label}_performance", "Repeated run has invalid performance data"))
                }

                val stats = parsed[index] ?: return@forEachIndexed
                if (stats.lastError.isNotBlank()) {
                    add(SpecializedCanaryViolation("${label}_native_error", stats.lastError))
                }
                expectedFields.forEach { field ->
                    val expectedValue = expected.value(field)
                    val actualValue: Any? = when (field) {
                        "n_batch" -> stats.nBatch
                        "n_ubatch" -> stats.nUbatch
                        "cache_type_k" -> stats.cacheTypeK
                        "cache_type_v" -> stats.cacheTypeV
                        "flash_attn" -> stats.flashAttention
                        else -> null
                    }
                    if (!equivalentNativeValue(expectedValue, actualValue)) {
                        add(
                            SpecializedCanaryViolation(
                                "${label}_${field}_mismatch",
                                "Native $field=$actualValue does not match candidate $expectedValue"
                            )
                        )
                    }
                }
                if (stats.nBatch != null && stats.nUbatch != null && stats.nUbatch > stats.nBatch) {
                    add(
                        SpecializedCanaryViolation(
                            "${label}_invalid_batch_relation",
                            "Native n_ubatch exceeds n_batch"
                        )
                    )
                }
            }
        }
        return SpecializedCanaryAssessment(violations)
    }
}

private fun JSONObject.optionalInt(field: String): Int? =
    if (has(field) && !isNull(field)) optInt(field) else null

private fun JSONObject.optionalString(field: String): String? =
    if (has(field) && !isNull(field)) optString(field) else null

private fun equivalentNativeValue(expected: Any?, actual: Any?): Boolean = when {
    expected is Number && actual is Number -> expected.toLong() == actual.toLong()
    expected is String && actual is String -> expected.trim().equals(actual.trim(), ignoreCase = true)
    else -> expected == actual
}
