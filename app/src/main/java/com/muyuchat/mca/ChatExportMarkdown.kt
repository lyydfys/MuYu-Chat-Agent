package com.muyuchat.mca

import com.muyuchat.core.engine.Role

/**
 * The Markdown body MCA writes when a conversation is exported.
 *
 * This is an output format the user keeps, so its shape is a contract: a level-1 title, a
 * metadata block, then one level-2 section per message with the reasoning trace kept
 * separate from the final answer. `safeChatExportTitle` names the file; this produces what
 * goes inside it.
 *
 * Extracted verbatim from `MainViewModel` during P2; no heading, label, or separator was
 * changed.
 */

/**
 * Renders this conversation as Markdown.
 *
 * The export timestamp is read from the clock at call time, so two calls are not
 * byte-identical; everything else is a pure function of the record. Content is written
 * as-is, including Markdown the user or model produced; only a blank message is replaced
 * with a placeholder so the section is not left empty.
 */
internal fun ChatSessionRecord.toMarkdown(): String = buildString {
    append("# ").append(title).append("\n\n")
    append("- 导出时间：").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())).append("\n")
    append("- 消息数量：").append(messages.size).append("\n")
    append("- 会话 ID：").append(id).append("\n\n")
    messages.forEach { message ->
        append("## ").append(message.role.displayLabel()).append("\n\n")
        if (message.role == Role.ASSISTANT && message.reasoningContent.isNotBlank()) {
            append("### 思考过程")
            if (message.reasoningDurationMs > 0L) {
                append("（用时 ").append(message.reasoningDurationMs / 1000).append(" 秒）")
            }
            append("\n\n")
            append(message.reasoningContent).append("\n\n")
            append("### 最终回答\n\n")
        }
        append(message.content.ifBlank { "（空消息）" }).append("\n\n")
    }
}

/** Section heading used for each role in an exported conversation. */
internal fun Role.displayLabel(): String = when (this) {
    Role.SYSTEM -> "系统"
    Role.USER -> "用户"
    Role.ASSISTANT -> "MCA"
}
