package com.muyuchat.mca

enum class AppTab(val title: String) {
    CHAT("聊天"),
    AGENT("智能调参"),
    MODELS("模型管理"),
    API("本地 API"),
    SETTINGS("系统设置"),

    /**
     * Independent Agent workspace (architecture 13.1). Appended last on purpose: no existing enum
     * ordinal or persistence mapping is renamed or shifted. Chat history tab restore never stores
     * the ordinal (verified in the navigation evidence), so appending is safe.
     */
    AGENT_WORKSPACE("Agent"),

    /**
     * Independent official DSH mode (proposal agent-dsh-embedded-v1). Appended after
     * AGENT_WORKSPACE on purpose: no existing enum ordinal, persistence mapping or tab key is
     * renamed or shifted. DSH keeps its own official kernel, frontend and session data.
     */
    DSH_WORKSPACE("DSH"),

    /**
     * Native video workflow workspace (proposal mca-native-video-workflow-v1). Appended after
     * DSH_WORKSPACE on purpose: no existing enum ordinal, persistence mapping or tab key is
     * renamed or shifted. The video page owns templates/node editing/run views only; execution
     * belongs to the process-level video owner and is injected via the video contract port.
     */
    VIDEO_WORKSPACE("视频")
}

/**
 * The tab a late chat-model load completion must land on. Overlay workspaces that host their own
 * long-running state (official DSH, video runs) are NOT ejected to CHAT when a chat model
 * finishes loading in the background: the DSH host must stay composed for its gateway refresh,
 * and an in-flight video run must keep its page. Every other tab keeps the established chat
 * landing.
 */
fun tabAfterLateModelLoad(current: AppTab): AppTab = when (current) {
    AppTab.DSH_WORKSPACE, AppTab.VIDEO_WORKSPACE -> current
    else -> AppTab.CHAT
}
