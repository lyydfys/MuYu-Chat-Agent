package com.muyuchat.feature.dsh.workspace.ui

/**
 * Picture-in-picture entry point, owned by the app shell.
 *
 * The workspace module cannot call `Activity.enterPictureInPictureMode` itself (it is a library
 * module with no Activity), and threading a new parameter through every route would collide with the
 * other slices in flight. The app therefore publishes one process-scoped port here and the console
 * renders a row against it; no route signature changes.
 *
 * [enterShrunken] returns `null` on success, or a short Chinese explanation when PiP is unavailable
 * (older platform, no DSH page on screen, system refusal) so the row never fails silently.
 */
interface DshPipPort {
    /** True when the platform supports PiP and the DSH page is the visible content. */
    fun isAvailable(): Boolean

    /** Asks the shell to shrink the current DSH page into a floating window. */
    fun enterShrunken(): String?
}

/** Process-scoped holder for [DshPipPort]; set once by the app shell. */
object DshPipHost {
    @Volatile
    var port: DshPipPort? = null
}
