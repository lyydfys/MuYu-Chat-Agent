package com.muyuchat.feature.chat

import org.json.JSONObject

/** Normalizes a user/native backend label to the accelerator family it names. */
internal fun normalizeChatAcceleratorBackend(raw: String?): String? {
    val value = raw?.trim()?.lowercase().orEmpty()
    if (value.isBlank() || value == "unknown" || value == "unavailable" || value == "none") return null
    return when {
        value == "cpu" || value.contains("cpu") -> "cpu"
        value == "gpu" || value.contains("gpu") || value.contains("opencl") ||
            value.contains("adreno") -> "gpu"
        value == "npu" || value.contains("npu") || value.contains("htp") ||
            value.contains("qnn") || value.contains("qairt") || value.contains("qualcomm") -> "npu"
        else -> value
    }
}

/**
 * Pure projection for one accelerator row.  `supported`, `selectedBackend`,
 * and `actualBackend` are intentionally independent so a configured delegate
 * cannot be presented as an executing delegate without native proof.
 */
internal fun chatAcceleratorStatusItem(
    key: String,
    label: String,
    accelerator: String,
    cloud: Boolean,
    supported: Boolean?,
    selectedBackend: String?,
    actualBackend: String?,
    executionObserved: Boolean,
    unavailableValue: String = "不可用",
    unknownValue: String = "待检测",
    idleValue: String = "未使用",
    extraDetail: String? = null
): ChatDeviceStatusItem {
    val selected = normalizeChatAcceleratorBackend(selectedBackend)
    val actual = normalizeChatAcceleratorBackend(actualBackend)
    val running = !cloud && executionObserved && actual == accelerator
    val selectedHere = !cloud && selected == accelerator
    val value = when {
        cloud -> idleValue
        running -> "运行中"
        supported == false -> unavailableValue
        selectedHere -> "已选择"
        supported == true -> "可用"
        else -> unknownValue
    }
    val supportLabel = when (supported) {
        true -> "支持：可用"
        false -> "支持：不可用"
        null -> "支持：未探测"
    }
    val selectedLabel = when {
        cloud -> "已选：云端"
        selectedHere -> "已选：${selectedBackend?.trim().orEmpty().ifBlank { accelerator }}"
        selected != null -> "已选：${selectedBackend?.trim().orEmpty()}"
        else -> "已选：未指定"
    }
    val actualLabel = when {
        cloud -> "实际：未使用本机"
        running -> "实际：${actualBackend?.trim().orEmpty().ifBlank { accelerator }}（原生证据）"
        actual != null -> "实际：${actualBackend?.trim().orEmpty()}（未确认执行）"
        else -> "实际：未确认"
    }
    // Proof first.  This line is the narrowest thing in the panel, so whichever fragment
    // survives must be the one that says whether execution was actually proven.
    val details = buildList {
        add(actualLabel)
        add(supportLabel)
        add(selectedLabel)
        extraDetail?.takeIf(String::isNotBlank)?.let(::add)
    }.joinToString(" · ")
    val known = cloud || supported != null || selected != null || actual != null || executionObserved
    return ChatDeviceStatusItem(
        key = key,
        label = label,
        value = value,
        detail = details,
        active = running,
        known = known,
        tone = when {
            running -> ChatDeviceStatusTone.ACTIVE
            !known -> ChatDeviceStatusTone.UNKNOWN
            else -> ChatDeviceStatusTone.NEUTRAL
        }
    )
}

/**
 * Converts the native runtime evidence already present in [ChatUiState] into
 * concise status rows. Unknown GPU/NPU evidence stays visibly unknown; it is
 * never presented as a device rejection.
 */
internal fun ChatUiState.deviceStatusItems(
    memorySample: ChatDeviceMemorySample? = null,
    temperatureSample: ChatDeviceTemperatureSample? = null
): List<ChatDeviceStatusItem> {
    val backend = stats.backend.trim().lowercase()
    val selectedLocalModel = localModels.firstOrNull { it.id == selectedModelId }
    val selectedBackend = if (selectedModelIsCloud) {
        null
    } else {
        selectedLocalModel?.selectedBackendId
            ?: runCatching { JSONObject(generationParams.advancedJson.ifBlank { "{}" }) }
                .getOrNull()
                ?.let { root ->
                    root.optString("backend")
                        .ifBlank { root.optString("backend_type") }
                        .takeIf(String::isNotBlank)
                }
    }
    val nativeActualBackend = stats.actualBackend
    val gpuExecutionObserved = stats.hasVerifiedGpuExecution ||
        (stats.backendExecutionObserved &&
            normalizeChatAcceleratorBackend(nativeActualBackend) == "gpu")
    val npuExecutionObserved = stats.npuExecutionObserved ||
        (stats.backendExecutionObserved &&
            normalizeChatAcceleratorBackend(nativeActualBackend) == "npu")
    val gpuActualBackend = if (gpuExecutionObserved) {
        nativeActualBackend?.takeIf(String::isNotBlank) ?: "gpu"
    } else {
        nativeActualBackend
    }
    val npuActualBackend = if (npuExecutionObserved) {
        nativeActualBackend?.takeIf(String::isNotBlank) ?: "npu"
    } else {
        nativeActualBackend
    }
    val gpuExtraDetail = when {
        stats.hasVerifiedGpuExecution && stats.gpuOffloadLayersKnown ->
            "${stats.gpuOffloadLayers} 层已验证"
        stats.hasVerifiedGpuExecution -> "已完成原生执行验证"
        stats.backendExecutionEvidence?.isNotBlank() == true -> stats.backendExecutionEvidence
        else -> null
    }
    val gpuItem = chatAcceleratorStatusItem(
        key = "gpu",
        label = "GPU",
        accelerator = "gpu",
        cloud = selectedModelIsCloud,
        supported = stats.gpuOffloadSupported,
        selectedBackend = selectedBackend,
        actualBackend = gpuActualBackend,
        executionObserved = gpuExecutionObserved,
        extraDetail = gpuExtraDetail
    )
    val npuItem = chatAcceleratorStatusItem(
        key = "npu",
        label = "NPU",
        accelerator = "npu",
        cloud = selectedModelIsCloud,
        supported = stats.npuSupported,
        selectedBackend = selectedBackend,
        actualBackend = npuActualBackend,
        executionObserved = npuExecutionObserved,
        // Without an explicit capability probe or execution proof, NPU is
        // idle/unknown.  A configured LiteRT NPU delegate is not execution.
        unknownValue = "未使用",
        extraDetail = stats.backendExecutionEvidence
    )
    val freshMemory = memorySample?.takeIf { it.totalKb > 0L }
    val memoryTotal = freshMemory?.totalKb ?: stats.totalMemKb
    val memoryAvailable = (freshMemory?.availableKb ?: stats.availMemKb)
        .coerceIn(0L, memoryTotal.coerceAtLeast(0L))
    val memoryUsed = (memoryTotal - memoryAvailable).coerceAtLeast(0L)
    val memoryValue = if (memoryTotal > 0L) {
        "${formatMemoryMb(memoryUsed)} / ${formatMemoryMb(memoryTotal)}"
    } else {
        "待检测"
    }
    val memoryDetail = if (memoryTotal > 0L) {
        "可用 ${formatMemoryMb(memoryAvailable)}"
    } else {
        "系统内存数据尚未返回"
    }
    return listOf(
        ChatDeviceStatusItem(
            key = "cpu",
            label = "CPU",
            value = if (!selectedModelIsCloud && stats.loaded && backend.contains("cpu")) "运行中" else "在线",
            // CPU previously carried an unconditional "系统线程与回退执行" blurb, so the one
            // backend that is always available was the only one with no proof statement.
            detail = when {
                selectedModelIsCloud -> "实际：未使用本机 · 支持：可用"
                !selectedModelIsCloud && stats.loaded && backend.contains("cpu") ->
                    "实际：本机 CPU 执行（原生证据） · 支持：可用"
                else -> "支持：可用 · 实际：未确认"
            },
            active = !selectedModelIsCloud && stats.loaded && backend.contains("cpu"),
            tone = if (!selectedModelIsCloud && stats.loaded && backend.contains("cpu")) {
                ChatDeviceStatusTone.ACTIVE
            } else {
                ChatDeviceStatusTone.NEUTRAL
            }
        ),
        gpuItem,
        npuItem,
        ChatDeviceStatusItem(
            key = "memory",
            label = "内存",
            value = memoryValue,
            detail = memoryDetail,
            active = freshMemory?.lowMemory ?: stats.isLowMemory,
            known = memoryTotal > 0L,
            tone = when {
                memoryTotal <= 0L -> ChatDeviceStatusTone.UNKNOWN
                freshMemory?.lowMemory ?: stats.isLowMemory -> ChatDeviceStatusTone.CRITICAL
                else -> ChatDeviceStatusTone.NEUTRAL
            }
        ),
        ChatDeviceStatusItem(
            key = "temperature",
            label = "温度",
            value = temperatureSample?.let { "%.1f°C".format(java.util.Locale.US, it.celsius) } ?: "不可用",
            detail = temperatureSample?.let {
                "传感器：${it.source} · ${chatTemperatureBandLabel(it.celsius)}"
            } ?: "未检测到可读的系统温度传感器",
            active = temperatureSample?.celsius?.let { it >= 45f } ?: false,
            known = temperatureSample != null,
            tone = temperatureSample?.let { chatTemperatureTone(it.celsius) }
                ?: ChatDeviceStatusTone.UNKNOWN
        )
    )
}
