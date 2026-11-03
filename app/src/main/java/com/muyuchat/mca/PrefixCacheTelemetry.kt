package com.muyuchat.mca

import com.muyuchat.core.engine.RuntimeStats
import org.json.JSONObject

/**
 * Aggregate reuse evidence for the local prefix cache and for provider-side prompt caching.
 *
 * Why this exists: the engine already reports, per completed turn, whether a persisted prefix state
 * was used (`persistentPrefixCacheHit` / `persistentPrefixCacheTokens`) or whether a provider
 * reported cached prompt tokens (`cacheReuseTokens`). Those numbers were only ever shown for the
 * single message that produced them, so nobody could answer "is this cache worth its disk space"
 * without reading one reply at a time. This is the smallest honest aggregate: successful reuses,
 * failed attempts with their reason, and the tokens actually reused.
 *
 * It deliberately counts only turns that produced an observation. A cloud turn with no cache
 * information is not a miss, and a model without cache support never drags the rate down.
 */
data class PrefixCacheTelemetryState(
    val hits: Long = 0L,
    val misses: Long = 0L,
    val reusedTokens: Long = 0L,
    /** Miss reason -> count. Reason keys are bounded by length, never by dropping an entry. */
    val missReasons: Map<String, Int> = emptyMap(),
    val updatedAtEpochMs: Long = 0L
) {

    val attempts: Long get() = hits + misses

    /** Whole-percent hit rate, or null when nothing has been observed yet. */
    val hitRatePercent: Int? get() = if (attempts <= 0L) null else ((hits * 100L) / attempts).toInt()

    /** Biggest miss causes first; ties keep a stable order so the UI does not jitter. */
    fun topMissReasons(limit: Int = 3): List<Pair<String, Int>> =
        missReasons.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key to it.value }

    fun toJson(): JSONObject = JSONObject()
        .put("formatVersion", FORMAT_VERSION)
        .put("hits", hits)
        .put("misses", misses)
        .put("reusedTokens", reusedTokens)
        .put("updatedAtEpochMs", updatedAtEpochMs)
        .put(
            "missReasons",
            JSONObject().also { reasons ->
                missReasons.forEach { (reason, count) -> reasons.put(reason, count) }
            }
        )

    companion object {
        const val FORMAT_VERSION = 1

        /**
         * Reason keys are engine-defined codes. Only their length is bounded, so a pathological
         * provider string cannot bloat the document; entries themselves are never silently dropped
         * (dropping them used to reset a reason's count to 1 when it re-appeared).
         */
        const val MAX_REASON_CHARS = 48

        /**
         * Reasons that mean "no observation", not "cache failed". Counting these as misses would
         * make every turn on a cloud model without cache reporting look like a broken cache.
         */
        private val NON_ATTEMPTS = setOf(
            "not_requested",
            "not_attempted",
            "model_unloaded",
            "unknown"
        )

        fun fromJson(json: JSONObject?): PrefixCacheTelemetryState? {
            if (json == null) return null
            if (json.optInt("formatVersion", -1) != FORMAT_VERSION) return null
            val hits = json.optLong("hits", -1L)
            val misses = json.optLong("misses", -1L)
            if (hits < 0L || misses < 0L) return null
            val reasons = mutableMapOf<String, Int>()
            json.optJSONObject("missReasons")?.let { stored ->
                stored.keys().forEach { key ->
                    val count = stored.optInt(key, 0)
                    if (count > 0) {
                        val normalized = key.normalizedMissReason()
                        reasons[normalized] = (reasons[normalized] ?: 0) + count
                    }
                }
            }
            return PrefixCacheTelemetryState(
                hits = hits,
                misses = misses,
                reusedTokens = json.optLong("reusedTokens", 0L).coerceAtLeast(0L),
                missReasons = reasons.toMap(),
                updatedAtEpochMs = json.optLong("updatedAtEpochMs", 0L)
            )
        }
    }
}

/**
 * Folds one finished turn into the aggregate. Pure so the accounting is unit-testable without a
 * store, a context or an engine.
 */
internal fun PrefixCacheTelemetryState.record(
    stats: RuntimeStats,
    nowEpochMs: Long = System.currentTimeMillis()
): PrefixCacheTelemetryState {
    val observation = PrefixCacheObservation.from(stats) ?: return this
    val hits = hits + if (observation.hit) 1L else 0L
    val misses = misses + if (observation.hit) 0L else 1L
    val reused = reusedTokens + observation.reusedTokens
    val reasons = if (observation.hit || observation.reason.isNullOrBlank()) {
        missReasons
    } else {
        val key = observation.reason.normalizedMissReason()
        missReasons + (key to (missReasons[key] ?: 0) + 1)
    }
    return copy(
        hits = hits,
        misses = misses,
        reusedTokens = reused,
        missReasons = reasons,
        updatedAtEpochMs = nowEpochMs
    )
}

/** One turn's cache evidence, normalised across the local and provider-side channels. */
internal data class PrefixCacheObservation(
    val hit: Boolean,
    val reusedTokens: Long,
    val reason: String?
) {
    companion object {
        private val NON_ATTEMPTS = setOf("not_requested", "not_attempted", "model_unloaded", "unknown")

        fun from(stats: RuntimeStats): PrefixCacheObservation? {
            val persistentReason = stats.persistentPrefixCacheReason
                ?.takeUnless { it in NON_ATTEMPTS }
            if (persistentReason != null) {
                return PrefixCacheObservation(
                    hit = stats.persistentPrefixCacheHit,
                    reusedTokens = if (stats.persistentPrefixCacheHit) {
                        stats.persistentPrefixCacheTokens.toLong().coerceAtLeast(0L)
                    } else {
                        0L
                    },
                    reason = if (stats.persistentPrefixCacheHit) null else persistentReason
                )
            }
            // Provider-side prompt cache (OpenAI Responses / Anthropic cache reads). The provider
            // reports cache hits, so this is the only evidence server-side caching exists at all.
            if (stats.cacheReuseReason == PROVIDER_PROMPT_CACHE) {
                return PrefixCacheObservation(
                    hit = stats.cacheReuseHit || stats.cacheReusedTokens > 0,
                    reusedTokens = stats.cacheReusedTokens.toLong().coerceAtLeast(0L),
                    reason = if (stats.cacheReuseHit || stats.cacheReusedTokens > 0) {
                        null
                    } else {
                        PROVIDER_PROMPT_CACHE_MISS
                    }
                )
            }
            if (stats.cacheReuseHits + stats.cacheReuseMisses > 0L) {
                val hit = stats.cacheReuseHits > 0L || stats.cacheReuseHit
                return PrefixCacheObservation(
                    hit = hit,
                    reusedTokens = stats.cacheReusedTokens.toLong().coerceAtLeast(0L),
                    reason = if (hit) {
                        null
                    } else {
                        stats.cacheReuseReason?.takeUnless { it in NON_ATTEMPTS } ?: "kv_miss"
                    }
                )
            }
            return null
        }
    }
}

internal const val PROVIDER_PROMPT_CACHE = "provider_prompt_cache"
internal const val PROVIDER_PROMPT_CACHE_MISS = "provider_prompt_cache_miss"

/**
 * Keeps a reason key storable and comparable: trimmed, length-bounded and never blank. The cap is a
 * guard against a provider-derived string, not a filter on which causes may be counted.
 */
internal fun String.normalizedMissReason(): String {
    val trimmed = trim()
    if (trimmed.isEmpty()) return "unknown"
    return if (trimmed.length <= PrefixCacheTelemetryState.MAX_REASON_CHARS) {
        trimmed
    } else {
        trimmed.take(PrefixCacheTelemetryState.MAX_REASON_CHARS)
    }
}
