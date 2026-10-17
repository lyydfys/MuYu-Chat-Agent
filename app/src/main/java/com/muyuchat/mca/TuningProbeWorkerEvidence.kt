package com.muyuchat.mca

import com.muyuchat.core.tuning.CandidateHardGate
import com.muyuchat.core.tuning.MinimumTextCanaryPolicy
import com.muyuchat.core.tuning.PerformanceSample
import com.muyuchat.core.tuning.RepeatedCandidateCanaryObservation
import com.muyuchat.core.tuning.SpecializedCanaryViolation
import com.muyuchat.core.tuning.TuningExecutionProfile
import org.json.JSONArray
import org.json.JSONObject

internal fun FixedCanaryRun.toRepeatedObservation(
        candidate: TuningExecutionProfile,
        signatureMatched: Boolean
    ): RepeatedCandidateCanaryObservation {
        val correctness = error == null && MinimumTextCanaryPolicy.matches(output)
        val safe = !stats.isLowMemory && !beforeMemory.lowMemory && !afterMemory.lowMemory
        return RepeatedCandidateCanaryObservation(
            requestId = requestId,
            hardGate = CandidateHardGate(
                correctnessPassed = correctness,
                crashCount = 0,
                anrCount = 0,
                nativeFatalSignalCount = 0,
                lowMemoryTriggered = !safe,
                outputVisible = output.isNotBlank(),
                templateValid = correctness,
                safetyPassed = safe,
                signaturesMatch = signatureMatched && candidate.identityHash.isNotBlank()
            ),
            sample = PerformanceSample(
                ttftMs = stats.ttftMs.coerceAtLeast(0L),
                decodeTps = stats.decodeTps.takeIf { it.isFinite() && it > 0.0 }
                    ?: stats.e2eTps,
                pssBytes = afterMemory.pssBytes,
                rssBytes = afterMemory.rssBytes,
                availableMemoryBytes = afterMemory.availableBytes
            ),
            nativeStatsJson = nativeStatsJson
        )
    }

internal fun TuningProbeWorkerService.independentRunViolations(runs: List<FixedCanaryRun>): List<SpecializedCanaryViolation> {
        val requestIds = runs.map { it.requestId }
        val sequences = runs.map { it.sequenceAfter }
        return buildList {
            if (requestIds.any(String::isBlank) || requestIds.distinct().size != requestIds.size) {
                add(
                    SpecializedCanaryViolation(
                        "request_ids_not_independent",
                        "Every specialized canary must use a distinct non-blank request id."
                    )
                )
            }
            if (runs.any { it.sequenceAfter <= it.sequenceBefore } ||
                sequences.distinct().size != sequences.size ||
                sequences.zipWithNext().any { (left, right) -> right <= left }
            ) {
                add(
                    SpecializedCanaryViolation(
                        "native_sequences_not_independent",
                        "Every specialized canary must advance a distinct native generation sequence."
                    )
                )
            }
        }
    }

internal fun TuningProbeWorkerService.buildEvidenceJson(
        request: TuningProbeWorkerProtocol.Request,
        modelArtifactFingerprint: String,
        planProbes: List<String>,
        changedLoadFields: Set<String>,
        signatureEvidence: SignatureEvidence,
        runs: List<FixedCanaryRun>,
        startMemory: ProbeMemoryPoint,
        endMemory: ProbeMemoryPoint,
        violations: List<SpecializedCanaryViolation>
    ): JSONObject = JSONObject()
        .put("requestId", request.requestId)
        .put("probeKind", request.probeKind.name)
        .put("transactionId", request.transactionId)
        .put("identityKey", request.identityKey)
        .put("profileId", request.profileId)
        .put("modelArtifactFingerprint", modelArtifactFingerprint.takeIf { SHA256_HEX.matches(it) })
        .put("probes", JSONArray(planProbes))
        .put("changedLoadFields", JSONArray(changedLoadFields.sorted()))
        .put("signatures", JSONObject()
            .put("matched", signatureEvidence.matched)
            .put("desired", signatureEvidence.desired)
            .put("resolvedLoad", signatureEvidence.resolvedLoad)
            .put("activeLoaded", signatureEvidence.activeLoaded)
            .put("committedExecution", signatureEvidence.committedExecution)
            .put("runtimeOverride", signatureEvidence.runtimeOverride)
            .put("effectiveExecution", signatureEvidence.effectiveExecution)
        )
        .put("memory", JSONObject()
            .put("start", startMemory.toJson())
            .put("end", endMemory.toJson())
        )
        .put("runs", JSONArray().apply {
            runs.forEach { run ->
                val native = JSONObject(run.nativeStatsJson)
                put(JSONObject()
                    .put("purpose", run.purpose)
                    .put("requestId", run.requestId)
                    .put("promptFingerprint", run.promptFingerprint)
                    .put("generationSequenceBefore", run.sequenceBefore)
                    .put("generationSequenceAfter", run.sequenceAfter)
                    .put("promptTokens", run.stats.promptTokens)
                    .put("completionTokens", run.stats.completionTokens)
                    .put("ttftMs", run.stats.ttftMs)
                    .put("decodeTps", run.stats.decodeTps.takeIf(Double::isFinite))
                    .put("nCtx", native.optInt("nCtx", run.stats.nCtx))
                    .put("nBatch", native.opt("nBatch"))
                    .put("nUbatch", native.opt("nUbatch"))
                    .put("cacheTypeK", native.opt("cacheTypeK"))
                    .put("cacheTypeV", native.opt("cacheTypeV"))
                    .put("flashAttn", native.opt("flashAttn"))
                    .put("contextShifts", native.opt("contextShifts"))
                    .put("speculative", native.optJSONObject("speculative"))
                    .put("error", LocalDiagnosticRedactor.sanitize(run.error))
                    .put("memoryBefore", run.beforeMemory.toJson())
                    .put("memoryAfter", run.afterMemory.toJson())
                )
            }
        })
        .put("violations", JSONArray().apply {
            violations.forEach { violation ->
                put(JSONObject().put("code", violation.code).put("message", violation.message))
            }
        })

internal data class SignatureEvidence(
    val matched: Boolean,
    val desired: String,
    val resolvedLoad: String,
    val activeLoaded: String?,
    val committedExecution: String,
    val runtimeOverride: String,
    val effectiveExecution: String?
)

private val SHA256_HEX = Regex("^[A-Fa-f0-9]{64}$")
