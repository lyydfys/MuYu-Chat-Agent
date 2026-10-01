package com.muyuchat.core.engine

import org.json.JSONArray

/**
 * Collects the non-blank string entries of a JSON array, preserving order.
 *
 * One implementation, in one place. This same function existed as a private copy in
 * ChatTypes and in api:local's OpenAiApiCompat, body for body. Both sit on a wire path -- chat
 * type conversion on one side, OpenAI-compatible request/response mapping on the other -- so a
 * divergence would mean the same JSON array is interpreted differently depending on which path
 * read it.
 *
 * Semantics, unchanged from both copies:
 *   - entries are read with optString, so a non-string entry is coerced rather than skipped
 *   - blank entries are dropped, so `["a", "", "  ", "b"]` yields ["a", "b"]
 *   - order is preserved
 *
 * Deliberately NOT unified with a third copy in app/WebSearchProvider.kt. That one is not the
 * same function: it applies `normalizeWhitespace()` to each entry before the blank check, so it
 * collapses internal runs of whitespace and would turn "a  b" into "a b". Folding the three
 * together would either start collapsing whitespace on the two wire paths or stop doing it on
 * the search path, and both are behaviour changes. The two real copies are unified; the third is
 * recorded here so the difference is visible rather than looking like an oversight.
 */
public fun JSONArray.toStringList(): List<String> = buildList {
    for (index in 0 until length()) {
        val value = optString(index)
        if (value.isNotBlank()) add(value)
    }
}
