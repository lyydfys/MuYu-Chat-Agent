package com.muyuchat.mca

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.AuthorizedPendingSignatureVerification
import com.muyuchat.core.engine.ParameterSignatureSnapshot
import com.muyuchat.core.engine.RuntimeOverrideSignature
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.tuning.MeasurementEnvelope
import com.muyuchat.core.tuning.MeasurementPoint
import com.muyuchat.core.tuning.PerformanceSample
import com.muyuchat.core.tuning.SafetyEnvelope
import com.muyuchat.core.tuning.TuningExecutionProfile
import org.json.JSONObject

/**
 * What turning a probe result into a canary verdict needs from its owner.
 *
 * Two members, and nothing else. Everything numeric the conversion reads -- the nineteen memory,
 * timing and token counters, the transaction and profile identity, the pass flag and the two
 * signatures -- arrives on `TuningProbeWorkerProtocol.Result` itself, so it stays a receiver
 * property rather than a port member. Only the device reading and the tolerant-double helper were
 * ViewModel state.
 */
internal interface CandidateCanaryResultPort {

    /** The current device profile, as the safety envelope needs it. */
    fun currentDeviceProfile(): DeviceProfile

    /**
     * The ViewModel's tolerant double reader: `0.0` when the field is absent, null, non-numeric or
     * non-finite. Kept on the owner side because it is a private extension there.
     */
    fun optionalFiniteDouble(root: JSONObject, field: String): Double
}

/**
 * Converts one tuning probe result into a candidate canary verdict: runtime stats, the measurement
 * envelope around the run, the signature verification block, and the safety assessment.
 *
 * Moved out of `MainViewModel` whole (134 lines). The body is the original, line for line: the same
 * JSON field reads and their defaults, the same `coerceAtLeast(0)`/`takeIf` clamps, the same
 * GPU/NPU evidence derivation (including treating a `gpuOffloadActive` claim as unverified unless
 * both the allocation and execution observations back it), the same `llama.cpp-gpu` downgrade, the
 * same measurement sample construction and the same `CandidateCanaryResult` field order. Only the two
 * owner interactions became [port] calls.
 *
 * Not a suspend function and not a dispatcher hop: the original was neither, and the verdict is
 * computed from values already captured on the caller's thread.
 */
internal fun TuningProbeWorkerProtocol.Result.toCandidateCanaryResult(
    port: CandidateCanaryResultPort,
    candidate: TuningExecutionProfile
): CandidateCanaryResult {
        val statsRoot = JSONObject(runtimeStatsJson)
        val gpuAllocationObserved = statsRoot.optBoolean("gpuOffloadAllocationObserved", false)
        val gpuExecutionObserved = statsRoot.optBoolean("gpuOffloadExecutionObserved", false)
        val verifiedGpuExecution = statsRoot.optBoolean("gpuOffloadActive", false) &&
            gpuAllocationObserved && gpuExecutionObserved
        val executionEvidence = statsRoot.optJSONObject("executionEvidence")
        val npuExecutionObserved = statsRoot.optBoolean("npuExecutionProven", false) ||
            statsRoot.optBoolean("provesNpuExecution", false) ||
            executionEvidence?.optBoolean("npuExecutionProven", false) == true ||
            executionEvidence?.optBoolean("qnnGraphExecution", false) == true
        val npuSupported = statsRoot.optJSONObject("backendCapabilities")
            ?.takeIf { it.has("npuSupported") }
            ?.optBoolean("npuSupported")
        val stats = RuntimeStats(
            loaded = statsRoot.optBoolean("loaded"),
            backend = statsRoot.optString("backend")
                .ifBlank { candidate.runtimeIdentity.runtime.backendId }
                .let { backend ->
                    if (!verifiedGpuExecution && backend == "llama.cpp-gpu") "llama.cpp-cpu" else backend
                },
            requestedBackend = statsRoot.optString("requestedBackend")
                .ifBlank { statsRoot.optString("selectedBackend") }
                .takeIf(String::isNotBlank),
            actualBackend = statsRoot.optString("actualBackend")
                .takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) },
            backendExecutionObserved = executionEvidence?.optBoolean("available", false) == true ||
                npuExecutionObserved,
            backendExecutionEvidence = listOf(
                executionEvidence?.optString("source"),
                statsRoot.optString("executionEvidenceSource"),
                statsRoot.optString("backendExecutionProof")
            ).firstOrNull { it?.isNotBlank() == true },
            npuExecutionObserved = npuExecutionObserved,
            npuSupported = npuSupported,
            loadMs = statsRoot.optLong("loadMs").coerceAtLeast(0L),
            promptTokens = statsRoot.optInt("promptTokens").coerceAtLeast(0),
            completionTokens = statsRoot.optInt("completionTokens").coerceAtLeast(0),
            ttftMs = statsRoot.optLong("ttftMs").coerceAtLeast(0L),
            prefillMs = statsRoot.optLong("prefillMs").coerceAtLeast(0L),
            prefillTokens = statsRoot.optInt("prefillTokens", -1)
                .takeIf { it >= 0 }
                ?: 0,
            prefillTps = port.optionalFiniteDouble(statsRoot, "prefillTps"),
            effectivePromptTps = port.optionalFiniteDouble(statsRoot, "effectivePromptTps"),
            decodeMs = statsRoot.optLong("decodeMs").coerceAtLeast(0L),
            decodeTps = port.optionalFiniteDouble(statsRoot, "decodeTps"),
            e2eTps = port.optionalFiniteDouble(statsRoot, "e2eTps"),
            nativePssKb = statsRoot.optLong("nativePssKb").coerceAtLeast(0L),
            processRssKb = statsRoot.optLong("processRssKb").coerceAtLeast(0L),
            availMemKb = statsRoot.optLong("availMemKb").coerceAtLeast(0L),
            totalMemKb = statsRoot.optLong("totalMemKb").coerceAtLeast(0L),
            modelMemoryBudgetKb = statsRoot.optLong("modelMemoryBudgetKb").coerceAtLeast(0L),
            nThreads = statsRoot.optInt("nThreads").coerceAtLeast(0),
            nThreadsBatch = statsRoot.optInt("nThreadsBatch").coerceAtLeast(0),
            nBatch = statsRoot.optInt("nBatch").coerceAtLeast(0),
            nUbatch = statsRoot.optInt("nUbatch").coerceAtLeast(0),
            nCtx = statsRoot.optInt("nCtx").coerceAtLeast(0),
            maxAllTokens = statsRoot.optInt("maxAllTokens").coerceAtLeast(0),
            maxNewTokens = statsRoot.optInt("maxNewTokens").coerceAtLeast(0),
            gpuOffloadActive = verifiedGpuExecution,
            gpuOffloadAllocationObserved = gpuAllocationObserved,
            gpuOffloadExecutionObserved = gpuExecutionObserved,
            gpuOffloadBytes = statsRoot.optLong("gpuOffloadBytes").coerceAtLeast(0L),
            gpuOffloadLayers = statsRoot.optInt("gpuOffloadLayers"),
            gpuOffloadLayersKnown = statsRoot.optBoolean("gpuOffloadLayersKnown", false),
            gpuAutoFallbackApplied = statsRoot.optBoolean("gpuAutoFallbackApplied", false),
            gpuAutoFallbackReason = statsRoot.optString("gpuAutoFallbackReason")
                .takeIf(String::isNotBlank),
            isLowMemory = statsRoot.optBoolean("isLowMemory") || lowMemoryTriggered,
            lastError = statsRoot.optString("lastError").takeIf(String::isNotBlank)
        )
        val device = port.currentDeviceProfile()
        val start = MeasurementPoint(
            thermalStatus = device.thermalStatus,
            batteryPercent = device.batteryPercent,
            isCharging = device.isCharging,
            availableMemoryBytes = startAvailableMemoryBytes,
            pssBytes = startPssBytes,
            rssBytes = startRssBytes,
            lowMemoryTriggered = lowMemoryTriggered,
            appInForeground = true
        )
        val end = start.copy(
            timeMs = start.timeMs + elapsedMs.coerceAtLeast(0L),
            availableMemoryBytes = endAvailableMemoryBytes,
            pssBytes = endPssBytes,
            rssBytes = endRssBytes,
            lowMemoryTriggered = lowMemoryTriggered || stats.isLowMemory
        )
        val decodeTps = stats.decodeTps.takeIf { it.isFinite() && it >= 0.0 }
            ?: stats.e2eTps.coerceAtLeast(0.0)
        val measurement = MeasurementEnvelope(
            start = start,
            end = end,
            samples = listOf(
                PerformanceSample(
                    ttftMs = stats.ttftMs,
                    decodeTps = decodeTps,
                    pssBytes = end.pssBytes,
                    rssBytes = end.rssBytes,
                    availableMemoryBytes = end.availableMemoryBytes
                )
            )
        )
        val expected = candidate.expectedSignatures()
        val signatures = ParameterSignatureSnapshot(
            desired = candidate.engineProfile.desiredSignature,
            resolved = candidate.engineProfile.resolvedLoadSignature,
            active = expected.activeLoaded,
            committed = candidate.engineProfile.committedExecutionSignature,
            override = RuntimeOverrideSignature.none(candidate.runtimeIdentity),
            effective = expected.effectiveExecution
        )
        val verification = AuthorizedPendingSignatureVerification(
            transactionId = transactionId,
            profileId = profileId,
            revision = candidate.revision,
            signatures = signatures,
            strictlyMatches = signatureMatched
        )
        val safetyPassed = !lowMemoryTriggered && SafetyEnvelope.forDevice(device).assess(measurement).passed
        return CandidateCanaryResult(
            passed = passed,
            output = output,
            stats = stats,
            detail = detail,
            measurement = measurement,
            safetyPassed = safetyPassed,
            signatureVerification = verification,
            testedProfileId = profileId,
            testedResolvedLoadSignature = resolvedLoadSignature,
            testedCommittedExecutionSignature = committedExecutionSignature,
            evidenceJson = evidenceJson
        )
}
