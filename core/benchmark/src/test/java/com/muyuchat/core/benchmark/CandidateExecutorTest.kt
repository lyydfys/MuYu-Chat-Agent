package com.muyuchat.core.benchmark

import com.muyuchat.core.deviceprofile.ThermalStatus
import com.muyuchat.core.tuning.CanaryCaseResult
import com.muyuchat.core.tuning.CanaryEvaluationParams
import com.muyuchat.core.tuning.CanaryRunResult
import com.muyuchat.core.tuning.CorrectnessSuite
import com.muyuchat.core.tuning.ExecutionProfileKind
import com.muyuchat.core.tuning.MeasurementPoint
import com.muyuchat.core.tuning.ModelTuningCapabilities
import com.muyuchat.core.tuning.PerformanceSample
import com.muyuchat.core.tuning.SafetyEnvelope
import com.muyuchat.core.tuning.TuningExecutionProfile
import com.muyuchat.core.tuning.TuningPlan
import com.muyuchat.core.tuning.TuningRuntime
import com.muyuchat.core.tuning.toAdaptive
import com.muyuchat.core.engine.CanonicalParameterSet
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.ParameterSignatureSnapshot
import com.muyuchat.core.engine.RuntimeOverrideSignature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CandidateExecutorTest {
    private val suite = CorrectnessSuite.minimumText()
    private val canaryParams = CanaryEvaluationParams(suiteId = suite.id)

    @Test
    fun loadBoundChangePerformsRealReloadAndRestoresCommittedProfile() = runBlocking {
        val committed = profile(id = "committed", nCtx = 4096, threads = 6)
        val candidate = profile(id = "candidate", nCtx = 8192, threads = 8)
        val adapter = FakeAdapter(committed)

        val result = CandidateExecutor(adapter).execute(candidate, canaryParams, suite, safety())

        assertEquals(CandidateEvaluationStatus.PASSED, result.status)
        assertTrue(result.loadReloaded)
        assertFalse(result.hotApplied)
        assertTrue(result.canaryRan)
        assertTrue(result.restored)
        assertTrue(result.eligibleForCommit)
        assertEquals(
            listOf("lease", "idle", "reload:candidate", "canary", "reload:committed", "release"),
            adapter.events
        )
        assertEquals(committed.expectedSignatures(), adapter.current.expectedSignatures())
    }

    @Test
    fun hotOnlyChangeAppliesCandidateThenRestoresCommittedHotValues() = runBlocking {
        val committed = profile(id = "committed", nCtx = 4096, threads = 6)
        val candidate = profile(id = "candidate", nCtx = 4096, threads = 4)
        val adapter = FakeAdapter(committed)

        val result = CandidateExecutor(adapter).execute(candidate, canaryParams, suite, safety())

        assertEquals(CandidateEvaluationStatus.PASSED, result.status)
        assertFalse(result.loadReloaded)
        assertTrue(result.hotApplied)
        assertEquals(
            listOf("lease", "idle", "hot:4", "canary", "hot:6", "release"),
            adapter.events
        )
        assertEquals(6, adapter.current.hotExecution.nThreads)
        assertEquals(committed.expectedSignatures(), adapter.current.expectedSignatures())
    }

    @Test
    fun effectiveSignatureMismatchNeverRunsCanaryOrScoresCandidate() = runBlocking {
        val committed = profile(id = "committed", nCtx = 4096, threads = 6)
        val candidate = profile(id = "candidate", nCtx = 4096, threads = 4)
        val adapter = FakeAdapter(committed).apply { mismatchNextSignatureRead = true }

        val result = CandidateExecutor(adapter).execute(candidate, canaryParams, suite, safety())

        assertEquals(CandidateEvaluationStatus.SIGNATURE_MISMATCH, result.status)
        assertFalse(result.canaryRan)
        assertFalse(result.score!!.eligible)
        assertFalse(result.eligibleForCommit)
        assertFalse(adapter.events.contains("canary"))
        assertEquals(6, adapter.current.hotExecution.nThreads)
    }

    @Test
    fun fastButIncorrectCandidateIsRejectedByHardGate() = runBlocking {
        val committed = profile(id = "committed", nCtx = 4096, threads = 6)
        val candidate = profile(id = "candidate", nCtx = 4096, threads = 8)
        val incorrect = passingCanary(samples = listOf(PerformanceSample(20, 250.0))).copy(
            cases = passingCases().map { result ->
                if (result.caseId == "chinese-instruction") result.copy(passed = false) else result
            }
        )
        val adapter = FakeAdapter(committed, canary = incorrect)

        val result = CandidateExecutor(adapter).execute(candidate, canaryParams, suite, safety())

        assertEquals(CandidateEvaluationStatus.CORRECTNESS_REJECTED, result.status)
        assertFalse(result.hardGate!!.passed)
        assertFalse(result.score!!.eligible)
        assertNull(result.score.value)
        assertFalse(result.eligibleForCommit)
    }

    @Test
    fun cancellationStillRestoresCommittedProfileBeforePropagating() = runBlocking {
        val committed = profile(id = "committed", nCtx = 4096, threads = 6)
        val candidate = profile(id = "candidate", nCtx = 8192, threads = 8)
        val adapter = FakeAdapter(committed).apply { cancelCanary = true }

        try {
            CandidateExecutor(adapter).execute(candidate, canaryParams, suite, safety())
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // Expected: cancellation is propagated after non-cancellable restore.
        }

        assertEquals(committed.expectedSignatures(), adapter.current.expectedSignatures())
        assertTrue(adapter.events.contains("reload:committed"))
        assertEquals("release", adapter.events.last())
    }

    private fun profile(id: String, nCtx: Int, threads: Int): TuningExecutionProfile {
        val recommendation = TuningPlan(
            nCtx = nCtx,
            nPredict = 96,
            nThreads = threads,
            temperature = 0.0f,
            topK = 1,
            topP = 1.0f,
            minP = 0.0f,
            repeatPenalty = 1.0f,
            presencePenalty = 0.0f,
            backend = "cpu"
        ).toAdaptive(
            runtimeIdentity = identity,
            capabilities = ModelTuningCapabilities(runtime = TuningRuntime.LLAMA_CPP),
            profileKind = ExecutionProfileKind.BALANCED,
            device = testDevice
        ).executionProfile
        return recommendation.copy(
            engineProfile = recommendation.engineProfile.copy(profileId = id)
        )
    }

    private fun safety(): SafetyEnvelope = SafetyEnvelope(
        minimumAvailableMemoryBytes = 512L * MB,
        maximumPssBytes = 8L * GB,
        maximumRssBytes = 9L * GB,
        perCandidateTimeoutMs = 10_000,
        totalTuningTimeoutMs = 60_000
    )

    private fun point(): MeasurementPoint = MeasurementPoint(
        thermalStatus = ThermalStatus.None,
        batteryPercent = 80,
        isCharging = true,
        availableMemoryBytes = 4L * GB,
        pssBytes = 1L * GB,
        rssBytes = 1L * GB
    )

    private fun passingCases(): List<CanaryCaseResult> = suite.cases.map {
        CanaryCaseResult(caseId = it.id, passed = true)
    }

    private fun passingCanary(
        samples: List<PerformanceSample> = listOf(
            PerformanceSample(ttftMs = 300, decodeTps = 6.0),
            PerformanceSample(ttftMs = 320, decodeTps = 6.2)
        )
    ): CanaryRunResult = CanaryRunResult(
        cases = passingCases(),
        outputVisible = true,
        templateValid = true,
        samples = samples
    )

    private inner class FakeAdapter(
        committed: TuningExecutionProfile,
        private val canary: CanaryRunResult = passingCanary()
    ) : CandidateRuntimeAdapter {
        var current: TuningExecutionProfile = committed
        val committedProfile: TuningExecutionProfile = committed
        val events = mutableListOf<String>()
        var mismatchNextSignatureRead: Boolean = false
        var cancelCanary: Boolean = false

        override suspend fun acquireExclusiveLifecycleLease(): CandidateLifecycleLease {
            events += "lease"
            return object : CandidateLifecycleLease {
                override suspend fun release() {
                    events += "release"
                }
            }
        }

        override suspend fun awaitGenerationIdle() {
            events += "idle"
        }

        override suspend fun snapshotCommitted(): CandidateRuntimeSnapshot = CandidateRuntimeSnapshot(
            committedProfile = committedProfile,
            signatures = snapshot(committedProfile)
        )

        override suspend fun reload(profile: ModelExecutionProfile): ParameterSignatureSnapshot {
            events += "reload:${profile.profileId}"
            current = current.copy(
                engineProfile = profile,
                loadBound = current.loadBound.copy(
                    nCtx = (profile.resolvedLoadBoundValues.value("n_ctx") as? Number)?.toInt()
                        ?: current.loadBound.nCtx
                ),
                hotExecution = current.hotExecution.copy(
                    nThreads = (profile.hotExecutionValues.value("n_threads") as? Number)?.toInt()
                        ?: current.hotExecution.nThreads
                )
            )
            return readSignatures()
        }

        override suspend fun applyHotOverride(params: CanonicalParameterSet): ParameterSignatureSnapshot {
            val threads = (params.value("n_threads") as Number).toInt()
            events += "hot:$threads"
            current = current.copy(
                engineProfile = current.engineProfile.copy(hotExecutionValues = params),
                hotExecution = current.hotExecution.copy(nThreads = threads)
            )
            return readSignatures()
        }

        override suspend fun restoreCommittedHot(params: CanonicalParameterSet): ParameterSignatureSnapshot =
            applyHotOverride(params)

        override suspend fun readSignatures(): ParameterSignatureSnapshot {
            if (mismatchNextSignatureRead) {
                mismatchNextSignatureRead = false
                return snapshot(current).copy(effective = null)
            }
            return snapshot(current)
        }

        override suspend fun runCanary(
            params: CanaryEvaluationParams,
            suite: CorrectnessSuite
        ): CanaryExecutionResult {
            events += "canary"
            if (cancelCanary) throw CancellationException("cancelled by test")
            return CanaryExecutionResult(canary)
        }

        override suspend fun readMeasurementPoint(): MeasurementPoint = point()

        private fun snapshot(profile: TuningExecutionProfile): ParameterSignatureSnapshot {
            val expected = profile.expectedSignatures()
            return ParameterSignatureSnapshot(
                desired = profile.engineProfile.desiredSignature,
                resolved = profile.engineProfile.resolvedLoadSignature,
                active = expected.activeLoaded,
                committed = profile.engineProfile.committedExecutionSignature,
                override = RuntimeOverrideSignature.none(profile.runtimeIdentity),
                effective = expected.effectiveExecution
            )
        }
    }

    private val identity = ModelRuntimeIdentity(
        modelId = "same-model",
        artifactFingerprint = "same-model-artifact",
        runtime = LocalChatRuntime.LLAMA_CPP,
        runtimeVersion = "test",
        nativeLibrarySha256 = "native-test"
    )

    private val testDevice = com.muyuchat.core.deviceprofile.DeviceProfile(
        socManufacturer = "Qualcomm",
        socModel = "Test",
        socFamily = com.muyuchat.core.telemetry.SocFamily.Snapdragon,
        cpuCores = 8,
        estimatedBigCores = 6,
        totalRamBytes = 12L * GB,
        availableRamBytes = 5L * GB,
        storageFreeBytes = 64L * GB,
        androidApi = 36,
        thermalStatus = ThermalStatus.None,
        batteryPercent = 80,
        isCharging = true,
        supportedAbis = listOf("arm64-v8a"),
        primaryAbi = "arm64-v8a"
    )

    private companion object {
        const val MB = 1024L * 1024L
        const val GB = 1024L * 1024L * 1024L
    }
}
