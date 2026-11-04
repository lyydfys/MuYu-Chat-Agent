package com.muyuchat.feature.chat

import androidx.compose.runtime.Immutable
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role

/** Maximum visible characters of a quoted message preview in the composer bar. */
const val CHAT_QUOTE_PREVIEW_CHARS: Int = 60

/** Upper bound for the quoted text that may enter a model request, independent of stored history. */
const val CHAT_QUOTE_CONTEXT_MAX_CHARS: Int = 2_000

/**
 * One request-scoped quote: the message the user chose to reply to.
 *
 * This is a composer/intent value, not chat history. It is sent with exactly one turn, folded into
 * that turn's model-facing content by [withQuotedContext], and never written into the stored
 * message content. Keeping it separate is what lets the composer show "回复 我/角色名" without
 * mutating the original message or the database.
 */
@Immutable
data class ChatQuotedMessage(
    val messageId: String = "",
    val role: Role = Role.USER,
    /** What the composer shows: "我" for the user, the selected assistant's name otherwise. */
    val authorLabel: String = "",
    /** Bounded original text; the model receives this exact text, not a paraphrase. */
    val content: String = ""
) {
    /** Single-line preview capped at [CHAT_QUOTE_PREVIEW_CHARS], with an ellipsis when shortened. */
    val preview: String
        get() {
            val normalized = content.replace(WHITESPACE, " ").trim()
            if (normalized.length <= CHAT_QUOTE_PREVIEW_CHARS) return normalized
            return normalized.take(CHAT_QUOTE_PREVIEW_CHARS - 1) + "…"
        }

    /**
     * Structured, delimited block for the model. It names the author and carries the original text
     * verbatim; it is not a natural-language "he just said" paraphrase and it is not persisted as
     * part of the user's visible message.
     */
    fun toModelContext(): String {
        val roleLabel = if (role == Role.ASSISTANT) "助手" else "用户"
        val author = authorLabel.trim().ifBlank { if (role == Role.ASSISTANT) "助手" else "我" }
        return buildString {
            append("[引用消息]")
            append('\n').append("作者：").append(author).append("（").append(roleLabel).append("）")
            append('\n').append("内容：")
            append('\n').append(content.trim())
            append('\n').append("[/引用消息]")
        }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

/**
 * Builds the quote value for [message], or null when there is no visible text to quote.
 *
 * The first 2,000 characters are kept so a very long assistant answer cannot blow the next request
 * by itself; the composer preview is shortened further by [ChatQuotedMessage.preview].
 */
fun chatQuotedMessageFor(message: ChatMessage, authorLabel: String): ChatQuotedMessage? {
    val text = displayInputWithoutAttachment(message.content).trim()
    val quoteText = text.ifEmpty {
        if (message.imageAttachments.isNotEmpty()) "（图片）" else return null
    }
    return ChatQuotedMessage(
        messageId = message.id,
        role = message.role,
        authorLabel = authorLabel.trim().ifBlank { if (message.role == Role.ASSISTANT) "助手" else "我" },
        content = quoteText.take(CHAT_QUOTE_CONTEXT_MAX_CHARS)
    )
}

/**
 * Returns this message with the quote's structured block prepended to its model-facing content.
 *
 * A null or blank quote returns the receiver unchanged, so a turn without a quote is byte-identical
 * to the pre-quote behaviour. The original content is never rewritten in place; the caller passes
 * this copy only into the request/admission path.
 */
fun ChatMessage.withQuotedContext(quote: ChatQuotedMessage?): ChatMessage {
    if (quote == null || quote.content.isBlank()) return this
    val quoted = quote.toModelContext()
    return copy(content = if (content.isBlank()) quoted else quoted + "\n\n" + content)
}
