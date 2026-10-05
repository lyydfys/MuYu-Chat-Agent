package com.muyuchat.mca

import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.telemetry.RuntimeMetrics

// R3/S15: Runtime 读取缝——从 MainViewModel 移出的 engine 读取封装（行为逐字等价：
// runCatching + 固定默认值）。后续 R3 的 Runtime owner 将复用本缝，而不是各自读 engine。
internal fun McaInferenceService.nativeStatsJsonOrEmpty(): String =
    runCatching { nativeStatsJson() }.getOrDefault("{}")

internal fun McaInferenceService.recentEngineLogsOrEmpty(): List<RuntimeMetrics> =
    runCatching { recentLogs() }.getOrDefault(emptyList())
