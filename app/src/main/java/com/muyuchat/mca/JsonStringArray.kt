package com.muyuchat.mca

import org.json.JSONArray

/**
 * Converts a list of strings into a JSON array.
 *
 * One implementation, in one place. The same two-line function existed twice in this package,
 * private and byte-identical, in ChatSessionStore and WebSearchProvider. Both write lists of
 * strings into persisted or transmitted JSON, so a divergence would mean one side of the app
 * writes an array differently from the other.
 *
 * Kept in this package rather than moved to a shared module: both callers are here, so the
 * move needs no imports and no dependency changes. That is the situation to prefer -- an
 * extraction that stays inside its package cannot get the visibility or the import wrong.
 *
 * Semantics, unchanged from both copies: order is preserved, and an empty list yields an empty
 * array rather than null.
 */
internal fun List<String>.toJsonArray(): JSONArray =
    JSONArray().also { array -> forEach { value -> array.put(value) } }
