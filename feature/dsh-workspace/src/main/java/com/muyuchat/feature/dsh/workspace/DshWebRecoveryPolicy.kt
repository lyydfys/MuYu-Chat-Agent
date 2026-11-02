package com.muyuchat.feature.dsh.workspace

/** Recovery action after a host/WebView lifecycle event. Never starts a second runtime. */
enum class DshWebRecoveryAction {
    /** Nothing to do; the page, renderer and runtime are unchanged. */
    NONE,

    /** Reload the same clean page in the WebView; this never starts a Node process or resends a turn. */
    RELOAD_CURRENT_PAGE
}

/**
 * Pure D25 recovery policy for the WebView container.
 *
 * Backgrounding a page is not cancellation: the runtime, DSH session and in-flight work stay
 * untouched. Only an interrupted page load (the system may discard a renderer while the app is
 * backgrounded) is reloaded on return, and a reload is a navigation of the same clean URL, not a
 * re-send of a turn and not a new runtime generation. Renderer death always recreates the WebView
 * against the same Node/session, so it maps to a reload as well; it never maps to a host restart.
 */
object DshWebRecoveryPolicy {

    /** Below this the page is treated as still warm; reloading would only flash and lose scroll state. */
    const val STALE_BACKGROUND_MILLIS = 30_000L

    fun onForeground(
        backgroundMillis: Long,
        loadingInterrupted: Boolean,
        hasWebPage: Boolean
    ): DshWebRecoveryAction {
        if (!hasWebPage) return DshWebRecoveryAction.NONE
        if (backgroundMillis < 0L) return DshWebRecoveryAction.NONE
        if (!loadingInterrupted) return DshWebRecoveryAction.NONE
        if (backgroundMillis < STALE_BACKGROUND_MILLIS) return DshWebRecoveryAction.NONE
        return DshWebRecoveryAction.RELOAD_CURRENT_PAGE
    }

    /** A dead renderer is recreated in place; the runtime and session are kept. */
    fun onRendererGone(): DshWebRecoveryAction = DshWebRecoveryAction.RELOAD_CURRENT_PAGE

    /**
     * The AndroidView host was recreated (rotation, tab re-entry, failure shell). A one-shot token
     * entry that has not been handed off must resume on the cookie, never replay the token URL.
     */
    fun onHostRecreated(authenticatedEntryPending: Boolean): DshWebRecoveryAction =
        if (authenticatedEntryPending) {
            DshWebRecoveryAction.RELOAD_CURRENT_PAGE
        } else {
            DshWebRecoveryAction.NONE
        }
}
