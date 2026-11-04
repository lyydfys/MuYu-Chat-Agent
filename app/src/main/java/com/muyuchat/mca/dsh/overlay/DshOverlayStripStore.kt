package com.muyuchat.mca.dsh.overlay

import android.content.Context

/**
 * App-private persistence of the top streaming strip.
 *
 * Kept in its own preference file (`dsh_overlay_strip`, as the feature spec names it) so the strip can
 * be reset or corrupt independently of the ball document. Reads and writes are total: a broken
 * document decodes to [DshOverlayStripSettings] defaults and never blocks the overlay.
 */
internal class DshOverlayStripStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadSettings(): DshOverlayStripSettings = runCatching {
        DshOverlayStripCodec.decode(preferences.getString(KEY_SETTINGS, null))
    }.getOrDefault(DshOverlayStripSettings())

    fun saveSettings(settings: DshOverlayStripSettings) {
        runCatching {
            preferences.edit()
                .putString(KEY_SETTINGS, DshOverlayStripCodec.encode(settings.normalized()))
                .apply()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "dsh_overlay_strip"
        const val KEY_SETTINGS = "settings_json"
    }
}
