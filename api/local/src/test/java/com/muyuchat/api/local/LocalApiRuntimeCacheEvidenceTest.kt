package com.muyuchat.api.local

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalApiRuntimeCacheEvidenceTest {
    @Test
    fun absentNullAndEmptyCacheObjectsDoNotClaimMeasuredMisses() {
        for (raw in listOf(
            "{}",
            """{"cacheReuse":null,"persistentPrefixCache":null}""",
            """{"cacheReuse":{},"persistentPrefixCache":{}}""",
            """{"cacheReuse":{"hit":null,"reusedTokens":null,"hits":null,"misses":null},"persistentPrefixCache":{"attempted":null,"hit":null,"saved":null,"tokens":null}}"""
        )) {
            val metrics = metrics(raw)
            measurementFields.forEach { assertFalse("$raw must not invent $it", metrics.has(it)) }
            assertEquals("unknown", metrics.getString("cacheReuseReason"))
            assertEquals("unknown", metrics.getString("persistentPrefixCacheReason"))
        }
    }

    @Test
    fun partialCacheEvidencePublishesOnlyTheReportedFields() {
        val metrics = metrics("""{"cacheReuse":{"reusedTokens":23},"persistentPrefixCache":{"attempted":true}}""")
        assertEquals(23, metrics.getInt("cacheReusedTokens"))
        assertTrue(metrics.getBoolean("persistentPrefixCacheAttempted"))
        (measurementFields - setOf("cacheReusedTokens", "persistentPrefixCacheAttempted"))
            .forEach { assertFalse("Unreported $it must stay unknown", metrics.has(it)) }
    }

    @Test
    fun realFalseZeroAndNonzeroMeasurementsRemainAvailable() {
        val metrics = metrics("""{"cacheReuse":{"hit":false,"reusedTokens":0,"hits":12,"misses":0,"reason":"prefix_mismatch"},"persistentPrefixCache":{"attempted":true,"hit":false,"saved":false,"tokens":0,"reason":"not_found"}}""")
        measurementFields.forEach { assertTrue("Reported $it must remain available", metrics.has(it)) }
        assertFalse(metrics.getBoolean("cacheReuseHit"))
        assertEquals(0, metrics.getInt("cacheReusedTokens"))
        assertEquals(12L, metrics.getLong("cacheReuseHits"))
        assertEquals(0L, metrics.getLong("cacheReuseMisses"))
        assertTrue(metrics.getBoolean("persistentPrefixCacheAttempted"))
        assertFalse(metrics.getBoolean("persistentPrefixCacheHit"))
        assertFalse(metrics.getBoolean("persistentPrefixCacheSaved"))
        assertEquals(0, metrics.getInt("persistentPrefixCacheTokens"))
        assertEquals("prefix_mismatch", metrics.getString("cacheReuseReason"))
        assertEquals("not_found", metrics.getString("persistentPrefixCacheReason"))
    }

    @Test
    fun wrongTypesAndInvalidCountsCannotBecomeMeasurements() {
        for (raw in listOf(
            """{"cacheReuse":{"hit":"false","reusedTokens":"0","hits":-1,"misses":1.5,"reason":7},"persistentPrefixCache":{"attempted":0,"hit":"true","saved":null,"tokens":2147483648,"reason":"/private/cache"}}""",
            """{"cacheReuseHit":0,"cacheReusedTokens":true,"cacheReuseHits":"0","cacheReuseMisses":-1,"cacheReuseReason":7,"persistentPrefixCacheAttempted":"false","persistentPrefixCacheHit":1,"persistentPrefixCacheSaved":{},"persistentPrefixCacheTokens":1.5,"persistentPrefixCacheReason":"/private/cache"}"""
        )) {
            val metrics = metrics(raw)
            measurementFields.forEach { assertFalse("$raw must not coerce $it", metrics.has(it)) }
            assertEquals("unknown", metrics.getString("cacheReuseReason"))
            assertEquals("unknown", metrics.getString("persistentPrefixCacheReason"))
        }
    }

    private fun metrics(raw: String): JSONObject {
        val previousProvider = LocalApiRuntime.nativeStatsJsonProvider
        return try {
            LocalApiRuntime.nativeStatsJsonProvider = { raw }
            JSONObject(LocalApiRuntime.metricsJson())
        } finally {
            LocalApiRuntime.nativeStatsJsonProvider = previousProvider
        }
    }

    private val measurementFields = setOf(
        "cacheReuseHit", "cacheReusedTokens", "cacheReuseHits", "cacheReuseMisses",
        "persistentPrefixCacheAttempted", "persistentPrefixCacheHit", "persistentPrefixCacheSaved",
        "persistentPrefixCacheTokens"
    )
}
