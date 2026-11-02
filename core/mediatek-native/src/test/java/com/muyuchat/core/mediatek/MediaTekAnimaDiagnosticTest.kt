package com.muyuchat.core.mediatek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTekAnimaDiagnosticTest {
    @Test
    fun diagnosticSampleReportsPerBranchBlockStatsAndFiniteSubsetDrift() {
        val input = MediaTekTensorData.Float32(
            name = "hidden_in",
            shape = listOf(1, 4),
            values = floatArrayOf(1f, -1f, Float.NaN, Float.POSITIVE_INFINITY),
        )
        val output = MediaTekTensorData.Float32(
            name = "hidden_out",
            shape = listOf(1, 4),
            values = floatArrayOf(2f, -2f, Float.NaN, Float.POSITIVE_INFINITY),
        )
        val sample = createMediaTekAnimaDiagnosticSample(
            stage = MediaTekAnimaDiagnosticStage.DIT_BLOCK,
            stepIndex = 3,
            cfgBranch = MediaTekAnimaCfgBranch.NEGATIVE,
            blockIndex = 27,
            graphFile = "graphs/dit_27.tflite",
            inputTensorName = input.name,
            outputTensorName = output.name,
            inputStats = input.diagnosticStats(),
            outputStats = output.diagnosticStats(),
        )

        assertEquals(3, sample.stepIndex)
        assertEquals(MediaTekAnimaCfgBranch.NEGATIVE, sample.cfgBranch)
        assertEquals(27, sample.blockIndex)
        assertEquals(4, sample.inputStats.count)
        assertEquals(2, sample.inputStats.finiteCount)
        assertEquals(0.0, requireNotNull(sample.inputStats.mean), 0.0)
        assertEquals(1.0, requireNotNull(sample.inputStats.standardDeviation), 0.0)
        assertEquals(1.0, requireNotNull(sample.inputStats.maxAbs), 0.0)
        assertEquals(2, sample.outputStats.finiteCount)
        assertEquals(2.0, requireNotNull(sample.outputStats.standardDeviation), 0.0)
        assertEquals(2.0, requireNotNull(sample.stdDriftRatio), 0.0)
    }

    @Test
    fun diagnosticDriftIsNullWhenNoFiniteStandardDeviationCanBeMeasured() {
        val input = MediaTekAnimaDiagnosticStats(2, 0, null, null, null)
        val output = MediaTekAnimaDiagnosticStats(2, 0, null, null, null)
        val sample = createMediaTekAnimaDiagnosticSample(
            stage = MediaTekAnimaDiagnosticStage.DIT_BLOCK,
            stepIndex = 0,
            cfgBranch = MediaTekAnimaCfgBranch.POSITIVE,
            blockIndex = 0,
            graphFile = "graphs/dit_0.tflite",
            inputTensorName = "hidden_in",
            outputTensorName = "hidden_out",
            inputStats = input,
            outputStats = output,
        )

        assertNull(sample.stdDriftRatio)
    }

    @Test
    fun graphRunnerRejectsNonFiniteFloatOutputAndAttachesStats() {
        val output = MediaTekTensorData.Float32(
            name = "hidden_out",
            shape = listOf(1, 4),
            values = floatArrayOf(1f, Float.NaN, Float.POSITIVE_INFINITY, -3f),
        )
        val error = runCatching {
            validateMediaTekGraphOutput("graphs/dit_27.tflite", output)
        }.exceptionOrNull() as? MediaTekGraphExecutionException

        assertNotNull(error)
        assertEquals(MediaTekGraphFailureStage.OUTPUTS, error?.stage)
        assertTrue(error?.message.orEmpty().contains("non-finite"))
        val diagnostic = requireNotNull(error?.outputDiagnostic)
        assertEquals("graphs/dit_27.tflite", diagnostic.graphFile)
        assertEquals("hidden_out", diagnostic.tensorName)
        assertEquals(4, diagnostic.stats.count)
        assertEquals(2, diagnostic.stats.finiteCount)
        assertEquals(-1.0, requireNotNull(diagnostic.stats.mean), 0.0)
        assertEquals(2.0, requireNotNull(diagnostic.stats.standardDeviation), 0.0)
        assertEquals(3.0, requireNotNull(diagnostic.stats.maxAbs), 0.0)
    }

    @Test
    fun graphRunnerStillAcceptsFiniteFloatOutput() {
        val output = MediaTekTensorData.Float32(
            name = "hidden_out",
            shape = listOf(1, 2),
            values = floatArrayOf(1f, -1f),
        )

        validateMediaTekGraphOutput("graphs/dit_0.tflite", output)
    }
}
