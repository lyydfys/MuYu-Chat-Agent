package com.muyuchat.core.tuning

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
