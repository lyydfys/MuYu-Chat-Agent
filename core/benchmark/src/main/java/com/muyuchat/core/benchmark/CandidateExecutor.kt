package com.muyuchat.core.benchmark

import com.muyuchat.core.tuning.AdaptiveTuningRecommendation
import com.muyuchat.core.tuning.CanaryEvaluationParams
import com.muyuchat.core.tuning.CanaryRunResult
import com.muyuchat.core.tuning.CandidateHardGate
import com.muyuchat.core.tuning.CandidateScore
import com.muyuchat.core.tuning.CandidateScorer
import com.muyuchat.core.tuning.CorrectnessAssessment
import com.muyuchat.core.tuning.CorrectnessSuite
import com.muyuchat.core.tuning.MeasurementEnvelope
import com.muyuchat.core.tuning.MeasurementPoint
import com.muyuchat.core.tuning.ProfileEligibility
import com.muyuchat.core.tuning.SafetyAssessment
import com.muyuchat.core.tuning.SafetyEnvelope
import com.muyuchat.core.tuning.TuningExecutionProfile
import com.muyuchat.core.engine.CanonicalParameterSet
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ParameterSignatureSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Exclusive engine lifecycle lease. Implementations must be idempotent. */
interface CandidateLifecycleLease {
    suspend fun release()
}

data class CandidateRuntimeSnapshot(
    val committedProfile: TuningExecutionProfile,
    val signatures: ParameterSignatureSnapshot
)

data class CanaryExecutionResult(
    val run: CanaryRunResult
)

/**
 * Runtime boundary used by the formal tuning path. `reload` must create a new
 * native session using the whole candidate profile. `applyHot` may only apply
 * fields registered as HOT_EXECUTION by the concrete runtime adapter.
 */
interface CandidateRuntimeAdapter {
    suspend fun acquireExclusiveLifecycleLease(): CandidateLifecycleLease

    suspend fun awaitGenerationIdle()

    suspend fun snapshotCommitted(): CandidateRuntimeSnapshot

    /** Must load natively and publish through ParameterCoordinator.publishLoaded. */
    suspend fun reload(profile: ModelExecutionProfile): ParameterSignatureSnapshot

    /** Must apply natively and publish through ParameterCoordinator.setRuntimeOverride. */
    suspend fun applyHotOverride(params: CanonicalParameterSet): ParameterSignatureSnapshot

    /** Restores native committed values and calls ParameterCoordinator.clearRuntimeOverride. */
    suspend fun restoreCommittedHot(params: CanonicalParameterSet): ParameterSignatureSnapshot

    suspend fun readSignatures(): ParameterSignatureSnapshot

    suspend fun runCanary(
        params: CanaryEvaluationParams,
        suite: CorrectnessSuite
    ): CanaryExecutionResult

    suspend fun readMeasurementPoint(): MeasurementPoint
}

enum class CandidateEvaluationStatus {
    PASSED,
    BLOCKED,
    SAFETY_REJECTED,
    SIGNATURE_MISMATCH,
    CORRECTNESS_REJECTED,
    EXECUTION_FAILED,
    TIMED_OUT,
    RESTORE_FAILED
}

data class CandidateEvaluation(
    val candidateProfileId: String,
    val status: CandidateEvaluationStatus,
    val hardGate: CandidateHardGate? = null,
    val score: CandidateScore? = null,
    val measurement: MeasurementEnvelope? = null,
    val correctness: CorrectnessAssessment? = null,
    val safety: SafetyAssessment? = null,
    val observedSignatures: ParameterSignatureSnapshot? = null,
    val loadReloaded: Boolean = false,
    val hotApplied: Boolean = false,
    val canaryRan: Boolean = false,
    val restored: Boolean = false,
    val error: String? = null
) {
    val eligibleForCommit: Boolean
        get() = status == CandidateEvaluationStatus.PASSED &&
            hardGate?.passed == true && score?.eligible == true && restored
}

class CandidateExecutor(
    private val adapter: CandidateRuntimeAdapter
) {
    suspend fun execute(
        recommendation: AdaptiveTuningRecommendation,
        suite: CorrectnessSuite = CorrectnessSuite.minimumText(),
        safetyEnvelope: SafetyEnvelope
    ): CandidateEvaluation = execute(
        candidate = recommendation.executionProfile,
        canaryParams = recommendation.canaryParams,
        suite = suite,
        safetyEnvelope = safetyEnvelope
    )

    suspend fun execute(
        candidate: TuningExecutionProfile,
        canaryParams: CanaryEvaluationParams,
        suite: CorrectnessSuite,
        safetyEnvelope: SafetyEnvelope
    ): CandidateEvaluation {
        var lease: CandidateLifecycleLease? = null
        var committed: CandidateRuntimeSnapshot? = null
        var loadReloaded = false
        var hotApplied = false
        var evaluation: CandidateEvaluation? = null
        var cancellation: CancellationException? = null
        var restorationFailure: Throwable? = null

        try {
            lease = adapter.acquireExclusiveLifecycleLease()
            adapter.awaitGenerationIdle()
            val committedSnapshot = adapter.snapshotCommitted()
            committed = committedSnapshot

            evaluation = when {
                candidate.eligibility == ProfileEligibility.BLOCKED_WITH_ACTION -> blocked(
                    candidate,
                    candidate.blockedAction ?: "候选需要用户处理后才能运行"
                )
                candidate.identityHash != committedSnapshot.committedProfile.identityHash -> blocked(
                    candidate,
                    "候选与当前已加载模型/runtime 身份不一致"
                )
                canaryParams.suiteId != suite.id -> blocked(
                    candidate,
                    "Canary suite mismatch: params=${canaryParams.suiteId}, suite=${suite.id}"
                )
                else -> {
                    val start = adapter.readMeasurementPoint()
                    val preflight = safetyEnvelope.assess(start)
                    if (!preflight.passed) {
                        CandidateEvaluation(
                            candidateProfileId = candidate.profileId,
                            status = CandidateEvaluationStatus.SAFETY_REJECTED,
                            safety = preflight,
                            measurement = MeasurementEnvelope(start, start),
                            error = preflight.violations.joinToString("; ") { it.message }
                        )
                    } else {
                        try {
                            withTimeout(safetyEnvelope.perCandidateTimeoutMs) {
                                val candidateExpected = candidate.expectedSignatures()
                                val loadChanged = candidate.engineProfile.resolvedLoadSignature !=
                                    committedSnapshot.committedProfile.engineProfile.resolvedLoadSignature
                                val effectiveChanged = candidate.engineProfile.committedExecutionSignature !=
                                    committedSnapshot.committedProfile.engineProfile.committedExecutionSignature

                                val observed = if (loadChanged) {
                                    // Contract requires a real unload/load of the candidate profile.
                                    loadReloaded = true
                                    adapter.reload(candidate.engineProfile)
                                } else if (effectiveChanged) {
                                    hotApplied = true
                                    adapter.applyHotOverride(candidate.engineProfile.hotExecutionValues)
                                } else {
                                    adapter.readSignatures()
                                }

                                if (!observed.matches(candidateExpected)) {
                                    signatureMismatch(
                                        candidate = candidate,
                                        observed = observed,
                                        expected = candidateExpected,
                                        start = start,
                                        loadReloaded = loadReloaded,
                                        hotApplied = hotApplied
                                    )
                                } else {
                                    val canary = adapter.runCanary(canaryParams, suite)
                                    val endRaw = adapter.readMeasurementPoint()
                                    val end = if (canary.run.lowMemoryTriggered) {
                                        endRaw.copy(lowMemoryTriggered = true)
                                    } else {
                                        endRaw
                                    }
                                    val measurement = MeasurementEnvelope(
                                        start = start,
                                        end = end,
                                        samples = canary.run.samples
                                    )
                                    evaluateCanary(
                                        candidate = candidate,
                                        suite = suite,
                                        canary = canary.run,
                                        measurement = measurement,
                                        safetyEnvelope = safetyEnvelope,
                                        observed = observed,
                                        loadReloaded = loadReloaded,
                                        hotApplied = hotApplied
                                    )
                                }
                            }
                        } catch (timeout: TimeoutCancellationException) {
                            CandidateEvaluation(
                                candidateProfileId = candidate.profileId,
                                status = CandidateEvaluationStatus.TIMED_OUT,
                                loadReloaded = loadReloaded,
                                hotApplied = hotApplied,
                                error = "候选执行超过 ${safetyEnvelope.perCandidateTimeoutMs}ms"
                            )
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            cancellation = cancelled
        } catch (error: Throwable) {
            evaluation = CandidateEvaluation(
                candidateProfileId = candidate.profileId,
                status = CandidateEvaluationStatus.EXECUTION_FAILED,
                loadReloaded = loadReloaded,
                hotApplied = hotApplied,
                error = error.message ?: error::class.java.simpleName
            )
        } finally {
            withContext(NonCancellable) {
                try {
                    val snapshot = committed
                    if (snapshot != null) {
                        when {
                            loadReloaded -> adapter.reload(snapshot.committedProfile.engineProfile)
                            hotApplied -> adapter.restoreCommittedHot(
                                snapshot.committedProfile.engineProfile.hotExecutionValues
                            )
                        }
                        if (loadReloaded || hotApplied) {
                            val restoredSignatures = adapter.readSignatures()
                            if (!restoredSignatures.matches(snapshot.committedProfile.expectedSignatures())) {
                                error("Committed profile signatures were not restored")
                            }
                        }
                    }
                } catch (error: Throwable) {
                    restorationFailure = error
                }
                try {
                    lease?.release()
                } catch (error: Throwable) {
                    if (restorationFailure == null) restorationFailure = error
                }
            }
        }

        if (cancellation != null) {
            restorationFailure?.let(cancellation::addSuppressed)
            throw cancellation
        }

        val base = evaluation ?: CandidateEvaluation(
            candidateProfileId = candidate.profileId,
            status = CandidateEvaluationStatus.EXECUTION_FAILED,
            error = "候选执行未产生结果"
        )
        if (restorationFailure != null) {
            val failure = restorationFailure
            return base.copy(
                status = CandidateEvaluationStatus.RESTORE_FAILED,
                score = base.score?.copy(eligible = false, value = null, reason = "恢复 committed profile 失败"),
                restored = false,
                error = failure.message ?: failure::class.java.simpleName
            )
        }
        return base.copy(restored = committed != null)
    }

    private fun evaluateCanary(
        candidate: TuningExecutionProfile,
        suite: CorrectnessSuite,
        canary: CanaryRunResult,
        measurement: MeasurementEnvelope,
        safetyEnvelope: SafetyEnvelope,
        observed: ParameterSignatureSnapshot,
        loadReloaded: Boolean,
        hotApplied: Boolean
    ): CandidateEvaluation {
        val correctness = suite.evaluate(canary)
        val safety = safetyEnvelope.assess(measurement)
        val hardGate = CandidateHardGate(
            correctnessPassed = correctness.passed,
            crashCount = canary.crashCount,
            anrCount = canary.anrCount,
            nativeFatalSignalCount = canary.nativeFatalSignalCount,
            lowMemoryTriggered = canary.lowMemoryTriggered ||
                measurement.start.lowMemoryTriggered || measurement.end.lowMemoryTriggered,
            outputVisible = canary.outputVisible,
            templateValid = canary.templateValid,
            safetyPassed = safety.passed,
            signaturesMatch = true
        )
        val score = CandidateScorer.score(hardGate, measurement)
        val status = when {
            !correctness.passed -> CandidateEvaluationStatus.CORRECTNESS_REJECTED
            !safety.passed || canary.crashCount > 0 || canary.anrCount > 0 ||
                canary.nativeFatalSignalCount > 0 || canary.lowMemoryTriggered ->
                CandidateEvaluationStatus.SAFETY_REJECTED
            hardGate.passed -> CandidateEvaluationStatus.PASSED
            else -> CandidateEvaluationStatus.CORRECTNESS_REJECTED
        }
        return CandidateEvaluation(
            candidateProfileId = candidate.profileId,
            status = status,
            hardGate = hardGate,
            score = score,
            measurement = measurement,
            correctness = correctness,
            safety = safety,
            observedSignatures = observed,
            loadReloaded = loadReloaded,
            hotApplied = hotApplied,
            canaryRan = true,
            error = when (status) {
                CandidateEvaluationStatus.PASSED -> null
                CandidateEvaluationStatus.CORRECTNESS_REJECTED -> "候选未通过 CorrectnessSuite"
                else -> "候选未通过 SafetyEnvelope 或稳定性硬门槛"
            }
        )
    }

    private fun signatureMismatch(
        candidate: TuningExecutionProfile,
        observed: ParameterSignatureSnapshot,
        expected: com.muyuchat.core.tuning.CandidateExpectedSignatures,
        start: MeasurementPoint,
        loadReloaded: Boolean,
        hotApplied: Boolean
    ): CandidateEvaluation {
        val hardGate = CandidateHardGate(
            correctnessPassed = false,
            crashCount = 0,
            anrCount = 0,
            nativeFatalSignalCount = 0,
            lowMemoryTriggered = false,
            outputVisible = false,
            templateValid = false,
            safetyPassed = true,
            signaturesMatch = false
        )
        val measurement = MeasurementEnvelope(start, start)
        return CandidateEvaluation(
            candidateProfileId = candidate.profileId,
            status = CandidateEvaluationStatus.SIGNATURE_MISMATCH,
            hardGate = hardGate,
            score = CandidateScorer.score(hardGate, measurement),
            measurement = measurement,
            observedSignatures = observed,
            loadReloaded = loadReloaded,
            hotApplied = hotApplied,
            canaryRan = false,
            error = "effective signature mismatch: expected=${expected.effectiveExecution.digest}, " +
                "observed=${observed.effective?.digest ?: "missing"}"
        )
    }

    private fun blocked(candidate: TuningExecutionProfile, reason: String): CandidateEvaluation =
        CandidateEvaluation(
            candidateProfileId = candidate.profileId,
            status = CandidateEvaluationStatus.BLOCKED,
            error = reason
        )
}

private fun ParameterSignatureSnapshot.matches(
    expected: com.muyuchat.core.tuning.CandidateExpectedSignatures
): Boolean = active == expected.activeLoaded && effective == expected.effectiveExecution
