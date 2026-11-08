package com.muyuchat.feature.settings.search

import com.muyuchat.feature.settings.SettingsSection

/**
 * One searchable row of the settings surface: the "index" side of "the row and the index come from
 * the same constant table".
 *
 * [spotId] is the jump anchor. After a hit, the settings UI opens [section] and scrolls to, then
 * highlights, the row carrying this id. It is a stable ASCII identifier and is treated as a
 * persisted contract, not as a convenience key:
 *
 * - **Never reuse an id.** A retired id may still sit in a saved quick-search, a bug report or a
 *   log line; handing it to a different row would silently point a reader at the wrong place.
 * - **Never rename an id.** To every reader that stored it, a rename is indistinguishable from a
 *   deletion, so the old name must keep resolving for the lifetime of the schema.
 * - **Retire by deletion.** Removing a row deletes its id from [SettingsSearchEntries]; that id is
 *   then burnt and must never be assigned again.
 *
 * [title] is the string the user actually reads on that row. [keywords] carries synonyms, English
 * terms, old names and abbreviations that should find the row even though they are not printed on
 * screen. [group] is the heading the result is listed under, so it doubles as a searchable term.
 *
 * [section] is the dashboard's own [SettingsSection] - the enum that decides render order and the
 * card a hit opens. Search does not define a mirror: one enum means the hub card list and the index
 * group list cannot drift apart.
 */
internal data class SettingsSearchEntry(
    /** Stable jump anchor. See the contract above before adding, changing or removing one. */
    val spotId: String,
    /** Owning dashboard section. */
    val section: SettingsSection,
    /** The visible row title (Chinese, as rendered). */
    val title: String,
    /** Synonyms, aliases, English terms and retired names used for matching only. */
    val keywords: List<String>,
    /** Result-group heading. */
    val group: String
)
