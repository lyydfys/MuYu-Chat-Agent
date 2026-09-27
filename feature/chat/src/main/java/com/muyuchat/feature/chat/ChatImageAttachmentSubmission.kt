package com.muyuchat.feature.chat

import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.visionDeduplicationKey

private val chatImageAttachmentWithUriPattern = Regex(
    """【上传图片：([^】]+)】(?:\s*\n描述：[^\n]+)?\s*\n(\S+)"""
)

/**
 * Deduplicates image markers before the chat callback persists a user message.
 * The identity provider may hash content:// data on an IO dispatcher; inaccessible URIs fall back
 * to the engine's stable URI/path identity so a failed read never drops a possibly distinct image.
 */
internal fun deduplicateChatImageAttachmentMarkers(
    input: String,
    identityForUri: (String) -> String?
): String {
    val matches = chatImageAttachmentWithUriPattern.findAll(input).toList()
    if (matches.size < 2) return input

    val seen = LinkedHashSet<String>(matches.size)
    val duplicateRanges = matches.filter { match ->
        val uri = match.groupValues.getOrNull(2).orEmpty().trim()
        if (uri.isBlank()) return@filter false
        val identity = attachmentIdentity(uri, identityForUri)
        !seen.add(identity)
    }.map { it.range }
    if (duplicateRanges.isEmpty()) return input

    return buildString(input.length) {
        var cursor = 0
        duplicateRanges.forEach { range ->
            append(input, cursor, range.first)
            cursor = range.last + 1
        }
        append(input, cursor, input.length)
    }
}

/** Uses the same identity contract for persisted bubble attachments and outgoing marker text. */
internal fun deduplicateChatImageAttachments(
    attachments: List<ChatImageAttachment>,
    identityForUri: (String) -> String?
): List<ChatImageAttachment> {
    if (attachments.size < 2) return attachments
    val seen = LinkedHashSet<String>(attachments.size)
    return attachments.filter { attachment ->
        seen.add(attachmentIdentity(attachment.uriString, identityForUri))
    }
}

internal fun chatImageAttachmentMarkerCount(input: String): Int =
    chatImageAttachmentWithUriPattern.findAll(input).count()

private fun attachmentIdentity(
    uri: String,
    identityForUri: (String) -> String?
): String = runCatching { identityForUri(uri) }
    .getOrNull()
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?: ChatImageAttachment(uriString = uri).visionDeduplicationKey()
