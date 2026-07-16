package com.muyuchat.core.tuning

import com.muyuchat.core.engine.CanonicalParameterSet
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StagedTuningCandidatePolicyTest {
    @Test
    fun contextStageChoosesTheLargestSafeCapacityInsteadOfTheFastestSmallContext() {
        data class Candidate(val context: Int, val score: CandidateScore)
        val candidates = listOf(
            Candidate(2_048, candidateScore(eligible = true, value = 100.0)),
            Candidate(8_192, candidateScore(eligible = true, value = 40.0)),
            Candidate(32_768, candidateScore(eligible = false, value = null, reason = "unsafe"))
        )

        val selected = StagedCandidateSelectionPolicy.selectBestEligible(
            stage = TuningSearchStage.CONTEXT,
            candidates = candidates,
            scoreOf = Candidate::score,
            contextTokensOf = Candidate::context
        )

        assertEquals(8_192, selected?.context)
    }

    private fun candidateScore(
        eligible: Boolean,
        value: Double?,
        reason: String? = null
    ): CandidateScore = CandidateScore(
        eligible = eligible,
        value = value,
        medianDecodeTps = value ?: 0.0,
        medianTtftMs = 0.0,
        decodeVariance = 0.0,
        reason = reason
    )

    @Test
    fun quickSearchContainsOnlyThreadCandidates() {
        val stages = StagedTuningCandidatePolicy.stagesFor(
            depth = TuningSearchDepth.QUICK,
            capabilities = fullLlamaCapabilities
        )

        assertEquals(listOf(TuningSearchStage.THREADS), stages)
        assertTrue(candidatesFor(stages.single(), TuningSearchDepth.QUICK).isNotEmpty())
    }

    @Test
    fun standardSearchContainsBatchAndKvStages() {
        val stages = StagedTuningCandidatePolicy.stagesFor(
            depth = TuningSearchDepth.STANDARD,
            capabilities = fullLlamaCapabilities
        )

        assertEquals(
            listOf(
                TuningSearchStage.THREADS,
                TuningSearchStage.BATCH,
                TuningSearchStage.KV_FLASH
            ),
            stages
        )
        assertTrue(candidatesFor(TuningSearchStage.BATCH, TuningSearchDepth.STANDARD).isNotEmpty())
        assertTrue(candidatesFor(TuningSearchStage.KV_FLASH, TuningSearchDepth.STANDARD).isNotEmpty())
    }

    @Test
    fun deepSearchContainsContextAndMtpStages() {
        val stages = StagedTuningCandidatePolicy.stagesFor(
            depth = TuningSearchDepth.DEEP,
            capabilities = fullLlamaCapabilities
        )

        assertEquals(
            listOf(
                TuningSearchStage.THREADS,
                TuningSearchStage.BATCH,
                TuningSearchStage.KV_FLASH,
                TuningSearchStage.CONTEXT,
                TuningSearchStage.MODEL_SPECIAL
            ),
            stages
        )
        assertTrue(candidatesFor(TuningSearchStage.CONTEXT, TuningSearchDepth.DEEP).isNotEmpty())
        assertTrue(candidatesFor(TuningSearchStage.MODEL_SPECIAL, TuningSearchDepth.DEEP).isNotEmpty())
    }

    @Test
    fun unknownModelsAndUnsupportedRuntimesDoNotReceiveDangerousCandidates() {
        val unknownModel = fullLlamaCapabilities.copy(knowledgeLevel = ModelKnowledgeLevel.UNKNOWN)
        assertEquals(
            listOf(TuningSearchStage.THREADS),
            StagedTuningCandidatePolicy.stagesFor(TuningSearchDepth.DEEP, unknownModel)
        )
        dangerousStages.forEach { stage ->
            assertTrue(candidatesFor(stage, TuningSearchDepth.DEEP, unknownModel).isEmpty())
        }

        val mnn = fullLlamaCapabilities.copy(runtime = TuningRuntime.MNN)
        assertEquals(
            listOf(TuningSearchStage.THREADS),
            StagedTuningCandidatePolicy.stagesFor(TuningSearchDepth.DEEP, mnn)
        )
        dangerousStages.forEach { stage ->
            assertTrue(candidatesFor(stage, TuningSearchDepth.DEEP, mnn).isEmpty())
        }

        val qairt = fullLlamaCapabilities.copy(runtime = TuningRuntime.QAIRT)
        assertTrue(StagedTuningCandidatePolicy.stagesFor(TuningSearchDepth.DEEP, qairt).isEmpty())
        TuningSearchStage.entries.forEach { stage ->
            assertTrue(candidatesFor(stage, TuningSearchDepth.DEEP, qairt).isEmpty())
        }

        val unknownRuntime = fullLlamaCapabilities.copy(runtime = TuningRuntime.UNKNOWN)
        assertTrue(StagedTuningCandidatePolicy.stagesFor(TuningSearchDepth.DEEP, unknownRuntime).isEmpty())
        TuningSearchStage.entries.forEach { stage ->
            assertTrue(candidatesFor(stage, TuningSearchDepth.DEEP, unknownRuntime).isEmpty())
        }
    }

    @Test
    fun contextSearchRequiresAReadableDeclaredMaximum() {
        val unreadable = fullLlamaCapabilities.copy(metadataReadable = false)
        val missingMaximum = fullLlamaCapabilities.copy(maxContextTokens = null)

        assertFalse(
            TuningSearchStage.CONTEXT in
                StagedTuningCandidatePolicy.stagesFor(TuningSearchDepth.DEEP, unreadable)
        )
        assertFalse(
            TuningSearchStage.CONTEXT in
                StagedTuningCandidatePolicy.stagesFor(TuningSearchDepth.DEEP, missingMaximum)
        )
        assertTrue(candidatesFor(TuningSearchStage.CONTEXT, TuningSearchDepth.DEEP, unreadable).isEmpty())
        assertTrue(candidatesFor(TuningSearchStage.CONTEXT, TuningSearchDepth.DEEP, missingMaximum).isEmpty())
    }

    @Test
    fun userOverridesAreNeverChangedByGeneratedCandidates() {
        val overrideSets = listOf(
            setOf("n_ctx"),
            setOf("n_batch"),
            setOf("n_ubatch"),
            setOf("cache_type_k"),
            setOf("cache_type_v"),
            setOf("flash_attn"),
            setOf("spec_type"),
            setOf("spec_draft_n_max"),
            setOf("n_threads"),
            setOf("n_threads_batch")
        )

        overrideSets.forEach { overrides ->
            val candidates = TuningSearchStage.entries.flatMap { stage ->
                candidatesFor(
                    stage = stage,
                    depth = TuningSearchDepth.DEEP,
                    userOverrides = overrides
                )
            }
            assertTrue(
                "Generated candidate changed user overrides $overrides: $candidates",
                candidates.all { candidate -> candidate.changedFields.intersect(overrides).isEmpty() }
            )
        }
    }

    @Test
    fun generatedLoadCandidatesNeverPlaceUbatchAboveBatch() {
        val candidates = TuningSearchStage.entries.flatMap { stage ->
            candidatesFor(stage, TuningSearchDepth.DEEP)
        }

        candidates.forEach { candidate ->
            val applied = candidate.applyTo(
                base = baseProfile,
                profileId = "candidate-${candidate.stage}-${candidate.label}",
                revision = 2
            )
            val batch = applied.loadBound.nBatch
            val ubatch = applied.loadBound.nUbatch
            assertTrue(
                "${candidate.label} produced n_batch=$batch, n_ubatch=$ubatch",
                batch == null || ubatch == null || ubatch <= batch
            )
        }
    }

    private fun candidatesFor(
        stage: TuningSearchStage,
        depth: TuningSearchDepth,
        capabilities: ModelTuningCapabilities = fullLlamaCapabilities,
        userOverrides: Set<String> = emptySet()
    ): List<TuningCandidatePatch> = StagedTuningCandidatePolicy.candidatesFor(
        stage = stage,
        base = baseProfile,
        capabilities = capabilities,
        cpuCores = 8,
        estimatedBigCores = 4,
        depth = depth,
        userOverrides = userOverrides
    )

    private companion object {
        val dangerousStages = setOf(
            TuningSearchStage.BATCH,
            TuningSearchStage.KV_FLASH,
            TuningSearchStage.CONTEXT,
            TuningSearchStage.MODEL_SPECIAL
        )

        val fullLlamaCapabilities = ModelTuningCapabilities(
            runtime = TuningRuntime.LLAMA_CPP,
            knowledgeLevel = ModelKnowledgeLevel.KNOWN,
            metadataReadable = true,
            maxContextTokens = 32_768,
            supportsBatchTuning = true,
            supportsQuantizedKv = true,
            supportsFlashAttention = true,
            supportsSpeculativeMtp = true
        )

        val baseProfile: TuningExecutionProfile = run {
            val identity = ModelRuntimeIdentity(
                modelId = "staged-policy-test",
                artifactFingerprint = "artifact-sha256",
                runtime = LocalChatRuntime.LLAMA_CPP
            )
            val loadValues = CanonicalParameterSet.of(
                mapOf(
                    "n_ctx" to 4096,
                    "n_batch" to 512,
                    "n_ubatch" to 256,
                    "cache_type_k" to "f16",
                    "cache_type_v" to "f16",
                    "flash_attn" to "off",
                    "spec_type" to "none",
                    "spec_draft_n_max" to 0
                )
            )
            val hotValues = CanonicalParameterSet.of(
                mapOf(
                    "n_threads" to 4,
                    "n_threads_batch" to 4
                )
            )
            TuningExecutionProfile(
                engineProfile = ModelExecutionProfile(
                    modelId = identity.modelId,
                    runtimeIdentity = identity,
                    desiredLoadBoundValues = loadValues,
                    resolvedLoadBoundValues = loadValues,
                    hotExecutionValues = hotValues
                ),
                kind = ExecutionProfileKind.BALANCED,
                loadBound = LoadBoundExecutionParams(
                    nCtx = 4096,
                    nBatch = 512,
                    nUbatch = 256,
                    cacheTypeK = "f16",
                    cacheTypeV = "f16",
                    flashAttention = "off",
                    speculativeType = "none",
                    speculativeDraftMax = 0
                ),
                hotExecution = HotExecutionParams(
                    nThreads = 4,
                    nThreadsBatch = 4
                )
            )
        }
    }
}
