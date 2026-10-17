package com.muyuchat.api.local

import org.json.JSONObject


internal fun LocalApiRuntime.nativeStatsObject(): JSONObject? = runCatching {
        // Read the stats source from one captured set, like every other runtime call, so a stats
        // request cannot pair the new owner's provider with the previous owner's engine.
        val bindings = currentBindings()
        bindings.nativeStatsJsonProvider?.invoke() ?: bindings.engine?.nativeStatsJson()
    }.getOrNull()?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }

internal fun LocalApiRuntime.nativeMetricsObject(): JSONObject {
        val source = nativeStatsObject() ?: return JSONObject()
        val metrics = JSONObject()
        NATIVE_METRIC_FIELDS.forEach { key ->
            if (!source.has(key) || source.isNull(key)) return@forEach
            when (key) {
                "cacheReuseHit", "persistentPrefixCacheAttempted",
                "persistentPrefixCacheHit", "persistentPrefixCacheSaved" -> {
                    metrics.copyCacheBoolean(key, source.opt(key))
                    return@forEach
                }
                "cacheReusedTokens", "persistentPrefixCacheTokens" -> {
                    metrics.copyCacheCount(key, source.opt(key), Int.MAX_VALUE.toLong())
                    return@forEach
                }
                "cacheReuseHits", "cacheReuseMisses" -> {
                    metrics.copyCacheCount(key, source.opt(key))
                    return@forEach
                }
                "cacheReuseReason", "persistentPrefixCacheReason" -> {
                    metrics.copyCacheReason(key, source.opt(key))
                    return@forEach
                }
            }
            when (val value = source.opt(key)) {
                is Number, is Boolean -> if (key !in NATIVE_METRIC_STRING_FIELDS) metrics.put(key, value)
                is String -> if (
                    key in NATIVE_METRIC_STRING_FIELDS &&
                    value.length <= MAX_PUBLIC_METRIC_STRING_LENGTH &&
                    (key !in BACKEND_SELECTION_METRIC_FIELDS || PUBLIC_TOKEN_PATTERN.matches(value)) &&
                    !value.looksLikeAbsolutePath()
                ) metrics.put(key, value)
            }
        }
        // Do not let an allocation-only or stale backend label claim GPU
        // execution through the public Local API. Native and Kotlin use the
        // same three-part proof: allocation, successful decode, active flag.
        if (source.has("gpuOffloadActive") ||
            source.has("gpuOffloadAllocationObserved") ||
            source.has("gpuOffloadExecutionObserved")
        ) {
            val allocationObserved = source.optBoolean("gpuOffloadAllocationObserved", false)
            val executionObserved = source.optBoolean("gpuOffloadExecutionObserved", false)
            val verifiedGpuExecution = source.optBoolean("gpuOffloadActive", false) &&
                allocationObserved && executionObserved
            metrics.put("gpuOffloadActive", verifiedGpuExecution)
            metrics.put("gpuOffloadAllocationObserved", allocationObserved)
            metrics.put("gpuOffloadExecutionObserved", executionObserved)
            if (!verifiedGpuExecution && metrics.optString("backend") == "llama.cpp-gpu") {
                metrics.put("backend", "llama.cpp-cpu")
            }
        }
        // llama.cpp and MNN publish cache details as a nested diagnostic
        // object. Flatten only bounded scalar evidence for the Local API.
        source.optJSONObject("cacheReuse")?.let { cache ->
            metrics.copyCacheBoolean("cacheReuseHit", cache.opt("hit"))
            metrics.copyCacheCount("cacheReusedTokens", cache.opt("reusedTokens"), Int.MAX_VALUE.toLong())
            metrics.copyCacheCount("cacheReuseHits", cache.opt("hits"))
            metrics.copyCacheCount("cacheReuseMisses", cache.opt("misses"))
            metrics.copyCacheReason("cacheReuseReason", cache.opt("reason"))
        }
        source.optJSONObject("persistentPrefixCache")?.let { cache ->
            metrics.copyCacheBoolean("persistentPrefixCacheAttempted", cache.opt("attempted"))
            metrics.copyCacheBoolean("persistentPrefixCacheHit", cache.opt("hit"))
            metrics.copyCacheBoolean("persistentPrefixCacheSaved", cache.opt("saved"))
            metrics.copyCacheCount("persistentPrefixCacheTokens", cache.opt("tokens"), Int.MAX_VALUE.toLong())
            metrics.copyCacheReason("persistentPrefixCacheReason", cache.opt("reason"))
        }
        source.optJSONObject("parameterApplication")?.let { application ->
            val encoded = application.toString()
            if (encoded.length <= 32_768) {
                metrics.put("parameterApplication", JSONObject(encoded))
            }
        }
        source.optJSONObject("componentBackends")?.let { components ->
            val published = JSONObject()
            for (component in listOf("llm", "vision", "audio")) {
                val evidence = components.optJSONObject(component) ?: continue
                val values = JSONObject()
                for (field in listOf("requested", "selected", "selectionSource", "actual")) {
                    if (!evidence.has(field)) continue
                    when (val value = evidence.opt(field)) {
                        JSONObject.NULL -> values.put(field, JSONObject.NULL)
                        is String -> if (value.length <= MAX_PUBLIC_METRIC_STRING_LENGTH &&
                            PUBLIC_TOKEN_PATTERN.matches(value)
                        ) values.put(field, value)
                    }
                }
                if (values.length() > 0) published.put(component, values)
            }
            if (published.length() > 0) metrics.put("componentBackends", published)
        }
        if (!metrics.has("cacheReuseReason")) metrics.put("cacheReuseReason", "unknown")
        if (!metrics.has("persistentPrefixCacheReason")) metrics.put("persistentPrefixCacheReason", "unknown")
        return metrics
    }

    private fun JSONObject.copyCacheBoolean(key: String, value: Any?) {
        if (value is Boolean) put(key, value)
    }

    private fun JSONObject.copyCacheCount(key: String, value: Any?, maximum: Long = Long.MAX_VALUE) {
        val count = (value as? Number)?.toString()?.toLongOrNull() ?: return
        if (count in 0L..maximum) put(key, count)
    }

    private fun JSONObject.copyCacheReason(key: String, value: Any?) {
        val reason = value as? String ?: return
        if (reason.isNotBlank() && reason.length <= MAX_PUBLIC_METRIC_STRING_LENGTH &&
            PUBLIC_TOKEN_PATTERN.matches(reason)
        ) put(key, reason)
    }

internal fun LocalApiRuntime.coordinatorSignaturesObject(): JSONObject {
        val profile = runCatching { profileJson().toJsonObjectOrNull() }.getOrNull()
        val source = profile?.optJSONObject("signatures") ?: profile
        fun signature(vararg names: String): Any {
            val value = source?.firstValue(*names) as? String ?: return JSONObject.NULL
            return value.takeIf(SIGNATURE_TOKEN_PATTERN::matches) ?: JSONObject.NULL
        }
        return JSONObject()
            .put("desired", signature("desired", "desiredSignature"))
            .put("resolved", signature("resolved", "resolvedLoad", "resolvedLoadSignature"))
            .put("active", signature("active", "activeLoaded", "activeLoadedSignature"))
            .put("committed", signature("committed", "committedExecution", "committedExecutionSignature"))
            .put("override", signature("override", "runtimeOverride", "runtimeOverrideSignature"))
            .put("effective", signature("effective", "effectiveExecution", "effectiveExecutionSignature"))
    }
    private val BACKEND_SELECTION_METRIC_FIELDS = setOf("requestedBackend", "selectedBackend", "actualBackend")
    private val NATIVE_METRIC_STRING_FIELDS = BACKEND_SELECTION_METRIC_FIELDS + setOf(
        "backend",
        "generationStopReason",
        "mnnModelType",
        "splitMode",
        "cacheTypeK",
        "cacheTypeV",
        "cacheReuseReason",
        "persistentPrefixCacheReason",
        "flashAttn",
        "specType",
        "gpuAutoFallbackReason"
    )
    private val NATIVE_METRIC_FIELDS = NATIVE_METRIC_STRING_FIELDS + setOf(
        "loaded",
        "runnerReady",
        "modelFileSizeBytes",
        "mmapFallbackAllowed",
        "mmapPrefetchEnabled",
        "mmap",
        "mlock",
        "loadMs",
        "loadGeneration",
        "loadedAtMs",
        "requestCountSinceLoad",
        "lastRequestReset",
        "multimodalSystemPromptSuppressed",
        "multimodalHistorySuppressed",
        "promptTokens",
        "prefillTokens",
        "completionTokens",
        "generationSequence",
        "generationActive",
        "stopRequested",
        "contextShifts",
        "ttftMs",
        "prefillMs",
        "prefillTps",
        "effectivePromptTps",
        "decodeMs",
        "decodeTps",
        "generatedSteps",
        "streamedBytes",
        "syncStepping",
        "nThreads",
        "nThreadsBatch",
        "nBatch",
        "nUbatch",
        "nCtx",
        "maxAllTokens",
        "maxNewTokens",
        "nGpuLayers",
        "gpuOffloadActive",
        "gpuOffloadAllocationObserved",
        "gpuOffloadExecutionObserved",
        "gpuOffloadBytes",
        "gpuOffloadModelBytes",
        "gpuOffloadContextBytes",
        "gpuOffloadComputeBytes",
        "gpuOffloadLayers",
        "gpuOffloadLayersKnown",
        "gpuAutoFallbackApplied",
        "mainGpu",
        "nCpuMoe",
        "nParallel",
        "cacheReuseThreshold",
        "cacheReuseHit",
        "cacheReusedTokens",
        "cacheReuseHits",
        "cacheReuseMisses",
        "cacheReuseReason",
        "persistentPrefixCacheAttempted",
        "persistentPrefixCacheHit",
        "persistentPrefixCacheSaved",
        "persistentPrefixCacheTokens",
        "persistentPrefixCacheReason",
        "specDraftNMax",
        "temperature",
        "topK",
        "topP",
        "minP",
        "repeatPenalty",
        "presencePenalty",
        "frequencyPenalty",
        "repeatLastN",
        "useJinja",
        "enableThinking",
        "visionReady",
        "backendReady",
        "backendDeviceCount",
        "mnnDebugTraceEnabled",
        "mnnDebugTraceTruncated",
        "mnnDebugPromptTruncated"
    )
    private const val MAX_PUBLIC_METRIC_STRING_LENGTH = 128
    private val SIGNATURE_TOKEN_PATTERN = PUBLIC_TOKEN_PATTERN
