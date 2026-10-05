package com.muyuchat.mca

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.tuning.CandidateHardGate
import com.muyuchat.core.tuning.CandidateScore
import com.muyuchat.core.tuning.CandidateScorer
import com.muyuchat.core.tuning.ExecutionProfileKind
import com.muyuchat.core.tuning.HotExecutionParams
import com.muyuchat.core.tuning.LoadBoundExecutionParams
import com.muyuchat.core.tuning.MeasurementEnvelope
import com.muyuchat.core.tuning.MeasurementPoint
import com.muyuchat.core.tuning.MinimumTextCanaryPolicy
import com.muyuchat.core.tuning.PerformanceSample
import com.muyuchat.core.tuning.ProfileVerificationLevel
import com.muyuchat.core.tuning.SafetyEnvelope
import com.muyuchat.core.tuning.TuningExecutionProfile
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.mca.CandidateCanaryResultPort
import com.muyuchat.mca.toCandidateCanaryResult
import java.util.UUID
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout

/**
 * The owner seam for the tuning canary: one candidate is run once through the real engine and turned
 * into a [CandidateCanaryResult].
 *
 * Five members. The canary's *inputs* (the authorization, the disposition, the lifecycle lease) are
 * parameters, and its measurement helpers are pure; only the device profile, the canary result port and
 * the engine are collaborations.
 *
 * **Known limitation, with an exit condition.** [engine] hands out the concrete `McaInferenceService`
 * rather than a narrow runtime interface. That is deliberate for this step: the two operations used are
 * `streamChat` and `authorizedPendingSignatureVerification`, and inventing a wrapper for them now would
 * add a second surface to keep in step without removing any coupling. The exit condition is explicit:
 * once `McaInferenceService` exposes an interface, this member becomes that interface and the canary
 * keeps compiling unchanged.
 */
internal interface TuningCanaryPort {

    fun uiState(): MainUiState

    fun updateUiState(transform: (MainUiState) -> MainUiState)

    fun currentDeviceProfile(): DeviceProfile

    val candidateCanaryResultPort: CandidateCanaryResultPort

    val engine: McaInferenceService
}

internal fun failedIsolatedCanaryResult(
    port: TuningCanaryPort,
        candidate: TuningExecutionProfile,
        start: MeasurementPoint,
        error: Throwable
    ): CandidateCanaryResult {
        val message = error.message?.take(1_024) ?: error::class.java.simpleName
        val normalized = message.lowercase()
        val remoteCode = (error as? TuningProbeWorkerRemoteException)?.code.orEmpty().lowercase()
        val timedOut = error is TimeoutCancellationException ||
            "timeout" in remoteCode || "timed out" in normalized || "watchdog" in normalized ||
            "hang" in normalized
        val nativeFatal = "native_fatal" in remoteCode || "fatal signal" in normalized ||
            "sigsegv" in normalized || "sigabrt" in normalized
        val workerDied = error is TuningProbeWorkerException &&
            error !is TuningProbeWorkerRemoteException &&
            ("crash" in normalized || "exited" in normalized || "disconnected" in normalized ||
                "binding died" in normalized || "watchdog" in normalized)
        val measuredEnd = currentMeasurementPoint(port = port)
        val measurement = MeasurementEnvelope(
            start = start,
            end = measuredEnd,
            samples = listOf(
                PerformanceSample(
                    ttftMs = 0L,
                    decodeTps = 0.0,
                    pssBytes = measuredEnd.pssBytes,
                    rssBytes = measuredEnd.rssBytes,
                    availableMemoryBytes = measuredEnd.availableMemoryBytes
                )
            )
        )
        return CandidateCanaryResult(
            passed = false,
            output = "",
            stats = RuntimeStats(
                loaded = false,
                backend = candidate.runtimeIdentity.runtime.backendId,
                isLowMemory = start.lowMemoryTriggered || measuredEnd.lowMemoryTriggered,
                lastError = message
            ),
            detail = "isolated candidate rejected: $message",
            measurement = measurement,
            safetyPassed = SafetyEnvelope.forDevice(port.currentDeviceProfile()).assess(measurement).passed,
            signatureVerification = null,
            crashCount = if (workerDied) 1 else 0,
            anrCount = if (timedOut) 1 else 0,
            nativeFatalSignalCount = if (nativeFatal) 1 else 0,
            testedProfileId = candidate.profileId,
            testedResolvedLoadSignature = candidate.engineProfile.resolvedLoadSignature.digest,
            testedCommittedExecutionSignature = candidate.engineProfile.committedExecutionSignature.digest,
            evidenceJson = JSONObject()
                .put("environment", "isolated_process")
                .put("outcome", "worker_failure")
                .put("errorClass", error::class.java.name)
                .put("remoteCode", remoteCode.takeIf(String::isNotBlank))
                .put("message", message)
                .put("crashCount", if (workerDied) 1 else 0)
                .put("anrCount", if (timedOut) 1 else 0)
                .put("nativeFatalSignalCount", if (nativeFatal) 1 else 0)
                .toString()
        )
    }

internal fun TuningProbeWorkerProtocol.Result.canaryResultFrom(
    port: TuningCanaryPort,
        candidate: TuningExecutionProfile
    ): CandidateCanaryResult =
        toCandidateCanaryResult(port = port.candidateCanaryResultPort, candidate = candidate)

    /**
     * Reads a tolerant double out of a JSON object: `0.0` when the field is absent, null, non-numeric
     * or non-finite. Private to the ViewModel, which is why the conversion reaches it through
     * [CandidateCanaryResultPort] rather than calling it directly.
     */

// R3/C2: port 为死参（函数体未使用），随端口具名化删除。
internal fun JSONObject.jsonOptionalFiniteDouble(field: String): Double {
        if (!has(field) || isNull(field)) return 0.0
        return optDouble(field, 0.0).takeIf(Double::isFinite) ?: 0.0
    }

internal suspend fun runCanary(
    port: TuningCanaryPort,
        candidate: TuningExecutionProfile,
        canaryParams: com.muyuchat.core.tuning.CanaryEvaluationParams,
        authorization: com.muyuchat.core.engine.LoadAuthorization,
        disposition: com.muyuchat.core.engine.PendingProfileDisposition =
            com.muyuchat.core.engine.PendingProfileDisposition.ROLLBACK_AFTER_REQUEST,
        lifecycleLease: com.muyuchat.core.engine.EngineLifecycleLease? = null
    ): CandidateCanaryResult {
        val startPoint = currentMeasurementPoint(port = port)
        val output = StringBuilder()
        var stats = RuntimeStats()
        var errorMessage: String? = null
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(
                    Role.USER,
                    MinimumTextCanaryPolicy.prompt
                )
            ),
            params = canaryParams.toGenerationParams(candidate)
        )
        withTimeout(120_000L) {
            port.engine.streamChat(
                request,
                com.muyuchat.core.engine.LocalChatExecutionContext(
                    requestId = "tuning-${UUID.randomUUID()}",
                    loadAuthorization = authorization,
                    pendingProfileDisposition = disposition,
                    lifecycleLease = lifecycleLease
                )
            ).collect { event ->
                when (event) {
                    is GenerateEvent.Phase -> stats = event.stats
                    is GenerateEvent.Persist -> Unit
                    is GenerateEvent.Chunk -> {
                        output.append(event.text)
                        stats = event.stats
                    }
                    is GenerateEvent.Done -> stats = event.stats
                    is GenerateEvent.Error -> {
                        errorMessage = event.message
                        stats = event.stats
                    }
                }
            }
        }
        val text = output.toString()
        val passed = errorMessage == null && MinimumTextCanaryPolicy.matches(text)
        val signatureVerification = port.engine.authorizedPendingSignatureVerification(authorization)
        val measuredEnd = currentMeasurementPoint(port = port)
        val endPoint = measuredEnd.copy(
            availableMemoryBytes = stats.availMemKb
                .takeIf { it > 0L }
                ?.times(1024L)
                ?: measuredEnd.availableMemoryBytes,
            pssBytes = stats.nativePssKb.coerceAtLeast(0L) * 1024L,
            rssBytes = stats.processRssKb.coerceAtLeast(0L) * 1024L,
            lowMemoryTriggered = measuredEnd.lowMemoryTriggered || stats.isLowMemory
        )
        val decodeTps = stats.decodeTps
            .takeIf { it.isFinite() && it >= 0.0 }
            ?: stats.e2eTps.coerceAtLeast(0.0)
        val measurement = MeasurementEnvelope(
            start = startPoint,
            end = endPoint,
            samples = listOf(
                PerformanceSample(
                    ttftMs = stats.ttftMs.coerceAtLeast(0L),
                    decodeTps = decodeTps,
                    pssBytes = endPoint.pssBytes,
                    rssBytes = endPoint.rssBytes,
                    availableMemoryBytes = endPoint.availableMemoryBytes
                )
            )
        )
        val safetyPassed = SafetyEnvelope.forDevice(port.currentDeviceProfile()).assess(measurement).passed
        return CandidateCanaryResult(
            passed = passed,
            output = text,
            stats = stats,
            detail = errorMessage ?: if (passed) "candidate correctness passed" else "candidate output failed correctness",
            measurement = measurement,
            safetyPassed = safetyPassed,
            signatureVerification = signatureVerification,
            testedProfileId = candidate.profileId,
            testedResolvedLoadSignature = candidate.engineProfile.resolvedLoadSignature.digest,
            testedCommittedExecutionSignature = candidate.engineProfile.committedExecutionSignature.digest,
            evidenceJson = JSONObject()
                .put("environment", "caller_process")
                .put("profileId", candidate.profileId)
                .put("resolvedLoadSignature", candidate.engineProfile.resolvedLoadSignature.digest)
                .put("committedExecutionSignature", candidate.engineProfile.committedExecutionSignature.digest)
                .toString()
        )
    }

internal fun canaryHardGate(
    port: TuningCanaryPort,result: CandidateCanaryResult): CandidateHardGate {
        return CandidateHardGate(
            correctnessPassed = result.passed && result.stats.lastError == null,
            crashCount = result.crashCount,
            anrCount = result.anrCount,
            nativeFatalSignalCount = result.nativeFatalSignalCount,
            lowMemoryTriggered = result.stats.isLowMemory ||
                result.measurement.start.lowMemoryTriggered ||
                result.measurement.end.lowMemoryTriggered,
            outputVisible = result.output.isNotBlank(),
            templateValid = result.passed,
            safetyPassed = result.safetyPassed,
            signaturesMatch = result.signatureVerification?.strictlyMatches == true
        )
    }

internal fun canaryScore(
    port: TuningCanaryPort,result: CandidateCanaryResult): CandidateScore =
        CandidateScorer.score(canaryHardGate(port = port, result), result.measurement)

internal fun canaryMeasurement(
    port: TuningCanaryPort,
        profileId: String,
        jobId: String?,
        phase: String,
        result: CandidateCanaryResult
    ): CandidateMeasurementEntity {
        val gate = canaryHardGate(port = port, result)
        val score = CandidateScorer.score(gate, result.measurement)
        val failureCode = tuningCandidateFailureCode(gate, score, result.stats.lastError)
        val metrics = JSONObject()
            .put("phase", phase)
            .put("eligible", score.eligible)
            .put("score", score.value)
            .put("correctnessPassed", gate.correctnessPassed)
            .put("templateValid", gate.templateValid)
            .put("outputVisible", gate.outputVisible)
            .put("safetyPassed", gate.safetyPassed)
            .put("signatureStrictlyMatched", gate.signaturesMatch)
            .put("lowMemoryTriggered", gate.lowMemoryTriggered)
            .put("crashCount", gate.crashCount)
            .put("anrCount", gate.anrCount)
            .put("nativeFatalSignalCount", gate.nativeFatalSignalCount)
            .put("ttftMs", result.stats.ttftMs.coerceAtLeast(0L))
            .put("decodeMs", result.stats.decodeMs.coerceAtLeast(0L))
            .put("decodeTps", result.stats.decodeTps.takeIf { it.isFinite() })
            .put("completionTokens", result.stats.completionTokens.coerceAtLeast(0))
            .put("nativePssKb", result.stats.nativePssKb.coerceAtLeast(0L))
            .put("processRssKb", result.stats.processRssKb.coerceAtLeast(0L))
            .put("minimumAvailableMemoryBytes", result.measurement.minimumAvailableMemoryBytes.coerceAtLeast(0L))
            .put("testedProfileId", result.testedProfileId)
            .put("testedResolvedLoadSignature", result.testedResolvedLoadSignature)
            .put("testedCommittedExecutionSignature", result.testedCommittedExecutionSignature)
            .put(
                "isolatedEvidence",
                runCatching { JSONObject(result.evidenceJson) }.getOrElse { JSONObject() }
            )
            .put("signatures", JSONObject().apply {
                result.signatureVerification?.signatures?.let { signatures ->
                    put("desired", signatures.desired.digest)
                    put("resolvedLoad", signatures.resolved.digest)
                    put("activeLoaded", signatures.active?.digest)
                    put("committedExecution", signatures.committed.digest)
                    put("runtimeOverride", signatures.override.digest)
                    put("effectiveExecution", signatures.effective?.digest)
                }
            })
        return CandidateMeasurementEntity(
            measurementId = "measurement-${UUID.randomUUID()}",
            profileId = profileId,
            jobId = jobId,
            correctnessPassed = gate.correctnessPassed,
            safetyPassed = gate.safetyPassed,
            effectiveSignatureMatched = gate.signaturesMatch,
            accepted = score.eligible,
            metricsJson = metrics.toString(),
            failureCode = failureCode,
            createdAt = System.currentTimeMillis()
        )
    }

internal fun ModelExecutionProfile.asTuningProfile(
    port: TuningCanaryPort,
        kind: ExecutionProfileKind,
        verificationLevel: ProfileVerificationLevel,
        reason: String
    ): TuningExecutionProfile {
        fun intValue(field: String): Int? =
            (resolvedLoadBoundValues.value(field) as? Number)?.toInt()
                ?: (hotExecutionValues.value(field) as? Number)?.toInt()
        fun stringValue(field: String): String? =
            resolvedLoadBoundValues.value(field)?.toString()
        fun boolValue(field: String): Boolean? =
            resolvedLoadBoundValues.value(field) as? Boolean
        return TuningExecutionProfile(
            engineProfile = this,
            kind = kind,
            loadBound = LoadBoundExecutionParams(
                nCtx = intValue("n_ctx") ?: port.uiState().params.nCtx,
                nBatch = intValue("n_batch"),
                nUbatch = intValue("n_ubatch"),
                cacheTypeK = stringValue("cache_type_k"),
                cacheTypeV = stringValue("cache_type_v"),
                flashAttention = stringValue("flash_attn"),
                gpuLayers = intValue("n_gpu_layers"),
                mainGpu = intValue("main_gpu"),
                cpuMoeLayers = intValue("n_cpu_moe"),
                speculativeType = stringValue("spec_type"),
                speculativeDraftMax = intValue("spec_draft_n_max"),
                nParallel = intValue("n_parallel") ?: 1,
                mmap = boolValue("mmap") ?: true,
                mlock = boolValue("mlock") ?: false,
                backend = stringValue("backend") ?: runtimeIdentity.runtime.backendId
            ),
            hotExecution = HotExecutionParams(
                nThreads = intValue("n_threads") ?: port.uiState().params.nThreads,
                nThreadsBatch = intValue("n_threads_batch")
            ),
            verificationLevel = verificationLevel,
            reason = reason
        )
    }

internal fun currentMeasurementPoint(
    port: TuningCanaryPort,device: DeviceProfile = port.currentDeviceProfile()): MeasurementPoint {
        val stats = port.uiState().stats
        return MeasurementPoint(
            thermalStatus = device.thermalStatus,
            batteryPercent = device.batteryPercent,
            isCharging = device.isCharging,
            availableMemoryBytes = device.availableRamBytes,
            pssBytes = stats.nativePssKb.coerceAtLeast(0L) * 1024L,
            rssBytes = stats.processRssKb.coerceAtLeast(0L) * 1024L,
            lowMemoryTriggered = device.isLowMemory || stats.isLowMemory,
            appInForeground = port.uiState().engineLifecycle != AgentEngineLifecycle.STOPPING
        )
    }
