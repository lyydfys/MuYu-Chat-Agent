package com.muyuchat.mca.dsh.overlay

import android.content.Context
import com.muyuchat.core.dsh.contract.DshOverlayAppearance
import com.muyuchat.core.dsh.contract.DshOverlayAppearanceStore

/**
 * App-private persistence of the app-outside display preferences.
 *
 * One preference file, one JSON document: appearance, on/off and position are written together so a
 * crash between two keys can never produce a half-updated overlay. Every read is total — a corrupted
 * or truncated document decodes to [DshOverlaySettings] defaults, because a broken preference must
 * never stop the window from starting.
 *
 * Threading: [loadSettings] is a SharedPreferences read (an in-memory map after the process loaded
 * it once, exactly like the existing DSH floating-ball position store); [saveSettings] uses `apply()`,
 * which hands the disk write to the platform's writer thread. Neither blocks the main thread on
 * payload data.
 */
internal class DshOverlayStore(context: Context) : DshOverlayAppearanceStore {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadSettings(): DshOverlaySettings = runCatching {
        DshOverlaySettingsCodec.decode(preferences.getString(KEY_SETTINGS, null))
    }.getOrDefault(DshOverlaySettings())

    fun saveSettings(settings: DshOverlaySettings) {
        runCatching {
            preferences.edit()
                .putString(KEY_SETTINGS, DshOverlaySettingsCodec.encode(settings.normalized()))
                .apply()
        }
    }

    override fun load(): DshOverlayAppearance = loadSettings().appearance

    override fun save(appearance: DshOverlayAppearance) {
        saveSettings(loadSettings().copy(appearance = appearance))
    }

    private companion object {
        const val PREFERENCES_NAME = "dsh_overlay_display"
        const val KEY_SETTINGS = "settings_json"
    }
}
