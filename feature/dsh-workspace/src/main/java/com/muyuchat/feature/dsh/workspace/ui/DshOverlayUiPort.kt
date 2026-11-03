package com.muyuchat.feature.dsh.workspace.ui

import android.net.Uri
import com.muyuchat.core.dsh.contract.DshOverlayAppearance

/**
 * Display snapshot of the app-outside display, as the workspace settings panel renders it.
 *
 * [permissionGranted] is the live `SYSTEM_ALERT_WINDOW` grant; [enabled] is the user's own switch,
 * which can be on while the grant is missing (the panel then explains how to grant it).
 */
data class DshOverlayUiSnapshot(
    val enabled: Boolean = false,
    val permissionGranted: Boolean = false,
    val backgroundRunning: Boolean = false,
    val appearance: DshOverlayAppearance = DshOverlayAppearance(),
    /** A custom image is selected but its private file is missing or was never committed. */
    val customImageMissing: Boolean = false
)

/**
 * The workspace's narrow port onto the app-owned overlay.
 *
 * The feature module renders and collects intents; the app owns the window service, the foreground
 * service, the image copy and the persistence. Every mutating call returns `null` on success or a
 * short Chinese explanation to show in a snackbar, so a refusal is never silent.
 *
 * [importCustomImage] is `suspend` because it copies and decodes the picked file: that is disk work and
 * must not run on the main thread.
 */
interface DshOverlayUiPort {
    fun snapshot(): DshOverlayUiSnapshot

    /** Enables/disables the whole app-outside display (window + background keep-alive). */
    fun setEnabled(enabled: Boolean): String?

    /** Persists an edited appearance and applies it to the live window, if attached. */
    fun saveAppearance(appearance: DshOverlayAppearance): String?

    /** Copies a picked image into app-private storage and selects it as the custom mark. */
    suspend fun importCustomImage(uri: Uri): String?

    /** The explicit "停止后台运行" intent: window removed, keep-alive service stopped. */
    fun stopBackground(): String?
}
