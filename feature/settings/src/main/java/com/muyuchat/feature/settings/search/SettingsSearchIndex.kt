package com.muyuchat.feature.settings.search

import com.muyuchat.feature.settings.SettingsSection

/** One search hit: the row that matched and the field the match landed in. */
internal data class SettingsSearchHit(
    val entry: SettingsSearchEntry,
    val matchedField: SettingsSearchField
)

/**
 * Normalises raw user input into the form the matcher compares against.
 *
 * Full-width characters are folded to ASCII (a Chinese IME emits `ＲＵＮＴＩＭＥ` and `１２３`),
 * runs of whitespace collapse to one space, edges are trimmed, and the result is lower-cased with
 * the locale-independent `lowercase()`. Pure and total: every input maps to a string, so callers
 * never need a try/catch.
 */
internal fun normalizeSettingsQuery(raw: String): String =
    foldSettingsSearchText(raw).trim().replace(WHITESPACE_RUN, " ")

/** One or more whitespace characters. */
private val WHITESPACE_RUN = Regex("\\s+")

/**
 * Ranks [entries] against [query], strongest match first.
 *
 * Ranking, highest first: exact title, title prefix, title substring, any keyword substring, group
 * substring. A query with several space-separated terms must have every term found, and the entry
 * is ranked by its strongest single term. Ties break on [SettingsSection.ordinal] - the dashboard's
 * own render order - and then [SettingsSearchEntry.spotId], which makes the order total and
 * reproducible across runs: there is no reliance on the input list order or on sort stability.
 *
 * Blank input and queries that match nothing both return an empty list; nothing throws.
 */
internal fun searchSettings(
    query: String,
    entries: List<SettingsSearchEntry> = SettingsSearchEntries
): List<SettingsSearchHit> {
    val normalized = normalizeSettingsQuery(query)
    if (normalized.isEmpty()) return emptyList()
    val needles = settingsSearchNeedles(normalized)
    if (needles.isEmpty()) return emptyList()
    return entries
        .mapNotNull { entry ->
            val match = rankEntry(entry, needles) ?: return@mapNotNull null
            RankedHit(SettingsSearchHit(entry, match.field), match.rank)
        }
        .sortedWith(
            compareByDescending<RankedHit> { it.rank }
                .thenBy { it.hit.entry.section.ordinal }
                .thenBy { it.hit.entry.spotId }
        )
        .map { it.hit }
}

/** A hit plus the score it sorted by; the rank is matcher-internal, not part of the public model. */
private class RankedHit(val hit: SettingsSearchHit, val rank: Int)

/** The winning field and score of one entry against all needles, or null when any needle misses. */
private class FieldMatch(val field: SettingsSearchField, val rank: Int)

private fun rankEntry(entry: SettingsSearchEntry, needles: List<String>): FieldMatch? {
    var best: FieldMatch? = null
    for (needle in needles) {
        val match = rankNeedle(entry, needle) ?: return null
        if (best == null || match.rank > best.rank) best = match
    }
    return best
}

/**
 * Ranks one needle against one entry. The title tiers add +2 (exact) and +1 (prefix) on top of
 * [SettingsSearchField.TITLE], so a title hit can never be overtaken by a keyword hit.
 */
private fun rankNeedle(entry: SettingsSearchEntry, needle: String): FieldMatch? {
    val title = normalizeSettingsQuery(entry.title)
    if (title == needle) return FieldMatch(SettingsSearchField.TITLE, SettingsSearchField.TITLE.weight() + 2)
    if (title.startsWith(needle)) return FieldMatch(SettingsSearchField.TITLE, SettingsSearchField.TITLE.weight() + 1)
    if (title.contains(needle)) return FieldMatch(SettingsSearchField.TITLE, SettingsSearchField.TITLE.weight())
    if (entry.keywords.any { normalizeSettingsQuery(it).contains(needle) }) {
        return FieldMatch(SettingsSearchField.KEYWORD, SettingsSearchField.KEYWORD.weight())
    }
    if (normalizeSettingsQuery(entry.group).contains(needle)) {
        return FieldMatch(SettingsSearchField.GROUP, SettingsSearchField.GROUP.weight())
    }
    return null
}
