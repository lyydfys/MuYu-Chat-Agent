package com.muyuchat.mca

/**
 * Encoding of the chat backend in a persisted session.
 *
 * `ChatSessionRecord.modelMode` is a database column, so the strings below are a stored
 * format rather than an internal detail: changing them would stop existing rows from
 * resolving their backend. [bindingValue] writes the value and [toChatBackendOrNull] reads
 * it back.
 *
 * ## Shared vocabulary
 *
 * An assistant can also bind a session to a model mode, and `MainViewModel` normalises that
 * to the literals `"local"` and `"cloud"` -- the same words [bindingValue] produces. The two
 * encodings therefore share one vocabulary on purpose, which is why a single reader serves
 * both. The sentinel `"follow_current"` means "use whatever is selected" and is never
 * written to the column; if it ever were, it reads back as null, which callers already treat
 * as "no stored preference".
 *
 * ## Why the reader is an explicit allow-list
 *
 * [toChatBackendOrNull] matches named literals instead of deriving them from the enum. That
 * is deliberate: adding a backend should require deciding whether previously stored rows may
 * resolve to it, rather than becoming readable the moment the enum gains a value. A test
 * pins the round trip so the two halves cannot drift apart.
 *
 * Extracted verbatim from `MainViewModel` during P2; neither the literals nor the leniency
 * were changed.
 */

/**
 * Value stored for this backend in a session's `modelMode` column.
 *
 * Lower-cased enum name: `"local"` or `"cloud"`.
 */
internal fun ChatBackend.bindingValue(): String =
    name.lowercase()

/**
 * Backend stored in a `modelMode` column value, or null when the column holds something else.
 *
 * Surrounding whitespace and letter case are ignored. Anything unrecognised -- a null or
 * blank column, the `"follow_current"` sentinel, or a value written by a newer version --
 * returns null, so callers fall back to the current selection instead of being handed the
 * wrong backend.
 */
internal fun String?.toChatBackendOrNull(): ChatBackend? =
    when (this?.trim()?.lowercase()) {
        "local" -> ChatBackend.LOCAL
        "cloud" -> ChatBackend.CLOUD
        else -> null
    }
