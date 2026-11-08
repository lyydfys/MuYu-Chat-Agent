package com.muyuchat.core.designsystem.onboarding

/**
 * Which install shape the first-run flow offers.
 *
 * This mirrors `:app`'s persisted choice by name only. It is declared here so the pages render without
 * importing `:app` (a `:feature:`/`:core:` module may not depend on it), and the composition root maps
 * one onto the other.
 */
public enum class McaOnboardingPathChoice(
    /** Row title. */
    public val label: String,
    /** One-line explanation of the tradeoff, shown under the label. */
    public val summary: String,
    /** Stable identifier; never localised. */
    public val wireName: String
) {
    EXPERIENCE(
        "本机运行，功能完整",
        "在手机上安装运行环境，聊天、生图、Agent 都能离线用。首次准备需要一两分钟和约 1 GB 空间。",
        "experience"
    ),
    PRODUCTION(
        "先只用手上的功能",
        "不安装运行环境，直接使用模型对话与图像功能。之后随时可以在设置里补装。",
        "production"
    );

    public companion object {
        /** Highlighted first; the flow never preselects, so the user must answer. */
        public val Recommended: McaOnboardingPathChoice = EXPERIENCE

        public fun fromWire(raw: String?): McaOnboardingPathChoice? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            return entries.firstOrNull { it.wireName.equals(text, ignoreCase = true) }
        }
    }
}

/**
 * The page the first-run flow is showing.
 *
 * The ordinal is the step number in the progress row, so declaration order is user-visible.
 */
public enum class McaOnboardingStep(val title: String, val subtitle: String) {
    WELCOME(
        "欢迎使用 MCA",
        "手机本地运行大模型的聊天应用。接下来三步把环境准备好，之后不再打扰你。"
    ),
    PATH(
        "选择运行方式",
        "两种方式共用同一套模型与聊天记录，区别只是运行环境装不装在手机里。"
    ),
    MODEL(
        "准备一个模型",
        "可以现在下载一个推荐模型，也可以跳过，稍后在「模型管理」里挑。"
    ),
    DONE(
        "可以开始了",
        "设置随时能在「系统设置」里改。遇到问题时，聊天页会直接告诉你下一步该做什么。"
    )
}
