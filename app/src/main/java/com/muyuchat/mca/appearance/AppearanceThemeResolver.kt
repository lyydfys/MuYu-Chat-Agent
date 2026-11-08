package com.muyuchat.mca.appearance

/**
 * Turns "the system says dark" plus "the user chose X" into the single boolean a theme needs.
 *
 * Android- and Compose-free on purpose. The whole truth table is a pure function, so every
 * combination is pinned by a plain JVM unit test instead of a rendering test. It lives apart from
 * [AppearanceController] because the controller owns *when* the value changes (a write, a tap) while
 * this object owns *what* the value is, and a second consumer (a preview, a screenshot harness, a
 * future widget) must ask the same function rather than re-derive the table and drift.
 */
internal object AppearanceThemeResolver {

    /**
     * Resolves the scheme the app must paint.
     *
     * @param systemDark the platform's current dark-mode setting (the value Compose exposes as
     *   `isSystemInDarkTheme()`), read by the caller so this function stays pure.
     * @param mode the user's persisted choice.
     * @return true when the dark scheme must be used. [AppearanceThemeMode.SYSTEM] defers to
     *   [systemDark]; the pinned modes ignore it entirely, which is what makes "固定浅色" mean the
     *   same thing on every device setting.
     */
    fun resolveDarkTheme(systemDark: Boolean, mode: AppearanceThemeMode): Boolean = when (mode) {
        AppearanceThemeMode.SYSTEM -> systemDark
        AppearanceThemeMode.LIGHT -> false
        AppearanceThemeMode.DARK -> true
    }
}
