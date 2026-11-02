package com.muyuchat.mca

import com.muyuchat.core.mediatek.MediaTekAnimaCfgBranch
import com.muyuchat.core.mediatek.MediaTekAnimaDiagnosticSample
import com.muyuchat.core.mediatek.MediaTekAnimaDiagnosticStage
import com.muyuchat.core.mediatek.MediaTekAnimaDiagnosticStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTekAnimaDiagnosticLogTest {
    @Test
    fun traceSerializesNonFiniteCountsAndNeverIncludesPromptContent() {
        val sample = MediaTekAnimaDiagnosticSample(
            stage = MediaTekAnimaDiagnosticStage.DIT_BLOCK,
            stepIndex = 2,
            cfgBranch = MediaTekAnimaCfgBranch.NEGATIVE,
            blockIndex = 22,
            graphFile = "graphs/dit/block-22.tflite",
            inputTensorName = "hidden_in",
            outputTensorName = "hidden_out",
            inputStats = MediaTekAnimaDiagnosticStats(
                count = 4,
                finiteCount = 4,
                mean = 0.0,
                standardDeviation = 0.5,
                maxAbs = 1.0,
            ),
            outputStats = MediaTekAnimaDiagnosticStats(
                count = 4,
                finiteCount = 2,
                mean = 0.25,
                standardDeviation = null,
                maxAbs = null,
            ),
            stdDriftRatio = null,
        )

        val json = MediaTekAnimaDiagnosticLog.toJson(sample)
        val output = json.getJSONObject("output")

        assertEquals("tensor_trace", json.getString("type"))
        assertEquals("DIT_BLOCK", json.getString("stage"))
        assertEquals(22, json.getInt("blockIndex"))
        assertEquals(2, output.getInt("finiteCount"))
        assertTrue(output.isNull("standardDeviation"))
        assertTrue(json.isNull("stdDriftRatio"))
        assertFalse(json.has("prompt"))
        assertFalse(json.toString().contains("tokenIds"))
    }
}
