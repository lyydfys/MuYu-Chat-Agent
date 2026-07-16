package com.muyuchat.mca

import com.muyuchat.core.tuning.ModelTuningCapabilities
import com.muyuchat.core.tuning.StagedTuningCandidatePolicy
import com.muyuchat.core.tuning.TuningCandidatePatch
import com.muyuchat.core.tuning.TuningExecutionProfile
import com.muyuchat.core.tuning.TuningSearchDepth
import com.muyuchat.core.tuning.TuningSearchStage
import org.json.JSONArray
import org.json.JSONObject

/** A probe candidate containing execution parameters only. */
internal data class AgentTuningStageCandidate(
    val stage: TuningSearchStage,
    val label: String,
    val profile: TuningExecutionProfile
) {
    val executionSummary: String
        get() = tuningExecutionParameterSummary(profile)
}

internal fun agentTuningStages(
    depth: TuningSearchDepth,
    capabilities: ModelTuningCapabilities,
    userOverrides: Set<String>
): List<TuningSearchStage> = StagedTuningCandidatePolicy.stagesFor(
    depth = depth,
    capabilities = capabilities,
    userOverrides = userOverrides
)

/**
 * Builds one bounded stage from the best profile selected by the previous stage.
 *
 * The second override filter is deliberate defense in depth. Some stages update
 * dependent fields (for example context may clamp batch); those dependent
 * changes must not overwrite an explicit user value even when the stage itself
 * remains eligible for search.
 */
internal fun buildAgentTuningStageCandidates(
    stage: TuningSearchStage,
    base: TuningExecutionProfile,
    capabilities: ModelTuningCapabilities,
    cpuCores: Int,
    estimatedBigCores: Int,
    depth: TuningSearchDepth,
    userOverrides: Set<String>,
    profileIdPrefix: String,
    revision: Long
): List<AgentTuningStageCandidate> = StagedTuningCandidatePolicy.candidatesFor(
    stage = stage,
    base = base,
    capabilities = capabilities,
    cpuCores = cpuCores,
    estimatedBigCores = estimatedBigCores,
    depth = depth,
    userOverrides = userOverrides
).map { patch -> patch.withoutUserOverrides(userOverrides) }
    .filter { patch -> patch.changedFields.isNotEmpty() }
    .mapIndexed { index, patch ->
        val profile = patch.applyTo(
            base = base,
            profileId = "$profileIdPrefix-${stage.name.lowercase()}-$index",
            revision = revision
        )
        AgentTuningStageCandidate(
            stage = stage,
            label = patch.label,
            profile = profile
        )
    }
    .distinctBy { candidate ->
        candidate.profile.engineProfile.resolvedLoadSignature.digest to
            candidate.profile.engineProfile.committedExecutionSignature.digest
    }

/** Complete canonical execution profile for progress and diagnostics. */
internal fun tuningExecutionParameterSummary(profile: TuningExecutionProfile): String {
    val values = profile.engineProfile.resolvedLoadBoundValues
        .plus(profile.engineProfile.hotExecutionValues)
        .plus(profile.engineProfile.modelBehaviorValues)
    return values.fields
        .sorted()
        .joinToString(" · ") { field -> "$field=${summaryValue(values.value(field))}" }
}

private fun TuningCandidatePatch.withoutUserOverrides(userOverrides: Set<String>): TuningCandidatePatch = copy(
    nThreads = nThreads.takeUnless { "n_threads" in userOverrides },
    nThreadsBatch = nThreadsBatch.takeUnless { "n_threads_batch" in userOverrides },
    nCtx = nCtx.takeUnless { "n_ctx" in userOverrides },
    nBatch = nBatch.takeUnless { "n_batch" in userOverrides },
    nUbatch = nUbatch.takeUnless { "n_ubatch" in userOverrides },
    cacheTypeK = cacheTypeK.takeUnless { "cache_type_k" in userOverrides },
    cacheTypeV = cacheTypeV.takeUnless { "cache_type_v" in userOverrides },
    flashAttention = flashAttention.takeUnless { "flash_attn" in userOverrides },
    speculativeType = speculativeType.takeUnless { "spec_type" in userOverrides },
    speculativeDraftMax = speculativeDraftMax.takeUnless { "spec_draft_n_max" in userOverrides }
)

private fun summaryValue(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject, is JSONArray -> value.toString()
    else -> value.toString().replace(Regex("\\s+"), " ")
}
