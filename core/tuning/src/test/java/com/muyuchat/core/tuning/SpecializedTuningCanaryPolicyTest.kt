package com.muyuchat.core.tuning

import com.muyuchat.core.engine.CanonicalParameterSet
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecializedTuningCanaryPolicyTest {
    @Test
    fun plannerKeepsHotOnlyCandidateInProcess() {
        val committed = profile(id = "committed", threads = 6)
        val candidate = profile(id = "candidate", threads = 8)

        val plan = TuningCandidateCanaryPlanner.plan(committed, candidate)

        assertEquals(setOf(SpecializedCanaryProbe.MINIMUM_TEXT), plan.probes)
        assertTrue(plan.changedLoadFields.isEmpty())
        assertEquals(setOf("n_threads"), plan.changedHotFields)
        assertEquals(CandidateProcessBoundary.CALLER_PROCESS_ALLOWED, plan.processBoundary)
        assertTrue(
            CandidateIsolationPolicy.assess(plan, CandidateExecutionEnvironment.CALLER_PROCESS).passed
        )
    }

    @Test
    fun plannerRequiresIsolationAndNeedleForContextIncrease() {
        val committed = profile(id = "committed", nCtx = 4096)
        val candidate = profile(id = "candidate", nCtx = 8192)

        val plan = TuningCandidateCanaryPlanner.plan(committed, candidate)

        assertTrue(SpecializedCanaryProbe.LONG_CONTEXT_NEEDLE in plan.probes)
        assertEquals(CandidateProcessBoundary.ISOLATED_PROCESS_REQUIRED, plan.processBoundary)
        assertTrue(plan.longContextSpec!!.minimumPromptTokens > 4096)
        assertFalse(
            CandidateIsolationPolicy.assess(plan, CandidateExecutionEnvironment.CALLER_PROCESS).passed
        )
        assertTrue(
            CandidateIsolationPolicy.assess(plan, CandidateExecutionEnvironment.ISOLATED_PROCESS).passed
        )
    }

    @Test
    fun plannerAddsRepeatedBatchKvAndMtpProofs() {
        val committed = profile(id = "committed")
        val candidate = profile(
            id = "candidate",
            nBatch = 1024,
            nUbatch = 256,
            cacheTypeK = "q4_0",
            cacheTypeV = "q4_0",
            flashAttention = "on",
            speculativeType = "draft-mtp",
            speculativeDraftMax = 2
        )

        val plan = TuningCandidateCanaryPlanner.plan(committed, candidate)

        assertTrue(SpecializedCanaryProbe.REPEATED_BATCH_KV in plan.probes)
        assertTrue(SpecializedCanaryProbe.SPECULATIVE_MTP in plan.probes)
        assertEquals(2, plan.requiredRepeatedRuns)
        assertEquals(CandidateProcessBoundary.ISOLATED_PROCESS_REQUIRED, plan.processBoundary)
    }

    @Test
    fun plannerRejectsDifferentRuntimeIdentityBeforeExecution() {
        val committed = profile(id = "committed")
        val candidate = profile(id = "candidate", identity = identity("other-model"))

        val plan = TuningCandidateCanaryPlanner.plan(committed, candidate)
        val assessment = CandidateIsolationPolicy.assess(
            plan,
            CandidateExecutionEnvironment.ISOLATED_PROCESS
        )

        assertEquals(CandidateProcessBoundary.REJECT_IDENTITY_MISMATCH, plan.processBoundary)
        assertEquals(setOf("identity_mismatch"), assessment.codes())
    }

    @Test
    fun longContextNeedleRequiresDeepPromptExactRecallAndNoShift() {
        val spec = LongContextNeedleCanaryPolicy.specFor(8192)
        val passing = LongContextNeedleEvidence(
            requestId = "context-request-1",
            generationSequenceBefore = 10,
            generationSequenceAfter = 11,
            effectiveContextTokens = 8192,
            promptTokens = spec.minimumPromptTokens + 32,
            needleTokenIndex = (spec.minimumPromptTokens + 32) / 2,
            completionTokens = 4,
            contextShifts = 0,
            output = spec.expectedOutput + "\n"
        )

        assertTrue(LongContextNeedleCanaryPolicy.assess(spec, passing).passed)
        assertEquals(1, Regex(Regex.escape(spec.marker)).findAll(spec.prompt("before", "after")).count())

        val failed = LongContextNeedleCanaryPolicy.assess(
            spec,
            passing.copy(
                requestId = "",
                generationSequenceAfter = 10,
                effectiveContextTokens = 4096,
                promptTokens = 2048,
                needleTokenIndex = 64,
                contextShifts = 1,
                output = "NEEDLE=wrong"
            )
        )
        assertEquals(
            setOf(
                "request_id_missing",
                "generation_not_observed",
                "effective_context_too_small",
                "prompt_not_long_enough",
                "needle_not_deep",
                "unexpected_context_shift",
                "needle_recall_failed"
            ),
            failed.codes()
        )
    }

    @Test
    fun mtpProofUsesBeforeAfterDeltaForThisGeneration() {
        val candidate = profile(
            id = "mtp",
            speculativeType = "draft-mtp",
            speculativeDraftMax = 2
        )
        val before = speculativeStats(
            sequence = 20,
            completionTokens = 0,
            drafted = 100,
            accepted = 60,
            steps = 50,
            active = false,
            reason = "awaiting_text_request"
        )
        val after = speculativeStats(
            sequence = 21,
            completionTokens = 32,
            drafted = 104,
            accepted = 62,
            steps = 52,
            active = true,
            reason = "active"
        )

        assertTrue(SpeculativeMtpCanaryPolicy.assess(candidate, before, after).passed)
    }

    @Test
    fun mtpProofRejectsStaleOrIneffectiveCounters() {
        val candidate = profile(
            id = "mtp",
            speculativeType = "draft-mtp",
            speculativeDraftMax = 2
        )
        val before = speculativeStats(
            sequence = 20,
            completionTokens = 0,
            drafted = 100,
            accepted = 60,
            steps = 50,
            active = false,
            reason = "awaiting_text_request"
        )
        val stale = speculativeStats(
            sequence = 20,
            completionTokens = 32,
            drafted = 104,
            accepted = 60,
            steps = 52,
            active = true,
            reason = "active"
        )

        val assessment = SpeculativeMtpCanaryPolicy.assess(candidate, before, stale)

        assertTrue("generation_not_observed" in assessment.codes())
        assertTrue("spec_accepts_missing" in assessment.codes())
        assertFalse(assessment.passed)
    }

    @Test
    fun disabledMtpCandidateMustBeDisabledNatively() {
        val candidate = profile(id = "no-mtp")
        val disabled = speculativeStats(
            sequence = 5,
            completionTokens = 16,
            drafted = 0,
            accepted = 0,
            steps = 0,
            requested = false,
            contextReady = false,
            active = false,
            reason = "disabled",
            specType = "none",
            specDraftNMax = 0
        )

        assertTrue(SpeculativeMtpCanaryPolicy.assess(candidate, "{}", disabled).passed)
    }

    @Test
    fun batchKvProofRequiresTwoIndependentSafeNativeMatches() {
        val candidate = profile(
            id = "batch-kv",
            nBatch = 1024,
            nUbatch = 256,
            cacheTypeK = "q4_0",
            cacheTypeV = "q4_0",
            flashAttention = "on"
        )
        val observations = listOf(
            observation("request-a", 31, candidate),
            observation("request-b", 32, candidate)
        )

        assertTrue(BatchKvCanaryPolicy.assess(candidate, observations).passed)
    }

    @Test
    fun batchKvProofRejectsReusedWitnessUnsafeRunAndNativeMismatch() {
        val candidate = profile(
            id = "batch-kv",
            nBatch = 1024,
            nUbatch = 256,
            cacheTypeK = "q4_0",
            cacheTypeV = "q4_0",
            flashAttention = "on"
        )
        val observations = listOf(
            observation("same-request", 41, candidate),
            observation(
                requestId = "same-request",
                sequence = 41,
                candidate = candidate,
                gate = passingGate().copy(lowMemoryTriggered = true, safetyPassed = false),
                cacheTypeV = "f16"
            )
        )

        val assessment = BatchKvCanaryPolicy.assess(candidate, observations)

        assertTrue("request_ids_not_independent" in assessment.codes())
        assertTrue("generation_sequences_not_independent" in assessment.codes())
        assertTrue("run_2_memory_safety" in assessment.codes())
        assertTrue("run_2_cache_type_v_mismatch" in assessment.codes())
        assertFalse(assessment.passed)
    }

    private fun profile(
        id: String,
        identity: ModelRuntimeIdentity = identity(),
        nCtx: Int = 4096,
        nBatch: Int = 512,
        nUbatch: Int = 128,
        cacheTypeK: String = "f16",
        cacheTypeV: String = "f16",
        flashAttention: String = "off",
        speculativeType: String = "none",
        speculativeDraftMax: Int = 0,
        threads: Int = 6
    ): TuningExecutionProfile {
        val loadValues = CanonicalParameterSet.of(
            mapOf(
                "n_ctx" to nCtx,
                "n_batch" to nBatch,
                "n_ubatch" to nUbatch,
                "cache_type_k" to cacheTypeK,
                "cache_type_v" to cacheTypeV,
                "flash_attn" to flashAttention,
                "spec_type" to speculativeType,
                "spec_draft_n_max" to speculativeDraftMax,
                "mmap" to true,
                "mlock" to false
            )
        )
        val hotValues = CanonicalParameterSet.of(mapOf("n_threads" to threads))
        val engineProfile = ModelExecutionProfile(
            modelId = identity.modelId,
            runtimeIdentity = identity,
            desiredLoadBoundValues = loadValues,
            resolvedLoadBoundValues = loadValues,
            hotExecutionValues = hotValues,
            profileId = id
        )
        return TuningExecutionProfile(
            engineProfile = engineProfile,
            kind = ExecutionProfileKind.BALANCED,
            loadBound = LoadBoundExecutionParams(
                nCtx = nCtx,
                nBatch = nBatch,
                nUbatch = nUbatch,
                cacheTypeK = cacheTypeK,
                cacheTypeV = cacheTypeV,
                flashAttention = flashAttention,
                speculativeType = speculativeType,
                speculativeDraftMax = speculativeDraftMax
            ),
            hotExecution = HotExecutionParams(nThreads = threads)
        )
    }

    private fun identity(modelId: String = "model-a"): ModelRuntimeIdentity = ModelRuntimeIdentity(
        modelId = modelId,
        artifactFingerprint = "$modelId-artifact",
        runtime = LocalChatRuntime.LLAMA_CPP,
        runtimeVersion = "test",
        nativeLibrarySha256 = "native-test"
    )

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

    private fun observation(
        requestId: String,
        sequence: Long,
        candidate: TuningExecutionProfile,
        gate: CandidateHardGate = passingGate(),
        cacheTypeV: String = candidate.loadBound.cacheTypeV!!
    ): RepeatedCandidateCanaryObservation = RepeatedCandidateCanaryObservation(
        requestId = requestId,
        hardGate = gate,
        sample = PerformanceSample(ttftMs = 300, decodeTps = 6.5),
        nativeStatsJson = JSONObject()
            .put("generationSequence", sequence)
            .put("nBatch", candidate.loadBound.nBatch)
            .put("nUbatch", candidate.loadBound.nUbatch)
            .put("cacheTypeK", candidate.loadBound.cacheTypeK)
            .put("cacheTypeV", cacheTypeV)
            .put("flashAttn", candidate.loadBound.flashAttention)
            .put("lastError", "")
            .toString()
    )

    private fun speculativeStats(
        sequence: Long,
        completionTokens: Int,
        drafted: Long,
        accepted: Long,
        steps: Long,
        requested: Boolean = true,
        contextReady: Boolean = true,
        active: Boolean,
        reason: String,
        specType: String = "draft-mtp",
        specDraftNMax: Int = 2
    ): String = JSONObject()
        .put("generationSequence", sequence)
        .put("completionTokens", completionTokens)
        .put("specType", specType)
        .put("specDraftNMax", specDraftNMax)
        .put("lastError", "")
        .put(
            "speculative",
            JSONObject()
                .put("requested", requested)
                .put("contextReady", contextReady)
                .put("activeForRequest", active)
                .put("draftedTokens", drafted)
                .put("acceptedTokens", accepted)
                .put("steps", steps)
                .put("acceptanceRate", if (drafted > 0L) accepted.toDouble() / drafted else 0.0)
                .put("reason", reason)
        )
        .toString()

    private fun SpecializedCanaryAssessment.codes(): Set<String> = violations.mapTo(linkedSetOf()) { it.code }
}
