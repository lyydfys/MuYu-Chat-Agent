package com.muyuchat.mca.video.settings

import android.content.Context
import com.muyuchat.feature.video.workspace.VideoExperimentalEnginePreference

/**
 * SharedPreferences store for the video-engine switch.
 *
 * Default is ON: a fresh install runs the external stable-diffusion.cpp engine. Turning the switch
 * off persists `false` and falls back to the built-in MCA engine. The switch only decides which
 * engine future submits freeze into their run snapshot.
 */
internal class VideoEnginePreferenceStore(context: Context) : VideoExperimentalEnginePreference {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isExternalEngineEnabled(): Boolean =
        preferences.getBoolean(KEY_EXTERNAL_ENGINE, true)

    override fun setExternalEngineEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_EXTERNAL_ENGINE, enabled).apply()
    }

    override fun isFullVaeEnabled(): Boolean =
        preferences.getBoolean(KEY_FULL_VAE, true)

    override fun setFullVaeEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_FULL_VAE, enabled).apply()
    }

    companion object {
        const val PREFS_NAME: String = "mca_video_settings"
        const val KEY_EXTERNAL_ENGINE: String = "external_engine_enabled"
        const val KEY_FULL_VAE: String = "full_vae_enabled"
    }
}
