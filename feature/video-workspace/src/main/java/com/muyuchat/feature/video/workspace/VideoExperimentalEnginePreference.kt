package com.muyuchat.feature.video.workspace

/**
 * Persistence seam for the video-backend switch.
 *
 * The composition root owns the concrete store (SharedPreferences) so the page state holder stays
 * free of Android context, and the default is ON: the external engine is the product path while
 * the built-in MCA engine remains the fallback when the user turns the switch off. The value only
 * decides which engine future submits freeze into their run snapshot; it never rewrites history.
 */
interface VideoExperimentalEnginePreference {
    fun isExternalEngineEnabled(): Boolean

    fun setExternalEngineEnabled(enabled: Boolean)

    /**
     * Full Wan VAE decode (spatial-tiled). On by default: it is the product decode path (~30-40
     * minutes per 480p clip) and the user can switch back to the TAE head (~6 minutes).
     */
    fun isFullVaeEnabled(): Boolean = true

    fun setFullVaeEnabled(enabled: Boolean) {}
}
