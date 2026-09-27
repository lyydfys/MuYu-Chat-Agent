package com.muyuchat.feature.chat

/** A numbered Markdown item retaining the number emitted by the model. */
internal data class OrderedMarkdownItem(
    val number: Int,
    val text: String
)

/*
 * Keep the ordered-list grammar in one place. Local models commonly omit the
 * space after the marker and Chinese models prefer `、`, while a decimal such
 * as `1.23` must remain prose.
 */
private val ORDERED_MARKDOWN_ITEM_PATTERN = Regex(
    """^\s*(\d{1,4})[.)、](?=\s|[^\d\s])\s*(.+?)\s*$"""
)

private val EMBEDDED_ORDERED_MARKER_PATTERN = Regex(
    """\s+(?=\d{1,4}[.)、](?=\s|[^\d\s]))"""
)

/** True when this is an ordered Markdown row in one of MCA's accepted forms. */
internal fun String.isOrderedMarkdownLine(): Boolean =
    ORDERED_MARKDOWN_ITEM_PATTERN.matches(this)

/** Split rows emitted on one line without changing decimal numbers. */
internal fun splitEmbeddedOrderedMarkdownRows(value: String): String =
    value.replace(EMBEDDED_ORDERED_MARKER_PATTERN, "\n")

/** Remove an ordered marker while preserving the rest of the line. */
internal fun stripOrderedMarkdownMarker(value: String): String =
    ORDERED_MARKDOWN_ITEM_PATTERN.matchEntire(value)?.groupValues?.get(2)?.trim()
        ?: value.trimStart()

/** Parse an ordered-list marker without renumbering the source. */
internal fun parseOrderedMarkdownItem(value: String, fallbackNumber: Int): OrderedMarkdownItem? {
    val match = ORDERED_MARKDOWN_ITEM_PATTERN
        .matchEntire(value) ?: return null
    val number = match.groupValues[1].toIntOrNull()?.coerceAtLeast(1) ?: fallbackNumber.coerceAtLeast(1)
    return OrderedMarkdownItem(number = number, text = match.groupValues[2].trim())
}
