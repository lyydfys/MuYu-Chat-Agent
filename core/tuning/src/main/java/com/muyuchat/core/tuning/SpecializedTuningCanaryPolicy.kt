package com.muyuchat.core.tuning

import org.json.JSONObject
import kotlin.math.abs

/** Additional proofs required when a candidate changes a high-risk execution field. */
enum class SpecializedCanaryProbe {
    MINIMUM_TEXT,
    LONG_CONTEXT_NEEDLE,
    REPEATED_BATCH_KV,
    SPECULATIVE_MTP
}

/** Process boundary required before native candidate execution is allowed. */
enum class CandidateProcessBoundary {
    CALLER_PROCESS_ALLOWED,
    ISOLATED_PROCESS_REQUIRED,
    REJECT_IDENTITY_MISMATCH
}

data class SpecializedCanaryViolation(
    val code: String,
    val message: String
)

data class SpecializedCanaryAssessment(
    val violations: List<SpecializedCanaryViolation> = emptyList()
) {
    val passed: Boolean
        get() = violations.isEmpty()
}

data class TuningCandidateCanaryPlan(
    val probes: Set<SpecializedCanaryProbe>,
    val changedLoadFields: Set<String>,
    val changedHotFields: Set<String>,
    val requiredRepeatedRuns: Int,
    val processBoundary: CandidateProcessBoundary,
    val longContextSpec: LongContextNeedleCanarySpec? = null
) {
    init {
        require(SpecializedCanaryProbe.MINIMUM_TEXT in probes) {
            "Every tuning candidate must retain the minimum-text correctness gate"
        }
        require(requiredRepeatedRuns > 0)
        require(
            (SpecializedCanaryProbe.LONG_CONTEXT_NEEDLE in probes) == (longContextSpec != null)
        ) { "Long-context probe and specification must be supplied together" }
    }
}

/**
 * Derives canary and process-isolation requirements from authoritative profile
 * differences. Device identity is deliberately absent: hardware discovery may
 * tune values, but never changes whether a candidate is allowed to be tried.
 */
object TuningCandidateCanaryPlanner {
    val batchKvFields: Set<String> = setOf(
        "n_batch",
        "n_ubatch",
        "cache_type_k",
        "cache_type_v",
        "flash_attn"
    )
    val speculativeFields: Set<String> = setOf("spec_type", "spec_draft_n_max")

    const val DEFAULT_REPEATED_RUNS: Int = 2

    fun plan(
        committed: TuningExecutionProfile,
        candidate: TuningExecutionProfile
    ): TuningCandidateCanaryPlan {
        val sameIdentity = committed.identityHash == candidate.identityHash
        val loadChanges = candidate.engineProfile.resolvedLoadBoundValues
            .differences(committed.engineProfile.resolvedLoadBoundValues)
        val hotChanges = candidate.engineProfile.hotExecutionValues
            .differences(committed.engineProfile.hotExecutionValues)
        val batchKvChanged = loadChanges.any(batchKvFields::contains)
        val speculativeChanged = loadChanges.any(speculativeFields::contains)
        val speculativeEnabled = candidate.loadBound.speculativeType
            .equals(SPECULATIVE_MTP_TYPE, ignoreCase = true)
        val contextIncreased = sameIdentity && candidate.loadBound.nCtx > committed.loadBound.nCtx

        val probes = linkedSetOf(SpecializedCanaryProbe.MINIMUM_TEXT).apply {
            if (contextIncreased) add(SpecializedCanaryProbe.LONG_CONTEXT_NEEDLE)
            if (batchKvChanged) add(SpecializedCanaryProbe.REPEATED_BATCH_KV)
            if (speculativeChanged || speculativeEnabled) add(SpecializedCanaryProbe.SPECULATIVE_MTP)
        }
        val boundary = when {
            !sameIdentity -> CandidateProcessBoundary.REJECT_IDENTITY_MISMATCH
            loadChanges.isNotEmpty() -> CandidateProcessBoundary.ISOLATED_PROCESS_REQUIRED
            else -> CandidateProcessBoundary.CALLER_PROCESS_ALLOWED
        }

        return TuningCandidateCanaryPlan(
            probes = probes,
            changedLoadFields = loadChanges,
            changedHotFields = hotChanges,
            requiredRepeatedRuns = if (batchKvChanged) DEFAULT_REPEATED_RUNS else 1,
            processBoundary = boundary,
            longContextSpec = if (contextIncreased) {
                LongContextNeedleCanaryPolicy.specFor(candidate.loadBound.nCtx)
            } else {
                null
            }
        )
    }

    private const val SPECULATIVE_MTP_TYPE = "draft-mtp"
}

enum class CandidateExecutionEnvironment {
    CALLER_PROCESS,
    ISOLATED_PROCESS
}

/** Enforces the planner's process boundary before the native load is attempted. */
object CandidateIsolationPolicy {
    fun assess(
        plan: TuningCandidateCanaryPlan,
        environment: CandidateExecutionEnvironment
    ): SpecializedCanaryAssessment {
        val violations = buildList {
            when (plan.processBoundary) {
                CandidateProcessBoundary.REJECT_IDENTITY_MISMATCH -> add(
                    SpecializedCanaryViolation(
                        "identity_mismatch",
                        "Candidate and committed profile belong to different runtime identities"
                    )
                )
                CandidateProcessBoundary.ISOLATED_PROCESS_REQUIRED -> {
                    if (environment != CandidateExecutionEnvironment.ISOLATED_PROCESS) {
                        add(
                            SpecializedCanaryViolation(
                                "isolated_process_required",
                                "Load-bound candidates must execute in a disposable isolated process"
                            )
                        )
                    }
                }
                CandidateProcessBoundary.CALLER_PROCESS_ALLOWED -> Unit
            }
        }
        return SpecializedCanaryAssessment(violations)
    }
}

data class LongContextNeedleCanarySpec(
    val requestedContextTokens: Int,
    val minimumPromptTokens: Int,
    val marker: String,
    val expectedOutput: String,
    val maximumOutputTokens: Int = 16
) {
    init {
        require(requestedContextTokens > 0)
        require(minimumPromptTokens > 0)
        require(maximumOutputTokens > 0)
        require(minimumPromptTokens + maximumOutputTokens < requestedContextTokens) {
            "Long-context canary must leave native context headroom"
        }
        require(marker.isNotBlank())
        require(expectedOutput.isNotBlank())
    }

    /** The runner fills both placeholders to a tokenizer-measured target size. */
    fun prompt(fillerBefore: String, fillerAfter: String): String = buildString {
        require("NEEDLE=" !in fillerBefore && "NEEDLE=" !in fillerAfter) {
            "Filler must not contain another needle"
        }
        require(marker !in fillerBefore && marker !in fillerAfter) {
            "Filler must not contain the canary marker"
        }
        append("Inside the filler there is exactly one line beginning with NEEDLE=. ")
        append("At the end, return that complete line exactly and nothing else.\nBEGIN_FILLER\n")
        append(fillerBefore)
        append("\nNEEDLE=")
        append(marker)
        append("\n")
        append(fillerAfter)
        append("\nEND_FILLER\nQuestion: What was NEEDLE?")
    }
}

data class LongContextNeedleEvidence(
    val requestId: String,
    val generationSequenceBefore: Long,
    val generationSequenceAfter: Long,
    val effectiveContextTokens: Int,
    val promptTokens: Int,
    val needleTokenIndex: Int,
    val completionTokens: Int,
    val contextShifts: Int,
    val output: String,
    val nativeError: String? = null
)

object LongContextNeedleCanaryPolicy {
    private const val CONTEXT_HEADROOM_TOKENS = 64
    private const val OUTPUT_TOKENS = 16

    fun specFor(requestedContextTokens: Int): LongContextNeedleCanarySpec {
        require(requestedContextTokens > CONTEXT_HEADROOM_TOKENS + OUTPUT_TOKENS)
        val maximumPrompt = requestedContextTokens - CONTEXT_HEADROOM_TOKENS - OUTPUT_TOKENS
        val depthTarget = (requestedContextTokens * 3L / 4L).toInt()
        val beyondLegacyWindow = if (requestedContextTokens > 4096) 4097 else 1
        val minimumPrompt = minOf(maximumPrompt, maxOf(depthTarget, beyondLegacyWindow))
        val marker = "MCA_CTX_NEEDLE_${requestedContextTokens}_17"
        return LongContextNeedleCanarySpec(
            requestedContextTokens = requestedContextTokens,
            minimumPromptTokens = minimumPrompt,
            marker = marker,
            expectedOutput = "NEEDLE=$marker",
            maximumOutputTokens = OUTPUT_TOKENS
        )
    }

    fun assess(
        spec: LongContextNeedleCanarySpec,
        evidence: LongContextNeedleEvidence
    ): SpecializedCanaryAssessment {
        val violations = buildList {
            if (evidence.requestId.isBlank()) {
                add(SpecializedCanaryViolation("request_id_missing", "Long-context canary needs a request id"))
            }
            if (evidence.generationSequenceAfter <= evidence.generationSequenceBefore) {
                add(
                    SpecializedCanaryViolation(
                        "generation_not_observed",
                        "Native generation sequence did not advance for the long-context canary"
                    )
                )
            }
            if (!evidence.nativeError.isNullOrBlank()) {
                add(SpecializedCanaryViolation("native_error", evidence.nativeError))
            }
            if (evidence.effectiveContextTokens < spec.requestedContextTokens) {
                add(
                    SpecializedCanaryViolation(
                        "effective_context_too_small",
                        "Native context ${evidence.effectiveContextTokens} is below requested ${spec.requestedContextTokens}"
                    )
                )
            }
            if (evidence.promptTokens < spec.minimumPromptTokens) {
                add(
                    SpecializedCanaryViolation(
                        "prompt_not_long_enough",
                        "Prompt used ${evidence.promptTokens} tokens; ${spec.minimumPromptTokens} are required"
                    )
                )
            }
            val minimumNeedleIndex = evidence.promptTokens / 4
            val maximumNeedleIndex = evidence.promptTokens * 3 / 4
            if (evidence.needleTokenIndex !in minimumNeedleIndex..maximumNeedleIndex) {
                add(
                    SpecializedCanaryViolation(
                        "needle_not_deep",
                        "Needle must be tokenizer-measured inside the middle half of the long prompt"
                    )
                )
            }
            if (evidence.promptTokens + evidence.completionTokens >= evidence.effectiveContextTokens) {
                add(
                    SpecializedCanaryViolation(
                        "context_headroom_exhausted",
                        "Canary exhausted the effective context instead of testing stable recall"
                    )
                )
            }
            if (evidence.completionTokens <= 0) {
                add(SpecializedCanaryViolation("empty_completion", "Long-context canary produced no tokens"))
            }
            if (evidence.contextShifts != 0) {
                add(
                    SpecializedCanaryViolation(
                        "unexpected_context_shift",
                        "Needle recall must pass without silently shifting away the tested prompt"
                    )
                )
            }
            if (!matches(spec, evidence.output)) {
                add(
                    SpecializedCanaryViolation(
                        "needle_recall_failed",
                        "Output did not exactly recall the long-context marker"
                    )
                )
            }
        }
        return SpecializedCanaryAssessment(violations)
    }

    fun matches(spec: LongContextNeedleCanarySpec, output: String): Boolean {
        if (output.isEmpty() || '\uFFFD' in output) return false
        val normalized = output
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .removeSuffix("\n")
        return normalized == spec.expectedOutput
    }
}

data class NativeSpeculativeStats(
    val generationSequence: Long,
    val completionTokens: Int,
    val specType: String,
    val specDraftNMax: Int,
    val requested: Boolean,
    val contextReady: Boolean,
    val activeForRequest: Boolean,
    val draftedTokens: Long,
    val acceptedTokens: Long,
    val steps: Long,
    val acceptanceRate: Double,
    val reason: String,
    val lastError: String
) {
    companion object {
        fun parse(nativeStatsJson: String): NativeSpeculativeStats? = runCatching {
            val root = JSONObject(nativeStatsJson)
            val speculative = root.optJSONObject("speculative") ?: return null
            NativeSpeculativeStats(
                generationSequence = root.optLong("generationSequence", -1L),
                completionTokens = root.optInt("completionTokens", -1),
                specType = root.optString("specType", ""),
                specDraftNMax = root.optInt("specDraftNMax", -1),
                requested = speculative.optBoolean("requested", false),
                contextReady = speculative.optBoolean("contextReady", false),
                activeForRequest = speculative.optBoolean("activeForRequest", false),
                draftedTokens = speculative.optLong("draftedTokens", -1L),
                acceptedTokens = speculative.optLong("acceptedTokens", -1L),
                steps = speculative.optLong("steps", -1L),
                acceptanceRate = speculative.optDouble("acceptanceRate", Double.NaN),
                reason = speculative.optString("reason", ""),
                lastError = root.optString("lastError", "")
            )
        }.getOrNull()
    }
}

/** Validates that draft-MTP was used by this exact canary request, not a stale earlier request. */
object SpeculativeMtpCanaryPolicy {
    fun assess(
        candidate: TuningExecutionProfile,
        beforeNativeStatsJson: String,
        afterNativeStatsJson: String
    ): SpecializedCanaryAssessment {
        val enabled = candidate.loadBound.speculativeType.equals("draft-mtp", ignoreCase = true)
        val before = NativeSpeculativeStats.parse(beforeNativeStatsJson)
        val after = NativeSpeculativeStats.parse(afterNativeStatsJson)
        val violations = buildList {
            if (after == null) {
                add(
                    SpecializedCanaryViolation(
                        "speculative_stats_missing",
                        "Native stats do not expose the speculative execution witness"
                    )
                )
                return@buildList
            }
            if (!after.lastError.isBlank()) {
                add(SpecializedCanaryViolation("native_error", after.lastError))
            }
            if (!enabled) {
                if (!after.specType.equals("none", ignoreCase = true) || after.specDraftNMax != 0 || after.requested) {
                    add(
                        SpecializedCanaryViolation(
                            "speculative_disable_mismatch",
                            "Native runtime still reports draft-MTP for a disabled candidate"
                        )
                    )
                }
                return@buildList
            }

            val expectedDraftMax = candidate.loadBound.speculativeDraftMax ?: 0
            if (!after.specType.equals("draft-mtp", ignoreCase = true)) {
                add(SpecializedCanaryViolation("spec_type_mismatch", "Native runtime did not load draft-mtp"))
            }
            if (expectedDraftMax <= 0 || after.specDraftNMax != expectedDraftMax) {
                add(
                    SpecializedCanaryViolation(
                        "spec_draft_max_mismatch",
                        "Native draft maximum ${after.specDraftNMax} does not match candidate $expectedDraftMax"
                    )
                )
            }
            if (!after.requested) {
                add(SpecializedCanaryViolation("spec_not_requested", "Native runtime did not request draft-MTP"))
            }
            if (!after.contextReady) {
                add(SpecializedCanaryViolation("spec_context_not_ready", "MTP native context is not ready"))
            }
            if (!after.activeForRequest || after.reason != "active") {
                add(
                    SpecializedCanaryViolation(
                        "spec_request_not_active",
                        "The canary request did not enter the draft-MTP generation path"
                    )
                )
            }
            if (before == null) {
                add(
                    SpecializedCanaryViolation(
                        "spec_before_stats_missing",
                        "Stats must be sampled immediately before generation to reject stale MTP counters"
                    )
                )
                return@buildList
            }
            if (before.lastError.isNotBlank()) {
                add(SpecializedCanaryViolation("spec_before_native_error", before.lastError))
            }
            if (!before.specType.equals("draft-mtp", ignoreCase = true) ||
                before.specDraftNMax != expectedDraftMax || !before.requested || !before.contextReady
            ) {
                add(
                    SpecializedCanaryViolation(
                        "spec_before_config_mismatch",
                        "Pre-generation stats were not sampled from the loaded MTP candidate"
                    )
                )
            }
            if (listOf(
                    before.draftedTokens,
                    before.acceptedTokens,
                    before.steps,
                    after.draftedTokens,
                    after.acceptedTokens,
                    after.steps
                ).any { it < 0L }
            ) {
                add(
                    SpecializedCanaryViolation(
                        "spec_counters_missing",
                        "Native MTP counters must be present before and after generation"
                    )
                )
            }
            if (after.generationSequence <= before.generationSequence) {
                add(
                    SpecializedCanaryViolation(
                        "generation_not_observed",
                        "Native generation sequence did not advance for the MTP canary"
                    )
                )
            }
            if (after.completionTokens <= 0) {
                add(SpecializedCanaryViolation("empty_completion", "MTP canary produced no completion tokens"))
            }
            val draftedDelta = after.draftedTokens - before.draftedTokens
            val acceptedDelta = after.acceptedTokens - before.acceptedTokens
            val stepsDelta = after.steps - before.steps
            if (draftedDelta < 0L || acceptedDelta < 0L || stepsDelta < 0L) {
                add(SpecializedCanaryViolation("spec_counter_regressed", "Native MTP counters regressed"))
            }
            if (stepsDelta <= 0) {
                add(SpecializedCanaryViolation("spec_steps_missing", "No speculative verification step ran"))
            }
            if (draftedDelta <= 0) {
                add(SpecializedCanaryViolation("spec_drafts_missing", "MTP produced no draft tokens"))
            }
            if (acceptedDelta <= 0) {
                add(SpecializedCanaryViolation("spec_accepts_missing", "MTP accepted no draft token"))
            }
            if (acceptedDelta > draftedDelta || after.acceptedTokens > after.draftedTokens) {
                add(
                    SpecializedCanaryViolation(
                        "spec_counter_invalid",
                        "Accepted draft tokens exceed drafted tokens"
                    )
                )
            }
            if (!after.acceptanceRate.isFinite() || after.acceptanceRate !in 0.0..1.0) {
                add(SpecializedCanaryViolation("spec_rate_invalid", "Native MTP acceptance rate is invalid"))
            } else if (after.draftedTokens > 0L) {
                val expectedRate = after.acceptedTokens.toDouble() / after.draftedTokens.toDouble()
                if (abs(expectedRate - after.acceptanceRate) > 0.000_001) {
                    add(
                        SpecializedCanaryViolation(
                            "spec_rate_inconsistent",
                            "Native MTP acceptance rate does not match its counters"
                        )
                    )
                }
            }
        }
        return SpecializedCanaryAssessment(violations)
    }
}

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
