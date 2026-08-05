package com.muyuchat.core.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkHistoryRecordTest {
    @Test
    fun roundTripKeepsPrefillAndCacheReuseMeasurements() {
        val record = BenchmarkHistoryRecord(
            modelId = "model-id",
            modelName = "Model",
            modelPath = "/models/model.gguf",
            deviceSummary = "device",
            paramsJson = "{}",
            result = BenchmarkResult(
                promptTokens = 128,
                prefillMs = 250,
                prefillTps = 512.0,
                cacheReuseHit = true,
                cacheReusedTokens = 96,
                cacheReuseReason = "longest_common_prefix"
            )
        )

        val decoded = BenchmarkHistoryRecord.fromJson(record.toJson().toString())

        assertEquals(250L, decoded.result.prefillMs)
        assertEquals(512.0, decoded.result.prefillTps, 0.0)
        assertTrue(decoded.result.cacheReuseHit)
        assertEquals(96, decoded.result.cacheReusedTokens)
        assertEquals("longest_common_prefix", decoded.result.cacheReuseReason)
    }

    @Test
    fun legacyRecordUsesNeutralDefaultsForNewMeasurements() {
        val decoded = BenchmarkHistoryRecord.fromJson(
            "{\"time\":1,\"deviceSummary\":\"device\",\"params\":{},\"result\":{\"decodeTps\":1.0}}"
        )

        assertEquals(0L, decoded.result.prefillMs)
        assertEquals(0.0, decoded.result.prefillTps, 0.0)
        assertFalse(decoded.result.cacheReuseHit)
        assertEquals(0, decoded.result.cacheReusedTokens)
        assertEquals(null, decoded.result.cacheReuseReason)
    }
}
