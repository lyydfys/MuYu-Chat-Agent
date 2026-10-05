package com.muyuchat.mca

import com.muyuchat.core.tuning.TuningSearchDepth
import java.util.Locale

// R1/S4: 从 MainViewModel 移出的调试模式标签枚举与调优深度映射。逐字迁移；
// 枚举解析（LocalApiControlPlaneOperations / localApiDebugMode）与 tuning 流程共用同一 owner。
internal enum class AgentDebugMode(val label: String) {
    Quick("快速调试"),
    Standard("标准调试"),
    Deep("深度调试"),
    PowerSave("省电调试")
}

internal fun AgentDebugMode.searchDepth(): TuningSearchDepth = when (this) {
    AgentDebugMode.Quick -> TuningSearchDepth.QUICK
    AgentDebugMode.Standard -> TuningSearchDepth.STANDARD
    AgentDebugMode.Deep -> TuningSearchDepth.DEEP
    AgentDebugMode.PowerSave -> TuningSearchDepth.POWER_SAVE
}

// R1/S14: Local API 调试模式字符串解析（从 MainViewModel 逐字移出；纯函数，可直测）。
internal fun agentDebugModeFromLocalApiValue(value: String): AgentDebugMode? = when (
    value.trim().lowercase(Locale.ROOT).replace("-", "_")
) {
    "quick" -> AgentDebugMode.Quick
    "standard" -> AgentDebugMode.Standard
    "deep", "full" -> AgentDebugMode.Deep
    "power", "power_save", "powersave" -> AgentDebugMode.PowerSave
    else -> null
}

// R1/S5: 从 MainViewModel 抽出的调优 ETA 纯公式（历史读取留在 VM，判定逻辑收敛于此，逐字等价）。
internal fun tuningEtaSecondsFromPreviousLoad(previousLoadMs: Long?, mode: AgentDebugMode): Long {
    val previous = previousLoadMs?.takeIf { it > 0 }
    val multiplier = when (mode) {
        AgentDebugMode.Quick -> 1.5
        AgentDebugMode.Standard -> 2.0
        AgentDebugMode.Deep -> 2.5
        AgentDebugMode.PowerSave -> 1.8
    }
    return ((previous ?: 30_000L) * multiplier / 1000.0).toLong().coerceAtLeast(15L)
}
