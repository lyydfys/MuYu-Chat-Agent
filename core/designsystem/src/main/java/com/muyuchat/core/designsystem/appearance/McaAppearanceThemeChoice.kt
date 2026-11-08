package com.muyuchat.core.designsystem.appearance

/**
 * The three colour modes the appearance page offers.
 *
 * This enum exists in the design system rather than in `:app` because the section that renders it is a
 * design-system component; the app-side persisted preference
 * (`com.muyuchat.mca.appearance.AppearanceThemeMode`) maps onto it, which keeps this module free of any
 * `:app` or `:feature:` import.
 */
public enum class McaAppearanceThemeChoice(
    /** User-visible label. Kept here so the row and any future search index read one string. */
    public val label: String,
    /** Stable identifier; never localised and safe to persist or log. */
    public val wireName: String
) {
    SYSTEM("跟随系统", "system"),
    LIGHT("浅色", "light"),
    DARK("深色", "dark");

    public companion object {
        /** The mode a fresh install runs in, and the fallback for an unreadable preference. */
        public val Default: McaAppearanceThemeChoice = SYSTEM

        /** Resolves a stored wire name; unknown text falls back to [Default]. */
        public fun fromWire(raw: String?): McaAppearanceThemeChoice {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return Default
            return entries.firstOrNull { it.wireName.equals(text, ignoreCase = true) } ?: Default
        }
    }
}
