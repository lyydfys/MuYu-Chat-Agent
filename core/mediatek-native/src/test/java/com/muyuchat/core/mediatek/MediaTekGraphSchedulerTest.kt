package com.muyuchat.core.mediatek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTekGraphSchedulerTest {
    private val confirmedGraph = MediaTekBackendEvidence.Confirmed(
        provider = "litert-mediatek",
        executionScope = MediaTekExecutionScope.GRAPH,
        npuPartitions = 18,
        cpuFallbackOps = 0,
        source = MediaTekEvidenceSource.RUNTIME_COUNTERS,
    )
    private val success = MediaTekAttemptOutcome.Succeeded("a".repeat(64))

    private fun scheduler() = MediaTekGraphScheduler()

    private fun MediaTekGraphScheduler.npuAttempt(
        graphId: String,
        stage: MediaTekGraphStage = MediaTekGraphStage.DIFFUSION,
        transport: MediaTekGraphTransport = MediaTekGraphTransport.LITERT_JIT,
        evidence: MediaTekBackendEvidence = MediaTekBackendEvidence.Unverified,
        outcome: MediaTekAttemptOutcome = success,
    ) = plan(graphId, stage, transport).let { selection ->
        record(selection, outcome, evidence, elapsedMs = 5L) to selection
    }

    @Test
    fun plannedCpuWorkAndNpuSuccessAreNotCountedAsFallback() {
        val scheduler = scheduler()
        repeat(18) { scheduler.npuAttempt("block_$it") }
        repeat(10) { index ->
            val selection = scheduler.plan(
                graphId = "cpu_block_$index",
                stage = MediaTekGraphStage.DIFFUSION,
                transport = MediaTekGraphTransport.LITERT_JIT,
                target = MediaTekComputeTarget.CPU,
                reason = MediaTekSelectionReason.MODEL_RANGE_PIN,
            )
            scheduler.record(selection, success, MediaTekBackendEvidence.Unverified, elapsedMs = 3L)
            scheduler.adopt(selection)
        }
        repeat(18) { scheduler.adopt(scheduler.attempts()[it].selection) }

        val tally = scheduler.tally()
        assertEquals(18, tally.npuSucceeded)
        assertEquals(10, tally.plannedCpu)
        assertEquals(0, tally.npuFailedThenCpu)
        assertEquals(0, tally.supersededFailures)
        assertTrue(tally.incompleteGraphs.isEmpty())
        val summary = scheduler.summary(MediaTekQualityReport.notCompared("no baseline"), outputValid = true)
        assertTrue(summary.accepted)
        assertFalse(summary.qualityVerified)
        assertEquals(MediaTekNpuAttribution.UNVERIFIED, summary.npuAttribution)
    }

    @Test
    fun referenceNpuFailureFollowedBySecondTransportSuccessIsAdopted() {
        val scheduler = scheduler()
        val (failedAttempt, failedSelection) = scheduler.npuAttempt(
            graphId = "block_0",
            transport = MediaTekGraphTransport.NPUG_NATIVE,
            outcome = MediaTekAttemptOutcome.Failed(MediaTekGraphFailureStage.COMPILE, "restore rejected"),
        )
        assertFalse(failedAttempt.succeeded)
        val (_, okSelection) = scheduler.npuAttempt(
            graphId = "block_0",
            transport = MediaTekGraphTransport.LITERT_JIT,
        )
        scheduler.adopt(okSelection)

        val tally = scheduler.tally()
        assertEquals(1, tally.npuSucceeded)
        assertEquals(1, tally.supersededFailures)
        assertTrue(tally.incompleteGraphs.isEmpty())
        val summary = scheduler.summary(MediaTekQualityReport.notCompared("no baseline"), outputValid = true)
        assertTrue(summary.accepted)
        assertEquals(failedSelection.graphId, summary.attempts.first().graphId)
    }

    @Test
    fun allCpuRunKeepsNpuAttributionUnverified() {
        val scheduler = scheduler()
        MediaTekGraphStage.entries.forEach { stage ->
            val selection = scheduler.plan(
                graphId = "graph_${stage.name}",
                stage = stage,
                transport = MediaTekGraphTransport.LITERT_JIT,
                target = MediaTekComputeTarget.CPU,
                reason = MediaTekSelectionReason.INPUT_RANGE_PIN,
            )
            scheduler.record(selection, success, MediaTekBackendEvidence.Unverified, elapsedMs = 1L)
            scheduler.adopt(selection)
        }
        val tally = scheduler.tally()
        assertEquals(0, tally.npuSucceeded)
        assertEquals(3, tally.rangeCpu)
        val summary = scheduler.summary(MediaTekQualityReport.notCompared("no baseline"), outputValid = true)
        assertTrue(summary.accepted)
        assertEquals(MediaTekNpuAttribution.UNVERIFIED, summary.npuAttribution)
    }

    @Test
    fun failedCpuRetryCannotBeAdoptedAndLeavesTheRunIncomplete() {
        val scheduler = scheduler()
        scheduler.npuAttempt(
            graphId = "block_0",
            outcome = MediaTekAttemptOutcome.Failed(MediaTekGraphFailureStage.EXECUTE, "driver error"),
        )
        val retry = scheduler.plan(
            graphId = "block_0",
            stage = MediaTekGraphStage.DIFFUSION,
            transport = MediaTekGraphTransport.LITERT_JIT,
            target = MediaTekComputeTarget.CPU,
            reason = MediaTekSelectionReason.NPU_EXECUTION_FAILED,
        )
        scheduler.record(
            retry,
            MediaTekAttemptOutcome.Failed(MediaTekGraphFailureStage.OUTPUTS, "non-finite CPU output"),
            MediaTekBackendEvidence.Unverified,
            elapsedMs = 2L,
        )
        val rejected = runCatching { scheduler.adopt(retry) }.exceptionOrNull()
        assertTrue(rejected is IllegalArgumentException)

        val summary = scheduler.summary(MediaTekQualityReport.notCompared("no baseline"), outputValid = false)
        assertFalse(summary.accepted)
        assertEquals(listOf("block_0"), summary.tally.incompleteGraphs)
    }

    @Test
    fun cfgBranchesAreAdoptedOnlyAfterBothBranchesSucceed() {
        val scheduler = scheduler()
        val (_, positive) = scheduler.npuAttempt(graphId = "positive_block_0")
        scheduler.adopt(positive)
        val negative = scheduler.plan(
            graphId = "negative_block_0",
            stage = MediaTekGraphStage.DIFFUSION,
            transport = MediaTekGraphTransport.LITERT_JIT,
        )
        val premature = runCatching { scheduler.adopt(negative) }.exceptionOrNull()
        assertTrue(premature is IllegalArgumentException)

        scheduler.record(negative, success, MediaTekBackendEvidence.Unverified, elapsedMs = 1L)
        scheduler.adopt(negative)
        assertEquals(2, scheduler.tally().npuSucceeded)
    }

    @Test
    fun nonFiniteComparisonIsInvalidRatherThanUncompared() {
        val report = MediaTekQualityReport.compare(
            referenceIdentity = "reference-cpu-v1",
            relativeL1 = Double.NaN,
            maximumAbsolute = 0.1,
            relativeL1Tolerance = 0.05,
            maximumAbsoluteTolerance = 0.5,
        )
        assertEquals(MediaTekQualityStatus.INVALID, report.status)
        assertFalse(report.verified)

        val scheduler = scheduler()
        scheduler.npuAttempt("block_0")
        scheduler.adopt(scheduler.attempts()[0].selection)
        val summary = scheduler.summary(report, outputValid = true)
        assertFalse(summary.accepted)
        assertFalse(summary.qualityVerified)
    }

    @Test
    fun missingBaselineStaysNotComparedAndCannotClaimVerifiedQuality() {
        val scheduler = scheduler()
        scheduler.npuAttempt("block_0")
        scheduler.adopt(scheduler.attempts()[0].selection)
        val summary = scheduler.summary(
            MediaTekQualityReport.notCompared("no reference baseline for this bundle"),
            outputValid = true,
        )
        assertEquals(MediaTekQualityStatus.NOT_COMPARED, summary.quality.status)
        assertTrue(summary.accepted)
        assertFalse(summary.qualityVerified)
    }

    @Test
    fun divergedQualityCannotMapToAccepted() {
        val report = MediaTekQualityReport.compare(
            referenceIdentity = "reference-cpu-v1",
            relativeL1 = 0.4,
            maximumAbsolute = 9.0,
            relativeL1Tolerance = 0.05,
            maximumAbsoluteTolerance = 0.5,
        )
        assertEquals(MediaTekQualityStatus.DIVERGED, report.status)
        val scheduler = scheduler()
        scheduler.npuAttempt("block_0")
        scheduler.adopt(scheduler.attempts()[0].selection)
        assertFalse(scheduler.summary(report, outputValid = true).accepted)
    }

    @Test
    fun cpuRepairLabelRequiresARecordedNpuFailure() {
        val scheduler = scheduler()
        val error = runCatching {
            scheduler.plan(
                graphId = "block_0",
                stage = MediaTekGraphStage.DIFFUSION,
                transport = MediaTekGraphTransport.LITERT_JIT,
                target = MediaTekComputeTarget.CPU,
                reason = MediaTekSelectionReason.NPU_EXECUTION_FAILED,
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun cpuAttemptCannotCarryNpuConfirmationEvidence() {
        val scheduler = scheduler()
        val selection = scheduler.plan(
            graphId = "cpu_block_0",
            stage = MediaTekGraphStage.DIFFUSION,
            transport = MediaTekGraphTransport.LITERT_JIT,
            target = MediaTekComputeTarget.CPU,
            reason = MediaTekSelectionReason.DEFAULT,
        )
        val error = runCatching {
            scheduler.record(selection, success, confirmedGraph, elapsedMs = 1L)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun npuAttributionNeedsConfirmedEvidenceForEveryStage() {
        val partial = scheduler()
        partial.npuAttempt(
            graphId = "diffusion_0",
            stage = MediaTekGraphStage.DIFFUSION,
            evidence = confirmedGraph,
        )
        partial.adopt(partial.attempts()[0].selection)
        assertEquals(
            MediaTekNpuAttribution.UNVERIFIED,
            partial.summary(MediaTekQualityReport.notCompared("none"), outputValid = true).npuAttribution,
        )

        val complete = scheduler()
        MediaTekGraphStage.entries.forEachIndexed { index, stage ->
            complete.npuAttempt("graph_$index", stage = stage, evidence = confirmedGraph)
            complete.adopt(complete.attempts()[index].selection)
        }
        assertEquals(
            MediaTekNpuAttribution.CONFIRMED,
            complete.summary(MediaTekQualityReport.notCompared("none"), outputValid = true).npuAttribution,
        )
    }
}
