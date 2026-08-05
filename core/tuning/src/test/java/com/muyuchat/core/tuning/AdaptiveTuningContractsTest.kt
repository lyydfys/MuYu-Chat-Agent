package com.muyuchat.core.tuning

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.ThermalStatus
import com.muyuchat.core.engine.LlamaAdvancedParams
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.telemetry.SocFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveTuningContractsTest {
    @Test
    fun adaptiveOutputSeparatesExecutionGenerationAndCanaryValues() {
        val plan = TuningPlan(
            nCtx = 8192,
            nPredict = 4096,
            nThreads = 8,
            temperature = 0.73f,
            topK = 31,
            topP = 0.82f,
            minP = 0.04f,
            repeatPenalty = 1.07f,
            presencePenalty = 0.1f,
            advancedJson = LlamaAdvancedParams(
                nBatch = 1024,
                nUbatch = 256,
                cacheTypeK = "q4_0",
                cacheTypeV = "q4_0",
                flashAttn = "on",
                specType = "draft-mtp",
                specDraftNMax = 2
            ).toJsonString()
        )
        val adaptive = plan.toAdaptive(
            runtimeIdentity = identity("model-a", setOf("draft_mtp")),
            capabilities = ModelTuningCapabilities(
                runtime = TuningRuntime.LLAMA_CPP,
                supportsBatchTuning = true,
                supportsQuantizedKv = true,
                supportsFlashAttention = true,
                supportsSpeculativeMtp = true
            ),
            profileKind = ExecutionProfileKind.BALANCED,
            device = device()
        )

        assertEquals(8192, adaptive.executionProfile.loadBound.nCtx)
        assertEquals(8, adaptive.executionProfile.hotExecution.nThreads)
        assertEquals("draft-mtp", adaptive.executionProfile.loadBound.speculativeType)
        assertEquals(0.73f, adaptive.generationRecommendation.temperature)
        assertEquals(4096, adaptive.generationRecommendation.maxOutputTokens)
        assertTrue(adaptive.generationRecommendation.explicitApplyRequired)
        assertEquals(0.0f, adaptive.canaryParams.temperature)
        assertEquals(1, adaptive.canaryParams.topK)
        assertNotEquals(
            adaptive.generationRecommendation.maxOutputTokens,
            adaptive.canaryParams.maxOutputTokens
        )
    }

    @Test
    fun verifiedMtpCapabilitySuppliesFallbackWhenTheModelWasRenamed() {
        val plan = TuningPlan(
            nCtx = 4096,
            nPredict = 512,
            nThreads = 8,
            temperature = 0.7f,
            topK = 20,
            topP = 0.9f,
            minP = 0.0f,
            repeatPenalty = 1.0f,
            presencePenalty = 0.0f,
            advancedJson = "{}"
        )

        val adaptive = plan.toAdaptive(
            runtimeIdentity = identity("renamed-exact-model", setOf("draft_mtp")),
            capabilities = ModelTuningCapabilities(
                runtime = TuningRuntime.LLAMA_CPP,
                supportsSpeculativeMtp = true
            ),
            profileKind = ExecutionProfileKind.BALANCED,
            device = device()
        )

        assertEquals("draft-mtp", adaptive.executionProfile.loadBound.speculativeType)
        assertEquals(2, adaptive.executionProfile.loadBound.speculativeDraftMax)
    }

    @Test
    fun unknownModelAlwaysUsesIndependentCpuSafeBaseline() {
        val aggressive = TuningPlan(
            nCtx = 32768,
            nPredict = 8192,
            nThreads = 12,
            temperature = 0.6f,
            topK = 20,
            topP = 0.95f,
            minP = 0.0f,
            repeatPenalty = 1.0f,
            presencePenalty = 0.0f,
            advancedJson = LlamaAdvancedParams(
                nBatch = 4096,
                nUbatch = 1024,
                nGpuLayers = -2,
                mainGpu = 1,
                cacheTypeK = "q4_0",
                cacheTypeV = "q4_0",
                flashAttn = "on",
                specType = "draft-mtp",
                specDraftNMax = 4
            ).toJsonString(),
            backend = "gpu"
        )
        val adaptive = aggressive.toAdaptive(
            runtimeIdentity = identity("unknown-model"),
            capabilities = ModelTuningCapabilities(
                runtime = TuningRuntime.LLAMA_CPP,
                knowledgeLevel = ModelKnowledgeLevel.UNKNOWN,
                maxContextTokens = 131072,
                supportsBatchTuning = true,
                supportsQuantizedKv = true,
                supportsFlashAttention = true,
                supportsGpuOffload = true,
                supportsSpeculativeMtp = true
            ),
            profileKind = ExecutionProfileKind.SPEED,
            device = device()
        )
        val profile = adaptive.executionProfile

        assertEquals(ExecutionProfileKind.SAFE_BASELINE, profile.kind)
        assertEquals(4096, profile.loadBound.nCtx)
        assertEquals("cpu", profile.loadBound.backend)
        assertEquals(0, profile.loadBound.gpuLayers)
        assertEquals(0, profile.loadBound.mainGpu)
        assertEquals("f16", profile.loadBound.cacheTypeK)
        assertEquals("f16", profile.loadBound.cacheTypeV)
        assertEquals("off", profile.loadBound.flashAttention)
        assertEquals("none", profile.loadBound.speculativeType)
        assertEquals(0, profile.loadBound.speculativeDraftMax)
        assertTrue(profile.reason.contains("未继承其他模型"))
    }

    @Test
    fun missingUnknownTemplateIsBlockedWithAnAction() {
        val profile = SafeBaselineFactory.create(
            runtimeIdentity = identity("unknown-no-template"),
            device = device(),
            capabilities = ModelTuningCapabilities(
                runtime = TuningRuntime.LLAMA_CPP,
                knowledgeLevel = ModelKnowledgeLevel.UNKNOWN,
                chatTemplateReady = false
            )
        )

        assertEquals(ProfileEligibility.BLOCKED_WITH_ACTION, profile.eligibility)
        assertNotNull(profile.blockedAction)
        assertTrue(profile.blockedAction!!.contains("chat template"))
    }

    @Test
    fun missingQairtDiagnosticEvidenceDoesNotBlockTheSafeBaseline() {
        val profile = SafeBaselineFactory.create(
            runtimeIdentity = identity(
                modelId = "unverified-qairt",
                runtime = LocalChatRuntime.GENIEX_QAIRT
            ),
            device = device(),
            capabilities = ModelTuningCapabilities(
                runtime = TuningRuntime.QAIRT,
                qairtAdmissionPassed = false
            )
        )

        assertEquals(ProfileEligibility.ELIGIBLE, profile.eligibility)
        assertNull(profile.blockedAction)
        assertEquals("qairt", profile.loadBound.backend)
    }

    @Test
    fun thermalThreadOverrideNeverMutatesCommittedProfileOrSignature() {
        val engine = TuningEngine()
        val recommendation = engine.recommendAdaptive(
            device = device(thermal = ThermalStatus.Severe, battery = 10),
            modelParametersB = 3.0,
            runtimeIdentity = identity("stable-model"),
            preference = UserPreference(PerformanceMode.Speed)
        )
        val profile = recommendation.executionProfile
        val committedBefore = profile.expectedSignatures()
        val override = recommendation.runtimeOverride

        assertNotNull(override)
        assertTrue(profile.hotExecution.nThreads > override!!.nThreads)
        assertEquals(committedBefore, profile.expectedSignatures())
        assertEquals(profile.hotExecution.nThreads, profile.copy().hotExecution.nThreads)
        assertNotEquals(
            committedBefore.effectiveExecution,
            profile.expectedSignatures(override).effectiveExecution
        )
    }

    @Test
    fun correctnessHardGateInvalidatesFastCandidateBeforeSpeedScore() {
        val measurement = MeasurementEnvelope(
            start = point(),
            end = point(),
            samples = listOf(
                PerformanceSample(ttftMs = 50, decodeTps = 100.0),
                PerformanceSample(ttftMs = 55, decodeTps = 110.0)
            )
        )
        val failed = CandidateScorer.score(
            hardGate = passingGate().copy(correctnessPassed = false),
            measurement = measurement
        )
        val slowerButCorrect = CandidateScorer.score(
            hardGate = passingGate(),
            measurement = measurement.copy(
                samples = listOf(
                    PerformanceSample(ttftMs = 400, decodeTps = 4.0),
                    PerformanceSample(ttftMs = 420, decodeTps = 4.1)
                )
            )
        )

        assertFalse(failed.eligible)
        assertNull(failed.value)
        assertTrue(slowerButCorrect.eligible)
        assertNotNull(slowerButCorrect.value)
    }

    private fun passingGate(): CandidateHardGate = CandidateHardGate(
        correctnessPassed = true,
        crashCount = 0,
        anrCount = 0,
        nativeFatalSignalCount = 0,
        lowMemoryTriggered = false,
        outputVisible = true,
        templateValid = true,
        safetyPassed = true,
        signaturesMatch = true
    )

    private fun point(): MeasurementPoint = MeasurementPoint(
        thermalStatus = ThermalStatus.None,
        batteryPercent = 80,
        isCharging = true,
        availableMemoryBytes = 4L * GB,
        pssBytes = 1L * GB,
        rssBytes = 1L * GB
    )

    private fun identity(
        modelId: String,
        capabilities: Set<String> = emptySet(),
        runtime: LocalChatRuntime = LocalChatRuntime.LLAMA_CPP
    ): ModelRuntimeIdentity = ModelRuntimeIdentity(
        modelId = modelId,
        artifactFingerprint = "artifact-$modelId",
        runtime = runtime,
        runtimeVersion = "test",
        nativeLibrarySha256 = "native-test",
        capabilities = capabilities
    )

    private fun device(
        thermal: ThermalStatus = ThermalStatus.None,
        battery: Int = 80
    ): DeviceProfile = DeviceProfile(
        socManufacturer = "Qualcomm",
        socModel = "Test",
        socFamily = SocFamily.Snapdragon,
        cpuCores = 8,
        estimatedBigCores = 6,
        totalRamBytes = 12L * GB,
        availableRamBytes = 5L * GB,
        storageFreeBytes = 64L * GB,
        androidApi = 36,
        thermalStatus = thermal,
        batteryPercent = battery,
        isCharging = false,
        supportedAbis = listOf("arm64-v8a"),
        primaryAbi = "arm64-v8a"
    )

    private companion object {
        const val GB = 1024L * 1024L * 1024L
    }
}
