package com.muyuchat.core.tuning

import org.json.JSONObject
import kotlin.math.abs

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
