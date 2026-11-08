package com.muyuchat.feature.chat

/**
 * Which composer modes are on for the next turn.
 *
 * The three modes are mutually exclusive by design, not by accident:
 * - **agent** hands the turn to the Agent runtime, which owns the tool loop, approvals and its own
 *   retry/repair path;
 * - **image prompt** submits the text to the image pipeline instead of the chat model;
 * - **research** changes how the *chat* model plans its web search.
 *
 * Running two at once has no defined meaning (an Agent task with an image-prompt submit target has no
 * owner; a research plan applied to an Agent turn would be silently discarded), so rather than letting
 * the UI show an impossible combination, enabling one switches the others off. The rule lives here as
 * a pure function so it is testable without Compose.
 */
data class ChatComposerModes(
    val agentMode: Boolean = false,
    val imagePromptMode: Boolean = false,
    val researchMode: Boolean = false
) {
    /**
     * Turns [mode] on and every other mode off, or turns [mode] off when it is already on.
     *
     * Toggling the *active* mode is always a plain off — it must not fall back to a different mode,
     * because the user's tap said "leave this mode", not "switch to another".
     */
    fun toggle(mode: ChatComposerMode): ChatComposerModes = when (mode) {
        ChatComposerMode.AGENT -> copy(
            agentMode = !agentMode,
            imagePromptMode = false,
            researchMode = false
        )

        ChatComposerMode.IMAGE_PROMPT -> copy(
            agentMode = false,
            imagePromptMode = !imagePromptMode,
            researchMode = false
        )

        ChatComposerMode.RESEARCH -> copy(
            agentMode = false,
            imagePromptMode = false,
            researchMode = !researchMode
        )
    }

    /** Explicitly leaving a mode (the banner close button, a completed send) never arms another. */
    fun disable(mode: ChatComposerMode): ChatComposerModes = when (mode) {
        ChatComposerMode.AGENT -> copy(agentMode = false)
        ChatComposerMode.IMAGE_PROMPT -> copy(imagePromptMode = false)
        ChatComposerMode.RESEARCH -> copy(researchMode = false)
    }

    /**
     * Whether any mode is armed. Used by the send path and by the placeholder, so they can never
     * disagree about whether this turn is "special".
     */
    val anyActive: Boolean get() = agentMode || imagePromptMode || researchMode

    companion object {
        /** Label/subtitle pairs for the `+` menu row, kept next to the state they describe. */
        fun agentMenuLabel(agentMode: Boolean): String =
            if (agentMode) "退出 Agent 模式" else "转为 Agent 任务"

        fun agentMenuSubtitle(agentMode: Boolean): String =
            if (agentMode) {
                "下一轮将由 Agent 执行，可调用工具并需要审批"
            } else {
                "把这一轮交给 Agent：可读写文件、操作设备"
            }

        fun composerPlaceholder(modes: ChatComposerModes, default: String = "问问 MCA"): String = when {
            modes.agentMode -> "描述你想让 Agent 做什么…"
            modes.imagePromptMode -> "描述要生成的图片"
            else -> default
        }
    }
}

/** The three mutually exclusive composer modes. */
enum class ChatComposerMode {
    AGENT,
    IMAGE_PROMPT,
    RESEARCH
}
