package com.muyuchat.mca

import com.muyuchat.core.engine.CanonicalParameterSet
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.tuning.ExecutionProfileKind
import com.muyuchat.core.tuning.HotExecutionParams
import com.muyuchat.core.tuning.LoadBoundExecutionParams
import com.muyuchat.core.tuning.ModelTuningCapabilities
import com.muyuchat.core.tuning.TuningExecutionProfile
import com.muyuchat.core.tuning.TuningRuntime
import com.muyuchat.core.tuning.TuningSearchDepth
import com.muyuchat.core.tuning.TuningSearchStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTuningStageSupportTest {
    @Test
    fun nextStageCandidatesRetainThePreviousStagesSelectedBest() {
        val base = profile(nThreads = 4)
        val threadCandidates = candidates(TuningSearchStage.THREADS, base)
        val selectedThreadProfile = threadCandidates
            .first { it.profile.hotExecution.nThreads == 6 }
            .profile

        val batchCandidates = candidates(TuningSearchStage.BATCH, selectedThreadProfile)

        assertTrue(batchCandidates.isNotEmpty())
        assertTrue(batchCandidates.all { it.profile.hotExecution.nThreads == 6 })
        assertTrue(batchCandidates.all {
            it.profile.engineProfile.hotExecutionValues.value("n_threads") == 6
        })
    }

    @Test
    fun anotherStageNeverOverwritesExplicitUserValues() {
        val base = profile(nCtx = 8_192, nBatch = 768, nUbatch = 192)

        val threadCandidates = buildAgentTuningStageCandidates(
            stage = TuningSearchStage.THREADS,
            base = base,
            capabilities = capabilities,
            cpuCores = 8,
            estimatedBigCores = 6,
            depth = TuningSearchDepth.DEEP,
            userOverrides = setOf("n_ctx", "n_batch", "n_ubatch"),
            profileIdPrefix = "threads",
            revision = 2
        )

        assertTrue(threadCandidates.isNotEmpty())
        assertTrue(threadCandidates.all { it.profile.loadBound.nCtx == 8_192 })
        assertTrue(threadCandidates.all { it.profile.loadBound.nBatch == 768 })
        assertTrue(threadCandidates.all { it.profile.loadBound.nUbatch == 192 })
    }

    @Test
    fun explicitContextOverrideRemovesTheContextSearchStage() {
        val stages = agentTuningStages(
            depth = TuningSearchDepth.DEEP,
            capabilities = capabilities,
            userOverrides = setOf("n_ctx")
        )

        assertFalse(TuningSearchStage.CONTEXT in stages)
        assertTrue(TuningSearchStage.THREADS in stages)
    }

    @Test
    fun progressSummaryContainsOnlyTheCompleteCanonicalExecutionProfile() {
        val summary = tuningExecutionParameterSummary(profile())

        assertTrue(summary.contains("n_ctx=8192"))
        assertTrue(summary.contains("n_threads=4"))
        assertTrue(summary.contains("n_batch=512"))
        assertTrue(summary.contains("cache_type_k=f16"))
        assertTrue(summary.contains("backend=cpu"))
        assertFalse(summary.contains("temperature"))
        assertFalse(summary.contains("system_prompt"))
        assertFalse(summary.contains("n_predict"))
        assertEquals(summary.split(" · ").sorted(), summary.split(" · "))
    }

    private fun candidates(
        stage: TuningSearchStage,
        base: TuningExecutionProfile
    ): List<AgentTuningStageCandidate> = buildAgentTuningStageCandidates(
        stage = stage,
        base = base,
        capabilities = capabilities,
        cpuCores = 8,
        estimatedBigCores = 6,
        depth = TuningSearchDepth.DEEP,
        userOverrides = emptySet(),
        profileIdPrefix = "probe",
        revision = 2
    )

    private fun profile(
        nCtx: Int = 8_192,
        nThreads: Int = 4,
        nBatch: Int = 512,
        nUbatch: Int = 256
    ): TuningExecutionProfile {
        val identity = ModelRuntimeIdentity(
            modelId = "test-model",
            artifactFingerprint = "artifact",
            runtime = LocalChatRuntime.LLAMA_CPP
        )
        val load = CanonicalParameterSet.of(
            mapOf(
                "backend" to "cpu",
                "cache_type_k" to "f16",
                "cache_type_v" to "f16",
                "flash_attn" to "off",
                "mmap" to true,
                "mlock" to false,
                "n_batch" to nBatch,
                "n_ctx" to nCtx,
                "n_parallel" to 1,
                "n_ubatch" to nUbatch,
                "spec_draft_n_max" to 0,
                "spec_type" to "none"
            )
        )
        val hot = CanonicalParameterSet.of(mapOf("n_threads" to nThreads))
        val engineProfile = ModelExecutionProfile(
            modelId = identity.modelId,
            runtimeIdentity = identity,
            desiredLoadBoundValues = load,
            resolvedLoadBoundValues = load,
            hotExecutionValues = hot,
            desiredHotExecutionValues = hot,
            profileId = "base",
            revision = 1
        )
        return TuningExecutionProfile(
            engineProfile = engineProfile,
            kind = ExecutionProfileKind.BALANCED,
            loadBound = LoadBoundExecutionParams(
                nCtx = nCtx,
                nBatch = nBatch,
                nUbatch = nUbatch,
                cacheTypeK = "f16",
                cacheTypeV = "f16",
                flashAttention = "off",
                speculativeType = "none",
                speculativeDraftMax = 0,
                backend = "cpu"
            ),
            hotExecution = HotExecutionParams(nThreads = nThreads)
        )
    }

    private companion object {
        val capabilities = ModelTuningCapabilities(
            runtime = TuningRuntime.LLAMA_CPP,
            maxContextTokens = 32_768,
            supportsBatchTuning = true,
            supportsQuantizedKv = true,
            supportsFlashAttention = true,
            supportsSpeculativeMtp = true
        )
    }
}
