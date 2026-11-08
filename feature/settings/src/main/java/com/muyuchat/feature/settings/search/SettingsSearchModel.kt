package com.muyuchat.feature.settings.search

import com.muyuchat.feature.settings.SettingsSection

/**
 * Which part of a [SettingsSearchEntry] a query matched.
 *
 * [weight] is the base rank `searchSettings` sorts by. The ordering rule ("title before keyword
 * before group") is expressed here once so the matcher and its tests cannot drift apart. The
 * weights are spaced by ten on purpose: [TITLE] adds two finer sub-tiers in the matcher (exact
 * match and prefix match) without ever reaching a keyword hit.
 */
internal enum class SettingsSearchField {
    TITLE,
    KEYWORD,
    GROUP;

    /** Base rank of this field. Higher sorts first. */
    fun weight(): Int = when (this) {
        TITLE -> 30
        KEYWORD -> 20
        GROUP -> 10
    }
}

/**
 * Resolves a stored or logged section wire name onto the real [SettingsSection].
 *
 * This is a top-level function rather than `SettingsSection.Companion.fromWire(...)`: a Kotlin enum
 * class only has a companion object when it declares one, and [SettingsSection] declares none. An
 * extension on `SettingsSection.Companion` therefore does not compile against this enum, and adding
 * a companion to it would put a search-layer parser back inside the dashboard file. This package is
 * the only caller, so a named function keeps the same import surface with no companion at all.
 *
 * Unknown text falls back to the section the dashboard lists first - the page a fresh install opens
 * on - instead of throwing: a section name read back from disk or a log line is untrusted input, and
 * a stale value must not crash a settings page.
 */
internal fun settingsSectionFromWire(raw: String?): SettingsSection {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return SettingsSection.entries.first()
    return SettingsSection.entries.firstOrNull { it.wireName.equals(text, ignoreCase = true) }
        ?: SettingsSection.entries.first()
}

/** Offset between the full-width ASCII block (U+FF01..U+FF5E) and plain ASCII. */
private const val FULL_WIDTH_ASCII_OFFSET = 0xFEE0

/** Ideographic space; Chinese IMEs and copied text use it where ASCII has a normal space. */
private const val IDEOGRAPHIC_SPACE = '\u3000'

/** No-break space; web and clipboard text carries it where a normal space was meant. */
private const val NO_BREAK_SPACE = '\u00A0'

/**
 * Case- and width-folding step of query normalisation, without whitespace handling.
 *
 * Full-width digits, letters and punctuation are the common real-world input here: a Chinese
 * keyboard produces `１２３` and `ＲＵＮＴＩＭＥ`, which would otherwise never match the ASCII
 * identifiers stored in the index. Lower-casing uses `lowercase()` (locale-independent), not the
 * JVM default locale, so a Turkish device cannot fold `I` into a dotless `ı` and change results.
 */
internal fun foldSettingsSearchText(raw: String): String {
    if (raw.isEmpty()) return ""
    val folded = StringBuilder(raw.length)
    for (ch in raw) {
        folded.append(
            when {
                ch == IDEOGRAPHIC_SPACE || ch == NO_BREAK_SPACE -> ' '
                ch in '\uFF01'..'\uFF5E' -> (ch.code - FULL_WIDTH_ASCII_OFFSET).toChar()
                else -> ch
            }
        )
    }
    return folded.toString().lowercase()
}

/**
 * Splits an already-normalised query into the terms the matcher must all find.
 *
 * A query such as `日志 log` is two terms: matching the whole string literally would fail even
 * though both halves name the same row. Chinese queries usually arrive without spaces, so a
 * single term is the normal case and simply participates in substring matching.
 */
internal fun settingsSearchNeedles(normalizedQuery: String): List<String> =
    normalizedQuery.split(' ').filter { it.isNotEmpty() }
