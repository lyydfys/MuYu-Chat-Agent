package com.muyuchat.core.telemetry

import org.json.JSONObject

object RuntimeMetricsJson {
    fun toJson(metrics: RuntimeMetrics): JSONObject = JSONObject()
        .put("time", metrics.time)
        .put("model", metrics.model)
        .put("backend", metrics.backend)
        .put("soc", metrics.soc)
        .put("promptTokens", metrics.promptTokens)
        .put("genTokens", metrics.genTokens)
        .put("loadMs", metrics.loadMs)
        .put("ttftMs", metrics.ttftMs)
        .put("prefillMs", metrics.prefillMs)
        .put("decodeMs", metrics.decodeMs)
        .put("decodeTps", metrics.decodeTps)
        .put("e2eTps", metrics.e2eTps)
        .put("nativePssKb", metrics.nativePssKb)
        .put("processRssKb", metrics.processRssKb)
        .put("nativeHeapKb", metrics.nativeHeapKb)
        .put("nativeHeapSizeKb", metrics.nativeHeapSizeKb)
        .put("javaHeapKb", metrics.javaHeapKb)
        .put("availMemKb", metrics.availMemKb)
        .put("totalMemKb", metrics.totalMemKb)
        .put("advertisedMemKb", metrics.advertisedMemKb)
        .put("memoryThresholdKb", metrics.memoryThresholdKb)
        .put("isLowMemory", metrics.isLowMemory)
        .put("procMemAvailableKb", metrics.procMemAvailableKb)
        .put("procMemFreeKb", metrics.procMemFreeKb)
        .put("cachedKb", metrics.cachedKb)
        .put("reclaimableKb", metrics.reclaimableKb)
        .put("modelMemoryBudgetKb", metrics.modelMemoryBudgetKb)
        .put("params", JSONObject(metrics.params))
        .put("error", metrics.error)

    fun fromJson(json: JSONObject): RuntimeMetrics = RuntimeMetrics(
        time = json.optLong("time"),
        model = json.optString("model"),
        backend = json.optString("backend"),
        soc = json.optString("soc"),
        promptTokens = json.optInt("promptTokens"),
        genTokens = json.optInt("genTokens"),
        loadMs = json.optLong("loadMs"),
        ttftMs = json.optLong("ttftMs"),
        prefillMs = json.optLong("prefillMs"),
        decodeMs = json.optLong("decodeMs"),
        decodeTps = json.optDouble("decodeTps"),
        e2eTps = json.optDouble("e2eTps"),
        nativePssKb = json.optLong("nativePssKb"),
        processRssKb = json.optLong("processRssKb"),
        nativeHeapKb = json.optLong("nativeHeapKb"),
        nativeHeapSizeKb = json.optLong("nativeHeapSizeKb"),
        javaHeapKb = json.optLong("javaHeapKb"),
        availMemKb = json.optLong("availMemKb"),
        totalMemKb = json.optLong("totalMemKb"),
        advertisedMemKb = json.optLong("advertisedMemKb"),
        memoryThresholdKb = json.optLong("memoryThresholdKb"),
        isLowMemory = json.optBoolean("isLowMemory", false),
        procMemAvailableKb = json.optLong("procMemAvailableKb"),
        procMemFreeKb = json.optLong("procMemFreeKb"),
        cachedKb = json.optLong("cachedKb"),
        reclaimableKb = json.optLong("reclaimableKb"),
        modelMemoryBudgetKb = json.optLong("modelMemoryBudgetKb"),
        params = json.optJSONObject("params")?.toString() ?: "{}",
        error = json.optString("error").takeIf { it.isNotBlank() && it != "null" }
    )
}
