package com.muyuchat.mca

import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.mca.presentation.prefixCacheMissReasonLabel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The aggregate is the only place a user can see whether the persisted prefix cache is worth its
 * disk space. These tests pin the accounting rules, because a miscount is worse than no count:
 * every "attempt" must be a real cache attempt, and a provider that reports nothing must not be
 * scored as a miss.
 */
class PrefixCacheTelemetryTest {

    private fun stats(
        persistentHit: Boolean = false,
        persistentTokens: Int = 0,
        persistentReason: String? = null,
        cacheReuseHit: Boolean = false,
        cacheReusedTokens: Int = 0,
        cacheReuseHits: Long = 0L,
        cacheReuseMisses: Long = 0L,
        cacheReuseReason: String? = null
    ) = RuntimeStats(
        cacheReuseHit = cacheReuseHit,
        cacheReusedTokens = cacheReusedTokens,
        cacheReuseHits = cacheReuseHits,
        cacheReuseMisses = cacheReuseMisses,
        cacheReuseReason = cacheReuseReason,
        persistentPrefixCacheHit = persistentHit,
        persistentPrefixCacheTokens = persistentTokens,
        persistentPrefixCacheReason = persistentReason
    )

    @Test
    fun `a persisted prefix hit counts as a hit and adds its reused tokens`() {
        val state = PrefixCacheTelemetryState().record(
            stats(persistentHit = true, persistentTokens = 812, persistentReason = "state_loaded"),
            nowEpochMs = 1_000L
        )
        assertEquals(1L, state.hits)
        assertEquals(0L, state.misses)
        assertEquals(812L, state.reusedTokens)
        assertEquals(100, state.hitRatePercent ?: -1)
        assertEquals(1_000L, state.updatedAtEpochMs)
        assertTrue(state.missReasons.isEmpty())
    }

    @Test
    fun `a persisted prefix miss is counted with its reason`() {
        val state = PrefixCacheTelemetryState().record(
            stats(persistentReason = "state_token_mismatch")
        )
        assertEquals(0L, state.hits)
        assertEquals(1L, state.misses)
        assertEquals(0, state.hitRatePercent ?: -1)
        assertEquals(mapOf("state_token_mismatch" to 1), state.missReasons)
    }

    @Test
    fun `turns with no cache information are not scored at all`() {
        val untouched = PrefixCacheTelemetryState()
        listOf(
            stats(),
            stats(persistentReason = "not_requested"),
            stats(persistentReason = "not_attempted"),
            stats(persistentReason = "model_unloaded"),
            stats(cacheReuseReason = "unknown")
        ).forEach { evidence ->
            assertEquals(untouched, untouched.record(evidence))
        }
    }

    @Test
    fun `provider prompt cache reuse is counted separately from a provider miss`() {
        val hit = PrefixCacheTelemetryState().record(
            stats(cacheReuseHit = true, cacheReusedTokens = 1_024, cacheReuseReason = PROVIDER_PROMPT_CACHE)
        )
        assertEquals(1L, hit.hits)
        assertEquals(1_024L, hit.reusedTokens)

        val miss = PrefixCacheTelemetryState().record(
            stats(cacheReusedTokens = 0, cacheReuseReason = PROVIDER_PROMPT_CACHE)
        )
        assertEquals(1L, miss.misses)
        assertEquals(mapOf(PROVIDER_PROMPT_CACHE_MISS to 1), miss.missReasons)
    }

    @Test
    fun `in-memory kv counters are folded in when there is no persisted prefix attempt`() {
        val hit = PrefixCacheTelemetryState().record(
            stats(cacheReuseHit = true, cacheReusedTokens = 256, cacheReuseHits = 3)
        )
        assertEquals(1L, hit.hits)
        assertEquals(256L, hit.reusedTokens)

        val miss = PrefixCacheTelemetryState().record(
            stats(cacheReuseMisses = 2, cacheReuseReason = "longest_common_prefix")
        )
        assertEquals(1L, miss.misses)
        assertEquals(mapOf("longest_common_prefix" to 1), miss.missReasons)
    }

    @Test
    fun `the persisted prefix channel wins over the in-memory counters`() {
        val state = PrefixCacheTelemetryState().record(
            stats(
                persistentHit = true,
                persistentTokens = 64,
                persistentReason = "state_loaded",
                cacheReuseHits = 9,
                cacheReusedTokens = 9_999
            )
        )
        assertEquals(1L, state.hits)
        assertEquals(64L, state.reusedTokens)
    }

    @Test
    fun `hit rate is a whole percent of observed attempts`() {
        var state = PrefixCacheTelemetryState()
        repeat(3) {
            state = state.record(stats(persistentHit = true, persistentTokens = 1, persistentReason = "state_loaded"))
        }
        state = state.record(stats(persistentReason = "state_prefix_mismatch"))
        assertEquals(3L, state.hits)
        assertEquals(1L, state.misses)
        assertEquals(4L, state.attempts)
        assertEquals(75, state.hitRatePercent ?: -1)
    }

    @Test
    fun `every distinct miss reason is counted and none is dropped`() {
        var state = PrefixCacheTelemetryState()
        repeat(17) { index ->
            state = state.record(stats(persistentReason = "reason_$index"))
        }
        assertEquals(17, state.missReasons.size)
        assertEquals(1, state.missReasons["reason_0"])
        assertEquals(1, state.missReasons["reason_16"])

        // A second miss on the same reason must add to its count, not evict anything.
        state = state.record(stats(persistentReason = "reason_0"))
        assertEquals(2, state.missReasons["reason_0"])
        assertEquals(17, state.missReasons.size)
        assertEquals("reason_0" to 2, state.topMissReasons(limit = 1).single())
    }

    @Test
    fun `an overlong reason key is truncated instead of bloating the document`() {
        val long = "x".repeat(PrefixCacheTelemetryState.MAX_REASON_CHARS + 200)
        val state = PrefixCacheTelemetryState().record(stats(persistentReason = long))
        assertEquals(1, state.missReasons.size)
        val key = state.missReasons.keys.single()
        assertEquals(PrefixCacheTelemetryState.MAX_REASON_CHARS, key.length)
        assertEquals(1, state.missReasons[key])
    }

    @Test
    fun `json round trip preserves counters and reasons`() {
        val state = PrefixCacheTelemetryState(
            hits = 7L,
            misses = 2L,
            reusedTokens = 4_321L,
            missReasons = mapOf("state_token_mismatch" to 2),
            updatedAtEpochMs = 42L
        )
        assertEquals(state, PrefixCacheTelemetryState.fromJson(JSONObject(state.toJson().toString())))
    }

    @Test
    fun `an unknown or corrupt document is discarded instead of trusted`() {
        assertNull(PrefixCacheTelemetryState.fromJson(null))
        assertNull(
            PrefixCacheTelemetryState.fromJson(
                JSONObject().put("formatVersion", PrefixCacheTelemetryState.FORMAT_VERSION + 1)
            )
        )
        assertNull(
            PrefixCacheTelemetryState.fromJson(
                JSONObject()
                    .put("formatVersion", PrefixCacheTelemetryState.FORMAT_VERSION)
                    .put("hits", -1)
            )
        )
        assertNull(PrefixCacheTelemetryState.fromJson(JSONObject()))
    }

    @Test
    fun `unknown miss reason codes stay visible in the label instead of being hidden`() {
        assertEquals("前缀 token 不一致", prefixCacheMissReasonLabel("state_token_mismatch"))
        assertEquals("服务端未命中", prefixCacheMissReasonLabel(PROVIDER_PROMPT_CACHE_MISS))
        assertEquals("brand_new_reason", prefixCacheMissReasonLabel("brand_new_reason"))
    }

    @Test
    fun `a fresh state has no rate and no attempts`() {
        val state = PrefixCacheTelemetryState()
        assertNull(state.hitRatePercent)
        assertEquals(0L, state.attempts)
        assertTrue(state.topMissReasons().isEmpty())
        assertNotNull(state.toJson())
    }
}
