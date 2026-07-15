package com.muyuchat.core.tuning

import com.muyuchat.core.deviceprofile.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveTuningPolicyTest {
    @Test
    fun quickCandidatesPrioritizeRecommendationBigCoresAndNeighbours() {
        assertEquals(
            listOf(4, 6, 3, 5),
            TuningCandidatePolicy.quickThreadCandidates(
                cpuCores = 8,
                estimatedBigCores = 6,
                recommendedThreads = 4
            )
        )
    }

    @Test
    fun quickCandidatesAreDistinctClampedAndCappedAtFour() {
        val candidates = TuningCandidatePolicy.quickThreadCandidates(
            cpuCores = 3,
            estimatedBigCores = 99,
            recommendedThreads = 99
        )

        assertEquals(listOf(3, 2, 1), candidates)
        assertEquals(candidates.distinct(), candidates)
        assertTrue(candidates.size <= TuningCandidatePolicy.QUICK_MAX_CANDIDATES)
        assertTrue(candidates.all { it in 1..3 })
    }

    @Test
    fun quickCandidatesUseBigCoresWhenRecommendationIsMissing() {
        assertEquals(
            listOf(6, 5, 7, 4),
            TuningCandidatePolicy.quickThreadCandidates(
                cpuCores = 8,
                estimatedBigCores = 6,
                recommendedThreads = null
            )
        )
    }

    @Test
    fun safetyFailureCannotWinCandidateSelection() {
        val unsafeFast = candidate("unsafe-fast", decodeTps = 100.0, gate = passingGate().copy(safetyPassed = false))
        val safeSlow = candidate("safe-slow", decodeTps = 4.0, gate = passingGate())

        val selected = CandidateSelectionPolicy.selectBestEligible(listOf(unsafeFast, safeSlow)) { it.score }

        assertEquals("safe-slow", selected?.id)
        assertFalse(unsafeFast.score.eligible)
        assertNull(unsafeFast.score.value)
    }

    @Test
    fun signatureMismatchCannotWinCandidateSelection() {
        val mismatch = candidate("mismatch", decodeTps = 200.0, gate = passingGate().copy(signaturesMatch = false))
        val verified = candidate("verified", decodeTps = 3.0, gate = passingGate())

        val selected = CandidateSelectionPolicy.selectBestEligible(listOf(mismatch, verified)) { it.score }

        assertEquals("verified", selected?.id)
        assertFalse(mismatch.score.eligible)
    }

    @Test
    fun allRejectedCandidatesProduceNoSelection() {
        val unsafe = candidate("unsafe", decodeTps = 100.0, gate = passingGate().copy(safetyPassed = false))
        val mismatch = candidate("mismatch", decodeTps = 200.0, gate = passingGate().copy(signaturesMatch = false))

        assertNull(CandidateSelectionPolicy.selectBestEligible(listOf(unsafe, mismatch)) { it.score })
    }

    @Test
    fun bootstrapLoadCanaryAcceptsOnlyTheCleanMinimalToken() {
        assertTrue(BootstrapLoadCanaryPolicy.matches(BootstrapLoadCanaryPolicy.expectedOutput))
        assertTrue(BootstrapLoadCanaryPolicy.matches(BootstrapLoadCanaryPolicy.expectedOutput + "\n"))
        assertTrue(BootstrapLoadCanaryPolicy.matches(BootstrapLoadCanaryPolicy.expectedOutput + "\r\n"))

        assertFalse(BootstrapLoadCanaryPolicy.matches(""))
        assertFalse(BootstrapLoadCanaryPolicy.matches(" ${BootstrapLoadCanaryPolicy.expectedOutput}"))
        assertFalse(BootstrapLoadCanaryPolicy.matches("${BootstrapLoadCanaryPolicy.expectedOutput} "))
        assertFalse(BootstrapLoadCanaryPolicy.matches("Answer: ${BootstrapLoadCanaryPolicy.expectedOutput}"))
        assertFalse(BootstrapLoadCanaryPolicy.matches("MCA_LOAD_OK_18"))
        assertFalse(BootstrapLoadCanaryPolicy.matches("MCA_LOAD_OK_\uFFFD17"))
    }

    @Test
    fun exactFiveLineCanaryAcceptsStandardLineEndingsOnly() {
        assertTrue(MinimumTextCanaryPolicy.matches(MinimumTextCanaryPolicy.expectedOutput))
        assertTrue(MinimumTextCanaryPolicy.matches(MinimumTextCanaryPolicy.expectedOutput + "\n"))
        assertTrue(MinimumTextCanaryPolicy.matches(MinimumTextCanaryPolicy.expectedLines.joinToString("\r\n")))
        assertTrue(MinimumTextCanaryPolicy.matches(MinimumTextCanaryPolicy.expectedCompactOutput))
    }

    @Test
    fun exactFiveLineCanaryRejectsPartialWrongOrDecoratedOutput() {
        assertFalse(MinimumTextCanaryPolicy.matches("FORMAT ZH ROLE CTX 蓝鲸 CLEAN"))
        assertFalse(MinimumTextCanaryPolicy.matches(MinimumTextCanaryPolicy.expectedOutput.replace("ZH=通过", "ZH=完成")))
        assertFalse(MinimumTextCanaryPolicy.matches("答案：\n${MinimumTextCanaryPolicy.expectedOutput}"))
        assertFalse(MinimumTextCanaryPolicy.matches("${MinimumTextCanaryPolicy.expectedOutput}\n解释：完成"))
        assertFalse(MinimumTextCanaryPolicy.matches(MinimumTextCanaryPolicy.expectedOutput.replace("CLEAN=OK", "CLEAN=OK ")))
        assertFalse(MinimumTextCanaryPolicy.matches(MinimumTextCanaryPolicy.expectedOutput + "\n\n"))
        assertFalse(MinimumTextCanaryPolicy.matches(MinimumTextCanaryPolicy.expectedOutput.replace("通过", "通\uFFFD过")))
        assertFalse(MinimumTextCanaryPolicy.matches("x${MinimumTextCanaryPolicy.expectedCompactOutput}"))
        assertFalse(MinimumTextCanaryPolicy.matches(MinimumTextCanaryPolicy.expectedCompactOutput + "x"))
        assertFalse(MinimumTextCanaryPolicy.matches(MinimumTextCanaryPolicy.expectedCompactOutput.replace("ROLE=USER", "ROLE=ASSISTANT")))
    }

    private fun candidate(id: String, decodeTps: Double, gate: CandidateHardGate): ScoredCandidate {
        val measurement = MeasurementEnvelope(
            start = point(),
            end = point(),
            samples = listOf(
                PerformanceSample(ttftMs = 100, decodeTps = decodeTps),
                PerformanceSample(ttftMs = 110, decodeTps = decodeTps)
            )
        )
        return ScoredCandidate(id, CandidateScorer.score(gate, measurement))
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
        availableMemoryBytes = 4L * GIB,
        pssBytes = 1L * GIB,
        rssBytes = 1L * GIB
    )

    private data class ScoredCandidate(val id: String, val score: CandidateScore)

    private companion object {
        const val GIB = 1024L * 1024L * 1024L
    }
}
