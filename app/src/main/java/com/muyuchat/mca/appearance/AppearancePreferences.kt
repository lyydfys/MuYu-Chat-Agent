package com.muyuchat.mca.appearance

import android.content.Context

/**
 * Persisted appearance preference: which colour mode the app paints in.
 *
 * This is deliberately a *separate* store from `mca_chat_appearance`
 * ([com.muyuchat.mca.GlobalChatAppearanceStore], which owns the chat background image). Mixing a
 * window-level mode into the per-conversation background record would mean every future migration of
 * one touches the other, and the background record is written on a different cadence (per user edit of
 * an image) than the theme toggle.
 *
 * Storage is a tiny key/value port so the codec is testable without Android; the production adapter is
 * SharedPreferences. Values are validated on read *and* write: a hand-edited preference can never put
 * the app into a mode this build does not implement.
 */
internal enum class AppearanceThemeMode(val wireName: String) {
    /** Follow the system dark/light setting. The default, and what every build did before this page existed. */
    SYSTEM("system"),

    /** Always paint the light scheme, even when the system is dark. */
    LIGHT("light"),

    /** Always paint the dark scheme, even when the system is light. */
    DARK("dark");

    companion object {
        /**
         * Parses a stored value, falling back to [SYSTEM] for anything unrecognised.
         *
         * Failing open to [SYSTEM] is what keeps a corrupted preference from locking a user into a
         * scheme they cannot see well: [SYSTEM] is the behaviour of every prior build.
         */
        fun fromWire(raw: String?): AppearanceThemeMode {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return SYSTEM
            return values().firstOrNull { it.wireName.equals(text, ignoreCase = true) } ?: SYSTEM
        }
    }
}

/** Minimal key/value seam over SharedPreferences. */
internal interface AppearancePreferenceStore {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

/**
 * The real store. [name] is fixed to [PREFERENCES_NAME] by the factory below so no caller can
 * accidentally point an appearance read at another file.
 */
internal class SharedPreferencesAppearanceStore(
    context: Context,
    name: String = PREFERENCES_NAME
) : AppearancePreferenceStore {
    private val preferences =
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun read(key: String): String? = preferences.getString(key, null)

    override fun write(key: String, value: String?) {
        preferences.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }

    internal companion object {
        /** Kept apart from `mca_chat_appearance`; see the type KDoc. */
        const val PREFERENCES_NAME: String = "mca_appearance"
    }
}

/** Reads and writes the appearance preference over an [AppearancePreferenceStore]. */
internal class AppearancePreferences(private val store: AppearancePreferenceStore) {

    fun loadThemeMode(): AppearanceThemeMode =
        AppearanceThemeMode.fromWire(store.read(KEY_THEME_MODE))

    fun saveThemeMode(mode: AppearanceThemeMode) {
        // Only this key is written: a future second appearance preference must not be cleared by a
        // theme change, which is why the store is never rewritten wholesale.
        store.write(KEY_THEME_MODE, mode.wireName)
    }

    internal companion object {
        const val KEY_THEME_MODE: String = "appearance_theme_mode"
    }
}
