package com.muyuchat.mca

import com.muyuchat.core.tuning.TuningSearchDepth

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
