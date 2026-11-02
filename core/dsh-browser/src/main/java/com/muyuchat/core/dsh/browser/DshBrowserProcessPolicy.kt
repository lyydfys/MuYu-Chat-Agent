package com.muyuchat.core.dsh.browser

/** Process mode the App composition actually gets for the browser. */
enum class DshBrowserProcessMode(val wire: String) {
    /** The `:browser` service is declared, the WebView host can be created. */
    ISOLATED("isolated"),

    /** The `:browser` service is declared but the platform precondition is missing. */
    DECLARED_UNAVAILABLE("declared-unavailable"),

    /** No `:browser` process declaration: only a same-process host could be used. */
    SHARED("shared")
}

/** Typed isolation decision; [reason] is a fixed sentence, never device or user data. */
data class DshBrowserIsolationState(
    val mode: DshBrowserProcessMode,
    val processName: String?,
    val overlayAvailable: Boolean,
    val reason: String?
) {
    /** True only when the WebView can actually be hosted in the isolated process. */
    val canHostWebView: Boolean
        get() = mode == DshBrowserProcessMode.ISOLATED

    val processIsolation: Boolean
        get() = mode == DshBrowserProcessMode.ISOLATED
}

/**
 * Pure decision logic for "can this build host the browser in its own process?".
 *
 * The pinned reference runs the browser `WebView` in `:browser`, but a process without an Activity
 * has no window to lay a `WebView` out in: it needs `SYSTEM_ALERT_WINDOW` to add an invisible
 * overlay window. That precondition is a *user-granted* permission, so the honest answer is a typed
 * degradation, not a silent same-process fallback that would claim isolation it does not have.
 *
 * The profile rules are pure data too: one data-directory suffix per process (Android forbids two
 * processes sharing one WebView data directory) and a session-only cookie policy cleared on close.
 */
object DshBrowserProcessPolicy {

    /** Manifest declaration of this module's service (`android:process=":browser"`). */
    const val PROCESS_NAME = ":browser"

    /** WebView data directory suffix, unique to `:browser` (never the DSH WebView's profile). */
    const val DATA_DIRECTORY_SUFFIX = "dsh-browser"

    /** Fully qualified service class the app composition binds to. */
    const val SERVICE_CLASS = "com.muyuchat.core.dsh.browser.DshBrowserService"

    /** Permission the invisible layout window needs when no Activity exists in `:browser`. */
    const val OVERLAY_PERMISSION = "android.permission.SYSTEM_ALERT_WINDOW"

    /** Cookies are accepted for the session but never persisted past `close()`. */
    const val SESSION_COOKIES_ONLY = true

    /** Third-party cookies stay off (a page cannot pull a session from another origin). */
    const val ALLOW_THIRD_PARTY_COOKIES = false

    fun decide(
        processDeclared: Boolean,
        overlayGranted: Boolean,
        engineAvailable: Boolean
    ): DshBrowserIsolationState {
        if (!processDeclared) {
            return DshBrowserIsolationState(
                mode = DshBrowserProcessMode.SHARED,
                processName = null,
                overlayAvailable = overlayGranted,
                reason = "the :browser process is not declared in this build"
            )
        }
        if (!overlayGranted) {
            return DshBrowserIsolationState(
                mode = DshBrowserProcessMode.DECLARED_UNAVAILABLE,
                processName = PROCESS_NAME,
                overlayAvailable = false,
                reason = "the layout overlay permission is not granted, so :browser cannot host a WebView"
            )
        }
        if (!engineAvailable) {
            return DshBrowserIsolationState(
                mode = DshBrowserProcessMode.DECLARED_UNAVAILABLE,
                processName = PROCESS_NAME,
                overlayAvailable = true,
                reason = "the WebView provider is missing in the :browser process"
            )
        }
        return DshBrowserIsolationState(
            mode = DshBrowserProcessMode.ISOLATED,
            processName = PROCESS_NAME,
            overlayAvailable = true,
            reason = null
        )
    }
}
